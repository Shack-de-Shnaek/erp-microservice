package finki.ukim.erp.orders.clients

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import finki.ukim.erp.orders.infrastructure.correlation.CorrelationId
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Stands in for the inventory team's service.
 *
 * A real HTTP server rather than a mocked [InventoryClient], because everything these tests are
 * about happens on the wire: whether the correlation id is actually on the request, whether a
 * refused connection is turned into the right domain exception, whether the breaker opens. A mock
 * of the Feign interface would skip all of it.
 *
 * Speaks the contract agreed with that team - the same one `InventoryClientPactTest` publishes:
 * `GET /api/products/{id}` and `GET /api/stock/{id}`, with the ids left opaque, because that is
 * what inventory's identifiers are.
 */
class InventoryStubServer(port: Int = 0) {

    /** Correlation ids seen on inbound requests, oldest first. */
    val receivedCorrelationIds = ConcurrentLinkedQueue<String>()

    private var server: HttpServer = start(port)

    val port: Int get() = server.address.port

    private fun start(port: Int): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            // Inventory's real prefixes. What a product *is* and how much of it there is are two
            // different resources over there, and orders needs both to answer one question.
            createContext("/api/products") { exchange -> handleProduct(exchange) }
            createContext("/api/stock") { exchange -> handleStock(exchange) }
            executor = null
            start()
        }

    /** Takes the service down the way stopping a container would: the port stops answering. */
    fun stop() = server.stop(0)

    /** Brings it back on the same port, so a client pinned to a URL reaches it again. */
    fun restart() {
        server = start(port)
    }

    private fun handleProduct(exchange: HttpExchange) = handle(exchange, "/api/products") { id ->
        respond(exchange, 200, productBody(id))
    }

    private fun handleStock(exchange: HttpExchange) = handle(exchange, "/api/stock") { id ->
        respond(exchange, 200, stockBody(id))
    }

    private fun handle(exchange: HttpExchange, prefix: String, found: (String) -> Unit) {
        exchange.requestHeaders.getFirst(CorrelationId.HEADER)?.let { receivedCorrelationIds.add(it) }

        when (val id = exchange.requestURI.path.removePrefix(prefix).trim('/')) {
            UNKNOWN_PRODUCT_ID -> respond(exchange, 404, """{"error":"no such product"}""")
            BROKEN_PRODUCT_ID -> respond(exchange, 500, """{"error":"boom"}""")
            else -> found(id)
        }
    }

    private fun productBody(id: String) =
        """{"productId":"$id","sku":"SKU-$id","name":"Desk lamp","unitOfMeasure":"piece","status":"ACTIVE"}"""

    /**
     * On hand minus reserved is what orders may still promise, so the stub keeps them apart rather
     * than answering with one number - a client that read `onHand` and called it availability would
     * pass against a single-number stub and oversell against the real service.
     */
    private fun stockBody(id: String) =
        """{"stockItemId":"stock-$id","productId":"$id","onHand":${AVAILABLE_QUANTITY + RESERVED_QUANTITY},""" +
            """"reserved":$RESERVED_QUANTITY,"reorderThreshold":5}"""

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val UNKNOWN_PRODUCT_ID = "no-such-product"
        const val BROKEN_PRODUCT_ID = "product-that-breaks-inventory"

        /** What the stub leaves free: `onHand` is this plus [RESERVED_QUANTITY]. */
        const val AVAILABLE_QUANTITY = 100
        const val RESERVED_QUANTITY = 20
    }
}
