package finki.ukim.erp.orders.clients

import feign.FeignException
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * The domain-facing port onto inventory - an anti-corruption layer. Everything above this line
 * speaks in this service's own types ([ProductId], [Quantity]); everything below it speaks
 * inventory's HTTP contract, in plain numbers. Neither side has to change when the other does.
 */
interface InventoryCatalog {

    /** The product, or null if inventory does not know it. */
    fun findProduct(productId: ProductId): InventoryProduct?

    /**
     * Several products in one go. Ids inventory does not know are absent from the result.
     *
     * This repeats [findProduct] rather than calling a batch endpoint, because inventory has none:
     * it exposes a single product by id and a paged listing of the whole catalogue, and neither is
     * "these four ids". So an n-line order costs 2n calls to inventory. That is a real cost inside
     * a command's unit of work, and the fix is a batch endpoint on inventory's side
     * (`GET /api/products?ids=...` and the same on `/api/stock`), not something orders can arrange
     * on its own.
     */
    fun findProducts(productIds: Collection<ProductId>): Map<ProductId, InventoryProduct> =
        productIds.distinct().mapNotNull { productId -> findProduct(productId)?.let { productId to it } }.toMap()

    fun requireProduct(productId: ProductId): InventoryProduct =
        findProduct(productId) ?: throw ProductNotFoundException(productId)

    /** The product, having checked it can currently cover [quantity]. */
    fun requireAvailable(productId: ProductId, quantity: Quantity): InventoryProduct =
        checkAvailable(productId, quantity, mapOf(productId to requireProduct(productId)))

    /**
     * The same check against products already fetched. Split out so the callers that need many
     * lines checked can pay for one round trip rather than one per line, without either of them
     * restating what "available" means.
     */
    fun checkAvailable(
        productId: ProductId,
        quantity: Quantity,
        products: Map<ProductId, InventoryProduct>
    ): InventoryProduct {
        val product = products[productId] ?: throw ProductNotFoundException(productId)
        if (product.availableQuantity < quantity.value) {
            throw InsufficientStockException(productId, quantity)
        }
        return product
    }
}

@Component
class FeignInventoryCatalog(
    private val inventoryClient: InventoryClient
) : InventoryCatalog {

    /**
     * One product, assembled from inventory's two resources: the catalogue entry says what it is,
     * the stock ledger says how much of it is free.
     *
     * A missing catalogue entry means the product does not exist and the answer is null. A missing
     * *stock* entry does not: the product is real and simply has nothing on the shelf, so it comes
     * back with an availability of zero and the caller's own check decides what that means.
     */
    override fun findProduct(productId: ProductId): InventoryProduct? {
        val product = translatingFailures { inventoryClient.getProduct(productId.value) } ?: return null
        val stock = translatingFailures { inventoryClient.getStock(productId.value) }
        return InventoryProduct(
            id = product.productId,
            name = product.name,
            // Inventory publishes no price; see the note on InventoryProduct.
            price = BigDecimal.ZERO,
            availableQuantity = stock?.available ?: 0
        )
    }

    /**
     * Null means inventory answered 404 - it has no such product. Anything else that went wrong
     * is rethrown as [InventoryUnavailableException], because a 5xx or a connection failure is
     * not "the product does not exist"; saying so would silently reject valid orders whenever
     * inventory is down.
     *
     * With the circuit breaker on, most failures never get this far - the fallback has already
     * made the same distinction. This still matters where the breaker is not in play: the Pact
     * tests build the client directly, and so does anything running with Feign's circuit breaker
     * support disabled.
     */
    private fun <T> translatingFailures(call: () -> T): T? =
        try {
            call()
        } catch (ex: FeignException.NotFound) {
            null
        } catch (ex: FeignException) {
            throw InventoryUnavailableException(ex)
        }
}
