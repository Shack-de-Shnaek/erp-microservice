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

    override fun toExternalEvent(): Any =
        StockAdjustedExternalEvent(
            stockItemId = stockItemId.value,
            adjustment = adjustment,
            reason = reason,
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

/**
 * An order's hold on this item changed size.
 *
 * Both quantities are carried. [previousQuantity] is what makes the event replayable into a
 * running total - a projection tracking `reserved` needs the delta, and deriving it from the
 * event alone is what stops it from having to read the state it is building. [quantity] is the
 * new truth, which is what a reader that only wants to know the current hold is after.
 */
data class StockReservationAmendedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val previousQuantity: Quantity,
    val quantity: Quantity,
) : StockItemEvent(stockItemId) {

    /** Positive when the order took more, negative when it gave some back. Never zero. */
    val delta: Int get() = quantity.amount - previousQuantity.amount

    override fun toExternalEvent(): Any =
        StockReservationAmendedExternalEvent(
            stockItemId = stockItemId.value,
            orderRef = orderRef,
            previousQuantity = previousQuantity.amount,
            quantity = quantity.amount,
        )
}

data class StockReservationReleasedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
    val reason: ReleaseReason = ReleaseReason.ORDER_NULLIFIED,
) : StockItemEvent(stockItemId) {
    override fun toExternalEvent(): Any =
        StockReservationReleasedExternalEvent(
            stockItemId = stockItemId.value,
            orderRef = orderRef,
            quantity = quantity.amount,
            reason = reason.name,
        )
}

/**
 * Goods that had already left the shelf for an order came back to it.
 *
 * The counterpart to [StockConfirmedEvent], and a different thing from a release: a release lets go
 * of a claim on stock that never moved, where this puts physical stock back. An order whose invoice
 * is reversed after payment has to produce this one - the goods went out, and refunding the customer
 * without restocking them would lose them from the ledger entirely.
 */
data class StockReturnedEvent(
    val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
    val reason: ReleaseReason = ReleaseReason.ORDER_NULLIFIED,
) : StockItemEvent(stockItemId) {
    override fun toExternalEvent(): Any =
        StockReturnedExternalEvent(
            stockItemId = stockItemId.value,
            orderRef = orderRef,
            quantity = quantity.amount,
            reason = reason.name,
        )
}

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

data class StockReorderThresholdUpdatedEvent(
    val stockItemId: StockItemId,
    val reorderThreshold: ReorderThreshold,
) : StockItemEvent(stockItemId) {
    constructor(command: UpdateReorderThresholdCommand) : this(
        command.stockItemId,
        command.reorderThreshold,
    )
}

data class StockItemDeletedEvent(
    val stockItemId: StockItemId,
) : StockItemEvent(stockItemId) {
    constructor(command: DeleteStockItemCommand) : this(command.stockItemId)
}