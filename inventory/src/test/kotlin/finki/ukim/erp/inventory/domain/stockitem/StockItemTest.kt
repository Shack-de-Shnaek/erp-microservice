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
    fun `raising a hold contests only the increase`() {
        val command = createCommand()
        val created = createdEvent(command)
        // Somebody else holds 50 and we hold 20, so 30 of the 100 are free. Going 20 -> 50 asks for
        // exactly those 30, and nothing we already hold is ever put back at risk.
        val otherOrder = StockReservedEvent(command.stockItemId, "order-other", Quantity(50))
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(20))

        fixture.given(created, otherOrder, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(50)))
            .expectEvents(
                StockReservationAmendedEvent(command.stockItemId, "order-1", Quantity(20), Quantity(50)),
            )
            .expectState { state ->
                check(state.reserved == Quantity(100)) { "reserved should be 50 + 50" }
                check(state.reservationLedger["order-1"] == Quantity(50)) { "ledger should read 50" }
            }
    }

    @Test
    fun `raising a hold beyond what is free is rejected`() {
        val command = createCommand()
        val created = createdEvent(command)
        val otherOrder = StockReservedEvent(command.stockItemId, "order-other", Quantity(50))
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(20))

        // 30 free, so 20 -> 60 needs 40 more and cannot be met.
        fixture.given(created, otherOrder, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(60)))
            .expectException(IllegalStateException::class.java)
    }

    @Test
    fun `lowering a hold cannot fail even with nothing free`() {
        val command = createCommand()
        val created = createdEvent(command)
        // Every unit on the shelf is spoken for; giving some back is still always allowed.
        val otherOrder = StockReservedEvent(command.stockItemId, "order-other", Quantity(60))
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(40))

        fixture.given(created, otherOrder, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(10)))
            .expectEvents(
                StockReservationAmendedEvent(command.stockItemId, "order-1", Quantity(40), Quantity(10)),
            )
            .expectState { state ->
                check(state.reserved == Quantity(70)) { "reserved should be 60 + 10" }
            }
    }

    @Test
    fun `an order holding the last of the stock can still amend it`() {
        val command = createCommand()
        val created = createdEvent(command)
        // The regression the amend command exists for: under release-then-reserve this order gave
        // up all 100 and had to win them back, so amending could lose stock it already had.
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(100))

        fixture.given(created, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(100)))
            .expectNoEvents()
    }

    @Test
    fun `amending an order that holds nothing is refused rather than reserved`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(AmendReservationCommand(command.stockItemId, "order-unknown", Quantity(5)))
            .expectException(IllegalStateException::class.java)
    }

    @Test
    fun `amending to the quantity already held changes nothing`() {
        val command = createCommand()
        val created = createdEvent(command)
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(30)))
            .expectNoEvents()
    }

    @Test
    fun `amending to zero is refused - releasing is the way to end a hold`() {
        val command = createCommand()
        val created = createdEvent(command)
        val ourOrder = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, ourOrder)
            .`when`(AmendReservationCommand(command.stockItemId, "order-1", Quantity(0)))
            .expectException(IllegalStateException::class.java)
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
    fun `confirmed goods can be returned to the shelf`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))
        val confirmed = StockConfirmedEvent(command.stockItemId, "order-1", Quantity(30))

        // The reversal case: the goods went out, the customer was refunded, and the stock has to
        // come back. Before the confirmed ledger existed the quantity was forgotten at confirmation
        // and this was simply impossible - the release found nothing and silently did nothing.
        fixture.given(created, reserved30, confirmed)
            .`when`(ReleaseReservationCommand(command.stockItemId, "order-1", ReleaseReason.ORDER_NULLIFIED))
            .expectEvents(
                StockReturnedEvent(command.stockItemId, "order-1", Quantity(30), ReleaseReason.ORDER_NULLIFIED),
            )
            .expectState { state ->
                check(state.onHand == Quantity(100)) { "the goods should be back on the shelf" }
                check(state.reserved == Quantity(0)) { "nothing is reserved any more" }
                check(!state.confirmedLedger.containsKey("order-1")) { "confirmed ledger should be cleared" }
            }
    }

    @Test
    fun `confirming moves the hold to the confirmed ledger rather than forgetting it`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30)
            .`when`(ConfirmStockCommand(command.stockItemId, "order-1"))
            .expectState { state ->
                check(!state.reservationLedger.containsKey("order-1")) { "no longer an active hold" }
                check(state.confirmedLedger["order-1"] == Quantity(30)) { "should be recorded as confirmed" }
            }
    }

    @Test
    fun `returning stock twice is a no-op the second time`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))
        val confirmed = StockConfirmedEvent(command.stockItemId, "order-1", Quantity(30))
        val returned = StockReturnedEvent(command.stockItemId, "order-1", Quantity(30))

        // Redelivery has to be harmless, or a repeated nullification would restock the same goods
        // over and over.
        fixture.given(created, reserved30, confirmed, returned)
            .`when`(ReleaseReservationCommand(command.stockItemId, "order-1"))
            .expectNoEvents()
    }

    @Test
    fun `a release carries the reason it was made for`() {
        val command = createCommand()
        val created = createdEvent(command)
        val reserved30 = StockReservedEvent(command.stockItemId, "order-1", Quantity(30))

        fixture.given(created, reserved30)
            .`when`(
                ReleaseReservationCommand(command.stockItemId, "order-1", ReleaseReason.WITHDRAWN_BY_INVENTORY),
            )
            .expectEvents(
                StockReservationReleasedEvent(
                    command.stockItemId,
                    "order-1",
                    Quantity(30),
                    ReleaseReason.WITHDRAWN_BY_INVENTORY,
                ),
            )
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

    @Test
    fun `deleting a stock item applies the deleted event and clears state`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(DeleteStockItemCommand(command.stockItemId))
            .expectEvents(StockItemDeletedEvent(command.stockItemId))
            .expectState { state ->
                check(state.productRef == null) { "productRef should be null after delete" }
                check(state.onHand == null) { "onHand should be null after delete" }
                check(state.reserved == null) { "reserved should be null after delete" }
                check(state.reorderThreshold == null) { "reorderThreshold should be null after delete" }
                check(state.reservationLedger.isEmpty()) { "reservationLedger should be empty after delete" }
            }
    }

    @Test
    fun `updating reorder threshold applies the threshold updated event`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(UpdateReorderThresholdCommand(command.stockItemId, ReorderThreshold(25)))
            .expectEvents(StockReorderThresholdUpdatedEvent(command.stockItemId, ReorderThreshold(25)))
            .expectState { state ->
                check(state.reorderThreshold == ReorderThreshold(25)) { "threshold should be updated" }
            }
    }

    @Test
    fun `updating reorder threshold to the same value is a no-op`() {
        val command = createCommand()
        val created = createdEvent(command)

        fixture.given(created)
            .`when`(UpdateReorderThresholdCommand(command.stockItemId, ReorderThreshold(10)))
            .expectNoEvents()
    }
}