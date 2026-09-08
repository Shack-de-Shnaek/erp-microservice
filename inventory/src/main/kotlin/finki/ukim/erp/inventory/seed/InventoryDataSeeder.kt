package finki.ukim.erp.inventory.seed

import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.product.DeactivateProductCommand
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import org.axonframework.commandhandling.gateway.CommandGateway
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Puts a small catalogue in place on a fresh database so the service - and the orders service
 * talking to it - has something to work with without anyone having to POST twenty times first.
 *
 * It runs on every boot but seeds only an empty catalogue: if `product_view` holds a single row,
 * this is somebody's data and the seeder leaves it alone. That check is what makes running always
 * safe, and it is deliberately the whole of it - a half-seeded database is treated as seeded, on
 * the grounds that guessing which of the ten products is missing is worse than doing nothing.
 *
 * Everything goes through the command gateway rather than the repositories, so the aggregates,
 * the read models and the events published to Kafka all come out the same as they would from the
 * REST API. The projections run on subscribing processors, so each command's view row is written
 * before the next command is sent.
 */
@Component
class InventoryDataSeeder(
    private val commandGateway: CommandGateway,
    private val productViewRepository: ProductViewRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val existing = productViewRepository.count()
        if (existing > 0) {
            log.info("Inventory catalogue already holds {} product(s); skipping seed", existing)
            return
        }

        log.info("Empty inventory catalogue; seeding {} products", CATALOGUE.size)
        CATALOGUE.forEach { entry ->
            val productId = ProductId.generate()
            commandGateway.sendAndWait<Any>(
                CreateProductCommand(
                    productId = productId,
                    sku = Sku(entry.sku),
                    name = ProductName(entry.name),
                    unitOfMeasure = UnitOfMeasure(entry.unitOfMeasure),
                ),
            )
            commandGateway.sendAndWait<Any>(
                CreateStockItemCommand(
                    stockItemId = StockItemId.generate(),
                    productRef = ProductRef(productId),
                    onHand = Quantity(ON_HAND),
                    reorderThreshold = ReorderThreshold(REORDER_THRESHOLD),
                ),
            )
            // Deactivation is a second command on purpose: a product is born active, and the one
            // inactive product per unit of measure exists to give the status filters something to
            // return, not to model a different kind of creation.
            if (!entry.active) {
                commandGateway.sendAndWait<Any>(DeactivateProductCommand(productId))
            }
            log.info("Seeded {} ({}, {})", entry.sku, entry.unitOfMeasure, if (entry.active) "active" else "inactive")
        }
        log.info("Inventory seed complete: {} products, {} on hand each, no reservations", CATALOGUE.size, ON_HAND)
    }

    private data class SeedProduct(
        val sku: String,
        val name: String,
        val unitOfMeasure: String,
        val active: Boolean = true,
    )

    private companion object {
        const val ON_HAND = 10
        const val REORDER_THRESHOLD = 2

        /**
         * Ten products: five measured in pieces, three in kilograms, two in litres, with exactly
         * one of each unit inactive. Fixed rather than randomly generated - SKUs are unique keys
         * and a seed that produces the same catalogue on every machine is one that a test, a demo
         * or a bug report can refer to by name.
         */
        val CATALOGUE = listOf(
            SeedProduct("PCE-001", "Ceramic mug 350ml", "piece"),
            SeedProduct("PCE-002", "Hex bolt M8x40", "piece"),
            SeedProduct("PCE-003", "LED bulb 9W E27", "piece"),
            SeedProduct("PCE-004", "Cardboard box 40x30x30", "piece"),
            SeedProduct("PCE-005", "Safety goggles", "piece", active = false),
            SeedProduct("KG-001", "Wheat flour type 500", "kg"),
            SeedProduct("KG-002", "Granulated sugar", "kg"),
            SeedProduct("KG-003", "Coarse sea salt", "kg", active = false),
            SeedProduct("L-001", "Sunflower oil", "l"),
            SeedProduct("L-002", "Distilled water", "l", active = false),
        )
    }
}
