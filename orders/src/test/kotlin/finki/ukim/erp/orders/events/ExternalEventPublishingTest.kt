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
import finki.ukim.erp.orders.events.ORDER_NULLIFIED_TOPIC
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
    private val lines = listOf(OrderItemEventData(ProductId("product-1"), Quantity(2), Money(BigDecimal("25.00"))))

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
        items = listOf(InvoiceLineItemEventData(ProductId("product-1"), Quantity(2), Money(BigDecimal("25.00")))),
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
            OrderItemsUpdatedEvent(orderId, lines, Money(BigDecimal("50.00"))),
            PaymentCreatedEvent(TransactionId("tx-1"), orderId, Money(BigDecimal("50.00")), PaymentType.CASH),
            PaymentReversedEvent(TransactionId("tx-2"), orderId, Money(BigDecimal("-50.00")), PaymentType.CASH),
            InvoiceLineItemsUpdatedEvent(InvoiceId("Invoice:i"), orderId, emptyList(), Money.ZERO)
        )

        internalEvents.forEach { event ->
            assertNull(event.toExternalEvent(), "${event.javaClass.simpleName} should stay internal")
            assertTrue(event.toExternalEvents().isEmpty(), "${event.javaClass.simpleName} should stay internal")
            handler.on(event)
        }

        assertTrue(sent.isEmpty(), "nothing internal should have reached a topic")
    }

    /**
     * The point of the nullification topic: a consumer holding stock, a delivery slot or a credit
     * line for an order has one topic to read and one rule to follow, however the order died.
     * Asserted for all three endings together, because the value is in there being no fourth way an
     * order can end quietly.
     */
    @Test
    fun `every way an order can end announces itself as a nullification`() {
        handler.on(OrderCancelledEvent(orderId, Money(BigDecimal("50.00")), lines))
        handler.on(OrderRejectedEvent(orderId))
        handler.on(InvoiceReversedEvent(InvoiceId("Invoice:invoice-1"), orderId, Money(BigDecimal("50.00"))))

        val nullifications = sent.filter { it.first == ORDER_NULLIFIED_TOPIC }
        assertEquals(3, nullifications.size, sent.joinToString("\n") { it.first })
        assertEquals(listOf("Order:order-1"), nullifications.map { it.second }.distinct())

        val reasons = nullifications.map { objectMapper.readTree(it.third)["reason"].asText() }
        assertEquals(listOf("CANCELLED", "REJECTED", "REFUNDED"), reasons)
    }

    /**
     * A cancellation is two announcements, not one renamed. Anyone already following
     * `order.cancelled` keeps working; the nullification is added alongside it.
     */
    @Test
    fun `a cancellation is announced on its own topic as well as the nullification topic`() {
        handler.on(OrderCancelledEvent(orderId, Money(BigDecimal("50.00")), lines))

        assertEquals(setOf("order.cancelled", ORDER_NULLIFIED_TOPIC), sent.map { it.first }.toSet())
    }

    /**
     * A rejected order never reserved anything, so there is nothing for a consumer to give back and
     * no lines to describe it with - and rejection gets no topic of its own, because *why* an order
     * was refused is this service's business.
     */
    @Test
    fun `a rejection is a nullification and nothing else`() {
        handler.on(OrderRejectedEvent(orderId))

        val (topic, _, payload) = sent.single()
        assertEquals(ORDER_NULLIFIED_TOPIC, topic)
        assertEquals(0, objectMapper.readTree(payload)["lines"].size())
    }

    @Test
    fun `an approved order publishes the lines a consumer needs to reserve`() {
        handler.on(OrderApprovedEvent(orderId, lines))

        val (topic, key, payload) = sent.single()
        assertEquals("order.approved", topic)
        // Keyed by the order, so everything about one order stays on one partition and in order.
        assertEquals("Order:order-1", key)
        assertTrue(payload.contains("\"productId\":\"product-1\""), payload)
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
        // A string, because a product id belongs to inventory and is opaque here.
        assertEquals("product-1", json["lines"][0]["productId"].asText())
        assertEquals(2, json["lines"][0]["quantity"].asInt())
        // Money is written as a JSON number, not a string, so a consumer reads it as one.
        assertEquals(0, json["totalAmount"].decimalValue().compareTo(BigDecimal("50.00")))
    }
}
