package finki.ukim.erp.orders.infrastructure.kafka

import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.events.ORDER_NULLIFIED_TOPIC
import finki.ukim.erp.orders.handlers.StockLifecycleEventHandler
import finki.ukim.erp.orders.services.OrderCommandService
import finki.ukim.erp.orders.services.OrderViewReadService
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * End-to-end against a real broker: a command here becomes a message on a topic out there, and a
 * message on a topic out there becomes a command here.
 *
 * This is the only test that needs infrastructure. Bring it up with `docker compose up -d` from the
 * repository root; without a broker on localhost:9092 the whole class skips rather than fails, so
 * `mvn test` still passes on a machine with no Docker.
 */
@SpringBootTest
@ActiveProfiles("mock-inventory")
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "orders.kafka.publishing-enabled=true",
        "orders.kafka.consuming-enabled=true",
        "spring.kafka.consumer.group-id=orders-e2e-test"
    ]
)
class KafkaEndToEndTest {

    @Autowired
    private lateinit var orderCommandService: OrderCommandService

    @Autowired
    private lateinit var orderViewReadService: OrderViewReadService

    /** Approval, the only way it happens: inventory confirming the order's goods out. */
    @Autowired
    private lateinit var stockLifecycleEventHandler: StockLifecycleEventHandler

    companion object {
        private const val BOOTSTRAP = "localhost:9092"

        @JvmStatic
        @BeforeAll
        fun requireBroker() {
            assumeTrue(brokerIsUp(), "no Kafka broker on $BOOTSTRAP - run `docker compose up -d`")
        }

        private fun brokerIsUp(): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("localhost", 9092), 1000); true }
        } catch (ex: Exception) {
            false
        }
    }

    private fun consumerOn(topic: String): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP)
            put(ConsumerConfig.GROUP_ID_CONFIG, "assertion-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        }
        return KafkaConsumer<String, String>(props).apply { subscribe(listOf(topic)) }
    }

    /** Polls until [predicate] matches a message on [topic], or gives up. */
    private fun awaitMessage(topic: String, timeout: Duration, predicate: (String) -> Boolean): String? {
        consumerOn(topic).use { consumer ->
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val match = consumer.poll(Duration.ofMillis(500))
                    .map { it.value() }
                    .firstOrNull(predicate)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    @Test
    fun `stock confirmed by inventory approves the order and publishes it on order-approved`() {
        val order = orderCommandService.createOrder(
            name = "John",
            surname = "Doe",
            customerId = "customer-e2e",
            items = listOf(OrderItemRequest(productId = "product-1", quantity = 2))
        )
        stockLifecycleEventHandler.onStockConfirmed(order.id)

        val published = awaitMessage("order.approved", Duration.ofSeconds(20)) { it.contains(order.id.value) }

        assertTrue(published != null, "nothing arrived on order.approved for ${order.id}")
        assertTrue(published!!.contains("\"productId\":\"product-1\""), published)
        assertTrue(published.contains("\"quantity\":2"), published)
        // The customer never leaves the service.
        assertTrue(!published.contains("customer-e2e"), published)
        assertTrue(!published.contains("John"), published)
    }

    /**
     * The topic a consumer holding stock for an order actually reads. Driven through a cancellation
     * because that is the ending that also publishes `order.cancelled` - so this proves the
     * nullification is a message of its own on its own topic, not the cancellation renamed.
     */
    @Test
    fun `cancelling an order publishes it on order-nullified so holds elsewhere can be released`() {
        val order = orderCommandService.createOrder(
            name = "John",
            surname = "Doe",
            customerId = "customer-e2e",
            items = listOf(OrderItemRequest(productId = "product-1", quantity = 2))
        )
        stockLifecycleEventHandler.onStockConfirmed(order.id)
        orderCommandService.cancelOrder(order.id, "customer-e2e")

        val published = awaitMessage(ORDER_NULLIFIED_TOPIC, Duration.ofSeconds(20)) { it.contains(order.id.value) }

        assertTrue(published != null, "nothing arrived on $ORDER_NULLIFIED_TOPIC for ${order.id}")
        assertTrue(published!!.contains("\"reason\":\"CANCELLED\""), published)
        // The refund amount is between us and the customer; it does not go on a public topic.
        assertTrue(!published.contains("refundedAmount"), published)
    }

    @Test
    fun `a product deactivated elsewhere rejects the pending orders waiting on it`() {
        val order = orderCommandService.createOrder(
            name = "Jane",
            surname = "Roe",
            customerId = "customer-e2e",
            items = listOf(OrderItemRequest(productId = "product-4", quantity = 1))
        )

        publish(
            KafkaEventConsumer.PRODUCT_DEACTIVATED_TOPIC,
            """{"productId":"product-4","name":"Standing desk"}"""
        )

        val deadline = System.currentTimeMillis() + 30_000
        var status = orderViewReadService.findById(order.id).status
        while (status != OrderStatus.REJECTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            status = orderViewReadService.findById(order.id).status
        }

        assertTrue(status == OrderStatus.REJECTED, "order ${order.id} was left $status")
    }

    private fun publish(topic: String, payload: String) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }
        KafkaProducer<String, String>(props).use { it.send(ProducerRecord(topic, "test-key", payload)).get() }
    }
}
