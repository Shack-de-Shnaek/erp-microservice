package finki.ukim.erp.orders.config

import finki.ukim.erp.orders.events.AbstractEvent
import finki.ukim.erp.orders.events.InvoiceGeneratedEvent
import finki.ukim.erp.orders.events.InvoiceGeneratedExternalEvent
import finki.ukim.erp.orders.events.InvoiceReversedEvent
import finki.ukim.erp.orders.events.InvoiceReversedExternalEvent
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderApprovedExternalEvent
import finki.ukim.erp.orders.events.OrderCancelledEvent
import finki.ukim.erp.orders.events.OrderCancelledExternalEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderCreatedExternalEvent
import finki.ukim.erp.orders.events.OrderNullifiedExternalEvent
import finki.ukim.erp.orders.events.ORDER_NULLIFIED_TOPIC
import finki.ukim.erp.orders.infrastructure.kafka.KafkaEventConsumer
import finki.ukim.erp.orders.infrastructure.kafka.ProductDeactivatedExternalEventDTO
import io.github.springwolf.asyncapi.v3.model.AsyncAPI
import io.github.springwolf.asyncapi.v3.model.channel.ChannelObject
import io.github.springwolf.asyncapi.v3.model.channel.ChannelReference
import io.github.springwolf.asyncapi.v3.model.channel.message.Message
import io.github.springwolf.asyncapi.v3.model.channel.message.MessageObject
import io.github.springwolf.asyncapi.v3.model.operation.Operation
import io.github.springwolf.asyncapi.v3.model.operation.OperationAction
import io.github.springwolf.core.asyncapi.AsyncApiCustomizer
import org.springframework.context.annotation.Configuration

/**
 * The AsyncAPI description of this service's Kafka contract, at /springwolf/docs and browsable in
 * the springwolf UI - the asynchronous counterpart to the OpenAPI description of the REST API.
 *
 * Springwolf normally finds channels by scanning for `@AsyncPublisher` on the method that sends
 * each message. There is no such method any more: publishing goes through one generic handler that
 * asks each event where it belongs, which is what makes adding an event cheap, and leaves nothing
 * per-topic to annotate. So the channels are declared here instead, from the same event classes the
 * publisher works from, with topics derived by the same [AbstractEvent.topicFor] the publisher
 * uses - an event that changes topic changes it in both places at once.
 *
 * What this documents is the contract: which topics exist, which direction they go, and which
 * message is on each. It is a list rather than a generated payload schema, and that is the honest
 * limit of it - the shapes themselves live in `externalEvents.kt` and are asserted by
 * `ExternalEventPublishingTest`.
 */
@Configuration
class AsyncApiDocumentation : AsyncApiCustomizer {

    /** Internal event -> the public message it puts on the topic named after it. */
    private val published = listOf(
        Publication(
            OrderCreatedEvent::class.java,
            OrderCreatedExternalEvent::class.java,
            "An order was placed. Carries the lines and the total; nothing is committed yet."
        ),
        Publication(
            OrderApprovedEvent::class.java,
            OrderApprovedExternalEvent::class.java,
            "An order was approved: inventory confirmed its goods out of the warehouse and the " +
                "order moved to match. The goods are already gone - nothing needs reserving here."
        ),
        Publication(
            OrderCancelledEvent::class.java,
            OrderCancelledExternalEvent::class.java,
            "An order was cancelled. Anything reserved against it can be released."
        ),
        Publication(
            InvoiceGeneratedEvent::class.java,
            InvoiceGeneratedExternalEvent::class.java,
            "An invoice was issued for an order. Carries the invoice number, never the EMBG."
        ),
        Publication(
            InvoiceReversedEvent::class.java,
            InvoiceReversedExternalEvent::class.java,
            "An invoice was reversed. The sale is undone."
        )
    )

    override fun customize(asyncAPI: AsyncAPI) {
        val channels = LinkedHashMap(asyncAPI.channels.orEmpty())
        val operations = LinkedHashMap(asyncAPI.operations.orEmpty())

        // Nullification is the one topic not named after an event class: three different events end
        // an order, and all three say so here. Declared on its own for that reason.
        val nullifiedMessage = messageFor(
            OrderNullifiedExternalEvent::class.java.simpleName,
            "An order is void - cancelled, rejected or refunded. Release anything held for it."
        )
        val nullifiedChannel = channelFor(ORDER_NULLIFIED_TOPIC, nullifiedMessage)
        channels[ORDER_NULLIFIED_TOPIC] = nullifiedChannel
        operations["$ORDER_NULLIFIED_TOPIC.publish"] = Operation.builder()
            .action(OperationAction.SEND)
            .channel(ChannelReference.fromChannel(nullifiedChannel))
            .title("$ORDER_NULLIFIED_TOPIC.publish")
            .description("Published on cancellation, rejection and invoice reversal alike.")
            .build()

        published.forEach { publication ->
            val topic = AbstractEvent.topicFor(publication.internalEvent.simpleName)
            val message = messageFor(publication.externalEvent.simpleName, publication.description)
            val channel = channelFor(topic, message)

            channels[topic] = channel
            operations["$topic.publish"] = Operation.builder()
                .action(OperationAction.SEND)
                .channel(ChannelReference.fromChannel(channel))
                .title("$topic.publish")
                .description(publication.description)
                .build()
        }

        // The one topic this service reads. Its shape is owned by the inventory service; what is
        // documented here is what orders understands of it, which is what its DTOs accept.
        val consumedTopic = KafkaEventConsumer.PRODUCT_DEACTIVATED_TOPIC
        val consumedMessage = messageFor(
            ProductDeactivatedExternalEventDTO::class.java.simpleName,
            "Published by the inventory service when a product is withdrawn. Pending orders for it are rejected."
        )
        val consumedChannel = channelFor(consumedTopic, consumedMessage)

        channels[consumedTopic] = consumedChannel
        operations["$consumedTopic.consume"] = Operation.builder()
            .action(OperationAction.RECEIVE)
            .channel(ChannelReference.fromChannel(consumedChannel))
            .title("$consumedTopic.consume")
            .description("Rejects every pending order that asks for the withdrawn product.")
            .build()

        asyncAPI.channels = channels
        asyncAPI.operations = operations
    }

    private fun messageFor(name: String, description: String): MessageObject = MessageObject.builder()
        .messageId(name)
        .name(name)
        .title(name)
        .description(description)
        .contentType("application/json")
        .build()

    private fun channelFor(topic: String, message: MessageObject): ChannelObject = ChannelObject.builder()
        .channelId(topic)
        .address(topic)
        .messages(mapOf<String, Message>(message.messageId to message))
        .build()

    private data class Publication(
        val internalEvent: Class<*>,
        val externalEvent: Class<*>,
        val description: String
    )
}
