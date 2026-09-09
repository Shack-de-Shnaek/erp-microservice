package finki.ukim.erp.inventory.integration

import finki.ukim.erp.inventory.domain.stockitem.ReleaseReason
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import finki.ukim.erp.inventory.readmodel.ReservationView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What stock does when an order ends in the other service.
 *
 * Only one moment is left here, and the shape of the split is the point. Taking stock is a
 * **command**: orders asks for it synchronously, at the moment the order is placed, and needs the
 * answer before it can accept the order at all - a customer who is told "your order is placed" has
 * been promised goods, and that promise must not rest on a message that may still be in flight.
 * Giving stock back is an **event**: nobody is waiting on the answer, the order is already over
 * whichever way it ended, and it has to happen even if orders is not in a position to ask.
 *
 * So reservation arrived here as `POST /api/reservations` before the order existed, and release
 * arrives as `order.nullified` - cancelled, rejected, refunded, orders says all three in the same
 * words - after it is finished. There is no longer anything to do on `order.approved`: the goods
 * were put aside when the order was placed and approval only decides what happens to them.
 *
 * No Kafka in here. [finki.ukim.erp.inventory.infrastructure.kafka.KafkaEventConsumer] deals with
 * the transport and hands this translated types, so the decisions can be read and tested without a
 * broker in sight.
 */
@Component
class OrderLifecycleSaga(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Releases everything held for the order, by the order's own reference.
     *
     * The lines come from this service's reservation record rather than from the message, and that
     * distinction is the whole point of releasing by id. What has to be given back is what was
     * actually taken: an order that was edited after approval, or one whose reservation partly
     * failed because a product had no stock item, does not match the lines on the event that ended
     * it. The reservation view does.
     *
     * No reservation is a normal outcome, not an error - a rejected order never reserved anything,
     * and a nullification that arrives twice finds nothing to do the second time. Releasing per
     * stock item is idempotent for the same reason: the aggregate has no hold left to drop.
     */
    fun onOrderNullified(order: NullifiedOrder) {
        val reservation = queryGateway.query(
            FindReservationByOrderRefQuery(order.orderRef),
            ResponseTypes.instanceOf(ReservationView::class.java),
        ).get()

        if (reservation == null) {
            log.info(
                "Order {} was nullified ({}) but this service holds no reservation for it",
                order.orderRef,
                order.reason ?: "no reason given",
            )
            return
        }

        log.info(
            "Order {} was nullified ({}); releasing {} reserved line(s)",
            order.orderRef,
            order.reason ?: "no reason given",
            reservation.lines.size,
        )

        // Straight off the reservation, with no lookup in between: the line already names the stock
        // item that is holding the goods, which is the thing the command has to address.
        // ORDER_NULLIFIED: the order ended and said so, so this is the consequence of something it
        // already decided. The reason keeps that distinct from stock this service takes back on its
        // own, which is news the order has to act on rather than an echo of its own decision.
        reservation.lines.forEach { line ->
            commandGateway.sendAndWait<Any>(
                ReleaseReservationCommand(
                    StockItemId.fromString(line.stockItemId),
                    order.orderRef,
                    ReleaseReason.ORDER_NULLIFIED,
                ),
            )
        }
    }
}
