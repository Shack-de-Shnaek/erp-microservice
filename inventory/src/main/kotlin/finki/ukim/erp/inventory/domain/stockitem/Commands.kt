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

/**
 * Changes how much of this item an order is holding, in one decision.
 *
 * [quantity] is the *new total*, not a delta. That is what makes the command idempotent - sending
 * it twice leaves the same hold, where a delta sent twice would move the shelf twice - and it is
 * also the form the caller naturally has, since an amended order knows what its lines now say and
 * not what they changed by.
 *
 * The aggregate works out the delta itself and contests only that. An order raising a line from 2
 * to 5 is asking for 3 more units and is judged on 3; one lowering it to 1 gives 1 back and cannot
 * fail. At no point does the order let go of what it already holds, which is the whole reason this
 * exists rather than a release followed by a fresh reservation.
 *
 * It amends an existing hold and will not create one: an order that holds nothing of this item is
 * refused rather than quietly reserved, because "amend what you are holding" and "start holding
 * this" are different requests and only the caller knows which it meant.
 */
data class AmendReservationCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val orderRef: String,
    val quantity: Quantity,
)

/**
 * Why an order stopped holding stock.
 *
 * Carried on the command and out onto the topic, because the two cases mean opposite things to the
 * order. A release the order itself brought about is the last step of something it already decided;
 * one this service decided is news, and news that invalidates the order. Without the reason on the
 * event they are the same message, and a consumer reacting to it would reject every order it had
 * just cancelled.
 */
enum class ReleaseReason {
    /**
     * The order ended - cancelled, rejected, or its invoice reversed - and said so. Giving the
     * stock back is the consequence, and the order needs no telling.
     */
    ORDER_NULLIFIED,

    /**
     * Somebody on this side took the stock back from the order, through the reservation API. The
     * order still believes it is backed by goods and has to be told that it is not.
     */
    WITHDRAWN_BY_INVENTORY,
}

/**
 * Gives back what an order is holding, whether or not the goods have already left the shelf.
 *
 * One command for both, because a caller giving stock back should not have to know which of the two
 * states the hold is in - and for an order being reversed after payment, it genuinely may not.
 * `StockItem` knows: a hold that is still reserved is released, and goods that were confirmed and
 * have physically gone are returned to the shelf. Holding neither is a no-op.
 */
data class ReleaseReservationCommand(
    @TargetAggregateIdentifier val stockItemId: StockItemId,
    val orderRef: String,
    val reason: ReleaseReason = ReleaseReason.ORDER_NULLIFIED,
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