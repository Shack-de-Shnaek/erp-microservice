package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.commands.ApproveOrderCommand
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
 * Published: approval is the moment goods are committed to a customer, which is what inventory
 * needs to reserve against. It carries the lines for that reason - an event saying only "order 5
 * was approved" would send every consumer back to ask what was on it.
 */
data class OrderApprovedEvent(
    override val orderId: OrderId,
    val items: List<OrderItemEventData>,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: ApproveOrderCommand, orderItems: List<OrderItem>) : this(
        orderId = command.orderId,
        items = orderItems.map { OrderItemEventData(it) }
    )

    override fun toExternalEvent() = OrderApprovedExternalEvent(
        orderId = orderId,
        lines = items.map { it.toLine() },
        approvedAt = occurredAt
    )
}

/**
 * Internal. A rejected order never committed anything, so there is nothing for anyone to undo.
 */
data class OrderRejectedEvent(
    override val orderId: OrderId,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: RejectOrderCommand) : this(orderId = command.orderId)
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
}
