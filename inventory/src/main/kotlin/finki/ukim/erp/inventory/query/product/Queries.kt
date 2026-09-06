package finki.ukim.erp.inventory.query.product

import finki.ukim.erp.inventory.domain.product.ProductStatus

data object FindAllProductsQuery

data class FindProductByIdQuery(
    val productId: String,
)

data class FindProductBySkuQuery(
    val sku: String,
)

data class FindProductsByStatusQuery(
    val status: ProductStatus,
)