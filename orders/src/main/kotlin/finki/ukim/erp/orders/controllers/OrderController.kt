package finki.ukim.erp.orders.controllers

import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.dto.CreateOrderRequest
import finki.ukim.erp.orders.dto.UpdateOrderItemsRequest
import finki.ukim.erp.orders.queries.FindAllOrdersQuery
import finki.ukim.erp.orders.queries.FindOrderByIdQuery
import finki.ukim.erp.orders.queries.FindOrdersByCustomerQuery
import finki.ukim.erp.orders.queries.FindOrdersByStatusQuery
import finki.ukim.erp.orders.queries.queryForMany
import finki.ukim.erp.orders.queries.queryForOne
import finki.ukim.erp.orders.services.OrderCommandService
import finki.ukim.erp.orders.views.OrderView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.axonframework.queryhandling.QueryGateway
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Writes go out as commands, reads come back as queries - the two never share a path, and no read
 * endpoint here can change anything.
 *
 * Ids arrive as their string form ("Order:9f1c...") and are wrapped in [OrderId] on the way in, so
 * nothing past this line handles a bare string that could have come from anywhere.
 */
@RestController
@RequestMapping("/orders")
@Tag(
    name = "Orders",
    description = "Placing, amending and deciding on orders. Reads are served from the projected " +
        "read model; writes are dispatched as commands to the order aggregate."
)
class OrderController(
    private val orderCommandService: OrderCommandService,
    private val queryGateway: QueryGateway
) {

    // ------------------------------------------------------------------ reads

    @Operation(
        summary = "List every order",
        description = "Returns the full read model, newest projection state included. Intended for " +
            "back-office screens; a customer looking for their own orders should use /orders/mine."
    )
    @GetMapping("/all")
    fun findAll(): List<OrderView> =
        queryGateway.queryForMany(FindAllOrdersQuery(), OrderView::class.java)

    @Operation(
        summary = "Fetch one order",
        description = "Looks the order up by its string id (\"Order:9f1c...\"). Responds 404 when no " +
            "order with that id has been projected."
    )
    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: String): OrderView =
        queryGateway.queryForOne(FindOrderByIdQuery(OrderId(id)), OrderView::class.java)

    @Operation(
        summary = "List orders in a given status",
        description = "Filters the read model by lifecycle status (PENDING, APPROVED, REJECTED, " +
            "CANCELLED, ...). Use it to work a queue - for example every PENDING order awaiting approval."
    )
    @GetMapping("/by-status/{status}")
    fun findByStatus(@PathVariable status: OrderStatus): List<OrderView> =
        queryGateway.queryForMany(FindOrdersByStatusQuery(status), OrderView::class.java)

    @Operation(
        summary = "List the caller's own orders",
        description = "Returns the orders placed by the customer the bearer token identifies. Takes no " +
            "customer parameter on purpose: the subject comes from the JWT, so one customer cannot read another's."
    )
    @GetMapping("/mine")
    fun getMyOrders(@AuthenticationPrincipal jwt: Jwt): List<OrderView> =
        queryGateway.queryForMany(FindOrdersByCustomerQuery(jwt.subject), OrderView::class.java)

    // ------------------------------------------------------------------ writes

    @Operation(
        summary = "Place an order",
        description = "Validates every line against the inventory service - the product must exist and " +
            "cover the requested quantity - then creates a PENDING order priced at what inventory quoted. " +
            "The customer is taken from the token, not the body."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrder(@Valid @RequestBody request: CreateOrderRequest, @AuthenticationPrincipal jwt: Jwt): OrderView =
        orderCommandService.createOrder(request.name, request.surname, jwt.subject, request.items)

    @Operation(
        summary = "Amend an order's lines",
        description = "Replaces the order's lines wholesale and reprices them against inventory. Allowed " +
            "while the order is pending or approved, and only until an invoice has been issued for it."
    )
    @PutMapping("/{id}/items")
    fun updateOrderItems(@PathVariable id: String, @Valid @RequestBody request: UpdateOrderItemsRequest): OrderView =
        orderCommandService.updateOrderItems(OrderId(id), request.items)

    @Operation(
        summary = "Approve an order",
        description = "Re-checks that the stock the order was accepted on is still there, then approves " +
            "it and announces order.approved so inventory can commit the goods. Administrators only."
    )
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    fun approveOrder(@PathVariable id: String): OrderView = orderCommandService.approveOrder(OrderId(id))

    @Operation(
        summary = "Reject an order",
        description = "Closes a pending order without fulfilling it: nothing is committed and no invoice " +
            "follows. Only a pending order can be rejected. Administrators only."
    )
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    fun rejectOrder(@PathVariable id: String): OrderView = orderCommandService.rejectOrder(OrderId(id))

    @Operation(
        summary = "Cancel an order",
        description = "Withdraws a pending or approved order so anything reserved against it can be " +
            "released; money already taken on an approved order is reversed. The token's subject must " +
            "match the order's customer, so one customer cannot cancel another's order."
    )
    @PostMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: String, @AuthenticationPrincipal jwt: Jwt): OrderView =
        orderCommandService.cancelOrder(OrderId(id), jwt.subject)
}
