package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.commands.CreateOrderCommand
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.commands.UpdateOrderItemsCommand
import java.time.LocalDateTime

/**
 * What happened to an order, in the past tense.
 *
 * The database holds current state; these are the record of how it got there and the way other
 * services find out. Each carries a secondary constructor taking the command it came from, which
 * keeps the aggregate's handlers down to "make the event, apply it" and puts the mapping between a
 * command and its event in one place.
 *
 * Whether an event leaves this service is decided by [AbstractEvent.toExternalEvent]. Overriding it
 * is a promise to whoever reads the topic, so most events here do not.
 */
data class OrderItemEventData(
    val productId: ProductId,
    val quantity: Quantity,
    val price: Money
) {
    constructor(item: PricedItem) : this(item.productId, item.quantity, item.price)

    constructor(item: OrderItem) : this(item.productId, item.quantity, item.price)

    fun lineTotal(): Money = price * quantity

    fun toLine() = OrderLine(productId, quantity)
}

data class OrderCreatedEvent(
    override val orderId: OrderId,
    val customerId: String,
    val customer: CustomerName,
    val items: List<OrderItemEventData>,
    val totalAmount: Money,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: CreateOrderCommand) : this(
        orderId = command.orderId,
        customerId = command.customerId,
        customer = command.customer,
        items = command.items.map { OrderItemEventData(it) },
        totalAmount = Money.sum(command.items.map { it.lineTotal() })
    )

    /** The customer's name and subject stay in; demand is public, who placed it is not. */
    override fun toExternalEvent() = OrderCreatedExternalEvent(
        orderId = orderId,
        lines = items.map { it.toLine() },
        totalAmount = totalAmount,
        createdAt = occurredAt
    )
}

/**
 * Internal. What an order was changed to is nobody else's business until it is approved - a
 * pending order commits nothing, so publishing every edit would be noise other services would have
 * to learn to ignore.
 */
data class OrderItemsUpdatedEvent(
    override val orderId: OrderId,
    val items: List<OrderItemEventData>,
    val totalAmount: Money,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: UpdateOrderItemsCommand) : this(
        orderId = command.orderId,
        items = command.items.map { OrderItemEventData(it) },
        totalAmount = Money.sum(command.items.map { it.lineTotal() })
    )
}

/**
 * Published: the order has been committed, because inventory confirmed its goods out of the
 * warehouse and this service moved the order to match.
 *
 * It asks nothing of anyone - the goods were reserved at placement and have already gone - but it
 * is the one announcement that an order became real, so it goes out for anyone following the life
 * of an order. It carries the lines because an event saying only "order 5 was approved" would send
 * every such reader back to ask what was on it.
 */
data class OrderApprovedEvent(
    override val orderId: OrderId,
    val items: List<OrderItemEventData>,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    override fun toExternalEvent() = OrderApprovedExternalEvent(
        orderId = orderId,
        lines = items.map { it.toLine() },
        approvedAt = occurredAt
    )
}

/**
 * Published, but only as a nullification. A rejected order never reached approval, so nothing was
 * ever reserved against it and there are no lines worth carrying - but a consumer that opened a
 * record keyed on the order still has one to close, and it should not have to know that rejection
 * is a separate topic from cancellation to find out. There is deliberately no `order.rejected`:
 * *why* an order was refused is this service's business.
 */
data class OrderRejectedEvent(
    override val orderId: OrderId,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: RejectOrderCommand) : this(orderId = command.orderId)

    override fun toExternalEvents() = listOf(nullification(NullificationReason.REJECTED))
}

/**
 * Published: the counterpart to approval. Whatever was reserved against this order can be released,
 * so the lines are carried again - a consumer must not have to remember what it reserved.
 */
data class OrderCancelledEvent(
    override val orderId: OrderId,
    val refundedAmount: Money,
    val items: List<OrderItemEventData>,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    /** The refunded amount stays internal; how much money moved is between us and the customer. */
    override fun toExternalEvent() = OrderCancelledExternalEvent(
        orderId = orderId,
        lines = items.map { it.toLine() },
        cancelledAt = occurredAt
    )

    /**
     * Twice over: on `order.cancelled` for anyone following what happens to orders, and on
     * `order.nullified` for anyone who only needs to know the order is void and let go of what
     * they were holding for it.
     */
    override fun toExternalEvents() =
        super.toExternalEvents() + nullification(NullificationReason.CANCELLED, items.map { it.toLine() })
}
