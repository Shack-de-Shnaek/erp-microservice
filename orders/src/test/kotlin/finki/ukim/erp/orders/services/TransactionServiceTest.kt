package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.Transaction
import finki.ukim.erp.orders.events.PaymentCreatedEvent
import finki.ukim.erp.orders.events.PaymentEventPublisher
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.OverpaymentException
import finki.ukim.erp.orders.repositories.TransactionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
class TransactionServiceTest {

    @Mock
    lateinit var transactionRepository: TransactionRepository

    @Mock
    lateinit var paymentEventPublisher: PaymentEventPublisher

    lateinit var transactionService: TransactionService

    @BeforeEach
    fun setUp() {
        transactionService = TransactionService(transactionRepository, paymentEventPublisher)
        given(transactionRepository.save(any(Transaction::class.java))).thenAnswer {
            val tx = it.arguments[0] as Transaction
            tx.id = 1L
            tx
        }
    }

    private fun approvedOrder(totalPrice: BigDecimal): Order {
        val order = Order(id = 1L, name = "John", surname = "Doe", customerId = "c1", status = OrderStatus.APPROVED)
        order.addOrderItem(OrderItem(productId = 1L, quantity = 1, price = totalPrice))
        return order
    }

    @Test
    fun `createPayment throws when order is not approved`() {
        val order = Order(id = 1L, name = "John", surname = "Doe", customerId = "c1", status = OrderStatus.PENDING)
        order.addOrderItem(OrderItem(productId = 1L, quantity = 1, price = BigDecimal("100.00")))

        assertThrows(InvalidOrderStateException::class.java) {
            transactionService.createPayment(order, BigDecimal("10.00"), PaymentType.CASH)
        }
    }

    @Test
    fun `createPayment allows a partial payment`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(emptyList())

        val payment = transactionService.createPayment(order, BigDecimal("40.00"), PaymentType.CARD)

        assertEquals(BigDecimal("40.00"), payment.amount)
        verify(paymentEventPublisher).publish(any(PaymentCreatedEvent::class.java))
    }

    @Test
    fun `createPayment throws when order is already fully paid`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(Transaction(order = order, amount = BigDecimal("100.00"), paymentType = PaymentType.CASH))
        )

        assertThrows(InvalidOrderStateException::class.java) {
            transactionService.createPayment(order, BigDecimal("10.00"), PaymentType.CASH)
        }
    }

    @Test
    fun `createPayment throws when payment would push total payments above the order total`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(Transaction(order = order, amount = BigDecimal("80.00"), paymentType = PaymentType.CASH))
        )

        assertThrows(OverpaymentException::class.java) {
            transactionService.createPayment(order, BigDecimal("30.00"), PaymentType.CASH)
        }
    }

    @Test
    fun `createPayment allows a payment that exactly completes the order total`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(Transaction(order = order, amount = BigDecimal("80.00"), paymentType = PaymentType.CASH))
        )

        val payment = transactionService.createPayment(order, BigDecimal("20.00"), PaymentType.CASH)

        assertEquals(BigDecimal("20.00"), payment.amount)
        verify(paymentEventPublisher).publish(any(PaymentCreatedEvent::class.java))
    }

    @Test
    fun `getTotalPaid sums all transactions including reversals`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(
                Transaction(order = order, amount = BigDecimal("100.00"), paymentType = PaymentType.CASH),
                Transaction(order = order, amount = BigDecimal("-100.00"), paymentType = PaymentType.CASH)
            )
        )

        assertEquals(BigDecimal("0.00"), transactionService.getTotalPaid(order))
    }

    @Test
    fun `reverse uses CASH when every original payment was cash`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(Transaction(order = order, amount = BigDecimal("100.00"), paymentType = PaymentType.CASH))
        )

        val reversal = transactionService.reverse(order, BigDecimal("100.00"))

        assertEquals(PaymentType.CASH, reversal.paymentType)
        assertEquals(BigDecimal("-100.00"), reversal.amount)
    }

    @Test
    fun `reverse uses BANK_TRANSFER when any original payment was not cash`() {
        val order = approvedOrder(BigDecimal("100.00"))
        given(transactionRepository.findByOrder(order)).willReturn(
            listOf(
                Transaction(order = order, amount = BigDecimal("50.00"), paymentType = PaymentType.CASH),
                Transaction(order = order, amount = BigDecimal("50.00"), paymentType = PaymentType.CARD)
            )
        )

        val reversal = transactionService.reverse(order, BigDecimal("100.00"))

        assertEquals(PaymentType.BANK_TRANSFER, reversal.paymentType)
    }
}
