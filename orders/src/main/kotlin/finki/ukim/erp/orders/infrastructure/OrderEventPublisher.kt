package finki.ukim.erp.orders.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.orders.domain.Order
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

data class OrderPlacedEvent(
    val orderId: String,
    val lines: List<OrderLineEvent>,
)

data class OrderLineEvent(
    val productId: String,
    val quantity: Int,
)

@Component
class OrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun publishOrderPlaced(order: Order) {
        val event = OrderPlacedEvent(
            orderId = order.orderId.value,
            lines = order.lines.map { line ->
                OrderLineEvent(
                    productId = line.productId.value,
                    quantity = line.quantity.amount,
                )
            },
        )
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send("order.placed", order.orderId.value, payload)
            .thenAccept { result ->
                log.info(
                    "Published order.placed for order={} to partition={} offset={}",
                    order.orderId.value,
                    result.recordMetadata.partition(),
                    result.recordMetadata.offset(),
                )
            }
            .exceptionally { ex ->
                log.error("Failed to publish order.placed for order={}", order.orderId.value, ex)
                null
            }
    }
}
