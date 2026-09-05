package finki.ukim.erp.orders.clients.interceptors

import feign.RequestInterceptor
import feign.RequestTemplate
import finki.ukim.erp.orders.infrastructure.correlation.CorrelationId
import java.util.UUID

/**
 * Stamps every outgoing Feign request with the correlation id of the request that caused it, so a
 * single id follows one user action through orders, inventory, and anything either of them calls.
 *
 * If nothing set an id - a scheduled job, a Kafka-driven reaction, anything not started by an
 * inbound HTTP request - one is minted here rather than sending the call untagged.
 */
class CorrelationIdInterceptor : RequestInterceptor {

    override fun apply(requestTemplate: RequestTemplate) {
        // An id already on the template was put there deliberately by the caller; do not overwrite it.
        if (requestTemplate.headers().containsKey(CorrelationId.HEADER)) {
            return
        }
        requestTemplate.header(CorrelationId.HEADER, CorrelationId.current() ?: UUID.randomUUID().toString())
    }
}
