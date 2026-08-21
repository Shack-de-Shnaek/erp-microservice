package finki.ukim.erp.inventory.domain.product

import jakarta.persistence.Embeddable

@Embeddable
data class Sku(val value: String) {
    init {
        require(value.isNotBlank()) { "SKU must not be blank" }
    }
}

@Embeddable
data class ProductName(val value: String) {
    init {
        require(value.isNotBlank()) { "Product name must not be blank" }
    }
}

@Embeddable
data class UnitOfMeasure(val value: String) {
    init {
        require(value.isNotBlank()) { "Unit of measure must not be blank" }
    }
}

enum class ProductStatus {
    ACTIVE,
    INACTIVE,
}