package finki.ukim.erp.inventory.web

import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.product.DeactivateProductCommand
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.util.UUID

/**
 * `POST /api/reservations` as a caller meets it - the orders service now places every order through
 * it, so what it accepts, what it refuses, and what it says when it refuses are all contract.
 *
 * Two things here are regressions waiting to happen rather than new rules. Reserving used to answer
 * 500 with an empty body for every refusal the domain made, which told the caller nothing; and a
 * multi-line request that failed on its last line kept the holds taken for the earlier ones. Both
 * have a test below.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var commandGateway: CommandGateway

    @Autowired
    private lateinit var stockItemViewRepository: StockItemViewRepository

    @Autowired
    private lateinit var productViewRepository: ProductViewRepository

    private val objectMapper = ObjectMapper()

    private fun orderRef() = "Order:${UUID.randomUUID()}"

    /** A fresh active product carrying [onHand] units, returned as its product id. */
    private fun productWithStock(onHand: Int = 10): ProductId {
        val productId = ProductId.generate()
        val unique = UUID.randomUUID().toString().take(8)
        commandGateway.sendAndWait<Any>(
            CreateProductCommand(
                productId = productId,
                sku = Sku("RES-$unique"),
                name = ProductName("Reservation fixture $unique"),
                unitOfMeasure = UnitOfMeasure("piece"),
            ),
        )
        commandGateway.sendAndWait<Any>(
            CreateStockItemCommand(
                stockItemId = StockItemId.generate(),
                productRef = ProductRef(productId),
                onHand = Quantity(onHand),
                reorderThreshold = ReorderThreshold(1),
            ),
        )
        awaitStatus(productId, ProductStatus.ACTIVE)
        assertNotNull(
            fetchWithRetry { stockItemViewRepository.findByProductId(productId.value) },
            "the stock item projection should have caught up",
        )
        return productId
    }

    /** The projections lag the commands that write them; see the note on ReserveInactiveProductIntegrationTest. */
    private fun awaitStatus(productId: ProductId, expected: ProductStatus) {
        assertNotNull(
            fetchWithRetry {
                productViewRepository.findById(productId.value).orElse(null)?.takeIf { it.status == expected }
            },
            "product_view never came to read $expected for $productId",
        )
    }

    private fun reservedFor(productId: ProductId): Int =
        stockItemViewRepository.findByProductId(productId.value)?.reserved ?: -1

    private fun awaitReserved(productId: ProductId, expected: Int) {
        assertEquals(
            expected,
            fetchWithRetry { reservedFor(productId).takeIf { it == expected } },
            "stock_item_view never came to read $expected reserved for $productId",
        )
    }

    private fun body(orderRef: String, vararg lines: Pair<ProductId, Int>): String =
        objectMapper.writeValueAsString(
            mapOf(
                "orderRef" to orderRef,
                "lines" to lines.map { mapOf("productId" to it.first.value, "quantity" to it.second) },
            ),
        )

    private fun reserve(orderRef: String, vararg lines: Pair<ProductId, Int>) =
        mockMvc.perform(
            post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body(orderRef, *lines)),
        ).andReturn().response

    private fun amendBody(vararg lines: Pair<ProductId, Int>): String =
        objectMapper.writeValueAsString(
            mapOf("lines" to lines.map { mapOf("productId" to it.first.value, "quantity" to it.second) }),
        )

    private fun amend(orderRef: String, vararg lines: Pair<ProductId, Int>) =
        mockMvc.perform(
            put("/api/reservations/$orderRef").contentType(MediaType.APPLICATION_JSON).content(amendBody(*lines)),
        ).andReturn().response

    @Test
    fun `amending an order holding the last of the stock keeps it`() {
        // The race the amend endpoint exists to close. Under release-then-reserve this order gave
        // all 10 back before asking for 8, and anything else in flight could take them in between.
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 10).status)
        awaitReserved(productId, 10)

        assertEquals(200, amend(orderRef, productId to 8).status)
        awaitReserved(productId, 8)

        // And back up again, into stock it never let go of.
        assertEquals(200, amend(orderRef, productId to 10).status)
        awaitReserved(productId, 10)
    }

    @Test
    fun `amending refuses an increase the shelf cannot cover and changes nothing`() {
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 6).status)
        awaitReserved(productId, 6)

        val refused = amend(orderRef, productId to 11)
        assertEquals(400, refused.status)
        assertTrue(
            refused.contentAsString.contains("Insufficient stock"),
            "the refusal should say what was wrong, was: ${refused.contentAsString}",
        )
        // The order still holds exactly what it held: a refused amendment moves nothing.
        awaitReserved(productId, 6)
    }

    @Test
    fun `amending adds, resizes and drops lines in one call`() {
        val kept = productWithStock(onHand = 10)
        val dropped = productWithStock(onHand = 10)
        val added = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, kept to 2, dropped to 3).status)
        awaitReserved(kept, 2)
        awaitReserved(dropped, 3)

        assertEquals(200, amend(orderRef, kept to 5, added to 1).status)

        awaitReserved(kept, 5)
        awaitReserved(dropped, 0)
        awaitReserved(added, 1)
    }

    @Test
    fun `amending a confirmed reservation is refused with a reason`() {
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 4).status)
        awaitReserved(productId, 4)
        assertEquals(
            200,
            mockMvc.perform(post("/api/reservations/$orderRef/confirm")).andReturn().response.status,
        )

        // The goods have gone out; there is no hold left to resize. A refusal, on the same status
        // as every other refusal this controller makes - the request is one it will not carry out.
        val refused = amend(orderRef, productId to 6)
        assertEquals(400, refused.status)
        assertTrue(
            refused.contentAsString.contains("confirmed"),
            "the refusal should say why, was: ${refused.contentAsString}",
        )
    }

    /** Confirmed goods still come back: an invoice can be reversed after the customer has paid. */
    @Test
    fun `releasing a confirmed reservation puts the goods back on the shelf`() {
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 4).status)
        awaitReserved(productId, 4)
        assertEquals(
            200,
            mockMvc.perform(post("/api/reservations/$orderRef/confirm")).andReturn().response.status,
        )
        awaitOnHand(productId, 6)

        assertEquals(204, mockMvc.perform(delete("/api/reservations/$orderRef")).andReturn().response.status)

        // Before the confirmed ledger existed this release found nothing, answered 204, and the
        // goods were lost from the ledger entirely.
        awaitOnHand(productId, 10)
    }

    private fun awaitOnHand(productId: ProductId, expected: Int) {
        assertEquals(
            expected,
            fetchWithRetry {
                stockItemViewRepository.findByProductId(productId.value)?.onHand?.takeIf { it == expected }
            },
            "stock_item_view never came to read $expected on hand for $productId",
        )
    }

    @Test
    fun `amending a reservation that does not exist is a 404, not a new reservation`() {
        val productId = productWithStock(onHand = 10)

        // The honest answer. Reserving instead would open a hold nobody asked for, and would hide
        // exactly the state an order needs to hear about: that it is backed by nothing.
        assertEquals(404, amend(orderRef(), productId to 1).status)
        awaitReserved(productId, 0)
    }

    @Test
    fun `amending an inactive product down is allowed, up is not`() {
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 5).status)
        awaitReserved(productId, 5)

        commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
        awaitStatus(productId, ProductStatus.INACTIVE)

        // Withdrawing a product must not trap an order that already holds some of it.
        assertEquals(200, amend(orderRef, productId to 2).status)
        awaitReserved(productId, 2)

        // But it may not take any more off the shelf.
        assertEquals(400, amend(orderRef, productId to 6).status)
        awaitReserved(productId, 2)
    }

    @Test
    fun `an order's stock is reserved and can be released again`() {
        val productId = productWithStock(onHand = 10)
        val orderRef = orderRef()

        assertEquals(201, reserve(orderRef, productId to 4).status)
        awaitReserved(productId, 4)

        val released = mockMvc.perform(delete("/api/reservations/$orderRef")).andReturn().response
        assertEquals(204, released.status)

        // The half that used to be broken: the release addressed a stock item looked up by a
        // product id that was really a stock item id, found nothing, and quietly did nothing.
        awaitReserved(productId, 0)
    }

    @Test
    fun `reserving stock for an inactive product is refused with a reason`() {
        val productId = productWithStock()
        commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
        awaitStatus(productId, ProductStatus.INACTIVE)

        val response = reserve(orderRef(), productId to 1)

        assertEquals(400, response.status)
        assertTrue(
            response.contentAsString.contains("INACTIVE"),
            "the refusal should say the product is inactive: ${response.contentAsString}",
        )
        awaitReserved(productId, 0)
    }

    @Test
    fun `reserving more than is available is refused with the numbers`() {
        val productId = productWithStock(onHand = 3)

        val response = reserve(orderRef(), productId to 4)

        assertEquals(400, response.status)
        assertTrue(
            response.contentAsString.contains("Insufficient stock"),
            "the refusal should name the shortfall: ${response.contentAsString}",
        )
    }

    @Test
    fun `reserving an unknown product is a 400, not a 404`() {
        val response = mockMvc.perform(
            post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(
                """{"orderRef":"${orderRef()}","lines":[{"productId":"no-such-product","quantity":1}]}""",
            ),
        ).andReturn().response

        // The request is invalid, not addressed at a missing resource: the caller asked to reserve
        // something that cannot be reserved, and the fix is to change the request.
        assertEquals(400, response.status)
        assertTrue(response.contentAsString.contains("No product with id"), response.contentAsString)
    }

    @Test
    fun `the same product twice in one reservation is refused rather than silently halved`() {
        val productId = productWithStock()

        val response = reserve(orderRef(), productId to 1, productId to 2)

        assertEquals(400, response.status)
        assertTrue(response.contentAsString.contains("only once"), response.contentAsString)
        awaitReserved(productId, 0)
    }

    @Test
    fun `a line that cannot be reserved undoes the lines that already were`() {
        val plentiful = productWithStock(onHand = 10)
        val scarce = productWithStock(onHand = 1)

        val response = reserve(orderRef(), plentiful to 2, scarce to 5)

        assertEquals(400, response.status)
        // Not "whatever part of the order happened to be available": the first line's hold is gone.
        awaitReserved(plentiful, 0)
        awaitReserved(scarce, 0)
    }

    @Test
    fun `an order cannot hold two reservations at once`() {
        val productId = productWithStock()
        val orderRef = orderRef()
        assertEquals(201, reserve(orderRef, productId to 1).status)

        val second = reserve(orderRef, productId to 1)

        assertEquals(400, second.status)
        assertTrue(second.contentAsString.contains("already holds a reservation"), second.contentAsString)
        awaitReserved(productId, 1)
    }
}
