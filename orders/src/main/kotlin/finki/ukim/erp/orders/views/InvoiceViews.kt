package finki.ukim.erp.orders.views

import com.fasterxml.jackson.annotation.JsonIgnore
import finki.ukim.erp.orders.InvoiceIdType
import finki.ukim.erp.orders.Embg
import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.InvoiceNumber
import finki.ukim.erp.orders.LabeledEntity
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.Type
import org.hibernate.annotations.Subselect
import org.hibernate.annotations.Synchronize
import java.time.LocalDateTime

/** The read side of an invoice; see [OrderView] for why this is its own class. */
@Entity
@Immutable
@Subselect(
    """
    select v.id, v.order_id, v.is_refunded, v.date, v.invoice_number, v.embg from invoice v
    """
)
@Synchronize("invoice")
open class InvoiceView(
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(InvoiceIdType::class)
    @Column(name = "id")
    open val id: InvoiceId = InvoiceId(),

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnore
    open val order: OrderView? = null,

    @Column(name = "is_refunded")
    open val isRefunded: Boolean = false,

    @Column(name = "date")
    open val date: LocalDateTime = LocalDateTime.now(),

    @Column(name = "invoice_number")
    open val invoiceNumber: InvoiceNumber = InvoiceNumber(),

    @Column(name = "embg")
    open val embg: Embg = Embg(),

    @OneToMany(mappedBy = "invoice", fetch = FetchType.EAGER)
    open val lineItems: List<InvoiceLineItemView> = emptyList()
) : LabeledEntity {

    protected constructor() : this(id = InvoiceId())

    override fun label(): String = invoiceNumber.value

    /** Flattened so a caller reading an invoice does not have to pull the whole order with it. */
    val orderId: OrderId? get() = order?.id

    val totalAmount: Money get() = Money.sum(lineItems.map { it.lineTotal })
}

@Entity
@Immutable
@Subselect(
    """
    select l.id, l.inventory_item_id, l.quantity, l.price, l.invoice_id from invoice_line_item l
    """
)
@Synchronize("invoice_line_item")
open class InvoiceLineItemView(
    @Id
    @Column(name = "id")
    open val id: Long = 0L,

    @Column(name = "inventory_item_id")
    open val inventoryItemId: ProductId = ProductId(),

    @Column(name = "quantity")
    open val quantity: Quantity = Quantity(),

    @Column(name = "price")
    open val price: Money = Money.ZERO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    @JsonIgnore
    open val invoice: InvoiceView? = null
) {
    protected constructor() : this(id = 0L)

    val lineTotal: Money get() = price * quantity
}
