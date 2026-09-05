package finki.ukim.erp.orders.exceptions

import org.axonframework.modelling.command.AggregateNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(val status: Int, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(
        OrderNotFoundException::class,
        InvoiceNotFoundException::class,
        TransactionNotFoundException::class,
        ProductNotFoundException::class
    )
    fun handleNotFound(ex: RuntimeException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(HttpStatus.NOT_FOUND.value(), ex.message ?: ""))

    /**
     * Axon raises this when a command is routed to an aggregate that has no events - i.e. the
     * order id in the URL does not exist.
     */
    @ExceptionHandler(AggregateNotFoundException::class)
    fun handleUnknownAggregate(ex: AggregateNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError(HttpStatus.NOT_FOUND.value(), "Order with id ${ex.aggregateIdentifier} not found"))

    @ExceptionHandler(InventoryUnavailableException::class)
    fun handleInventoryUnavailable(ex: InventoryUnavailableException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.message ?: ""))

    @ExceptionHandler(
        InsufficientStockException::class,
        InvalidOrderStateException::class,
        OverpaymentException::class,
        InvoiceAlreadyExistsException::class,
        InvoiceAlreadyReversedException::class
    )
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(HttpStatus.BAD_REQUEST.value(), ex.message ?: ""))

    @ExceptionHandler(OrderNotOwnedException::class)
    fun handleForbidden(ex: RuntimeException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError(HttpStatus.FORBIDDEN.value(), ex.message ?: ""))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(HttpStatus.BAD_REQUEST.value(), message))
    }
}
