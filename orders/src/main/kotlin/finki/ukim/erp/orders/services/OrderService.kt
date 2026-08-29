package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCancelledEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderEventPublisher
import finki.ukim.erp.orders.events.OrderItemEventData
import finki.ukim.erp.orders.events.OrderItemsUpdatedEvent
import finki.ukim.erp.orders.events.OrderRejectedEvent
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.exceptions.OrderNotOwnedException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import finki.ukim.erp.orders.repositories.OrderRepository
import finki.ukim.erp.orders.util.total
import finki.ukim.erp.orders.util.verifyStockAvailable
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val transactionService: TransactionService,
    private val orderEventPublisher: OrderEventPublisher
) {

    fun findOrderOrThrow(orderId: Long): Order = getOrderOrThrow(orderId)

    fun createOrder(name: String, surname: String, customerId: String, items: List<OrderItemRequest>): Order {
        val order = Order(name = name, surname = surname, customerId = customerId, status = OrderStatus.PENDING)
        items.forEach { order.addOrderItem(toOrderItem(it)) }
        val saved = orderRepository.save(order)

        orderEventPublisher.publish(
            OrderCreatedEvent(
                orderId = saved.id!!,
                customerId = saved.customerId,
                name = saved.name,
                surname = saved.surname,
                items = saved.toEventItems(),
                totalAmount = saved.total()
            )
        )
        return saved
    }

    fun updateOrderItems(orderId: Long, items: List<OrderItemRequest>): Order {
        val order = getOrderOrThrow(orderId)
        requireEditable(order)

        val requestedByProduct = items.associateBy { it.productId }
        val existingByProduct = order.orderItems.associateBy { it.productId }

        val toRemove = order.orderItems.filter { it.productId !in requestedByProduct.keys }
        toRemove.forEach { order.removeOrderItem(it) }

        items.forEach { request ->
            val existing = existingByProduct[request.productId]
            if (existing == null) {
                order.addOrderItem(toOrderItem(request))
            } else if (existing.quantity != request.quantity) {
                requireProductExists(request.productId)
                if (!inventoryClient.isStockAvailable(request.productId, request.quantity)) {
                    throw InsufficientStockException(request.productId, request.quantity)
                }
                existing.quantity = request.quantity
                existing.price = inventoryClient.getPrice(request.productId)
            }
        }

        val saved = orderRepository.save(order)
        orderEventPublisher.publish(
            OrderItemsUpdatedEvent(orderId = saved.id!!, items = saved.toEventItems(), totalAmount = saved.total())
        )
        return saved
    }

    fun approveOrder(orderId: Long): Order {
        val order = getOrderOrThrow(orderId)
        if (order.status != OrderStatus.PENDING) {
            throw InvalidOrderStateException("Only a pending order can be approved")
        }
        order.orderItems.verifyStockAvailable(inventoryClient)
        order.status = OrderStatus.APPROVED
        val saved = orderRepository.save(order)
        orderEventPublisher.publish(OrderApprovedEvent(orderId = saved.id!!))
        return saved
    }

    fun rejectOrder(orderId: Long): Order {
        val order = getOrderOrThrow(orderId)
        if (order.status != OrderStatus.PENDING) {
            throw InvalidOrderStateException("Only a pending order can be rejected")
        }
        order.status = OrderStatus.REJECTED
        val saved = orderRepository.save(order)
        orderEventPublisher.publish(OrderRejectedEvent(orderId = saved.id!!))
        return saved
    }

    fun cancelOrder(orderId: Long, customerId: String): Order {
        val order = getOrderOrThrow(orderId)
        if (order.customerId != customerId) {
            throw OrderNotOwnedException(orderId)
        }
        if (order.status != OrderStatus.PENDING && order.status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only a pending or approved order can be cancelled")
        }

        var refundedAmount = BigDecimal.ZERO
        if (order.status == OrderStatus.APPROVED) {
            val totalPaid = transactionService.getTotalPaid(order)
            if (totalPaid > BigDecimal.ZERO) {
                transactionService.reverse(order, totalPaid)
                refundedAmount = totalPaid
            }
        }

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)
        orderEventPublisher.publish(OrderCancelledEvent(orderId = saved.id!!, refundedAmount = refundedAmount))
        return saved
    }

    private fun requireEditable(order: Order) {
        if (order.invoice != null) {
            throw InvalidOrderStateException("Order ${order.id} already has an invoice and can no longer be edited")
        }
        if (order.status != OrderStatus.PENDING && order.status != OrderStatus.APPROVED) {
            throw InvalidOrderStateException("Only a pending or approved order can be edited")
        }
    }

    private fun toOrderItem(request: OrderItemRequest): OrderItem {
        requireProductExists(request.productId)
        if (!inventoryClient.isStockAvailable(request.productId, request.quantity)) {
            throw InsufficientStockException(request.productId, request.quantity)
        }
        return OrderItem(
            productId = request.productId,
            quantity = request.quantity,
            price = inventoryClient.getPrice(request.productId)
        )
    }

    private fun requireProductExists(productId: Long) {
        if (!inventoryClient.productExists(productId)) {
            throw ProductNotFoundException(productId)
        }
    }

    private fun getOrderOrThrow(orderId: Long): Order =
        orderRepository.findById(orderId).orElseThrow { OrderNotFoundException(orderId) }

    private fun Order.toEventItems(): List<OrderItemEventData> =
        orderItems.map { OrderItemEventData(it.productId, it.quantity, it.price) }
}
