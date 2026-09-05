package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.exceptions.InvoiceNotFoundException
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.exceptions.TransactionNotFoundException
import finki.ukim.erp.orders.repositories.InvoiceViewJpaRepository
import finki.ukim.erp.orders.repositories.OrderViewJpaRepository
import finki.ukim.erp.orders.repositories.TransactionViewJpaRepository
import finki.ukim.erp.orders.views.InvoiceView
import finki.ukim.erp.orders.views.OrderView
import finki.ukim.erp.orders.views.TransactionView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The read side's public surface. Everything that wants to *look* at an order goes through here -
 * controllers, and the event handler that reacts to inventory - and nothing here can change
 * anything.
 */
interface OrderViewReadService {
    fun findAll(): List<OrderView>
    fun findById(orderId: OrderId): OrderView
    fun findByStatus(status: OrderStatus): List<OrderView>
    fun findByCustomerId(customerId: String): List<OrderView>
    fun findByStatusAndProduct(status: OrderStatus, productId: ProductId): List<OrderView>
}

interface InvoiceViewReadService {
    fun findAll(): List<InvoiceView>
    fun findById(invoiceId: InvoiceId): InvoiceView
    fun findByOrderId(orderId: OrderId): InvoiceView
}

interface TransactionViewReadService {
    fun findById(transactionId: TransactionId): TransactionView
    fun findByOrderId(orderId: OrderId): List<TransactionView>
}

@Service
@Transactional(readOnly = true)
class OrderViewReadServiceImpl(
    private val orderViewJpaRepository: OrderViewJpaRepository
) : OrderViewReadService {

    override fun findAll(): List<OrderView> = orderViewJpaRepository.findAll()

    override fun findById(orderId: OrderId): OrderView =
        orderViewJpaRepository.findById(orderId).orElseThrow { OrderNotFoundException(orderId) }

    override fun findByStatus(status: OrderStatus): List<OrderView> =
        orderViewJpaRepository.findByStatus(status)

    override fun findByCustomerId(customerId: String): List<OrderView> =
        orderViewJpaRepository.findByCustomerId(customerId)

    override fun findByStatusAndProduct(status: OrderStatus, productId: ProductId): List<OrderView> =
        orderViewJpaRepository.findByStatusAndProduct(status, productId)
}

@Service
@Transactional(readOnly = true)
class InvoiceViewReadServiceImpl(
    private val invoiceViewJpaRepository: InvoiceViewJpaRepository
) : InvoiceViewReadService {

    override fun findAll(): List<InvoiceView> = invoiceViewJpaRepository.findAll()

    override fun findById(invoiceId: InvoiceId): InvoiceView =
        invoiceViewJpaRepository.findById(invoiceId).orElseThrow { InvoiceNotFoundException(invoiceId) }

    override fun findByOrderId(orderId: OrderId): InvoiceView =
        invoiceViewJpaRepository.findByOrderId(orderId) ?: throw InvoiceNotFoundException.forOrder(orderId)
}

@Service
@Transactional(readOnly = true)
class TransactionViewReadServiceImpl(
    private val transactionViewJpaRepository: TransactionViewJpaRepository
) : TransactionViewReadService {

    override fun findById(transactionId: TransactionId): TransactionView =
        transactionViewJpaRepository.findById(transactionId)
            .orElseThrow { TransactionNotFoundException(transactionId) }

    override fun findByOrderId(orderId: OrderId): List<TransactionView> =
        transactionViewJpaRepository.findByOrderId(orderId)
}
