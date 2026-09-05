package finki.ukim.erp.orders.infrastructure.kafka

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.handlers.ProductDiscontinuedEventHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * The anti-corruption layer, from raw bytes inwards.
 *
 * The point of these is that the producer is free to move: extra fields it adds, or fields orders
 * does not read, must not stop a message being understood, while a message missing what orders
 * actually needs must fail rather than be turned into a half-formed command.
 */
class KafkaEventConsumerTest {

    private val reaction: ProductDiscontinuedEventHandler = mock(ProductDiscontinuedEventHandler::class.java)
    private val consumer = KafkaEventConsumer(ProductDiscontinuedTranslator(), reaction)

    private fun record(json: String) =
        ConsumerRecord(KafkaEventConsumer.PRODUCT_DISCONTINUED_TOPIC, 0, 0L, "key", json)

    @Test
    fun `a message from inventory becomes a product id in this domain`() {
        consumer.onProductDiscontinued(
            record("""{"_eventType":"ProductDiscontinuedEvent","productId":{"value":7}}""")
        )

        verify(reaction).handle(ProductId(7L))
    }

    @Test
    fun `fields this service does not read are ignored rather than fatal`() {
        consumer.onProductDiscontinued(
            record(
                """
                {"_eventType":"ProductDiscontinuedEvent","productId":{"value":7},
                 "name":"Desk lamp","discontinuedAt":"2026-09-05T10:15:30",
                 "warehouse":{"code":"MK-01"},"reason":"supplier withdrew"}
                """.trimIndent()
            )
        )

        verify(reaction).handle(ProductId(7L))
    }

    @Test
    fun `a message missing the product id is refused rather than half-understood`() {
        consumer.onProductDiscontinued(record("""{"_eventType":"ProductDiscontinuedEvent","name":"Desk lamp"}"""))

        verify(reaction, never()).handle(ProductId(7L))
    }

    @Test
    fun `an unreadable message is logged and does not bring the consumer down`() {
        assertDoesNotThrow { consumer.onProductDiscontinued(record("this is not json")) }
        assertDoesNotThrow { consumer.onProductDiscontinued(record("")) }
    }

    @Test
    fun `the translator speaks only in domain types`() {
        val translated = ProductDiscontinuedTranslator().toDiscontinuedProduct(
            ProductDiscontinuedExternalEventDTO(productId = ExternalProductIdDTO(42L), name = "Notebook")
        )

        assertEquals(ProductId(42L), translated)
    }
}
