package finki.ukim.erp.inventory.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finki.ukim.erp.inventory.integration.NullifiedOrder
import finki.ukim.erp.inventory.integration.OrderEventTranslator
import finki.ukim.erp.inventory.integration.OrderLifecycleSaga
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * The anti-corruption layer, from raw bytes inwards.
 *
 * Two things are asserted here, and they pull in opposite directions on purpose. The orders service
 * must be free to move - fields it adds, or fields inventory never reads, cannot stop a message
 * being understood - while a message that cannot be understood at all must not be able to stop the
 * listener, because an exception escaping here moves no offset and has the broker redeliver the
 * same unreadable message forever while nothing else on the partition gets through.
 */
class KafkaEventConsumerTest {

    private val saga: OrderLifecycleSaga = mock(OrderLifecycleSaga::class.java)
    private val consumer = KafkaEventConsumer(
        ObjectMapper().registerKotlinModule(),
        OrderEventTranslator(),
        saga,
    )

    @Test
    fun `a nullification from orders becomes a release in this domain`() {
        consumer.onOrderNullified("""{"orderId":"Order:abc","reason":"CANCELLED"}""")

        verify(saga).onOrderNullified(NullifiedOrder(orderRef = "Order:abc", reason = "CANCELLED"))
    }

    @Test
    fun `fields this service does not read are ignored rather than fatal`() {
        consumer.onOrderNullified(
            """
            {"_eventType":"OrderCancelledEvent","orderId":"Order:abc","reason":"CANCELLED",
             "nullifiedAt":"2026-09-05T10:15:30","customer":{"name":"someone"},
             "lines":[{"productId":"product-7","quantity":2}]}
            """.trimIndent()
        )

        verify(saga).onOrderNullified(NullifiedOrder(orderRef = "Order:abc", reason = "CANCELLED"))
    }

    @Test
    fun `a message missing the order reference is refused rather than half-understood`() {
        assertDoesNotThrow { consumer.onOrderNullified("""{"reason":"CANCELLED"}""") }

        verifyNoInteractions(saga)
    }

    @Test
    fun `an unreadable message is logged and does not bring the consumer down`() {
        assertDoesNotThrow { consumer.onOrderNullified("this is not json") }
        assertDoesNotThrow { consumer.onOrderNullified("") }

        verifyNoInteractions(saga)
    }
}
