package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.commands.GenerateInvoiceCommand
import finki.ukim.erp.orders.commands.PricedItem
import finki.ukim.erp.orders.commands.UpdateInvoiceLineItemsCommand
import java.time.LocalDateTime

data class InvoiceLineItemEventData(
    val inventoryItemId: ProductId,
    val quantity: Quantity,
    val price: Money
) {
    constructor(item: PricedItem) : this(item.productId, item.quantity, item.price)

    constructor(item: OrderItem) : this(item.productId, item.quantity, item.price)

    fun lineTotal(): Money = price * quantity
}

/**
 * Published: the goods have been billed, which is the point the sale is final.
 *
 * The external version carries the invoice number, because that is what a customer quotes when
 * they call about a document. It does not carry the [embg] - the citizen identification number the
 * invoice was issued to is personal data that exists here for one reason, printing a valid tax
 * document, and it has no business on a topic other services read.
 */
data class InvoiceGeneratedEvent(
    val invoiceId: InvoiceId,
    override val orderId: OrderId,
    val invoiceNumber: InvoiceNumber,
    val embg: Embg,
    val items: List<InvoiceLineItemEventData>,
    val totalAmount: Money,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    /**
     * The invoice is issued against the order's items as they stand right now, which is why this
     * takes them alongside the command - the command carries only what the caller chose.
     */
    constructor(command: GenerateInvoiceCommand, orderItems: List<OrderItem>) : this(
        invoiceId = command.invoiceId,
        orderId = command.orderId,
        invoiceNumber = command.invoiceNumber,
        embg = command.embg,
        items = orderItems.map { InvoiceLineItemEventData(it) },
        totalAmount = Money.sum(orderItems.map { it.lineTotal() })
    )

    override fun toExternalEvent() = InvoiceGeneratedExternalEvent(
        orderId = orderId,
        invoiceId = invoiceId,
        invoiceNumber = invoiceNumber.value,
        totalAmount = totalAmount,
        issuedAt = occurredAt
    )
}

/**
 * Internal. Correcting the lines on an invoice is bookkeeping within this service; the sale itself
 * has not changed, and republishing it would tell consumers something happened that did not.
 */
data class InvoiceLineItemsUpdatedEvent(
    val invoiceId: InvoiceId,
    override val orderId: OrderId,
    val items: List<InvoiceLineItemEventData>,
    val totalAmount: Money,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    constructor(command: UpdateInvoiceLineItemsCommand, invoiceId: InvoiceId) : this(
        invoiceId = invoiceId,
        orderId = command.orderId,
        items = command.items.map { InvoiceLineItemEventData(it) },
        totalAmount = Money.sum(command.items.map { it.lineTotal() })
    )
}

/** Published: the sale is undone, so whatever was committed against it is no longer. */
data class InvoiceReversedEvent(
    val invoiceId: InvoiceId,
    override val orderId: OrderId,
    val refundedAmount: Money,
    override val occurredAt: LocalDateTime = LocalDateTime.now()
) : OrderEvent(orderId, occurredAt) {

    override fun toExternalEvent() = InvoiceReversedExternalEvent(
        orderId = orderId,
        invoiceId = invoiceId,
        refundedAmount = refundedAmount,
        reversedAt = occurredAt
    )

    /**
     * A refund voids the order as surely as a cancellation does, so it says so on the same topic.
     * No lines: the invoice event does not carry them, and a consumer releasing what it held for
     * this order works from its own record of the order, not from this message.
     */
    override fun toExternalEvents() =
        super.toExternalEvents() + nullification(NullificationReason.REFUNDED)
}
