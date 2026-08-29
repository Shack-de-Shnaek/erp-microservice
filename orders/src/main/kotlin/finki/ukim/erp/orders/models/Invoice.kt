package finki.ukim.erp.orders

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import java.time.LocalDateTime

@Entity
open class Invoice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    open var order: Order? = null,

    @Column(name = "is_refunded", nullable = false)
    open var isRefunded: Boolean = false,

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    @Column(name = "invoice_number", nullable = false, unique = true)
    open var invoiceNumber: String = "",

    @Column(name = "embg", nullable = false, length = 13)
    open var embg: String = "",

    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var invoiceLineItems: MutableList<InvoiceLineItem> = mutableListOf()
) {
    protected constructor() : this(
        id = null,
        order = null,
        isRefunded = false,
        date = LocalDateTime.now(),
        invoiceNumber = "",
        embg = "",
        invoiceLineItems = mutableListOf()
    )

    fun addInvoiceLineItem(invoiceLineItem: InvoiceLineItem) {
        invoiceLineItems.add(invoiceLineItem)
        invoiceLineItem.invoice = this
    }

    fun removeInvoiceLineItem(invoiceLineItem: InvoiceLineItem) {
        invoiceLineItems.remove(invoiceLineItem)
        invoiceLineItem.invoice = null
    }
}
