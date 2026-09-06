package finki.ukim.erp.orders.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Orders acts purely as an OAuth2 resource server: tokens are obtained through the api-gateway
 * (which owns the OAuth2 login/client flow against Keycloak); this service only validates the
 * incoming Bearer JWT and derives authorities from the Keycloak realm roles claim
 * (`realm_access.roles`), e.g. "ADMIN" / "CLIENT" -> ROLE_ADMIN / ROLE_CLIENT.
 *
 * Not active under the `test` profile. A Pact provider test replays a contract's requests with no
 * bearer token - it is verifying the shape of what this service answers, not the authentication in
 * front of it - so every interaction would come back 401 and a contract that is actually being
 * honoured would look broken. The permissive chain that stands in is
 * [finki.ukim.erp.orders.config.TestSecurityConfig], which lives in the test sources and so cannot
 * be switched on by a deployment however the profiles are set.
 *
 * Note where the exclusion is *not*: nothing in application.yaml is relaxed, and every other test
 * in the suite runs under the default profile with this chain in place.
 */
@Configuration
@Profile("!test")
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                // The API documentation is public: it describes the contract, it does not expose
                // any of the data behind it. /swagger-ui.html is listed separately because it is
                // the redirect into /swagger-ui/, not a path underneath it.
                authorize("/v3/api-docs", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/springwolf/**", permitAll)
                // Consul polls this unauthenticated to decide whether to keep advertising
                // the instance; the rest of the actuator stays behind a token.
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = jwtAuthenticationConverter()
                }
            }
        }
        return http.build()
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRealmRoleConverter())
        return converter
    }
}

class KeycloakRealmRoleConverter : org.springframework.core.convert.converter.Converter<Jwt, Collection<GrantedAuthority>> {
    override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
        val realmAccess = jwt.claims["realm_access"] as? Map<*, *> ?: return emptyList()
        val roles = realmAccess["roles"] as? Collection<*> ?: return emptyList()
        return roles.map { SimpleGrantedAuthority("ROLE_${it.toString().uppercase()}") }
    }
}
