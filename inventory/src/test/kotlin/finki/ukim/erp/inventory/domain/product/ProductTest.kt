package finki.ukim.erp.inventory.domain.product

import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProductTest {

    private lateinit var fixture: FixtureConfiguration<Product>

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(Product::class.java)
    }

    private fun createCommand() = CreateProductCommand(
        sku = Sku("SKU-001"),
        name = ProductName("Widget"),
        unitOfMeasure = UnitOfMeasure("pcs"),
    )

    private fun createdEvent(command: CreateProductCommand) = ProductCreatedEvent(
        productId = command.productId,
        sku = command.sku,
        name = command.name,
        unitOfMeasure = command.unitOfMeasure,
    )

    @Test
    fun `creating a product applies the created event`() {
        val command = createCommand()

        fixture.givenNoPriorActivity()
            .`when`(command)
            .expectEvents(createdEvent(command))
    }

    @Test
    fun `updating a product applies the updated event`() {
        val command = createCommand()
        val created = createdEvent(command)
        val update = UpdateProductCommand(
            productId = command.productId,
            sku = Sku("SKU-002"),
            name = ProductName("Gadget"),
            unitOfMeasure = UnitOfMeasure("box"),
        )

        fixture.given(created)
            .`when`(update)
            .expectEvents(
                ProductUpdatedEvent(
                    productId = update.productId,
                    sku = update.sku,
                    name = update.name,
                    unitOfMeasure = update.unitOfMeasure,
                ),
            )
    }

    @Test
    fun `updating with unchanged values is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)
        val unchangedUpdate = UpdateProductCommand(
            productId = command.productId,
            sku = command.sku,
            name = command.name,
            unitOfMeasure = command.unitOfMeasure,
        )

        fixture.given(created)
            .`when`(unchangedUpdate)
            .expectNoEvents()
    }

    @Test
    fun `deactivating and reactivating applies the events`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(DeactivateProductCommand(command.productId))
            .expectEvents(ProductDeactivatedEvent(command.productId))

        fixture.given(created, ProductDeactivatedEvent(command.productId))
            .`when`(ReactivateProductCommand(command.productId))
            .expectEvents(ProductReactivatedEvent(command.productId))
    }

    @Test
    fun `deactivating an inactive product is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created, ProductDeactivatedEvent(command.productId))
            .`when`(DeactivateProductCommand(command.productId))
            .expectNoEvents()
    }

    @Test
    fun `reactivating an active product is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(ReactivateProductCommand(command.productId))
            .expectNoEvents()
    }
}