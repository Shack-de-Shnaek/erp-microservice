package finki.ukim.erp.orders.infrastructure.kafka

import finki.ukim.erp.orders.OrderId
import org.springframework.stereotype.Service

/**
 * Why an order stopped having stock behind it, in this service's terms.
 *
 * A mirror of inventory's `ReleaseReason`, deliberately re-declared rather than imported: it is
 * part of a message contract, and a shared enum would mean a shared jar both services have to
 * upgrade in step. [UNKNOWN] is what a value this service has not been taught about becomes, so a
 * reason inventory adds later cannot start closing orders before anybody here has decided it should.
 */
enum class StockReleaseReason {
    /** The order ended and this service said so; the release is its own decision coming back. */
    ORDER_NULLIFIED,

    /** Inventory took the goods back. The order believes it is backed and is not. */
    WITHDRAWN_BY_INVENTORY,

    /** Something else. Treated as news, but never acted on. */
    UNKNOWN
}

/** An order that no longer has stock behind it, and why. */
data class StockWithdrawnFromOrder(
    val orderId: OrderId,
    val reason: StockReleaseReason,
    val detail: String
)

/**
 * Turns inventory's stock messages into this service's own types.
 *
 * Types in, types out, no dependencies - the same shape as [ProductDeactivatedTranslator], and for
 * the same reason: what to *do* about a withdrawn reservation depends on the order's own state, so
 * it belongs in a handler that can read one, not here.
 */
@Service
class StockEventTranslator {

    fun toOrderId(event: StockConfirmedExternalEventDTO): OrderId = OrderId(event.orderRef)

    fun toWithdrawal(event: StockReleasedExternalEventDTO): StockWithdrawnFromOrder =
        StockWithdrawnFromOrder(
            orderId = OrderId(event.orderRef),
            reason = runCatching { StockReleaseReason.valueOf(event.reason) }
                .getOrDefault(StockReleaseReason.UNKNOWN),
            detail = buildString {
                append("inventory released ")
                append(event.quantity?.toString() ?: "stock")
                event.stockItemId?.let { append(" of stock item ").append(it) }
                append(" (reason ").append(event.reason).append(")")
            }
        )
}
