package finki.ukim.erp.orders

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * A line on an invoice. Deliberately a separate entity from [OrderItem] even though the two look
 * alike: the invoice is a snapshot of what was billed, and correcting an invoice line must not
 * move the order it was issued for.
 */
@Entity
@Table
open class InvoiceLineItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    open var invoice: Invoice? = null,

    @Column(name = "inventory_item_id", nullable = false)
    open var inventoryItemId: ProductId = ProductId(),

    @Column(name = "quantity", nullable = false)
    open var quantity: Quantity = Quantity(),

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    open var price: Money = Money.ZERO
) {
    protected constructor() : this(id = null)

    fun lineTotal(): Money = price * quantity
}
