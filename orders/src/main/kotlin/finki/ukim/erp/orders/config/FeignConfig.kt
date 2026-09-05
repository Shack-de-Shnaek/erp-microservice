package finki.ukim.erp.orders.config

import finki.ukim.erp.orders.clients.interceptors.CorrelationIdInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registered as a plain `@Configuration` rather than through `@FeignClient(configuration = ...)`,
 * which would scope it to one client. A correlation id that is attached to some outgoing calls
 * and not others is worse than none: the gap is exactly where the trace goes cold.
 */
@Configuration
class FeignConfig {

    @Bean
    fun correlationIdInterceptor(): CorrelationIdInterceptor = CorrelationIdInterceptor()
}
