package finki.ukim.erp.inventory.integration

import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.readmodel.ReservationView
import finki.ukim.erp.inventory.readmodel.StockItemView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What stock does as an order moves through its life in the other service.
 *
 * Two moments matter here and no others. An order being *approved* is the point goods are committed
 * to a customer, so that is when stock is reserved - not when it is created, which commits nothing
 * and may still be rejected. An order being *nullified* - cancelled, rejected, refunded, orders
 * says all three in the same words - is the point the hold ends, whatever the reason.
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

    fun onOrderApproved(order: ApprovedOrder) {
        order.lines.forEach { line ->
            val stockItemId = stockItemIdForProduct(line.productId.value)
            if (stockItemId == null) {
                log.warn(
                    "Skipping reservation for order={} product={}: no stock item exists",
                    order.orderRef,
                    line.productId.value,
                )
                return@forEach
            }
            log.info(
                "Reserving stock for order={} product={} quantity={}",
                order.orderRef,
                line.productId.value,
                line.quantity.amount,
            )
            commandGateway.sendAndWait<Any>(
                ReserveStockCommand(stockItemId, order.orderRef, line.quantity),
            )
        }
    }

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

        reservation.lines.forEach { line ->
            val stockItemId = stockItemIdForProduct(line.productId)
            if (stockItemId == null) {
                log.warn(
                    "Cannot release order={} product={}: no stock item exists",
                    order.orderRef,
                    line.productId,
                )
                return@forEach
            }
            commandGateway.sendAndWait<Any>(ReleaseReservationCommand(stockItemId, order.orderRef))
        }
    }

    private fun stockItemIdForProduct(productId: String): StockItemId? =
        queryGateway.query(
            FindStockItemByProductIdQuery(productId),
            ResponseTypes.instanceOf(StockItemView::class.java),
        ).get()?.let { StockItemId.fromString(it.stockItemId) }
}
