package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.TransactionId
import finki.ukim.erp.orders.clients.InventoryCatalog
import finki.ukim.erp.orders.commands.ApproveOrderCommand
import finki.ukim.erp.orders.commands.CancelOrderCommand
import finki.ukim.erp.orders.commands.CreateOrderCommand
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.commands.RegisterPaymentCommand
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.commands.ReverseInvoiceCommand
import finki.ukim.erp.orders.commands.UpdateInvoiceLineItemsCommand
import finki.ukim.erp.orders.commands.UpdateOrderItemsCommand
import finki.ukim.erp.orders.dto.InvoiceLineItemRequest
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.util.priceItems
import finki.ukim.erp.orders.views.InvoiceView
import finki.ukim.erp.orders.views.OrderView
import finki.ukim.erp.orders.views.TransactionView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * The seam between the REST layer and Axon.
 *
 * Its job is everything the aggregate is not allowed to do itself: mint identifiers, generate
 * invoice numbers, and ask the inventory service for prices. It also turns what arrived as loose
 * JSON into the domain's own types, so a malformed quantity or EMBG is refused here, at the edge,
 * rather than halfway through handling a command.
 *
 * Re-checking stock at approval and invoicing needs the order's current state as well as the
 * inventory service, so it happens one step later, in the external command handlers in
 * [finki.ukim.erp.orders.handlers].
 *
 * Commands are dispatched with `sendAndWait`, so by the time one returns the aggregate's row has
 * been written and the follow-up read sees it.
 */
@Service
class OrderCommandService(
    private val commandGateway: CommandGateway,
    private val orderViewReadService: OrderViewReadService,
    private val invoiceViewReadService: InvoiceViewReadService,
    private val transactionViewReadService: TransactionViewReadService,
    private val inventoryCatalog: InventoryCatalog
) {

    fun createOrder(name: String, surname: String, customerId: String, items: List<OrderItemRequest>): OrderView {
        val orderId = OrderId.random()
        commandGateway.sendAndWait<Any>(
            CreateOrderCommand(
                orderId = orderId,
                customer = CustomerName(name, surname),
                customerId = customerId,
                items = inventoryCatalog.priceItems(items)
            )
        )
        return orderViewReadService.findById(orderId)
    }

    fun updateOrderItems(orderId: OrderId, items: List<OrderItemRequest>): OrderView {
        commandGateway.sendAndWait<Any>(
            UpdateOrderItemsCommand(orderId = orderId, items = inventoryCatalog.priceItems(items))
        )
        return orderViewReadService.findById(orderId)
    }

    fun approveOrder(orderId: OrderId): OrderView {
        // Stock is re-checked by ApproveOrderCommandHandler, inside the command's unit of work.
        commandGateway.sendAndWait<Any>(ApproveOrderCommand(orderId))
        return orderViewReadService.findById(orderId)
    }

    fun rejectOrder(orderId: OrderId): OrderView {
        commandGateway.sendAndWait<Any>(RejectOrderCommand(orderId))
        return orderViewReadService.findById(orderId)
    }

    fun cancelOrder(orderId: OrderId, customerId: String): OrderView {
        commandGateway.sendAndWait<Any>(
            CancelOrderCommand(
                orderId = orderId,
                customerId = customerId,
                reversalTransactionId = TransactionId.random()
            )
        )
        return orderViewReadService.findById(orderId)
    }

    fun registerPayment(orderId: OrderId, amount: BigDecimal, paymentType: PaymentType): TransactionView {
        val transactionId = TransactionId.random()
        commandGateway.sendAndWait<Any>(
            RegisterPaymentCommand(
                orderId = orderId,
                transactionId = transactionId,
                amount = Money.fromExternal(amount),
                paymentType = paymentType
            )
        )
        return transactionViewReadService.findById(transactionId)
    }

    fun generateInvoice(orderId: OrderId, embg: String): InvoiceView {
        val invoiceId = InvoiceId.random()
        commandGateway.sendAndWait<Any>(
            GenerateInvoiceCommand(
                orderId = orderId,
                invoiceId = invoiceId,
                invoiceNumber = generateInvoiceNumber(),
                embg = Embg(embg)
            )
        )
        return invoiceViewReadService.findById(invoiceId)
    }

    fun updateInvoiceLineItems(invoiceId: InvoiceId, items: List<InvoiceLineItemRequest>): InvoiceView {
        val orderId = invoiceViewReadService.findById(invoiceId).orderId
            ?: throw IllegalStateException("Invoice $invoiceId is not attached to an order")

        commandGateway.sendAndWait<Any>(
            UpdateInvoiceLineItemsCommand(
                orderId = orderId,
                items = items.map {
                    PricedItem(
                        productId = ProductId(it.inventoryItemId),
                        quantity = Quantity(it.quantity),
                        price = Money.fromExternal(it.price)
                    )
                }
            )
        )
        return invoiceViewReadService.findById(invoiceId)
    }

    fun reverseInvoice(invoiceId: InvoiceId): InvoiceView {
        val orderId = invoiceViewReadService.findById(invoiceId).orderId
            ?: throw IllegalStateException("Invoice $invoiceId is not attached to an order")

        commandGateway.sendAndWait<Any>(
            ReverseInvoiceCommand(orderId = orderId, reversalTransactionId = TransactionId.random())
        )
        return invoiceViewReadService.findById(invoiceId)
    }

    private fun generateInvoiceNumber(): InvoiceNumber =
        InvoiceNumber("INV-${UUID.randomUUID().toString().take(8).uppercase()}")
}
