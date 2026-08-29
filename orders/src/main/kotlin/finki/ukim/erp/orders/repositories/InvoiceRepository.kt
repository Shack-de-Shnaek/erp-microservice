package finki.ukim.erp.orders.repositories

import finki.ukim.erp.orders.Invoice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface InvoiceRepository : JpaRepository<Invoice, Long>
