package finki.ukim.erp.inventory.readmodel

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "stock_item_view")
@Immutable
data class StockItemView(
    @Id
    @Column(name = "stock_item_id")
    val stockItemId: String = "",
    @Column(name = "product_id")
    val productId: String = "",
    @Column(name = "on_hand")
    val onHand: Int = 0,
    @Column(name = "reserved")
    val reserved: Int = 0,
    @Column(name = "reorder_threshold")
    val reorderThreshold: Int = 0,
)

interface StockItemViewRepository : JpaRepository<StockItemView, String> {
    fun findByProductId(productId: String): StockItemView?
}

data class StockSummaryResponse(
    val totalProducts: Int,
    val totalOnHand: Int,
    val totalReserved: Int,
    val totalAvailable: Int,
    val lowStockCount: Int,
)