package finki.ukim.erp.inventory.pact

import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.readmodel.ProductView
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemView
import finki.ukim.erp.inventory.readmodel.ReservationViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import finki.ukim.erp.inventory.web.fetchWithRetry
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import finki.ukim.erp.inventory.config.TestSecurityConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class, PactVerificationInvocationContextProvider::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Provider("inventory")
@PactFolder("pacts/http")
@ActiveProfiles("test")
@Import(TestSecurityConfig::class)
class PactHttpProviderTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var productViewRepository: ProductViewRepository

    @Autowired
    lateinit var stockItemViewRepository: StockItemViewRepository

    @Autowired
    lateinit var reservationViewRepository: ReservationViewRepository

    @Autowired
    lateinit var commandGateway: CommandGateway

    @BeforeEach
    fun setUp(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port)
    }

    @State("a product with id exists")
    fun `setup product`() {
        productViewRepository.save(
            ProductView(
                productId = "11111111-1111-1111-1111-111111111111",
                sku = "SKU-001",
                name = "Widget",
                unitOfMeasure = "pcs",
                status = ProductStatus.ACTIVE,
            ),
        )
    }

    @State("a stock item for the product exists")
    fun `setup stock item`() {
        stockItemViewRepository.save(
            StockItemView(
                stockItemId = "22222222-2222-2222-2222-222222222222",
                productId = "11111111-1111-1111-1111-111111111111",
                onHand = 100,
                reserved = 0,
                reorderThreshold = 10,
            ),
        )
    }

    /**
     * A product that exists and whose stock is entirely spoken for. Orders needs this case in the
     * contract because availability is `onHand - reserved`: a stub that only ever answered with
     * plenty free would let a client that read `onHand` alone pass, and oversell in production.
     */
    @State("a product whose stock is entirely reserved exists")
    fun `setup fully reserved product`() {
        productViewRepository.save(
            ProductView(
                productId = "33333333-3333-3333-3333-333333333333",
                sku = "SKU-003",
                name = "Notebook",
                unitOfMeasure = "pcs",
                status = ProductStatus.ACTIVE,
            ),
        )
        stockItemViewRepository.save(
            StockItemView(
                stockItemId = "44444444-4444-4444-4444-444444444444",
                productId = "33333333-3333-3333-3333-333333333333",
                onHand = 8,
                reserved = 8,
                reorderThreshold = 10,
            ),
        )
    }

    /**
     * Deliberately empty, and the assertion is that it stays that way: orders relies on a 404
     * meaning "no such product" rather than "inventory is unwell", and answers a customer
     * differently for each.
     */
    @State("no product with that id exists")
    fun `setup unknown product`() {
        productViewRepository.deleteById("99999999-9999-9999-9999-999999999999")
    }

    @TestTemplate
    fun pactVerificationTestTemplate(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    // ------------------------------------------------------------------ reservation states
    //
    // These two cannot be set up by seeding the read model, and that difference is the point. The
    // states above answer GETs, which are served from the views, so a row is enough. Reserving
    // sends a command to the `StockItem` *aggregate*, and an aggregate with no rows behind it does
    // not exist to be commanded - a seeded view would make the endpoint answer 404 for a product
    // Pact was told is there. So the fixtures are built the way the real ones are: through the
    // command gateway, exactly as the seeder and the REST API build them.

    @State("a product with reservable stock exists")
    fun `setup reservable product`() {
        createProductWithStock()
    }

    @State("an order is holding stock")
    fun `setup held reservation`() {
        createProductWithStock()
        if (reservationViewRepository.findByOrderRef(HELD_ORDER_REF) == null) {
            commandGateway.sendAndWait<Any>(
                ReserveStockCommand(reservableStockItemId(), HELD_ORDER_REF, Quantity(2)),
            )
            fetchWithRetry { reservationViewRepository.findByOrderRef(HELD_ORDER_REF) }
        }
    }

    /**
     * Idempotent, because Pact sets a state up before every interaction that names it and the
     * database outlives all of them. Creating the aggregate a second time would fail on the id.
     */
    private fun createProductWithStock() {
        if (productViewRepository.findById(RESERVABLE_PRODUCT_ID).isPresent) {
            return
        }
        val productId = ProductId.fromString(RESERVABLE_PRODUCT_ID)
        commandGateway.sendAndWait<Any>(
            CreateProductCommand(
                productId = productId,
                sku = Sku("SKU-RESERVABLE"),
                name = ProductName("Reservable widget"),
                unitOfMeasure = UnitOfMeasure("pcs"),
            ),
        )
        commandGateway.sendAndWait<Any>(
            CreateStockItemCommand(
                stockItemId = StockItemId.generate(),
                productRef = ProductRef(productId),
                onHand = Quantity(100),
                reorderThreshold = ReorderThreshold(10),
            ),
        )
        // The projections lag the commands that write them, and the endpoint under verification
        // reads them, so the state is not ready until they have caught up.
        fetchWithRetry { productViewRepository.findById(RESERVABLE_PRODUCT_ID).orElse(null) }
        fetchWithRetry { stockItemViewRepository.findByProductId(RESERVABLE_PRODUCT_ID) }
    }

    private fun reservableStockItemId(): StockItemId =
        StockItemId.fromString(
            checkNotNull(stockItemViewRepository.findByProductId(RESERVABLE_PRODUCT_ID)) {
                "the reservable product's stock item should exist by now"
            }.stockItemId,
        )

    private companion object {
        const val RESERVABLE_PRODUCT_ID = "55555555-5555-5555-5555-555555555555"
        const val HELD_ORDER_REF = "Order:66666666-6666-6666-6666-666666666666"
    }
}
