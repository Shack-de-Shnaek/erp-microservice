package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import java.math.BigDecimal

/**
 * An inventory service in a few fields, for the tests that need one to behave rather than merely to
 * exist.
 *
 * It keeps the parts that decide outcomes - what each product's availability is, whether it is
 * still sold, and what is currently held for which order - and nothing else. Reservations are
 * remembered because the code under test reads them back: an order is approved and invoiced against
 * its own hold, so a catalog that accepted reservations and forgot them would let every one of those
 * checks pass without ever having reserved a thing.
 *
 * Availability is deliberately *not* decremented by a reservation. What these tests are about is
 * which calls are made and what happens when one is refused, and a fake that also kept a ledger
 * would be a second implementation of inventory to get wrong.
 */
class FakeInventoryCatalog(
    private val defaultAvailable: Int = Int.MAX_VALUE,
    private val price: BigDecimal = BigDecimal("25.00")
) : InventoryCatalog {

    /** Availability per product; anything not named here has [defaultAvailable]. */
    val available = mutableMapOf<String, Int>()

    /** Products the catalogue has withdrawn. */
    val withdrawn = mutableSetOf<String>()

    /** Products inventory has never heard of. */
    val unknown = mutableSetOf<String>()

    /** What is held, by order reference - the same thing inventory's reservation view holds. */
    val reservations = mutableMapOf<String, Map<ProductId, Quantity>>()

    /** Every call made, in order, so a test can assert on the sequence and not just the end state. */
    val calls = mutableListOf<String>()

    /** When set, the next [reserve] fails with this. Cleared once it has been thrown. */
    var failNextReservation: RuntimeException? = null

    override fun findProduct(productId: ProductId): InventoryProduct? {
        if (productId.value in unknown) {
            return null
        }
        return InventoryProduct(
            id = productId.value,
            name = "product-${productId.value}",
            price = price,
            availableQuantity = available[productId.value] ?: defaultAvailable,
            active = productId.value !in withdrawn
        )
    }

    override fun reserve(orderRef: String, lines: Map<ProductId, Quantity>) {
        calls += "reserve($orderRef)"
        failNextReservation?.let {
            failNextReservation = null
            throw it
        }
        check(orderRef !in reservations) { "Order $orderRef already holds a reservation" }
        lines.forEach { (productId, quantity) -> requireAvailable(productId, quantity) }
        reservations[orderRef] = lines
    }

    override fun release(orderRef: String) {
        calls += "release($orderRef)"
        reservations.remove(orderRef)
    }

    override fun findReservation(orderRef: String): InventoryReservation? =
        reservations[orderRef]?.let { InventoryReservation(orderRef, it) }
}
