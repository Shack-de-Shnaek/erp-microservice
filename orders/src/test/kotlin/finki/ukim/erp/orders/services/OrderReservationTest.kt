package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.clients.FakeInventoryCatalog
import finki.ukim.erp.orders.commands.CreateOrderCommand
import finki.ukim.erp.orders.commands.UpdateOrderItemsCommand
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.ProductNotAvailableException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import finki.ukim.erp.orders.exceptions.StockNotReservedException
import finki.ukim.erp.orders.exceptions.StockReservationRejectedException
import finki.ukim.erp.orders.views.OrderView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Where an order stops being a hope and becomes a claim on real goods.
 *
 * Placing an order asks inventory to put its stock aside first, synchronously, and only then
 * creates the order - so an order that exists is always one something is being held for. These
 * tests are about the two things that follow from that ordering and are easy to get wrong: that
 * nothing is created when the stock cannot be had, and that nothing is *held* when the order
 * cannot be created.
 *
 * The command gateway is a mock and inventory is a fake that remembers what it is holding, because
 * what is under test is the sequence of calls between them. The aggregate's own rules are
 * OrderAggregateTest's, and the whole thing wired together is OrderFlowIntegrationTest's.
 */
class OrderReservationTest {

    private val productId = "product-1"
    private val otherProductId = "product-2"

    private val dispatched = mutableListOf<Any>()

    /** Set to make the aggregate refuse, standing in for any reason a command can fail. */
    private var commandFailure: RuntimeException? = null

    /** Run as each command is dispatched, for the tests that care *when* something was true. */
    private var whenDispatched: (Any) -> Unit = {}

    private val commandGateway: CommandGateway = mock(CommandGateway::class.java).also { gateway ->
        `when`(gateway.sendAndWait<Any>(any())).thenAnswer { invocation ->
            commandFailure?.let { throw it }
            val command = invocation.getArgument<Any>(0)
            whenDispatched(command)
            dispatched.add(command)
            Unit
        }
    }

    private val inventory = FakeInventoryCatalog()

    /**
     * The reads are hand-written rather than mocked: every one of them is here only because the
     * service fetches the row back after the command, and none of these tests looks at what comes
     * out. A stub that says so plainly beats four stubbings that have to be read to find out.
     */
    private val reads = object : OrderViewReadService {
        override fun findAll(): List<OrderView> = emptyList()
        override fun findById(orderId: OrderId): OrderView = OrderView()
        override fun findByStatus(status: finki.ukim.erp.orders.OrderStatus): List<OrderView> = emptyList()
        override fun findByCustomerId(customerId: String): List<OrderView> = emptyList()
        override fun findByStatusAndProduct(
            status: finki.ukim.erp.orders.OrderStatus,
            productId: ProductId
        ): List<OrderView> = emptyList()
    }

    private val service = OrderCommandService(
        commandGateway = commandGateway,
        orderViewReadService = reads,
        invoiceViewReadService = mock(InvoiceViewReadService::class.java),
        transactionViewReadService = mock(TransactionViewReadService::class.java),
        inventoryCatalog = inventory
    )

    private fun place(vararg items: Pair<String, Int>) = service.createOrder(
        name = "John",
        surname = "Doe",
        customerId = "customer-1",
        items = items.map { OrderItemRequest(productId = it.first, quantity = it.second) }
    )

    /** The order the run created, taken off the command that created it. */
    private fun createdOrderId(): OrderId =
        dispatched.filterIsInstance<CreateOrderCommand>().single().orderId

    private fun heldFor(orderRef: String): Map<ProductId, Quantity>? = inventory.reservations[orderRef]

    // ------------------------------------------------------------------------------- placing

    @Test
    fun `placing an order holds its stock, under the order's own reference`() {
        place(productId to 2)

        val orderId = createdOrderId()
        assertEquals(listOf("reserve(${orderId.value})"), inventory.calls)
        assertEquals(mapOf(ProductId(productId) to Quantity(2)), heldFor(orderId.value))
    }

    /**
     * The reservation is taken before the order is created, not after, so there is never a moment
     * where an order exists that nothing is being held for.
     */
    @Test
    fun `the stock is held before the order is created`() {
        var heldWhenCreated: Boolean? = null
        whenDispatched = { command ->
            if (command is CreateOrderCommand) {
                heldWhenCreated = inventory.reservations.containsKey(command.orderId.value)
            }
        }

        place(productId to 1)

        assertEquals(true, heldWhenCreated, "the order was created before its stock was held")
    }

    /**
     * An order may name the same product on two lines, and the aggregate is happy to keep them
     * apart. The *hold* cannot be two holds - inventory keys one reservation per order per product
     * - so it has to be taken for the sum, or the order would end up backed by only one of them.
     */
    @Test
    fun `an order naming one product twice is held for the total of both lines`() {
        place(productId to 2, productId to 3)

        assertEquals(mapOf(ProductId(productId) to Quantity(5)), heldFor(createdOrderId().value))
    }

    // ------------------------------------------------------------- refusals, and what is left

    @Test
    fun `an order for a withdrawn product is refused and nothing is held`() {
        inventory.withdrawn += productId

        assertThrows<ProductNotAvailableException> { place(productId to 1) }

        assertTrue(inventory.calls.isEmpty(), "asked inventory to hold stock for a withdrawn product")
        assertTrue(dispatched.isEmpty(), "created an order for a withdrawn product")
    }

    @Test
    fun `an order for a product inventory has never heard of is refused`() {
        inventory.unknown += productId

        assertThrows<ProductNotFoundException> { place(productId to 1) }

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `an order larger than the stock is refused before anything is created`() {
        inventory.available[productId] = 1

        assertThrows<InsufficientStockException> { place(productId to 2) }

        assertTrue(dispatched.isEmpty())
    }

    /**
     * Inventory is the one that actually decides, and when it says no the reason it gave is what
     * reaches the caller - it knows which product ran short and by how much.
     */
    @Test
    fun `an order inventory refuses to hold is refused in inventory's own words`() {
        inventory.failNextReservation = StockReservationRejectedException(
            "Order:whatever",
            "Insufficient stock for product $productId: 2 requested, 1 available"
        )

        val failure = assertThrows<StockReservationRejectedException> { place(productId to 2) }

        assertTrue(failure.message!!.contains("2 requested, 1 available"), failure.message)
        assertTrue(dispatched.isEmpty(), "created an order inventory would not hold stock for")
    }

    /**
     * The one case that needs undoing. Nothing else will ever release this hold: releasing happens
     * when an order ends, and an order that was never created can never end.
     */
    @Test
    fun `stock held for an order that then fails to be created is given back`() {
        commandFailure = IllegalStateException("the aggregate refused")

        assertThrows<IllegalStateException> { place(productId to 1) }

        assertTrue(inventory.reservations.isEmpty(), "left a hold behind for an order that does not exist")
        assertTrue(
            inventory.calls.any { it.startsWith("releaseQuietly(") },
            "never tried to release: ${inventory.calls}"
        )
    }

    // ------------------------------------------------------------------------------ amending

    @Test
    fun `amending an order moves the hold in one call, never letting go of it`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.calls.clear()

        service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))

        // One call, not a release followed by a reservation. There is no moment in between for
        // another order to take the stock this one is keeping, because there is no in between.
        assertEquals(listOf("amend(${orderId.value})"), inventory.calls)
        assertEquals(mapOf(ProductId(otherProductId) to Quantity(4)), heldFor(orderId.value))
        assertNotNull(dispatched.filterIsInstance<UpdateOrderItemsCommand>().singleOrNull())
    }

    /**
     * The reason the amendment is priced against the order's own hold. Availability counts what is
     * *free*, and an order holding the last of something has made it unfree - so judging its new
     * lines on availability alone would refuse an order raising a line it is already sitting on.
     */
    @Test
    fun `an order holding the last of a product can still raise that line`() {
        inventory.available[productId] = 5
        place(productId to 5)
        val orderId = createdOrderId()
        // Nothing free at all now, as far as availability is concerned.
        inventory.available[productId] = 0

        service.updateOrderItems(orderId, listOf(OrderItemRequest(productId, 5)))

        assertEquals(mapOf(ProductId(productId) to Quantity(5)), heldFor(orderId.value))
    }

    @Test
    fun `an amendment inventory will not hold leaves the order exactly as it was`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.failNextAmendment = StockReservationRejectedException(orderId.value, "not enough left")

        assertThrows<StockReservationRejectedException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        // Nothing to put back, because nothing was given up: inventory unwinds its own work and
        // answers, and the hold this order was placed on never moved.
        assertEquals(mapOf(ProductId(productId) to Quantity(2)), heldFor(orderId.value))
        assertTrue(dispatched.filterIsInstance<UpdateOrderItemsCommand>().isEmpty())
    }

    /**
     * The amendment reaches inventory before the command, so an aggregate that then refuses leaves
     * the hold on the new lines while the order still reads the old ones.
     *
     * That is a real gap and it is recorded here rather than asserted away. It is much narrower
     * than what it replaces - the aggregate's refusals are decided from state the caller has
     * already read, so this needs the order to be invoiced or closed in the moment between the two
     * calls - and unlike the old release-then-reserve it can never leave the order backed by
     * *nothing*, only by the wrong lines. Closing it entirely needs the amendment to be part of
     * the same decision as the command, which is a two-phase conversation neither service has.
     */
    @Test
    fun `an amendment the aggregate refuses leaves the hold on the new lines`() {
        place(productId to 2)
        val orderId = createdOrderId()
        commandFailure = IllegalStateException("an invoiced order can no longer be edited")

        assertThrows<IllegalStateException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        assertEquals(mapOf(ProductId(otherProductId) to Quantity(4)), heldFor(orderId.value))
    }

    /**
     * An order inventory holds nothing for cannot be amended into holding something. The caller is
     * told, because that order is backed by nothing and somebody needs to know.
     */
    @Test
    fun `amending an order that holds nothing is refused rather than reserved`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.reservations.remove(orderId.value)

        assertThrows<StockNotReservedException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        assertNull(heldFor(orderId.value))
        assertTrue(dispatched.filterIsInstance<UpdateOrderItemsCommand>().isEmpty())
    }
}
