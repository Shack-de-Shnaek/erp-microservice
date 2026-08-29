package finki.ukim.erp.orders.clients

import java.math.BigDecimal

interface InventoryClient {
    fun productExists(productId: Long): Boolean

    fun isStockAvailable(productId: Long, quantity: Int): Boolean

    fun getPrice(productId: Long): BigDecimal
}
