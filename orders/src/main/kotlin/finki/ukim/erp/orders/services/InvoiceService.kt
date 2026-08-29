package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.repositories.InvoiceRepository
import org.springframework.stereotype.Service

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository
) {
}
