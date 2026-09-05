package finki.ukim.erp.orders.util

import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.dto.OrderItemRequest

/**
 * Turns requested items into priced ones, having checked each product exists and can cover the
 * requested quantity. Pricing happens out here rather than in the aggregate: a price looked up
 * from another service is not something the aggregate can be trusted to fetch for itself.
 *
 * One batch lookup covers the whole request, then each line is checked on its own - so an order
 * that names the same product twice is still judged line by line, as it was before the lookup
 * was batched.
 */
fun InventoryCatalog.priceItems(items: List<OrderItemRequest>): List<PricedItem> {
    val products = findProducts(items.map { ProductId(it.productId) })
    return items.map { request ->
        val productId = ProductId(request.productId)
        val quantity = Quantity(request.quantity)
        val product = checkAvailable(productId, quantity, products)
        PricedItem(productId = productId, quantity = quantity, price = Money.fromExternal(product.price))
    }
}

/**
 * Re-checked at every point stock could have moved since it was last verified: order approval and
 * invoice generation. Both are driven by the external command handlers in
 * [finki.ukim.erp.orders.handlers], which read the quantities straight off the order they are
 * about to act on.
 */
fun InventoryCatalog.verifyStockAvailable(quantitiesByProduct: Map<ProductId, Quantity>) {
    val products = findProducts(quantitiesByProduct.keys)
    quantitiesByProduct.forEach { (productId, quantity) -> checkAvailable(productId, quantity, products) }
}
