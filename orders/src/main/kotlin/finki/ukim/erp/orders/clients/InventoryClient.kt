package finki.ukim.erp.orders.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import finki.ukim.erp.orders.clients.fallbacks.InventoryClientFallbackFactory
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import java.math.BigDecimal

/**
 * What orders needs to know about a product, in orders' own terms.
 *
 * Inventory does not have a resource shaped like this. It splits the catalogue from the stock
 * ledger - `/api/products/{id}` says what a product *is*, `/api/stock/{id}` says how much of it
 * there is - so this is assembled from both by [FeignInventoryCatalog] rather than deserialized
 * from one response.
 *
 * ## About [price]
 *
 * Inventory's product resource carries no price: it has `sku`, `name`, `unitOfMeasure` and
 * `status`, and its `Product` aggregate has no money on it at all. Nothing in inventory can answer
 * "what does this cost", so this field arrives as zero from the real client, and only the
 * `mock-inventory` profile fills it in. Pricing is an unresolved question between the two teams -
 * either inventory grows a price, or a third owner of it appears - and until it is answered, an
 * order priced from the live inventory service totals zero.
 */
data class InventoryProduct(
    val id: String,
    val name: String,
    val price: BigDecimal,
    val availableQuantity: Int,
    /**
     * Whether the catalogue still sells it. A withdrawn product may sit on a full shelf and still
     * not be orderable, so availability alone never answers "can this be ordered" - inventory
     * refuses to reserve stock for an inactive product, and an order that got as far as asking
     * would be rejected there rather than here.
     */
    val active: Boolean = true
)

/** Inventory's `ProductView`, as this service reads it. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InventoryProductResponse(
    val productId: String,
    val name: String,
    val sku: String? = null,
    val unitOfMeasure: String? = null,
    val status: String? = null
)

/** One line of a reservation request: how much of a product this order needs held for it. */
data class ReservationLineRequest(
    val productId: String,
    val quantity: Int
)

/** The body of `POST /api/reservations`: an order's whole hold, taken in one call. */
data class CreateReservationRequest(
    val orderRef: String,
    val lines: List<ReservationLineRequest>
)

/**
 * The body of `PUT /api/reservations/{orderRef}`: every line the order should hold after the
 * amendment, not the ones that changed.
 *
 * Stating the whole set is what makes the operation one decision. Inventory works out for itself
 * which lines went up, which came down, which are new and which are gone, and moves all of them
 * together - so the order never stands empty-handed between giving its stock back and asking for
 * it again, which is the race a release followed by a fresh reservation could not avoid.
 */
data class AmendReservationRequest(
    val lines: List<ReservationLineRequest>
)

/**
 * Inventory's `ReservationView` - what it is holding for one order.
 *
 * `stockItemId` is inventory's own addressing and orders has no use for it; `productId` is what an
 * order's lines are written in, which is what makes this answerable against an order at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InventoryReservationResponse(
    val orderRef: String,
    val status: String? = null,
    val lines: List<InventoryReservationLine> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InventoryReservationLine(
    val productId: String,
    val quantity: Int,
    val stockItemId: String? = null
)

/**
 * Inventory's `StockItemView`. `onHand` is everything on the shelf and `reserved` is the part of it
 * already committed to somebody else's order, so what orders may still promise a customer is the
 * difference - never `onHand` on its own.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InventoryStockResponse(
    val productId: String,
    val onHand: Int = 0,
    val reserved: Int = 0,
    val stockItemId: String? = null,
    val reorderThreshold: Int? = null
) {
    val available: Int get() = (onHand - reserved).coerceAtLeast(0)
}

/**
 * The HTTP contract with the inventory service. This interface *is* the consumer side of the
 * Pact: the shape of the request and the response asserted in `InventoryClientPactTest` is the
 * shape inventory has to honour.
 *
 * One interface per target service, not per endpoint - all of these belong to the same
 * conversation with the same team, and grouping them means one breaker and one fallback covers
 * the service as a whole. That is also why there is no `path` on the annotation: the two resources
 * orders needs sit under different prefixes, so each method carries its full path.
 *
 * Addressing: there is no `url`, and that is the point. `name` is looked up in Consul, so the
 * client is load-balanced across whatever instances of `inventory` are currently registered and
 * healthy - a stopped instance stops receiving traffic without anything here being changed or
 * redeployed. The name has to match the target service's `spring.application.name` exactly, which
 * is what it is registered under.
 *
 * Prefer talking to [InventoryCatalog] from domain code - it maps transport failures onto domain
 * exceptions and puts the two calls below back together.
 */
@FeignClient(
    name = "inventory",
    fallbackFactory = InventoryClientFallbackFactory::class
)
interface InventoryClient {

    /** Returns the product, or 404 if inventory does not know it. */
    @GetMapping("/api/products/{productId}")
    fun getProduct(@PathVariable("productId") productId: String): InventoryProductResponse

    /**
     * The stock ledger for one product, or 404 if inventory tracks no stock for it.
     *
     * A product with no stock item is not an error: it exists in the catalogue and simply has
     * nothing on the shelf, which [FeignInventoryCatalog] reads as an availability of zero.
     */
    @GetMapping("/api/stock/{productId}")
    fun getStock(@PathVariable("productId") productId: String): InventoryStockResponse

    /**
     * Puts an order's stock aside, all lines or none.
     *
     * A command in the proper sense: orders is not telling inventory something has happened, it is
     * asking for something to be done and cannot proceed without the answer. Inventory answers 400
     * when it will not - an unknown or withdrawn product, or not enough left - and the body carries
     * the reason, which is the half a caller can act on.
     */
    // `consumes` is not decoration. Without it Feign has no content type for the body and falls
    // back to form encoding, so a perfectly good JSON payload arrives labelled
    // `application/x-www-form-urlencoded` and Spring refuses it with a 415 before any of this
    // service's code runs. The GETs above need no such thing - they have no body to label.
    @PostMapping("/api/reservations", consumes = ["application/json"])
    fun createReservation(@RequestBody request: CreateReservationRequest): InventoryReservationResponse

    /**
     * Moves an order's existing hold to a new set of lines, all of it or none.
     *
     * A command like [createReservation], and refused the same way - 400 with the reason in the
     * body when inventory will not do it. The difference is that it never lets go of what the
     * order is keeping: only the part of a line that is going *up* is contested, so an amendment
     * that lowers a quantity, drops a product or leaves a line alone cannot lose that stock to
     * another order.
     *
     * 404 means inventory holds no reservation for this order, which is a real answer and not a
     * success - there was nothing to amend, and the order is not backed by the goods the caller
     * believed it was.
     */
    @PutMapping("/api/reservations/{orderRef}", consumes = ["application/json"])
    fun amendReservation(
        @PathVariable("orderRef") orderRef: String,
        @RequestBody request: AmendReservationRequest
    ): InventoryReservationResponse

    /** Gives back everything held for the order. 404 when there is nothing held. */
    @DeleteMapping("/api/reservations/{orderRef}")
    fun releaseReservation(@PathVariable("orderRef") orderRef: String)

    /** What inventory is currently holding for the order, or 404 if it holds nothing. */
    @GetMapping("/api/reservations/{orderRef}")
    fun getReservation(@PathVariable("orderRef") orderRef: String): InventoryReservationResponse
}
