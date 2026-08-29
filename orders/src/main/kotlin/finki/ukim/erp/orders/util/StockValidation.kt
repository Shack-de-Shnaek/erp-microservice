package finki.ukim.erp.orders.util

import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.exceptions.InsufficientStockException

/**
 * Re-checked at every point stock could have moved since it was last verified: order creation,
 * order approval, and invoice generation.
 */
fun List<OrderItem>.verifyStockAvailable(inventoryClient: InventoryClient) {
    forEach { item ->
        if (!inventoryClient.isStockAvailable(item.productId, item.quantity)) {
            throw InsufficientStockException(item.productId, item.quantity)
        }
    }
}
