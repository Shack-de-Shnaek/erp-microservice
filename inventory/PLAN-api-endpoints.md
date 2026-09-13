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
| `POST` | `/{productId}/deactivate` | Withdraw from sale, returning the product | — | `ProductView` | **New** |
| `POST` | `/{productId}/reactivate` | Put back on sale | — | `ProductView` | **New** |

`DELETE /{productId}` and `POST /{productId}/deactivate` send the same command; the first answers
`204` for a caller that thinks in REST deletes, the second returns the updated product for a caller
that wants to see the result. Only `POST /{productId}/reactivate` undoes it.

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
| `POST` | `/{productId}/reserve` | Hold stock for one order, one product | `ReserveStockRequest` | `StockItemView` | **New** |
| `POST` | `/{productId}/release` | Give back that hold | `ReleaseReservationRequest` | `StockItemView` | **New** |
| `POST` | `/{productId}/confirm` | The goods left the shelf | `ConfirmStockRequest` | `StockItemView` | **New** |

The last three are the low-level counterpart of `/api/reservations`, addressed one product at a
time rather than one order at a time, and are ADMIN or SERVICE. `/api/reservations` is what an
ordering service should call; these exist for an administrator correcting one item by hand.

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

data class ReserveStockRequest(
    val orderRef: String,
    val quantity: Int
)

data class ReleaseReservationRequest(
    val orderRef: String
)

data class ConfirmStockRequest(
    val orderRef: String
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
| `PUT` | `/{orderRef}` | Amend to a new set of lines | `AmendReservationRequest` | `ReservationView` | **New** |
| `POST` | `/{orderRef}/confirm` | Confirm (fulfill) | — | `ReservationView` | Existing |

`PUT` states every line the order should hold once the amendment is done, not the ones that
changed. One call covers a raised line, a lowered one, a dropped one and a new one together, and it
applies whole or not at all - which is what keeps the order from standing empty-handed between
giving stock back and asking for it again. An order never contests stock it is keeping, so only the
part of a line going *up* can fail.

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

data class AmendReservationRequest(
    val lines: List<ReservationLineRequest>
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
| Amend a reservation | `PUT /api/reservations/{orderRef}` | When an order's lines are edited |
| Release stock | `DELETE /api/reservations/{orderRef}` | On order cancellation |
| Read a reservation | `GET /api/reservations/{orderRef}` | Before invoicing, to check the hold still stands |

### Kafka Events (asynchronous)

Everything inventory publishes, and who reads it. Orders is the only consumer today; the rest are
published because they are facts about the ledger, not because somebody asked for them.

| Event | Topic | Consumed by orders? |
|-------|-------|---------------------|
| Product created | `product.created` | No — orders keeps no catalogue of its own |
| Product updated | `product.updated` | No — a rename does not change a priced order |
| Product deactivated | `product.deactivated` | **Yes** — rejects every pending order for that product |
| Product reactivated | `product.reactivated` | No — the orders it rejected are finished |
| Stock reserved | `stock.reserved` | No — orders already knows, it asked for the hold synchronously |
| Stock reservation amended | `stock.reservation.amended` | No — same reason |
| Stock reservation released | `stock.reservation.released` | **Yes** — a hold orders did not ask to drop means the order is no longer backed |
| Stock returned | `stock.returned` | **Yes** — same handler; confirmed goods coming back ends the order the same way |
| Stock confirmed | `stock.confirmed` | **Yes** — the goods left the shelf, so a pending order is approved |
| Stock adjusted | `stock.adjusted` | No — orders re-checks its reservation when it invoices |

The reverse direction is one topic. Orders publishes `order.nullified` — cancelled, rejected or
refunded, in the same words — and inventory's `OrderLifecycleSaga` releases whatever it is holding
for that order. There is deliberately no reservation on `order.approved`: stock is taken
synchronously when the order is placed, and `order.approved` is what comes *back* once this service
confirms it out — our own decision echoed, with nothing left to act on.

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
