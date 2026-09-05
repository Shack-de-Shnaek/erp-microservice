package finki.ukim.erp.orders

import finki.ukim.erp.orders.commands.ApproveOrderCommand
import finki.ukim.erp.orders.commands.CancelOrderCommand
import finki.ukim.erp.orders.commands.CreateOrderCommand
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.commands.RegisterPaymentCommand
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.commands.ReverseInvoiceCommand
import finki.ukim.erp.orders.commands.UpdateInvoiceLineItemsCommand
import finki.ukim.erp.orders.commands.UpdateOrderItemsCommand
import finki.ukim.erp.orders.events.InvoiceGeneratedEvent
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
import jakarta.persistence.AttributeOverride
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Type
import jakarta.persistence.Version
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle.apply
import org.axonframework.spring.stereotype.Aggregate
import java.time.LocalDateTime

/**
 * The order aggregate: the consistency boundary around an order, its payment transactions and its
 * invoice.
 *
 * Those three are one aggregate because the rules span all three - a payment may not exceed the
 * order total, an invoice may not be issued until the order is fully paid, cancelling an approved
 * order refunds what was collected - and a rule can only be enforced against state that changes
 * together.
 *
 * This is a *state-stored* aggregate. The row in `orders` and its children are the source of
 * truth for what an order currently is; Axon loads this instance through
 * [finki.ukim.erp.orders.config.AxonRepositoriesConfiguration] rather than replaying anything.
 * Events are still applied on every change and still land in `domain_event_entry` and on Kafka -
 * they are the record of what happened and the way other services find out, but they are not
 * where current state is read from.
 *
 * That is why every handler here follows the same three steps: build the event, call `on(event)`
 * to move this object's state, then `apply(event)` to publish it. The state change and the
 * announcement of it are separate acts, and both are explicit.
 *
 * Two commands are deliberately *not* handled here - [ApproveOrderCommand] and
 * [GenerateInvoiceCommand] both need the inventory service to confirm stock first, and an
 * aggregate has no business holding a client to another service. They arrive through the external
 * handlers in [finki.ukim.erp.orders.handlers], which call [approve] and [generateInvoice] once
 * stock is confirmed. The rules about *when* those are allowed still live here.
 *
 * ("orders", not "order": the latter is a reserved word in both Postgres and H2.)
 */
@Aggregate(repository = "axonOrderRepository")
@Entity
@Table(name = "orders")
open class Order(
    @AggregateIdentifier
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(OrderIdType::class)
    @Column(name = "id", nullable = false)
    open var id: OrderId = OrderId(),

    @Embedded
    @AttributeOverride(name = "name", column = Column(name = "name", nullable = false))
    @AttributeOverride(name = "surname", column = Column(name = "surname", nullable = false))
    open var customer: CustomerName = CustomerName(),

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    /** The Keycloak subject of the customer who placed the order. */
    @Column(name = "customer_id", nullable = false)
    open var customerId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    open var status: OrderStatus = OrderStatus.PENDING,

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var orderItems: MutableList<OrderItem> = mutableListOf(),

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var transactions: MutableList<Transaction> = mutableListOf(),

    @OneToOne(mappedBy = "order", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    open var invoice: Invoice? = null,

    /**
     * Optimistic locking. Two commands loading the same order at once both write against the
     * version they read, and the second one to reach the database is rejected rather than quietly
     * overwriting the first.
     */
    @Version
    @Column(name = "version")
    open var version: Long? = null
) : LabeledEntity {

    /** Required by JPA, and by Axon before it populates a loaded instance. */
    protected constructor() : this(id = OrderId())

    override fun label(): String = "Order ${id.value} for $customer"

    // ------------------------------------------------------------------ commands

    @CommandHandler
    constructor(command: CreateOrderCommand) : this(id = command.orderId) {
        val event = OrderCreatedEvent(command)
        on(event)
        apply(event)
    }

    @CommandHandler
    fun updateItems(command: UpdateOrderItemsCommand) {
        if (invoice != null) {
            throw InvalidOrderStateException("Order $id already has an invoice and can no longer be edited")
        }
        if (status != OrderStatus.PENDING && status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only a pending or approved order can be edited")
        }

        val event = OrderItemsUpdatedEvent(command)
        on(event)
        apply(event)
    }

    /**
     * Not a `@CommandHandler`: approval re-checks stock first, so
     * [finki.ukim.erp.orders.handlers.ApproveOrderCommandHandler] owns the command and calls this
     * once inventory has confirmed. The rule about which orders may be approved stays here, with
     * the state it judges.
     */
    fun approve(command: ApproveOrderCommand) {
        if (status != OrderStatus.PENDING) {
            throw InvalidOrderStateException("Only a pending order can be approved")
        }

        val event = OrderApprovedEvent(command, orderItems)
        on(event)
        apply(event)
    }

    @CommandHandler
    fun reject(command: RejectOrderCommand) {
        if (status != OrderStatus.PENDING) {
            throw InvalidOrderStateException("Only a pending order can be rejected")
        }

        val event = OrderRejectedEvent(command)
        on(event)
        apply(event)
    }

    @CommandHandler
    fun cancel(command: CancelOrderCommand) {
        if (customerId != command.customerId) {
            throw OrderNotOwnedException(id)
        }
        if (status != OrderStatus.PENDING && status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only a pending or approved order can be cancelled")
        }

        var refundedAmount = Money.ZERO
        val totalPaid = totalPaid()
        if (status == OrderStatus.APPROVED && totalPaid.isPositive()) {
            refundedAmount = totalPaid
            val reversal = reversalOf(command.reversalTransactionId, totalPaid)
            on(reversal)
            apply(reversal)
        }

        val event = OrderCancelledEvent(
            orderId = id,
            refundedAmount = refundedAmount,
            items = orderItems.map { OrderItemEventData(it) }
        )
        on(event)
        apply(event)
    }

    @CommandHandler
    fun registerPayment(command: RegisterPaymentCommand) {
        if (status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Payments can only be made towards an approved order")
        }

        val orderTotal = total()
        val totalPaid = totalPaid()
        if (totalPaid >= orderTotal) {
            throw InvalidOrderStateException("Order $id is already fully paid")
        }
        if (totalPaid + command.amount > orderTotal) {
            throw OverpaymentException(
                "Payment of ${command.amount} would bring total payments for order $id " +
                    "above its total of $orderTotal"
            )
        }

        val event = PaymentCreatedEvent(command)
        on(event)
        apply(event)
    }

    /** Not a `@CommandHandler`; see [approve]. */
    fun generateInvoice(command: GenerateInvoiceCommand) {
        if (status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only an approved order can be invoiced")
        }
        if (invoice != null) {
            throw InvoiceAlreadyExistsException(id)
        }
        if (totalPaid() < total()) {
            throw InvalidOrderStateException("Order $id is not yet fully paid off")
        }

        // The invoice is a snapshot of the order's items at the moment it was issued; changing one
        // afterwards must never move the other.
        val event = InvoiceGeneratedEvent(command, orderItems)
        on(event)
        apply(event)
    }

    @CommandHandler
    fun updateInvoiceLineItems(command: UpdateInvoiceLineItemsCommand) {
        val current = invoice ?: throw InvoiceNotFoundException.forOrder(id)
        if (current.isRefunded) {
            throw InvalidOrderStateException("A reversed invoice can no longer be modified")
        }

        val event = InvoiceLineItemsUpdatedEvent(command, current.id)
        on(event)
        apply(event)
    }

    @CommandHandler
    fun reverseInvoice(command: ReverseInvoiceCommand) {
        val current = invoice ?: throw InvoiceNotFoundException.forOrder(id)
        if (current.isRefunded) {
            throw InvoiceAlreadyReversedException(current.id)
        }

        val refundedAmount = current.total()
        val reversal = reversalOf(command.reversalTransactionId, refundedAmount)
        on(reversal)
        apply(reversal)

        val event = InvoiceReversedEvent(
            invoiceId = current.id,
            orderId = id,
            refundedAmount = refundedAmount
        )
        on(event)
        apply(event)
    }

    // ------------------------------------------------------------------ state changes

    fun on(event: OrderCreatedEvent) {
        id = event.orderId
        customerId = event.customerId
        customer = event.customer
        date = event.occurredAt
        status = OrderStatus.PENDING
        replaceOrderItems(event.items.map { OrderItem(productId = it.productId, quantity = it.quantity, price = it.price) })
    }

    fun on(event: OrderItemsUpdatedEvent) {
        replaceOrderItems(event.items.map { OrderItem(productId = it.productId, quantity = it.quantity, price = it.price) })
    }

    fun on(event: OrderApprovedEvent) {
        status = OrderStatus.APPROVED
    }

    fun on(event: OrderRejectedEvent) {
        status = OrderStatus.REJECTED
    }

    fun on(event: OrderCancelledEvent) {
        status = OrderStatus.CANCELLED
    }

    fun on(event: PaymentCreatedEvent) = recordTransaction(
        id = event.transactionId,
        amount = event.amount,
        paymentType = event.paymentType,
        date = event.occurredAt
    )

    fun on(event: PaymentReversedEvent) = recordTransaction(
        id = event.transactionId,
        amount = event.amount,
        paymentType = event.paymentType,
        date = event.occurredAt
    )

    fun on(event: InvoiceGeneratedEvent) {
        val issued = Invoice(
            id = event.invoiceId,
            order = this,
            isRefunded = false,
            date = event.occurredAt,
            invoiceNumber = event.invoiceNumber,
            embg = event.embg
        )
        issued.replaceLineItems(
            event.items.map {
                InvoiceLineItem(inventoryItemId = it.inventoryItemId, quantity = it.quantity, price = it.price)
            }
        )
        invoice = issued
    }

    fun on(event: InvoiceLineItemsUpdatedEvent) {
        invoice?.replaceLineItems(
            event.items.map {
                InvoiceLineItem(inventoryItemId = it.inventoryItemId, quantity = it.quantity, price = it.price)
            }
        )
    }

    fun on(event: InvoiceReversedEvent) {
        invoice?.isRefunded = true
    }

    // ------------------------------------------------------------------ derived state

    fun total(): Money = Money.sum(orderItems.map { it.lineTotal() })

    fun totalPaid(): Money = Money.sum(transactions.map { it.amount })

    /** How much of each product this order currently asks for, for the external stock re-checks. */
    fun requestedQuantities(): Map<ProductId, Quantity> =
        orderItems.associate { it.productId to it.quantity }

    // ------------------------------------------------------------------ internals

    private fun replaceOrderItems(items: List<OrderItem>) {
        orderItems.clear()
        items.forEach {
            it.order = this
            orderItems.add(it)
        }
    }

    private fun recordTransaction(id: TransactionId, amount: Money, paymentType: PaymentType, date: LocalDateTime) {
        transactions.add(
            Transaction(id = id, order = this, amount = amount, paymentType = paymentType, date = date)
        )
    }

    private fun reversalOf(transactionId: TransactionId, amount: Money) = PaymentReversedEvent(
        transactionId = transactionId,
        orderId = id,
        amount = amount.negate(),
        paymentType = reversalPaymentType()
    )

    /**
     * The refund follows the money already collected: cash-only stays cash, anything involving a
     * card or a transfer goes back out as a bank transfer.
     */
    private fun reversalPaymentType(): PaymentType {
        val collected = transactions.filter { it.amount.isPositive() }
        return if (collected.isNotEmpty() && collected.all { it.paymentType == PaymentType.CASH }) {
            PaymentType.CASH
        } else {
            PaymentType.BANK_TRANSFER
        }
    }
}
