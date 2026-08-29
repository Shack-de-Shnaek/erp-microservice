package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.dto.CreateOrderRequest
import finki.ukim.erp.orders.dto.UpdateOrderItemsRequest
import finki.ukim.erp.orders.services.OrderService
import finki.ukim.erp.orders.views.OrderView
import finki.ukim.erp.orders.views.toView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrder(@Valid @RequestBody request: CreateOrderRequest, @AuthenticationPrincipal jwt: Jwt): OrderView =
        orderService.createOrder(request.name, request.surname, jwt.subject, request.items).toView()

    @PutMapping("/{id}/items")
    fun updateOrderItems(@PathVariable id: Long, @Valid @RequestBody request: UpdateOrderItemsRequest): OrderView =
        orderService.updateOrderItems(id, request.items).toView()

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    fun approveOrder(@PathVariable id: Long): OrderView = orderService.approveOrder(id).toView()

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    fun rejectOrder(@PathVariable id: Long): OrderView = orderService.rejectOrder(id).toView()

    @PostMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: Long, @AuthenticationPrincipal jwt: Jwt): OrderView =
        orderService.cancelOrder(id, jwt.subject).toView()
}
