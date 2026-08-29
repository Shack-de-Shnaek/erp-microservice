package finki.ukim.erp.orders

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
open class Transaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    open var paymentType: PaymentType = PaymentType.CARD,

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    open var order: Order? = null,

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    open var amount: BigDecimal = BigDecimal.ZERO
) {
    protected constructor() : this(
        id = null,
        paymentType = PaymentType.CARD,
        date = LocalDateTime.now(),
        order = null,
        amount = BigDecimal.ZERO
    )
}
