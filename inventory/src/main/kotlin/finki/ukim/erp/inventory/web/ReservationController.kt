package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ReserveStockCommand
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import finki.ukim.erp.inventory.query.reservation.FindAllReservationsQuery
import finki.ukim.erp.inventory.query.reservation.FindReservationByOrderRefQuery
import finki.ukim.erp.inventory.query.stockitem.FindStockItemByProductIdQuery
import finki.ukim.erp.inventory.readmodel.ReservationView
import finki.ukim.erp.inventory.readmodel.ReservationViewRepository
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
) {

    @PostMapping
    @Operation(summary = "Reserve stock for an order (single order, multiple lines)")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Stock reserved"),
            ApiResponse(responseCode = "400", description = "Invalid request or insufficient stock"),
        ],
    )
    fun create(@RequestBody request: CreateReservationRequest): ResponseEntity<ReservationView> {
        for (line in request.lines) {
            val stockItemView = queryGateway.query(
                FindStockItemByProductIdQuery(line.productId),
                ResponseTypes.instanceOf(finki.ukim.erp.inventory.readmodel.StockItemView::class.java),
            ).get() ?: return ResponseEntity.badRequest().build()

            val stockItemId = StockItemId.fromString(stockItemView.stockItemId)
            commandGateway.sendAndWait<Any>(
                ReserveStockCommand(stockItemId, request.orderRef, Quantity(line.quantity)),
            )
        }
        val reservation = fetchWithRetry {
            queryGateway.query(
                FindReservationByOrderRefQuery(request.orderRef),
                ResponseTypes.instanceOf(ReservationView::class.java),
            ).get()
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation)
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

        for (line in reservation.lines) {
            val stockItemView = queryGateway.query(
                FindStockItemByProductIdQuery(line.productId),
                ResponseTypes.instanceOf(finki.ukim.erp.inventory.readmodel.StockItemView::class.java),
            ).get() ?: continue

            val stockItemId = StockItemId.fromString(stockItemView.stockItemId)
            commandGateway.sendAndWait<Any>(ReleaseReservationCommand(stockItemId, orderRef))
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

        for (line in reservation.lines) {
            val stockItemView = queryGateway.query(
                FindStockItemByProductIdQuery(line.productId),
                ResponseTypes.instanceOf(finki.ukim.erp.inventory.readmodel.StockItemView::class.java),
            ).get() ?: continue

            val stockItemId = StockItemId.fromString(stockItemView.stockItemId)
            commandGateway.sendAndWait<Any>(ConfirmStockCommand(stockItemId, orderRef))
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

data class CreateReservationRequest(
    val orderRef: String,
    val lines: List<ReservationLineRequest>,
)

data class ReservationLineRequest(
    val productId: String,
    val quantity: Int,
)
