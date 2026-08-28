package finki.ukim.erp.inventory.domain.product

import finki.ukim.erp.inventory.domain.base.AbstractEvent

sealed class ProductEvent(
    identifier: ProductId,
) : AbstractEvent(identifier)

data class ProductCreatedEvent(
    val productId: ProductId,
    val sku: Sku,
    val name: ProductName,
    val unitOfMeasure: UnitOfMeasure,
) : ProductEvent(productId) {
    constructor(command: CreateProductCommand) : this(
        command.productId,
        command.sku,
        command.name,
        command.unitOfMeasure,
    )

    override fun toExternalEvent(): Any =
        ProductCreatedExternalEvent(
            productId = productId.value,
            sku = sku.value,
            name = name.value,
            unitOfMeasure = unitOfMeasure.value,
        )
}

data class ProductUpdatedEvent(
    val productId: ProductId,
    val sku: Sku,
    val name: ProductName,
    val unitOfMeasure: UnitOfMeasure,
) : ProductEvent(productId) {
    constructor(command: UpdateProductCommand) : this(
        command.productId,
        command.sku,
        command.name,
        command.unitOfMeasure,
    )

    override fun toExternalEvent(): Any =
        ProductUpdatedExternalEvent(
            productId = productId.value,
            sku = sku.value,
            name = name.value,
            unitOfMeasure = unitOfMeasure.value,
        )
}

data class ProductDeactivatedEvent(
    val productId: ProductId,
) : ProductEvent(productId) {
    constructor(command: DeactivateProductCommand) : this(command.productId)

    override fun toExternalEvent(): Any =
        ProductDeactivatedExternalEvent(productId = productId.value)
}

data class ProductReactivatedEvent(
    val productId: ProductId,
) : ProductEvent(productId) {
    constructor(command: ReactivateProductCommand) : this(command.productId)

    override fun toExternalEvent(): Any =
        ProductReactivatedExternalEvent(productId = productId.value)
}