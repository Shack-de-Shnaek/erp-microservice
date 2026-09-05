package finki.ukim.erp.orders

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.Embeddable
import java.math.BigDecimal

/**
 * An amount of money.
 *
 * The guard is about representation, not sign: money in this domain is stored to the cent, and a
 * value carrying more precision than that means a rounding decision was made somewhere it should
 * not have been. Negative amounts are legitimate and deliberate - a refund is stored as a
 * transaction with a negative amount, which is what makes a payment and its reversal sum to zero.
 */
data class Money(@get:JsonValue val amount: BigDecimal = BigDecimal.ZERO) {

    init {
        require(amount.scale() <= 2) {
            "Money is held to the cent; $amount has ${amount.scale()} decimal places"
        }
    }

    operator fun plus(other: Money) = Money(amount + other.amount)

    operator fun times(quantity: Quantity) = Money(amount * BigDecimal(quantity.value))

    operator fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    fun negate() = Money(amount.negate())

    fun isPositive() = amount > BigDecimal.ZERO

    override fun toString(): String = amount.toPlainString()

    companion object {
        val ZERO = Money(BigDecimal.ZERO)

        fun sum(amounts: Iterable<Money>) = amounts.fold(ZERO) { acc, money -> acc + money }

        @JvmStatic
        @JsonCreator
        fun of(amount: BigDecimal) = Money(amount)

        /**
         * An amount arriving from outside this service, where trailing zeros are an artefact of
         * JSON rather than a decision - 19.9900 and 19.99 are the same price. Anything still
         * carrying more than two decimals after that is a genuinely different number, and is
         * rejected rather than rounded.
         */
        fun fromExternal(amount: BigDecimal): Money {
            val trimmed = amount.stripTrailingZeros()
            return Money(if (trimmed.scale() < 0) trimmed.setScale(0) else trimmed)
        }
    }
}

/**
 * How many of something an order line asks for. An order line for zero items is not an order line,
 * and a negative one is nonsense, so neither can be constructed at all.
 */
data class Quantity(@get:JsonValue val value: Int = 1) {

    init {
        require(value >= 1) { "A quantity must be at least 1, got $value" }
    }

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(value: Int) = Quantity(value)
    }
}

/**
 * The citizen identification number the invoice is issued to. Thirteen digits, exactly - an
 * invoice carrying anything else is not a valid tax document.
 */
data class Embg(@get:JsonValue val value: String = PLACEHOLDER) {

    init {
        require(FORMAT.matches(value)) { "An EMBG is exactly 13 digits, got '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("\\d{13}")

        /** Satisfies the guard so JPA and Jackson can instantiate before populating fields. */
        private const val PLACEHOLDER = "0000000000000"

        @JvmStatic
        @JsonCreator
        fun of(value: String) = Embg(value)
    }
}

/**
 * The number printed on the invoice. Unlike [InvoiceId], which is internal plumbing, this is what
 * a customer quotes when they call about a document, so its shape is part of the domain.
 */
data class InvoiceNumber(@get:JsonValue val value: String = PLACEHOLDER) {

    init {
        require(FORMAT.matches(value)) { "An invoice number looks like 'INV-A1B2C3D4', got '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("INV-[A-Z0-9]{4,}")
        private const val PLACEHOLDER = "INV-0000"

        @JvmStatic
        @JsonCreator
        fun of(value: String) = InvoiceNumber(value)
    }
}

/**
 * Who the order is for. First and last name always travel together and are never meaningful
 * apart, which is exactly what makes them one value rather than two fields.
 */
@Embeddable
data class CustomerName(val name: String = "-", val surname: String = "-") {

    init {
        require(name.isNotBlank()) { "A customer's name must not be blank" }
        require(surname.isNotBlank()) { "A customer's surname must not be blank" }
    }

    override fun toString(): String = "$name $surname"
}
