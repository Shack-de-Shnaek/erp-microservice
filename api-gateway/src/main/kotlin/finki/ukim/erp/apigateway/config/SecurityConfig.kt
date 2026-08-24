package finki.ukim.erp.apigateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(security: ServerHttpSecurity): SecurityWebFilterChain {
        security
            .csrf { it.disable() }
            .authorizeExchange { ex ->
                ex.pathMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                ex.pathMatchers("/inventory/**,/orders/**,/api/**").authenticated()
                ex.anyExchange().permitAll()
            }
        return security.build()
    }
}