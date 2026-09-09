package finki.ukim.erp.orders.infrastructure.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * The shape of what arrives on inventory's topic, as understood *here*.
 *
 * These classes belong to orders, not to inventory, and that is the whole idea of an
 * anti-corruption layer. Importing inventory's own event class would look like less code and would
 * quietly hand another team the ability to break this service by renaming a field - and would need
 * a shared jar that both services then have to upgrade in step, which is most of the coupling a
 * microservice was supposed to avoid.
 *
 * Two rules make these tolerant of the producer moving:
 *
 * - `ignoreUnknown = true`, so inventory can add fields without this service failing on them.
 * - Anything orders does not need is nullable and defaulted; anything it *does* need is not, so a
 *   message missing it fails immediately and loudly rather than being turned into a command with a
 *   hole in it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductDeactivatedExternalEventDTO(
    /**
     * Required: without it there is nothing to act on.
     *
     * A bare string, because that is what inventory puts on the wire - its
     * `ProductDeactivatedExternalEvent` is `{"productId": "<uuid>"}`, one flat field. An earlier
     * version of this DTO expected `{"productId": {"value": 7}}`, which was a guess at a shape
     * nothing ever published; every message would have failed to parse and been logged and dropped.
     */
    val productId: String,
    val name: String? = null,
    val deactivatedAt: String? = null
)


/**
 * Inventory's `StockConfirmedExternalEvent` - the goods for an order have left the shelf.
 *
 * [orderRef] is the order's own id: inventory keys a reservation by the reference orders gave it
 * when the stock was taken, so a message about a stock item is answerable against an order without
 * anything here knowing what a stock item is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StockConfirmedExternalEventDTO(
    val orderRef: String,
    val stockItemId: String? = null,
    val quantity: Int? = null
)

/**
 * Inventory's `StockReservationReleasedExternalEvent` and `StockReturnedExternalEvent`, which this
 * service reads as one thing: an order no longer has stock behind it.
 *
 * The two are different facts on inventory's side - a claim let go, versus goods physically put
 * back - and the same fact here, so they share a DTO and a handler.
 *
 * [reason] is required, and that is the point of it. It says whether this service asked for the
 * release or inventory decided it, and getting that wrong in either direction is severe: treating
 * our own cancellations as withdrawals would reject every order the moment it was cancelled, and
 * treating withdrawals as our own would leave orders standing on stock that is gone. A message
 * without it fails to parse and is logged rather than being guessed at.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StockReleasedExternalEventDTO(
    val orderRef: String,
    val reason: String,
    val stockItemId: String? = null,
    val quantity: Int? = null
)
