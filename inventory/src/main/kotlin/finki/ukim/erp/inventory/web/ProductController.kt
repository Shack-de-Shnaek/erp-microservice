package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.CreateProductCommand
import finki.ukim.erp.inventory.domain.product.DeactivateProductCommand
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.ReactivateProductCommand
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.product.UpdateProductCommand
import finki.ukim.erp.inventory.query.product.FindAllProductsQuery
import finki.ukim.erp.inventory.query.product.FindProductByIdQuery
import finki.ukim.erp.inventory.query.product.FindProductsByStatusQuery
import finki.ukim.erp.inventory.readmodel.ProductView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.queryhandling.QueryGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products")
class ProductController(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
) {

    @PostMapping
    fun create(@RequestBody request: CreateProductRequest): ResponseEntity<ProductView> {
        val command = CreateProductCommand(
            productId = ProductId.generate(),
            sku = Sku(request.sku),
            name = ProductName(request.name),
            unitOfMeasure = UnitOfMeasure(request.unitOfMeasure),
        )
        commandGateway.sendAndWait<Any>(command)
        val view = fetchWithRetry { queryById(command.productId.value) }
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @GetMapping
    fun findAll(@RequestParam(required = false) status: ProductStatus?): List<ProductView> =
        if (status != null) {
            queryGateway.query(
                FindProductsByStatusQuery(status),
                ResponseTypes.multipleInstancesOf(ProductView::class.java),
            ).get()
        } else {
            queryGateway.query(
                FindAllProductsQuery,
                ResponseTypes.multipleInstancesOf(ProductView::class.java),
            ).get()
        }

    @GetMapping("/{productId}")
    fun findById(@PathVariable productId: String): ResponseEntity<ProductView> {
        val view = queryById(productId)
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PutMapping("/{productId}")
    fun update(
        @PathVariable productId: String,
        @RequestBody request: UpdateProductRequest,
    ): ResponseEntity<ProductView> {
        val before = queryById(productId)
        commandGateway.sendAndWait<Any>(
            UpdateProductCommand(
                productId = ProductId.fromString(productId),
                sku = Sku(request.sku),
                name = ProductName(request.name),
                unitOfMeasure = UnitOfMeasure(request.unitOfMeasure),
            ),
        )
        val view = fetchWithRetry(changedFrom = before) { queryById(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PostMapping("/{productId}/deactivate")
    fun deactivate(@PathVariable productId: String): ResponseEntity<ProductView> {
        val before = queryById(productId)
        commandGateway.sendAndWait<Any>(DeactivateProductCommand(ProductId.fromString(productId)))
        val view = fetchWithRetry(changedFrom = before) { queryById(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PostMapping("/{productId}/reactivate")
    fun reactivate(@PathVariable productId: String): ResponseEntity<ProductView> {
        val before = queryById(productId)
        commandGateway.sendAndWait<Any>(ReactivateProductCommand(ProductId.fromString(productId)))
        val view = fetchWithRetry(changedFrom = before) { queryById(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    private fun queryById(productId: String): ProductView? =
        queryGateway.query(
            FindProductByIdQuery(productId),
            ResponseTypes.instanceOf(ProductView::class.java),
        ).get()
}

data class CreateProductRequest(
    val sku: String,
    val name: String,
    val unitOfMeasure: String,
)

data class UpdateProductRequest(
    val sku: String,
    val name: String,
    val unitOfMeasure: String,
)