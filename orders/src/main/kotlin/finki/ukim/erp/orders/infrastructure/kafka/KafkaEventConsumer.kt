package finki.ukim.erp.orders.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import finki.ukim.erp.orders.handlers.ProductDeactivatedEventHandler
import finki.ukim.erp.orders.handlers.StockLifecycleEventHandler
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
    private val translator: ProductDeactivatedTranslator,
    private val productDeactivatedEventHandler: ProductDeactivatedEventHandler,
    private val stockEventTranslator: StockEventTranslator,
    private val stockLifecycleEventHandler: StockLifecycleEventHandler
) {

    private val logger = LoggerFactory.getLogger(KafkaEventConsumer::class.java)

    private val objectMapper = ObjectMapper()
        .registerModules(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @KafkaListener(topics = [PRODUCT_DEACTIVATED_TOPIC])
    fun onProductDeactivated(record: ConsumerRecord<String, String>) {
        try {
            val dto: ProductDeactivatedExternalEventDTO = objectMapper.readValue(record.value())
            val productId = translator.toDeactivatedProduct(dto)
            productDeactivatedEventHandler.handle(productId)
            logger.info("Processed external event: product {} was deactivated", productId)
        } catch (ex: Exception) {
            logger.error("Failed to process event from topic {}: {}", record.topic(), record.value(), ex)
        }
    }

    /** Goods for an order have left inventory's shelf, which is what commits the order. */
    @KafkaListener(topics = [STOCK_CONFIRMED_TOPIC])
    fun onStockConfirmed(record: ConsumerRecord<String, String>) {
        try {
            val dto: StockConfirmedExternalEventDTO = objectMapper.readValue(record.value())
            stockLifecycleEventHandler.onStockConfirmed(stockEventTranslator.toOrderId(dto))
        } catch (ex: Exception) {
            logger.error("Failed to process event from topic {}: {}", record.topic(), record.value(), ex)
        }
    }

    /**
     * An order no longer has stock behind it - either a hold let go, or confirmed goods put back.
     *
     * Both topics, one listener: the two are different facts to inventory and the same fact here,
     * and the handler tells apart the only distinction that matters to an order, which is whether
     * this service asked for it.
     */
    @KafkaListener(topics = [STOCK_RELEASED_TOPIC, STOCK_RETURNED_TOPIC])
    fun onStockReleased(record: ConsumerRecord<String, String>) {
        try {
            val dto: StockReleasedExternalEventDTO = objectMapper.readValue(record.value())
            stockLifecycleEventHandler.onStockWithdrawn(stockEventTranslator.toWithdrawal(dto))
        } catch (ex: Exception) {
            logger.error("Failed to process event from topic {}: {}", record.topic(), record.value(), ex)
        }
    }

    companion object {
        /**
         * Named the way this service names its own topics - `ProductDeactivatedEvent` ->
         * `product.deactivated` - so both ends of the system read the same way.
         */
        const val PRODUCT_DEACTIVATED_TOPIC = "product.deactivated"

        /** Inventory derives these the same way, from `StockConfirmedEvent` and its siblings. */
        const val STOCK_CONFIRMED_TOPIC = "stock.confirmed"
        const val STOCK_RELEASED_TOPIC = "stock.reservation.released"
        const val STOCK_RETURNED_TOPIC = "stock.returned"
    }
}
