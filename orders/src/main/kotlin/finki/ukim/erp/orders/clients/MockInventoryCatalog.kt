package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.ProductId
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
}
