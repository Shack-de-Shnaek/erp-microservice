package finki.ukim.erp.orders.infrastructure.correlation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * The inbound half of correlation. Without it the outbound interceptor would mint a fresh id for
 * every call this service makes, and each hop of a request would carry a different one - which
 * traces nothing.
 *
 * So: adopt the id the caller sent, or start one if this service is where the request began, and
 * echo it back on the response so whoever is holding the failed request can quote it.
 *
 * Ordered first, so the id is set before anything else - including security - has a chance to
 * reject the request and log about it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val correlationId = request.getHeader(CorrelationId.HEADER)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        CorrelationId.set(correlationId)
        response.setHeader(CorrelationId.HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // Servlet threads are pooled; leaving the id behind would stamp it on the next,
            // unrelated request that happened to land on this thread.
            CorrelationId.clear()
        }
    }
}
