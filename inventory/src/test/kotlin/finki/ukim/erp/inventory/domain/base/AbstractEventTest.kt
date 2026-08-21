package finki.ukim.erp.inventory.domain.base

import finki.ukim.erp.inventory.domain.product.ProductCreatedEvent
import finki.ukim.erp.inventory.domain.product.ProductCreatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductDeactivatedEvent
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductReactivatedEvent
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockItemCreatedEvent
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.domain.stockitem.StockReservedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AbstractEventTest {

    @Test
    fun `event topic is derived by stripping Event suffix and dotting camel case`() {
        val productId = ProductId.generate()
        val productCreated = ProductCreatedEvent(
            productId,
            Sku("SKU-001"),
            ProductName("Widget"),
            UnitOfMeasure("pcs"),
        )
        val productDeactivated = ProductDeactivatedEvent(productId)
        val stockItemId = StockItemId.generate()
        val stockItemCreated = StockItemCreatedEvent(
            stockItemId,
            ProductRef(productId),
            Quantity(10),
            ReorderThreshold(5),
        )
        val stockReserved = StockReservedEvent(stockItemId, "order-1", Quantity(2))
        val stockConfirmed = StockConfirmedEvent(stockItemId, "order-1", Quantity(2))

        assertEquals("product.created", productCreated.eventTopic())
        assertEquals("product.deactivated", productDeactivated.eventTopic())
        assertEquals("stock.item.created", stockItemCreated.eventTopic())
        assertEquals("stock.reserved", stockReserved.eventTopic())
        assertEquals("stock.confirmed", stockConfirmed.eventTopic())
    }

    @Test
    fun `events do not publish to Kafka unless an external event is defined`() {
        val productId = ProductId.generate()
        val stockItemId = StockItemId.generate()
        val stockItemCreated = StockItemCreatedEvent(
            stockItemId,
            ProductRef(productId),
            Quantity(10),
            ReorderThreshold(5),
        )
        val productReactivated = ProductReactivatedEvent(productId)

        assertEquals(null, stockItemCreated.toExternalEvent())
        assertEquals(null, productReactivated.toExternalEvent())
    }

    @Test
    fun `events with an external contract publish a dedicated dto`() {
        val productId = ProductId.generate()
        val productCreated = ProductCreatedEvent(
            productId,
            Sku("SKU-001"),
            ProductName("Widget"),
            UnitOfMeasure("pcs"),
        )

        val external = productCreated.toExternalEvent()
        check(external != null) { "ProductCreatedEvent must be externalized" }
        assertEquals("SKU-001", (external as ProductCreatedExternalEvent).sku)
    }
}