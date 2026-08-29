package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.PaymentType
import java.math.BigDecimal
import java.time.LocalDateTime

data class PaymentCreatedEvent(
    val transactionId: Long,
    val orderId: Long,
    val amount: BigDecimal,
    val paymentType: PaymentType,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : DomainEvent
