package finki.ukim.erp.orders.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

data class OrderItemRequest(
    @field:Positive(message = "productId must be positive")
    val productId: Long,

    @field:Min(value = 1, message = "quantity must be at least 1")
    val quantity: Int
)

data class CreateOrderRequest(
    @field:NotBlank(message = "name must not be blank")
    val name: String,

    @field:NotBlank(message = "surname must not be blank")
    val surname: String,

    @field:NotEmpty(message = "an order must have at least one item")
    @field:Valid
    val items: List<OrderItemRequest>
)

data class UpdateOrderItemsRequest(
    @field:NotEmpty(message = "an order must have at least one item")
    @field:Valid
    val items: List<OrderItemRequest>
)
