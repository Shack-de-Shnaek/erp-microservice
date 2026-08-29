package finki.ukim.erp.orders.events

import io.github.springwolf.bindings.kafka.annotations.KafkaAsyncOperationBinding
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class OrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    companion object {
        const val TOPIC = "order-events"
    }

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An order was created"))
    @KafkaAsyncOperationBinding
    fun publish(event: OrderCreatedEvent) = send(event.orderId, event)

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An order's items were updated"))
    @KafkaAsyncOperationBinding
    fun publish(event: OrderItemsUpdatedEvent) = send(event.orderId, event)

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An order was approved"))
    @KafkaAsyncOperationBinding
    fun publish(event: OrderApprovedEvent) = send(event.orderId, event)

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An order was rejected"))
    @KafkaAsyncOperationBinding
    fun publish(event: OrderRejectedEvent) = send(event.orderId, event)

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An order was cancelled"))
    @KafkaAsyncOperationBinding
    fun publish(event: OrderCancelledEvent) = send(event.orderId, event)

    private fun send(orderId: Long, event: DomainEvent) {
        kafkaTemplate.send(TOPIC, orderId.toString(), event)
    }
}
