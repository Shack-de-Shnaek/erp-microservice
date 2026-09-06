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
