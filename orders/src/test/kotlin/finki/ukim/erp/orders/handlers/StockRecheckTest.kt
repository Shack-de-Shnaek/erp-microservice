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
import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.clients.InventoryProduct
import finki.ukim.erp.orders.commands.ApproveOrderCommand
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderItemEventData
import finki.ukim.erp.orders.events.PaymentCreatedEvent
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The half of approval and invoicing that does *not* live in the aggregate: an order that was
 * fulfillable when it was placed must not go through if the goods have since sold out.
 *
 * Stock is what the external command handlers add on top of the aggregate's own rules, so this is
 * the test that covers them - OrderAggregateTest deliberately hands them a catalog that always
 * says yes, to keep its own assertions about order state honest.
 */
class StockRecheckTest {

    private lateinit var fixture: FixtureConfiguration<Order>

    private val orderId = OrderId("Order:order-1")

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(Order::class.java).useStateStorage()
        fixture.registerFieldFilter { field -> field.name != "occurredAt" }
    }

    private fun withCatalog(catalog: InventoryCatalog) {
        fixture.registerAnnotatedCommandHandler(ApproveOrderCommandHandler(fixture.repository, catalog))
        fixture.registerAnnotatedCommandHandler(GenerateInvoiceCommandHandler(fixture.repository, catalog))
    }

    private fun stocked(availableQuantity: Int) = object : InventoryCatalog {
        override fun findProduct(productId: ProductId) =
            InventoryProduct(productId.value, "Desk lamp", BigDecimal("25.00"), availableQuantity)
    }

    private val gone = object : InventoryCatalog {
        override fun findProduct(productId: ProductId): InventoryProduct? = null
    }

    /** 2 x 25.00 of product 1, pending. */
    private fun pendingOrder(): Order {
        val order = Order(id = orderId)
        order.on(
            OrderCreatedEvent(
                orderId = orderId,
                customerId = "customer-1",
                customer = CustomerName("John", "Doe"),
                items = listOf(OrderItemEventData(ProductId(1L), Quantity(2), Money(BigDecimal("25.00")))),
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
    fun `an order whose stock ran out while it was pending is not approved`() {
        withCatalog(stocked(availableQuantity = 1))

        fixture.givenState { pendingOrder() }
            .`when`(ApproveOrderCommand(orderId))
            .expectException(InsufficientStockException::class.java)
            .expectNoEvents()
    }

    @Test
    fun `an order whose product was removed from the catalog is not approved`() {
        withCatalog(gone)

        fixture.givenState { pendingOrder() }
            .`when`(ApproveOrderCommand(orderId))
            .expectException(ProductNotFoundException::class.java)
            .expectNoEvents()
    }

    @Test
    fun `stock that still covers the order lets the approval through`() {
        withCatalog(stocked(availableQuantity = 2))

        fixture.givenState { pendingOrder() }
            .`when`(ApproveOrderCommand(orderId))
            .expectSuccessfulHandlerExecution()
            .expectEvents(
                OrderApprovedEvent(
                    orderId,
                    listOf(OrderItemEventData(ProductId(1L), Quantity(2), Money(BigDecimal("25.00"))))
                )
            )
    }

    @Test
    fun `a paid order cannot be invoiced once its stock is gone`() {
        withCatalog(stocked(availableQuantity = 0))

        fixture.givenState { paidOrder() }
            .`when`(
                GenerateInvoiceCommand(
                    orderId = orderId,
                    invoiceId = InvoiceId("Invoice:invoice-1"),
                    invoiceNumber = InvoiceNumber("INV-0001"),
                    embg = Embg("1234567890123")
                )
            )
            .expectException(InsufficientStockException::class.java)
            .expectNoEvents()
    }
}
