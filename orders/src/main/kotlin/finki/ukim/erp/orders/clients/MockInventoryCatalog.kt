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
 */
@Component
@Primary
@Profile("mock-inventory")
class MockInventoryCatalog : InventoryCatalog {

    private val catalog: Map<Long, InventoryProduct> = mapOf(
        1L to InventoryProduct(1L, "Desk lamp", BigDecimal("19.99"), 100),
        2L to InventoryProduct(2L, "Office chair", BigDecimal("49.50"), 25),
        3L to InventoryProduct(3L, "Notebook", BigDecimal("5.00"), 0),
        4L to InventoryProduct(4L, "Standing desk", BigDecimal("199.99"), 5)
    )

    override fun findProduct(productId: ProductId): InventoryProduct? = catalog[productId.value]
}
