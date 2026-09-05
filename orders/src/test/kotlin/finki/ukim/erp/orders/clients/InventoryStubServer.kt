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
 * Speaks the contract agreed with that team - the same one `InventoryClientPactTest` publishes.
 */
class InventoryStubServer(port: Int = 0) {

    /** Correlation ids seen on inbound requests, oldest first. */
    val receivedCorrelationIds = ConcurrentLinkedQueue<String>()

    private var server: HttpServer = start(port)

    val port: Int get() = server.address.port

    private fun start(port: Int): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/products") { exchange -> handle(exchange) }
            executor = null
            start()
        }

    /** Takes the service down the way stopping a container would: the port stops answering. */
    fun stop() = server.stop(0)

    /** Brings it back on the same port, so a client pinned to a URL reaches it again. */
    fun restart() {
        server = start(port)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestHeaders.getFirst(CorrelationId.HEADER)?.let { receivedCorrelationIds.add(it) }

        val path = exchange.requestURI.path.removePrefix("/products").trim('/')
        when {
            path.isEmpty() -> respond(exchange, 200, batchBody(idsFrom(exchange.requestURI.query)))
            path == UNKNOWN_PRODUCT_ID.toString() -> respond(exchange, 404, """{"error":"no such product"}""")
            path == BROKEN_PRODUCT_ID.toString() -> respond(exchange, 500, """{"error":"boom"}""")
            else -> respond(exchange, 200, productBody(path.toLong()))
        }
    }

    private fun idsFrom(query: String?): List<Long> =
        query.orEmpty().split('&')
            .filter { it.startsWith("ids=") }
            .flatMap { it.removePrefix("ids=").split("%2C", ",") }
            .filter { it.isNotBlank() }
            .map { it.toLong() }
            .filter { it != UNKNOWN_PRODUCT_ID }

    private fun productBody(id: Long) =
        """{"id":$id,"name":"Desk lamp","price":19.99,"availableQuantity":$AVAILABLE_QUANTITY}"""

    private fun batchBody(ids: List<Long>) = ids.joinToString(prefix = "[", postfix = "]") { productBody(it) }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val UNKNOWN_PRODUCT_ID = 999L
        const val BROKEN_PRODUCT_ID = 500L
        const val AVAILABLE_QUANTITY = 100
    }
}
