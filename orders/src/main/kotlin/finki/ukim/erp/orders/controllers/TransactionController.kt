package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.dto.CreatePaymentRequest
import finki.ukim.erp.orders.services.OrderService
import finki.ukim.erp.orders.services.TransactionService
import finki.ukim.erp.orders.views.TransactionView
import finki.ukim.erp.orders.views.toView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders/{orderId}/payments")
class TransactionController(
    private val transactionService: TransactionService,
    private val orderService: OrderService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(@PathVariable orderId: Long, @Valid @RequestBody request: CreatePaymentRequest): TransactionView {
        val order = orderService.findOrderOrThrow(orderId)
        return transactionService.createPayment(order, request.amount, request.paymentType).toView()
    }
}
