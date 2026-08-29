package finki.ukim.erp.orders.views

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.util.total
import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderItemView(
    val id: Long?,
    val productId: Long,
    val quantity: Int,
    val price: BigDecimal
)

data class OrderView(
    val id: Long?,
    val name: String,
    val surname: String,
    val customerId: String,
    val status: OrderStatus,
    val date: LocalDateTime,
    val items: List<OrderItemView>,
    val totalAmount: BigDecimal,
    val hasInvoice: Boolean
)

fun Order.toView(): OrderView = OrderView(
    id = id,
    name = name,
    surname = surname,
    customerId = customerId,
    status = status,
    date = date,
    items = orderItems.map { OrderItemView(it.id, it.productId, it.quantity, it.price) },
    totalAmount = total(),
    hasInvoice = invoice != null
)
