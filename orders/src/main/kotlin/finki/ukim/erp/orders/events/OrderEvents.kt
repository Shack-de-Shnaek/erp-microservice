package finki.ukim.erp.orders.events

import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderItemEventData(
    val productId: Long,
    val quantity: Int,
    val price: BigDecimal
)

data class OrderCreatedEvent(
    val orderId: Long,
    val customerId: String,
    val name: String,
    val surname: String,
    val items: List<OrderItemEventData>,
    val totalAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class OrderItemsUpdatedEvent(
    val orderId: Long,
    val items: List<OrderItemEventData>,
    val totalAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class OrderApprovedEvent(
    val orderId: Long,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class OrderRejectedEvent(
    val orderId: Long,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class OrderCancelledEvent(
    val orderId: Long,
    val refundedAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent
