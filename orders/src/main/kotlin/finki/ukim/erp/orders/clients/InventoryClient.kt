package finki.ukim.erp.orders.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import finki.ukim.erp.orders.clients.fallbacks.InventoryClientFallbackFactory
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    val availableQuantity: Int
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
}
