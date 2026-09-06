package finki.ukim.erp.inventory.domain.stockitem

import finki.ukim.erp.inventory.domain.product.ProductId
import jakarta.persistence.Embeddable

@Embeddable
data class ProductRef(val productId: ProductId) {
    init {
        require(productId.value.isNotBlank()) { "Product reference must not be blank" }
    }
}

@Embeddable
data class Quantity(val amount: Int) {
    init {
        require(amount >= 0) { "Quantity must be non-negative" }
    }
}

@Embeddable
data class ReorderThreshold(val value: Int) {
    init {
        require(value >= 0) { "Reorder threshold must be non-negative" }
    }
}