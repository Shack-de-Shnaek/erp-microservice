package finki.ukim.erp.orders.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.beans.factory.annotation.Value
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
 *
 * The gateway serves this document alongside inventory's, in one UI at
 * http://localhost:8088/swagger-ui.html, fetched through its `/docs/orders/` route. The server
 * declared below is nonetheless this service's own origin: the paths here are the service's
 * (`/orders/{id}`) while the gateway publishes them under `/api/orders/`, so a gateway base URL
 * would send every "Try it out" to a path that does not resolve. The paths stay the ones the Pact
 * contracts are written against, and the description says where the gateway equivalent lives.
 */
@Configuration
class OpenApiConfig {

    /**
     * How this service is reachable from a browser. Under docker-compose that is the published
     * port (8089), which is not the port the container itself listens on (8080), so it cannot be
     * derived from `server.port`.
     */
    @Value("\${erp.docs.public-url:http://localhost:8089}")
    private lateinit var publicUrl: String

    @Bean
    fun ordersOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Orders Service")
                .version("0.0.1")
                .description(
                    "Ordering, payment and invoicing for the ERP system. Writes are dispatched as " +
                        "commands to the order aggregate; reads are served from the projected " +
                        "read model.\n\n" +
                        "Behind the API gateway the same endpoints are published under " +
                        "`/api/orders/` - `/orders/{id}` here is `/api/orders/{id}` there, and " +
                        "`/invoices/{id}` is `/api/orders/invoices/{id}`. All of them require a " +
                        "Keycloak access token from the `erp` realm; approving and rejecting an " +
                        "order additionally requires ADMIN."
                )
        )
        .addServersItem(Server().url(publicUrl).description("The orders service directly"))
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
