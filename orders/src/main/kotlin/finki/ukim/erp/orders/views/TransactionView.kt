package finki.ukim.erp.orders.views

import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.Transaction
import java.math.BigDecimal
import java.time.LocalDateTime

data class TransactionView(
    val id: Long?,
    val orderId: Long?,
    val amount: BigDecimal,
    val paymentType: PaymentType,
    val date: LocalDateTime
)

fun Transaction.toView(): TransactionView = TransactionView(
    id = id,
    orderId = order?.id,
    amount = amount,
    paymentType = paymentType,
    date = date
)
