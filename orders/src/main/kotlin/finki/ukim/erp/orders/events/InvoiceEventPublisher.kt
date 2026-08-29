package finki.ukim.erp.orders.events

import io.github.springwolf.bindings.kafka.annotations.KafkaAsyncOperationBinding
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class InvoiceEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    companion object {
        const val TOPIC = "invoice-events"
    }

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An invoice was generated"))
    @KafkaAsyncOperationBinding
    fun publish(event: InvoiceGeneratedEvent) = send(event.invoiceId, event)

    @AsyncPublisher(
        operation = AsyncOperation(channelName = TOPIC, description = "An invoice's line items were updated")
    )
    @KafkaAsyncOperationBinding
    fun publish(event: InvoiceLineItemsUpdatedEvent) = send(event.invoiceId, event)

    @AsyncPublisher(operation = AsyncOperation(channelName = TOPIC, description = "An invoice was reversed"))
    @KafkaAsyncOperationBinding
    fun publish(event: InvoiceReversedEvent) = send(event.invoiceId, event)

    private fun send(invoiceId: Long, event: DomainEvent) {
        kafkaTemplate.send(TOPIC, invoiceId.toString(), event)
    }
}
