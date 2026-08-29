package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.repositories.OrderRepository
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val orderRepository: OrderRepository
) {
}
