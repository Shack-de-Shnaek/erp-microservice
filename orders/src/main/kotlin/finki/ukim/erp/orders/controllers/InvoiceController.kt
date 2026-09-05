package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.dto.GenerateInvoiceRequest
import finki.ukim.erp.orders.dto.UpdateInvoiceLineItemsRequest
import finki.ukim.erp.orders.queries.FindInvoiceByIdQuery
import finki.ukim.erp.orders.queries.queryForOne
import finki.ukim.erp.orders.services.OrderCommandService
import finki.ukim.erp.orders.views.InvoiceView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.axonframework.queryhandling.QueryGateway
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The invoice is not an aggregate of its own: it lives inside [finki.ukim.erp.orders.Order], which
 * is why issuing one is addressed under the order and everything afterwards is addressed by the
 * invoice's own id.
 */
@RestController
@Tag(
    name = "Invoices",
    description = "Issuing, amending and reversing the invoice attached to an order. An invoice is a " +
        "snapshot of the order's items at the moment it was issued - editing one never moves the other."
)
class InvoiceController(
    private val orderCommandService: OrderCommandService,
    private val queryGateway: QueryGateway
) {

    @Operation(
        summary = "Fetch an invoice",
        description = "Looks the invoice up by its own id. Responds 404 when no invoice with that id " +
            "has been issued."
    )
    @GetMapping("/invoices/{id}")
    fun getInvoice(@PathVariable id: String): InvoiceView =
        queryGateway.queryForOne(FindInvoiceByIdQuery(InvoiceId(id)), InvoiceView::class.java)

    @Operation(
        summary = "Issue the invoice for an order",
        description = "Re-checks stock, then issues the single invoice an order may have. The order must " +
            "be approved and fully paid off, and the customer's EMBG (13 digits) is required. The EMBG is " +
            "held on the invoice and never leaves this service on an event."
    )
    @PostMapping("/orders/{orderId}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateInvoice(
        @PathVariable orderId: String,
        @Valid @RequestBody request: GenerateInvoiceRequest
    ): InvoiceView = orderCommandService.generateInvoice(OrderId(orderId), request.embg)

    @Operation(
        summary = "Correct an invoice's line items",
        description = "Replaces the invoice's lines - for a billing correction after issue. The order's " +
            "own items are untouched, and a reversed invoice can no longer be modified."
    )
    @PutMapping("/invoices/{id}/line-items")
    fun updateLineItems(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateInvoiceLineItemsRequest
    ): InvoiceView = orderCommandService.updateInvoiceLineItems(InvoiceId(id), request.items)

    @Operation(
        summary = "Reverse an invoice",
        description = "Undoes the sale: refunds the invoice total as a reversal transaction and announces " +
            "invoice.reversed. An invoice can only be reversed once."
    )
    @PostMapping("/invoices/{id}/reverse")
    fun reverseInvoice(@PathVariable id: String): InvoiceView =
        orderCommandService.reverseInvoice(InvoiceId(id))
}
