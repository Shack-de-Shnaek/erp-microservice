package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.util.verifyStockAvailable
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.modelling.command.Repository
import org.springframework.stereotype.Component

/**
 * The second point where stock has to be confirmed before the order moves on - invoicing is what
 * commits the goods - and so, like [ApproveOrderCommandHandler], it is handled outside the
 * aggregate.
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
        inventoryCatalog.verifyStockAvailable(order.invoke { it.requestedQuantities() })
        order.execute { it.generateInvoice(command) }
    }
}
