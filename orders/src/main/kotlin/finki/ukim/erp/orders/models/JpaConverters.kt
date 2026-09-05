package finki.ukim.erp.orders

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/**
 * How the single-valued value objects are stored.
 *
 * Each of these wraps exactly one value, so each occupies exactly one column - an [OrderId] is a
 * `varchar`, a [Money] is a `numeric`, a [Quantity] is an `int`. An `AttributeConverter` is JPA's
 * tool for that, and `autoApply = true` means it is used everywhere the type appears without
 * having to be repeated on every field.
 *
 * Mapping them as `@Embeddable` instead would work, but it would make every identifier a
 * *composite* key as far as Hibernate is concerned - one component, but composite - and Hibernate
 * cannot take the row lock the aggregate's repository asks for on a composite key. A converter
 * says what is actually true: this is one value in one column.
 *
 * [CustomerName] is the exception and stays `@Embeddable`: it is two columns, which is precisely
 * what an embeddable is for.
 */
@Converter(autoApply = true)
class OrderIdConverter : AttributeConverter<OrderId, String> {
    override fun convertToDatabaseColumn(attribute: OrderId?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): OrderId? = dbData?.let { OrderId(it) }
}

@Converter(autoApply = true)
class InvoiceIdConverter : AttributeConverter<InvoiceId, String> {
    override fun convertToDatabaseColumn(attribute: InvoiceId?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): InvoiceId? = dbData?.let { InvoiceId(it) }
}

@Converter(autoApply = true)
class TransactionIdConverter : AttributeConverter<TransactionId, String> {
    override fun convertToDatabaseColumn(attribute: TransactionId?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): TransactionId? = dbData?.let { TransactionId(it) }
}

@Converter(autoApply = true)
class ProductIdConverter : AttributeConverter<ProductId, Long> {
    override fun convertToDatabaseColumn(attribute: ProductId?): Long? = attribute?.value
    override fun convertToEntityAttribute(dbData: Long?): ProductId? = dbData?.let { ProductId(it) }
}

@Converter(autoApply = true)
class MoneyConverter : AttributeConverter<Money, BigDecimal> {
    override fun convertToDatabaseColumn(attribute: Money?): BigDecimal? = attribute?.amount

    /**
     * The column is `numeric(19, 2)`, so what comes back always has a scale of 2 - within what
     * [Money] allows, and the reason reading a row can never trip its guard.
     */
    override fun convertToEntityAttribute(dbData: BigDecimal?): Money? = dbData?.let { Money(it) }
}

@Converter(autoApply = true)
class QuantityConverter : AttributeConverter<Quantity, Int> {
    override fun convertToDatabaseColumn(attribute: Quantity?): Int? = attribute?.value
    override fun convertToEntityAttribute(dbData: Int?): Quantity? = dbData?.let { Quantity(it) }
}

@Converter(autoApply = true)
class EmbgConverter : AttributeConverter<Embg, String> {
    override fun convertToDatabaseColumn(attribute: Embg?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): Embg? = dbData?.let { Embg(it) }
}

@Converter(autoApply = true)
class InvoiceNumberConverter : AttributeConverter<InvoiceNumber, String> {
    override fun convertToDatabaseColumn(attribute: InvoiceNumber?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): InvoiceNumber? = dbData?.let { InvoiceNumber(it) }
}
