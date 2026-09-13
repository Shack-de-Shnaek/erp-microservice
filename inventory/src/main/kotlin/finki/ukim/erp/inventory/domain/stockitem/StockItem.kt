package finki.ukim.erp.inventory.domain.stockitem

import jakarta.persistence.AttributeOverride
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle.apply
import org.axonframework.spring.stereotype.Aggregate

@Aggregate(repository = "stockItemRepository")
@Entity
class StockItem {

    @EmbeddedId
    @AttributeOverride(name = "value", column = Column(name = "stock_item_id"))
    @AggregateIdentifier
    var stockItemId: StockItemId? = null

    @AttributeOverride(name = "productId.value", column = Column(name = "product_id"))
    var productRef: ProductRef? = null

    @AttributeOverride(name = "amount", column = Column(name = "on_hand_amount"))
    var onHand: Quantity? = null

    @AttributeOverride(name = "amount", column = Column(name = "reserved_amount"))
    var reserved: Quantity? = null

    @AttributeOverride(name = "value", column = Column(name = "reorder_threshold_value"))
    var reorderThreshold: ReorderThreshold? = null

    /**
     * What each order is holding but has not yet taken.
     *
     * Fetched lazily, and it has to be. Axon's `GenericJpaRepository` loads this aggregate with
     * `PESSIMISTIC_WRITE`, and an eager @ElementCollection makes that load an outer join, which no
     * dialect can render `FOR UPDATE` directly. Hibernate 7 therefore falls back to follow-on
     * locking - a second statement that locks the rows it has just read - and that path is simply
     * not implemented for an entity whose key is an @EmbeddedId, as this one's is. Every command
     * that touched a stock item died on `UnsupportedOperationException: Not implemented yet` from
     * deep inside Hibernate. Loading the collections lazily keeps the aggregate's own row the only
     * thing in the locking select, so the lock is taken inline and follow-on locking never starts.
     *
     * Nothing is given up by it. The aggregate is only ever loaded inside Axon's unit of work,
     * which is a transaction, so the ledgers initialise on first touch with the session still
     * open; and the row lock on the stock item is what actually serialises two concurrent commands
     * against it, not the locks on the ledger tables.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "stock_item_reservations",
        joinColumns = [JoinColumn(name = "stock_item_id")],
    )
    @MapKeyColumn(name = "order_ref")
    var reservationLedger: MutableMap<String, Quantity> = mutableMapOf()

    /**
     * What each order has already taken off the shelf.
     *
     * Confirmation used to be the end of an order as far as this item was concerned: the ledger
     * entry was dropped and the quantity forgotten. It cannot be, because an order is not finished
     * when its goods go out - an invoice can be reversed and the customer refunded afterwards, and
     * the goods then have to come back. Something has to remember how many, and only this does.
     *
     * So a hold moves between the two ledgers rather than disappearing: reserved while it is a
     * claim on stock that has not moved, confirmed once the goods have gone. An order appears in
     * at most one of them.
     *
     * Lazy for the same reason as [reservationLedger] above.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "stock_item_confirmations",
        joinColumns = [JoinColumn(name = "stock_item_id")],
    )
    @MapKeyColumn(name = "order_ref")
    var confirmedLedger: MutableMap<String, Quantity> = mutableMapOf()

    constructor()

    @CommandHandler
    constructor(command: CreateStockItemCommand) {
        apply(StockItemCreatedEvent(command))
    }

    @CommandHandler
    fun on(command: AdjustStockCommand) {
        val current = onHand ?: return
        check(command.adjustment != 0) { "Adjustment must not be zero" }
        check(current.amount + command.adjustment >= 0) { "Adjustment would make stock negative" }
        apply(StockAdjustedEvent(command))
    }

    @CommandHandler
    fun on(command: ReserveStockCommand) {
        if (reservationLedger.containsKey(command.orderRef)) {
            return
        }
        val currentOnHand = onHand?.amount ?: 0
        val currentReserved = reserved?.amount ?: 0
        check(currentOnHand - currentReserved >= command.quantity.amount) {
            "Insufficient stock: $currentOnHand on hand, $currentReserved reserved, " +
                "${command.quantity.amount} requested"
        }
        apply(StockReservedEvent(command))
    }

    /**
     * Resizes a hold this order already has, contesting only the difference.
     *
     * The delta is the point. Raising a hold from 2 to 5 asks the shelf for 3 more units and is
     * checked against 3; lowering it hands units back and cannot fail at all. Contrast the release
     * -then-reserve this replaces, where the order dropped all 2 units and then had to win all 5
     * back against every other order in flight - so amending an order for the last of something
     * could lose stock the order already had.
     *
     * Refusing an unknown `orderRef` rather than reserving one is deliberate, and it is the one
     * place this command is stricter than its neighbours: [ReserveStockCommand] ignores a repeat
     * and [ReleaseReservationCommand] ignores a hold that is not there, because both are driven by
     * messages that may be redelivered and doing nothing twice is harmless. An amend is not
     * redelivered - it comes from a caller holding a reservation it just read - so "you are not
     * holding any of this" means the caller is working from a picture that has already changed,
     * and silently creating the hold would turn that into stock taken for a line nobody checked.
     *
     * Amending to the quantity already held is a no-op: nothing moved, so there is nothing to
     * announce, and a caller replaying the same amendment gets the same state rather than a second
     * event saying zero changed.
     */
    @CommandHandler
    fun on(command: AmendReservationCommand) {
        val held = reservationLedger[command.orderRef]
            // Which of the two it is, decided here rather than in the controller, because only the
            // aggregate knows without lag. The controller's equivalent check reads the reservation
            // projection, which can still say ACTIVE moments after the goods have gone out.
            ?: throw IllegalStateException(
                if (confirmedLedger.containsKey(command.orderRef)) {
                    "Order ${command.orderRef} has already been confirmed for item " +
                        "${command.stockItemId.value} and its reservation can no longer be amended"
                } else {
                    "Order ${command.orderRef} holds no stock of item ${command.stockItemId.value}: " +
                        "an amendment can resize a hold but not create one"
                },
            )
        check(command.quantity.amount > 0) {
            "Amending order ${command.orderRef} to zero would end the hold; release it instead"
        }
        if (held == command.quantity) {
            return
        }

        val delta = command.quantity.amount - held.amount
        if (delta > 0) {
            val currentOnHand = onHand?.amount ?: 0
            val currentReserved = reserved?.amount ?: 0
            // Only the increase is judged. What this order is already holding stays its own and is
            // never put back on the shelf to be won again.
            check(currentOnHand - currentReserved >= delta) {
                "Insufficient stock to raise order ${command.orderRef} from ${held.amount} to " +
                    "${command.quantity.amount}: $currentOnHand on hand, $currentReserved reserved, " +
                    "$delta more requested"
            }
        }
        apply(StockReservationAmendedEvent(command.stockItemId, command.orderRef, held, command.quantity))
    }

    /**
     * Gives back whatever this order has, in whichever of the two forms it has it.
     *
     * A hold that is still reserved is released and the goods were never touched. Goods that were
     * confirmed have physically left, so giving them back means putting them on the shelf again -
     * a different event, because it is a different fact, and a consumer counting stock movements
     * must not read the two as the same thing.
     *
     * Being able to do the second at all is what lets a paid order be reversed. Confirmation used
     * to be terminal here, so a refunded invoice returned the customer's money and quietly lost the
     * goods.
     *
     * Holding neither is still a silent no-op: a nullification that arrives twice finds nothing to
     * do the second time, which is what makes redelivery harmless.
     */
    @CommandHandler
    fun on(command: ReleaseReservationCommand) {
        reservationLedger[command.orderRef]?.let { reserved ->
            apply(StockReservationReleasedEvent(command.stockItemId, command.orderRef, reserved, command.reason))
            return
        }
        confirmedLedger[command.orderRef]?.let { confirmed ->
            apply(StockReturnedEvent(command.stockItemId, command.orderRef, confirmed, command.reason))
        }
    }

    @CommandHandler
    fun on(command: ConfirmStockCommand) {
        val quantity = reservationLedger[command.orderRef] ?: return
        apply(StockConfirmedEvent(command.stockItemId, command.orderRef, quantity))
    }

    @CommandHandler
    fun on(command: UpdateReorderThresholdCommand) {
        if (reorderThreshold == command.reorderThreshold) {
            return
        }
        apply(StockReorderThresholdUpdatedEvent(command))
    }

    @CommandHandler
    fun on(command: DeleteStockItemCommand) {
        apply(StockItemDeletedEvent(command))
    }

    @EventSourcingHandler
    fun on(event: StockItemCreatedEvent) {
        stockItemId = event.stockItemId
        productRef = event.productRef
        onHand = event.onHand
        reserved = Quantity(0)
        reorderThreshold = event.reorderThreshold
        reservationLedger = mutableMapOf()
        confirmedLedger = mutableMapOf()
    }

    @EventSourcingHandler
    fun on(event: StockAdjustedEvent) {
        onHand = onHand?.let { Quantity(it.amount + event.adjustment) }
    }

    @EventSourcingHandler
    fun on(event: StockReservedEvent) {
        reserved = Quantity((reserved?.amount ?: 0) + event.quantity.amount)
        reservationLedger[event.orderRef] = event.quantity
    }

    @EventSourcingHandler
    fun on(event: StockReservationAmendedEvent) {
        reserved = Quantity((reserved?.amount ?: 0) + event.delta)
        reservationLedger[event.orderRef] = event.quantity
    }

    @EventSourcingHandler
    fun on(event: StockReservationReleasedEvent) {
        reserved = Quantity((reserved?.amount ?: 0) - event.quantity.amount)
        reservationLedger.remove(event.orderRef)
    }

    @EventSourcingHandler
    fun on(event: StockConfirmedEvent) {
        onHand = Quantity((onHand?.amount ?: 0) - event.quantity.amount)
        reserved = Quantity((reserved?.amount ?: 0) - event.quantity.amount)
        // Moved rather than forgotten: the goods have gone out, but the order can still be reversed
        // and they have to be findable when it is.
        reservationLedger.remove(event.orderRef)
        confirmedLedger[event.orderRef] = event.quantity
    }

    @EventSourcingHandler
    fun on(event: StockReturnedEvent) {
        onHand = Quantity((onHand?.amount ?: 0) + event.quantity.amount)
        confirmedLedger.remove(event.orderRef)
    }

    @EventSourcingHandler
    fun on(event: StockReorderThresholdUpdatedEvent) {
        reorderThreshold = event.reorderThreshold
    }

    @EventSourcingHandler
    fun on(event: StockItemDeletedEvent) {
        productRef = null
        onHand = null
        reserved = null
        reorderThreshold = null
        reservationLedger = mutableMapOf()
        confirmedLedger = mutableMapOf()
    }
}