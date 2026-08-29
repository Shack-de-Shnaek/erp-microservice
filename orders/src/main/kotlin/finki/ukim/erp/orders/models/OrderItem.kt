package finki.ukim.erp.orders

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.math.BigDecimal

@Entity
open class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @Column(name = "product_id", nullable = false)
    open var productId: Long = 0L,

    @Column(name = "quantity", nullable = false)
    open var quantity: Int = 0,

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    open var price: BigDecimal = BigDecimal.ZERO,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    open var order: Order? = null
) {
    protected constructor() : this(
        id = null,
        productId = 0L,
        quantity = 0,
        price = BigDecimal.ZERO,
        order = null
    )
}
