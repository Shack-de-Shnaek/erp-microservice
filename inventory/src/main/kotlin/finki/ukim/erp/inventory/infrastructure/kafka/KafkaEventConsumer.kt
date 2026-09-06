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
 * The topics are the ones orders actually publishes. It names them after the events that produce
 * them - `OrderApprovedEvent` becomes `order.approved` - and `order.nullified` is the one exception,
 * because three different endings (cancellation, rejection, invoice reversal) all announce
 * themselves there. There is no `order.placed`; this service listened for one for a while and
 * therefore reserved nothing at all.
 *
 * Only these two are read. `order.created` is published as well, but a pending order commits
 * nothing and may still be rejected, so reserving against it would hold stock for orders that never
 * happen. `invoice.generated` and `invoice.reversed` are about money; the reversal reaches us as a
 * nullification anyway.
 */
@Component
class KafkaEventConsumer(
    private val objectMapper: ObjectMapper,
    private val orderEventTranslator: OrderEventTranslator,
    private val orderLifecycleSaga: OrderLifecycleSaga,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [ORDER_APPROVED_TOPIC])
    fun onOrderApproved(message: String) {
        log.info("Received {} message: {}", ORDER_APPROVED_TOPIC, message)
        val dto = objectMapper.readValue(message, OrderApprovedEventDTO::class.java)
        orderLifecycleSaga.onOrderApproved(orderEventTranslator.toInternal(dto))
    }

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
        const val ORDER_APPROVED_TOPIC = "order.approved"
        const val ORDER_NULLIFIED_TOPIC = "order.nullified"
    }
}
