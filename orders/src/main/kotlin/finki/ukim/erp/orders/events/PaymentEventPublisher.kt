package finki.ukim.erp.orders.events

import io.github.springwolf.bindings.kafka.annotations.KafkaAsyncOperationBinding
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class PaymentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    companion object {
        const val TOPIC = "payment-events"
    }

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "A payment was made towards an order"))
    @KafkaAsyncOperationBinding
    fun publish(event: PaymentCreatedEvent) = send(event.orderId, event)

    private fun send(orderId: Long, event: DomainEvent) {
        kafkaTemplate.send(TOPIC, orderId.toString(), event)
    }
}
