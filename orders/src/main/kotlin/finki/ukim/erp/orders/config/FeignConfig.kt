package finki.ukim.erp.orders.config

import finki.ukim.erp.orders.clients.interceptors.BearerTokenInterceptor
import finki.ukim.erp.orders.clients.interceptors.CorrelationIdInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registered as a plain `@Configuration` rather than through `@FeignClient(configuration = ...)`,
 * which would scope it to one client. Both of these belong on every outgoing call for the same
 * reason: a correlation id attached to some calls and not others is worse than none, because the
 * gap is exactly where the trace goes cold - and a token attached to some calls and not others is
 * a 401 on whichever one was forgotten.
 */
@Configuration
class FeignConfig {

    @Bean
    fun correlationIdInterceptor(): CorrelationIdInterceptor = CorrelationIdInterceptor()

    /**
     * `ObjectProvider` because [ServiceTokenSource] is conditional on an OAuth2 client
     * registration being configured, which it is in every deployment and in none of the tests.
     * Asking for it directly would fail the context there rather than degrade.
     */
    @Bean
    fun bearerTokenInterceptor(serviceTokens: ObjectProvider<ServiceTokenSource>): BearerTokenInterceptor =
        BearerTokenInterceptor(serviceTokens.getIfAvailable())
}
