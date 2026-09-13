package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.util.verifyStockReserved
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.modelling.command.Repository
import org.springframework.stereotype.Component

/**
 * The one point where the goods behind the order have to be confirmed before it moves on -
 * invoicing is what bills for them - and so it is handled outside the aggregate, and against the
 * reservation rather than against free stock. An aggregate that reached for the inventory service
 * would no longer be a pure function of its own state, which is why this handler exists at all.
 *
 * Approval used to be the other such point. It no longer is: an order is approved by inventory
 * confirming its goods out, and that message is its own proof - see
 * [finki.ukim.erp.orders.handlers.StockLifecycleEventHandler].
 *
 * The invoice id and invoice number are still minted by
 * [finki.ukim.erp.orders.services.OrderCommandService] and carried on the command: they are
 * non-deterministic, and anything non-deterministic has to be decided before the aggregate sees
 * it or replaying the event stream would produce a different invoice every time.
 */
@Component
class GenerateInvoiceCommandHandler(
    private val orderRepository: Repository<Order>,
    private val inventoryCatalog: InventoryCatalog
) {

    @CommandHandler
    fun handle(command: GenerateInvoiceCommand) {
        val order = orderRepository.load(command.orderId.value)
        inventoryCatalog.verifyStockReserved(command.orderId.value, order.invoke { it.requestedQuantities() })
        order.execute { it.generateInvoice(command) }
    }
}
