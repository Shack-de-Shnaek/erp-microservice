package finki.ukim.erp.orders.dto

import finki.ukim.erp.orders.PaymentType
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DtoValidationTest {

    companion object {
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setUpValidator() {
            validator = Validation.buildDefaultValidatorFactory().validator
        }
    }

    @Test
    fun `CreateOrderRequest is valid with a blank name`() {
        val request = CreateOrderRequest(name = "", surname = "Doe", items = listOf(OrderItemRequest(1L, 1)))
        assertTrue(validator.validate(request).isNotEmpty())
    }

    @Test
    fun `CreateOrderRequest requires at least one item`() {
        val request = CreateOrderRequest(name = "John", surname = "Doe", items = emptyList())
        assertTrue(validator.validate(request).isNotEmpty())
    }

    @Test
    fun `CreateOrderRequest is valid when all fields are populated correctly`() {
        val request = CreateOrderRequest(name = "John", surname = "Doe", items = listOf(OrderItemRequest(1L, 1)))
        assertEquals(0, validator.validate(request).size)
    }

    @Test
    fun `OrderItemRequest rejects a zero or negative quantity`() {
        assertTrue(validator.validate(OrderItemRequest(1L, 0)).isNotEmpty())
        assertTrue(validator.validate(OrderItemRequest(1L, -3)).isNotEmpty())
    }

    @Test
    fun `OrderItemRequest rejects a non-positive productId`() {
        assertTrue(validator.validate(OrderItemRequest(0L, 1)).isNotEmpty())
    }

    @Test
    fun `UpdateOrderItemsRequest requires at least one item`() {
        assertTrue(validator.validate(UpdateOrderItemsRequest(items = emptyList())).isNotEmpty())
    }

    @Test
    fun `CreatePaymentRequest rejects an amount of zero`() {
        assertTrue(validator.validate(CreatePaymentRequest(BigDecimal.ZERO, PaymentType.CASH)).isNotEmpty())
    }

    @Test
    fun `CreatePaymentRequest accepts a positive amount`() {
        assertEquals(0, validator.validate(CreatePaymentRequest(BigDecimal("10.00"), PaymentType.CASH)).size)
    }

    @Test
    fun `GenerateInvoiceRequest rejects an embg that is not exactly 13 digits`() {
        assertTrue(validator.validate(GenerateInvoiceRequest(embg = "123")).isNotEmpty())
        assertTrue(validator.validate(GenerateInvoiceRequest(embg = "abcdefghijklm")).isNotEmpty())
    }

    @Test
    fun `GenerateInvoiceRequest accepts a 13 digit embg`() {
        assertEquals(0, validator.validate(GenerateInvoiceRequest(embg = "1234567890123")).size)
    }

    @Test
    fun `InvoiceLineItemRequest rejects a negative price`() {
        assertTrue(
            validator.validate(InvoiceLineItemRequest(inventoryItemId = 1L, quantity = 1, price = BigDecimal("-1.00")))
                .isNotEmpty()
        )
    }

    @Test
    fun `UpdateInvoiceLineItemsRequest requires at least one line item`() {
        assertTrue(validator.validate(UpdateInvoiceLineItemsRequest(items = emptyList())).isNotEmpty())
    }
}
