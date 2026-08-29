package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.Transaction
import finki.ukim.erp.orders.events.PaymentCreatedEvent
import finki.ukim.erp.orders.events.PaymentEventPublisher
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.OverpaymentException
import finki.ukim.erp.orders.repositories.TransactionRepository
import finki.ukim.erp.orders.util.total
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val paymentEventPublisher: PaymentEventPublisher
) {

    fun getTotalPaid(order: Order): BigDecimal =
        transactionRepository.findByOrder(order).fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }

    fun createPayment(order: Order, amount: BigDecimal, paymentType: PaymentType): Transaction {
        if (order.status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Payments can only be made towards an approved order")
        }

        val orderTotal = order.total()
        val totalPaid = getTotalPaid(order)

        if (totalPaid >= orderTotal) {
            throw InvalidOrderStateException("Order ${order.id} is already fully paid")
        }
        if (totalPaid + amount > orderTotal) {
            throw OverpaymentException(
                "Payment of $amount would bring total payments for order ${order.id} above its total of $orderTotal"
            )
        }

        val transaction = Transaction(paymentType = paymentType, order = order, amount = amount)
        val saved = transactionRepository.save(transaction)

        paymentEventPublisher.publish(
            PaymentCreatedEvent(
                transactionId = saved.id!!,
                orderId = order.id!!,
                amount = saved.amount,
                paymentType = saved.paymentType
            )
        )
        return saved
    }

    /**
     * Shared by invoice reversal and order cancellation-with-payments: creates a negative
     * transaction cancelling out [amount] already paid on [order]. The payment type follows
     * the money already collected: cash only stays cash, any card/bank_transfer makes it
     * bank_transfer.
     */
    fun reverse(order: Order, amount: BigDecimal): Transaction {
        val transaction = Transaction(
            paymentType = determineReversalPaymentType(order),
            order = order,
            amount = amount.negate()
        )
        return transactionRepository.save(transaction)
    }

    private fun determineReversalPaymentType(order: Order): PaymentType {
        val originalPayments = transactionRepository.findByOrder(order).filter { it.amount > BigDecimal.ZERO }
        return if (originalPayments.isNotEmpty() && originalPayments.all { it.paymentType == PaymentType.CASH }) {
            PaymentType.CASH
        } else {
            PaymentType.BANK_TRANSFER
        }
    }
}
