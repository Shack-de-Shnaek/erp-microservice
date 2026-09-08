package finki.ukim.erp.inventory.policy

import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.stockitem.AdjustStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.readmodel.ProductView
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.ReservationLineEmbeddable
import finki.ukim.erp.inventory.readmodel.ReservationView
import finki.ukim.erp.inventory.readmodel.ReservationViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemView
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.GenericCommandMessage
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * The rule on its own, with the read model stubbed - no bus, no Spring context. What is being
 * tested is which commands the policy lets past and which it refuses, and the two repositories it
 * consults to decide are the only reason it needs collaborators at all.
 */
class ActiveProductReservationPolicyTest {

    private val activeProduct = "11111111-1111-1111-1111-111111111111"
    private val inactiveProduct = "22222222-2222-2222-2222-222222222222"
    private val orderRef = "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4"

    /** One stock item per product, its id derived from the product's so assertions can name it. */
    private fun stockItemIdFor(productId: String) = StockItemId.fromString("stock-$productId")

    private val stockItemViews = mapOf(
        "stock-$activeProduct" to StockItemView("stock-$activeProduct", activeProduct, 100, 0),
        "stock-$inactiveProduct" to StockItemView("stock-$inactiveProduct", inactiveProduct, 100, 0),
    )

    private val productViews = mapOf(
        activeProduct to ProductView(activeProduct, "SKU-A", "Active thing", "piece", ProductStatus.ACTIVE),
        inactiveProduct to ProductView(inactiveProduct, "SKU-I", "Withdrawn thing", "piece", ProductStatus.INACTIVE),
    )

    /** What this service already holds for an order. Set per test; empty for a fresh reservation. */
    private var existingReservation: ReservationView? = null

    private val stockItemViewRepository = mock(StockItemViewRepository::class.java).also {
        `when`(it.findById(anyString())).thenAnswer { call ->
            Optional.ofNullable(stockItemViews[call.getArgument<String>(0)])
        }
    }

    private val productViewRepository = mock(ProductViewRepository::class.java).also {
        `when`(it.findById(anyString())).thenAnswer { call ->
            Optional.ofNullable(productViews[call.getArgument<String>(0)])
        }
    }

    private val reservationViewRepository = mock(ReservationViewRepository::class.java).also {
        `when`(it.findByOrderRef(anyString())).thenAnswer { call ->
            existingReservation?.takeIf { held -> held.orderRef == call.getArgument<String>(0) }
        }
    }

    private val policy = ActiveProductReservationPolicy(
        stockItemViewRepository,
        productViewRepository,
        reservationViewRepository,
    )

    /** Dispatch one command past the policy, as the command bus would. */
    private fun dispatch(payload: Any): CommandMessage<*> {
        val message = GenericCommandMessage.asCommandMessage<Any>(payload)
        return policy.handle(listOf(message)).apply(0, message)
    }

    @Test
    fun `reserving stock for an inactive product is refused`() {
        val exception = assertThrows<InactiveProductReservationException> {
            dispatch(ReserveStockCommand(stockItemIdFor(inactiveProduct), orderRef, Quantity(5)))
        }

        assertTrue(
            exception.message!!.contains(inactiveProduct) && exception.message!!.contains(orderRef),
            "the refusal should name the product and the order: ${exception.message}",
        )
    }

    @Test
    fun `reserving stock for an active product is let through untouched`() {
        val command = ReserveStockCommand(stockItemIdFor(activeProduct), orderRef, Quantity(5))

        val forwarded = dispatch(command)

        assertEquals(command, forwarded.payload, "the command should reach the bus unchanged")
    }

    /**
     * Withdrawing a product must not strand goods already committed to an approved order, so
     * everything that ends or fulfils an existing hold stays allowed. Adjustment too: stock of a
     * discontinued product is still received, counted and written off.
     */
    @Test
    fun `releasing, confirming and adjusting an inactive product's stock stay allowed`() {
        val stockItemId = stockItemIdFor(inactiveProduct)

        assertDoesNotThrow { dispatch(ReleaseReservationCommand(stockItemId, orderRef)) }
        assertDoesNotThrow { dispatch(ConfirmStockCommand(stockItemId, orderRef)) }
        assertDoesNotThrow { dispatch(AdjustStockCommand(stockItemId, -3, "write-off")) }
    }

    /**
     * `StockItem` treats a repeat reservation for the same order as a no-op, which is what makes a
     * redelivered `order.approved` harmless. Refusing here on the second delivery would turn that
     * into a failure redelivery could never clear.
     */
    @Test
    fun `re-reserving a hold this order already has is allowed even once the product is inactive`() {
        existingReservation = ReservationView(
            orderRef = orderRef,
            lines = mutableListOf(ReservationLineEmbeddable("stock-$inactiveProduct", inactiveProduct, 5)),
        )

        assertDoesNotThrow {
            dispatch(ReserveStockCommand(stockItemIdFor(inactiveProduct), orderRef, Quantity(5)))
        }
    }

    /** A different order's hold on the same product is somebody else's; this one is still new. */
    @Test
    fun `another order's hold on the same product does not excuse a new reservation`() {
        existingReservation = ReservationView(
            orderRef = "Order:someone-else",
            lines = mutableListOf(ReservationLineEmbeddable("stock-$inactiveProduct", inactiveProduct, 5)),
        )

        assertThrows<InactiveProductReservationException> {
            dispatch(ReserveStockCommand(stockItemIdFor(inactiveProduct), orderRef, Quantity(5)))
        }
    }

    /**
     * Not knowing the product is not the same as knowing it is withdrawn. The Pact verification
     * drives the saga with product ids that exist in no catalogue at all, and a guard that refused
     * on absence would fail those without a single product having been deactivated.
     */
    @Test
    fun `a stock item whose product the catalogue does not hold is let through`() {
        val orphan = "33333333-3333-3333-3333-333333333333"
        `when`(stockItemViewRepository.findById("stock-$orphan"))
            .thenReturn(Optional.of(StockItemView("stock-$orphan", orphan, 10, 0)))

        assertDoesNotThrow {
            dispatch(ReserveStockCommand(StockItemId.fromString("stock-$orphan"), orderRef, Quantity(1)))
        }
    }

    /** An unknown stock item is the aggregate's refusal to make, not this rule's. */
    @Test
    fun `an unknown stock item is left for the aggregate to refuse`() {
        assertDoesNotThrow {
            dispatch(ReserveStockCommand(StockItemId.fromString("stock-nowhere"), orderRef, Quantity(1)))
        }
    }
}
