package finki.ukim.erp.orders.commands

import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.TransactionId
import org.axonframework.modelling.command.TargetAggregateIdentifier

/**
 * The write side. Every state change goes through one of these, routed to
 * [finki.ukim.erp.orders.Order].
 *
 * `@TargetAggregateIdentifier` is what tells Axon which order a command is about: it reads that
 * field, hands its string form to the aggregate's repository, and loads that row. Creation is the
 * one case without it - there is nothing to load yet, and the identifier is minted on the command
 * itself.
 *
 * Anything non-deterministic or needing a lookup elsewhere - prices, invoice numbers, identifiers
 * - is resolved before dispatch by [finki.ukim.erp.orders.services.OrderCommandService] and
 * carried here, so the aggregate never has to reach outside itself to handle a command.
 */
data class PricedItem(
    val productId: ProductId,
    val quantity: Quantity,
    val price: Money
) {
    fun lineTotal(): Money = price * quantity
}

data class CreateOrderCommand(
    val orderId: OrderId = OrderId.random(),
    val customer: CustomerName,
    val customerId: String,
    val items: List<PricedItem>
)

data class UpdateOrderItemsCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val items: List<PricedItem>
)

data class ApproveOrderCommand(
    @TargetAggregateIdentifier val orderId: OrderId
)

data class RejectOrderCommand(
    @TargetAggregateIdentifier val orderId: OrderId
)

/**
 * Inventory confirmed this order's goods out, which is what commits the order.
 *
 * Separate from [ApproveOrderCommand] because the two prove the same thing by opposite means.
 * An administrator's approval has to go and check that the hold is still standing, which is why
 * [finki.ukim.erp.orders.handlers.ApproveOrderCommandHandler] owns it and reaches for inventory.
 * This one arrives *from* inventory, saying the goods have already gone out - there is nothing left
 * to verify, and verifying it would fail, because a confirmed hold is no longer a hold.
 */
data class ApproveOrderForConfirmedStockCommand(
    @TargetAggregateIdentifier val orderId: OrderId
)

/**
 * Inventory took this order's goods back, so the order cannot stand.
 *
 * Separate from [RejectOrderCommand] because the two are not the same decision and must not share
 * a rule. An administrator rejecting an order is refusing something still pending; this arrives
 * after the fact, about an order that may well have been approved on stock that has since been
 * withdrawn, and refusing it for not being pending would leave an approved order backed by nothing.
 *
 * It carries no reversal transaction on purpose. An order with money against it is exactly the one
 * this may not close - see [finki.ukim.erp.orders.Order.rejectForWithdrawnStock].
 */
data class RejectOrderForWithdrawnStockCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    /** Inventory's own words, carried into the log so the refusal can be traced back. */
    val detail: String
)

data class CancelOrderCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val customerId: String,
    val reversalTransactionId: TransactionId = TransactionId.random()
)

data class RegisterPaymentCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val amount: Money,
    val paymentType: PaymentType,
    val transactionId: TransactionId = TransactionId.random()
)

data class GenerateInvoiceCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val embg: Embg,
    val invoiceId: InvoiceId = InvoiceId.random(),
    val invoiceNumber: InvoiceNumber
)

data class UpdateInvoiceLineItemsCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val items: List<PricedItem>
)

data class ReverseInvoiceCommand(
    @TargetAggregateIdentifier val orderId: OrderId,
    val reversalTransactionId: TransactionId = TransactionId.random()
)
