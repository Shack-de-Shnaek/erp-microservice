package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.dto.CreatePaymentRequest
import finki.ukim.erp.orders.services.OrderCommandService
import finki.ukim.erp.orders.views.TransactionView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Payments are transactions on the order, not an aggregate of their own, so they are addressed
 * under the order that they pay for.
 */
@RestController
@RequestMapping("/orders/{orderId}/payments")
@Tag(
    name = "Payments",
    description = "Money taken against an order. Payments accumulate until the order is paid off, " +
        "which is what allows it to be invoiced; refunds are recorded as reversal transactions by " +
        "cancelling the order or reversing its invoice."
)
class TransactionController(
    private val orderCommandService: OrderCommandService
) {

    @Operation(
        summary = "Register a payment against an order",
        description = "Records money received for the order - the amount and how it was paid (CARD, " +
            "CASH, ...). The order cannot be paid beyond its total; an attempt to do so is rejected " +
            "rather than partially accepted."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(
        @PathVariable orderId: String,
        @Valid @RequestBody request: CreatePaymentRequest
    ): TransactionView = orderCommandService.registerPayment(OrderId(orderId), request.amount, request.paymentType)
}
