package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.dto.GenerateInvoiceRequest
import finki.ukim.erp.orders.dto.UpdateInvoiceLineItemsRequest
import finki.ukim.erp.orders.services.InvoiceService
import finki.ukim.erp.orders.views.InvoiceView
import finki.ukim.erp.orders.views.toView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class InvoiceController(
    private val invoiceService: InvoiceService
) {

    @PostMapping("/orders/{orderId}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateInvoice(@PathVariable orderId: Long, @Valid @RequestBody request: GenerateInvoiceRequest): InvoiceView =
        invoiceService.generateInvoice(orderId, request.embg).toView()

    @PutMapping("/invoices/{id}/line-items")
    fun updateLineItems(@PathVariable id: Long, @Valid @RequestBody request: UpdateInvoiceLineItemsRequest): InvoiceView =
        invoiceService.updateInvoiceLineItems(id, request.items).toView()

    @PostMapping("/invoices/{id}/reverse")
    fun reverseInvoice(@PathVariable id: Long): InvoiceView = invoiceService.reverseInvoice(id).toView()
}
