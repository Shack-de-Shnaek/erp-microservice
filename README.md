# ERP Inventory Management System

A microservice architecture for managing products, stock levels, and orders — built
with event sourcing, CQRS, and asynchronous event-driven communication.

## Team Members and Roles

| Member | Role | Responsibility |
|--------|------|----------------|
| Luka   | Inventory Service | Domain modeling (DDD), event sourcing with Axon, CQRS read model, Kafka publishing/consumption, ACL translators, reservation lifecycle |
| Dragan | Orders Service | Order lifecycle, payments, invoicing, Feign integration, Kafka consumption |
| Mixed  | API Gateway | Routing, JWT validation, OAuth2 login, unified Swagger |
| Mixed  | MCP Server | AI-facing adapter, 17 MCP tools, Keycloak authentication |

## Architecture Overview

Two business services (inventory, orders) + API gateway + MCP server, built on
Spring Boot 3.2.12 with Axon Framework 4.10.0. Kafka handles async inter-service
communication. Consul provides service discovery. Keycloak manages authentication
and authorization. Each service owns its own PostgreSQL database.

| Component | Technology | Port |
|-----------|-----------|------|
| Inventory Service | Kotlin, Spring Boot, Axon | 8081 |
| Orders Service | Kotlin, Spring Boot, Axon | 8089 |
| API Gateway | Kotlin, Spring Cloud Gateway | 8088 |
| MCP Server | Python, FastMCP | 8765 |
| PostgreSQL | 15 (single instance, 3 databases) | 5432 |
| Kafka | Confluent 7.5.0 (KRaft) | 9092 |
| Consul | Service discovery | 8500 |
| Keycloak | OAuth2 / OIDC | 8080 |

## How to Run

```bash
docker-compose up -d --build
```

| Service | URL |
|---------|-----|
| API Gateway | http://localhost:8088 |
| Inventory Service | http://localhost:8081 |
| Orders Service | http://localhost:8089 |
| Consul UI | http://localhost:8500 |
| Keycloak Admin | http://localhost:8080 (admin / admin) |
| Swagger UI | http://localhost:8088/swagger-ui.html |
| MCP Server | http://localhost:8765/mcp (streamable-http) |

### Smoke Tests

```bash
# Inventory smoke tests (against running stack)
./test-scripts/test_inventory.sh

# All smoke tests
./test-scripts/run_all_tests.sh

# MCP server smoke test
cd mcp-server && .venv/bin/python test_client.py
```

## Project Structure

```
├── inventory/              # Product & stock management (Axon, CQRS)
│   └── src/main/kotlin/finki/ukim/erp/inventory/
│       ├── domain/         # Aggregates (Product, StockItem), events, commands
│       ├── readmodel/      # Views, projections, queries
│       ├── web/            # REST controllers
│       ├── infrastructure/ # Kafka publisher, event consumer
│       ├── integration/    # ACL translators, saga
│       └── config/         # Axon, security, OpenAPI, Springwolf
├── orders/                 # Order lifecycle (placement, approval, invoicing)
│   └── src/main/kotlin/finki/ukim/erp/orders/
│       ├── models/         # Order aggregate, value objects, identifiers
│       ├── commands/       # Command data classes
│       ├── events/         # Domain events, external events
│       ├── views/          # @Subselect read model entities
│       ├── controllers/    # REST controllers
│       ├── handlers/       # Kafka event handlers, command handlers
│       ├── clients/        # Feign client to inventory (ACL)
│       └── config/         # Axon, security, Feign, Kafka
├── api-gateway/            # Spring Cloud Gateway + JWT validation
│   └── src/main/kotlin/finki/ukim/erp/apigateway/
│       └── config/         # RoutesConfig, SecurityConfig
├── mcp-server/             # MCP server for AI inventory queries
│   ├── server.py           # FastMCP server with 21 tools
│   └── test_client.py      # Smoke test client
├── keycloak/               # Realm import for Keycloak
│   └── realm-erp.json      # erp realm with 3 clients, 3 roles
├── test-scripts/           # Smoke test scripts
│   ├── test_inventory.sh
│   └── run_all_tests.sh
├── docker-db/              # PostgreSQL init script
│   └── init.sql
├── docker-compose.yml      # Full system orchestration
└── docs/
    ├── specification.md    # Project specification (≤10 pages)
    └── architecture-diagram.md  # Mermaid architecture diagram
```

## Documentation

- [Specification](docs/specification.md) — Architecture, service descriptions,
  communication patterns, concepts, MCP server details
- [Architecture Diagram](docs/architecture-diagram.md) — Mermaid diagram showing
  all components and communication flows

## Key Concepts

- **Domain-Driven Design:** Typed value objects, aggregate roots with business
  invariants, commands/events in ubiquitous language
- **Event Sourcing:** Domain events as source of truth, aggregate state rebuilt
  from event replay (inventory service)
- **CQRS:** Separate write model (aggregates) and read model (projected views),
  eventual consistency bridged by `fetchWithRetry`
- **Kafka:** Async event-driven communication, at-least-once delivery, topic names
  derived from event class names. Inventory reserves stock synchronously via Feign;
  stock release flows asynchronously via `order.nullified`
- **ACL:** Anti-Corruption Layer translates foreign events before they touch the domain
- **Feign + Consul:** Synchronous HTTP calls resolved by service discovery (product
  lookup, stock validation, reservation management)
- **Keycloak:** OAuth2 resource server with role-based access (ADMIN, CUSTOMER, SERVICE)
- **Pact:** Consumer-driven contract testing for HTTP and Kafka message contracts
- **Idempotency:** Every stock mutation handler checks for duplicate operations
- **MCP Server:** AI-facing adapter exposing 21 tools through the API gateway
