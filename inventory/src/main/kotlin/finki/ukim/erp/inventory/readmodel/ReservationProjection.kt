package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationAmendedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationReleasedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReturnedEvent
import finki.ukim.erp.inventory.query.reservation.FindAllReservationsQuery
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import org.axonframework.eventhandling.EventHandler
import org.axonframework.queryhandling.QueryHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The order-facing view of what stock is being held, keyed by the order that asked for it.
 *
 * The stock item's own product is resolved here rather than carried on the event: `StockItem`
 * announces a reservation in terms of itself, and which product that item stocks is a fact this
 * side already holds. Looking it up once, at projection time, is what lets a reader ask "is this
 * order holding product X" without a second round trip per line.
 */
@Component
class ReservationProjection(
    private val reservationViewRepository: ReservationViewRepository,
    private val stockItemViewRepository: StockItemViewRepository,
) {

    @EventHandler
    @Transactional
    fun on(event: StockReservedEvent) {
        val line = ReservationLineEmbeddable(
            stockItemId = event.stockItemId.value,
            // Empty only if the stock item's own projection has not landed, which cannot happen
            // for an item that just accepted a reservation: it was created before it could.
            productId = stockItemViewRepository.findById(event.stockItemId.value)
                .map { it.productId }
                .orElse(""),
            quantity = event.quantity.amount,
        )
        val existing = reservationViewRepository.findByOrderRef(event.orderRef)
        if (existing != null) {
            reservationViewRepository.save(
                existing.copy(lines = existing.lines.toMutableList().apply { add(line) }),
            )
        } else {
            reservationViewRepository.save(
                ReservationView(
                    orderRef = event.orderRef,
                    status = "ACTIVE",
                    createdAt = Instant.now().toString(),
                    lines = mutableListOf(line),
                ),
            )
        }
    }

    /**
     * Resizes the line the amendment was about and leaves the rest of the reservation alone.
     *
     * A missing reservation or a missing line is not repaired here. This projection is built from
     * the same aggregate's events in order, so the reserve that opened the line has always been
     * seen first - and if it somehow has not, inventing a line from an amendment would write a
     * hold the read model cannot attribute to any reservation that was actually taken.
     */
    @EventHandler
    @Transactional
    fun on(event: StockReservationAmendedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef) ?: return
        val updatedLines = existing.lines.map { line ->
            if (line.stockItemId == event.stockItemId.value) line.copy(quantity = event.quantity.amount) else line
        }.toMutableList()
        reservationViewRepository.save(existing.copy(lines = updatedLines))
    }

    @EventHandler
    @Transactional
    fun on(event: StockReservationReleasedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef) ?: return
        val updatedLines = existing.lines.filter {
            it.stockItemId != event.stockItemId.value
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

    /**
     * A confirmed line that came back closes the reservation, the same way releasing one does.
     *
     * Both are the order letting go of this stock item; the difference is only whether the goods
     * had physically moved. Once no line is left there is nothing being held, so the view goes -
     * which is also what lets the order reserve again if it is ever placed anew.
     */
    @EventHandler
    @Transactional
    fun on(event: StockReturnedEvent) {
        val existing = reservationViewRepository.findByOrderRef(event.orderRef) ?: return
        val updatedLines = existing.lines.filter { it.stockItemId != event.stockItemId.value }.toMutableList()
        if (updatedLines.isEmpty()) {
            reservationViewRepository.delete(existing)
        } else {
            reservationViewRepository.save(existing.copy(lines = updatedLines))
        }
    }

    @QueryHandler
    fun handle(query: FindAllReservationsQuery): List<ReservationView> =
        reservationViewRepository.findAll()

    @QueryHandler
    fun handle(query: FindReservationByOrderRefQuery): ReservationView? =
        reservationViewRepository.findByOrderRef(query.orderRef)
}
