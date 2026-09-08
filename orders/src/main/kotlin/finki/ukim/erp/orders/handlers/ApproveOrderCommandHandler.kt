package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.commands.ApproveOrderCommand
import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.util.verifyStockReserved
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.modelling.command.Repository
import org.springframework.stereotype.Component

/**
 * An external command handler: a plain Spring component that owns [ApproveOrderCommand], loads the
 * aggregate from Axon's repository itself, and invokes a method on it.
 *
 * Approval is a point at which the goods behind the order have to be confirmed, and that check
 * needs the inventory service. Putting it in a `@CommandHandler` on the aggregate would mean
 * injecting the [InventoryCatalog] into the aggregate, and an aggregate that reaches outside itself
 * is no longer a pure function of its event history.
 *
 * What is confirmed is the *reservation*, not availability. The stock was put aside when the order
 * was placed, so it is not available any more - by design - and the only question left is whether
 * the hold is still standing. Handling the command out here keeps the aggregate a clean domain object and
 * leaves it holding only the rule it can decide on its own: that a pending order is the only kind
 * that can be approved.
 *
 * Doing the check here rather than in [finki.ukim.erp.orders.services.OrderCommandService] before
 * dispatch also puts it inside the command's unit of work, so a stock shortfall and the approval
 * can never straddle a transaction boundary.
 */
@Component
class ApproveOrderCommandHandler(
    private val orderRepository: Repository<Order>,
    private val inventoryCatalog: InventoryCatalog
) {

    @CommandHandler
    fun handle(command: ApproveOrderCommand) {
        val order = orderRepository.load(command.orderId.value)
        inventoryCatalog.verifyStockReserved(command.orderId.value, order.invoke { it.requestedQuantities() })
        order.execute { it.approve(command) }
    }
}
