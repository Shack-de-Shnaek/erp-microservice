package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.clients.fallbacks.InventoryClientFallbackFactory
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal

/**
 * What orders needs to know about a product. Deliberately narrow: only the fields the ordering
 * flow actually reads, so the contract published to the inventory service (see
 * `InventoryClientPactTest`) stays small and inventory is free to change everything else.
 */
data class InventoryProduct(
    val id: Long,
    val name: String,
    val price: BigDecimal,
    val availableQuantity: Int
)

/**
 * The HTTP contract with the inventory service. This interface *is* the consumer side of the
 * Pact: the shape of the request and the response asserted in `InventoryClientPactTest` is the
 * shape inventory has to honour.
 *
 * One interface per target service, not per endpoint - both product lookups belong to the same
 * conversation with the same team, and grouping them means one breaker and one fallback covers
 * the service as a whole.
 *
 * Addressing: there is no `url`, and that is the point. `name` is looked up in Consul, so the
 * client is load-balanced across whatever instances of `inventory` are currently registered and
 * healthy - a stopped instance stops receiving traffic without anything here being changed or
 * redeployed. The name has to match the target service's `spring.application.name` exactly, which
 * is what it is registered under.
 *
 * Prefer talking to [InventoryCatalog] from domain code - it maps transport failures onto domain
 * exceptions.
 */
@FeignClient(
    name = "inventory",
    path = "/products",
    fallbackFactory = InventoryClientFallbackFactory::class
)
interface InventoryClient {

    /** Returns the product, or 404 if inventory does not know it. */
    @GetMapping("/{productId}")
    fun getProduct(@PathVariable("productId") productId: Long): InventoryProduct

    /**
     * The same lookup for a whole order at once. Products inventory does not know are simply
     * absent from the response rather than being an error, because a single unknown product in a
     * ten-line order is a fact about that line, not a failed request.
     *
     * This exists because approval and invoicing check every line of an order at once, and doing
     * that one HTTP call per line puts the latency of a large order at the mercy of its length -
     * inside a command's unit of work, holding a database transaction open the whole time.
     */
    @GetMapping
    fun getProducts(@RequestParam("ids") ids: List<Long>): List<InventoryProduct>
}
