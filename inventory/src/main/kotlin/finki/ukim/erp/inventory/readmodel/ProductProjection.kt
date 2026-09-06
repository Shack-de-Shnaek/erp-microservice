package finki.ukim.erp.inventory.readmodel

import finki.ukim.erp.inventory.domain.product.ProductCreatedEvent
import finki.ukim.erp.inventory.domain.product.ProductDeactivatedEvent
import finki.ukim.erp.inventory.domain.product.ProductReactivatedEvent
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.ProductUpdatedEvent
import finki.ukim.erp.inventory.query.product.FindAllProductsQuery
import finki.ukim.erp.inventory.query.product.FindProductByIdQuery
import finki.ukim.erp.inventory.query.product.FindProductBySkuQuery
import finki.ukim.erp.inventory.query.product.FindProductsByStatusQuery
import org.axonframework.eventhandling.EventHandler
import org.axonframework.queryhandling.QueryHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductProjection(
    private val productViewRepository: ProductViewRepository,
) {

    @EventHandler
    @Transactional
    fun on(event: ProductCreatedEvent) {
        productViewRepository.save(
            ProductView(
                productId = event.productId.value,
                sku = event.sku.value,
                name = event.name.value,
                unitOfMeasure = event.unitOfMeasure.value,
                status = ProductStatus.ACTIVE,
            ),
        )
    }

    @EventHandler
    @Transactional
    fun on(event: ProductUpdatedEvent) {
        upsert(event.productId.value) {
            it.copy(
                sku = event.sku.value,
                name = event.name.value,
                unitOfMeasure = event.unitOfMeasure.value,
            )
        }
    }

    @EventHandler
    @Transactional
    fun on(event: ProductDeactivatedEvent) {
        upsert(event.productId.value) { it.copy(status = ProductStatus.INACTIVE) }
    }

    @EventHandler
    @Transactional
    fun on(event: ProductReactivatedEvent) {
        upsert(event.productId.value) { it.copy(status = ProductStatus.ACTIVE) }
    }

    @QueryHandler
    fun handle(query: FindAllProductsQuery): List<ProductView> =
        productViewRepository.findAll()

    @QueryHandler
    fun handle(query: FindProductByIdQuery): ProductView? =
        productViewRepository.findById(query.productId).orElse(null)

    @QueryHandler
    fun handle(query: FindProductBySkuQuery): ProductView? =
        productViewRepository.findBySku(query.sku)

    @QueryHandler
    fun handle(query: FindProductsByStatusQuery): List<ProductView> =
        productViewRepository.findByStatus(query.status)

    private fun upsert(productId: String, transform: (ProductView) -> ProductView) {
        val existing = productViewRepository.findById(productId).orElse(null) ?: return
        productViewRepository.deleteById(productId)
        productViewRepository.flush()
        productViewRepository.save(transform(existing))
    }
}