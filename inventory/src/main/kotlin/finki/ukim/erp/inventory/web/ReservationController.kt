package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.stockitem.AmendReservationCommand
import finki.ukim.erp.inventory.domain.stockitem.ConfirmStockCommand
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.domain.stockitem.Quantity
import finki.ukim.erp.inventory.domain.stockitem.ReleaseReason
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * An order's hold on stock, over HTTP.
 *
 * This is the endpoint orders actually calls, and the roles reflect who can be behind such a call:
 * CUSTOMER, because a customer placing an order through the gateway has their token relayed here
 * by orders; SERVICE, because orders also reserves and releases off the back of Kafka events,
 * where there is no user and it uses its own service-account token; ADMIN, because an
 * administrator can do either by hand. Reads need a token only.
 */
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE', 'CUSTOMER')")
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

    @PutMapping("/{orderRef}")
    @Operation(
        summary = "Amend an order's reservation to a new set of lines",
        description = "Takes the lines the order should now hold and moves the reservation to " +
            "them in one operation: quantities that changed are resized, products no longer on " +
            "the order are released, and products newly on it are reserved. The order never " +
            "gives up stock it is keeping, so raising a line contests only the increase and an " +
            "unchanged line cannot be lost to another order at all. All of it applies or none " +
            "of it does.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Reservation amended"),
            ApiResponse(responseCode = "400", description = "Invalid request, unknown or inactive product, or insufficient stock"),
            ApiResponse(responseCode = "404", description = "This order holds no reservation to amend"),
        ],
    )
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE', 'CUSTOMER')")
    fun amend(
        @PathVariable orderRef: String,
        @RequestBody request: AmendReservationRequest,
    ): ResponseEntity<ReservationView> {
        val existing = reservationViewRepository.findByOrderRef(orderRef)
            // A genuine 404: there is nothing to amend, and saying so is the only honest answer.
            // Reserving the lines instead would create a hold the caller never asked to open.
            ?: return ResponseEntity.notFound().build()

        // A fast path, not the guarantee: this reads the reservation projection, which can still
        // say ACTIVE for a moment after the goods have gone out. `StockItem` refuses a confirmed
        // hold without lag and says the same thing, so the race costs a wasted dispatch, not a
        // wrong answer.
        require(existing.status != "CONFIRMED") {
            "Order $orderRef has already been confirmed and its reservation can no longer be amended"
        }

        val plan = planned(orderRef, request, existing)

        // Nothing to do, and the caller is told the same thing it would be told after doing it.
        if (plan.isEmpty()) {
            return ResponseEntity.ok(existing)
        }

        execute(orderRef, plan)

        val amended = fetchWithRetry {
            queryGateway.query(
                FindReservationByOrderRefQuery(orderRef),
                ResponseTypes.instanceOf(ReservationView::class.java),
            ).get()
        }
        return if (amended == null) ResponseEntity.notFound().build() else ResponseEntity.ok(amended)
    }

    /**
     * One step of an amendment: what to do to a single stock item, and how to put it back.
     *
     * Each step carries its own undo rather than the unwinder working it out afterwards, because
     * only the step knows what the item was holding before it ran - and by the time an unwind is
     * needed, the read model it would otherwise have to ask has already moved.
     */
    private data class AmendmentStep(
        val stockItemId: StockItemId,
        val perform: () -> Unit,
        val undo: () -> Unit,
    )

    /**
     * Works out the whole amendment before any of it runs.
     *
     * Everything knowably wrong is refused here, off the read model, so the ordinary rejections -
     * an unknown product, a withdrawn one, not enough on the shelf - never leave a half-amended
     * reservation behind. What survives this is a plan whose steps are expected to succeed, so
     * [execute] unwinding is the rare path rather than the usual one.
     *
     * As with [validated], this does not replace the aggregate's own check. Between here and the
     * command another order can take the stock, and `StockItem` is the only thing that decides
     * that for real.
     */
    private fun planned(
        orderRef: String,
        request: AmendReservationRequest,
        existing: ReservationView,
    ): List<AmendmentStep> {
        require(request.lines.isNotEmpty()) {
            "An amendment must leave at least one line; release the reservation instead"
        }
        val duplicates = request.lines.groupingBy { it.productId }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "A product may appear only once per reservation; repeated: ${duplicates.joinToString()}"
        }

        val heldByProduct = existing.lines.associateBy { it.productId }
        val wantedByProduct = request.lines.associateBy { it.productId }

        val steps = mutableListOf<AmendmentStep>()

        request.lines.forEach { line ->
            require(line.quantity >= 1) {
                "Quantity for product ${line.productId} must be at least 1, was ${line.quantity}"
            }
            val held = heldByProduct[line.productId]
            if (held != null && held.quantity == line.quantity) {
                // Untouched, and deliberately not re-sent. A line nobody is changing should not be
                // able to fail an amendment, and should not cost a command to leave alone.
                return@forEach
            }

            val increase = line.quantity - (held?.quantity ?: 0)
            val stockItem = checkedStockItem(line.productId, increase)

            steps += if (held == null) {
                val stockItemId = StockItemId.fromString(stockItem.stockItemId)
                AmendmentStep(
                    stockItemId = stockItemId,
                    perform = { send(ReserveStockCommand(stockItemId, orderRef, Quantity(line.quantity))) },
                    undo = { send(ReleaseReservationCommand(stockItemId, orderRef)) },
                )
            } else {
                val stockItemId = StockItemId.fromString(held.stockItemId)
                AmendmentStep(
                    stockItemId = stockItemId,
                    perform = { send(AmendReservationCommand(stockItemId, orderRef, Quantity(line.quantity))) },
                    undo = { send(AmendReservationCommand(stockItemId, orderRef, Quantity(held.quantity))) },
                )
            }
        }

        existing.lines.filterNot { wantedByProduct.containsKey(it.productId) }.forEach { dropped ->
            val stockItemId = StockItemId.fromString(dropped.stockItemId)
            steps += AmendmentStep(
                stockItemId = stockItemId,
                perform = { send(ReleaseReservationCommand(stockItemId, orderRef)) },
                undo = { send(ReserveStockCommand(stockItemId, orderRef, Quantity(dropped.quantity))) },
            )
        }

        return steps
    }

    /**
     * The stock item behind a product, having checked it can cover [increase] more than the order
     * already holds.
     *
     * [increase] is what is judged, not the line's total: the order is not giving back what it is
     * keeping, so only the difference has to be found on the shelf. A line that is coming down
     * passes a negative increase and is checked for nothing beyond existing - including its
     * product's status, since lowering or dropping a withdrawn product is exactly what an order
     * holding one should be able to do.
     */
    private fun checkedStockItem(productId: String, increase: Int): StockItemView {
        if (increase > 0) {
            val product = productViewRepository.findById(productId).orElse(null)
            requireNotNull(product) { "No product with id $productId exists" }
            require(product.status == ProductStatus.ACTIVE) {
                "Product $productId is ${product.status}: stock cannot be reserved for it"
            }
        }

        val stockItem = stockItemViewRepository.findByProductId(productId)
        requireNotNull(stockItem) { "No stock is tracked for product $productId" }

        if (increase > 0) {
            val available = stockItem.onHand - stockItem.reserved
            require(available >= increase) {
                "Insufficient stock for product $productId: $increase more requested, " +
                    "$available available (${stockItem.onHand} on hand, ${stockItem.reserved} reserved)"
            }
        }
        return stockItem
    }

    /**
     * Runs the plan, and puts back what it managed to do if any of it fails.
     *
     * The same shape as [create]: the caller asked for one amendment, not for whichever part of it
     * happened to fit, so a step that fails takes the earlier ones with it. Undoing runs in reverse,
     * which matters when two lines touch the same stock item - they cannot today, since a product
     * appears once, but the order is what makes that a property of the plan rather than luck.
     */
    private fun execute(orderRef: String, plan: List<AmendmentStep>) {
        val done = mutableListOf<AmendmentStep>()
        try {
            plan.forEach { step ->
                step.perform()
                done += step
            }
        } catch (failure: RuntimeException) {
            done.asReversed().forEach { step ->
                runCatching { step.undo() }.onFailure {
                    log.error(
                        "Could not undo {} while unwinding the amendment of order {}; the reservation " +
                            "is now partly amended",
                        step.stockItemId.value,
                        orderRef,
                        it,
                    )
                }
            }
            throw failure
        }
    }

    private fun send(command: Any) {
        commandGateway.sendAndWait<Any>(command)
    }

    @GetMapping
    @Operation(
        summary = "List reservations with pagination",
        description = "Every reservation the projection still holds: those being held for an order, " +
            "and those already confirmed but whose goods have not been returned. A released " +
            "reservation is removed outright and is not listed.",
    )
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE', 'CUSTOMER')")
    fun release(@PathVariable orderRef: String): ResponseEntity<Void> {
        val reservation = queryGateway.query(
            FindReservationByOrderRefQuery(orderRef),
            ResponseTypes.instanceOf(ReservationView::class.java),
        ).get() ?: return ResponseEntity.notFound().build()

        // WITHDRAWN_BY_INVENTORY: nobody on the order's side asked for this. The order still
        // believes it is backed by goods, and the reason travels out on the event so it can be told.
        reservation.lines.forEach { line ->
            commandGateway.sendAndWait<Any>(
                ReleaseReservationCommand(
                    StockItemId.fromString(line.stockItemId),
                    orderRef,
                    ReleaseReason.WITHDRAWN_BY_INVENTORY,
                ),
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE', 'CUSTOMER')")
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

/**
 * The body of `PUT /api/reservations/{orderRef}`: every line the order should hold once the
 * amendment is done, not the ones that changed. Stating the whole set is what lets one call cover
 * a raised line, a dropped one and an added one together, and what makes sending it twice leave
 * the same reservation rather than moving the shelf twice.
 */
data class AmendReservationRequest(
    val lines: List<ReservationLineRequest>,
)
