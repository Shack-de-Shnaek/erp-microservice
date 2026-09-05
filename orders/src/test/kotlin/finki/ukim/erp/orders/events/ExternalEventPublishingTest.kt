package finki.ukim.erp.orders.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.handlers.EventMessagingEventHandler
import finki.ukim.erp.orders.services.EventMessagingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * What this service promises other services, and what it keeps to itself.
 *
 * These assertions are about a published contract rather than internal behaviour, so they are
 * deliberately literal: a topic name changing, or a field appearing on a topic, is something
 * another team would find out about at runtime. Here it breaks a build instead.
 */
class ExternalEventPublishingTest {

    private val orderId = OrderId("Order:order-1")
    private val lines = listOf(OrderItemEventData(ProductId(1L), Quantity(2), Money(BigDecimal("25.00"))))

    private val sent = mutableListOf<Triple<String, String, String>>()

    private val handler = EventMessagingEventHandler(
        object : EventMessagingService {
            override fun send(topic: String, key: String, payload: String) {
                sent.add(Triple(topic, key, payload))
            }
        }
    )

    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun created() = OrderCreatedEvent(
        orderId = orderId,
        customerId = "keycloak-subject-1",
        customer = CustomerName("John", "Doe"),
        items = lines,
        totalAmount = Money(BigDecimal("50.00"))
    )

    private fun invoiced() = InvoiceGeneratedEvent(
        invoiceId = InvoiceId("Invoice:invoice-1"),
        orderId = orderId,
        invoiceNumber = InvoiceNumber("INV-0001"),
        embg = Embg("1234567890123"),
        items = listOf(InvoiceLineItemEventData(ProductId(1L), Quantity(2), Money(BigDecimal("25.00")))),
        totalAmount = Money(BigDecimal("50.00"))
    )

    @Test
    fun `topics are derived from the event's own name`() {
        assertEquals("order.created", created().eventTopic())
        assertEquals("order.approved", OrderApprovedEvent(orderId, lines).eventTopic())
        assertEquals("invoice.generated", invoiced().eventTopic())
        assertEquals("invoice.reversed", InvoiceReversedEvent(InvoiceId("Invoice:i"), orderId, Money.ZERO).eventTopic())
    }

    @Test
    fun `the event type travels with the message`() {
        assertEquals("OrderCreatedEvent", created().eventType())
    }

    @Test
    fun `events with nothing public to say are not published`() {
        val internalEvents = listOf(
            OrderRejectedEvent(orderId),
            OrderItemsUpdatedEvent(orderId, lines, Money(BigDecimal("50.00"))),
            PaymentCreatedEvent(TransactionId("tx-1"), orderId, Money(BigDecimal("50.00")), PaymentType.CASH),
            PaymentReversedEvent(TransactionId("tx-2"), orderId, Money(BigDecimal("-50.00")), PaymentType.CASH),
            InvoiceLineItemsUpdatedEvent(InvoiceId("Invoice:i"), orderId, emptyList(), Money.ZERO)
        )

        internalEvents.forEach { event ->
            assertNull(event.toExternalEvent(), "${event.javaClass.simpleName} should stay internal")
            handler.on(event)
        }

        assertTrue(sent.isEmpty(), "nothing internal should have reached a topic")
    }

    @Test
    fun `an approved order publishes the lines a consumer needs to reserve`() {
        handler.on(OrderApprovedEvent(orderId, lines))

        val (topic, key, payload) = sent.single()
        assertEquals("order.approved", topic)
        // Keyed by the order, so everything about one order stays on one partition and in order.
        assertEquals("Order:order-1", key)
        assertTrue(payload.contains("\"productId\":1"), payload)
        assertTrue(payload.contains("\"quantity\":2"), payload)
    }

    @Test
    fun `the customer's identity never leaves the service`() {
        handler.on(created())

        val payload = sent.single().third
        assertFalse(payload.contains("keycloak-subject-1"), payload)
        assertFalse(payload.contains("John"), payload)
        assertFalse(payload.contains("Doe"), payload)
    }

    @Test
    fun `an invoice publishes its number but never the EMBG it was issued to`() {
        handler.on(invoiced())

        val (topic, _, payload) = sent.single()
        assertEquals("invoice.generated", topic)
        assertTrue(payload.contains("INV-0001"), payload)
        assertFalse(payload.contains("1234567890123"), payload)
    }

    @Test
    fun `an external event serializes to the shape consumers are promised`() {
        val json = objectMapper.readTree(objectMapper.writeValueAsString(created().toExternalEvent()))

        assertEquals("Order:order-1", json["orderId"].asText())
        assertEquals(1, json["lines"].size())
        assertEquals(1, json["lines"][0]["productId"].asInt())
        assertEquals(2, json["lines"][0]["quantity"].asInt())
        // Money is written as a JSON number, not a string, so a consumer reads it as one.
        assertEquals(0, json["totalAmount"].decimalValue().compareTo(BigDecimal("50.00")))
    }
}
