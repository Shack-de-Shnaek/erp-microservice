package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.commands.ApproveOrderForConfirmedStockCommand
import finki.ukim.erp.orders.commands.RejectOrderForWithdrawnStockCommand
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.infrastructure.kafka.StockReleaseReason
import finki.ukim.erp.orders.infrastructure.kafka.StockWithdrawnFromOrder
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.modelling.command.AggregateNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * The rule that decides whether a stock release is news or an echo.
 *
 * Everything about *what* it then does lives on the aggregate and is tested there. What is tested
 * here is the one thing the aggregate cannot see: whether this service asked for the release.
 * Getting that wrong is severe in both directions - treating our own cancellations as withdrawals
 * would reject every order the moment it was cancelled, and it would do so in a loop, since the
 * rejection nullifies the order and nullification is what makes inventory release.
 */
class StockLifecycleEventHandlerTest {

    private val orderId = OrderId("Order:order-1")
    private val dispatched = mutableListOf<Any>()
    private var commandFailure: RuntimeException? = null

    private val commandGateway: CommandGateway = mock(CommandGateway::class.java).also { gateway ->
        `when`(gateway.sendAndWait<Any>(any())).thenAnswer { invocation ->
            commandFailure?.let { throw it }
            dispatched.add(invocation.getArgument<Any>(0))
            Unit
        }
    }

    private val handler = StockLifecycleEventHandler(commandGateway)

    private fun withdrawal(reason: StockReleaseReason) =
        StockWithdrawnFromOrder(orderId, reason, "inventory released 2 of stock item si-1")

    @Test
    fun `stock confirmed by inventory approves the order`() {
        handler.onStockConfirmed(orderId)

        assertEquals(listOf(ApproveOrderForConfirmedStockCommand(orderId)), dispatched)
    }

    @Test
    fun `stock withdrawn by inventory rejects the order`() {
        handler.onStockWithdrawn(withdrawal(StockReleaseReason.WITHDRAWN_BY_INVENTORY))

        assertTrue(
            dispatched.single() is RejectOrderForWithdrawnStockCommand,
            "a withdrawal should close the order, dispatched: $dispatched"
        )
    }

    /**
     * The loop guard. Cancelling an order nullifies it, inventory releases the hold, and the
     * release comes back here - so acting on it would reject every order this service cancels, and
     * the rejection would nullify it again.
     */
    @Test
    fun `a release the order itself brought about is ignored`() {
        handler.onStockWithdrawn(withdrawal(StockReleaseReason.ORDER_NULLIFIED))

        assertTrue(dispatched.isEmpty(), "acted on our own release: $dispatched")
    }

    /** A reason this service has not been taught about must not start closing orders on its own. */
    @Test
    fun `a release with an unrecognised reason is ignored`() {
        handler.onStockWithdrawn(withdrawal(StockReleaseReason.UNKNOWN))

        assertTrue(dispatched.isEmpty(), "acted on an unknown reason: $dispatched")
    }

    /**
     * The paid-order case. The aggregate refuses, and that refusal must not escape into the Kafka
     * listener, where it would fail the batch and have the broker redeliver a message that can
     * never succeed.
     */
    @Test
    fun `an order that cannot be rejected is reported rather than retried`() {
        commandFailure = InvalidOrderStateException("Order has been paid")

        handler.onStockWithdrawn(withdrawal(StockReleaseReason.WITHDRAWN_BY_INVENTORY))
        // Reaching here at all is the assertion: nothing was thrown.
    }

    /**
     * An event about an order that does not exist here, which is not the same as one that cannot
     * be acted on: there is no order to refuse, and none will appear later. Inventory can keep a
     * reservation under an id nothing answers to - the hold is placed before the order exists - and
     * a replayed Kafka backlog can ask about orders whose rows are long gone. Neither is a fault of
     * this service's, and neither is worth a stack trace, so both stop here.
     */
    @Test
    fun `stock confirmed for an order this service does not have is dropped`() {
        commandFailure = AggregateNotFoundException(orderId.value, "not found")

        handler.onStockConfirmed(orderId)
        // Reaching here at all is the assertion: nothing was thrown, and nothing is retried.
    }

    @Test
    fun `stock withdrawn for an order this service does not have is dropped`() {
        commandFailure = AggregateNotFoundException(orderId.value, "not found")

        handler.onStockWithdrawn(withdrawal(StockReleaseReason.WITHDRAWN_BY_INVENTORY))
        // Reaching here at all is the assertion: nothing was thrown, and nothing is retried.
    }
}
