package finki.ukim.erp.inventory.domain.stockitem

import org.axonframework.modelling.command.TargetAggregateIdentifier

data class CreateStockItemCommand(
    val stockItemId: StockItemId = StockItemId.generate(),
    val productRef: ProductRef,
    val onHand: Quantity,
    val reorderThreshold: ReorderThreshold,
)

data class AdjustStockCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val adjustment: Int,
    val reason: String,
)

data class ReserveStockCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
)

data class ReleaseReservationCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val orderRef: String,
)

data class ConfirmStockCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val orderRef: String,
)

data class UpdateReorderThresholdCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val reorderThreshold: ReorderThreshold,
)

data class DeleteStockItemCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
)