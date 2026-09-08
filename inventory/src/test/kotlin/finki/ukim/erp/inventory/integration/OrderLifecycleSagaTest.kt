package finki.ukim.erp.inventory.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.infrastructure.kafka.KafkaEventConsumer
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import finki.ukim.erp.inventory.readmodel.ReservationLineEmbeddable
import finki.ukim.erp.inventory.readmodel.ReservationView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.responsetypes.ResponseType
import org.axonframework.queryhandling.QueryGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.concurrent.CompletableFuture

/**
 * What this service does with the messages the orders service actually publishes.
 *
 * Only one message is left to react to. Stock is taken by a synchronous `POST /api/reservations`
 * while the order is being placed, so `order.approved` no longer says anything to this service;
 * what remains here is giving the goods back when an order ends.
 *
 * The gateways are hand-written stubs rather than mocks: what these tests are about is which
 * commands come out for a given message, and a list of captured commands says that more plainly
 * than a chain of verifications does. No Spring context and no broker - the consumer is constructed
 * directly, so the JSON, the translation and the decision are all real and only the bus is not.
 */
class OrderLifecycleSagaTest {

    private val productId = "11111111-1111-1111-1111-111111111111"
    private val otherProductId = "22222222-2222-2222-2222-222222222222"
    private val orderRef = "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4"

    private val sentCommands = mutableListOf<Any>()

    /** What this service believes it is holding for the order. Set per test. */
    private var reservation: ReservationView? = null

    private val commandGateway: CommandGateway = mock(CommandGateway::class.java).also { gateway ->
        `when`(gateway.sendAndWait<Any>(any())).thenAnswer { invocation ->
            sentCommands.add(invocation.getArgument(0))
            Unit
        }
    }

    private val queryGateway: QueryGateway = mock(QueryGateway::class.java).also { gateway ->
        `when`(gateway.query(any<Any>(), any<ResponseType<Any>>())).thenAnswer { invocation ->
            val answer: Any? = when (val query = invocation.getArgument<Any>(0)) {
                is FindReservationByOrderRefQuery -> reservation?.takeIf { it.orderRef == query.orderRef }
                else -> null
            }
            CompletableFuture.completedFuture(answer)
        }
    }

    private val saga = OrderLifecycleSaga(commandGateway, queryGateway)
    private val consumer = KafkaEventConsumer(ObjectMapper().registerKotlinModule(), OrderEventTranslator(), saga)

    /** A held line, as the reservation projection records one: both ids, because both are read. */
    private fun heldLine(productId: String, quantity: Int) =
        ReservationLineEmbeddable(stockItemId = "stock-$productId", productId = productId, quantity = quantity)

    /**
     * The lines released are this service's own record of what it reserved, not the ones on the
     * message - which is the whole reason releasing works by order id. Here the event names one
     * line and the reservation holds two, and both have to come back.
     */
    @Test
    fun `a nullified order releases what this service actually reserved, not what the message lists`() {
        reservation = ReservationView(
            orderRef = orderRef,
            lines = mutableListOf(heldLine(productId, 5), heldLine(otherProductId, 2)),
        )

        consumer.onOrderNullified(
            """
            {"orderId":"$orderRef","reason":"CANCELLED",
             "lines":[{"productId":"$productId","quantity":5}],
             "nullifiedAt":"2026-09-05T10:15:30"}
            """.trimIndent()
        )

        val releases = sentCommands.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(2, releases.size)
        assertEquals(
            setOf("stock-$productId", "stock-$otherProductId"),
            releases.map { it.stockItemId.value }.toSet(),
        )
        assertEquals(listOf(orderRef, orderRef), releases.map { it.orderRef })
    }

    /**
     * The command addresses the stock item the reservation line already names. It used to be looked
     * up by product id from a field that holds a *stock item* id, so the lookup found nothing and
     * every release was skipped with a warning - the hold stayed on the shelf for good.
     */
    @Test
    fun `a release addresses the stock item holding the goods, with no lookup in between`() {
        reservation = ReservationView(
            orderRef = orderRef,
            lines = mutableListOf(heldLine(productId, 5)),
        )

        consumer.onOrderNullified(
            """{"orderId":"$orderRef","reason":"CANCELLED","lines":[],"nullifiedAt":"2026-09-05T10:15:30"}"""
        )

        val release = sentCommands.filterIsInstance<ReleaseReservationCommand>().single()
        assertEquals("stock-$productId", release.stockItemId.value)
    }

    /**
     * A rejection carries no lines, because a rejected order never reserved anything - and one
     * arriving for an order this service never saw must not be an error. Redelivery of a
     * nullification lands here too, once the first one has cleared the reservation.
     */
    @Test
    fun `a nullification for an order with no reservation does nothing`() {
        reservation = null

        consumer.onOrderNullified(
            """{"orderId":"$orderRef","reason":"REJECTED","lines":[],"nullifiedAt":"2026-09-05T10:15:30"}"""
        )

        assertTrue(sentCommands.isEmpty(), "released something for an order that was never reserved")
    }

    /** Orders may add fields to its events at any time without inventory having to be redeployed. */
    @Test
    fun `unknown fields on an incoming message are ignored`() {
        reservation = ReservationView(orderRef = orderRef, lines = mutableListOf(heldLine(productId, 1)))

        consumer.onOrderNullified(
            """
            {"orderId":"$orderRef","reason":"CANCELLED","customerTier":"GOLD",
             "lines":[{"productId":"$productId","quantity":1,"price":19.99}]}
            """.trimIndent()
        )

        assertEquals(1, sentCommands.filterIsInstance<ReleaseReservationCommand>().size)
    }
}
