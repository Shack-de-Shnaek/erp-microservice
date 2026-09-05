# Synchronous cross-service calls

How the orders service asks another team's service a question it needs answered *now*: OpenFeign
for the call, Resilience4j for what happens when the answer does not come, and a correlation id so
one user action can be followed across both services.

The asynchronous half - the events this service publishes and consumes - is in
[aggregate-design.md](aggregate-design.md). The two are deliberately different mechanisms: an
event says "this happened", and nobody waits for it. Everything below is a question, and the
command handler cannot proceed until it has the answer.

---

## Part 1 - Where a synchronous answer is actually needed

The whole write side of this service is one aggregate, `Order`, and every cross-service question it
asks is about products, which the inventory service owns. Orders stores a `ProductId` on each line
and nothing else about the product: no name, no price, no stock. That is the boundary, and it is
why these calls exist.

### 1.1 - The two dependencies

| | Placing or amending an order | Approving / invoicing an order |
|---|---|---|
| **Aggregate** | `Order` | `Order` |
| **Commands** | `CreateOrderCommand`, `UpdateOrderItemsCommand` | `ApproveOrderCommand`, `GenerateInvoiceCommand` |
| **Handled in** | `OrderCommandService` (before dispatch) | `ApproveOrderCommandHandler`, `GenerateInvoiceCommandHandler` |
| **Question** | Does each product exist, can it cover the quantity, and what does it cost? | Is the stock this order was accepted on *still* there? |
| **Endpoint** | `GET /products?ids=` | `GET /products?ids=` |
| **Response** | An array of products | An array of products |
| **If the answer is no** | 404 `ProductNotFoundException` / 400 `InsufficientStockException` | the same, and the order does not move |

They look similar and are not the same question. The first is *validation of a reference*: a
product id that arrived in a JSON body may be a typo, and an order line pointing at a product
nobody stocks is meaningless. The second is *validation of a condition that expires*: an order can
sit pending for days, and the stock it was accepted on can be sold to someone else in the meantime.
Only asking at creation time would approve orders that cannot be fulfilled; only asking at approval
time would let customers place orders for products that never existed.

Prices come back from the same call, which is why creation cannot skip it even in principle. A
price is inventory's to state, and the aggregate is not allowed to go and fetch one - see "Why the
aggregate never holds a client" below.

### 1.2 - The contract with the inventory team

Addressed as `inventory` through Consul - the name the inventory service registers itself under.

```
GET /products/{id}
  200 -> {"id":1,"name":"Desk lamp","price":19.99,"availableQuantity":100}
  404 -> the product does not exist

GET /products?ids=1&ids=2
  200 -> [ {...}, {...} ]        products that do not exist are absent from the array,
                                  not an error
```

Only four fields, because those are the four the ordering flow reads. Inventory is free to change
everything else about a product without breaking this service.

This contract is not just prose: `InventoryClientPactTest` exercises the real client against Pact's
mock provider and writes `target/pacts/orders-inventory.json`. Inventory verifies itself against
that file, so if either endpoint changes shape, their build fails rather than our runtime.

**Why the batch endpoint.** `/products/{id}` alone would do, and that is how this started. But
approval checks *every line of an order at once*, inside the command's unit of work - so a ten-line
order meant ten sequential HTTP calls with a database transaction held open across all of them.
`GET /products?ids=` makes it one call whatever the order's size. The one-product endpoint stays,
because a single lookup should not have to pretend to be a list.

Absent-rather-than-404 for unknown ids in a batch is the other half of that decision: one bad id in
a ten-line order is a fact about that line, and failing the whole request would lose the answer for
the nine good ones.

---

## Part 2 & 3 - The client

`@EnableFeignClients` and `@EnableDiscoveryClient` are both on `OrdersApplication`. There is no
address for the inventory service anywhere in this repository, and that is the arrangement, not an
omission: `name = "inventory"` is resolved through Consul, so the client is load-balanced across
whatever instances are registered and healthy at the moment of the call.

That is what makes an instance moving, dying or scaling a non-event here - nothing is redeployed,
nothing is edited, and an instance that stops answering its health check stops receiving traffic
about fifteen seconds later. A pinned `url` would give all of that up for one address that has to
be kept correct by hand, which is why `ServiceDiscoveryTest` fails the build if one reappears.

The name has to match the inventory service's own `spring.application.name` exactly - that is what
it registers under. Ours is `orders`, pinned explicitly as
`spring.cloud.consul.discovery.service-name` so it cannot drift away from what other teams put in
their clients.

One interface per target service, not per endpoint:

```kotlin
@FeignClient(
    name = "inventory",
    path = "/products",
    fallbackFactory = InventoryClientFallbackFactory::class
)
interface InventoryClient {
    @GetMapping("/{productId}") fun getProduct(@PathVariable productId: Long): InventoryProduct
    @GetMapping             fun getProducts(@RequestParam("ids") ids: List<Long>): List<InventoryProduct>
}
```

### Why the aggregate never holds a client

The exercise injects the Feign client as a parameter of a `@CommandHandler` on the aggregate. This
service does something slightly different, for the reason the exercise's own rule exists.

`ApproveOrderCommand` and `GenerateInvoiceCommand` are handled by **external command handlers** -
plain Spring components that load the aggregate from Axon's repository and call a method on it:

```kotlin
@Component
class ApproveOrderCommandHandler(
    private val orderRepository: Repository<Order>,
    private val inventoryCatalog: InventoryCatalog
) {
    @CommandHandler
    fun handle(command: ApproveOrderCommand) {
        val order = orderRepository.load(command.orderId.value)
        inventoryCatalog.verifyStockAvailable(order.invoke { it.requestedQuantities() })
        order.execute { it.approve(command) }
    }
}
```

The rule the exercise is protecting is "the client must not be a field of the aggregate", and it
is not: the aggregate has no idea inventory exists. Handling the command in a component instead of
on the aggregate takes it one step further - the aggregate keeps only the rules it can decide on
its own (a pending order is the only kind that can be approved), and the check that needs the
network lives where a network call is an ordinary thing to do. Constructor injection is then the
natural way to get the dependency, rather than a handler parameter.

The ordering the exercise asks for holds either way: the stock check runs **before** `approve()` is
called, so a shortfall means no event is applied and the order is untouched. That is asserted in
`StockRecheckTest` and shown in [6.3](#63---the-other-service-is-down) below.

### Typed exceptions, one per failure

| Exception | HTTP | Means |
|---|---|---|
| `ProductNotFoundException` | 404 | Inventory answered, and has no such product |
| `InsufficientStockException` | 400 | Inventory has it and cannot cover the quantity |
| `InventoryUnavailableException` | 503 | Inventory did not answer |

The third is the one that matters most, and the next section is about keeping it distinct from the
first.

---

## Part 4 - The circuit breaker, and what a fallback should return

```yaml
spring.cloud.openfeign.circuitbreaker.enabled: true
spring.cloud.openfeign.circuitbreaker.group.enabled: true
```

### The fallback returns a refusal, not a value

The exercise's fallback returns a safe default - `false` from an existence check. That is right
when the question is genuinely a boolean. Here it would be a lie with consequences.

`getProduct` returning null means "inventory does not stock this product". If the fallback returned
that when inventory is *down*, then every order placed during an outage would be rejected with
"product 7 does not exist in inventory". The customer would believe it. Support would go looking
for a catalogue problem. Nobody would learn that inventory was unreachable, because the service
would be busy answering confidently and wrongly.

So the conservative default here is not a value but a refusal: fail the call at once with
`InventoryUnavailableException`, which the exception handler maps to **503 - try again later**,
which is the truth. Nothing hangs, no order is created on a guess, and the service keeps serving
every request that does not need inventory.

### Why a factory rather than a plain fallback

With the circuit breaker enabled, *every* failure reaches the fallback - including a 404 that
inventory answered perfectly correctly. A fallback that cannot see the cause cannot tell "inventory
says there is no such product" from "inventory said nothing at all", and those two have to become
different HTTP statuses.

Only `FallbackFactory` is handed the cause. So `InventoryClientFallbackFactory` (`@Component`)
builds an `InventoryClientFallback` around it, and that fallback rethrows a `NotFound` untouched
and converts everything else into `InventoryUnavailableException`. One fallback per client
interface, covering every method.

The same distinction is made a second time, in `FeignInventoryCatalog`, for the paths where no
breaker is in play - the Pact tests build the client directly.

### Tuning

```yaml
resilience4j.circuitbreaker.configs.default:
  sliding-window-size: 10
  minimum-number-of-calls: 5
  failure-rate-threshold: 50
  wait-duration-in-open-state: 10s
  ignore-exceptions: [ feign.FeignException.NotFound ]

spring.cloud.openfeign.client.config.default:
  connect-timeout: 2000
  read-timeout: 3000
```

`ignore-exceptions` is not a detail. Without it, ordinary traffic for products that do not exist
would count as failures, open the breaker, and take out the valid orders alongside them - a service
tripping its own breaker by working correctly.

The timeouts are short on purpose. A caller waiting on an order is not helped by a request that
eventually succeeds after thirty seconds; it is helped by a fast, honest 503.

---

## Part 5 - Correlation id

Two halves, and the exercise only names one.

**Outbound** (`CorrelationIdInterceptor`, registered globally in `FeignConfig` so no call goes out
untagged) puts `X-Correlation-ID` on every Feign request, unless the caller already set one.

**Inbound** (`CorrelationIdFilter`) is the half that makes it a *correlation* id. It adopts the id
the caller sent, or starts one if this service is where the request began, holds it in the SLF4J
MDC for the life of the request, and echoes it on the response. Without it the interceptor would
mint a fresh UUID for every outgoing call, each hop would carry a different id, and nothing would
be tied to anything - a unique id per request is not a trace.

Held in the MDC rather than a bare thread local so it also reaches the log output: an id you cannot
grep for is not tracing anything. Cleared in a `finally`, because servlet threads are pooled and a
leftover id would stamp itself on the next, unrelated request.

One configuration line exists only to keep this working:

```yaml
spring.cloud.circuitbreaker.resilience4j.disable-threadpool: true
```

By default the circuit breaker's time limiter runs the guarded call on its own thread pool. The MDC
is thread-local, so the interceptor would find nothing there and generate a fresh id for every
outgoing call - defeating the whole mechanism, silently, in exactly the failure scenarios where a
trace matters most. Running the call on the caller's thread keeps the id; the HTTP read timeout is
what bounds the call.

---

## Part 6 - Verification

Run against the shared infrastructure (`docker compose up -d` at the repo root: Kafka, Consul,
Keycloak with the `erp` realm imported), the orders service on 8090, and a stub standing in for the
inventory team's service on 8081, speaking the contract in Part 1.2. Tokens are real, from
Keycloak: `customer/customer` holds CLIENT, `staff/staff` holds ADMIN.

### 6.1 - Normal operation

```console
$ curl -i -X POST http://localhost:8090/orders \
    -H "Authorization: Bearer $CUSTOMER" -H "Content-Type: application/json" \
    -H "X-Correlation-ID: demo-6-1" \
    -d '{"name":"John","surname":"Doe","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}'

HTTP/1.1 201
X-Correlation-ID: demo-6-1
Content-Type: application/json

{"id":"Order:321921b0-3143-4fde-96bd-3b88589b1ac6","customer":{"name":"John","surname":"Doe"},
 "customerId":"83df9dea-96fa-4a61-b9f1-e95e9a3ba78f","status":"PENDING",
 "date":"2026-09-05T23:19:49.481536",
 "items":[{"id":1,"productId":1,"quantity":2,"price":19.99,"lineTotal":39.98},
          {"id":2,"productId":2,"quantity":1,"price":49.50,"lineTotal":49.50}],
 "transactions":[],"hasInvoice":false,"totalAmount":89.48,"totalPaid":0}
```

On the inventory side - two products, one request, carrying the caller's id:

```
inventory-stub  "GET /products?ids=1&ids=2 HTTP/1.1" 200  X-Correlation-ID=demo-6-1
```

Prices and the order total come from that response; nothing about a product is stored here beyond
its id.

### 6.2 - Validation rejection

A product inventory has never heard of:

```console
$ curl -i -X POST http://localhost:8090/orders ... -d '{"...","items":[{"productId":999,"quantity":1}]}'
HTTP/1.1 404
{"status":404,"message":"Product with id Product:999 does not exist in inventory"}
```

And one it stocks but cannot cover (product 3, zero available):

```console
HTTP/1.1 400
{"status":400,"message":"Insufficient stock for product Product:3 to fulfil quantity 1"}
```

Two different answers, because they are two different problems - and neither is the 503 below.

### 6.3 - The other service is down

Inventory stopped, seven creation requests:

```
call 1  status=503  total=0.011557s
call 2  status=503  total=0.007199s
call 3  status=503  total=0.009117s
call 4  status=503  total=0.005836s
call 5  status=503  total=0.005298s
call 6  status=503  total=0.005145s
call 7  status=503  total=0.005447s

{"status":503,"message":"The inventory service is currently unavailable"}
```

Milliseconds, not the thirty seconds an unguarded client would take. And the service log shows the
breaker doing its job - the first calls actually try, and then stop trying:

```
1  feign.RetryableException: Connection refused executing GET http://localhost:8081/products?ids=1
2  feign.RetryableException: Connection refused executing GET http://localhost:8081/products?ids=1
3  feign.RetryableException: Connection refused executing GET http://localhost:8081/products?ids=1
4  CallNotPermittedException: CircuitBreaker 'InventoryClientgetProductsList' is OPEN and does not permit further calls
5  CallNotPermittedException: CircuitBreaker 'InventoryClientgetProductsList' is OPEN and does not permit further calls
6  CallNotPermittedException: CircuitBreaker 'InventoryClientgetProductsList' is OPEN and does not permit further calls
7  CallNotPermittedException: CircuitBreaker 'InventoryClientgetProductsList' is OPEN and does not permit further calls
```

That is the difference between a retry loop and a circuit breaker: from call 4 on, no connection is
attempted at all.

Meanwhile the service is entirely healthy for anything that does not need inventory:

```
GET /orders/all  status=200  total=0.024978s
```

The second dependency behaves the same way. Approving an order with inventory down:

```
approve with inventory down  status=503  total=0.011056s
{"status":503,"message":"The inventory service is currently unavailable"}

$ curl http://localhost:8090/orders/Order:abc986c2-...
Order:abc986c2-fc5f-456a-b0a9-2edebcbaa818 PENDING
```

Still `PENDING` - the validation failed before `approve()` was reached, so no event was applied and
nothing about the order changed.

**Recovery.** Inventory restarted, and after the breaker's 10s open window:

```
call 1  status=201  total=0.014646s
call 2  status=201  total=0.012187s
call 3  status=201  total=0.012181s

inventory-stub  "GET /products?ids=1 HTTP/1.1" 200  X-Correlation-ID=demo-6-3-recovery
```

No restart, no manual reset: the breaker half-opens, the trial calls succeed, and it closes.

### In the build

The manual run above is reproduced by tests, so a regression fails the build rather than a demo:

- `InventoryResilienceTest` - a real HTTP stub on a real socket: a live target, a 404 that stays a
  404, a 500 that becomes an outage, the correlation id arriving on the wire, and a stopped target
  failing in under two seconds and recovering when it comes back.
- `InventoryCircuitBreakerTest` - the breaker opening against a dead address, and the calls after
  it never reaching the network.
- `InventoryClientFallbackTest`, `CorrelationIdInterceptorTest`, `CorrelationIdFilterTest` - the
  decisions each piece makes, in isolation.
- `InventoryClientPactTest` - both endpoints of the contract, published for the inventory team.
