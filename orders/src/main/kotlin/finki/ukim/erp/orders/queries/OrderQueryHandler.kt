package finki.ukim.erp.orders.queries

import finki.ukim.erp.orders.services.InvoiceViewReadService
import finki.ukim.erp.orders.services.OrderViewReadService
import finki.ukim.erp.orders.services.TransactionViewReadService
import finki.ukim.erp.orders.views.InvoiceView
import finki.ukim.erp.orders.views.OrderView
import finki.ukim.erp.orders.views.TransactionView
import org.axonframework.queryhandling.QueryHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Bridges the query bus onto the read services. The handlers hold no logic of their own - the
 * lookups and the not-found rules live in the services, so anything that is not going through the
 * query bus (the inventory event handler, for one) gets exactly the same behaviour.
 */
@Component
@Transactional(readOnly = true)
class OrderQueryHandler(
    private val orderViewReadService: OrderViewReadService,
    private val invoiceViewReadService: InvoiceViewReadService,
    private val transactionViewReadService: TransactionViewReadService
) {

    @QueryHandler
    fun handle(query: FindAllOrdersQuery): List<OrderView> = orderViewReadService.findAll()

    @QueryHandler
    fun handle(query: FindOrderByIdQuery): OrderView = orderViewReadService.findById(query.orderId)

    @QueryHandler
    fun handle(query: FindOrdersByCustomerQuery): List<OrderView> =
        orderViewReadService.findByCustomerId(query.customerId)

    @QueryHandler
    fun handle(query: FindOrdersByStatusQuery): List<OrderView> =
        orderViewReadService.findByStatus(query.status)

    @QueryHandler
    fun handle(query: FindInvoiceByIdQuery): InvoiceView = invoiceViewReadService.findById(query.invoiceId)

    @QueryHandler
    fun handle(query: FindTransactionByIdQuery): TransactionView =
        transactionViewReadService.findById(query.transactionId)
}
