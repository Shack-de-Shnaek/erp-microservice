package finki.ukim.erp.orders.views

import com.fasterxml.jackson.annotation.JsonIgnore
import finki.ukim.erp.orders.TransactionIdType
import finki.ukim.erp.orders.LabeledEntity
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.PaymentType
import finki.ukim.erp.orders.TransactionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.Type
import org.hibernate.annotations.Subselect
import org.hibernate.annotations.Synchronize
import java.time.LocalDateTime

/** The read side of a payment or a refund; see [OrderView]. */
@Entity
@Immutable
@Subselect(
    """
    select t.id, t.payment_type, t.date, t.amount, t.order_id from transaction t
    """
)
@Synchronize("transaction")
open class TransactionView(
    @Id
    // An identifier cannot go through an AttributeConverter; see StringIdentifierUserType.
    @Type(TransactionIdType::class)
    @Column(name = "id")
    open val id: TransactionId = TransactionId(),

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type")
    open val paymentType: PaymentType = PaymentType.CARD,

    @Column(name = "date")
    open val date: LocalDateTime = LocalDateTime.now(),

    @Column(name = "amount")
    open val amount: Money = Money.ZERO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnore
    open val order: OrderView? = null
) : LabeledEntity {

    protected constructor() : this(id = TransactionId())

    override fun label(): String = "$paymentType $amount"

    val orderId: OrderId? get() = order?.id

    /** A refund is stored as a negative amount. */
    val isRefund: Boolean get() = !amount.isPositive()
}
