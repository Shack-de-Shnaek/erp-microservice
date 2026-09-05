package finki.ukim.erp.orders

import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.usertype.UserType
import java.io.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * How a typed identifier is stored in its own `id` column.
 *
 * The converters in [OrderIdConverter] and friends handle every other single-valued value object,
 * but JPA does not allow an `AttributeConverter` on an identifier - Hibernate rejects it outright.
 * A `UserType` is the supported way to say "this Java type is one varchar", and unlike an
 * `@Embeddable` id it does not turn the primary key into a composite one, which is what lets the
 * aggregate's repository take a row lock when it loads an order.
 */
abstract class StringIdentifierUserType<T : Identifier<String>> : UserType<T> {

    protected abstract fun wrap(value: String): T

    override fun getSqlType(): Int = Types.VARCHAR

    override fun equals(x: T?, y: T?): Boolean = x == y

    override fun hashCode(x: T?): Int = x?.hashCode() ?: 0

    override fun nullSafeGet(
        rs: ResultSet,
        position: Int,
        session: SharedSessionContractImplementor?,
        owner: Any?
    ): T? = rs.getString(position)?.let { wrap(it) }

    override fun nullSafeSet(
        st: PreparedStatement,
        value: T?,
        index: Int,
        session: SharedSessionContractImplementor?
    ) {
        if (value == null) {
            st.setNull(index, Types.VARCHAR)
        } else {
            st.setString(index, value.value)
        }
    }

    /** Identifiers are immutable, so there is nothing to copy and nothing to snapshot. */
    override fun deepCopy(value: T?): T? = value

    override fun isMutable(): Boolean = false

    override fun disassemble(value: T?): Serializable? = value?.value

    override fun assemble(cached: Serializable?, owner: Any?): T? = (cached as? String)?.let { wrap(it) }
}

class OrderIdType : StringIdentifierUserType<OrderId>() {
    override fun wrap(value: String) = OrderId(value)
    override fun returnedClass(): Class<OrderId> = OrderId::class.java
}

class InvoiceIdType : StringIdentifierUserType<InvoiceId>() {
    override fun wrap(value: String) = InvoiceId(value)
    override fun returnedClass(): Class<InvoiceId> = InvoiceId::class.java
}

class TransactionIdType : StringIdentifierUserType<TransactionId>() {
    override fun wrap(value: String) = TransactionId(value)
    override fun returnedClass(): Class<TransactionId> = TransactionId::class.java
}
