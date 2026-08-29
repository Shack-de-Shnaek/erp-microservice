package finki.ukim.erp.orders.repositories

import finki.ukim.erp.orders.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface OrderRepository : JpaRepository<Order, Long>
