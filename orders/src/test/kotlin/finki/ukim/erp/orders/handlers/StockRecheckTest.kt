package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.clients.FakeInventoryCatalog
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderItemEventData
import finki.ukim.erp.orders.events.PaymentCreatedEvent
import finki.ukim.erp.orders.exceptions.StockNotReservedException
import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The half of invoicing that does *not* live in the aggregate: an order may only be billed while
 * inventory is still holding the goods it was placed against.
 *
 * The question the handler asks changed with the reservation. It used to be "is there enough free
 * stock for this order", which stopped being answerable the moment placing an order put its stock
 * aside - the order's own goods are no longer free, so an order for the last of something would
 * have failed its own check. What is asked now is whether the hold taken at placement is still
 * there and still covers the lines.
 *
 * Approval is not tested here, because it no longer asks inventory anything: an order is approved
 * by inventory confirming its goods out, and that message is its own proof. See
 * StockLifecycleEventHandlerTest and OrderAggregateTest.
 *
 * Stock is what the external command handler adds on top of the aggregate's own rules, so this is
 * the test that covers it - OrderAggregateTest deliberately hands it a catalog that is holding
 * everything, to keep its own assertions about order state honest.
 */
class StockRecheckTest {

    private lateinit var fixture: FixtureConfiguration<Order>

    private val orderId = OrderId("Order:order-1")

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(Order::class.java).useStateStorage()
        fixture.registerFieldFilter { field -> field.name != "occurredAt" }
    }

    private fun withCatalog(catalog: FakeInventoryCatalog) {
        fixture.registerAnnotatedCommandHandler(GenerateInvoiceCommandHandler(fixture.repository, catalog))
    }

    /** Inventory holding [quantity] of the order's product against this order. */
    private fun holding(quantity: Int) = FakeInventoryCatalog().apply {
        reservations[orderId.value] = mapOf(ProductId("product-1") to Quantity(quantity))
    }

    /** Inventory holding nothing at all for this order. */
    private fun holdingNothing() = FakeInventoryCatalog()

    /** 2 x 25.00 of product 1, pending. */
    private fun pendingOrder(): Order {
        val order = Order(id = orderId)
        order.on(
            OrderCreatedEvent(
                orderId = orderId,
                customerId = "customer-1",
                customer = CustomerName("John", "Doe"),
                items = listOf(OrderItemEventData(ProductId("product-1"), Quantity(2), Money(BigDecimal("25.00")))),
                totalAmount = Money(BigDecimal("50.00"))
            )
        )
        return order
    }

    private fun paidOrder(): Order {
        val order = pendingOrder()
        order.on(OrderApprovedEvent(orderId, order.orderItems.map { OrderItemEventData(it) }))
        order.on(
            PaymentCreatedEvent(
                transactionId = TransactionId("tx-1"),
                orderId = orderId,
                amount = Money(BigDecimal("50.00")),
                paymentType = PaymentType.CARD
            )
        )
        return order
    }

    @Test
    fun `a paid order cannot be invoiced once its reservation is gone`() {
        withCatalog(holdingNothing())

        fixture.givenState { paidOrder() }
            .`when`(invoiceCommand())
            .expectException(StockNotReservedException::class.java)
            .expectNoEvents()
    }

    /**
     * A hold that no longer covers the order is as good as none. It is the shape an amendment
     * leaves behind when the new lines were reserved and the order itself then failed to change.
     */
    @Test
    fun `a paid order held for less than it asks for is not invoiced`() {
        withCatalog(holding(quantity = 1))

        fixture.givenState { paidOrder() }
            .`when`(invoiceCommand())
            .expectException(StockNotReservedException::class.java)
            .expectNoEvents()
    }

    @Test
    fun `a paid order still backed by its reservation is invoiced`() {
        withCatalog(holding(quantity = 2))

        fixture.givenState { paidOrder() }
            .`when`(invoiceCommand())
            .expectSuccessfulHandlerExecution()
    }

    private fun invoiceCommand() = GenerateInvoiceCommand(
        orderId = orderId,
        invoiceId = InvoiceId("Invoice:invoice-1"),
        invoiceNumber = InvoiceNumber("INV-0001"),
        embg = Embg("1234567890123")
    )
}
