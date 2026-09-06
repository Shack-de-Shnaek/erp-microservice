package finki.ukim.erp.orders.domain

import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, OrderId>
