package finki.ukim.erp.orders.clients

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory

/**
 * Addressing another team's service by name instead of by URL is the whole point of running a
 * registry, and it is a one-word regression: adding `url = ...` back to a client silently pins it
 * to a single address again, and nothing else in the suite would notice - the calls keep working
 * right up until the moment an instance moves.
 *
 * So this asserts the arrangement rather than the behaviour: no client names an address, and the
 * shipped configuration still registers this service under a name others can resolve.
 */
class ServiceDiscoveryTest {

    @Test
    fun `no Feign client pins itself to a URL`() {
        val pinned = feignClients().filter { (_, annotation) ->
            annotation.url.isNotBlank()
        }

        assertTrue(pinned.isEmpty(), "these clients bypass discovery: " +
            pinned.map { (name, annotation) -> "$name -> ${annotation.url}" })
    }

    @Test
    fun `the inventory client addresses the service by its registered name`() {
        val (_, annotation) = feignClients().single { (name, _) -> name.endsWith("InventoryClient") }

        // Must match the inventory service's own spring.application.name: that is what it
        // registers under, and what the load balancer looks up.
        assertEquals("inventory", annotation.name.ifBlank { annotation.value })
    }

    @Test
    fun `the shipped configuration registers this service with a health check Consul can reach`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                YamlPropertySourceLoader()
                    .load("shipped", FileSystemResource("src/main/resources/application.yaml"))
                    .single()
            )
        }

        assertEquals("true", environment.getProperty("spring.cloud.consul.discovery.register"))
        assertEquals("true", environment.getProperty("spring.cloud.consul.discovery.register-health-check"))
        // Without this the instance registers under its container hostname, which Consul cannot
        // resolve from inside its own container - the check then fails forever and the instance
        // is never advertised.
        assertEquals("true", environment.getProperty("spring.cloud.consul.discovery.prefer-ip-address"))
        assertEquals("/actuator/health", environment.getProperty("spring.cloud.consul.discovery.health-check-path"))
        // The name other teams put in their @FeignClient.
        assertEquals("orders", environment.getProperty("spring.cloud.consul.discovery.service-name"))
        assertEquals("orders", environment.getProperty("spring.application.name"))
        // Consul polls the health endpoint unauthenticated, so it has to be exposed.
        assertTrue(
            environment.getProperty("management.endpoints.web.exposure.include").orEmpty().contains("health"),
            "the actuator health endpoint must be exposed for Consul to poll"
        )
    }

    /** Every `@FeignClient` interface under this service's client package, by class name. */
    private fun feignClients(): List<Pair<String, FeignClient>> {
        val resolver = PathMatchingResourcePatternResolver()
        val readers = CachingMetadataReaderFactory(resolver)

        return resolver
            .getResources("classpath*:finki/ukim/erp/orders/clients/**/*.class")
            .map { readers.getMetadataReader(it).classMetadata.className }
            .mapNotNull { className ->
                val type = runCatching { Class.forName(className) }.getOrNull() ?: return@mapNotNull null
                type.getAnnotation(FeignClient::class.java)?.let { className to it }
            }
            .also { check(it.isNotEmpty()) { "found no @FeignClient at all - the scan is broken, not the code" } }
    }
}
