package finki.ukim.erp.orders.events

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import java.time.LocalDateTime

/**
 * What orders promises the rest of the system.
 *
 * These are separate classes from the internal events on purpose. An internal event is free to
 * gain a field, lose one, or be renamed as the domain moves; the moment another service is reading
 * it, none of that is free any more. Keeping the two apart means the published shape only changes
 * when somebody edits a file in this one, which is a decision rather than an accident.
 *
 * What is *left out* is as deliberate as what is in. The customer's name and Keycloak subject, the
 * EMBG on an invoice, individual payments and their types - none of that is anyone else's
 * business, and publishing personal data because it happened to be on the internal event is how it
 * ends up somewhere it should not be.
 */
data class OrderLine(
    val productId: ProductId,
    val quantity: Quantity
)

/**
 * A new order exists. Consumed by anything that wants to know demand is being placed; carries no
 * commitment, since a pending order may still be rejected.
 */
data class OrderCreatedExternalEvent(
    val orderId: OrderId,
    val lines: List<OrderLine>,
    val totalAmount: Money,
    val createdAt: LocalDateTime
)

/**
 * The order is going ahead. This is the one inventory acts on: approval is the point at which
 * goods are committed to a customer, so it carries the lines to reserve.
 */
data class OrderApprovedExternalEvent(
    val orderId: OrderId,
    val lines: List<OrderLine>,
    val approvedAt: LocalDateTime
)

/** The order is off. Whatever was reserved for it can go back. */
data class OrderCancelledExternalEvent(
    val orderId: OrderId,
    val lines: List<OrderLine>,
    val cancelledAt: LocalDateTime
)

/**
 * The goods have been billed. The invoice number is here because it is what a customer quotes;
 * the EMBG it was issued to is not, and never will be.
 */
data class InvoiceGeneratedExternalEvent(
    val orderId: OrderId,
    val invoiceId: InvoiceId,
    val invoiceNumber: String,
    val totalAmount: Money,
    val issuedAt: LocalDateTime
)

/** The invoice was reversed - the sale is undone, and the goods are no longer committed. */
data class InvoiceReversedExternalEvent(
    val orderId: OrderId,
    val invoiceId: InvoiceId,
    val refundedAmount: Money,
    val reversedAt: LocalDateTime
)

/**
 * The topic every nullification lands on.
 *
 * A constant rather than a derivation from a class name, because there is no `OrderNullifiedEvent`
 * to derive it from - nullification is not something that happens to an order, it is what three
 * different things that happen to an order all mean. The name still follows the same convention
 * everything else on the bus does.
 */
const val ORDER_NULLIFIED_TOPIC = "order.nullified"

/** Why an order stopped being an order. */
enum class NullificationReason {
    /** The customer or the business called it off. */
    CANCELLED,

    /** It was never going to be fulfillable - stock ran out, or a product was withdrawn. */
    REJECTED,

    /** It was billed and then unbilled: the invoice was reversed and the money went back. */
    REFUNDED
}

/**
 * This order is void. Whatever you were holding for it, let it go.
 *
 * The single most useful event this service publishes to anyone downstream, and the reason it
 * exists separately from `order.cancelled` / `invoice.reversed`: a consumer that reserves stock,
 * holds a delivery slot or blocks a credit line does not care *why* an order died, only that it
 * did. Without this it would have to subscribe to every ending an order can have, know that
 * rejection is one of them, and be changed again the next time a new one is added. With it, there
 * is one topic and one rule.
 *
 * [reason] is carried anyway, for consumers that do want to distinguish - a refund reverses
 * something that was already fulfilled, a rejection never got that far - but nothing is required
 * to read it. [lines] is what the order was for, where the event that produced it knew: a rejection
 * carries none, because a rejected order never reserved anything. A consumer that keeps its own
 * record against [orderId] should use that record rather than these lines, which is what the
 * inventory service does.
 */
data class OrderNullifiedExternalEvent(
    val orderId: OrderId,
    val reason: NullificationReason,
    val lines: List<OrderLine>,
    val nullifiedAt: LocalDateTime
)
