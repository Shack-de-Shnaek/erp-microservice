package finki.ukim.erp.orders

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/**
 * The identifiers of this bounded context.
 *
 * Each carries a human-readable prefix, so a row in `domain_event_entry`, a Kafka key or a log
 * line says what kind of thing it points at without a join: "Order:9f1c...", not "9f1c...".
 *
 * Every one of them needs three things to survive the round trip through the infrastructure:
 * a no-arg form (JPA instantiates an @Embeddable reflectively, and Axon's Jackson serializer
 * does the same when reading an event back out of the store), [JsonValue] so it is written as
 * the bare prefixed string rather than as `{"value": "..."}`, and [JsonCreator] to read that
 * string back. Kotlin's default argument gives us the first; the other two are declared.
 */
data class OrderId(@get:JsonValue override val value: String = "") : Identifier<String> {

    /** A fresh identifier for a new order. */
    constructor(uuid: UUID) : this("$PREFIX$uuid")

    override fun toString(): String = value

    companion object {
        const val PREFIX = "Order:"

        fun random() = OrderId(UUID.randomUUID())

        @JvmStatic
        @JsonCreator
        fun of(value: String) = OrderId(value)
    }
}

data class InvoiceId(@get:JsonValue override val value: String = "") : Identifier<String> {

    constructor(uuid: UUID) : this("$PREFIX$uuid")

    override fun toString(): String = value

    companion object {
        const val PREFIX = "Invoice:"

        fun random() = InvoiceId(UUID.randomUUID())

        @JvmStatic
        @JsonCreator
        fun of(value: String) = InvoiceId(value)
    }
}

data class TransactionId(@get:JsonValue override val value: String = "") : Identifier<String> {

    constructor(uuid: UUID) : this("$PREFIX$uuid")

    override fun toString(): String = value

    companion object {
        const val PREFIX = "Transaction:"

        fun random() = TransactionId(UUID.randomUUID())

        @JvmStatic
        @JsonCreator
        fun of(value: String) = TransactionId(value)
    }
}

/**
 * A product in the inventory service. Orders does not own products, so this is a reference across a
 * service boundary rather than an identifier of anything in here - and the value is whatever
 * inventory says it is.
 *
 * That value is a string. Inventory keys its products by an opaque identifier it generates (a UUID
 * in every deployment so far), and it is the segment orders puts in the path of
 * `GET /api/products/{productId}`. Treating it as a number here would make every one of those calls
 * a guaranteed 404, so this type says what is actually true: an opaque token owned elsewhere,
 * carried unaltered.
 */
data class ProductId(@get:JsonValue override val value: String = "") : Identifier<String> {

    override fun toString(): String = "Product:$value"

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(value: String) = ProductId(value)
    }
}
