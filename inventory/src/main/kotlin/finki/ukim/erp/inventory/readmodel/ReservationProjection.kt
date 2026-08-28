package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationReleasedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedEvent
import finki.ukim.erp.inventory.query.reservation.FindAllReservationsQuery
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import org.axonframework.eventhandling.EventHandler
import org.axonframework.queryhandling.QueryHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ReservationProjection(
    private val reservationViewRepository: ReservationViewRepository,
) {

    @EventHandler
    @Transactional
    fun on(event: StockReservedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef)
        if (existing != null) {
            val updated = existing.copy(
                lines = existing.lines.toMutableList().apply {
                    add(ReservationLineEmbeddable(event.stockItemId.value, event.quantity.amount))
                },
            )
            reservationViewRepository.save(updated)
        } else {
            reservationViewRepository.save(
                ReservationView(
                    orderRef = event.orderRef,
                    status = "ACTIVE",
                    createdAt = Instant.now().toString(),
                    lines = mutableListOf(
                        ReservationLineEmbeddable(event.stockItemId.value, event.quantity.amount),
                    ),
                ),
            )
        }
    }

    @EventHandler
    @Transactional
    fun on(event: StockReservationReleasedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef) ?: return
        val updatedLines = existing.lines.filter {
            it.productId != event.stockItemId.value
        }.toMutableList()
        if (updatedLines.isEmpty()) {
            reservationViewRepository.delete(existing)
        } else {
            reservationViewRepository.save(existing.copy(lines = updatedLines))
        }
    }

    @EventHandler
    @Transactional
    fun on(event: StockConfirmedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef) ?: return
        reservationViewRepository.save(existing.copy(status = "CONFIRMED"))
    }

    @QueryHandler
    fun handle(query: FindAllReservationsQuery): List<ReservationView> =
        reservationViewRepository.findAll()

    @QueryHandler
    fun handle(query: FindReservationByOrderRefQuery): ReservationView? =
        reservationViewRepository.findByOrderRef(query.orderRef)
}
