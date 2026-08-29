package finki.ukim.erp.orders

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import java.time.LocalDateTime

@Entity
open class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @Column(name = "name", nullable = false)
    open var name: String = "",

    @Column(name = "surname", nullable = false)
    open var surname: String = "",

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var orderItems: MutableList<OrderItem> = mutableListOf(),

    @OneToOne(mappedBy = "order", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    open var invoice: Invoice? = null
) {
    protected constructor() : this(
        id = null,
        name = "",
        surname = "",
        date = LocalDateTime.now(),
        orderItems = mutableListOf(),
        invoice = null
    )

    fun addOrderItem(orderItem: OrderItem) {
        orderItems.add(orderItem)
        orderItem.order = this
    }

    fun removeOrderItem(orderItem: OrderItem) {
        orderItems.remove(orderItem)
        orderItem.order = null
    }
}
