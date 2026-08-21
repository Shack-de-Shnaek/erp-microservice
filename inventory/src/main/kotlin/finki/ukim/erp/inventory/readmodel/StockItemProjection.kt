package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.stockitem.StockAdjustedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockItemCreatedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationReleasedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedEvent
import finki.ukim.erp.inventory.query.stockitem.FindAllStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindLowStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
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

    @QueryHandler
    fun handle(query: FindAllStockItemsQuery): List<StockItemView> =
        stockItemViewRepository.findAll()

    @QueryHandler
    fun handle(query: FindStockItemByProductIdQuery): StockItemView? =
        stockItemViewRepository.findByProductId(query.productId)

    @QueryHandler
    fun handle(query: FindLowStockItemsQuery): List<StockItemView> =
        stockItemViewRepository.findAll()
            .filter { it.onHand - it.reserved <= it.reorderThreshold }

    private fun upsert(stockItemId: String, transform: (StockItemView) -> StockItemView) {
        val existing = stockItemViewRepository.findById(stockItemId).orElse(null) ?: return
        stockItemViewRepository.deleteById(stockItemId)
        stockItemViewRepository.flush()
        stockItemViewRepository.save(transform(existing))
    }
}