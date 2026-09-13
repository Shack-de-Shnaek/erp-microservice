package finki.ukim.erp.orders

import finki.ukim.erp.orders.dto.InvoiceLineItemRequest
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.handlers.StockLifecycleEventHandler
import finki.ukim.erp.orders.services.OrderCommandService
import finki.ukim.erp.orders.services.OrderViewReadService
import finki.ukim.erp.orders.services.TransactionViewReadService
import finki.ukim.erp.orders.views.OrderView
import org.axonframework.eventsourcing.eventstore.EventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

/**
 * The whole loop, wired for real: command -> aggregate -> `orders` and its child tables -> the
 * read-only views over those same tables.
 *
 * This is what proves the pieces the aggregate unit tests stub out actually fit together - the
 * JPA mapping of the typed identifiers and the embedded value objects, the custom Axon repository
 * that loads the aggregate by its typed id, and read-after-write through the views.
 *
 * Runs with `mock-inventory` so pricing comes from the in-memory catalog instead of a live
 * inventory service.
 */
@SpringBootTest
@ActiveProfiles("mock-inventory")
class OrderFlowIntegrationTest {

    @Autowired
    private lateinit var orderCommandService: OrderCommandService

    @Autowired
    private lateinit var orderViewReadService: OrderViewReadService

    @Autowired
    private lateinit var transactionViewReadService: TransactionViewReadService

    @Autowired
    private lateinit var eventStore: EventStore

    /**
     * The only way an order is approved: inventory confirms its goods out of the warehouse, which
     * arrives as `stock.confirmed` and is dispatched from here. The tests call the handler directly
     * rather than going through Kafka - what the broker adds is delivery, which KafkaEndToEndTest
     * covers, and this way each test still moves the order the way production does.
     */
    @Autowired
    private lateinit var stockLifecycleEventHandler: StockLifecycleEventHandler

    private fun placeOrder(): OrderView = orderCommandService.createOrder(
        name = "John",
        surname = "Doe",
        customerId = "customer-1",
        items = listOf(OrderItemRequest(productId = "product-1", quantity = 2))
    )

    @Test
    fun `an order is priced from inventory and immediately readable through the view`() {
        val created = placeOrder()

        assertEquals(OrderStatus.PENDING, created.status)
        assertEquals(Money(BigDecimal("39.98")), created.totalAmount)
        assertEquals(Money(BigDecimal("19.99")), created.items.single().price)
        assertTrue(created.id.value.startsWith(OrderId.PREFIX))

        val queried = orderViewReadService.findById(created.id)
        assertEquals(created.id, queried.id)
        assertEquals(CustomerName("John", "Doe"), queried.customer)
    }

    @Test
    fun `an order that cannot be covered by stock is refused before any row is written`() {
        assertThrows(InsufficientStockException::class.java) {
            orderCommandService.createOrder(
                name = "John",
                surname = "Doe",
                customerId = "customer-1",
                // Product 3 is in the catalog but out of stock.
                items = listOf(OrderItemRequest(productId = "product-3", quantity = 1))
            )
        }
    }

    @Test
    fun `the full confirm, pay, invoice and reverse flow lands in the database`() {
        val order = placeOrder()

        stockLifecycleEventHandler.onStockConfirmed(order.id)
        val payment = orderCommandService.registerPayment(order.id, BigDecimal("39.98"), PaymentType.CARD)
        assertEquals(Money(BigDecimal("39.98")), payment.amount)

        val invoice = orderCommandService.generateInvoice(order.id, embg = "1234567890123")
        assertEquals(Money(BigDecimal("39.98")), invoice.totalAmount)
        assertTrue(invoice.invoiceNumber.value.startsWith("INV-"))
        assertEquals(ProductId("product-1"), invoice.lineItems.single().inventoryItemId)

        val invoiced = orderViewReadService.findById(order.id)
        assertEquals(OrderStatus.APPROVED, invoiced.status)
        assertTrue(invoiced.hasInvoice)

        val reversed = orderCommandService.reverseInvoice(invoice.id)
        assertTrue(reversed.isRefunded)

        // Payment plus its reversal net out to nothing.
        val transactions = transactionViewReadService.findByOrderId(order.id)
        assertEquals(2, transactions.size)
        assertEquals(
            BigDecimal.ZERO,
            transactions.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount.amount }.stripTrailingZeros()
        )
    }

    @Test
    fun `correcting invoice line items leaves the order's own items alone`() {
        val order = placeOrder()
        stockLifecycleEventHandler.onStockConfirmed(order.id)
        orderCommandService.registerPayment(order.id, BigDecimal("39.98"), PaymentType.CARD)
        val invoice = orderCommandService.generateInvoice(order.id, embg = "1234567890123")

        val corrected = orderCommandService.updateInvoiceLineItems(
            invoice.id,
            listOf(InvoiceLineItemRequest(inventoryItemId = "product-2", quantity = 1, price = BigDecimal("49.50")))
        )

        assertEquals(ProductId("product-2"), corrected.lineItems.single().inventoryItemId)
        assertEquals(Money(BigDecimal("49.50")), corrected.totalAmount)

        val unchanged = orderViewReadService.findById(order.id)
        assertEquals(ProductId("product-1"), unchanged.items.single().productId)
        assertEquals(Quantity(2), unchanged.items.single().quantity)
    }

    @Test
    fun `an invoiced order can no longer be edited`() {
        val order = placeOrder()
        stockLifecycleEventHandler.onStockConfirmed(order.id)
        orderCommandService.registerPayment(order.id, BigDecimal("39.98"), PaymentType.CARD)
        orderCommandService.generateInvoice(order.id, embg = "1234567890123")

        assertThrows(InvalidOrderStateException::class.java) {
            orderCommandService.updateOrderItems(order.id, listOf(OrderItemRequest(productId = "product-1", quantity = 3)))
        }
    }

    @Test
    fun `orders can be listed by status`() {
        val first = placeOrder()
        val second = placeOrder()
        stockLifecycleEventHandler.onStockConfirmed(second.id)

        val pending = orderViewReadService.findByStatus(OrderStatus.PENDING).map { it.id }
        val approved = orderViewReadService.findByStatus(OrderStatus.APPROVED).map { it.id }

        assertTrue(pending.contains(first.id))
        assertTrue(approved.contains(second.id))
        assertTrue(!approved.contains(first.id))
    }

    @Test
    fun `every state change is still appended to the event store, even though state is read from the database`() {
        val order = placeOrder()
        stockLifecycleEventHandler.onStockConfirmed(order.id)
        orderCommandService.registerPayment(order.id, BigDecimal("39.98"), PaymentType.CARD)
        orderCommandService.generateInvoice(order.id, embg = "1234567890123")

        val payloads = eventStore.readEvents(order.id.value).asStream().map { it.payload.javaClass }.toList()

        assertEquals(
            listOf(
                finki.ukim.erp.orders.events.OrderCreatedEvent::class.java,
                finki.ukim.erp.orders.events.OrderApprovedEvent::class.java,
                finki.ukim.erp.orders.events.PaymentCreatedEvent::class.java,
                finki.ukim.erp.orders.events.InvoiceGeneratedEvent::class.java
            ),
            payloads
        )
    }
}
