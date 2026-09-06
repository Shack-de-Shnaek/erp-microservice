package finki.ukim.erp.inventory.domain.stockitem

import finki.ukim.erp.inventory.domain.base.Identifier
import jakarta.persistence.Embeddable
import java.util.UUID

@Embeddable
data class StockItemId(
    override val value: String = UUID.randomUUID().toString(),
) : Identifier<String> {

    override fun toString(): String = value

    companion object {
        fun generate(): StockItemId = StockItemId()
        fun fromString(value: String): StockItemId = StockItemId(value)
    }
}