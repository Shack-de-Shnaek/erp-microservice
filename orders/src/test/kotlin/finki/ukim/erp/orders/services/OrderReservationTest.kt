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
        assertTrue(inventory.calls.any { it.startsWith("release(") }, "never tried to release: ${inventory.calls}")
    }

    // ------------------------------------------------------------------------------ amending

    @Test
    fun `amending an order gives back the old hold before taking the new one`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.calls.clear()

        service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))

        // Released first, deliberately: inventory refuses a second reservation for an order that
        // already holds stock, and the order's own goods have to be back on the shelf to be
        // counted as available for its new lines.
        assertEquals(listOf("release(${orderId.value})", "reserve(${orderId.value})"), inventory.calls)
        assertEquals(mapOf(ProductId(otherProductId) to Quantity(4)), heldFor(orderId.value))
        assertNotNull(dispatched.filterIsInstance<UpdateOrderItemsCommand>().singleOrNull())
    }

    @Test
    fun `an amendment inventory will not hold puts the previous hold back`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.failNextReservation = StockReservationRejectedException(orderId.value, "not enough left")

        assertThrows<StockReservationRejectedException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        // The amendment is what was lost, not the order: it stands exactly as it was placed.
        assertEquals(mapOf(ProductId(productId) to Quantity(2)), heldFor(orderId.value))
        assertTrue(dispatched.filterIsInstance<UpdateOrderItemsCommand>().isEmpty())
    }

    @Test
    fun `an amendment the aggregate refuses puts the previous hold back`() {
        place(productId to 2)
        val orderId = createdOrderId()
        commandFailure = IllegalStateException("an invoiced order can no longer be edited")

        assertThrows<IllegalStateException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        assertEquals(mapOf(ProductId(productId) to Quantity(2)), heldFor(orderId.value))
    }

    /** Nothing to put back: an order that was holding nothing is not given a hold by a failure. */
    @Test
    fun `a failed amendment on an order that held nothing leaves it holding nothing`() {
        place(productId to 2)
        val orderId = createdOrderId()
        inventory.reservations.remove(orderId.value)
        inventory.failNextReservation = StockReservationRejectedException(orderId.value, "not enough left")

        assertThrows<StockReservationRejectedException> {
            service.updateOrderItems(orderId, listOf(OrderItemRequest(otherProductId, 4)))
        }

        assertNull(heldFor(orderId.value))
    }
}
