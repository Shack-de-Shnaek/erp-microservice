package finki.ukim.erp.orders.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class GenerateInvoiceRequest(
    @field:Pattern(regexp = "\\d{13}", message = "embg must be exactly 13 digits")
    val embg: String
)

data class InvoiceLineItemRequest(
    @field:Positive(message = "inventoryItemId must be positive")
    val inventoryItemId: Long,

    @field:Min(value = 1, message = "quantity must be at least 1")
    val quantity: Int,

    @field:NotNull(message = "price is required")
    @field:DecimalMin(value = "0.00", inclusive = true, message = "price must not be negative")
    val price: BigDecimal
)

data class UpdateInvoiceLineItemsRequest(
    @field:NotEmpty(message = "an invoice must have at least one line item")
    @field:Valid
    val items: List<InvoiceLineItemRequest>
)
