package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.stockitem.StockAdjustedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockItemCreatedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockItemDeletedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReorderThresholdUpdatedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationReleasedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedEvent
import finki.ukim.erp.inventory.query.stockitem.FindAllStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindLowStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByStockItemIdQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockSummaryQuery
import org.axonframework.eventhandling.EventHandler
import org.axonframework.queryhandling.QueryHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class StockItemProjection(
    private val stockItemViewRepository: StockItemViewRepository,
) {

    @EventHandler
    @Transactional
    fun on(event: StockItemCreatedEvent) {
        stockItemViewRepository.save(
            StockItemView(
                stockItemId = event.stockItemId.value,
                productId = event.productRef.productId.value,
                onHand = event.onHand.amount,
                reserved = 0,
                reorderThreshold = event.reorderThreshold.value,
            ),
        )
    }

    @EventHandler
    @Transactional
    fun on(event: StockAdjustedEvent) {
        upsert(event.stockItemId.value) { it.copy(onHand = it.onHand + event.adjustment) }
    }

    @EventHandler
    @Transactional
    fun on(event: StockReservedEvent) {
        upsert(event.stockItemId.value) { it.copy(reserved = it.reserved + event.quantity.amount) }
    }

    @EventHandler
    @Transactional
    fun on(event: StockReservationReleasedEvent) {
        upsert(event.stockItemId.value) { it.copy(reserved = (it.reserved - event.quantity.amount).coerceAtLeast(0)) }
    }

    @EventHandler
    @Transactional
    fun on(event: StockConfirmedEvent) {
        upsert(event.stockItemId.value) {
            it.copy(
                onHand = (it.onHand - event.quantity.amount).coerceAtLeast(0),
                reserved = (it.reserved - event.quantity.amount).coerceAtLeast(0),
            )
        }
    }

    @EventHandler
    @Transactional
    fun on(event: StockReorderThresholdUpdatedEvent) {
        upsert(event.stockItemId.value) { it.copy(reorderThreshold = event.reorderThreshold.value) }
    }

    @EventHandler
    @Transactional
    fun on(event: StockItemDeletedEvent) {
        stockItemViewRepository.deleteById(event.stockItemId.value)
    }

    @QueryHandler
    fun handle(query: FindAllStockItemsQuery): List<StockItemView> =
        stockItemViewRepository.findAll()

    @QueryHandler
    fun handle(query: FindStockItemByProductIdQuery): StockItemView? =
        stockItemViewRepository.findByProductId(query.productId)

    @QueryHandler
    fun handle(query: FindStockItemByStockItemIdQuery): StockItemView? =
        stockItemViewRepository.findById(query.stockItemId).orElse(null)

    @QueryHandler
    fun handle(query: FindLowStockItemsQuery): List<StockItemView> =
        stockItemViewRepository.findAll()
            .filter { it.onHand - it.reserved <= it.reorderThreshold }

    @QueryHandler
    fun handle(query: FindStockSummaryQuery): StockSummaryResponse {
        val all = stockItemViewRepository.findAll()
        val lowStockCount = all.count { it.onHand - it.reserved <= it.reorderThreshold }
        return StockSummaryResponse(
            totalProducts = all.size,
            totalOnHand = all.sumOf { it.onHand },
            totalReserved = all.sumOf { it.reserved },
            totalAvailable = all.sumOf { it.onHand - it.reserved },
            lowStockCount = lowStockCount,
        )
    }

    private fun upsert(stockItemId: String, transform: (StockItemView) -> StockItemView) {
        val existing = stockItemViewRepository.findById(stockItemId).orElse(null) ?: return
        stockItemViewRepository.deleteById(stockItemId)
        stockItemViewRepository.flush()
        stockItemViewRepository.save(transform(existing))
    }
}