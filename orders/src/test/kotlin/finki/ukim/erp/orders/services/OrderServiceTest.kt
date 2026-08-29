package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.Invoice
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.dto.OrderItemRequest
import finki.ukim.erp.orders.events.OrderApprovedEvent
import finki.ukim.erp.orders.events.OrderCancelledEvent
import finki.ukim.erp.orders.events.OrderCreatedEvent
import finki.ukim.erp.orders.events.OrderEventPublisher
import finki.ukim.erp.orders.events.OrderItemsUpdatedEvent
import finki.ukim.erp.orders.events.OrderRejectedEvent
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.exceptions.OrderNotOwnedException
import finki.ukim.erp.orders.exceptions.ProductNotFoundException
import finki.ukim.erp.orders.repositories.OrderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class OrderServiceTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryClient: InventoryClient

    @Mock
    lateinit var transactionService: TransactionService

    @Mock
    lateinit var orderEventPublisher: OrderEventPublisher

    lateinit var orderService: OrderService

    @BeforeEach
    fun setUp() {
        orderService = OrderService(orderRepository, inventoryClient, transactionService, orderEventPublisher)
        given(orderRepository.save(any(Order::class.java))).thenAnswer {
            val order = it.arguments[0] as Order
            if (order.id == null) order.id = 1L
            order
        }
    }

    @Test
    fun `createOrder computes price and stores items when stock is available`() {
        given(inventoryClient.productExists(1L)).willReturn(true)
        given(inventoryClient.isStockAvailable(1L, 2)).willReturn(true)
        given(inventoryClient.getPrice(1L)).willReturn(BigDecimal("10.00"))

        val order = orderService.createOrder("John", "Doe", "customer-1", listOf(OrderItemRequest(1L, 2)))

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals("customer-1", order.customerId)
        assertEquals(1, order.orderItems.size)
        assertEquals(BigDecimal("10.00"), order.orderItems[0].price)
        verify(orderEventPublisher).publish(any(OrderCreatedEvent::class.java))
    }

    @Test
    fun `createOrder throws when product does not exist`() {
        given(inventoryClient.productExists(1L)).willReturn(false)

        assertThrows(ProductNotFoundException::class.java) {
            orderService.createOrder("John", "Doe", "customer-1", listOf(OrderItemRequest(1L, 5)))
        }
    }

    @Test
    fun `createOrder throws when stock is insufficient`() {
        given(inventoryClient.productExists(1L)).willReturn(true)
        given(inventoryClient.isStockAvailable(1L, 5)).willReturn(false)

        assertThrows(InsufficientStockException::class.java) {
            orderService.createOrder("John", "Doe", "customer-1", listOf(OrderItemRequest(1L, 5)))
        }
    }

    @Test
    fun `updateOrderItems throws when order already has an invoice`() {
        val order = pendingOrder("customer-1")
        order.invoice = Invoice(id = 99L, order = order)
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) {
            orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 1)))
        }
    }

    @Test
    fun `updateOrderItems throws when order is cancelled`() {
        val order = pendingOrder("customer-1")
        order.status = OrderStatus.CANCELLED
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) {
            orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 1)))
        }
    }

    @Test
    fun `updateOrderItems checks stock only for increased quantities and refreshes price`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.productExists(1L)).willReturn(true)
        given(inventoryClient.isStockAvailable(1L, 5)).willReturn(true)
        given(inventoryClient.getPrice(1L)).willReturn(BigDecimal("12.00"))

        val updated = orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 5)))

        assertEquals(5, updated.orderItems[0].quantity)
        assertEquals(BigDecimal("12.00"), updated.orderItems[0].price)
        verify(orderEventPublisher).publish(any(OrderItemsUpdatedEvent::class.java))
    }

    @Test
    fun `updateOrderItems throws when increased quantity exceeds stock`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.productExists(1L)).willReturn(true)
        given(inventoryClient.isStockAvailable(1L, 999)).willReturn(false)

        assertThrows(InsufficientStockException::class.java) {
            orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 999)))
        }
    }

    @Test
    fun `updateOrderItems throws when a changed item's product no longer exists`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.productExists(1L)).willReturn(false)

        assertThrows(ProductNotFoundException::class.java) {
            orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 5)))
        }
    }

    @Test
    fun `updateOrderItems throws when a newly added product does not exist`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.productExists(2L)).willReturn(false)

        assertThrows(ProductNotFoundException::class.java) {
            orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 2), OrderItemRequest(2L, 1)))
        }
    }

    @Test
    fun `updateOrderItems removes items missing from the new list`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        order.addOrderItem(OrderItem(productId = 2L, quantity = 1, price = BigDecimal("5.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        val updated = orderService.updateOrderItems(1L, listOf(OrderItemRequest(1L, 2)))

        assertEquals(1, updated.orderItems.size)
        assertEquals(1L, updated.orderItems[0].productId)
    }

    @Test
    fun `approveOrder moves a pending order to approved`() {
        val order = pendingOrder("customer-1")
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        val approved = orderService.approveOrder(1L)

        assertEquals(OrderStatus.APPROVED, approved.status)
        verify(orderEventPublisher).publish(any(OrderApprovedEvent::class.java))
    }

    @Test
    fun `approveOrder re-checks stock and throws when it is no longer available`() {
        val order = pendingOrder("customer-1")
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("10.00")))
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.isStockAvailable(1L, 2)).willReturn(false)

        assertThrows(InsufficientStockException::class.java) { orderService.approveOrder(1L) }
    }

    @Test
    fun `approveOrder throws when order is not pending`() {
        val order = pendingOrder("customer-1")
        order.status = OrderStatus.APPROVED
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) { orderService.approveOrder(1L) }
    }

    @Test
    fun `rejectOrder moves a pending order to rejected and publishes an event`() {
        val order = pendingOrder("customer-1")
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        val rejected = orderService.rejectOrder(1L)

        assertEquals(OrderStatus.REJECTED, rejected.status)
        verify(orderEventPublisher).publish(any(OrderRejectedEvent::class.java))
    }

    @Test
    fun `rejectOrder throws when order is not pending`() {
        val order = pendingOrder("customer-1")
        order.status = OrderStatus.CANCELLED
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) { orderService.rejectOrder(1L) }
    }

    @Test
    fun `cancelOrder throws when caller does not own the order`() {
        val order = pendingOrder("customer-1")
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(OrderNotOwnedException::class.java) { orderService.cancelOrder(1L, "someone-else") }
    }

    @Test
    fun `cancelOrder throws when order is already rejected`() {
        val order = pendingOrder("customer-1")
        order.status = OrderStatus.REJECTED
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) { orderService.cancelOrder(1L, "customer-1") }
    }

    @Test
    fun `cancelOrder on a pending order with no payments does not trigger a reversal`() {
        val order = pendingOrder("customer-1")
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        val cancelled = orderService.cancelOrder(1L, "customer-1")

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        verify(transactionService, never()).reverse(any(Order::class.java), any(BigDecimal::class.java))

        val captor = ArgumentCaptor.forClass(OrderCancelledEvent::class.java)
        verify(orderEventPublisher).publish(captor.capture())
        assertEquals(BigDecimal.ZERO, captor.value.refundedAmount)
    }

    @Test
    fun `cancelOrder on an approved order with payments reverses the amount paid`() {
        val order = pendingOrder("customer-1")
        order.status = OrderStatus.APPROVED
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(transactionService.getTotalPaid(order)).willReturn(BigDecimal("30.00"))

        val cancelled = orderService.cancelOrder(1L, "customer-1")

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        verify(transactionService).reverse(order, BigDecimal("30.00"))

        val captor = ArgumentCaptor.forClass(OrderCancelledEvent::class.java)
        verify(orderEventPublisher).publish(captor.capture())
        assertEquals(BigDecimal("30.00"), captor.value.refundedAmount)
    }

    @Test
    fun `findOrderOrThrow throws OrderNotFoundException for an unknown id`() {
        given(orderRepository.findById(anyLong())).willReturn(Optional.empty())

        assertThrows(OrderNotFoundException::class.java) { orderService.findOrderOrThrow(1L) }
    }

    private fun pendingOrder(customerId: String): Order =
        Order(id = 1L, name = "John", surname = "Doe", customerId = customerId, status = OrderStatus.PENDING)
}
