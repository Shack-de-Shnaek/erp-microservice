package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.AdjustStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.CreateStockItemCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReorderThreshold
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.stockitem.FindAllStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindLowStockItemsQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.readmodel.StockItemView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.queryhandling.QueryGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stock")
class StockItemController(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
) {

    @PostMapping
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
    fun findAll(): List<StockItemView> =
        queryGateway.query(
            FindAllStockItemsQuery,
            ResponseTypes.multipleInstancesOf(StockItemView::class.java),
        ).get()

    @GetMapping("/low-stock")
    fun lowStock(): List<StockItemView> =
        queryGateway.query(
            FindLowStockItemsQuery,
            ResponseTypes.multipleInstancesOf(StockItemView::class.java),
        ).get()

    @GetMapping("/{productId}")
    fun findByProductId(@PathVariable productId: String): ResponseEntity<StockItemView> {
        val view = queryByProductId(productId)
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @PostMapping("/{productId}/adjust")
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