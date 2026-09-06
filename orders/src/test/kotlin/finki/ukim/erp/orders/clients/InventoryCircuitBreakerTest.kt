package finki.ukim.erp.orders.clients

import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * The breaker itself, against an address nothing is listening on - the state a stopped service
 * leaves behind.
 *
 * Its own context, with the thresholds turned right down, because a breaker tuned to trip after
 * two calls would make every other test in the suite depend on how many calls ran before it.
 *
 * What is being asserted is the difference between a retry loop and a circuit breaker: after
 * enough failures the breaker stops calling at all, and the calls that follow fail without
 * touching the network. That is what keeps a dead dependency from consuming this service's
 * threads.
 */
@SpringBootTest(
    properties = [
        "spring.cloud.openfeign.circuitbreaker.enabled=true",
        "spring.cloud.openfeign.circuitbreaker.group.enabled=true",
        "spring.cloud.circuitbreaker.resilience4j.disable-threadpool=true",
        // `inventory` registered at 127.0.0.1 on a port nothing binds: connections are refused
        // at once, as they are when a registered instance's container is stopped but Consul has
        // not yet noticed. Registered rather than pinned as a `url`, because resolution through a
        // registry is the path the client actually takes.
        "spring.cloud.discovery.client.simple.instances.inventory[0].uri=http://127.0.0.1:1",
        "resilience4j.circuitbreaker.configs.default.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.configs.default.sliding-window-size=4",
        "resilience4j.circuitbreaker.configs.default.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.configs.default.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state=30s"
    ]
)
class InventoryCircuitBreakerTest {

    @Autowired
    private lateinit var inventoryCatalog: InventoryCatalog

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Test
    fun `repeated failures open the breaker, and the calls after it never reach the network`() {
        repeat(4) {
            assertThrows(InventoryUnavailableException::class.java) {
                inventoryCatalog.findProduct(ProductId("product-1"))
            }
        }

        val open = circuitBreakerRegistry.allCircuitBreakers
            .filter { it.state == CircuitBreaker.State.OPEN }
        assertTrue(open.isNotEmpty(), "expected an open breaker, states were " +
            circuitBreakerRegistry.allCircuitBreakers.associate { it.name to it.state })

        val callsBefore = open.sumOf { it.metrics.numberOfFailedCalls }
        assertThrows(InventoryUnavailableException::class.java) {
            inventoryCatalog.findProduct(ProductId("product-1"))
        }
        assertEquals(
            callsBefore,
            open.sumOf { it.metrics.numberOfFailedCalls },
            "an open breaker should reject without attempting the call"
        )
    }
}
