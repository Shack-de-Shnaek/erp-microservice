package finki.ukim.erp.orders.config

import io.github.springwolf.asyncapi.v3.model.AsyncAPI
import io.github.springwolf.asyncapi.v3.model.operation.OperationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import io.github.springwolf.core.asyncapi.AsyncApiService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * The documented contract has to match the one the code actually speaks. These assertions are the
 * link between the two: a topic that stops being published, or one that appears, shows up here.
 */
class AsyncApiDocumentationTest {

    private fun documented(): AsyncAPI = AsyncAPI.builder().build().also { AsyncApiDocumentation().customize(it) }

    @Test
    fun `every topic this service publishes to is documented`() {
        val channels = documented().channels.keys

        assertTrue(channels.containsAll(
            listOf("order.created", "order.approved", "order.cancelled", "invoice.generated", "invoice.reversed")
        ), "documented channels were $channels")
    }

    @Test
    fun `the topic this service consumes is documented as incoming`() {
        val api = documented()

        assertTrue(api.channels.containsKey("product.discontinued"))
        assertEquals(
            OperationAction.RECEIVE,
            api.operations["product.discontinued.consume"]?.action
        )
    }

    @Test
    fun `internal events are not advertised as topics`() {
        val channels = documented().channels.keys

        // Payments, rejections and invoice corrections stay inside the service.
        assertTrue(channels.none { it.startsWith("payment.") }, "documented channels were $channels")
        assertTrue("order.rejected" !in channels, "documented channels were $channels")
        assertTrue("invoice.line.items.updated" !in channels, "documented channels were $channels")
    }
}

/**
 * Boots the application with springwolf switched on - the rest of the suite runs with it off - so
 * the autoconfiguration, the docket and this customizer are at least proven to wire together.
 *
 * Its limit is worth stating: `src/test/resources/application.yaml` shadows the production one, so
 * this covers the *test* docket, not the `springwolf.docket` block that ships. A malformed server
 * definition there (`url` where springwolf 2.x wants `host`) still only shows up when the service
 * is actually run, which is how it was found.
 */
@SpringBootTest(properties = ["springwolf.enabled=true"])
class AsyncApiDocketConfigurationTest {

    @Autowired
    private lateinit var asyncApiService: AsyncApiService

    @Test
    fun `the service starts with springwolf enabled and serves a document`() {
        val api = asyncApiService.asyncAPI

        assertTrue(api.channels.containsKey("order.created"), "channels were ${api.channels.keys}")
        assertEquals(OperationAction.SEND, api.operations["order.created.publish"]?.action)
    }
}
