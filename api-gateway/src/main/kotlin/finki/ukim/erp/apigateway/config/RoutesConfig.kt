package finki.ukim.erp.apigateway.config

import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The public URL space, and the only place that maps it onto the services behind it.
 *
 * Everything the system exposes lives under `/api/<service>/`, so a caller learns one shape rather
 * than one per service:
 *
 * ```
 * /api/inventory/products          -> inventory  /api/products
 * /api/inventory/stock/summary     -> inventory  /api/stock/summary
 * /api/inventory/reservations      -> inventory  /api/reservations
 *
 * /api/orders                      -> orders     /orders
 * /api/orders/all                  -> orders     /orders/all
 * /api/orders/{id}/approve         -> orders     /orders/{id}/approve
 * /api/orders/{id}/payments        -> orders     /orders/{id}/payments
 * /api/orders/invoices/{id}        -> orders     /invoices/{id}
 *
 * /docs/inventory/v3/api-docs      -> inventory  /v3/api-docs
 * /docs/orders/v3/api-docs         -> orders     /v3/api-docs
 * ```
 *
 * The `/docs/` pair is what lets this gateway serve one Swagger UI with both services in its
 * dropdown: each document is fetched from the gateway's own origin.
 *
 * The rewrites exist because the two services do not agree on where their endpoints live -
 * inventory puts everything under `/api`, orders puts orders at `/orders` and invoices at
 * `/invoices` - and that disagreement is theirs to keep. Absorbing it here means the outside world
 * sees one convention while each service keeps the paths its own contracts are written against:
 * the Pact files pin `/api/products/{id}` and `/orders/{id}`, and both still hold.
 *
 * These are written out rather than left to `spring.cloud.gateway.discovery.locator`, which is now
 * off. The locator invents a route per registered service - one under `/inventory`, one under
 * `/orders`, and one under `/api-gateway` pointing the gateway at itself - which means the URL
 * space is decided by whatever happens to be registered in Consul at the time, and every service
 * that registers is published whether or not it was meant to be. `lb://` still resolves through Consul, so instances
 * are discovered and load-balanced exactly as before; only the naming is no longer automatic.
 *
 * `\${segment}` is a capture-group reference handed to Spring Cloud Gateway, escaped so Kotlin does
 * not read it as a string template.
 */
@Configuration
class RoutesConfig {

    @Bean
    fun apiRoutes(builder: RouteLocatorBuilder): RouteLocator = builder.routes()
        // Inventory keeps its own `/api` prefix, so only the `/api/inventory` in front of it is
        // swapped out: /api/inventory/products -> /api/products.
        .route("inventory") { route ->
            route
                .path("/api/inventory/**")
                .filters { it.rewritePath("/api/inventory/(?<segment>.*)", "/api/\${segment}") }
                .uri("lb://inventory")
        }
        // Invoices are part of ordering as far as a caller is concerned, but the service puts them
        // at `/invoices`, outside `/orders`. This route has to come first: the pattern below also
        // matches /api/orders/invoices/{id} and would send it to /orders/invoices/{id}, which is
        // an order id of "invoices".
        .route("orders-invoices") { route ->
            route
                .path("/api/orders/invoices/**")
                .filters { it.rewritePath("/api/orders/(?<segment>.*)", "/\${segment}") }
                .uri("lb://orders")
        }
        // Everything else about an order. The capture starts *after* `/api/orders` rather than
        // after a trailing slash, so the bare collection - POST /api/orders, which places an
        // order - rewrites to /orders and not to /orders/.
        .route("orders") { route ->
            route
                .path("/api/orders", "/api/orders/**")
                .filters { it.rewritePath("/api/orders(?<segment>.*)", "/orders\${segment}") }
                .uri("lb://orders")
        }
        // The two services' own documentation, proxied. `/docs/<service>/` maps onto the root of
        // that service, so `/docs/orders/v3/api-docs` is orders' OpenAPI document and
        // `/docs/inventory/springwolf/docs` is inventory's AsyncAPI one.
        //
        // These exist so the Swagger UI this gateway serves can fetch both documents from its own
        // origin. Pointing it straight at localhost:8081 and localhost:8089 would work only as
        // long as those ports are published to the host, and only with CORS opened up on both
        // services - a browser fetching an OpenAPI document cross-origin is a cross-origin request
        // like any other. Neither holds in a deployment, where the services are reachable only
        // inside the network; through `lb://` these routes work either way.
        //
        // A separate prefix rather than a path under `/api/**`, because the rewrites there are
        // written for each service's API shape and neither lands on the service root:
        // `/api/orders/v3/api-docs` would arrive at orders as `/orders/v3/api-docs`, which is not
        // where springdoc serves it.
        .route("orders-docs") { route ->
            route
                .path("/docs/orders/**")
                .filters { it.rewritePath("/docs/orders/(?<segment>.*)", "/\${segment}") }
                .uri("lb://orders")
        }
        .route("inventory-docs") { route ->
            route
                .path("/docs/inventory/**")
                .filters { it.rewritePath("/docs/inventory/(?<segment>.*)", "/\${segment}") }
                .uri("lb://inventory")
        }
        .build()
}
