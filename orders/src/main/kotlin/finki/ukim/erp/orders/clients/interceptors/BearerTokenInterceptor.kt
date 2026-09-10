package finki.ukim.erp.orders.clients.interceptors

import feign.RequestInterceptor
import feign.RequestTemplate
import finki.ukim.erp.orders.config.ServiceTokenSource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Puts a Keycloak access token on every outgoing Feign call, so inventory can authenticate the
 * caller the same way the gateway authenticated it.
 *
 * Inventory is a resource server now: an unauthenticated call to `/api/reservations` is a 401, and
 * a reservation that silently fails to be taken is an order that believes it has stock it does not
 * have. So this is not an enhancement to the call - it is part of it.
 *
 * ## Which token
 *
 * Two cases, and the order between them matters:
 *
 * 1. **The caller's own token, relayed.** A request that reached this service carrying a JWT (which
 *    is every request through the gateway, whose `TokenRelay` filter attaches the logged-in user's
 *    access token) has that same token forwarded. Inventory then sees the customer who placed the
 *    order rather than a service acting on their behalf, which is what keeps its
 *    `hasAnyRole('ADMIN', 'SERVICE', 'CUSTOMER')` on the reservation endpoints meaningful and
 *    keeps one identity attached to the whole user action, the way the correlation id keeps one
 *    trace attached to it.
 * 2. **This service's own token.** Plenty of calls to inventory are not on any user's behalf: a
 *    Kafka event that withdraws a product makes this service re-check the orders holding it, and
 *    the release that follows has no request and no user behind it. Those authenticate as the
 *    `erp-orders` service account, which holds SERVICE. See [ServiceTokenSource].
 *
 * A token already on the template is left alone - it was put there deliberately by whoever built
 * the request.
 *
 * ## When neither is available
 *
 * The call goes out unauthenticated rather than failing here. That is for the tests, which drive
 * the real Feign client against a stub inventory with no Keycloak anywhere in sight
 * ([ServiceTokenSource] is absent when no client registration is configured, which is exactly that
 * situation). In a deployment the registration is always configured, so the only way to reach this
 * branch is a misconfiguration - and inventory answering 401 says so far more clearly than an
 * exception thrown from inside an interceptor, which surfaces as a circuit-breaker fallback and
 * looks like inventory being down.
 */
class BearerTokenInterceptor(
    private val serviceTokens: ServiceTokenSource?
) : RequestInterceptor {

    override fun apply(requestTemplate: RequestTemplate) {
        if (requestTemplate.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
            return
        }

        val token = relayedToken() ?: serviceTokens?.accessToken()
        if (token == null) {
            log.debug(
                "No token available for {} {}; sending it unauthenticated",
                requestTemplate.method(),
                requestTemplate.path(),
            )
            return
        }

        requestTemplate.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    /** The token the inbound request arrived with, if this call is happening on its thread. */
    private fun relayedToken(): String? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.tokenValue

    private companion object {
        val log = LoggerFactory.getLogger(BearerTokenInterceptor::class.java)
    }
}
