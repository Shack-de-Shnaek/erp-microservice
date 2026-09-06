package finki.ukim.erp.orders.clients.fallbacks

import feign.FeignException
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.clients.InventoryProductResponse
import finki.ukim.erp.orders.clients.InventoryStockResponse
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

/**
 * What [InventoryClient] does when the circuit breaker will not let a call through - either
 * because inventory failed, or because it has failed often enough recently that the breaker is
 * open and is refusing to try.
 *
 * ## Why this is not a value
 *
 * The exercise's fallback returns a safe default - `false` for an existence check. That is the
 * right answer when the question is genuinely a boolean, but here it would be a lie with
 * consequences: returning `null`/empty says "inventory does not stock this product", which is
 * indistinguishable from a real 404. The caller would reject a perfectly valid order with "product
 * 7 does not exist in inventory", the customer would believe it, and nobody would learn that
 * inventory was down.
 *
 * So the conservative default here is not a value but a refusal: fail the call, immediately, with
 * a domain exception that says what actually happened. [InventoryUnavailableException] maps to
 * 503, which tells the caller to try again later - the truth. Nothing hangs, no order is created
 * on a guess, and the service stays up and answers every request that does not need inventory.
 *
 * ## Why a factory
 *
 * With the circuit breaker enabled, *every* failure reaches the fallback, including a 404 that
 * inventory answered perfectly correctly. Only [FallbackFactory] is handed the cause, so only a
 * factory-built fallback can tell "inventory says no such product" apart from "inventory said
 * nothing at all" - and the two have to end up as different HTTP statuses.
 */
class InventoryClientFallback(private val cause: Throwable) : InventoryClient {

    override fun getProduct(productId: String): InventoryProductResponse = throw translated()

    override fun getStock(productId: String): InventoryStockResponse = throw translated()

    private fun translated(): RuntimeException = when (cause) {
        // Inventory answered, and its answer was "no such product". Let it through untouched so
        // FeignInventoryCatalog can turn it into a 404 rather than a 503.
        is FeignException.NotFound -> cause
        else -> {
            log.warn("Inventory call fell back: {}", cause.toString())
            InventoryUnavailableException(cause)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(InventoryClientFallback::class.java)
    }
}

@Component
class InventoryClientFallbackFactory : FallbackFactory<InventoryClient> {
    override fun create(cause: Throwable): InventoryClient = InventoryClientFallback(cause)
}
