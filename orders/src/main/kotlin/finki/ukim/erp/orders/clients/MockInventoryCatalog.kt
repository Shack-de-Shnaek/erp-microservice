package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Lets the orders service run on its own, before inventory is deployed alongside it. Enabled with
 * the `mock-inventory` profile, where it takes precedence over the Feign-backed
 * [FeignInventoryCatalog]; without that profile the real client is the only implementation.
 *
 * The ids are readable stand-ins rather than the UUIDs a real inventory generates. Nothing here
 * depends on their shape - a product id is an opaque string as far as this service is concerned -
 * and `product-1` in a failing assertion says more than a UUID does. It is also the only place in
 * the service where a price exists at all; see the note on [InventoryProduct].
 */
@Component
@Primary
@Profile("mock-inventory")
class MockInventoryCatalog : InventoryCatalog {

    private val catalog: Map<String, InventoryProduct> = listOf(
        InventoryProduct("product-1", "Desk lamp", BigDecimal("19.99"), 100),
        InventoryProduct("product-2", "Office chair", BigDecimal("49.50"), 25),
        InventoryProduct("product-3", "Notebook", BigDecimal("5.00"), 0),
        InventoryProduct("product-4", "Standing desk", BigDecimal("199.99"), 5)
    ).associateBy { it.id }

    override fun findProduct(productId: ProductId): InventoryProduct? = catalog[productId.value]

    /**
     * Reservations are remembered, not merely accepted.
     *
     * A mock that said yes to everything and forgot would make the two checks that read a
     * reservation back - at approval and at invoicing - pass for orders that never reserved
     * anything, which is exactly the failure this profile exists to let someone rehearse. It does
     * not, however, decrement anything: the point of the profile is to run without inventory, not
     * to reimplement it.
     */
    private val reservations = java.util.concurrent.ConcurrentHashMap<String, Map<ProductId, Quantity>>()

    override fun reserve(orderRef: String, lines: Map<ProductId, Quantity>) {
        lines.forEach { (productId, quantity) -> requireAvailable(productId, quantity) }
        reservations[orderRef] = lines
    }

    override fun release(orderRef: String) {
        reservations.remove(orderRef)
    }

    override fun findReservation(orderRef: String): InventoryReservation? =
        reservations[orderRef]?.let { InventoryReservation(orderRef, it) }
}
