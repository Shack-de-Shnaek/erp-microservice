package finki.ukim.erp.orders.web

import finki.ukim.erp.orders.domain.Order
import finki.ukim.erp.orders.domain.OrderId
import finki.ukim.erp.orders.domain.OrderLine
import finki.ukim.erp.orders.domain.OrderRepository
import finki.ukim.erp.orders.domain.ProductRef
import finki.ukim.erp.orders.domain.Quantity
import finki.ukim.erp.orders.infrastructure.OrderEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderRepository: OrderRepository,
    private val orderEventPublisher: OrderEventPublisher,
) {

    @PostMapping
    fun create(@RequestBody request: CreateOrderRequest): ResponseEntity<OrderResponse> {
        val order = Order(
            orderId = OrderId.generate(),
            lines = request.lines.map { line ->
                OrderLine(
                    productId = ProductRef(line.productId),
                    quantity = Quantity(line.quantity),
                )
            }.toMutableList(),
        )
        val saved = orderRepository.save(order)
        orderEventPublisher.publishOrderPlaced(saved)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @GetMapping
    fun findAll(): List<OrderResponse> =
        orderRepository.findAll().map { it.toResponse() }

    @GetMapping("/{orderId}")
    fun findById(@PathVariable orderId: String): ResponseEntity<OrderResponse> {
        val order = orderRepository.findById(OrderId.fromString(orderId))
        return order.map { ResponseEntity.ok(it.toResponse()) }
            .orElse(ResponseEntity.notFound().build())
    }

    private fun Order.toResponse() = OrderResponse(
        orderId = orderId.value,
        status = status.name,
        lines = lines.map { line ->
            OrderLineResponse(
                productId = line.productId.value,
                quantity = line.quantity.amount,
            )
        },
    )
}

data class CreateOrderRequest(
    val lines: List<OrderLineRequest>,
)

data class OrderLineRequest(
    val productId: String,
    val quantity: Int,
)

data class OrderResponse(
    val orderId: String,
    val status: String,
    val lines: List<OrderLineResponse>,
)

data class OrderLineResponse(
    val productId: String,
    val quantity: Int,
)
