package finki.ukim.erp.inventory.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.inventory.integration.OrderEventTranslator
import finki.ukim.erp.inventory.integration.OrderPlacedSaga
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaEventConsumer(
    private val objectMapper: ObjectMapper,
    private val orderEventTranslator: OrderEventTranslator,
    private val orderPlacedSaga: OrderPlacedSaga,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["order.placed"])
    fun onOrderPlaced(message: String) {
        log.info("Received order.placed message: {}", message)
        val dto = objectMapper.readValue(message, OrderPlacedEventDTO::class.java)
        val order = orderEventTranslator.toInternal(dto)
        orderPlacedSaga.onOrderPlaced(order)
    }
}