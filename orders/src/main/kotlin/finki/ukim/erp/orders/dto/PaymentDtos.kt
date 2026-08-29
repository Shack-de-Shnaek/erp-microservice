package finki.ukim.erp.orders.dto

import finki.ukim.erp.orders.PaymentType
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull

data class CreatePaymentRequest(
    @field:NotNull(message = "amount is required")
    @field:DecimalMin(value = "0.01", message = "amount must be greater than zero")
    val amount: java.math.BigDecimal,

    @field:NotNull(message = "paymentType is required")
    val paymentType: PaymentType
)
