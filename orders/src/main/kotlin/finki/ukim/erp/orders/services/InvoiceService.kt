package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Invoice
import finki.ukim.erp.orders.InvoiceLineItem
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.dto.InvoiceLineItemRequest
import finki.ukim.erp.orders.events.InvoiceEventPublisher
import finki.ukim.erp.orders.events.InvoiceGeneratedEvent
import finki.ukim.erp.orders.events.InvoiceLineItemEventData
import finki.ukim.erp.orders.events.InvoiceLineItemsUpdatedEvent
import finki.ukim.erp.orders.events.InvoiceReversedEvent
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyExistsException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyReversedException
import finki.ukim.erp.orders.exceptions.InvoiceNotFoundException
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.repositories.InvoiceRepository
import finki.ukim.erp.orders.repositories.OrderRepository
import finki.ukim.erp.orders.util.total
import finki.ukim.erp.orders.util.verifyStockAvailable
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val orderRepository: OrderRepository,
    private val transactionService: TransactionService,
    private val inventoryClient: InventoryClient,
    private val invoiceEventPublisher: InvoiceEventPublisher
) {

    fun generateInvoice(orderId: Long, embg: String): Invoice {
        val order = orderRepository.findById(orderId).orElseThrow { OrderNotFoundException(orderId) }

        if (order.status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only an approved order can be invoiced")
        }
        if (order.invoice != null) {
            throw InvoiceAlreadyExistsException(orderId)
        }
        order.orderItems.verifyStockAvailable(inventoryClient)

        val orderTotal = order.total()
        val totalPaid = transactionService.getTotalPaid(order)
        if (totalPaid < orderTotal) {
            throw InvalidOrderStateException("Order $orderId is not yet fully paid off")
        }

        val invoice = Invoice(
            order = order,
            isRefunded = false,
            date = LocalDateTime.now(),
            invoiceNumber = generateInvoiceNumber(),
            embg = embg
        )
        order.orderItems.forEach { orderItem ->
            invoice.addInvoiceLineItem(
                InvoiceLineItem(
                    inventoryItemId = orderItem.productId,
                    quantity = orderItem.quantity,
                    price = orderItem.price
                )
            )
        }

        val savedInvoice = invoiceRepository.save(invoice)
        order.invoice = savedInvoice

        invoiceEventPublisher.publish(
            InvoiceGeneratedEvent(
                invoiceId = savedInvoice.id!!,
                orderId = orderId,
                invoiceNumber = savedInvoice.invoiceNumber,
                totalAmount = savedInvoice.total()
            )
        )
        return savedInvoice
    }

    fun updateInvoiceLineItems(invoiceId: Long, items: List<InvoiceLineItemRequest>): Invoice {
        val invoice = getInvoiceOrThrow(invoiceId)
        if (invoice.isRefunded) {
            throw InvalidOrderStateException("A reversed invoice can no longer be modified")
        }

        invoice.invoiceLineItems.clear()
        items.forEach { request ->
            invoice.addInvoiceLineItem(
                InvoiceLineItem(
                    inventoryItemId = request.inventoryItemId,
                    quantity = request.quantity,
                    price = request.price
                )
            )
        }

        val saved = invoiceRepository.save(invoice)
        invoiceEventPublisher.publish(
            InvoiceLineItemsUpdatedEvent(
                invoiceId = saved.id!!,
                orderId = saved.order?.id!!,
                items = saved.invoiceLineItems.map { InvoiceLineItemEventData(it.inventoryItemId, it.quantity, it.price) },
                totalAmount = saved.total()
            )
        )
        return saved
    }

    fun reverseInvoice(invoiceId: Long): Invoice {
        val invoice = getInvoiceOrThrow(invoiceId)
        if (invoice.isRefunded) {
            throw InvoiceAlreadyReversedException(invoiceId)
        }

        val order = invoice.order ?: throw OrderNotFoundException(invoiceId)
        val refundedAmount = invoice.total()
        transactionService.reverse(order, refundedAmount)

        invoice.isRefunded = true
        val saved = invoiceRepository.save(invoice)

        invoiceEventPublisher.publish(
            InvoiceReversedEvent(invoiceId = saved.id!!, orderId = order.id!!, refundedAmount = refundedAmount)
        )
        return saved
    }

    private fun generateInvoiceNumber(): String = "INV-${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun getInvoiceOrThrow(invoiceId: Long): Invoice =
        invoiceRepository.findById(invoiceId).orElseThrow { InvoiceNotFoundException(invoiceId) }
}
