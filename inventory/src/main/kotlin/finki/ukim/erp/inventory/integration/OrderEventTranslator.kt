package finki.ukim.erp.inventory.integration

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.infrastructure.kafka.OrderApprovedEventDTO
import finki.ukim.erp.inventory.infrastructure.kafka.OrderNullifiedEventDTO
import org.springframework.stereotype.Component

/**
 * An order that has been approved, in inventory's own words: a reference to hold the reservation
 * under, and the lines to hold.
 */
data class ApprovedOrder(
    val orderRef: String,
    val lines: List<OrderLine>,
)

/**
 * An order that is void, in inventory's own words. Deliberately just the reference: what has to be
 * released is whatever this service reserved, which its own reservation record knows and the
 * message does not.
 */
data class NullifiedOrder(
    val orderRef: String,
    val reason: String?,
)

data class OrderLine(
    val productId: ProductId,
    val quantity: Quantity,
)

/**
 * The anti-corruption layer: somebody else's JSON in, this service's types out. Nothing downstream
 * of these methods knows a Kafka message was involved.
 */
@Component
class OrderEventTranslator {

    fun toInternal(dto: OrderApprovedEventDTO): ApprovedOrder =
        ApprovedOrder(
            orderRef = dto.orderId,
            lines = dto.lines.map { line ->
                OrderLine(
                    productId = ProductId.fromString(line.productId),
                    quantity = Quantity(line.quantity),
                )
            },
        )

    fun toInternal(dto: OrderNullifiedEventDTO): NullifiedOrder =
        NullifiedOrder(orderRef = dto.orderId, reason = dto.reason)
}
