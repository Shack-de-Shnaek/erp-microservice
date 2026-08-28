package finki.ukim.erp.inventory.domain.product

data class ProductCreatedExternalEvent(
    val productId: String,
    val sku: String,
    val name: String,
    val unitOfMeasure: String,
)

data class ProductUpdatedExternalEvent(
    val productId: String,
    val sku: String,
    val name: String,
    val unitOfMeasure: String,
)

data class ProductDeactivatedExternalEvent(
    val productId: String,
)

data class ProductReactivatedExternalEvent(
    val productId: String,
)