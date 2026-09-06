package finki.ukim.erp.inventory.integration

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.infrastructure.kafka.OrderPlacedEventDTO
import org.springframework.stereotype.Component

data class OrderPlaced(
    val orderId: String,
    val lines: List<OrderLine>,
)

data class OrderLine(
    val productId: ProductId,
    val quantity: Quantity,
)

@Component
class OrderEventTranslator {

    fun toInternal(dto: OrderPlacedEventDTO): OrderPlaced =
        OrderPlaced(
            orderId = dto.orderId,
            lines = dto.lines.map { line ->
                OrderLine(
                    productId = ProductId.fromString(line.productId),
                    quantity = Quantity(line.quantity),
                )
            },
        )
}