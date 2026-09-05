package finki.ukim.erp.orders.queries

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.TransactionId

/**
 * The questions the read side answers. Controllers ask these through the QueryGateway rather than
 * calling a repository, so the read model stays free to move - a different store, a cache, another
 * process - without the API changing.
 */
data class FindAllOrdersQuery(val unused: Boolean = true)

data class FindOrderByIdQuery(val orderId: OrderId)

data class FindOrdersByCustomerQuery(val customerId: String)

data class FindOrdersByStatusQuery(val status: OrderStatus)

data class FindInvoiceByIdQuery(val invoiceId: InvoiceId)

data class FindTransactionByIdQuery(val transactionId: TransactionId)
