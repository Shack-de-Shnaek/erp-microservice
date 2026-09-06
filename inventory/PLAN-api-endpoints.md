# Inventory Service — API Endpoint Plan

## Overview

RESTful API design for the inventory microservice in the ERP system. Follows standard REST conventions with clear separation between inventory and order concerns.

---

## Design Decisions

| Decision | Choice |
|----------|--------|
| Delete behavior | Soft-delete (deactivate) for products, hard-delete for stock records |
| Reservations | Separate line per order — no bulk |
| Pagination | Add `?page=&size=` to all list endpoints |
| Summary | Aggregate stats endpoint for dashboards |
| Swagger | Document all endpoints with OpenAPI/SpringDoc |

---

## Resources

### Products (`/api/products`)

| Method | Path | Description | Request Body | Response | Status |
|--------|------|-------------|--------------|----------|--------|
| `POST` | `/` | Create product | `CreateProductRequest` | `ProductView` | Existing |
| `GET` | `/` | List products | — | `Page<ProductView>` | Existing + pagination |
| `GET` | `/{productId}` | Get product by ID | — | `ProductView` | Existing |
| `PUT` | `/{productId}` | Full update | `UpdateProductRequest` | `ProductView` | Existing |
| `PATCH` | `/{productId}` | Partial update | `PatchProductRequest` | `ProductView` | **New** |
| `DELETE` | `/{productId}` | Soft-delete (deactivate) | — | `204 No Content` | **New** |
| `GET` | `/by-sku/{sku}` | Lookup by SKU | — | `ProductView` | **New** |

#### Product DTOs

```kotlin
// Existing
data class CreateProductRequest(
    val sku: String,
    val name: String,
    val unitOfMeasure: String
)

data class UpdateProductRequest(
    val sku: String,
    val name: String,
    val unitOfMeasure: String
)

// New
data class PatchProductRequest(
    val sku: String? = null,
    val name: String? = null,
    val unitOfMeasure: String? = null
)
```

---

### Stock (`/api/stock`)

| Method | Path | Description | Request Body | Response | Status |
|--------|------|-------------|--------------|----------|--------|
| `POST` | `/` | Create stock record | `CreateStockItemRequest` | `StockItemView` | Existing |
| `GET` | `/` | List stock records | — | `Page<StockItemView>` | Existing + pagination |
| `GET` | `/{productId}` | Get stock for product | — | `StockItemView` | Existing |
| `PATCH` | `/{productId}` | Update reorder threshold | `UpdateReorderThresholdRequest` | `StockItemView` | **New** |
| `DELETE` | `/{productId}` | Remove stock record | — | `204 No Content` | **New** |
| `POST` | `/{productId}/adjust` | Manual adjustment | `AdjustStockRequest` | `StockItemView` | Existing |
| `GET` | `/low-stock` | Items below threshold | — | `List<StockItemView>` | Existing |
| `GET` | `/summary` | Aggregate stats | — | `StockSummaryResponse` | **New** |

#### Stock DTOs

```kotlin
// Existing
data class CreateStockItemRequest(
    val productId: String,
    val onHand: Int,
    val reorderThreshold: Int
)

data class AdjustStockRequest(
    val adjustment: Int,
    val reason: String
)

// New
data class UpdateReorderThresholdRequest(
    val reorderThreshold: Int
)

data class StockSummaryResponse(
    val totalProducts: Int,
    val totalOnHand: Int,
    val totalReserved: Int,
    val totalAvailable: Int,
    val lowStockCount: Int
)
```

---

### Reservations (`/api/reservations`)

| Method | Path | Description | Request Body | Response | Status |
|--------|------|-------------|--------------|----------|--------|
| `POST` | `/` | Reserve stock (single order) | `CreateReservationRequest` | `ReservationView` | **New** |
| `GET` | `/` | List active reservations | — | `Page<ReservationView>` | **New** |
| `GET` | `/{orderRef}` | Get reservation by order | — | `ReservationView` | **New** |
| `DELETE` | `/{orderRef}` | Release reservation | — | `204 No Content` | **New** |
| `POST` | `/{orderRef}/confirm` | Confirm (fulfill) | — | `ReservationView` | Existing |

#### Reservation DTOs

```kotlin
data class CreateReservationRequest(
    val orderRef: String,
    val lines: List<ReservationLineRequest>
)

data class ReservationLineRequest(
    val productId: String,
    val quantity: Int
)

data class ReservationView(
    val orderRef: String,
    val lines: List<ReservationLineView>,
    val status: String,
    val createdAt: String
)

data class ReservationLineView(
    val productId: String,
    val quantity: Int
)
```

---

## Integration Points (for Orders Service)

The orders service consumes these endpoints and events:

### REST Calls (synchronous)

| Operation | Endpoint | When |
|-----------|----------|------|
| Get product details | `GET /api/products/{productId}` | Before order placement |
| Lookup product by SKU | `GET /api/products/by-sku/{sku}` | When SKU is known |
| Check availability | `GET /api/stock/{productId}` → compute `onHand - reserved` | Pre-order validation |
| Reserve stock | `POST /api/reservations` | On order placement |
| Confirm stock | `POST /api/reservations/{orderRef}/confirm` | On delivery/fulfillment |
| Release stock | `DELETE /api/reservations/{orderRef}` | On order cancellation |

### Kafka Events (asynchronous)

| Event | Topic | Published By | Consumed By |
|-------|-------|--------------|-------------|
| Product created | `product.created` | Inventory | Orders (catalog sync) |
| Product updated | `product.updated` | Inventory | Orders (catalog sync) |
| Product deactivated | `product.deactivated` | Inventory | Orders (catalog sync) |
| Stock reserved | `stock.reserved` | Inventory | Orders (confirmation) |
| Stock confirmed | `stock.confirmed` | Inventory | Orders (confirmation) |
| Order placed | `order.placed` | Orders | Inventory (auto-reserve) |

---

## Pagination

All list endpoints support pagination via query parameters:

```
GET /api/products?page=0&size=20
GET /api/stock?page=0&size=20&sort=productId,asc
GET /api/reservations?page=0&size=20
```

Response wraps results in Spring's `Page<T>`:

```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

The `/low-stock` endpoint returns a plain list (typically small result set).

---

## Soft-Delete Behavior

Products use soft-delete via status field:

- `DELETE /api/products/{productId}` sets `status = INACTIVE`
- `GET /api/products?status=INACTIVE` returns soft-deleted products
- No hard-delete endpoint — products are never permanently removed
- Stock records for deactivated products remain in the system

Stock records use hard-delete:

- `DELETE /api/stock/{productId}` permanently removes the stock record
- Should only be called after product is deactivated

---

## Swagger / OpenAPI Documentation

Add SpringDoc OpenAPI for auto-generated API docs:

### Dependency

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

### Configuration

```properties
# application.properties
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.swagger-ui.tags-sorter=alpha
springdoc.swagger-ui.operations-sorter=alpha
```

### Endpoint Access

| URL | Purpose |
|-----|---------|
| `http://localhost:8080/swagger-ui.html` | Swagger UI |
| `http://localhost:8080/api-docs` | OpenAPI JSON spec |

### Documentation Requirements

Each endpoint should include:

- `@Operation(summary = "...", description = "...")` on handler methods
- `@ApiResponse` for each status code (200, 201, 204, 400, 404, 409)
- `@Parameter` on path/query params
- `@Tag(name = "Products")` for controller grouping
- Schema descriptions on DTOs via `@Schema`

---

## Implementation Order

1. **Pagination** — Add `Pageable` to existing list endpoints
2. **Products** — Add `PATCH`, `DELETE`, `GET /by-sku/{sku}`
3. **Stock** — Add `PATCH`, `DELETE`, `GET /summary`
4. **Reservations** — Refactor to `POST /` with lines, add list/get/delete endpoints
5. **Swagger** — Add SpringDoc dependency + annotate all endpoints
6. **Events** — Add missing external events (reactivated, adjusted, etc.)

---

## Open Questions

1. **Reservation expiry** — Should stale reservations auto-expire after a timeout? If so, what duration?
2. **Stock hard-delete guard** — Should `DELETE /api/stock/{productId}` reject if reservations are active?
3. **Pagination defaults** — Default page size? (Suggested: 20, max: 100)
