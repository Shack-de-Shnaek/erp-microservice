package finki.ukim.erp.orders.clients

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
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
import java.math.BigDecimal

/**
 * Consumer-driven contract for the orders -> inventory call.
 *
 * These tests exercise the *real* [InventoryClient] and [FeignInventoryCatalog] against Pact's
 * mock provider, so what gets written to `target/pacts/orders-inventory.json` is exactly what this
 * service will send and expects back. The inventory service then verifies itself against that
 * file; if it ever changes the shape of `/products/{id}` or `/products?ids=`, its build breaks
 * rather than ours at runtime.
 *
 * The `given(...)` clauses are provider states - inventory has to set that data up before
 * replaying the interaction.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "inventory")
class InventoryClientPactTest {

    @Pact(consumer = "orders")
    fun productInStock(builder: PactDslWithProvider): V4Pact = builder
        .given("product 1 exists and has 100 in stock")
        .uponReceiving("a request for product 1")
        .path("/products/1")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { product ->
                product.numberValue("id", 1)
                product.stringType("name", "Desk lamp")
                product.decimalType("price", 19.99)
                product.numberType("availableQuantity", 100)
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun productOutOfStock(builder: PactDslWithProvider): V4Pact = builder
        .given("product 3 exists and is out of stock")
        .uponReceiving("a request for product 3")
        .path("/products/3")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { product ->
                product.numberValue("id", 3)
                product.stringType("name", "Notebook")
                product.decimalType("price", 5.00)
                product.numberValue("availableQuantity", 0)
            }.build()
        )
        .toPact(V4Pact::class.java)

    /**
     * The second endpoint in the contract: one request covering every line of an order. Inventory
     * has to answer with an array, and has to answer for the ids it knows rather than failing the
     * whole request because one of them is unknown.
     */
    @Pact(consumer = "orders")
    fun severalProducts(builder: PactDslWithProvider): V4Pact = builder
        .given("products 1 and 2 exist and are in stock")
        .uponReceiving("a request for products 1 and 2 at once")
        .path("/products")
        .method("GET")
        .matchQuery("ids", "\\d+", listOf("1", "2"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonArray { array ->
                array.`object` { product ->
                    product.numberValue("id", 1)
                    product.stringType("name", "Desk lamp")
                    product.decimalType("price", 19.99)
                    product.numberType("availableQuantity", 100)
                }
                array.`object` { product ->
                    product.numberValue("id", 2)
                    product.stringType("name", "Office chair")
                    product.decimalType("price", 49.50)
                    product.numberType("availableQuantity", 25)
                }
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun unknownProduct(builder: PactDslWithProvider): V4Pact = builder
        .given("product 999 does not exist")
        .uponReceiving("a request for a product that does not exist")
        .path("/products/999")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "productInStock")
    fun `a product in stock can be priced and ordered`(mockServer: MockServer) {
        val product = catalogAgainst(mockServer).requireAvailable(productId = ProductId(1L), quantity = Quantity(5))

        assertEquals(1L, product.id)
        assertEquals(BigDecimal("19.99"), product.price)
        assertEquals(100, product.availableQuantity)
    }

    @Test
    @PactTestFor(pactMethod = "productOutOfStock")
    fun `a product with no stock is rejected`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        assertThrows(InsufficientStockException::class.java) { catalog.requireAvailable(productId = ProductId(3L), quantity = Quantity(1)) }
    }

    @Test
    @PactTestFor(pactMethod = "severalProducts")
    fun `a whole order's products are checked in one request`(mockServer: MockServer) {
        val products = catalogAgainst(mockServer).findProducts(listOf(ProductId(1L), ProductId(2L)))

        assertEquals(setOf(ProductId(1L), ProductId(2L)), products.keys)
        // compareTo, not equals: what matters is the amount, not whether the provider wrote 49.50 or 49.5.
        assertEquals(0, BigDecimal("49.50").compareTo(products[ProductId(2L)]?.price))
    }

    @Test
    @PactTestFor(pactMethod = "unknownProduct")
    fun `a 404 means the product does not exist, not that inventory is broken`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        assertNull(catalog.findProduct(ProductId(999L)))
        assertThrows(ProductNotFoundException::class.java) { catalog.requireProduct(ProductId(999L)) }
    }

    /**
     * The production client gets its base path from `@FeignClient(path = "/products")`, which
     * Spring Cloud applies when it builds the target - not something [SpringMvcContract] reads.
     * Building the target by hand here means repeating it.
     */
    private fun catalogAgainst(mockServer: MockServer): InventoryCatalog {
        val objectMapper = ObjectMapper().registerKotlinModule()
        val client = Feign.builder()
            .contract(SpringMvcContract())
            .encoder(JacksonEncoder(objectMapper))
            .decoder(JacksonDecoder(objectMapper))
            .target(InventoryClient::class.java, "${mockServer.getUrl()}/products")
        return FeignInventoryCatalog(client)
    }
}
