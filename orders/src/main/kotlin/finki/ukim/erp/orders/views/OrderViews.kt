package finki.ukim.erp.orders.views

import com.fasterxml.jackson.annotation.JsonIgnore
import finki.ukim.erp.orders.OrderIdType
import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Identifier
import finki.ukim.erp.orders.LabeledEntity
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
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

/**
 * The read side of an order.
 *
 * It maps to the very same tables the [finki.ukim.erp.orders.Order] aggregate writes, but it is a
 * separate class on purpose: an aggregate exists to enforce rules and produce events, and the
 * moment it is also used to answer queries the write model can no longer change without breaking
 * every reader. This class exposes what a caller should see and nothing else - no command
 * handlers, no invariants, no way to modify anything.
 *
 * `@Immutable` is what makes that a promise Hibernate keeps rather than a convention: it will
 * never write these rows, so it does not take a snapshot of the loaded state to compare against
 * at flush time, and dirty checking skips these entities entirely. All writes still go through
 * the aggregate.
 */
@Entity
@Immutable
@Subselect("select o.id, o.name, o.surname, o.customer_id, o.status, o.date from orders o")
@Synchronize("orders")
open class OrderView(
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(OrderIdType::class)
    @Column(name = "id")
    open val id: OrderId = OrderId(),

    @Embedded
    @AttributeOverride(name = "name", column = Column(name = "name"))
    @AttributeOverride(name = "surname", column = Column(name = "surname"))
    open val customer: CustomerName = CustomerName(),

    @Column(name = "customer_id")
    open val customerId: String = "",

    @Column(name = "status")
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    open val status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "date")
    open val date: LocalDateTime = LocalDateTime.now(),

    @OneToMany(mappedBy = "order", fetch = FetchType.EAGER)
    open val items: List<OrderItemView> = emptyList(),

    @OneToMany(mappedBy = "order", fetch = FetchType.EAGER)
    open val transactions: List<TransactionView> = emptyList(),

    @OneToOne(mappedBy = "order", fetch = FetchType.LAZY)
    @JsonIgnore
    open val invoice: InvoiceView? = null
) : LabeledEntity {

    protected constructor() : this(id = OrderId())

    override fun label(): String = "Order ${id.value} for $customer"

    val totalAmount: Money get() = Money.sum(items.map { it.lineTotal })

    val totalPaid: Money get() = Money.sum(transactions.map { it.amount })

    /** Callers only need to know whether one exists; the invoice itself has its own endpoint. */
    val hasInvoice: Boolean get() = invoice != null
}

@Entity
@Immutable
@Subselect("select i.id, i.product_id, i.quantity, i.price, i.order_id from order_item i")
@Synchronize("order_item")
open class OrderItemView(
    @Id
    @Column(name = "id")
    open val id: Long = 0L,

    @Column(name = "product_id")
    open val productId: ProductId = ProductId(),

    @Column(name = "quantity")
    open val quantity: Quantity = Quantity(),

    @Column(name = "price")
    open val price: Money = Money.ZERO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnore
    open val order: OrderView? = null
) {
    protected constructor() : this(id = 0L)

    val lineTotal: Money get() = price * quantity
}
