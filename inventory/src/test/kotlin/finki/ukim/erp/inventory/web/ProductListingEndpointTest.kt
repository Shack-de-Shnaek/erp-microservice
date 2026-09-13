package finki.ukim.erp.inventory.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.inventory.config.TestSecurityConfig
import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.product.DeactivateProductCommand
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.util.UUID

/**
 * `GET /api/products` and its two query parameters.
 *
 * The filtered path is the one worth pinning. It used to read the whole of `findByStatus` and wrap
 * the result in a `PageImpl`, so `page` and `size` were accepted, echoed back, and ignored: every
 * matching product came back on every page. The filter is now part of the query, which is the only
 * way the two can agree.
 *
 * Nothing here asserts an exact total. The read model is shared with every other test in this
 * context, so the products these fixtures add are a lower bound on what is in it, never the whole.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig::class)
class ProductListingEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var commandGateway: CommandGateway

    @Autowired
    private lateinit var productViewRepository: ProductViewRepository

    private val objectMapper = ObjectMapper()

    private fun product(deactivated: Boolean = false): ProductId {
        val productId = ProductId.generate()
        val unique = UUID.randomUUID().toString().take(8)
        commandGateway.sendAndWait<Any>(
            CreateProductCommand(
                productId = productId,
                sku = Sku("LIST-$unique"),
                name = ProductName("Listing fixture $unique"),
                unitOfMeasure = UnitOfMeasure("piece"),
            ),
        )
        if (deactivated) {
            commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
        }
        awaitStatus(productId, if (deactivated) ProductStatus.INACTIVE else ProductStatus.ACTIVE)
        return productId
    }

    /** The projection lags the command that writes it; see the note on ReserveInactiveProductIntegrationTest. */
    private fun awaitStatus(productId: ProductId, expected: ProductStatus) {
        assertNotNull(
            fetchWithRetry {
                productViewRepository.findById(productId.value).orElse(null)?.takeIf { it.status == expected }
            },
            "product_view never came to read $expected for $productId",
        )
    }

    private fun listing(query: String): JsonNode =
        objectMapper.readTree(mockMvc.perform(get("/api/products$query")).andReturn().response.contentAsString)

    private fun ids(page: JsonNode): List<String> = page["content"].map { it["productId"].asText() }

    @Test
    fun `a status filter is paged rather than returned whole`() {
        repeat(3) { product(deactivated = true) }

        val page = listing("?status=INACTIVE&page=0&size=1")

        assertEquals(1, ids(page).size, "size=1 should return one product, not every INACTIVE one")
        assertTrue(
            page["totalElements"].asLong() >= 3,
            "the total should count every INACTIVE product, not just the page",
        )
    }

    @Test
    fun `consecutive pages of a filtered listing do not repeat products`() {
        repeat(3) { product(deactivated = true) }

        val first = ids(listing("?status=INACTIVE&page=0&size=2"))
        val second = ids(listing("?status=INACTIVE&page=1&size=2"))

        assertEquals(2, first.size)
        assertTrue(second.isNotEmpty(), "there should be a second page of INACTIVE products")
        assertTrue(
            first.intersect(second.toSet()).isEmpty(),
            "page 1 repeated page 0: $first vs $second",
        )
    }

    @Test
    fun `a filtered listing contains only that status`() {
        product(deactivated = true)
        product()

        val statuses = listing("?status=INACTIVE&page=0&size=50")["content"].map { it["status"].asText() }

        assertTrue(statuses.isNotEmpty(), "the fixtures above should be listed")
        assertTrue(statuses.all { it == "INACTIVE" }, "an ACTIVE product leaked into the filter: $statuses")
    }

    @Test
    fun `the unfiltered listing pages too`() {
        repeat(3) { product() }

        val page = listing("?page=0&size=2")

        assertEquals(2, ids(page).size)
        assertTrue(page["totalElements"].asLong() >= 3)
    }
}
