package finki.ukim.erp.orders.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * This service's own identity, for the calls it makes on nobody's behalf.
 *
 * A token is fetched with the client-credentials grant against the same `erp` realm that issues
 * user tokens, as the `erp-orders` confidential client, whose service account holds the SERVICE
 * role. So there is still exactly one authority deciding who a caller is - a service account is
 * just another identity in it, not a second scheme bolted on beside it. That is what "centralized"
 * buys: inventory verifies this token with the same signature check and the same claim mapping it
 * applies to a customer's.
 *
 * Caching is Spring's, not ours: the authorized-client service holds the token and
 * `ClientCredentialsOAuth2AuthorizedClientProvider` re-fetches it a minute before it expires
 * (`clockSkew` default), so this is one Keycloak round trip per token lifetime rather than one per
 * call.
 *
 * `@ConditionalOnBean(ClientRegistrationRepository)` is what makes the tests work: they configure
 * no `spring.security.oauth2.client.registration.*`, so no repository exists, so this bean is
 * absent and [finki.ukim.erp.orders.clients.interceptors.BearerTokenInterceptor] takes it as
 * nullable and relays only what a request brought with it. Nothing in a deployment reaches that
 * branch - application.yaml always registers `orders-service`.
 */
@Configuration
@ConditionalOnBean(ClientRegistrationRepository::class)
class ServiceTokenConfig {

    @Bean
    fun serviceAuthorizedClientManager(
        clientRegistrations: ClientRegistrationRepository
    ): OAuth2AuthorizedClientManager {
        // The servlet-request-bound manager cannot be used here: half these calls happen on a
        // Kafka listener thread, where there is no request to bind to. This one keeps its
        // authorized clients in a service of its own instead.
        val manager = AuthorizedClientServiceOAuth2AuthorizedClientManager(
            clientRegistrations,
            InMemoryOAuth2AuthorizedClientService(clientRegistrations),
        )
        manager.setAuthorizedClientProvider(
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build()
        )
        return manager
    }

    @Bean
    fun serviceTokenSource(manager: OAuth2AuthorizedClientManager): ServiceTokenSource =
        ServiceTokenSource(manager)
}

/** Hands out this service's own access token, minting or refreshing it as needed. */
class ServiceTokenSource(
    private val manager: OAuth2AuthorizedClientManager,
    private val registrationId: String = REGISTRATION_ID,
) {

    /**
     * Returns the token, or null if Keycloak would not issue one - a wrong secret, a realm without
     * this client, a Keycloak that is not up. Null rather than an exception because the caller is
     * a Feign interceptor: see the note on
     * [finki.ukim.erp.orders.clients.interceptors.BearerTokenInterceptor] about why an exception
     * thrown from there is indistinguishable from inventory being down.
     */
    fun accessToken(): String? {
        val request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
            // A client-credentials grant has no user behind it, but the manager insists on a
            // principal to key its stored client by. An anonymous one named after the
            // registration is the conventional stand-in.
            .principal(
                AnonymousAuthenticationToken(
                    registrationId,
                    registrationId,
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
                )
            )
            .build()

        return manager.authorize(request)?.accessToken?.tokenValue
    }

    companion object {
        /** Matches `spring.security.oauth2.client.registration.orders-service` in application.yaml. */
        const val REGISTRATION_ID = "orders-service"
    }
}
