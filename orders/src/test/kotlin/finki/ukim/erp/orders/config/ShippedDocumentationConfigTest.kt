package finki.ukim.erp.orders.config

import io.github.springwolf.core.configuration.properties.SpringwolfConfigProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource

/**
 * Asserts the documentation configuration that actually *ships* - src/main/resources/application.yaml.
 *
 * The rest of the suite cannot: src/test/resources/application.yaml shadows it on the test
 * classpath, so [AsyncApiDocketConfigurationTest] proves the wiring holds but only against the
 * test docket. That is how a malformed server definition (`url` where springwolf 2.x wants `host`)
 * got as far as a running service once. This loads the shipped file directly and binds it, so the
 * same class of mistake fails here instead.
 */
class ShippedDocumentationConfigTest {

    private val shipped: PropertySource<*> =
        YamlPropertySourceLoader()
            // By path, not by classpath: src/test/resources/application.yaml is the one that
            // wins on the test classpath, and it is precisely the file this test is not about.
            .load("shipped", FileSystemResource("src/main/resources/application.yaml"))
            .single()

    private fun environment() = StandardEnvironment().apply {
        propertySources.addFirst(shipped)
    }

    @Test
    fun `the shipped springwolf docket binds`() {
        val properties = Binder.get(environment())
            .bind("springwolf", SpringwolfConfigProperties::class.java)
            .orElseThrow { AssertionError("springwolf configuration is absent from the shipped application.yaml") }

        assertTrue(properties.isEnabled, "springwolf must be on in a deployment; only the test suite turns it off")

        val docket = requireNotNull(properties.docket) { "springwolf.docket is absent" }
        assertEquals("finki.ukim.erp.orders", docket.basePackage)
        // A server declared with `url` rather than `host` fails the context at startup, and only
        // when springwolf is enabled - which everywhere except a deployment, it is not.
        // springwolf 2.7 relaxed these getters to nullable, so absence and blankness are now two
        // different failures; both are still failures here.
        val servers = requireNotNull(docket.servers) { "springwolf.docket.servers is absent" }
        val kafka = requireNotNull(servers["kafka"]) { "no kafka server is declared" }
        assertEquals("kafka", kafka.protocol)
        assertTrue(!kafka.host.isNullOrBlank(), "the kafka server must name a host")

        val info = requireNotNull(docket.info) { "springwolf.docket.info is absent" }
        assertTrue(!info.title.isNullOrBlank())
        assertTrue(!info.version.isNullOrBlank())
        assertTrue(!info.description.isNullOrBlank(), "the AsyncAPI document must describe what it documents")
    }

    @Test
    fun `the shipped springdoc endpoints are on and are the ones SecurityConfig lets through`() {
        val environment = environment()

        assertEquals("true", environment.getProperty("springdoc.api-docs.enabled"))
        assertEquals("true", environment.getProperty("springdoc.swagger-ui.enabled"))
        // SecurityConfig permits exactly these two paths unauthenticated; moving one here without
        // moving it there turns the documentation into a 401.
        assertEquals("/v3/api-docs", environment.getProperty("springdoc.api-docs.path"))
        assertEquals("/swagger-ui.html", environment.getProperty("springdoc.swagger-ui.path"))
    }
}
