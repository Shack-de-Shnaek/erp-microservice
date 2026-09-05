package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import finki.ukim.erp.orders.infrastructure.correlation.CorrelationId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The synchronous call to inventory, over real HTTP, with the circuit breaker in the path.
 *
 * Covers what the exercise verifies by hand in Part 6 - a live target, a rejected reference, and
 * a target that is down - so a regression shows up in the build rather than in a demo.
 */
@SpringBootTest(
    properties = [
        "spring.cloud.openfeign.circuitbreaker.enabled=true",
        "spring.cloud.openfeign.circuitbreaker.group.enabled=true",
        "spring.cloud.circuitbreaker.resilience4j.disable-threadpool=true",
        "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
        "spring.cloud.openfeign.client.config.default.read-timeout=3000"
    ]
)
@TestMethodOrder(OrderAnnotation::class)
class InventoryResilienceTest {

    @Autowired
    private lateinit var inventoryCatalog: InventoryCatalog

    @AfterEach
    fun clearCorrelationId() = CorrelationId.clear()

    @Test
    @Order(1)
    fun `a product the other service knows about comes back priced and in stock`() {
        val product = inventoryCatalog.requireAvailable(ProductId(1L), Quantity(5))

        assertEquals(1L, product.id)
        assertEquals(InventoryStubServer.AVAILABLE_QUANTITY, product.availableQuantity)
    }

    @Test
    @Order(2)
    fun `a whole order is checked in one round trip`() {
        val before = stub.receivedCorrelationIds.size

        val products = inventoryCatalog.findProducts(listOf(ProductId(1L), ProductId(2L), ProductId(3L)))

        assertEquals(setOf(ProductId(1L), ProductId(2L), ProductId(3L)), products.keys)
        assertEquals(1, stub.receivedCorrelationIds.size - before, "three products, one HTTP call")
    }

    @Test
    @Order(3)
    fun `a product the other service has never heard of is not found, not an outage`() {
        assertNull(inventoryCatalog.findProduct(ProductId(InventoryStubServer.UNKNOWN_PRODUCT_ID)))
        assertThrows(ProductNotFoundException::class.java) {
            inventoryCatalog.requireProduct(ProductId(InventoryStubServer.UNKNOWN_PRODUCT_ID))
        }
    }

    /**
     * The 404 above reaches the fallback exactly like a real failure does, so this is the test
     * that the fallback tells them apart. Answering "unavailable" here would turn every typo in a
     * product id into a 503 and hide genuine outages in the noise.
     */
    @Test
    @Order(4)
    fun `an error from the other service is an outage, and says so`() {
        assertThrows(InventoryUnavailableException::class.java) {
            inventoryCatalog.findProduct(ProductId(InventoryStubServer.BROKEN_PRODUCT_ID))
        }
    }

    @Test
    @Order(5)
    fun `the caller's correlation id travels with the outgoing request`() {
        CorrelationId.set("correlation-under-test")

        inventoryCatalog.findProduct(ProductId(1L))

        assertEquals("correlation-under-test", stub.receivedCorrelationIds.last())
    }

    @Test
    @Order(6)
    fun `a call with no inbound request still carries an id, rather than none`() {
        inventoryCatalog.findProduct(ProductId(1L))

        val sent = stub.receivedCorrelationIds.last()
        assertNotNull(sent)
        assertTrue(sent.isNotBlank(), "expected a generated correlation id, got '$sent'")
    }

    /**
     * Last, because it takes the stub down. Restored in place so nothing after it is affected.
     */
    @Test
    @Order(7)
    fun `when the other service is down the call fails immediately with a 503-shaped error`() {
        stub.stop()
        try {
            val startedAt = System.nanoTime()
            assertThrows(InventoryUnavailableException::class.java) {
                inventoryCatalog.findProduct(ProductId(1L))
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            // The point of the fallback: not "eventually fails" but "fails now". Generous
            // enough not to be flaky, far below the 30s a caller would otherwise wait.
            assertTrue(elapsedMillis < 2_000, "expected a fast failure, took ${elapsedMillis}ms")
        } finally {
            stub.restart()
        }

        // Still serving as soon as the target is back - the breaker has not latched the service
        // into a broken state.
        assertEquals(1L, inventoryCatalog.requireProduct(ProductId(1L)).id)
    }

    companion object {
        private val stub = InventoryStubServer()

        /**
         * The client has no `url` - it resolves `inventory` through a registry - so the stub is
         * put in front of it the same way a real instance would be: registered under that name.
         * Consul is off in the test suite, which leaves Spring Cloud's simple discovery client as
         * the one in play, and the load balancer reads from it exactly as it would from Consul.
         */
        @JvmStatic
        @DynamicPropertySource
        fun registerStubAsInventory(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.discovery.client.simple.instances.inventory[0].uri") {
                "http://127.0.0.1:${stub.port}"
            }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() = stub.stop()
    }
}
