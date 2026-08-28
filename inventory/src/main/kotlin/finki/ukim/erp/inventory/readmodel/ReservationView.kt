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

@Embeddable
data class ReservationLineEmbeddable(
    @Column(name = "product_id")
    val productId: String = "",
    @Column(name = "quantity")
    val quantity: Int = 0,
)

interface ReservationViewRepository : JpaRepository<ReservationView, String> {
    fun findByOrderRef(orderRef: String): ReservationView?
}
