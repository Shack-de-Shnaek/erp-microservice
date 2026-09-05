package finki.ukim.erp.orders

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Type
import java.time.LocalDateTime

/**
 * Money moving for an order. Reversals are rows too, with a negative [amount] - nothing is ever
 * deleted or edited, so a payment and its refund still both show on the order and net to zero.
 */
@Entity
open class Transaction(
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(TransactionIdType::class)
    @Column(name = "id", nullable = false)
    open var id: TransactionId = TransactionId(),

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    open var paymentType: PaymentType = PaymentType.CARD,

    @Column(name = "date", nullable = false)
    open var date: LocalDateTime = LocalDateTime.now(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    open var order: Order? = null,

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    open var amount: Money = Money.ZERO
) : LabeledEntity {
    protected constructor() : this(id = TransactionId())

    override fun label(): String = "$paymentType $amount"
}
