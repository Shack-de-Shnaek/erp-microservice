package finki.ukim.erp.inventory.integration

import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.readmodel.StockItemView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OrderPlacedSaga(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun onOrderPlaced(order: OrderPlaced) {
        order.lines.forEach { line ->
            val stockItemId = stockItemIdForProduct(line.productId.value)
            if (stockItemId == null) {
                log.warn(
                    "Skipping reservation for order={} product={}: no stock item exists",
                    order.orderId,
                    line.productId.value,
                )
                return@forEach
            }
            log.info(
                "Reserving stock for order={} product={} quantity={}",
                order.orderId,
                line.productId.value,
                line.quantity.amount,
            )
            commandGateway.sendAndWait<Any>(
                ReserveStockCommand(stockItemId, order.orderId, line.quantity),
            )
        }
    }

    private fun stockItemIdForProduct(productId: String): StockItemId? =
        queryGateway.query(
            FindStockItemByProductIdQuery(productId),
            ResponseTypes.instanceOf(StockItemView::class.java),
        ).get()?.let { StockItemId.fromString(it.stockItemId) }
}