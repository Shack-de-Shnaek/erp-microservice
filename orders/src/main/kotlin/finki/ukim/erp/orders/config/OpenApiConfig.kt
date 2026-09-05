package finki.ukim.erp.orders.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The synchronous half of this service's documentation - springdoc turns the controllers into an
 * OpenAPI description at /v3/api-docs, browsable at /swagger-ui.html. (The asynchronous half, the
 * Kafka events, is documented as AsyncAPI by springwolf at /springwolf/docs.)
 *
 * Declaring the bearer scheme is what makes that documentation usable rather than merely correct:
 * every endpoint here is behind a Keycloak JWT, so without it the "Try it out" button can only
 * ever produce a 401. Tokens come from Keycloak through the api-gateway; paste one into
 * Swagger UI's Authorize dialog.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun ordersOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Orders Service")
                .version("0.0.1")
                .description(
                    "Ordering, payment and invoicing for the ERP system. Writes are dispatched as " +
                        "commands to the order aggregate; reads are served from the projected read model."
                )
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("A Keycloak access token from the erp realm.")
            )
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "keycloak"
    }
}
