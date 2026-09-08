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
import finki.ukim.erp.orders.exceptions.StockReservationRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    private val reservableProductId = ProductId("55555555-5555-5555-5555-555555555555")
    private val orderRef = "Order:66666666-6666-6666-6666-666666666666"

    /**
     * The same reference as it appears in a URL. Feign percent-encodes the colon, and the contract
     * records what is actually sent rather than what it was written as - inventory decodes it back
     * before Spring ever sees it, so the two are the same reference either way.
     */
    private val encodedOrderRef = orderRef.replace(":", "%3A")

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

    /**
     * The call that makes an order real. It is a POST rather than a message because orders cannot
     * accept an order without the answer - see `OrderCommandService.createOrder`.
     */
    @Pact(consumer = "orders")
    fun reserveStock(builder: PactDslWithProvider): V4Pact = builder
        .given("a product with reservable stock exists")
        .uponReceiving("reserve stock for an order")
        .path("/api/reservations")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { request ->
                request.stringValue("orderRef", orderRef)
                request.minArrayLike("lines", 1) { line ->
                    line.stringValue("productId", reservableProductId.value)
                    line.numberValue("quantity", 2)
                }
            }.build()
        )
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { reservation ->
                reservation.stringValue("orderRef", orderRef)
                reservation.stringType("status", "ACTIVE")
                reservation.minArrayLike("lines", 1) { line ->
                    // Both ids: orders reads its own lines by product, and could not match a
                    // reservation to an order from inventory's stock item ids alone.
                    line.stringType("stockItemId", "77777777-7777-7777-7777-777777777777")
                    line.stringValue("productId", reservableProductId.value)
                    line.numberValue("quantity", 2)
                }
            }.build()
        )
        .toPact(V4Pact::class.java)

    /**
     * A refusal, with the reason in the body. The status has to be 400 and not 404: the request was
     * understood and declined, and orders passes inventory's message straight through to whoever
     * placed the order, so the `message` field is as much part of the contract as the status.
     */
    @Pact(consumer = "orders")
    fun reservationRefused(builder: PactDslWithProvider): V4Pact = builder
        .given("no product with that id exists")
        .uponReceiving("reserve stock for a product that does not exist")
        .path("/api/reservations")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { request ->
                request.stringValue("orderRef", orderRef)
                request.minArrayLike("lines", 1) { line ->
                    line.stringValue("productId", unknownProductId.value)
                    line.numberValue("quantity", 1)
                }
            }.build()
        )
        .willRespondWith()
        .status(400)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { error ->
                error.numberValue("status", 400)
                error.stringType("message", "No product with id ... exists")
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun reservationExists(builder: PactDslWithProvider): V4Pact = builder
        .given("an order is holding stock")
        .uponReceiving("get an order's reservation")
        .path("/api/reservations/$encodedOrderRef")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { reservation ->
                reservation.stringValue("orderRef", orderRef)
                reservation.stringType("status", "ACTIVE")
                reservation.minArrayLike("lines", 1) { line ->
                    line.stringType("stockItemId", "77777777-7777-7777-7777-777777777777")
                    line.stringValue("productId", reservableProductId.value)
                    line.numberValue("quantity", 2)
                }
            }.build()
        )
        .toPact(V4Pact::class.java)

    @Pact(consumer = "orders")
    fun releaseReservation(builder: PactDslWithProvider): V4Pact = builder
        .given("an order is holding stock")
        .uponReceiving("release an order's reservation")
        .path("/api/reservations/$encodedOrderRef")
        .method("DELETE")
        .willRespondWith()
        .status(204)
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

    @Test
    @PactTestFor(pactMethod = "reserveStock")
    fun `an order's stock is put aside in one call`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        catalog.reserve(orderRef, mapOf(reservableProductId to Quantity(2)))
    }

    @Test
    @PactTestFor(pactMethod = "reservationRefused")
    fun `a refused reservation carries inventory's reason back to the caller`(mockServer: MockServer) {
        val catalog = catalogAgainst(mockServer)

        val failure = assertThrows(StockReservationRejectedException::class.java) {
            catalog.reserve(orderRef, mapOf(unknownProductId to Quantity(1)))
        }

        assertTrue(
            failure.message!!.contains("No product with id"),
            "inventory's own message should survive the trip: ${failure.message}"
        )
    }

    @Test
    @PactTestFor(pactMethod = "reservationExists")
    fun `a reservation is read back in the product ids an order is written in`(mockServer: MockServer) {
        val reservation = catalogAgainst(mockServer).findReservation(orderRef)

        assertEquals(2, reservation?.heldFor(reservableProductId))
    }

    @Test
    @PactTestFor(pactMethod = "releaseReservation")
    fun `a reservation can be given back`(mockServer: MockServer) {
        catalogAgainst(mockServer).release(orderRef)
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
