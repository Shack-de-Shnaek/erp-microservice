package finki.ukim.erp.orders.infrastructure.correlation

import org.slf4j.MDC

/**
 * The id that ties one user action together across every service it touches.
 *
 * Held in the SLF4J MDC rather than a bare thread local so it also lands in the log output for
 * free - a correlation id you cannot grep for in the logs is not tracing anything.
 */
object CorrelationId {

    const val HEADER = "X-Correlation-ID"

    private const val MDC_KEY = "correlationId"

    fun current(): String? = MDC.get(MDC_KEY)?.takeIf { it.isNotBlank() }

    fun set(value: String) = MDC.put(MDC_KEY, value)

    fun clear() = MDC.remove(MDC_KEY)
}
