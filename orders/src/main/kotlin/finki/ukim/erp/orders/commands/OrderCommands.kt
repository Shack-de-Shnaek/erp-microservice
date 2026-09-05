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
