package finki.ukim.erp.inventory.web

import org.axonframework.commandhandling.CommandExecutionException
import org.axonframework.modelling.command.AggregateNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** The body every failed request answers with, so a caller never has to guess from a bare status. */
data class ApiError(val status: Int, val message: String)

/**
 * Turns the refusals this service makes into statuses and messages a caller can act on.
 *
 * Without it every one of them was a bare 500 with an empty body - the aggregate's `check` for
 * insufficient stock, a value object's `require`, the product-status guard - and the endpoints
 * documented "400 Invalid request or insufficient stock" that they never actually returned. All of
 * these are the caller asking for something the domain will not do, which is a 400, and the
 * *reason* is the useful half: "Insufficient stock: 10 on hand, 8 reserved, 5 requested" tells
 * whoever is calling exactly what to change.
 *
 * A 404 is reserved for what it means - a resource that is not there - and is produced by the
 * controllers themselves, never from here. Nothing that is merely invalid comes back as 404.
 *
 * ## Unwrapping
 *
 * Axon wraps whatever a command handler threw in a [CommandExecutionException] whenever the
 * dispatch crosses a boundary that cannot carry the original type. The wrapper says nothing worth
 * reporting, so the cause is what gets mapped, and the same mapping then covers both the wrapped
 * and unwrapped forms rather than depending on which bus is in play.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CommandExecutionException::class)
    fun handleCommandFailure(ex: CommandExecutionException): ResponseEntity<ApiError> =
        when (val cause = ex.cause) {
            is IllegalStateException, is IllegalArgumentException -> badRequest(cause as RuntimeException)
            is AggregateNotFoundException -> handleMissingAggregate(cause)
            else -> unexpected(ex)
        }

    /**
     * The domain refusing: an aggregate's `check` (insufficient stock, a negative adjustment) or a
     * value object's `require` (a blank SKU, a negative quantity).
     */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    fun handleDomainRefusal(ex: RuntimeException): ResponseEntity<ApiError> = badRequest(ex)

    /** A command addressed to a stock item or product that does not exist. */
    @ExceptionHandler(AggregateNotFoundException::class)
    fun handleMissingAggregate(ex: Throwable): ResponseEntity<ApiError> {
        val id = (ex as? AggregateNotFoundException)?.aggregateIdentifier
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError(HttpStatus.NOT_FOUND.value(), "No aggregate with id $id exists"))
    }

    private fun badRequest(ex: RuntimeException): ResponseEntity<ApiError> {
        log.info("Rejecting request: {}", ex.message)
        return ResponseEntity.badRequest()
            .body(ApiError(HttpStatus.BAD_REQUEST.value(), ex.message ?: "The request was rejected"))
    }

    private fun unexpected(ex: Throwable): ResponseEntity<ApiError> {
        log.error("Unhandled failure serving a request", ex)
        return ResponseEntity.internalServerError()
            .body(ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Unexpected failure"))
    }
}
