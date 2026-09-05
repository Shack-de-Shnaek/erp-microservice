package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.commands.RegisterPaymentCommand
import java.time.LocalDateTime

/**
 * Both internal, and deliberately so. How a customer paid, in how many instalments, and by what
 * method is between them and this service; no other aggregate reacts to a payment. What the rest
 * of the system cares about is the outcome - the order being approved, invoiced, or cancelled -
 * and those are published on their own.
 */
data class PaymentCreatedEvent(
    val transactionId: TransactionId,
    override val orderId: OrderId,
    val amount: Money,
    val paymentType: PaymentType,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: RegisterPaymentCommand) : this(
        transactionId = command.transactionId,
        orderId = command.orderId,
        amount = command.amount,
        paymentType = command.paymentType
    )
}

/** A refund. [amount] is negative, so a payment and its reversal sum to zero. */
data class PaymentReversedEvent(
    val transactionId: TransactionId,
    override val orderId: OrderId,
    val amount: Money,
    val paymentType: PaymentType,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt)
