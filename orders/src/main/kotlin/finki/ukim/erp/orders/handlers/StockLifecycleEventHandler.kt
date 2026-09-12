package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.commands.ApproveOrderForConfirmedStockCommand
import finki.ukim.erp.orders.commands.RejectOrderForWithdrawnStockCommand
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.infrastructure.kafka.StockReleaseReason
import finki.ukim.erp.orders.infrastructure.kafka.StockWithdrawnFromOrder
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.modelling.command.AggregateNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What an order does when the stock behind it moves.
 *
 * Two moments, and the split between them is the one the rest of the system is built on. Stock
 * being *confirmed* is inventory saying the goods for this order have left the shelf, which is the
 * point the order is committed - so it is what approves it. Stock being *withdrawn* is inventory
 * saying they are no longer there, which invalidates the order.
 *
 * Both arrive as events, because neither is a question anybody is waiting on an answer to: the
 * decision was already made on the other side, and this service's job is to catch up with it. That
 * is also why nothing here reaches back to inventory to check anything - the message *is* the
 * check, and asking again would only produce a second answer that could disagree with it.
 *
 * The rules themselves live on the aggregate, which is why this dispatches commands rather than
 * loading the order: neither decision needs anything from outside the order, so neither has any
 * business being made out here. What this class owns is the one thing the aggregate cannot see -
 * *why* the stock moved.
 */
@Component
class StockLifecycleEventHandler(
    private val commandGateway: CommandGateway
) {

    private val logger = LoggerFactory.getLogger(StockLifecycleEventHandler::class.java)

    /** Confirmed stock approves the order. Idempotent on the aggregate, so redelivery is harmless. */
    fun onStockConfirmed(orderId: OrderId) {
        try {
            commandGateway.sendAndWait<Any>(ApproveOrderForConfirmedStockCommand(orderId))
        } catch (unknown: AggregateNotFoundException) {
            logUnknownOrder(orderId, "confirmed")
            return
        }
        logger.info("Stock confirmed for order {}", orderId)
    }

    /**
     * Stock taken back by inventory rejects the order - but only stock this service did not ask to
     * give back.
     *
     * The reason is the whole of the rule. Cancelling, rejecting or reversing an order publishes
     * `order.nullified`, inventory releases the hold, and that release comes back here as an event.
     * Acting on it would mean every cancellation immediately rejected its own order, and every
     * rejection re-rejected one already rejected - a loop feeding on its own output. So a release
     * the order brought about is read and dropped.
     *
     * A withdrawal is the opposite: nobody here decided it, and the order now stands on goods that
     * are gone.
     *
     * One order can lose several lines and so produce several of these. The first closes the order
     * and the rest find it closed and do nothing, which is what makes "any product's stock being
     * withdrawn rejects the whole order" fall out of the rule rather than needing to be counted.
     */
    fun onStockWithdrawn(withdrawal: StockWithdrawnFromOrder) {
        if (withdrawal.reason != StockReleaseReason.WITHDRAWN_BY_INVENTORY) {
            logger.debug(
                "Stock released for order {} with reason {}; this service asked for it, so the order stands",
                withdrawal.orderId,
                withdrawal.reason
            )
            return
        }

        try {
            commandGateway.sendAndWait<Any>(
                RejectOrderForWithdrawnStockCommand(withdrawal.orderId, withdrawal.detail)
            )
        } catch (unknown: AggregateNotFoundException) {
            logUnknownOrder(withdrawal.orderId, "withdrawn")
            return
        } catch (refusal: InvalidOrderStateException) {
            // The paid-order case, most likely. Deliberately not retried and deliberately not
            // swallowed quietly: the order and the stock now disagree, no rule here can put that
            // right without deciding what happens to the customer's money, and a person has to.
            logger.error(
                "Inventory withdrew the stock behind order {} ({}), but the order could not be " +
                    "rejected: {}. The order and inventory now disagree and need attention.",
                withdrawal.orderId,
                withdrawal.detail,
                refusal.message
            )
            return
        }
        logger.info("Order {} rejected: {}", withdrawal.orderId, withdrawal.detail)
    }

    /**
     * An event about an order this service has never heard of.
     *
     * Distinct from redelivery, which the aggregate absorbs by itself, and the distinction is that
     * this one can never come good: an order id that names no order will not start naming one
     * later, so there is nothing to retry and nothing to wait for. Left to escape, it would reach
     * [finki.ukim.erp.orders.infrastructure.kafka.KafkaEventConsumer]'s catch-all and be logged as
     * a failure with a stack trace, which reads as a service in trouble rather than as a message
     * that was never ours.
     *
     * Two things produce one. A reservation can outlive the order it was taken for - the hold is
     * placed before the order exists, and if creating the order fails and giving the hold back
     * fails too, inventory keeps a reservation under an id nothing will ever answer to. Or the two
     * services' stores can simply diverge: Kafka retains a message for longer than the order row
     * it refers to survives, and a replayed backlog then asks about orders that are gone.
     *
     * WARN, not ERROR: worth seeing, because a steady stream of these means the reservations and
     * the orders have drifted apart, but no single one of them is a fault to be fixed.
     */
    private fun logUnknownOrder(orderId: OrderId, movement: String) {
        logger.warn(
            "Inventory reports stock {} for order {}, which does not exist in this service. " +
                "Nothing to do: an unknown order cannot be acted on and will not appear later.",
            movement,
            orderId
        )
    }
}
