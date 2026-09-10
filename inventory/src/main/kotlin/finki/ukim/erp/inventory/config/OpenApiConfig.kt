package finki.ukim.erp.inventory.config

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
 * OpenAPI description at /v3/api-docs, browsable at /swagger-ui.html, and offered alongside orders'
 * in the gateway's Swagger UI. (The asynchronous half, the Kafka events, is documented as AsyncAPI
 * by springwolf at /springwolf/docs.)
 *
 * Declaring the bearer scheme is what makes that documentation usable rather than merely correct:
 * since every endpoint here moved behind a Keycloak JWT, without it the "Try it out" button can
 * only ever produce a 401. Tokens come from the `erp` realm; paste one into the Authorize dialog.
 *
 * ## About the server URL
 *
 * The one server declared here is this service's own origin, not the gateway's. The paths in this
 * document are the service's - `/api/products/{id}` - while the gateway publishes the same
 * endpoint as `/api/inventory/products/{id}`, so a gateway base URL would produce
 * `/api/inventory/api/products/{id}` and every "Try it out" would 404. Rather than describe a URL
 * that does not resolve, the document keeps the paths its Pact contracts are written against and
 * says where the gateway equivalent lives in the description.
 */
@Configuration
class OpenApiConfig {

    /**
     * How this service is reachable from a browser. Under docker-compose that is the published
     * port (8081), which is not the port the container itself listens on (8080), so it cannot be
     * derived from `server.port` - hence the override.
     */
    @Value("\${erp.docs.public-url:http://localhost:8081}")
    private lateinit var publicUrl: String

    @Bean
    fun inventoryOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Inventory Service")
                .version("0.0.1")
                .description(
                    "The product catalogue, the stock ledger, and the holds orders take against " +
                        "it. Writes are dispatched as commands to the product and stock-item " +
                        "aggregates; reads are served from the projected read model.\n\n" +
                        "Behind the API gateway the same endpoints are published under " +
                        "`/api/inventory/` - `/api/products/{id}` here is " +
                        "`/api/inventory/products/{id}` there. Both require a Keycloak access " +
                        "token from the `erp` realm; reads need only a valid token, while the " +
                        "catalogue and ledger mutations need ADMIN and the reservation endpoints " +
                        "need ADMIN, SERVICE or CUSTOMER."
                )
        )
        .addServersItem(Server().url(publicUrl).description("The inventory service directly"))
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
