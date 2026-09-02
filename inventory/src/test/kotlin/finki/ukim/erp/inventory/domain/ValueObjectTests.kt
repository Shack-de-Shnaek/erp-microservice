package finki.ukim.erp.inventory.domain

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ValueObjectTests {

    @Test
    fun `blank SKU is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { Sku("") }
        assertEquals("SKU must not be blank", ex.message)
    }

    @Test
    fun `whitespace-only SKU is rejected`() {
        assertThrows<IllegalArgumentException> { Sku("   ") }
    }

    @Test
    fun `valid SKU is accepted`() {
        assertEquals("SKU-001", Sku("SKU-001").value)
    }

    @Test
    fun `blank product name is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { ProductName("") }
        assertEquals("Product name must not be blank", ex.message)
    }

    @Test
    fun `blank unit of measure is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { UnitOfMeasure("") }
        assertEquals("Unit of measure must not be blank", ex.message)
    }

    @Test
    fun `negative quantity is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { Quantity(-1) }
        assertEquals("Quantity must be non-negative", ex.message)
    }

    @Test
    fun `zero quantity is accepted`() {
        assertEquals(0, Quantity(0).amount)
    }

    @Test
    fun `negative reorder threshold is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { ReorderThreshold(-5) }
        assertEquals("Reorder threshold must be non-negative", ex.message)
    }

    @Test
    fun `zero reorder threshold is accepted`() {
        assertEquals(0, ReorderThreshold(0).value)
    }

    @Test
    fun `product ref with blank product id is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProductRef(ProductId.fromString(""))
        }
        assertEquals("Product reference must not be blank", ex.message)
    }

    @Test
    fun `valid product ref is accepted`() {
        val id = ProductId.generate()
        assertEquals(id, ProductRef(id).productId)
    }
}
