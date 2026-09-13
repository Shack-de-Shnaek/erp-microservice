# Contracts between orders and other services

Every place this service talks to another team's service, and the Pact test that pins it down.
There is one other service in play: **inventory**. The consumer and provider names below are the
ones written into the pact files, and they have to match on both sides - a provider only loads a
pact whose `provider.name` is its own, so a mismatch does not fail, it silently verifies nothing.

| # | Direction | Consumer | Provider | Endpoint / topic | Pinned by |
|---|-----------|----------|----------|------------------|-----------|
| 1 | HTTP out  | `orders` | `inventory` | `GET /api/products/{id}`, `GET /api/stock/{id}` | `clients/InventoryClientPactTest` |
| 2 | HTTP in   | `inventory` | `orders` | `GET /orders/{id}` | `pact/OrdersHttpProviderPactTest` |
| 3 | Kafka in  | `orders` | `inventory` | topic `product.deactivated` | `pact/ProductDeactivatedListenerTest` |
| 4 | Kafka in  | `orders` | `inventory` | topics `stock.confirmed`, `stock.reservation.released`, `stock.returned` | `handlers/StockLifecycleEventHandlerTest` (no pact — see below) |
| 5 | Kafka out | `inventory` | `orders` | topic `order.approved` | `pact/OrderApprovedProducerTest` |
| 6 | Kafka out | `inventory` | `orders` | topic `order.nullified` | `pact/OrderApprovedProducerTest` |

Both parties are the same two services in every row, so all of these contracts would be written to a
file called `orders-inventory.json` or `inventory-orders.json`. Where two of them would collide they
are kept apart by directory, not by renaming the parties:

```
target/pacts/orders-inventory.json          # 1 + 3, generated here, merged into one V4 pact
src/test/resources/pacts/http/inventory-orders.json      # 2, received from inventory
src/test/resources/pacts/messages/inventory-orders.json  # 5 + 6, received from inventory
contracts/published/orders-inventory.json   # committed copy of 1 + 3, for handing over
```

---

## 1. HTTP out - orders calls inventory

Consumer `orders`, provider `inventory`. Driven by `InventoryClient` (Feign), behind
`InventoryCatalog`.

Inventory keeps the catalogue and the stock ledger as separate resources, so one product costs two
calls and `InventoryCatalog` puts the halves back together. Product ids are opaque strings that
inventory generates (UUIDs in every deployment so far); orders carries them without interpreting
them.

`GET /api/products/{productId}` - the catalogue entry.

```json
{
  "productId": "11111111-1111-1111-1111-111111111111",
  "sku": "SKU-001",
  "name": "Widget",
  "unitOfMeasure": "pcs",
  "status": "ACTIVE"
}
```

`GET /api/stock/{productId}` - the stock ledger for that product. What orders may promise a customer
is `onHand - reserved`, never `onHand`.

```json
{
  "stockItemId": "22222222-2222-2222-2222-222222222222",
  "productId": "11111111-1111-1111-1111-111111111111",
  "onHand": 100,
  "reserved": 0,
  "reorderThreshold": 10
}
```

A `404` on the product path means "no such product" and is part of the contract: orders turns it
into `ProductNotFoundException`, and anything else into `InventoryUnavailableException`. The
difference matters - treating an outage as "product does not exist" would reject valid orders
whenever inventory is down. A 404 on the *stock* path is not the same thing: the product exists and
simply has nothing on the shelf, so it reads as an availability of zero.

Provider states: `a product with id exists`, `a stock item for the product exists`,
`a product whose stock is entirely reserved exists`, `no product with that id exists` - worded
exactly as inventory's `PactHttpProviderTest` implements them.

### Two open questions with the inventory team

- **No price anywhere.** Inventory's product resource carries `sku`, `name`, `unitOfMeasure` and
  `status`, and its `Product` aggregate has no money on it at all. Orders prices every line from
  the catalogue, so against the live service that price is zero. Either inventory grows a price, or
  a third service owns pricing; until then only the `mock-inventory` profile produces a priced
  order.
- **No batch lookup.** Inventory exposes one product by id and a paged listing of the whole
  catalogue, and neither is "these four ids", so an n-line order costs 2n calls inside a command's
  unit of work. The fix is a batch endpoint on inventory's side (`GET /api/products?ids=...` and the
  same on `/api/stock`); `InventoryResilienceTest` asserts the current cost so the improvement is
  visible when it lands.

## 2. HTTP in - inventory reads an order

Consumer `inventory`, provider `orders`.

`GET /orders/{id}`, where the id is this service's prefixed form, e.g.
`Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4`. The response is the whole `OrderView`; the contract
covers only the three fields inventory reads:

```json
{
  "id": "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4",
  "status": "APPROVED",
  "items": [ { "productId": "11111111-1111-1111-1111-111111111111", "quantity": 5, "price": 19.99 } ]
}
```

`404` when no such order has been projected. Extra fields in the real response (`customer`,
`totalAmount`, `transactions`, ...) do not break the contract - Pact fails on missing and mismatched
fields, not on additional ones - which is what leaves this service free to keep changing them.

Provider states: `order Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4 is approved and has two lines`,
`no order with id Order:00000000-0000-0000-0000-000000000000 has been placed`.

## 3. Kafka in - inventory deactivates a product

Consumer `orders`, provider `inventory`. Topic `product.deactivated`, consumed by
`KafkaEventConsumer`, which rejects every pending order for that product.

```json
{ "productId": "11111111-1111-1111-1111-111111111111" }
```

That is the whole message today, and the whole contract. A consumer pact is a *demand* on the
provider, so naming a field here obliges inventory to publish it forever - asking for a `name` or a
`deactivatedAt` it does not send would fail on their side the first time they verified this file.
Orders' DTO accepts both, nullable, in case they appear, and ignores anything else inventory adds
(`@JsonIgnoreProperties(ignoreUnknown = true)`) - so inventory can extend the event without
coordinating a release.

Message description: `Product Deactivated`.

### The rest of inventory's events, and why orders does not read them

Inventory publishes ten external events. Orders consumes four - `product.deactivated` above, and
the three stock movements in contract 4 below. The others are listed here so the decision is on the
record rather than implied by silence:

| Topic | Why orders does not consume it |
|---|---|
| `product.created` | Orders has no catalogue of its own to keep in step; it asks about a product when somebody tries to order it. |
| `product.updated` | Same. A renamed product does not change an order that was already priced. |
| `product.reactivated` | The mirror of deactivation, but there is nothing to undo - the orders it rejected are finished, and reopening them from here would overrule a decision the aggregate already made. |
| `stock.reserved` | The hold orders itself asked for over HTTP, echoed back. It already has the answer; reading this would be hearing it twice. |
| `stock.reservation.amended` | Same - the amendment was this service's `PUT /api/reservations/{orderRef}`, and it saw the result synchronously. |
| `stock.adjusted` | A stocktake correction. An adjustment that actually costs an order its goods surfaces as a release or a return, which *is* consumed. |

The one genuine gap is the opposite direction: inventory has no event for a reservation it could
**not** make. An approved order whose stock ran out between approval and the reservation attempt is
logged in inventory and never heard about in orders. Closing that needs a new event from inventory
(`stock.reservation.failed`), not a listener here.

## 4. Kafka in - the stock behind an order moves

Consumer `orders`, provider `inventory`. Topics `stock.confirmed`, `stock.reservation.released` and
`stock.returned`, consumed by `KafkaEventConsumer` and decided on by `StockLifecycleEventHandler`.

This is the half of the lifecycle that is *not* a question. Stock is taken synchronously when an
order is placed, because a customer told "your order is placed" has been promised goods and that
promise must not rest on a message in flight. Everything that happens to the stock afterwards is
news, and arrives as an event.

`stock.confirmed` - the goods for this order have left the shelf, so the order is approved:

```json
{
  "stockItemId": "22222222-2222-2222-2222-222222222222",
  "orderRef": "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4",
  "quantity": 5
}
```

`stock.reservation.released` and `stock.returned` - a hold let go, and confirmed goods put back.
Different facts on inventory's side, the same fact here, so they share a DTO and a handler:

```json
{
  "stockItemId": "22222222-2222-2222-2222-222222222222",
  "orderRef": "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4",
  "quantity": 5,
  "reason": "WITHDRAWN_BY_INVENTORY"
}
```

`orderRef` is the order's own id - inventory keys a reservation by the reference orders gave it -
so a message about a stock item is answerable against an order without this service knowing what a
stock item is.

**`reason` is the load-bearing field**, and the only one besides `orderRef` that orders requires. It
says whether this service asked for the release or inventory decided it, and getting that wrong is
severe in both directions: reading our own cancellations as withdrawals would reject every order
the moment it was cancelled, and reading withdrawals as our own would leave orders standing on
goods that are gone. `WITHDRAWN_BY_INVENTORY` rejects the order; `ORDER_NULLIFIED` is the echo of
this service's own `order.nullified` and is read and dropped. A reason orders has not been taught
about becomes `UNKNOWN` and is treated as *not* a withdrawal - the safe direction, since the order
is left alone rather than closed on a guess.

**No pact, deliberately — but `stock.confirmed` is no longer optional.** A consumer pact is a
demand on the provider, and the two *release* topics are still consumed best-effort: orders acts on
them when they arrive and is correct without them, since invoicing re-reads the reservation over
HTTP (contract 1) and would refuse an order whose goods had gone.

`stock.confirmed` is now the only thing that approves an order. Lose it and orders never leaves
`PENDING`, and no amount of HTTP re-reading recovers that — there is no endpoint that approves an
order any more. It is kept out of the pact suite for the practical reason that the message is
already covered end-to-end by `KafkaEndToEndTest` against a real broker, and by
`StockLifecycleEventHandlerTest` and `KafkaEventConsumerTest` on this side; but it is a hard
dependency now, not a best-effort one, and a pact on it would be defensible where one on the others
would not.

## 5. Kafka out - orders approves an order

Consumer `inventory`, provider `orders`. Topic `order.approved`, published by
`EventMessagingEventHandler` from `OrderApprovedExternalEvent`.

```json
{
  "orderId": "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4",
  "lines": [ { "productId": "11111111-1111-1111-1111-111111111111", "quantity": 5 } ],
  "approvedAt": "2026-09-05T10:15:30"
}
```

**Inventory no longer acts on this.** It once did - approval used to be the moment stock was
reserved - but the reservation moved to the moment the order is *placed*, over HTTP, where the
caller can be told "no" while it still matters. Approval now runs the other way entirely: it is
what *inventory* causes by confirming the goods out, so this topic carries inventory's own decision
coming back to it. `OrderLifecycleSaga` has nothing to do here and inventory's consumer does not
subscribe to the topic.

The contract is kept because the pact is still verified and the event is still published, for
anyone following the life of an order rather than only holding stock against it. The lines travel
with it so such a consumer need not call back. Prices and the customer are deliberately absent:
they are on the internal `OrderApprovedEvent` and are nobody else's business.

Message description: `Order Approved`. Provider state: `an order has been approved`.

## 6. Kafka out - an order is void

Consumer `inventory`, provider `orders`. Topic `order.nullified`, published by
`EventMessagingEventHandler` from `OrderNullifiedExternalEvent`.

```json
{
  "orderId": "Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4",
  "reason": "CANCELLED",
  "lines": [ { "productId": "11111111-1111-1111-1111-111111111111", "quantity": 5 } ],
  "nullifiedAt": "2026-09-05T10:15:30"
}
```

The counterpart to approval, and the one contract a consumer holding something for an order needs.
Every ending publishes it - `OrderCancelledEvent`, `OrderRejectedEvent` and `InvoiceReversedEvent` -
so a consumer has one topic and one rule rather than a list of endings it has to keep up to date.
`reason` is `CANCELLED`, `REJECTED` or `REFUNDED` for consumers that do care which; nothing is
required to read it.

`lines` is present where the event that produced it knew them and empty otherwise: a rejected order
never reserved anything, and a refund is announced by the invoice, which carries no lines. A
consumer should release against **its own record** for `orderId` rather than these lines - which is
what inventory's `OrderLifecycleSaga` does, because an order edited after approval, or one whose
reservation partly failed, does not match the lines on the event that ended it.

`order.cancelled` is still published alongside it, unchanged, for anyone following the life of an
order rather than only its ending. There is deliberately no `order.rejected`: *why* an order was
refused is this service's business.

Message description: `Order Nullified`. Provider state: `an order has been nullified`.

---

## Exchanging the files

There is no Pact Broker in this setup, so the files move by hand.

**Sending ours (contracts 1 and 3).** `mvn test` writes `target/pacts/orders-inventory.json` - one
V4 pact holding both the HTTP interactions and the message. Copy it over the committed one and hand
that file to the inventory team:

```sh
./mvnw test -Dtest='InventoryClientPactTest,ProductDeactivatedListenerTest'
cp target/pacts/orders-inventory.json contracts/published/orders-inventory.json
```

They put it under their `src/test/resources/pacts/` and their next `mvn test` verifies it. Anything
they change about `/api/products`, `/api/stock` or `product.deactivated` that this service depends
on now breaks
their build instead of our runtime.

**Receiving theirs (contracts 2, 5 and 6).** Inventory's consumer tests produce
`inventory-orders.json`. Both of their contracts with us carry that same name, so they go in
separate directories here:

- the HTTP one at `src/test/resources/pacts/http/inventory-orders.json`
- the message one at `src/test/resources/pacts/messages/inventory-orders.json`

Then `./mvnw test -Dtest='OrdersHttpProviderPactTest,OrderApprovedProducerTest'`.

A broker would replace all of this and additionally record which version of each side has verified
which contract, which is the part hand-exchanged files cannot give you: right now nothing stops a
stale pact sitting in this repository long after inventory has moved on.
