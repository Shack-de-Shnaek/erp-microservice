package finki.ukim.erp.orders.domain

import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Embedded
import jakarta.persistence.OneToMany
import jakarta.persistence.CascadeType
import java.util.UUID

@Embeddable
data class OrderId(
    val value: String = UUID.randomUUID().toString(),
) {
    companion object {
        fun generate() = OrderId()
        fun fromString(id: String) = OrderId(id)
    }
}

@Embeddable
data class ProductRef(
    val value: String,
)

@Embeddable
data class Quantity(
    val amount: Int,
) {
    init {
        require(amount >= 0) { "Quantity must be non-negative" }
    }
}

@Entity
@Table(name = "orders")
data class Order(
    @EmbeddedId
    val orderId: OrderId = OrderId.generate(),
    val status: OrderStatus = OrderStatus.PENDING,
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    val lines: MutableList<OrderLine> = mutableListOf(),
)

@Entity
@Table(name = "order_lines")
data class OrderLine(
    @jakarta.persistence.Id
    val id: String = UUID.randomUUID().toString(),
    @Embedded
    val productId: ProductRef,
    @Embedded
    val quantity: Quantity,
)

enum class OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
}
