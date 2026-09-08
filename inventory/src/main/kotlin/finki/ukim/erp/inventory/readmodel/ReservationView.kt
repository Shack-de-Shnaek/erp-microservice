package finki.ukim.erp.inventory.readmodel

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "reservation_view")
@Immutable
data class ReservationView(
    @Id
    @Column(name = "order_ref")
    val orderRef: String = "",
    @Column(name = "status")
    val status: String = "ACTIVE",
    @Column(name = "created_at")
    val createdAt: String = "",
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "reservation_view_lines",
        joinColumns = [JoinColumn(name = "order_ref")],
    )
    @OrderColumn(name = "line_index")
    val lines: MutableList<ReservationLineEmbeddable> = mutableListOf(),
)

/**
 * One line of a reservation: which stock item is holding how much, and for which product.
 *
 * Both identifiers are here on purpose, because the two readers of this view want different ones.
 * Releasing and confirming address the [StockItem][finki.ukim.erp.inventory.domain.stockitem
 * .StockItem] aggregate, so they need [stockItemId]; the orders service checks a reservation
 * against the lines of an order, which are written in product ids, so it needs [productId].
 *
 * This used to carry one field, named `productId` and holding a stock item id, which every reader
 * then fed back into a lookup *by product* - so the lookup found nothing and the release it was
 * supposed to drive silently never happened. Keeping both, correctly named, is what stops that
 * being possible again.
 */
@Embeddable
data class ReservationLineEmbeddable(
    @Column(name = "stock_item_id")
    val stockItemId: String = "",
    @Column(name = "product_id")
    val productId: String = "",
    @Column(name = "quantity")
    val quantity: Int = 0,
)

interface ReservationViewRepository : JpaRepository<ReservationView, String> {
    fun findByOrderRef(orderRef: String): ReservationView?
}
