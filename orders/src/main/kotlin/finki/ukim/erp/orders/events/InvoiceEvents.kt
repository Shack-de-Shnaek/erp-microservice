package finki.ukim.erp.orders.events

import java.math.BigDecimal
import java.time.LocalDateTime

data class InvoiceLineItemEventData(
    val inventoryItemId: Long,
    val quantity: Int,
    val price: BigDecimal
)

data class InvoiceGeneratedEvent(
    val invoiceId: Long,
    val orderId: Long,
    val invoiceNumber: String,
    val totalAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class InvoiceLineItemsUpdatedEvent(
    val invoiceId: Long,
    val orderId: Long,
    val items: List<InvoiceLineItemEventData>,
    val totalAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent

data class InvoiceReversedEvent(
    val invoiceId: Long,
    val orderId: Long,
    val refundedAmount: BigDecimal,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent
