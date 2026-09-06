package finki.ukim.erp.orders.clients.fallbacks

import feign.FeignException
import feign.Request
import feign.RequestTemplate
import finki.ukim.erp.orders.exceptions.InventoryUnavailableException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The fallback's only real decision: which failures mean "inventory has no such product" and
 * which mean "inventory is not answering". Everything downstream - a 404 to the customer versus a
 * 503 and a retry - hangs off getting that right.
 */
class InventoryClientFallbackTest {

    @Test
    fun `a connection failure becomes an outage`() {
        val fallback = InventoryClientFallbackFactory().create(IOException("connection refused"))

        assertThrows(InventoryUnavailableException::class.java) { fallback.getProduct("product-1") }
        assertThrows(InventoryUnavailableException::class.java) { fallback.getStock("product-1") }
    }

    @Test
    fun `a 500 becomes an outage`() {
        val fallback = InventoryClientFallbackFactory().create(feignException(500))

        assertThrows(InventoryUnavailableException::class.java) { fallback.getProduct("product-1") }
    }

    @Test
    fun `a 404 is passed through untouched, so it can still mean 'no such product'`() {
        val notFound = feignException(404)
        val fallback = InventoryClientFallbackFactory().create(notFound)

        val thrown = assertThrows(FeignException.NotFound::class.java) { fallback.getProduct("no-such-product") }
        assertSame(notFound, thrown)
    }

    private fun feignException(status: Int): FeignException = FeignException.errorStatus(
        "InventoryClient#getProduct(String)",
        feign.Response.builder()
            .status(status)
            .reason("test")
            .request(
                Request.create(
                    Request.HttpMethod.GET,
                    "http://inventory/products/1",
                    emptyMap(),
                    null,
                    StandardCharsets.UTF_8,
                    RequestTemplate()
                )
            )
            .headers(emptyMap())
            .build()
    )
}
