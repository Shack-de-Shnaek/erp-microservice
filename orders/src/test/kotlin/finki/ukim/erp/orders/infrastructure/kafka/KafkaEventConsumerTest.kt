package finki.ukim.erp.orders.infrastructure.kafka

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.handlers.ProductDeactivatedEventHandler
import finki.ukim.erp.orders.handlers.StockLifecycleEventHandler
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

    private val reaction: ProductDeactivatedEventHandler = mock(ProductDeactivatedEventHandler::class.java)
    private val consumer = KafkaEventConsumer(
        ProductDeactivatedTranslator(),
        reaction,
        StockEventTranslator(),
        mock(StockLifecycleEventHandler::class.java)
    )

    private fun record(json: String) =
        ConsumerRecord(KafkaEventConsumer.PRODUCT_DEACTIVATED_TOPIC, 0, 0L, "key", json)

    @Test
    fun `a message from inventory becomes a product id in this domain`() {
        consumer.onProductDeactivated(
            record("""{"productId":"product-7"}""")
        )

        verify(reaction).handle(ProductId("product-7"))
    }

    @Test
    fun `fields this service does not read are ignored rather than fatal`() {
        consumer.onProductDeactivated(
            record(
                """
                {"productId":"product-7",
                 "name":"Desk lamp","deactivatedAt":"2026-09-05T10:15:30",
                 "warehouse":{"code":"MK-01"},"reason":"supplier withdrew"}
                """.trimIndent()
            )
        )

        verify(reaction).handle(ProductId("product-7"))
    }

    @Test
    fun `a message missing the product id is refused rather than half-understood`() {
        consumer.onProductDeactivated(record("""{"name":"Desk lamp"}"""))

        verify(reaction, never()).handle(ProductId("product-7"))
    }

    @Test
    fun `an unreadable message is logged and does not bring the consumer down`() {
        assertDoesNotThrow { consumer.onProductDeactivated(record("this is not json")) }
        assertDoesNotThrow { consumer.onProductDeactivated(record("")) }
    }

    @Test
    fun `the translator speaks only in domain types`() {
        val translated = ProductDeactivatedTranslator().toDeactivatedProduct(
            ProductDeactivatedExternalEventDTO(productId = "product-42", name = "Notebook")
        )

        assertEquals(ProductId("product-42"), translated)
    }
}
