package finki.ukim.erp.orders

import finki.ukim.erp.orders.clients.FakeInventoryCatalog
import finki.ukim.erp.orders.clients.InventoryProduct
import finki.ukim.erp.orders.commands.ApproveOrderCommand
import finki.ukim.erp.orders.commands.CancelOrderCommand
import finki.ukim.erp.orders.commands.CreateOrderCommand
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.commands.RegisterPaymentCommand
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.commands.ReverseInvoiceCommand
import finki.ukim.erp.orders.commands.UpdateInvoiceLineItemsCommand
import finki.ukim.erp.orders.commands.UpdateOrderItemsCommand
import finki.ukim.erp.orders.events.OrderEvent
import finki.ukim.erp.orders.events.InvoiceGeneratedEvent
import finki.ukim.erp.orders.events.InvoiceLineItemEventData
import finki.ukim.erp.orders.events.InvoiceLineItemsUpdatedEvent
import finki.ukim.erp.orders.events.InvoiceReversedEvent
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCancelledEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderItemEventData
import finki.ukim.erp.orders.events.OrderItemsUpdatedEvent
import finki.ukim.erp.orders.events.OrderRejectedEvent
import finki.ukim.erp.orders.events.PaymentCreatedEvent
import finki.ukim.erp.orders.events.PaymentReversedEvent
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyExistsException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyReversedException
import finki.ukim.erp.orders.exceptions.InvoiceNotFoundException
import finki.ukim.erp.orders.exceptions.OrderNotOwnedException
import finki.ukim.erp.orders.exceptions.OverpaymentException
import finki.ukim.erp.orders.handlers.ApproveOrderCommandHandler
import finki.ukim.erp.orders.handlers.GenerateInvoiceCommandHandler
import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Given-state-when-expect tests for the order aggregate's rules.
 *
 * The order is a state-stored aggregate, so "given" is the order as it currently stands rather
 * than a list of events to replay - which is why the fixture runs in [useStateStorage] mode. The
 * starting state is still built by feeding the aggregate the events that would have got it there,
 * because that is genuinely how it moves, and it keeps each test readable as a history.
 *
 * "Expect" is still about events: the assertion is that handling a command produced the right
 * announcement, or refused outright.
 */
class OrderAggregateTest {

    private lateinit var fixture: FixtureConfiguration<Order>

    private val orderId = OrderId("Order:order-1")
    private val customerId = "customer-1"
    private val invoiceId = InvoiceId("Invoice:invoice-1")
    private val reversalId = TransactionId("Transaction:reversal-1")
    private val customer = CustomerName("John", "Doe")

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(Order::class.java).useStateStorage()
        // Every event stamps its own LocalDateTime.now(); comparing those would only ever test
        // the clock.
        fixture.registerFieldFilter { field -> field.name != "occurredAt" }

        // Approval and invoicing are handled outside the aggregate, so the fixture has to be told
        // about those handlers or their commands would have nowhere to go. They get a catalog
        // that always has stock: what is under test here is the aggregate's rules about *when*
        // those commands are allowed. The stock check itself is covered by StockRecheckTest.
        fixture.registerAnnotatedCommandHandler(ApproveOrderCommandHandler(fixture.repository, alwaysInStock))
        fixture.registerAnnotatedCommandHandler(GenerateInvoiceCommandHandler(fixture.repository, alwaysInStock))
    }

    /**
     * Stock is never the reason a command fails here, so the fake is handed the reservation these
     * handlers now check for before each one runs. What is under test is the aggregate's rules
     * about *when* approval and invoicing are allowed; the stock half is StockRecheckTest's.
     */
    private val alwaysInStock = FakeInventoryCatalog().apply {
        reservations[orderId.value] = mapOf(ProductId("product-1") to Quantity(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------ state builders

    /** An order that has lived through [history]. */
    private fun orderWith(vararg history: OrderEvent): Order {
        val order = Order(id = orderId)
        history.forEach { event ->
            when (event) {
                is OrderCreatedEvent -> order.on(event)
                is OrderItemsUpdatedEvent -> order.on(event)
                is OrderApprovedEvent -> order.on(event)
                is OrderRejectedEvent -> order.on(event)
                is OrderCancelledEvent -> order.on(event)
                is PaymentCreatedEvent -> order.on(event)
                is PaymentReversedEvent -> order.on(event)
                is InvoiceGeneratedEvent -> order.on(event)
                is InvoiceLineItemsUpdatedEvent -> order.on(event)
                is InvoiceReversedEvent -> order.on(event)
                else -> error("Unhandled event in test setup: $event")
            }
        }
        return order
    }

    private fun orderCreated(vararg items: Triple<String, Int, String>) = OrderCreatedEvent(
        orderId = orderId,
        customerId = customerId,
        customer = customer,
        items = items.map { (productId, quantity, price) ->
            OrderItemEventData(ProductId(productId), Quantity(quantity), Money(BigDecimal(price)))
        },
        totalAmount = Money.sum(items.map { (_, quantity, price) -> Money(BigDecimal(price)) * Quantity(quantity) })
    )

    /** One item: 2 x 25.00 = 50.00. */
    private fun defaultOrderCreated() = orderCreated(Triple("product-1", 2, "25.00"))

    private fun payment(id: String, amount: String, type: PaymentType) =
        PaymentCreatedEvent(TransactionId(id), orderId, Money(BigDecimal(amount)), type)

    private fun fullPayment() = payment("tx-1", "50.00", PaymentType.CASH)

    private fun invoiceGenerated() = InvoiceGeneratedEvent(
        invoiceId = invoiceId,
        orderId = orderId,
        invoiceNumber = InvoiceNumber("INV-0001"),
        embg = Embg("1234567890123"),
        items = listOf(InvoiceLineItemEventData(ProductId("product-1"), Quantity(2), Money(BigDecimal("25.00")))),
        totalAmount = Money(BigDecimal("50.00"))
    )

    private fun money(amount: String) = Money(BigDecimal(amount))

    /** The default order's lines, which approval and cancellation now carry for consumers. */
    private fun defaultLines() =
        listOf(OrderItemEventData(ProductId("product-1"), Quantity(2), money("25.00")))

    private fun approved() = OrderApprovedEvent(orderId, defaultLines())

    private fun cancelled(refunded: Money) = OrderCancelledEvent(orderId, refunded, defaultLines())

    // ------------------------------------------------------------------ creation

    @Test
    fun `creating an order emits OrderCreatedEvent with the prices carried on the command`() {
        fixture.givenNoPriorActivity()
            .`when`(
                CreateOrderCommand(
                    orderId = orderId,
                    customer = customer,
                    customerId = customerId,
                    items = listOf(PricedItem(ProductId("product-1"), Quantity(2), money("25.00")))
                )
            )
            .expectSuccessfulHandlerExecution()
            .expectEvents(defaultOrderCreated())
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun `updating items on a pending order replaces the whole item set`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(UpdateOrderItemsCommand(orderId, listOf(PricedItem(ProductId("product-2"), Quantity(1), money("99.00")))))
            .expectEvents(
                OrderItemsUpdatedEvent(
                    orderId = orderId,
                    items = listOf(OrderItemEventData(ProductId("product-2"), Quantity(1), money("99.00"))),
                    totalAmount = money("99.00")
                )
            )
    }

    @Test
    fun `an invoiced order can no longer be edited`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), fullPayment(), invoiceGenerated())
        }
            .`when`(UpdateOrderItemsCommand(orderId, listOf(PricedItem(ProductId("product-1"), Quantity(3), money("25.00")))))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `a cancelled order can no longer be edited`() {
        fixture.givenState { orderWith(defaultOrderCreated(), cancelled(Money.ZERO)) }
            .`when`(UpdateOrderItemsCommand(orderId, listOf(PricedItem(ProductId("product-1"), Quantity(3), money("25.00")))))
            .expectException(InvalidOrderStateException::class.java)
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `approving moves a pending order to approved`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(ApproveOrderCommand(orderId))
            .expectEvents(approved())
    }

    @Test
    fun `only a pending order can be approved`() {
        fixture.givenState { orderWith(defaultOrderCreated(), approved()) }
            .`when`(ApproveOrderCommand(orderId))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `rejecting moves a pending order to rejected`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(RejectOrderCommand(orderId))
            .expectEvents(OrderRejectedEvent(orderId))
    }

    @Test
    fun `only a pending order can be rejected`() {
        fixture.givenState { orderWith(defaultOrderCreated(), cancelled(Money.ZERO)) }
            .`when`(RejectOrderCommand(orderId))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `an order can only be cancelled by the customer who placed it`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(CancelOrderCommand(orderId, "someone-else", reversalId))
            .expectException(OrderNotOwnedException::class.java)
    }

    @Test
    fun `a rejected order cannot be cancelled`() {
        fixture.givenState { orderWith(defaultOrderCreated(), OrderRejectedEvent(orderId)) }
            .`when`(CancelOrderCommand(orderId, customerId, reversalId))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `cancelling a pending order with no payments refunds nothing`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(CancelOrderCommand(orderId, customerId, reversalId))
            .expectEvents(cancelled(Money.ZERO))
    }

    @Test
    fun `cancelling an approved order reverses everything already paid`() {
        fixture.givenState {
            orderWith(
                defaultOrderCreated(),
                approved(),
                payment("tx-1", "30.00", PaymentType.CARD)
            )
        }
            .`when`(CancelOrderCommand(orderId, customerId, reversalId))
            .expectEvents(
                PaymentReversedEvent(reversalId, orderId, money("-30.00"), PaymentType.BANK_TRANSFER),
                cancelled(money("30.00"))
            )
    }

    // ------------------------------------------------------------------ payments

    @Test
    fun `payments are only accepted on an approved order`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(RegisterPaymentCommand(orderId, money("10.00"), PaymentType.CASH, TransactionId("tx-1")))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `a partial payment is accepted`() {
        fixture.givenState { orderWith(defaultOrderCreated(), approved()) }
            .`when`(RegisterPaymentCommand(orderId, money("20.00"), PaymentType.CARD, TransactionId("tx-1")))
            .expectEvents(PaymentCreatedEvent(TransactionId("tx-1"), orderId, money("20.00"), PaymentType.CARD))
    }

    @Test
    fun `a payment that exactly completes the order total is accepted`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), payment("tx-1", "30.00", PaymentType.CASH))
        }
            .`when`(RegisterPaymentCommand(orderId, money("20.00"), PaymentType.CASH, TransactionId("tx-2")))
            .expectEvents(PaymentCreatedEvent(TransactionId("tx-2"), orderId, money("20.00"), PaymentType.CASH))
    }

    @Test
    fun `a fully paid order takes no further payments`() {
        fixture.givenState { orderWith(defaultOrderCreated(), approved(), fullPayment()) }
            .`when`(RegisterPaymentCommand(orderId, money("10.00"), PaymentType.CASH, TransactionId("tx-2")))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `a payment may not push the total paid above the order total`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), payment("tx-1", "40.00", PaymentType.CASH))
        }
            .`when`(RegisterPaymentCommand(orderId, money("30.00"), PaymentType.CASH, TransactionId("tx-2")))
            .expectException(OverpaymentException::class.java)
    }

    // ------------------------------------------------------------------ invoicing

    @Test
    fun `only an approved order can be invoiced`() {
        fixture.givenState { orderWith(defaultOrderCreated()) }
            .`when`(generateInvoice())
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `an order cannot be invoiced before it is fully paid`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), payment("tx-1", "30.00", PaymentType.CASH))
        }
            .`when`(generateInvoice())
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `an order cannot be invoiced twice`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), fullPayment(), invoiceGenerated())
        }
            .`when`(generateInvoice(InvoiceId("Invoice:invoice-2"), InvoiceNumber("INV-0002")))
            .expectException(InvoiceAlreadyExistsException::class.java)
    }

    @Test
    fun `the invoice snapshots the order's items and total`() {
        fixture.givenState { orderWith(defaultOrderCreated(), approved(), fullPayment()) }
            .`when`(generateInvoice())
            .expectEvents(invoiceGenerated())
    }

    @Test
    fun `invoice line items can be corrected without touching the order's items`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), fullPayment(), invoiceGenerated())
        }
            .`when`(UpdateInvoiceLineItemsCommand(orderId, listOf(PricedItem(ProductId("product-2"), Quantity(1), money("99.00")))))
            .expectEvents(
                InvoiceLineItemsUpdatedEvent(
                    invoiceId = invoiceId,
                    orderId = orderId,
                    items = listOf(InvoiceLineItemEventData(ProductId("product-2"), Quantity(1), money("99.00"))),
                    totalAmount = money("99.00")
                )
            )
    }

    @Test
    fun `line items cannot be changed once the invoice is reversed`() {
        fixture.givenState { reversedInvoiceOrder() }
            .`when`(UpdateInvoiceLineItemsCommand(orderId, listOf(PricedItem(ProductId("product-2"), Quantity(1), money("99.00")))))
            .expectException(InvalidOrderStateException::class.java)
    }

    @Test
    fun `reversing an invoice refunds its total and marks it reversed`() {
        fixture.givenState {
            orderWith(defaultOrderCreated(), approved(), fullPayment(), invoiceGenerated())
        }
            .`when`(ReverseInvoiceCommand(orderId, reversalId))
            .expectEvents(
                PaymentReversedEvent(reversalId, orderId, money("-50.00"), PaymentType.CASH),
                InvoiceReversedEvent(invoiceId, orderId, money("50.00"))
            )
    }

    @Test
    fun `reversing refunds the corrected line items, not the original ones`() {
        fixture.givenState {
            orderWith(
                defaultOrderCreated(),
                approved(),
                fullPayment(),
                invoiceGenerated(),
                InvoiceLineItemsUpdatedEvent(
                    invoiceId = invoiceId,
                    orderId = orderId,
                    items = listOf(InvoiceLineItemEventData(ProductId("product-2"), Quantity(1), money("99.00"))),
                    totalAmount = money("99.00")
                )
            )
        }
            .`when`(ReverseInvoiceCommand(orderId, reversalId))
            .expectEvents(
                PaymentReversedEvent(reversalId, orderId, money("-99.00"), PaymentType.CASH),
                InvoiceReversedEvent(invoiceId, orderId, money("99.00"))
            )
    }

    @Test
    fun `an invoice cannot be reversed twice`() {
        fixture.givenState { reversedInvoiceOrder() }
            .`when`(ReverseInvoiceCommand(orderId, TransactionId("Transaction:reversal-2")))
            .expectException(InvoiceAlreadyReversedException::class.java)
    }

    @Test
    fun `reversing an order that has no invoice fails`() {
        fixture.givenState { orderWith(defaultOrderCreated(), approved(), fullPayment()) }
            .`when`(ReverseInvoiceCommand(orderId, reversalId))
            .expectException(InvoiceNotFoundException::class.java)
    }

    @Test
    fun `a refund goes back out as cash only when every payment was cash`() {
        fixture.givenState {
            orderWith(
                defaultOrderCreated(),
                approved(),
                payment("tx-1", "25.00", PaymentType.CASH),
                payment("tx-2", "25.00", PaymentType.CARD)
            )
        }
            .`when`(CancelOrderCommand(orderId, customerId, reversalId))
            .expectEvents(
                PaymentReversedEvent(reversalId, orderId, money("-50.00"), PaymentType.BANK_TRANSFER),
                cancelled(money("50.00"))
            )
    }

    private fun generateInvoice(
        id: InvoiceId = invoiceId,
        number: InvoiceNumber = InvoiceNumber("INV-0001")
    ) = GenerateInvoiceCommand(
        orderId = orderId,
        invoiceId = id,
        invoiceNumber = number,
        embg = Embg("1234567890123")
    )

    private fun reversedInvoiceOrder() = orderWith(
        defaultOrderCreated(),
        approved(),
        fullPayment(),
        invoiceGenerated(),
        PaymentReversedEvent(reversalId, orderId, money("-50.00"), PaymentType.CASH),
        InvoiceReversedEvent(invoiceId, orderId, money("50.00"))
    )
}
