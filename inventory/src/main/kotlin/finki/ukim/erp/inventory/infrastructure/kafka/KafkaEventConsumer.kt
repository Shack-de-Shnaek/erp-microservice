package finki.ukim.erp.inventory.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.inventory.integration.OrderEventTranslator
import finki.ukim.erp.inventory.integration.OrderLifecycleSaga
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * The way into this service from the orders service's topics.
 *
 * One topic, `order.nullified`, which orders publishes for all three ways an order can end -
 * cancellation, rejection, invoice reversal - and which is where a reservation is given back.
 *
 * Nothing else is read, and the absences are deliberate. `order.created` and `order.approved` used
 * to matter: stock was taken when an order was approved. It is now taken by a synchronous call to
 * `POST /api/reservations` made while the order is being placed, so that orders cannot accept an
 * order it has no goods for - which leaves both topics with nothing to say to this service.
 * `invoice.generated` and `invoice.reversed` are about money; the reversal reaches us as a
 * nullification anyway.
 */
@Component
class KafkaEventConsumer(
    private val objectMapper: ObjectMapper,
    private val orderEventTranslator: OrderEventTranslator,
    private val orderLifecycleSaga: OrderLifecycleSaga,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Clears the reservations held for an order, by the order's id. One listener covers every way
     * an order can end, because orders says all of them here.
     */
    @KafkaListener(topics = [ORDER_NULLIFIED_TOPIC])
    fun onOrderNullified(message: String) {
        log.info("Received {} message: {}", ORDER_NULLIFIED_TOPIC, message)
        val dto = objectMapper.readValue(message, OrderNullifiedEventDTO::class.java)
        orderLifecycleSaga.onOrderNullified(orderEventTranslator.toInternal(dto))
    }

    companion object {
        const val ORDER_NULLIFIED_TOPIC = "order.nullified"
    }
}
