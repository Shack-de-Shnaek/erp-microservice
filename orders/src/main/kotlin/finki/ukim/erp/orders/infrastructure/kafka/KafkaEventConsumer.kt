package finki.ukim.erp.orders.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import finki.ukim.erp.orders.handlers.ProductDiscontinuedEventHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

/**
 * The way into this service from other services' topics.
 *
 * Its job is narrow on purpose: read the bytes, hand them to the translator, and let the reaction
 * decide what to do. It knows about JSON and about Kafka, and nothing at all about orders.
 *
 * Every exception is caught. A message that cannot be read is one bad message - very often one
 * somebody typed by hand into a console producer - and letting it escape would fail the batch, move
 * no offset, and have the broker redeliver the same unreadable message forever while nothing else
 * on the partition gets through. Logging it and moving on keeps one bad message from becoming an
 * outage. The trade is that a genuinely broken message is dropped rather than retried, which is why
 * the log line carries the payload that failed.
 */
@Service
@ConditionalOnProperty(
    prefix = "orders.kafka",
    name = ["consuming-enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class KafkaEventConsumer(
    private val translator: ProductDiscontinuedTranslator,
    private val productDiscontinuedEventHandler: ProductDiscontinuedEventHandler
) {

    private val logger = LoggerFactory.getLogger(KafkaEventConsumer::class.java)

    private val objectMapper = ObjectMapper()
        .registerModules(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @KafkaListener(topics = [PRODUCT_DISCONTINUED_TOPIC])
    fun onProductDiscontinued(record: ConsumerRecord<String, String>) {
        try {
            val dto: ProductDiscontinuedExternalEventDTO = objectMapper.readValue(record.value())
            val productId = translator.toDiscontinuedProduct(dto)
            productDiscontinuedEventHandler.handle(productId)
            logger.info("Processed external event: product {} was discontinued", productId)
        } catch (ex: Exception) {
            logger.error("Failed to process event from topic {}: {}", record.topic(), record.value(), ex)
        }
    }

    companion object {
        /**
         * Named the way this service names its own topics - `ProductDiscontinuedEvent` ->
         * `product.discontinued` - so both ends of the system read the same way.
         */
        const val PRODUCT_DISCONTINUED_TOPIC = "product.discontinued"
    }
}
