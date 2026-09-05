package finki.ukim.erp.orders

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import org.hibernate.annotations.Type
import java.time.LocalDateTime

/**
 * The tax document issued for a paid order.
 *
 * Part of the order's aggregate - it has no life of its own, is only ever reached through the
 * order that owns it, and the rules governing it (an order must be fully paid before it can be
 * invoiced; an order that has been invoiced can no longer be edited) are rules about the order.
 */
@Entity
open class Invoice(
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(InvoiceIdType::class)
    @Column(name = "id", nullable = false)
    open var id: InvoiceId = InvoiceId(),

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    open var order: Order? = null,

    @Column(name = "is_refunded", nullable = false)
    open var isRefunded: Boolean = false,

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    @Column(name = "invoice_number", nullable = false, unique = true)
    open var invoiceNumber: InvoiceNumber = InvoiceNumber(),

    @Column(name = "embg", nullable = false, length = 13)
    open var embg: Embg = Embg(),

    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var invoiceLineItems: MutableList<InvoiceLineItem> = mutableListOf()
) : LabeledEntity {
    protected constructor() : this(id = InvoiceId())

    override fun label(): String = invoiceNumber.value

    fun addInvoiceLineItem(invoiceLineItem: InvoiceLineItem) {
        invoiceLineItems.add(invoiceLineItem)
        invoiceLineItem.invoice = this
    }

    fun replaceLineItems(lineItems: List<InvoiceLineItem>) {
        invoiceLineItems.clear()
        lineItems.forEach { addInvoiceLineItem(it) }
    }

    fun total(): Money = Money.sum(invoiceLineItems.map { it.lineTotal() })
}
