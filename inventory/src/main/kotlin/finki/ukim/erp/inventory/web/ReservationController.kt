package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.reservation.FindAllReservationsQuery
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.ReservationView
import finki.ukim.erp.inventory.readmodel.ReservationViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemView
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reservations")
@Tag(name = "Reservations", description = "Reservation management endpoints")
class ReservationController(
    private val commandGateway: CommandGateway,
    private val queryGateway: QueryGateway,
    private val reservationViewRepository: ReservationViewRepository,
    private val stockItemViewRepository: StockItemViewRepository,
    private val productViewRepository: ProductViewRepository,
) {

    @PostMapping
    @Operation(
        summary = "Reserve stock for an order (single order, multiple lines)",
        description = "Takes the whole order's stock in one call, or takes none of it. Every line " +
            "is checked before any is reserved - the product must exist, be active, and have " +
            "enough on hand that is not already spoken for - and a line that fails after earlier " +
            "ones succeeded releases them again.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Stock reserved"),
            ApiResponse(responseCode = "400", description = "Invalid request, unknown or inactive product, or insufficient stock"),
        ],
    )
    fun create(@RequestBody request: CreateReservationRequest): ResponseEntity<ReservationView> {
        val lines = validated(request)

        // Reserved one stock item at a time, because that is how the aggregate boundary runs: each
        // hold is a decision by one StockItem about its own goods. Which is exactly why the failure
        // of a later line has to undo the earlier ones - the caller asked for an order's worth of
        // stock, not for whatever part of it happened to be available.
        val reserved = mutableListOf<StockItemId>()
        try {
            lines.forEach { (stockItem, quantity) ->
                val stockItemId = StockItemId.fromString(stockItem.stockItemId)
                commandGateway.sendAndWait<Any>(
                    ReserveStockCommand(stockItemId, request.orderRef, Quantity(quantity)),
                )
                reserved += stockItemId
            }
        } catch (failure: RuntimeException) {
            releaseAll(reserved, request.orderRef)
            throw failure
        }

        val reservation = fetchWithRetry {
            queryGateway.query(
                FindReservationByOrderRefQuery(request.orderRef),
                ResponseTypes.instanceOf(ReservationView::class.java),
            ).get()
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation)
    }

    /**
     * Everything that can be known to be wrong before a single hold is taken, checked here so the
     * common refusals never leave a half-reserved order behind.
     *
     * It is not a substitute for the aggregate's own check: between this and the command another
     * order can take the last of the stock, and `StockItem` is the only thing that decides that
     * for real. What this buys is a clear message naming the offending product, and the ordinary
     * "not enough left" case being refused without any compensation having to run.
     */
    private fun validated(request: CreateReservationRequest): List<Pair<StockItemView, Int>> {
        require(request.orderRef.isNotBlank()) { "orderRef must not be blank" }
        require(request.lines.isNotEmpty()) { "A reservation must have at least one line" }

        val duplicates = request.lines.groupingBy { it.productId }.eachCount().filterValues { it > 1 }.keys
        // Two lines for one product would look like two holds and become one: the aggregate treats
        // a repeat reservation under the same order reference as a no-op, so the second quantity
        // would be silently dropped rather than added.
        require(duplicates.isEmpty()) {
            "A product may appear only once per reservation; repeated: ${duplicates.joinToString()}"
        }

        require(reservationViewRepository.findByOrderRef(request.orderRef) == null) {
            "Order ${request.orderRef} already holds a reservation; release it before reserving again"
        }

        return request.lines.map { line ->
            require(line.quantity >= 1) {
                "Quantity for product ${line.productId} must be at least 1, was ${line.quantity}"
            }

            val product = productViewRepository.findById(line.productId).orElse(null)
            requireNotNull(product) { "No product with id ${line.productId} exists" }
            require(product.status == ProductStatus.ACTIVE) {
                "Product ${line.productId} is ${product.status}: stock cannot be reserved for it"
            }

            val stockItem = stockItemViewRepository.findByProductId(line.productId)
            requireNotNull(stockItem) { "No stock is tracked for product ${line.productId}" }

            val available = stockItem.onHand - stockItem.reserved
            require(available >= line.quantity) {
                "Insufficient stock for product ${line.productId}: ${line.quantity} requested, " +
                    "$available available (${stockItem.onHand} on hand, ${stockItem.reserved} reserved)"
            }

            stockItem to line.quantity
        }
    }

    /** Undoes the holds taken so far. Best effort: the failure being compensated is the one to report. */
    private fun releaseAll(stockItemIds: List<StockItemId>, orderRef: String) {
        stockItemIds.forEach { stockItemId ->
            runCatching { commandGateway.sendAndWait<Any>(ReleaseReservationCommand(stockItemId, orderRef)) }
                .onFailure { log.error("Could not release {} while unwinding order {}", stockItemId, orderRef, it) }
        }
    }

    @GetMapping
    @Operation(summary = "List active reservations with pagination")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Reservations listed"),
        ],
    )
    fun findAll(
        @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") size: Int,
    ): Page<ReservationView> {
        val pageable = PageRequest.of(page, size)
        val all = reservationViewRepository.findAll()
        val start = (page * size).coerceAtMost(all.size)
        val end = (start + size).coerceAtMost(all.size)
        val paged = all.subList(start, end)
        return PageImpl(paged, pageable, all.size.toLong())
    }

    @GetMapping("/{orderRef}")
    @Operation(summary = "Get reservation by order reference")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Reservation found"),
            ApiResponse(responseCode = "404", description = "Reservation not found"),
        ],
    )
    fun findByOrderRef(@PathVariable orderRef: String): ResponseEntity<ReservationView> {
        val view = queryGateway.query(
            FindReservationByOrderRefQuery(orderRef),
            ResponseTypes.instanceOf(ReservationView::class.java),
        ).get()
        return if (view == null) ResponseEntity.notFound().build() else ResponseEntity.ok(view)
    }

    @DeleteMapping("/{orderRef}")
    @Operation(summary = "Release a reservation")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Reservation released"),
            ApiResponse(responseCode = "404", description = "Reservation not found"),
        ],
    )
    fun release(@PathVariable orderRef: String): ResponseEntity<Void> {
        val reservation = queryGateway.query(
            FindReservationByOrderRefQuery(orderRef),
            ResponseTypes.instanceOf(ReservationView::class.java),
        ).get() ?: return ResponseEntity.notFound().build()

        reservation.lines.forEach { line ->
            commandGateway.sendAndWait<Any>(
                ReleaseReservationCommand(StockItemId.fromString(line.stockItemId), orderRef),
            )
        }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{orderRef}/confirm")
    @Operation(summary = "Confirm (fulfill) a reservation")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Reservation confirmed"),
            ApiResponse(responseCode = "404", description = "Reservation not found"),
        ],
    )
    fun confirm(@PathVariable orderRef: String): ResponseEntity<ReservationView> {
        val reservation = queryGateway.query(
            FindReservationByOrderRefQuery(orderRef),
            ResponseTypes.instanceOf(ReservationView::class.java),
        ).get() ?: return ResponseEntity.notFound().build()

        reservation.lines.forEach { line ->
            commandGateway.sendAndWait<Any>(
                ConfirmStockCommand(StockItemId.fromString(line.stockItemId), orderRef),
            )
        }

        val updated = fetchWithRetry {
            queryGateway.query(
                FindReservationByOrderRefQuery(orderRef),
                ResponseTypes.instanceOf(ReservationView::class.java),
            ).get()
        }
        return if (updated == null) ResponseEntity.notFound().build() else ResponseEntity.ok(updated)
    }
}

private val log = org.slf4j.LoggerFactory.getLogger(ReservationController::class.java)

data class CreateReservationRequest(
    val orderRef: String,
    val lines: List<ReservationLineRequest>,
)

data class ReservationLineRequest(
    val productId: String,
    val quantity: Int,
)
