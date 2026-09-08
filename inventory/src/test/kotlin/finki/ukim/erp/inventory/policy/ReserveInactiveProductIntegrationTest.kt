package finki.ukim.erp.inventory.policy

import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.product.DeactivateProductCommand
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.ReactivateProductCommand
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import finki.ukim.erp.inventory.web.fetchWithRetry
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * The same rule, but through the real command bus - which is the half the unit test cannot cover.
 * [ActiveProductReservationPolicy] only does anything if it is actually registered as a dispatch
 * interceptor, and nothing about the class itself would fail if that registration were dropped.
 *
 * Every fixture here is created by this test. The seeded catalogue already holds inactive products
 * with stock on them, which would do, but sharing them would leave reservations behind on rows the
 * other tests in this context read.
 *
 * ## Why it waits
 *
 * The policy decides from `product_view`, and this service's projections are not readable the
 * instant the command that produced them returns - which is why every controller here fetches
 * through [fetchWithRetry]. So does this test: [awaitStatus] waits for the catalogue to actually
 * say what the command told it, and only then is the reservation judged. Asserting straight after
 * `sendAndWait` would be testing the projection's latency, not the rule.
 */
@SpringBootTest
class ReserveInactiveProductIntegrationTest {

    @Autowired
    private lateinit var commandGateway: CommandGateway

    @Autowired
    private lateinit var stockItemViewRepository: StockItemViewRepository

    @Autowired
    private lateinit var productViewRepository: ProductViewRepository

    /** A fresh active product with stock on the shelf, and the id of its stock item. */
    private fun productWithStock(): Pair<ProductId, StockItemId> {
        val productId = ProductId.generate()
        val unique = UUID.randomUUID().toString().take(8)
        commandGateway.sendAndWait<Any>(
            CreateProductCommand(
                productId = productId,
                sku = Sku("TEST-$unique"),
                name = ProductName("Policy fixture $unique"),
                unitOfMeasure = UnitOfMeasure("piece"),
            ),
        )
        commandGateway.sendAndWait<Any>(
            CreateStockItemCommand(
                stockItemId = StockItemId.generate(),
                productRef = ProductRef(productId),
                onHand = Quantity(50),
                reorderThreshold = ReorderThreshold(5),
            ),
        )

        awaitStatus(productId, ProductStatus.ACTIVE)
        val stockItem = fetchWithRetry { stockItemViewRepository.findByProductId(productId.value) }
        assertNotNull(stockItem, "the stock item projection should have caught up")
        return productId to StockItemId.fromString(stockItem!!.stockItemId)
    }

    /** Blocks until the catalogue reads back the status just commanded, or gives up and says so. */
    private fun awaitStatus(productId: ProductId, expected: ProductStatus) {
        val view = fetchWithRetry {
            productViewRepository.findById(productId.value).orElse(null)?.takeIf { it.status == expected }
        }
        assertNotNull(view, "product_view never came to read $expected for $productId")
    }

    /**
     * Blocks until the stock projection reads back the expected reserved quantity.
     *
     * The `takeIf` is what makes the wait mean anything: [fetchWithRetry] stops at the first
     * non-null answer, and a stale `reserved = 0` is perfectly non-null.
     */
    private fun awaitReserved(productId: ProductId, expected: Int) {
        val reserved = fetchWithRetry {
            stockItemViewRepository.findByProductId(productId.value)?.reserved?.takeIf { it == expected }
        }
        assertEquals(expected, reserved, "stock_item_view never came to read $expected reserved")
    }

    @Test
    fun `a reservation is refused once the product has been deactivated`() {
        val (productId, stockItemId) = productWithStock()

        // While it is active the very same command goes through, so what the next one proves is the
        // deactivation and not some unrelated defect in the fixture.
        commandGateway.sendAndWait<Any>(ReserveStockCommand(stockItemId, "Order:while-active", Quantity(4)))

        commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
        awaitStatus(productId, ProductStatus.INACTIVE)

        val failure = assertThrows<Exception> {
            commandGateway.sendAndWait<Any>(ReserveStockCommand(stockItemId, "Order:after-withdrawal", Quantity(1)))
        }
        assertEquals(
            InactiveProductReservationException::class.java,
            rootCause(failure).javaClass,
            "expected the reservation to be refused for an inactive product, got: $failure",
        )

        // Refused, not partly applied: the hold taken while the product was active is all there is.
        awaitReserved(productId, 4)
    }

    @Test
    fun `reactivating a product makes it reservable again`() {
        val (productId, stockItemId) = productWithStock()

        commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
        awaitStatus(productId, ProductStatus.INACTIVE)
        assertThrows<Exception> {
            commandGateway.sendAndWait<Any>(ReserveStockCommand(stockItemId, "Order:blocked", Quantity(2)))
        }

        commandGateway.sendAndWait<Any>(ReactivateProductCommand(productId))
        awaitStatus(productId, ProductStatus.ACTIVE)
        commandGateway.sendAndWait<Any>(ReserveStockCommand(stockItemId, "Order:allowed", Quantity(2)))

        awaitReserved(productId, 2)
    }

    /** The gateway wraps what the interceptor threw; the assertions care about the original. */
    private fun rootCause(throwable: Throwable): Throwable =
        generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }.last()
}
