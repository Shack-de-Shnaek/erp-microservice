package finki.ukim.erp.orders.clients

import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Stand-in for the real inventory-service integration (Feign/HTTP), which will replace this
 * later. Keeps a small in-memory catalog so stock/price checks are deterministic for now.
 */
@Component
class MockInventoryClient : InventoryClient {

    private data class MockProduct(val price: BigDecimal, val stock: Int)

    private val catalog: Map<Long, MockProduct> = mapOf(
        1L to MockProduct(BigDecimal("19.99"), 100),
        2L to MockProduct(BigDecimal("49.50"), 25),
        3L to MockProduct(BigDecimal("5.00"), 0),
        4L to MockProduct(BigDecimal("199.99"), 5)
    )

    override fun productExists(productId: Long): Boolean = catalog.containsKey(productId)

    override fun isStockAvailable(productId: Long, quantity: Int): Boolean {
        val product = catalog[productId] ?: return false
        return quantity in 1..product.stock
    }

    override fun getPrice(productId: Long): BigDecimal =
        catalog[productId]?.price ?: throw IllegalStateException("Unknown product $productId")
}
