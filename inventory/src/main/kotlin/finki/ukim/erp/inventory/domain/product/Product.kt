package finki.ukim.erp.inventory.domain.product

import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle.apply
import org.axonframework.spring.stereotype.Aggregate

@Aggregate(repository = "productRepository")
@Entity
class Product {

    @EmbeddedId
    @AttributeOverride(name = "value", column = Column(name = "product_id"))
    @AggregateIdentifier
    var productId: ProductId? = null

    @AttributeOverride(name = "value", column = Column(name = "sku_value"))
    var sku: Sku? = null

    @AttributeOverride(name = "value", column = Column(name = "name_value"))
    var name: ProductName? = null

    @AttributeOverride(name = "value", column = Column(name = "unit_of_measure_value"))
    var unitOfMeasure: UnitOfMeasure? = null

    var status: ProductStatus = ProductStatus.ACTIVE

    constructor()

    @CommandHandler
    constructor(command: CreateProductCommand) {
        apply(ProductCreatedEvent(command))
    }

    @CommandHandler
    fun on(command: UpdateProductCommand) {
        if (sku == command.sku && name == command.name && unitOfMeasure == command.unitOfMeasure) {
            return
        }
        apply(ProductUpdatedEvent(command))
    }

    @CommandHandler
    fun on(command: DeactivateProductCommand) {
        if (status == ProductStatus.INACTIVE) {
            return
        }
        apply(ProductDeactivatedEvent(command))
    }

    @CommandHandler
    fun on(command: ReactivateProductCommand) {
        if (status == ProductStatus.ACTIVE) {
            return
        }
        apply(ProductReactivatedEvent(command))
    }

    @EventSourcingHandler
    fun on(event: ProductCreatedEvent) {
        productId = event.productId
        sku = event.sku
        name = event.name
        unitOfMeasure = event.unitOfMeasure
        status = ProductStatus.ACTIVE
    }

    @EventSourcingHandler
    fun on(event: ProductUpdatedEvent) {
        sku = event.sku
        name = event.name
        unitOfMeasure = event.unitOfMeasure
    }

    @EventSourcingHandler
    fun on(event: ProductDeactivatedEvent) {
        status = ProductStatus.INACTIVE
    }

    @EventSourcingHandler
    fun on(event: ProductReactivatedEvent) {
        status = ProductStatus.ACTIVE
    }
}