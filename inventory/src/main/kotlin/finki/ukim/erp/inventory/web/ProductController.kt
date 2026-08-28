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
import finki.ukim.erp.inventory.query.product.FindProductBySkuQuery
import finki.ukim.erp.inventory.query.product.FindProductsByStatusQuery
import finki.ukim.erp.inventory.readmodel.ProductView
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.queryhandling.QueryGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Product management endpoints")
class ProductController(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
    private val productViewRepository: ProductViewRepository,
) {

    @PostMapping
    @Operation(summary = "Create a new product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Product created"),
            ApiResponse(responseCode = "400", description = "Invalid request"),
        ],
    )
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
    @Operation(summary = "List products with optional status filter and pagination")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Products listed"),
        ],
    )
    fun findAll(
        @Parameter(description = "Filter by product status") @RequestParam(required = false) status: ProductStatus?,
        @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") size: Int,
    ): Page<ProductView> {
        val pageable = PageRequest.of(page, size, Sort.by("productId"))
        return if (status != null) {
            val results = productViewRepository.findByStatus(status)
            PageImpl(results.toMutableList(), pageable, results.size.toLong())
        } else {
            productViewRepository.findAll(pageable)
        }
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get product by ID")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product found"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
    fun findById(@PathVariable productId: String): ResponseEntity<ProductView> {
        val view = queryById(productId)
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Full update of a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product updated"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
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

    @PatchMapping("/{productId}")
    @Operation(summary = "Partial update of a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product patched"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
    fun patch(
        @PathVariable productId: String,
        @RequestBody request: PatchProductRequest,
    ): ResponseEntity<ProductView> {
        val current = queryById(productId) ?: return ResponseEntity.notFound().build()
        val merged = UpdateProductRequest(
            sku = request.sku ?: current.sku,
            name = request.name ?: current.name,
            unitOfMeasure = request.unitOfMeasure ?: current.unitOfMeasure,
        )
        val before = current
        commandGateway.sendAndWait<Any>(
            UpdateProductCommand(
                productId = ProductId.fromString(productId),
                sku = Sku(merged.sku),
                name = ProductName(merged.name),
                unitOfMeasure = UnitOfMeasure(merged.unitOfMeasure),
            ),
        )
        val view = fetchWithRetry(changedFrom = before) { queryById(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "Soft-delete (deactivate) a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Product deactivated"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
    fun delete(@PathVariable productId: String): ResponseEntity<Void> {
        val before = queryById(productId) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(DeactivateProductCommand(ProductId.fromString(productId)))
        fetchWithRetry(changedFrom = before) { queryById(productId) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/by-sku/{sku}")
    @Operation(summary = "Lookup product by SKU")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product found"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
    fun findBySku(@PathVariable sku: String): ResponseEntity<ProductView> {
        val view = queryGateway.query(
            FindProductBySkuQuery(sku),
            ResponseTypes.instanceOf(ProductView::class.java),
        ).get()
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PostMapping("/{productId}/deactivate")
    @Operation(summary = "Deactivate a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product deactivated"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
    fun deactivate(@PathVariable productId: String): ResponseEntity<ProductView> {
        val before = queryById(productId)
        commandGateway.sendAndWait<Any>(DeactivateProductCommand(ProductId.fromString(productId)))
        val view = fetchWithRetry(changedFrom = before) { queryById(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PostMapping("/{productId}/reactivate")
    @Operation(summary = "Reactivate a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product reactivated"),
            ApiResponse(responseCode = "404", description = "Product not found"),
        ],
    )
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

data class PatchProductRequest(
    val sku: String? = null,
    val name: String? = null,
    val unitOfMeasure: String? = null,
)
