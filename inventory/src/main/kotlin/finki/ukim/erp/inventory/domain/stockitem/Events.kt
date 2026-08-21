package finki.ukim.erp.inventory.domain.stockitem

import finki.ukim.erp.inventory.domain.base.AbstractEvent

sealed class StockItemEvent(
    identifier: StockItemId,
) : AbstractEvent(identifier)

data class StockItemCreatedEvent(
    val stockItemId: StockItemId,
    val productRef: ProductRef,
    val onHand: Quantity,
    val reorderThreshold: ReorderThreshold,
) : StockItemEvent(stockItemId) {
    constructor(command: CreateStockItemCommand) : this(
        command.stockItemId,
        command.productRef,
        command.onHand,
        command.reorderThreshold,
    )
}

data class StockAdjustedEvent(
    val stockItemId: StockItemId,
    val adjustment: Int,
    val reason: String,
) : StockItemEvent(stockItemId) {
    constructor(command: AdjustStockCommand) : this(
        command.stockItemId,
        command.adjustment,
        command.reason,
    )
}

data class StockReservedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
) : StockItemEvent(stockItemId) {
    constructor(command: ReserveStockCommand) : this(
        command.stockItemId,
        command.orderRef,
        command.quantity,
    )

    override fun toExternalEvent(): Any =
        StockReservedExternalEvent(
            stockItemId = stockItemId.value,
            orderRef = orderRef,
            quantity = quantity.amount,
        )
}

data class StockReservationReleasedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
) : StockItemEvent(stockItemId)

data class StockConfirmedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
) : StockItemEvent(stockItemId) {
    override fun toExternalEvent(): Any =
        StockConfirmedExternalEvent(
            stockItemId = stockItemId.value,
            orderRef = orderRef,
            quantity = quantity.amount,
        )
}