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
import finki.ukim.erp.orders.util.priceItemsForAmendment
import finki.ukim.erp.orders.util.totalPerProduct
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
 * It is also where the goods are claimed. Placing an order asks inventory to put its stock aside
 * *before* the order is created, synchronously, so that an order which exists is always an order
 * something is being held for - see [createOrder]. Confirming that the hold is still standing needs
 * the order's current state as well as the inventory service, so it happens one step later, in the
 * external command handler in [finki.ukim.erp.orders.handlers].
 *
 * There is no `approveOrder` here, and its absence is deliberate. An order is approved when
 * inventory confirms its goods out of the warehouse, which reaches this service as `stock.confirmed`
 * and is dispatched by [finki.ukim.erp.orders.handlers.StockLifecycleEventHandler]. Nothing a caller
 * does to this service can approve an order.
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

    /**
     * Places an order, having first had inventory put the goods aside for it.
     *
     * The order of the two steps is the whole point. Inventory is asked to reserve the stock
     * *before* the order exists, and it is asked as a command - synchronously, with the answer
     * waited for - because a customer who is told "your order is placed" has been promised goods,
     * and that promise must not rest on a message still in flight. If inventory will not hold the
     * stock, no order is created and the caller is told why; there is no pending order to explain
     * away afterwards.
     *
     * The id is minted here rather than by the aggregate so that it can be the reservation's
     * reference before there is anything to reference. That is also what makes the compensation
     * below possible: the hold is already addressable by the id of an order that does not exist yet.
     *
     * [priceItems] has checked the same things inventory is about to - the products exist, are
     * still sold, had enough on the shelf a moment ago - and that is not redundant. It prices the
     * lines, which is needed either way, and it fails per line with a message naming the product,
     * where inventory's refusal is about the request as a whole. Inventory remains the one that
     * decides, because only it decides atomically.
     */
    fun createOrder(name: String, surname: String, customerId: String, items: List<OrderItemRequest>): OrderView {
        val orderId = OrderId.random()
        val pricedItems = inventoryCatalog.priceItems(items)

        inventoryCatalog.reserve(orderId.value, totalPerProduct(pricedItems))
        try {
            commandGateway.sendAndWait<Any>(
                CreateOrderCommand(
                    orderId = orderId,
                    customer = CustomerName(name, surname),
                    customerId = customerId,
                    items = pricedItems
                )
            )
        } catch (failure: RuntimeException) {
            // The order was not created, so nothing will ever release this hold - no cancellation
            // can be sent for an order that does not exist. Giving it back here is the only chance.
            inventoryCatalog.releaseQuietly(orderId.value)
            throw failure
        }
        return orderViewReadService.findById(orderId)
    }

    /**
     * Replaces the order's lines, moving the hold behind them in one operation.
     *
     * Inventory is asked to *amend* the reservation rather than to drop it and take a new one, and
     * that is the whole difference. It works out for itself which lines went up, which came down,
     * which are new and which are gone, and applies all of it or none of it - so the order never
     * stands holding nothing while its new lines are judged, and stock it is simply keeping cannot
     * be taken by another order in between. Only an increase is contested, which is the only part
     * anyone else has a claim on.
     *
     * There is nothing to compensate here any more. The release-then-reserve this replaces could
     * fail with the old hold already given up, which is why it needed to put the previous
     * reservation back, and why that restoration could itself fail and leave an order backed by
     * nothing. A refused amendment changes nothing: inventory unwinds its own half-applied work
     * before answering, and the order stands exactly as it was.
     */
    fun updateOrderItems(orderId: OrderId, items: List<OrderItemRequest>): OrderView {
        // Priced against what this order is already holding: its own goods are not free stock, so
        // judging the new lines without them would refuse an amendment the shelf can plainly cover.
        val held = inventoryCatalog.findReservation(orderId.value)
        val pricedItems = inventoryCatalog.priceItemsForAmendment(items, held)

        inventoryCatalog.amend(orderId.value, totalPerProduct(pricedItems))
        commandGateway.sendAndWait<Any>(UpdateOrderItemsCommand(orderId = orderId, items = pricedItems))
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
