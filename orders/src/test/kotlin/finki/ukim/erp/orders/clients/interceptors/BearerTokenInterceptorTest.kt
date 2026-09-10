package finki.ukim.erp.orders.clients.interceptors

import feign.RequestTemplate
import finki.ukim.erp.orders.config.ServiceTokenSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Which token ends up on a call to inventory.
 *
 * The three cases are three real situations and not variations on one: a customer's request coming
 * through the gateway, a Kafka event making this service call inventory with nobody behind it, and
 * the tests' own setup where there is no Keycloak at all.
 */
class BearerTokenInterceptorTest {

    private val serviceTokens = mock(ServiceTokenSource::class.java)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authorization(template: RequestTemplate): String? =
        template.headers()[HttpHeaders.AUTHORIZATION]?.firstOrNull()

    private fun authenticateAs(tokenValue: String) {
        val jwt = Jwt.withTokenValue(tokenValue)
            .header("alg", "RS256")
            .claim("sub", "customer")
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, emptyList())
    }

    @Test
    fun `relays the token the inbound request arrived with`() {
        authenticateAs("customers-own-token")
        val template = RequestTemplate()

        BearerTokenInterceptor(serviceTokens).apply(template)

        // The customer's token, not this service's: inventory sees who placed the order.
        assertEquals("Bearer customers-own-token", authorization(template))
    }

    @Test
    fun `falls back to this service's own token when no request is behind the call`() {
        `when`(serviceTokens.accessToken()).thenReturn("orders-service-token")
        val template = RequestTemplate()

        BearerTokenInterceptor(serviceTokens).apply(template)

        assertEquals("Bearer orders-service-token", authorization(template))
    }

    @Test
    fun `sends the call unauthenticated when no token can be had`() {
        `when`(serviceTokens.accessToken()).thenReturn(null)
        val template = RequestTemplate()

        BearerTokenInterceptor(serviceTokens).apply(template)

        // Deliberately not an exception: thrown from here it would surface as a circuit-breaker
        // fallback and read as inventory being down, while a 401 from inventory says what is wrong.
        assertNull(authorization(template))
    }

    @Test
    fun `sends the call unauthenticated when there is no token source at all`() {
        val template = RequestTemplate()

        BearerTokenInterceptor(null).apply(template)

        assertNull(authorization(template))
    }

    @Test
    fun `leaves a token the caller put there alone`() {
        authenticateAs("customers-own-token")
        val template = RequestTemplate().header(HttpHeaders.AUTHORIZATION, "Bearer deliberate")

        BearerTokenInterceptor(serviceTokens).apply(template)

        assertEquals("Bearer deliberate", authorization(template))
    }
}
