package finki.ukim.erp.apigateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher

/**
 * The front door, in two halves.
 *
 * They exist separately because the two kinds of caller want opposite things from a request with
 * no credentials. A script wants `401 WWW-Authenticate: Bearer` and would be baffled by a redirect
 * to an HTML login form; a person in a browser wants exactly that redirect and cannot do anything
 * with a 401. A single chain has one authentication entry point and so can only serve one of them.
 *
 * So: requests that arrive carrying a bearer token are handled by [apiSecurityFilterChain], which
 * validates it and nothing else. Everything else falls through to [browserSecurityFilterChain],
 * which starts an authorization-code login against Keycloak.
 *
 * There is no login page in this repository on purpose. `oauth2Login` redirects to the identity
 * provider's own page - Keycloak renders the form, owns the password, and handles the things a
 * hand-rolled page would get wrong. With exactly one registered provider Spring Security skips its
 * generated provider-picker entirely and goes straight there.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    /**
     * API clients. Selected by the request itself rather than by path, since the same URLs serve
     * both audiences: `/orders/orders` is a page to a person and a resource to a script.
     */
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(security: ServerHttpSecurity): SecurityWebFilterChain {
        security
            .securityMatcher(bearerTokenRequests())
            .csrf { it.disable() }
            .oauth2ResourceServer { it.jwt {} }
            .authorizeExchange { exchange ->
                exchange.pathMatchers(*PUBLIC_PATHS).permitAll()
                exchange.anyExchange().authenticated()
            }
        return security.build()
    }

    /**
     * Everyone else - which in practice means a browser.
     *
     * The token obtained here is put back on the proxied request by the `TokenRelay` filter (see
     * application.properties), so the services behind the gateway see the same bearer token they
     * would have got from an API client and need to know nothing about sessions or logins.
     *
     * CSRF stays *on*: this half is cookie-authenticated, which is precisely the case the
     * protection exists for. The API half above has it off because a bearer token is not sent
     * automatically by a browser and so cannot be ridden.
     */
    @Bean
    fun browserSecurityFilterChain(security: ServerHttpSecurity): SecurityWebFilterChain {
        security
            .authorizeExchange { exchange ->
                exchange.pathMatchers(*PUBLIC_PATHS).permitAll()
                exchange.anyExchange().authenticated()
            }
            .oauth2Login { }
            .logout { }
        return security.build()
    }

    /** Matches a request that brought its own bearer token, whatever it is asking for. */
    private fun bearerTokenRequests() = ServerWebExchangeMatcher { exchange ->
        val authorization = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authorization?.startsWith(BEARER_PREFIX, ignoreCase = true) == true) {
            ServerWebExchangeMatcher.MatchResult.match()
        } else {
            ServerWebExchangeMatcher.MatchResult.notMatch()
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "

        /**
         * Health checks and documentation: reachable without a token, in either chain.
         *
         * The documentation describes the contract and exposes none of the data behind it, and it
         * has to be readable before a caller has a token - it is where they find out how to use
         * one. `/swagger-ui.html` is listed separately from everything under `/swagger-ui/`
         * because it is the redirect into that directory rather than a path beneath it, and
         * `/webjars/` is where the UI's own assets are served from.
         *
         * The `/docs/` entry covers the proxy routes onto the two services' documents (see
         * RoutesConfig). Permitting them here is what makes them public, but not more public than
         * they already are: both services permit their own `/v3/api-docs` and springwolf
         * endpoints unauthenticated, and nothing else lives under that prefix. Every actual API
         * path stays under `/api/`, which is not matched here.
         */
        val PUBLIC_PATHS = arrayOf(
            "/actuator/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/docs/**",
        )
    }
}
