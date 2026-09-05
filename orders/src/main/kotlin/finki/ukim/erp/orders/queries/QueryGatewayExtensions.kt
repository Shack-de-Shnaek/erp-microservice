package finki.ukim.erp.orders.queries

import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * `CompletableFuture.join()` wraps whatever the query handler threw in a [CompletionException].
 * Unwrapping it keeps the domain exception - and so the HTTP status
 * [finki.ukim.erp.orders.exceptions.GlobalExceptionHandler] maps it to - intact.
 *
 * Read endpoints still return their data directly; the future never leaves this file.
 */
fun <R> CompletableFuture<R>.awaitResult(): R =
    try {
        join()
    } catch (ex: CompletionException) {
        throw ex.cause ?: ex
    }

fun <R : Any> QueryGateway.queryForOne(query: Any, responseType: Class<R>): R =
    query(query, responseType).awaitResult()

fun <R : Any> QueryGateway.queryForMany(query: Any, responseType: Class<R>): List<R> =
    query(query, ResponseTypes.multipleInstancesOf(responseType)).awaitResult()
