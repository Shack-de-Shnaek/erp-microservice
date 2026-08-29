package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.repositories.TransactionRepository
import org.springframework.stereotype.Service

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository
) {
}
