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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "stock_item_reservations",
        joinColumns = [JoinColumn(name = "stock_item_id")],
    )
    @MapKeyColumn(name = "order_ref")
    var reservationLedger: MutableMap<String, Quantity> = mutableMapOf()

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

    @CommandHandler
    fun on(command: ReleaseReservationCommand) {
        val quantity = reservationLedger[command.orderRef] ?: return
        apply(StockReservationReleasedEvent(command.stockItemId, command.orderRef, quantity))
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
    fun on(event: StockReservationReleasedEvent) {
        reserved = Quantity((reserved?.amount ?: 0) - event.quantity.amount)
        reservationLedger.remove(event.orderRef)
    }

    @EventSourcingHandler
    fun on(event: StockConfirmedEvent) {
        onHand = Quantity((onHand?.amount ?: 0) - event.quantity.amount)
        reserved = Quantity((reserved?.amount ?: 0) - event.quantity.amount)
        reservationLedger.remove(event.orderRef)
    }

    @EventSourcingHandler
    fun on(event: StockReorderThresholdUpdatedEvent) {
        reorderThreshold = event.reorderThreshold
    }

    @EventSourcingHandler
    fun on(event: StockItemDeletedEvent) {
        // Aggregate state cleared on delete — event sourcing marks this as the terminal state
        stockItemId = null
        productRef = null
        onHand = null
        reserved = null
        reorderThreshold = null
        reservationLedger = mutableMapOf()
    }
}