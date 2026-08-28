package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.AdjustStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.DeleteStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.domain.stockitem.UpdateReorderThresholdCommand
import finki.ukim.erp.inventory.query.stockitem.FindAllStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindLowStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockSummaryQuery
import finki.ukim.erp.inventory.readmodel.StockItemView
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import finki.ukim.erp.inventory.readmodel.StockSummaryResponse
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stock")
@Tag(name = "Stock", description = "Stock management endpoints")
class StockItemController(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
    private val stockItemViewRepository: StockItemViewRepository,
) {

    @PostMapping
    @Operation(summary = "Create a stock record for a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Stock record created"),
            ApiResponse(responseCode = "400", description = "Invalid request"),
        ],
    )
    fun create(@RequestBody request: CreateStockItemRequest): ResponseEntity<StockItemView> {
        val command = CreateStockItemCommand(
            stockItemId = StockItemId.generate(),
            productRef = ProductRef(ProductId.fromString(request.productId)),
            onHand = Quantity(request.onHand),
            reorderThreshold = ReorderThreshold(request.reorderThreshold),
        )
        commandGateway.sendAndWait<Any>(command)
        val view = fetchWithRetry { queryByProductId(request.productId) }
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @GetMapping
    @Operation(summary = "List stock records with pagination")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Stock records listed"),
        ],
    )
    fun findAll(
        @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") size: Int,
    ): Page<StockItemView> {
        val pageable = PageRequest.of(page, size, Sort.by("productId"))
        return stockItemViewRepository.findAll(pageable)
    }

    @GetMapping("/low-stock")
    @Operation(summary = "Get items below reorder threshold")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Low stock items returned"),
        ],
    )
    fun lowStock(): List<StockItemView> =
        queryGateway.query(
            FindLowStockItemsQuery,
            ResponseTypes.multipleInstancesOf(StockItemView::class.java),
        ).get()

    @GetMapping("/{productId}")
    @Operation(summary = "Get stock record for a product")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Stock record found"),
            ApiResponse(responseCode = "404", description = "Stock record not found"),
        ],
    )
    fun findByProductId(@PathVariable productId: String): ResponseEntity<StockItemView> {
        val view = queryByProductId(productId)
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PatchMapping("/{productId}")
    @Operation(summary = "Update reorder threshold for a product's stock")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Reorder threshold updated"),
            ApiResponse(responseCode = "404", description = "Stock record not found"),
        ],
    )
    fun updateReorderThreshold(
        @PathVariable productId: String,
        @RequestBody request: UpdateReorderThresholdRequest,
    ): ResponseEntity<StockItemView> {
        val before = queryByProductId(productId)
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(
            UpdateReorderThresholdCommand(stockItemId, ReorderThreshold(request.reorderThreshold)),
        )
        return fetchUpdated(productId, before)
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "Remove a stock record")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Stock record removed"),
            ApiResponse(responseCode = "404", description = "Stock record not found"),
        ],
    )
    fun delete(@PathVariable productId: String): ResponseEntity<Void> {
        val before = queryByProductId(productId) ?: return ResponseEntity.notFound().build()
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(DeleteStockItemCommand(stockItemId))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{productId}/adjust")
    @Operation(summary = "Manually adjust stock quantity")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Stock adjusted"),
            ApiResponse(responseCode = "400", description = "Adjustment would make stock negative"),
            ApiResponse(responseCode = "404", description = "Stock record not found"),
        ],
    )
    fun adjust(
        @PathVariable productId: String,
        @RequestBody request: AdjustStockRequest,
    ): ResponseEntity<StockItemView> {
        val before = queryByProductId(productId)
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(
            AdjustStockCommand(stockItemId, request.adjustment, request.reason),
        )
        return fetchUpdated(productId, before)
    }

    @GetMapping("/summary")
    @Operation(summary = "Get aggregate stock statistics")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Stock summary returned"),
        ],
    )
    fun summary(): StockSummaryResponse =
        queryGateway.query(
            FindStockSummaryQuery,
            ResponseTypes.instanceOf(StockSummaryResponse::class.java),
        ).get()

    @PostMapping("/{productId}/reserve")
    fun reserve(
        @PathVariable productId: String,
        @RequestBody request: ReserveStockRequest,
    ): ResponseEntity<StockItemView> {
        val before = queryByProductId(productId)
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(
            ReserveStockCommand(stockItemId, request.orderRef, Quantity(request.quantity)),
        )
        return fetchUpdated(productId, before)
    }

    @PostMapping("/{productId}/release")
    fun release(
        @PathVariable productId: String,
        @RequestBody request: ReleaseReservationRequest,
    ): ResponseEntity<StockItemView> {
        val before = queryByProductId(productId)
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(ReleaseReservationCommand(stockItemId, request.orderRef))
        return fetchUpdated(productId, before)
    }

    @PostMapping("/{productId}/confirm")
    fun confirm(
        @PathVariable productId: String,
        @RequestBody request: ConfirmStockRequest,
    ): ResponseEntity<StockItemView> {
        val before = queryByProductId(productId)
        val stockItemId = stockItemIdFrom(before) ?: return ResponseEntity.notFound().build()
        commandGateway.sendAndWait<Any>(ConfirmStockCommand(stockItemId, request.orderRef))
        return fetchUpdated(productId, before)
    }

    private fun stockItemIdFrom(view: StockItemView?): StockItemId? =
        view?.let { StockItemId.fromString(it.stockItemId) }

    private fun queryByProductId(productId: String): StockItemView? =
        queryGateway.query(
            FindStockItemByProductIdQuery(productId),
            ResponseTypes.instanceOf(StockItemView::class.java),
        ).get()

    private fun fetchUpdated(productId: String, before: StockItemView?): ResponseEntity<StockItemView> {
        val view = fetchWithRetry(changedFrom = before) { queryByProductId(productId) }
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }
}

data class CreateStockItemRequest(
    val productId: String,
    val onHand: Int,
    val reorderThreshold: Int,
)

data class AdjustStockRequest(
    val adjustment: Int,
    val reason: String,
)

data class ReserveStockRequest(
    val orderRef: String,
    val quantity: Int,
)

data class ReleaseReservationRequest(
    val orderRef: String,
)

data class ConfirmStockRequest(
    val orderRef: String,
)

data class UpdateReorderThresholdRequest(
    val reorderThreshold: Int,
)
