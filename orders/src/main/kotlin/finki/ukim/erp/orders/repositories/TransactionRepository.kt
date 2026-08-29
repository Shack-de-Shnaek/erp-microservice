package finki.ukim.erp.orders.repositories

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.Transaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository : JpaRepository<Transaction, Long> {
    fun findByOrder(order: Order): List<Transaction>
}
