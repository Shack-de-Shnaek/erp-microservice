package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.product.ProductStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "product_view")
@Immutable
data class ProductView(
    @Id
    @Column(name = "product_id")
    val productId: String = "",
    @Column(name = "sku")
    val sku: String = "",
    @Column(name = "name")
    val name: String = "",
    @Column(name = "unit_of_measure")
    val unitOfMeasure: String = "",
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    val status: ProductStatus = ProductStatus.ACTIVE,
)

interface ProductViewRepository : JpaRepository<ProductView, String> {
    fun findByStatus(status: ProductStatus): List<ProductView>

    /**
     * The paged form, for the list endpoint. Separate from the unpaged one above rather than
     * replacing it: the query handler behind `FindProductsByStatusQuery` answers with a whole list
     * and has no page to ask for.
     */
    fun findByStatus(status: ProductStatus, pageable: Pageable): Page<ProductView>

    fun findBySku(sku: String): ProductView?
}