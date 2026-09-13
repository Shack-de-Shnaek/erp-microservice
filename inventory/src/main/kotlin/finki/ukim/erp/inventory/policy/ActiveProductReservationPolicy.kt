package finki.ukim.erp.inventory.policy

import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.stockitem.AmendReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.ReservationViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.messaging.MessageDispatchInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.function.BiFunction

/**
 * Thrown when stock is asked for on a product the catalogue has withdrawn.
 *
 * An [IllegalStateException] on purpose: that is what `StockItem` already throws when it refuses a
 * reservation it cannot cover, and a caller that handles one refusal should not have to learn a
 * second exception hierarchy to handle the other. The distinct type is there so a test can say
 * which refusal it means.
 */
class InactiveProductReservationException(productId: String, orderRef: String) :
    IllegalStateException("Cannot reserve stock for product $productId (order $orderRef): the product is INACTIVE")

/**
 * Stock is never reserved for a product the catalogue has withdrawn.
 *
 * ## Why this is not in the aggregate
 *
 * It is the natural place to look, and it cannot go there. `StockItem` knows which product it is
 * for - it holds a `ProductRef` - but not whether that product is currently active: status lives on
 * the `Product` aggregate, and an aggregate may not reach into another one to decide its own
 * invariants. The rule spans both, so like the equivalent rule on the orders side
 * (`ProductDeactivatedEventHandler`, in the other service) it lives between them rather than inside
 * either.
 *
 * A dispatch interceptor rather than a check at the call sites, because a rule that has to be
 * remembered at each of them is one the next caller will not remember. Today reservations come in
 * through [ReservationController][finki.ukim.erp.inventory.web.ReservationController] alone - both
 * the whole-order `POST` and the per-line work an amendment turns into - but every
 * [ReserveStockCommand] goes over the command bus whatever sends it, so this sees all of them.
 *
 * ## What it deliberately does not touch
 *
 * Only stock an order does not already hold. Releasing and confirming an existing hold stay allowed
 * on an inactive product, and that is the point rather than an oversight: withdrawing a product must
 * not strand goods already committed to an approved order, which is exactly what blocking
 * `ConfirmStockCommand` would do. `AdjustStockCommand` is likewise untouched - stock of a
 * discontinued product still gets received, counted and written off.
 *
 * [AmendReservationCommand] is judged, but only when it asks for **more** than the order already
 * holds, because only then is it taking stock of a withdrawn product off the shelf. An amendment
 * that lowers a line, or leaves it where it is, is let through - otherwise withdrawing one product
 * would freeze every order containing it, and a customer could not even remove the withdrawn line
 * from their own order, which is the one change they most obviously should be allowed to make.
 *
 * ## Two edges
 *
 * **A product this service cannot see is allowed through.** A missing `ProductView` means the
 * catalogue has no row for it, not that it was withdrawn, and refusing on that basis would turn
 * "the read model has not been populated" into random reservation failures - including in the Pact
 * verification, which drives the saga with product ids that exist in no catalogue at all. The guard
 * refuses what it knows to be inactive; it does not require proof of life.
 *
 * **A hold that already exists is allowed through.** `StockItem` treats a repeat
 * [ReserveStockCommand] for the same `orderRef` as a silent no-op, which is what makes a redelivered
 * `order.approved` message harmless. Were this to refuse on the second delivery, deactivating a
 * product between the two would turn that idempotency into a failure that redelivery can never
 * clear. So an order that already holds this product is not a new reservation and is not judged.
 *
 * ## How current the answer is
 *
 * `product_view` is a projection, and this service's projections are not readable the instant the
 * command that wrote them returns - which is why every controller here fetches through
 * [fetchWithRetry][finki.ukim.erp.inventory.web.fetchWithRetry]. So a reservation dispatched in the
 * moments right after a deactivation can still see the product as active and be allowed. That is
 * the same guarantee the equivalent rule on the orders side gives, since it arrives over Kafka:
 * the catalogue closes shortly after the decision, not at the same instant as it. A check that
 * closed the instant the decision was made would have to load the `Product` aggregate on every
 * reservation, which is the cost this trades away.
 *
 * ## The race this leaves
 *
 * A reservation dispatched just before its product was deactivated can still arrive here
 * afterwards and be refused. Placing an order takes that refusal well - the reservation is
 * synchronous, so orders is told "no" and never creates the order at all. An *amendment* is the
 * awkward case: a line raised on a product withdrawn a moment earlier is refused, and the order
 * stands on the lines it had. That is the same answer it would get a moment later, so nothing is
 * left inconsistent; the customer is simply told to try again with the withdrawn product taken off.
 */
@Component
class ActiveProductReservationPolicy(
    private val stockItemViewRepository: StockItemViewRepository,
    private val productViewRepository: ProductViewRepository,
    private val reservationViewRepository: ReservationViewRepository,
) : MessageDispatchInterceptor<CommandMessage<*>> {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Registers on the bus rather than being picked up by configuration, so the rule and the fact
     * that it is enforced are one file. Setter injection: taking the bus as a constructor argument
     * would make this bean depend on the bus while the bus is being built.
     */
    @Autowired
    fun registerOn(commandBus: CommandBus) {
        commandBus.registerDispatchInterceptor(this)
    }

    override fun handle(
        messages: List<CommandMessage<*>>,
    ): BiFunction<Int, CommandMessage<*>, CommandMessage<*>> =
        BiFunction { _, message ->
            when (val payload = message.payload) {
                is ReserveStockCommand -> rejectIfProductIsInactive(payload)
                is AmendReservationCommand -> rejectIfRaisingInactiveProduct(payload)
                else -> Unit
            }
            message
        }

    private fun rejectIfProductIsInactive(command: ReserveStockCommand) {
        val stockItem = stockItemViewRepository.findById(command.stockItemId.value).orElse(null)
        if (stockItem == null) {
            // Nothing to judge: the aggregate will refuse a stock item that does not exist, and
            // saying why is its business rather than this rule's.
            return
        }

        if (alreadyReserved(command.orderRef, command.stockItemId.value)) {
            return
        }

        val product = productViewRepository.findById(stockItem.productId).orElse(null)
        if (product == null) {
            log.warn(
                "Reserving stock item {} for order {} without a status check: the catalogue holds " +
                    "no product {}",
                command.stockItemId.value,
                command.orderRef,
                stockItem.productId,
            )
            return
        }

        if (product.status == ProductStatus.INACTIVE) {
            log.info(
                "Refusing to reserve {} of product {} for order {}: the product is inactive",
                command.quantity.amount,
                stockItem.productId,
                command.orderRef,
            )
            throw InactiveProductReservationException(stockItem.productId, command.orderRef)
        }
    }

    /**
     * The same rule for an amendment, applied to the increase rather than to the total.
     *
     * The quantity already held comes from the reservation view, which is the same picture the
     * caller amended against. It can lag the aggregate by a moment, so an amendment right after
     * another one may be judged on a slightly stale hold - the effect is only that a raise of one
     * or two units is occasionally weighed as a raise of three, and `StockItem` decides the real
     * arithmetic either way. Reading the aggregate here to do better would cost a load on every
     * amendment, which is the trade this class already makes everywhere else.
     */
    private fun rejectIfRaisingInactiveProduct(command: AmendReservationCommand) {
        val stockItem = stockItemViewRepository.findById(command.stockItemId.value).orElse(null) ?: return

        val held = heldQuantity(command.orderRef, command.stockItemId.value)
        if (held == null || command.quantity.amount <= held) {
            // Not taking anything new off the shelf: either the order holds nothing here and the
            // aggregate is about to refuse the amendment outright, or the line is level or coming
            // down, which a withdrawn product is no reason to prevent.
            return
        }

        val product = productViewRepository.findById(stockItem.productId).orElse(null)
        if (product == null) {
            log.warn(
                "Amending stock item {} for order {} without a status check: the catalogue holds " +
                    "no product {}",
                command.stockItemId.value,
                command.orderRef,
                stockItem.productId,
            )
            return
        }

        if (product.status == ProductStatus.INACTIVE) {
            log.info(
                "Refusing to raise order {} from {} to {} of product {}: the product is inactive",
                command.orderRef,
                held,
                command.quantity.amount,
                stockItem.productId,
            )
            throw InactiveProductReservationException(stockItem.productId, command.orderRef)
        }
    }

    /** How much of this stock item the order is recorded as holding, or null if it holds none. */
    private fun heldQuantity(orderRef: String, stockItemId: String): Int? =
        reservationViewRepository.findByOrderRef(orderRef)
            ?.lines
            ?.firstOrNull { it.stockItemId == stockItemId }
            ?.quantity

    /** Whether this order already holds this stock item - see the note on redelivery above. */
    private fun alreadyReserved(orderRef: String, stockItemId: String): Boolean =
        reservationViewRepository.findByOrderRef(orderRef)
            ?.lines
            ?.any { it.stockItemId == stockItemId }
            ?: false
}
