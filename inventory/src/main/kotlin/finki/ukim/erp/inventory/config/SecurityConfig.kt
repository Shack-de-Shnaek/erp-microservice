package finki.ukim.erp.inventory.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.convert.converter.Converter
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Inventory as an OAuth2 resource server, on the same terms as orders.
 *
 * There is one authority in the system that says who a caller is - the `erp` realm in Keycloak -
 * and neither service talks to it to find out. A caller arrives with a JWT the realm signed, and
 * each service verifies that signature against the realm's key set and reads the caller's roles
 * out of the token. Nothing here calls orders, or the gateway, or Keycloak itself to authenticate
 * a request: the token is the whole answer, which is what makes the check identical on both sides
 * and survivable when any one service is down.
 *
 * Where the token comes from does not matter to this service, and deliberately so. It may have
 * been minted for a browser session at the gateway and relayed here by `TokenRelay`, fetched by
 * the MCP server's service account, or fetched by orders itself when it calls
 * `/api/reservations` off the back of a Kafka event. All three are the same kind of thing by the
 * time they arrive.
 *
 * Roles come from the realm roles claim (`realm_access.roles`) - ADMIN, CUSTOMER, SERVICE - and are
 * mapped onto `ROLE_*` authorities so `hasRole(...)` reads normally. This mirrors
 * `finki.ukim.erp.orders.config.SecurityConfig` exactly; the duplication is the cost of the two
 * services not sharing a jar, and the mapping is the part that has to agree.
 *
 * Not active under the `test` profile, for the reason set out in the inventory
 * `TestSecurityConfig`: a Pact provider replays a consumer's recorded requests, which carry no
 * bearer token, so every interaction would come back 401 and a contract that is being honoured
 * would look broken. That config lives in the test sources and cannot be switched on by a
 * deployment.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                // The documentation describes the contract; it exposes none of the data behind it.
                // /swagger-ui.html is listed separately because it is the redirect into
                // /swagger-ui/, not a path underneath it.
                authorize.requestMatchers(
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/springwolf/**",
                ).permitAll()
                // Consul polls this unauthenticated to decide whether to keep advertising the
                // instance; the rest of the actuator stays behind a token.
                authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                authorize.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
        return http.build()
    }

    /**
     * Authentication is settled here; *authorization* is settled per endpoint, with
     * `@PreAuthorize` on the controllers. Keeping it there rather than in a path list above is
     * what stops the two from drifting: a new endpoint on an existing controller inherits nothing
     * silently, and the rule for an operation sits next to the operation.
     */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRealmRoleConverter())
        return converter
    }
}

/** `realm_access.roles` -> `ROLE_*`. The same conversion orders applies, on the same claim. */
class KeycloakRealmRoleConverter : Converter<Jwt, Collection<GrantedAuthority>> {
    override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
        val realmAccess = jwt.claims["realm_access"] as? Map<*, *> ?: return emptyList()
        val roles = realmAccess["roles"] as? Collection<*> ?: return emptyList()
        return roles.map { SimpleGrantedAuthority("ROLE_${it.toString().uppercase()}") }
    }
}
