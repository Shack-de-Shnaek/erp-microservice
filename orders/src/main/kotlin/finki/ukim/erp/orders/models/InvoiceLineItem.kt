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
import java.math.BigDecimal

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
    open var inventoryItemId: Long = 0L,

    @Column(name = "quantity", nullable = false)
    open var quantity: Int = 1,

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    open var price: BigDecimal = BigDecimal.ZERO
) {
    protected constructor() : this(
        id = null,
        invoice = null,
        inventoryItemId = 0L,
        quantity = 1,
        price = BigDecimal.ZERO
    )
}
