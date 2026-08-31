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
            .oauth2ResourceServer { it.jwt {} }
            .authorizeExchange { ex ->
                ex.pathMatchers("/actuator/**").permitAll()
                ex.pathMatchers("/swagger-ui/**").permitAll()
                ex.pathMatchers("/v3/api-docs/**").permitAll()
                ex.pathMatchers("/inventory/**").authenticated()
                ex.pathMatchers("/orders/**").authenticated()
                ex.pathMatchers("/api/**").authenticated()
                ex.anyExchange().permitAll()
            }
        return security.build()
    }
}
