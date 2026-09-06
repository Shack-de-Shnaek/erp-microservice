package finki.ukim.erp.inventory.domain.product

import org.axonframework.modelling.command.TargetAggregateIdentifier

data class CreateProductCommand(
    val productId: ProductId = ProductId.generate(),
    val sku: Sku,
    val name: ProductName,
    val unitOfMeasure: UnitOfMeasure,
)

data class UpdateProductCommand(
    @TargetAggregateIdentifier val productId: ProductId,
    val sku: Sku,
    val name: ProductName,
    val unitOfMeasure: UnitOfMeasure,
)

data class DeactivateProductCommand(
    @TargetAggregateIdentifier val productId: ProductId,
)

data class ReactivateProductCommand(
    @TargetAggregateIdentifier val productId: ProductId,
)