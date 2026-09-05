package finki.ukim.erp.orders

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne

/**
 * A line on an order: how much of which product, at the price it was quoted when the line was
 * added.
 *
 * An entity rather than a value object, and the difference is worth being explicit about: two
 * lines for 2 x product 1 at 25.00 are not the same line. One of them can be removed while the
 * other stays, and the database has to be able to tell them apart - which is what the generated
 * [id] is for. The [price] and [quantity] it carries *are* value objects: a quantity of 2 is a
 * quantity of 2, wherever it appears.
 *
 * The price is stored rather than looked up because it is the price the customer was quoted. What
 * inventory charges tomorrow does not retroactively change what this order costs.
 */
@Entity
open class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @Column(name = "product_id", nullable = false)
    open var productId: ProductId = ProductId(),

    @Column(name = "quantity", nullable = false)
    open var quantity: Quantity = Quantity(),

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    open var price: Money = Money.ZERO,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    open var order: Order? = null
) {
    protected constructor() : this(id = null)

    /** What this line contributes to the order total. */
    fun lineTotal(): Money = price * quantity
}
