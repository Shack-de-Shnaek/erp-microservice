package finki.ukim.erp.inventory.domain.product

import finki.ukim.erp.inventory.domain.base.Identifier
import jakarta.persistence.Embeddable
import java.util.UUID

@Embeddable
data class ProductId(
    override val value: String = UUID.randomUUID().toString(),
) : Identifier<String> {

    override fun toString(): String = value

    companion object {
        fun generate(): ProductId = ProductId()
        fun fromString(value: String): ProductId = ProductId(value)
    }
}