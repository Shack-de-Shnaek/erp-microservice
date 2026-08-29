package finki.ukim.erp.orders.views

import finki.ukim.erp.orders.Invoice
import finki.ukim.erp.orders.util.total
import java.math.BigDecimal
import java.time.LocalDateTime

data class InvoiceLineItemView(
    val id: Long?,
    val inventoryItemId: Long,
    val quantity: Int,
    val price: BigDecimal
)

data class InvoiceView(
    val id: Long?,
    val orderId: Long?,
    val invoiceNumber: String,
    val embg: String,
    val date: LocalDateTime,
    val isRefunded: Boolean,
    val lineItems: List<InvoiceLineItemView>,
    val totalAmount: BigDecimal
)

fun Invoice.toView(): InvoiceView = InvoiceView(
    id = id,
    orderId = order?.id,
    invoiceNumber = invoiceNumber,
    embg = embg,
    date = date,
    isRefunded = isRefunded,
    lineItems = invoiceLineItems.map { InvoiceLineItemView(it.id, it.inventoryItemId, it.quantity, it.price) },
    totalAmount = total()
)
