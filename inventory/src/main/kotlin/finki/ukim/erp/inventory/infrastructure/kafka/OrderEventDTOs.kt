package finki.ukim.erp.inventory.infrastructure.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * The shapes the orders service actually publishes, as understood here.
 *
 * These belong to inventory, not to orders: importing the other service's event classes would look
 * like less code and would hand another team the ability to break this one by renaming a field.
 * `ignoreUnknown = true` so orders can add fields freely; anything inventory genuinely needs is
 * non-null, so a message missing it fails loudly instead of producing a command with a hole in it.
 *
 * The identifiers are strings because that is what arrives. Orders serializes its `OrderId` as the
 * prefixed string `"Order:<uuid>"` and its `ProductId` as inventory's own product id - so the
 * `orderRef` inventory reserves under and the one it later releases under are the same token, and
 * `productId` can be looked up in the stock ledger without translation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderApprovedEventDTO(
    val orderId: String,
    val lines: List<OrderLineDTO> = emptyList(),
    val approvedAt: String? = null,
)

/**
 * An order is void. Published by orders on cancellation, rejection and invoice reversal alike, so
 * this is the only message inventory needs in order to let go of what it was holding.
 *
 * [lines] is present when the event that produced it knew them and empty otherwise - a rejected
 * order never reserved anything, and a refund is announced by the invoice, which does not carry
 * lines. Inventory does not read them: it releases against its own reservation record for
 * [orderId], which is the only account of what was actually reserved.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderNullifiedEventDTO(
    val orderId: String,
    val reason: String? = null,
    val lines: List<OrderLineDTO> = emptyList(),
    val nullifiedAt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderLineDTO(
    val productId: String,
    val quantity: Int,
)
