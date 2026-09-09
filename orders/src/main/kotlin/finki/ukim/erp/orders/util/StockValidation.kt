package finki.ukim.erp.orders.util

import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.clients.InventoryReservation
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.exceptions.StockNotReservedException

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
 * The same, for an amendment, judged against what the order is already holding.
 *
 * [priceItems] asks whether there is enough *free* stock for each line, and on an amendment that
 * is the wrong question: the order's own goods were put aside when it was placed, so they no
 * longer count as free. Asking it anyway would judge a line going from 2 to 5 as though the order
 * held none of it and needed all 5 off the shelf - so an order for the last of something could not
 * be amended even to *fewer* of it.
 *
 * What is asked instead is whether the shelf can cover the increase, which is exactly what
 * inventory itself will decide a moment later. This check is still not the decision - inventory is
 * the only thing that decides atomically - it is what turns the common refusal into a message
 * naming the product before a command goes anywhere.
 */
fun InventoryCatalog.priceItemsForAmendment(
    items: List<OrderItemRequest>,
    held: InventoryReservation?
): List<PricedItem> {
    val products = findProducts(items.map { ProductId(it.productId) })
    return items.map { request ->
        val productId = ProductId(request.productId)
        val quantity = Quantity(request.quantity)
        val product = checkAvailable(productId, quantity, products, alreadyHeld = held?.heldFor(productId) ?: 0)
        PricedItem(productId = productId, quantity = quantity, price = Money.fromExternal(product.price))
    }
}

/**
 * What the reservation actually holds, checked at the two points an order moves on: approval and
 * invoicing. Both are driven by the external command handlers in [finki.ukim.erp.orders.handlers],
 * which read the quantities straight off the order they are about to act on.
 *
 * It asks about the *reservation* rather than about availability, and the difference is not
 * cosmetic. The order's own goods were put aside when it was placed, so they no longer count as
 * available - asking "is there enough free stock for this order" would be asking whether a
 * *second* copy of it could be filled, and an order for the last of something would fail its own
 * approval. What matters here is only whether the hold taken at placement is still there.
 *
 * It can be gone: an amendment that could not be re-reserved, a release that ran when it should
 * not have, an inventory database restored from behind. In every one of those the order is no
 * longer backed by goods, and approving or invoicing it would promise what nobody is holding.
 */
fun InventoryCatalog.verifyStockReserved(orderRef: String, quantitiesByProduct: Map<ProductId, Quantity>) {
    val reservation = findReservation(orderRef)
        ?: throw StockNotReservedException("Inventory holds no reservation for order $orderRef")

    quantitiesByProduct.forEach { (productId, quantity) ->
        val held = reservation.heldFor(productId)
        if (held < quantity.value) {
            throw StockNotReservedException(
                "Order $orderRef needs ${quantity.value} of product $productId, " +
                    "but inventory is holding only $held"
            )
        }
    }
}

/** The total each product is wanted in, so an order naming one twice is held for the sum of both. */
fun totalPerProduct(items: List<PricedItem>): Map<ProductId, Quantity> =
    items.groupBy { it.productId }
        .mapValues { (_, lines) -> Quantity(lines.sumOf { it.quantity.value }) }
