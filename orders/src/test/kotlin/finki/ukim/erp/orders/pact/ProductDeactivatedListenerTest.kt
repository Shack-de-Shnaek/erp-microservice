package finki.ukim.erp.orders.pact

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.handlers.ProductDeactivatedEventHandler
import finki.ukim.erp.orders.handlers.StockLifecycleEventHandler
import finki.ukim.erp.orders.infrastructure.kafka.KafkaEventConsumer
import finki.ukim.erp.orders.infrastructure.kafka.ProductDeactivatedTranslator
import finki.ukim.erp.orders.infrastructure.kafka.StockEventTranslator
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * The consumer side of the inventory -> orders message contract.
 *
 * This is the asynchronous mirror of [finki.ukim.erp.orders.clients.InventoryClientPactTest]: the
 * same two parties, the same direction of dependency, but over a topic instead of HTTP. What it
 * writes to `target/pacts/orders-inventory.json` is a statement to the inventory team about what a
 * `product.deactivated` message has to contain for this service to be able to act on it.
 *
 * The contract is exactly one field: `productId`, the only thing orders reads. Pinning anything
 * else would be this service claiming a say over parts of inventory's event it does not use, and a
 * consumer pact is a *demand* on the provider - a field named here is one inventory must publish
 * forever. `ProductDeactivatedExternalEvent` is today `{"productId": "<id>"}` and nothing more, so
 * a contract that also asked for `name` and `deactivatedAt` would fail on inventory's side the
 * first time they verified it. Orders' DTO still accepts both, nullable, if they ever appear.
 *
 * No Spring context. The listener is constructed directly with the real translator and a mocked
 * reaction, which is the whole of what this test is about - that the bytes on the topic survive
 * deserialization and arrive in the domain as a [ProductId]. The exercise's outline mocks the
 * translator too; that would leave nothing between the JSON and the assertion but a stub, and the
 * test would pass against a message shape this service cannot actually read.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "inventory",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V4
)
class ProductDeactivatedListenerTest {

    private val reaction: ProductDeactivatedEventHandler = mock(ProductDeactivatedEventHandler::class.java)
    private val consumer = KafkaEventConsumer(
        ProductDeactivatedTranslator(),
        reaction,
        StockEventTranslator(),
        mock(StockLifecycleEventHandler::class.java)
    )

    /**
     * V4, matching the HTTP consumer test against the same provider. Both write to
     * `orders-inventory.json`, and pact-jvm merges a new interaction into an existing file only
     * when the specification versions agree - a V3 message pact here would collide with the V4 HTTP
     * pact rather than joining it.
     */
    @Pact(consumer = "orders")
    fun productDeactivated(builder: MessagePactBuilder): V4Pact {
        val body = PactDslJsonBody()
            // The one field orders actually reads. A flat string: inventory's
            // ProductDeactivatedExternalEvent is `{"productId": "<id>"}`, and a product id is an
            // opaque token this service carries rather than interprets.
            .stringType("productId", "11111111-1111-1111-1111-111111111111")

        return builder
            .expectsToReceive("Product Deactivated")
            .withContent(body)
            .toPact(V4Pact::class.java)
    }

    @Test
    @PactTestFor(pactMethod = "productDeactivated", providerType = ProviderType.ASYNCH)
    fun `a deactivated product from inventory reaches the domain as a product id`(
        messages: List<V4Interaction.AsynchronousMessage>
    ) {
        assertFalse(messages.isEmpty(), "Pact produced no messages to feed to the listener")

        messages.forEach { message ->
            consumer.onProductDeactivated(
                ConsumerRecord(
                    KafkaEventConsumer.PRODUCT_DEACTIVATED_TOPIC,
                    0,
                    0L,
                    "11111111-1111-1111-1111-111111111111",
                    message.contentsAsString()
                )
            )
        }

        // The listener swallows every exception so one bad message cannot stall the partition,
        // which means "it did not throw" proves nothing. What proves the message was understood is
        // that the reaction was called with the id that was in it.
        verify(reaction).handle(ProductId("11111111-1111-1111-1111-111111111111"))
    }
}
