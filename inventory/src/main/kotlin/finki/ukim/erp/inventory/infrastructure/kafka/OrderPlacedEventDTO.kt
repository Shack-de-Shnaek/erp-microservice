package finki.ukim.erp.inventory.infrastructure.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderPlacedEventDTO(
    val orderId: String,
    val lines: List<OrderLineDTO> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderLineDTO(
    val productId: String,
    val quantity: Int,
)