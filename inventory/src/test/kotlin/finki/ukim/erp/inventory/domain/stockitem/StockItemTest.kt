package finki.ukim.erp.inventory.domain.stockitem

import finki.ukim.erp.inventory.domain.product.ProductId
import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StockItemTest {

    private lateinit var fixture: FixtureConfiguration<StockItem>

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(StockItem::class.java)
    }

    private fun createCommand() = CreateStockItemCommand(
        productRef = ProductRef(ProductId.generate()),
        onHand = Quantity(100),
        reorderThreshold = ReorderThreshold(10),
    )

    private fun createdEvent(command: CreateStockItemCommand) = StockItemCreatedEvent(
        stockItemId = command.stockItemId,
        productRef = command.productRef,
        onHand = command.onHand,
        reorderThreshold = command.reorderThreshold,
    )

    @Test
    fun `creating a stock item applies the created event`() {
        val command = createCommand()

        fixture.givenNoPriorActivity()
            .`when`(command)
            .expectEvents(createdEvent(command))
    }

    @Test
    fun `reserving stock applies the reserved event and updates state`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(ReserveStockCommand(command.stockItemId, "order-1", Quantity(30)))
            .expectEvents(StockReservedEvent(command.stockItemId, "order-1", Quantity(30)))
            .expectState { state ->
                check(state.onHand == Quantity(100)) { "onHand should remain 100" }
                check(state.reserved == Quantity(30)) { "reserved should be 30" }
                check(state.reservationLedger["order-1"] == Quantity(30)) { "ledger should track order-1" }
            }
    }

    @Test
    fun `reserving more than available stock is rejected`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved80 = StockReservedEvent(command.stockItemId, "order-1", Quantity(80))

        fixture.given(created, reserved80)
            .`when`(ReserveStockCommand(command.stockItemId, "order-2", Quantity(30)))
            .expectException(IllegalStateException::class.java)
    }

    @Test
    fun `re-reserving the same order ref is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30)
            .`when`(ReserveStockCommand(command.stockItemId, "order-1", Quantity(50)))
            .expectNoEvents()
    }

    @Test
    fun `releasing a reservation applies the released event`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30)
            .`when`(ReleaseReservationCommand(command.stockItemId, "order-1"))
            .expectEvents(StockReservationReleasedEvent(command.stockItemId, "order-1", Quantity(30)))
            .expectState { state ->
                check(state.reserved == Quantity(0)) { "reserved should drop back to 0" }
                check(state.onHand == Quantity(100)) { "onHand should be unchanged" }
                check(!state.reservationLedger.containsKey("order-1")) { "ledger entry should be removed" }
            }
    }

    @Test
    fun `releasing an unknown reservation is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(ReleaseReservationCommand(command.stockItemId, "order-unknown"))
            .expectNoEvents()
    }

    @Test
    fun `confirming a reservation removes physical stock`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30)
            .`when`(ConfirmStockCommand(command.stockItemId, "order-1"))
            .expectEvents(StockConfirmedEvent(command.stockItemId, "order-1", Quantity(30)))
            .expectState { state ->
                check(state.onHand == Quantity(70)) { "confirming should decrement onHand" }
                check(state.reserved == Quantity(0)) { "confirming should clear the reservation" }
                check(!state.reservationLedger.containsKey("order-1")) { "ledger entry should be removed" }
            }
    }

    @Test
    fun `confirming an unknown reservation is a silent no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(ConfirmStockCommand(command.stockItemId, "order-unknown"))
            .expectNoEvents()
    }

    @Test
    fun `releasing and re-reserving the same order ref works`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30, StockReservationReleasedEvent(command.stockItemId, "order-1", Quantity(30)))
            .`when`(ReserveStockCommand(command.stockItemId, "order-1", Quantity(20)))
            .expectEvents(StockReservedEvent(command.stockItemId, "order-1", Quantity(20)))
    }

    @Test
    fun `adjusting stock applies the adjusted event`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(AdjustStockCommand(command.stockItemId, 25, "restock"))
            .expectEvents(StockAdjustedEvent(command.stockItemId, 25, "restock"))
            .expectState { state ->
                check(state.onHand == Quantity(125)) { "onHand should be adjusted up" }
            }
    }

    @Test
    fun `adjusting stock below zero is rejected`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(AdjustStockCommand(command.stockItemId, -150, "write-off"))
            .expectException(IllegalStateException::class.java)
    }

    @Test
    fun `zero adjustment is a no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(AdjustStockCommand(command.stockItemId, 0, "no-op"))
            .expectNoEvents()
    }
}