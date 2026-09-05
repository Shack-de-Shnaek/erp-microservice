package finki.ukim.erp.orders

/**
 * A strongly-typed identifier.
 *
 * Covariant in [T] so that anything holding "some identifier" - `AbstractEvent`, for one - can
 * accept an [OrderId] or a [ProductId] without caring which. Wrapping ids in their own types is what stops an [InvoiceId] from
 * ever being passed where an [OrderId] is expected - a mistake a raw `String` id makes invisible
 * to the compiler and visible only at runtime, usually as a "not found".
 */
interface Identifier<out T> {
    val value: T
}

/**
 * Anything that can name itself in a log line, an error message or a picker in a UI, without the
 * reader having to know the shape of its table.
 */
interface LabeledEntity {
    fun label(): String
}
