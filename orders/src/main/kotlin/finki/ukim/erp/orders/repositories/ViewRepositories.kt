package finki.ukim.erp.orders.repositories

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.views.InvoiceView
import finki.ukim.erp.orders.views.OrderView
import finki.ukim.erp.orders.views.TransactionView
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Plain Spring Data repositories over the read-only views. No Axon here at all: these read the
 * same tables the aggregate writes, and nothing on this side can change a row.
 */
@Repository
interface OrderViewJpaRepository : JpaRepository<OrderView, OrderId> {

    fun findByStatus(status: OrderStatus): List<OrderView>

    fun findByCustomerId(customerId: String): List<OrderView>

    /**
     * Every order in a given state that has a line for a given product. Written out as a query
     * rather than derived from the method name because it has to reach through the item
     * collection into an embedded value.
     *
     * This is what the inventory reaction needs: when a product is withdrawn, the orders that
     * still expect it have to be found before anything can be done about them.
     */
    @Query(
        """
        select distinct o from OrderView o
        join o.items item
        where o.status = :status and item.productId = :productId
        """
    )
    fun findByStatusAndProduct(
        @Param("status") status: OrderStatus,
        @Param("productId") productId: ProductId
    ): List<OrderView>
}

@Repository
interface InvoiceViewJpaRepository : JpaRepository<InvoiceView, InvoiceId> {

    /**
     * `orderId` on the view is derived from the association rather than a mapped column, so the
     * path has to be spelled out for the query parser.
     */
    @Query("select i from InvoiceView i where i.order.id = :orderId")
    fun findByOrderId(@Param("orderId") orderId: OrderId): InvoiceView?

    fun findByIsRefunded(isRefunded: Boolean): List<InvoiceView>
}

@Repository
interface TransactionViewJpaRepository : JpaRepository<TransactionView, TransactionId> {

    @Query("select t from TransactionView t where t.order.id = :orderId order by t.date")
    fun findByOrderId(@Param("orderId") orderId: OrderId): List<TransactionView>
}
