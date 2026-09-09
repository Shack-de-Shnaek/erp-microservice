package finki.ukim.erp.orders.clients

import feign.FeignException
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import finki.ukim.erp.orders.exceptions.ProductNotAvailableException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import finki.ukim.erp.orders.exceptions.StockNotReservedException
import finki.ukim.erp.orders.exceptions.StockReservationRejectedException
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
     * Asks inventory to hold [lines] for [orderRef], all of them or none.
     *
     * This is the point at which an order stops being a hope and becomes a claim on real goods.
     * Everything checked before it - that the products exist, are still sold, and had enough on
     * the shelf a moment ago - is a courtesy that produces better messages; inventory is the only
     * thing that can decide it for real, and this is where it does.
     */
    fun reserve(orderRef: String, lines: Map<ProductId, Quantity>)

    /**
     * Moves the hold for [orderRef] onto [lines], as one operation.
     *
     * The whole set, not a delta: inventory decides for itself what went up, what came down, what
     * is new and what is gone. What matters to a caller is that the order keeps what it is
     * keeping - only an increase is contested - so amending an order for the last of something no
     * longer risks losing it to another order in the gap. There is no gap.
     *
     * Throws [StockNotReservedException] when inventory holds nothing for this order. That is a
     * real answer rather than a reason to reserve instead: the order was accepted on goods that
     * are no longer being held, and quietly opening a fresh hold would paper over exactly the
     * state [finki.ukim.erp.orders.util.verifyStockReserved] exists to catch.
     */
    fun amend(orderRef: String, lines: Map<ProductId, Quantity>)

    /**
     * Gives back whatever is held for [orderRef].
     *
     * Throws [StockNotReservedException] if inventory holds nothing. Releasing what is not there
     * is not success - it means the order was already unbacked, which somebody wants to know
     * about - so the tolerance a compensating caller needs lives in [releaseQuietly] rather than
     * being built into every release.
     */
    fun release(orderRef: String)

    /**
     * [release], for the compensating callers, where failing while undoing helps nobody.
     *
     * Swallows everything and logs it, including the "nothing was held" that [release] treats as
     * an error - during compensation that is often the truth, because the reservation being given
     * back may never have been taken. The distinction is worth two methods: a caller undoing its
     * own half-finished work has different needs from one being told the order is unbacked, and
     * one method serving both meant the second caller silently got the first one's answer.
     */
    fun releaseQuietly(orderRef: String) {
        runCatching { release(orderRef) }
            .onFailure { catalogLogger.error("Could not release the stock held for order {}", orderRef, it) }
    }

    /** What inventory is holding for [orderRef], or null if it holds nothing. */
    fun findReservation(orderRef: String): InventoryReservation?

    /**
     * The same check against products already fetched. Split out so the callers that need many
     * lines checked can pay for one round trip rather than one per line, without either of them
     * restating what "available" means.
     */
    fun checkAvailable(
        productId: ProductId,
        quantity: Quantity,
        products: Map<ProductId, InventoryProduct>,
        alreadyHeld: Int = 0
    ): InventoryProduct {
        val product = products[productId] ?: throw ProductNotFoundException(productId)
        // Before the numbers: a withdrawn product is not "some of it left", it is not for sale, and
        // saying so is more use than an arithmetic complaint about a shelf nobody may draw from.
        // Only asking for *more* of it counts, though: an order already holding a withdrawn product
        // must still be able to lower that line or drop it, which is the one change it most
        // obviously should be allowed to make.
        if (!product.active && quantity.value > alreadyHeld) {
            throw ProductNotAvailableException(productId)
        }
        // What this order may take is what is free plus what it is already holding. Counting only
        // what is free would judge an amendment as though the order had first given its own stock
        // back - so raising a line on the last of something would fail against the order's own hold.
        if (product.availableQuantity + alreadyHeld < quantity.value) {
            throw InsufficientStockException(productId, quantity)
        }
        return product
    }
}

private val catalogLogger = org.slf4j.LoggerFactory.getLogger(InventoryCatalog::class.java)

/** What inventory is holding for one order, in the ids this service's own orders are written in. */
data class InventoryReservation(
    val orderRef: String,
    val quantitiesByProduct: Map<ProductId, Quantity>
) {
    fun heldFor(productId: ProductId): Int = quantitiesByProduct[productId]?.value ?: 0
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
            availableQuantity = stock?.available ?: 0,
            // Inventory says INACTIVE for a product it has withdrawn. Anything else - including a
            // status it has not told us about - is read as still for sale, so a new value there
            // cannot start silently rejecting orders until this service has been taught about it.
            active = !product.status.equals("INACTIVE", ignoreCase = true)
        )
    }

    override fun reserve(orderRef: String, lines: Map<ProductId, Quantity>) {
        val request = CreateReservationRequest(
            orderRef = orderRef,
            lines = lines.map { (productId, quantity) ->
                ReservationLineRequest(productId = productId.value, quantity = quantity.value)
            }
        )
        // Not translatingFailures: a 404 here would not mean "nothing to see", and a 400 is
        // inventory refusing on grounds the customer needs to be told about rather than a fault.
        try {
            inventoryClient.createReservation(request)
        } catch (ex: FeignException.BadRequest) {
            throw StockReservationRejectedException(orderRef, reasonFrom(ex))
        } catch (ex: FeignException) {
            throw InventoryUnavailableException(ex)
        }
    }

    override fun amend(orderRef: String, lines: Map<ProductId, Quantity>) {
        val request = AmendReservationRequest(
            lines = lines.map { (productId, quantity) ->
                ReservationLineRequest(productId = productId.value, quantity = quantity.value)
            }
        )
        try {
            inventoryClient.amendReservation(orderRef, request)
        } catch (ex: FeignException.NotFound) {
            throw StockNotReservedException("Inventory holds no reservation for order $orderRef to amend")
        } catch (ex: FeignException.BadRequest) {
            throw StockReservationRejectedException(orderRef, reasonFrom(ex))
        } catch (ex: FeignException) {
            throw InventoryUnavailableException(ex)
        }
    }

    /**
     * A 404 is reported, not swallowed: it says inventory holds nothing for this order, and an
     * order that believed it was backed by goods needs that to surface rather than read as a
     * successful release. Callers undoing their own work go through
     * [InventoryCatalog.releaseQuietly], which is where the tolerance belongs.
     */
    override fun release(orderRef: String) {
        try {
            inventoryClient.releaseReservation(orderRef)
        } catch (ex: FeignException.NotFound) {
            throw StockNotReservedException("Inventory holds no reservation for order $orderRef to release")
        } catch (ex: FeignException) {
            throw InventoryUnavailableException(ex)
        }
    }

    override fun findReservation(orderRef: String): InventoryReservation? {
        val reservation = translatingFailures { inventoryClient.getReservation(orderRef) } ?: return null
        return InventoryReservation(
            orderRef = reservation.orderRef,
            quantitiesByProduct = reservation.lines.associate {
                ProductId(it.productId) to Quantity(it.quantity)
            }
        )
    }

    /**
     * Inventory's own words for why it refused, dug out of the error body it answered with.
     *
     * The body is `{"status":400,"message":"..."}`, and the message is the whole value of the
     * exchange - "Insufficient stock for product X: 5 requested, 2 available" is something a
     * customer can act on, where "the inventory service returned 400" is not. If it cannot be
     * read for any reason, the raw body is still better than nothing.
     */
    private fun reasonFrom(ex: FeignException): String {
        val body = ex.contentUTF8()
        return runCatching { objectMapper.readTree(body).path("message").asText().ifBlank { body } }
            .getOrDefault(body)
            .ifBlank { "inventory refused the reservation" }
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

    private companion object {
        val objectMapper = ObjectMapper()
    }
}
