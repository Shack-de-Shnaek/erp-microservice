package finki.ukim.erp.orders.pact

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.events.ORDER_NULLIFIED_TOPIC
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCancelledEvent
import finki.ukim.erp.orders.events.OrderItemEventData
import finki.ukim.erp.orders.handlers.EventMessagingEventHandler
import finki.ukim.erp.orders.services.EventMessagingService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * The provider side of the orders -> inventory message contract.
 *
 * Inventory reserves goods when an order is approved, and
 * `src/test/resources/pacts/messages/inventory-orders.json` is the contract its listener published
 * for the `order.approved` topic. This test proves the bytes this service actually puts on that
 * topic still satisfy it.
 *
 * The important detail is where the bytes come from. Nothing here serializes a DTO with a mapper of
 * its own - it drives the real [EventMessagingEventHandler] with a capturing
 * [EventMessagingService], so what is compared against the contract is the output of the same
 * `toExternalEvent()` mapping and the same ObjectMapper configuration that production publishes
 * with. A test that built its own mapper would keep passing after somebody enabled
 * `WRITE_DATES_AS_TIMESTAMPS` on the real one, and inventory would be the one to find out.
 *
 * That also makes this a check on what is *not* published: the handler drops anything whose
 * `toExternalEvent()` returns null, and the external event deliberately omits the prices and the
 * customer that the internal [OrderApprovedEvent] carries. If someone published the internal event
 * instead, this test would show the extra fields.
 *
 * No `@SpringBootTest` and no broker: the contract is about the shape of a message, and Kafka's
 * involvement ends after the bytes exist.
 */
@Provider("orders")
@Consumer("inventory")
@PactFolder("pacts/messages")
class OrderApprovedProducerTest {

    @BeforeEach
    fun before(context: PactVerificationContext?) {
        // A message target, not an HTTP one: there is no port and no filter chain in this contract,
        // which is why the async side needs none of the security setup the HTTP provider test does.
        context?.target = MessageTestTarget(listOf("finki.ukim.erp.orders.pact"))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun pactVerificationTestTemplate(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * Nothing to set up - the message is produced from the event below rather than read from a
     * store - but the state has to be declared, because Pact reports a `given(...)` it cannot find
     * as a failure rather than ignoring it.
     */
    @State("an order has been approved")
    fun anOrderHasBeenApproved() = Unit

    /**
     * Must match the consumer's `expectsToReceive(...)` exactly; Pact pairs the two by this string.
     */
    @PactVerifyProvider("Order Approved")
    fun verifyOrderApproved(): MessageAndMetadata {
        val published = mutableListOf<String>()
        val handler = EventMessagingEventHandler(
            object : EventMessagingService {
                override fun send(topic: String, key: String, payload: String) {
                    published.add(payload)
                }
            }
        )

        handler.on(
            OrderApprovedEvent(
                orderId = OrderId("Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4"),
                items = listOf(
                    OrderItemEventData(ProductId("11111111-1111-1111-1111-111111111111"), Quantity(5), Money(BigDecimal("19.99"))),
                    OrderItemEventData(ProductId("22222222-2222-2222-2222-222222222222"), Quantity(2), Money(BigDecimal("49.50")))
                ),
                occurredAt = LocalDateTime.of(2026, 9, 5, 10, 15, 30)
            )
        )

        return asJson(published.single())
    }

    /**
     * Nothing to set up here either, for the same reason as above.
     */
    @State("an order has been nullified")
    fun anOrderHasBeenNullified() = Unit

    /**
     * The other half of what inventory needs: the message that tells it to let a reservation go.
     *
     * Verified from a *cancellation*, which publishes two messages - `order.cancelled` and
     * `order.nullified` - so this picks the one on the nullification topic rather than assuming
     * there is only one. That is the assertion that matters: a change that made cancellation stop
     * publishing the nullification, or publish it on a different topic, fails here instead of
     * leaving inventory holding stock for a dead order.
     */
    @PactVerifyProvider("Order Nullified")
    fun verifyOrderNullified(): MessageAndMetadata {
        val published = mutableMapOf<String, String>()
        val handler = EventMessagingEventHandler(
            object : EventMessagingService {
                override fun send(topic: String, key: String, payload: String) {
                    published[topic] = payload
                }
            }
        )

        handler.on(
            OrderCancelledEvent(
                orderId = OrderId("Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4"),
                refundedAmount = Money(BigDecimal("149.95")),
                items = listOf(
                    OrderItemEventData(
                        ProductId("11111111-1111-1111-1111-111111111111"),
                        Quantity(5),
                        Money(BigDecimal("19.99"))
                    )
                ),
                occurredAt = LocalDateTime.of(2026, 9, 5, 10, 15, 30)
            )
        )

        return asJson(
            requireNotNull(published[ORDER_NULLIFIED_TOPIC]) {
                "A cancelled order published nothing to $ORDER_NULLIFIED_TOPIC; topics seen: ${published.keys}"
            }
        )
    }

    private fun asJson(payload: String) =
        MessageAndMetadata(payload.toByteArray(), mapOf("contentType" to "application/json"))
}
