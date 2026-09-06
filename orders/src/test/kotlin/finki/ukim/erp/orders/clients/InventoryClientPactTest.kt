package finki.ukim.erp.orders.clients

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import feign.Feign
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.cloud.openfeign.support.SpringMvcContract

/**
 * Consumer-driven contract for the orders -> inventory calls.
 *
 * These tests exercise the *real* [InventoryClient] and [FeignInventoryCatalog] against Pact's
 * mock provider, so what gets written to `target/pacts/orders-inventory.json` is exactly what this
 * service will send and expects back. The inventory service then verifies itself against that
 * file; if it ever changes the shape of `/api/products/{id}` or `/api/stock/{id}`, its build breaks
 * rather than ours at runtime.
 *
 * ## Why there are two calls for one question
 *
 * Inventory does not have a resource that answers "what is this product and how much of it can I
 * have". The catalogue and the stock ledger are separate resources with separate lifecycles, which
 * is a reasonable thing for that service to do and not something orders should ask it to undo. So
 * the contract is two interactions per product, and the anti-corruption layer puts them back
 * together - which is also why the identifiers in these paths are opaque strings rather than
 * numbers. They are inventory's, and orders only carries them.
 *
 * The `given(...)` clauses are provider states, and they are worded exactly as inventory's
 * `PactHttpProviderTest` implements them - a state whose name does not match is a state the
 * provider silently never sets up.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "inventory")
class InventoryClientPactTest {

    private val productId = ProductId("11111111-1111-1111-1111-111111111111")
    private val fullyReservedProductId = ProductId("33333333-3333-3333-3333-333333333333")
    private val unknownProductId = ProductId("99999999-9999-9999-9999-999999999999")

    @Pact(consumer = "orders")
    fun productExists(builder: PactDslWithProvider): V4Pact = builder
        .given("a product with id exists")
        .uponReceiving("get a product by id")
        .path("/api/products/${productId.value}")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { product ->
                product.stringValue("productId", productId.value)
                product.stringType("sku", "SKU-001")
                product.stringType("name", "Widget")
                product.stringType("unitOfMeasure", "pcs")
                product.stringType("status", "ACTIVE")
            }.build()
        )
        .toPact(V4Pact::class.java)

    /**
     * `onHand` and `reserved` are both required, because what orders may promise a customer is the
     * difference. A contract that only pinned `onHand` would let inventory keep passing while
     * orders oversold everything already committed to somebody else.
     */
    @Pact(consumer = "orders")
    fun stockExists(builder: PactDslWithProvider): V4Pact = builder
        .given("a stock item for the product exists")
        .uponReceiving("get stock item by product id")
        .path("/api/stock/${productId.value}")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { stock ->
                stock.stringType("stockItemId", "22222222-2222-2222-2222-222222222222")
                stock.stringValue("productId", productId.value)
                stock.numberType("onHand", 100)
                stock.numberType("reserved", 0)
                stock.numberType("reorderThreshold", 10)
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun productFullyReserved(builder: PactDslWithProvider): V4Pact = builder
        .given("a product whose stock is entirely reserved exists")
        .uponReceiving("get a fully reserved product by id")
        .path("/api/products/${fullyReservedProductId.value}")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { product ->
                product.stringValue("productId", fullyReservedProductId.value)
                product.stringType("sku", "SKU-003")
                product.stringType("name", "Notebook")
                product.stringType("unitOfMeasure", "pcs")
                product.stringType("status", "ACTIVE")
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun stockFullyReserved(builder: PactDslWithProvider): V4Pact = builder
        .given("a product whose stock is entirely reserved exists")
        .uponReceiving("get stock for a fully reserved product")
        .path("/api/stock/${fullyReservedProductId.value}")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { stock ->
                stock.stringType("stockItemId", "44444444-4444-4444-4444-444444444444")
                stock.stringValue("productId", fullyReservedProductId.value)
                stock.numberValue("onHand", 8)
                stock.numberValue("reserved", 8)
                stock.numberType("reorderThreshold", 10)
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun unknownProduct(builder: PactDslWithProvider): V4Pact = builder
        .given("no product with that id exists")
        .uponReceiving("get a product that does not exist")
        .path("/api/products/${unknownProductId.value}")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "productExists")
    fun `a product's catalogue entry is read into the domain's own type`(mockServer: MockServer) {
        val product = clientAgainst(mockServer).getProduct(productId.value)

        assertEquals(productId.value, product.productId)
        assertEquals("Widget", product.name)
    }

    @Test
    @PactTestFor(pactMethod = "stockExists")
    fun `availability is what is on hand less what is already reserved`(mockServer: MockServer) {
        val stock = clientAgainst(mockServer).getStock(productId.value)

        assertEquals(100, stock.available)
    }

    @Test
    @PactTestFor(pactMethods = ["productFullyReserved", "stockFullyReserved"])
    fun `a product whose stock is all spoken for cannot be ordered`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        assertThrows(InsufficientStockException::class.java) {
            catalog.requireAvailable(fullyReservedProductId, Quantity(1))
        }
    }

    @Test
    @PactTestFor(pactMethod = "unknownProduct")
    fun `a 404 means the product does not exist, not that inventory is broken`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        assertNull(catalog.findProduct(unknownProductId))
        assertThrows(ProductNotFoundException::class.java) { catalog.requireProduct(unknownProductId) }
    }

    /**
     * The production client is built by Spring Cloud from the `@FeignClient` annotation and
     * addressed through Consul. Building it by hand here points the same interface, with the same
     * contract reader, at the Pact mock server instead - so the paths and the deserialization under
     * test are production's, and only the host is the test's.
     */
    private fun clientAgainst(mockServer: MockServer): InventoryClient {
        val objectMapper = ObjectMapper().registerKotlinModule()
        return Feign.builder()
            .contract(SpringMvcContract())
            .encoder(JacksonEncoder(objectMapper))
            .decoder(JacksonDecoder(objectMapper))
            .target(InventoryClient::class.java, mockServer.getUrl())
    }

    private fun catalogAgainst(mockServer: MockServer): InventoryCatalog =
        FeignInventoryCatalog(clientAgainst(mockServer))
}
