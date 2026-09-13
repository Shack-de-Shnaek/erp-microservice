# Orders — aggregate design

Written answers for Laboratory Exercise 3, against the orders service in this repository.

---

## Part 1 — Understanding the aggregate

### 1.1 Identity

**Name:** `Order` (`finki.ukim.erp.orders.Order`).

**What it represents:** a customer's purchase — the goods asked for, the money paid against them,
and the invoice issued once they are paid for. One real order placed by one real person.

**Natural identifier:** `OrderId`, a UUID with an `Order:` prefix (`Order:9f1c…`). A purchase has no
pre-existing business number the way a student has an index number, so the system mints one. The
prefix means a row in `domain_event_entry`, a Kafka key or a log line says what kind of thing it
points at without a join.

### 1.2 State

| Field | Type | Changes after creation? |
|---|---|---|
| `id` | `OrderId` (typed id) | No — identity is fixed for life |
| `customer` | `CustomerName` (value object) | No |
| `customerId` | `String` (Keycloak subject) | No — ownership does not transfer |
| `date` | `LocalDateTime` | No |
| `status` | `OrderStatus` enum | Yes — the lifecycle |
| `orderItems` | `List<OrderItem>` (entities) | Yes, while pending or approved and no invoice exists |
| `transactions` | `List<Transaction>` (entities) | Append-only; never edited or deleted |
| `invoice` | `Invoice?` (entity) | Set once; afterwards only reversed |
| `version` | `Long` | Yes — optimistic locking |

`orderItems`, `transactions` and `invoice` are inside the aggregate rather than being aggregates of
their own because the rules span them: a payment may not exceed the order total, an invoice may not
be issued until the order is fully paid, and cancelling an approved order refunds what was
collected. A rule can only be enforced against state that changes together.

### 1.3 Relationships

**Incoming — Inventory → Orders.** Orders depends on inventory for two things: what a product costs
when a line is added, and whether there is enough of it to go ahead. It also reacts to
`ProductDeactivatedEvent`: an order still waiting for approval that asks for a withdrawn product
can never be fulfilled, so it is rejected now rather than at approval time (Part 5).

**Outgoing — Orders → Inventory.** Stock is *taken* synchronously, when the order is placed
(`POST /api/reservations`), because the customer is told there and then whether the order stands.
It comes *back* as an event: `OrderCancelledEvent`, `OrderRejectedEvent` and `InvoiceReversedEvent`
all publish `OrderNullifiedExternalEvent`, and inventory releases whatever it is holding. Orders
publishes these; what inventory does with them is inventory's business (Part 5.3).

**Outgoing — Orders → anything downstream.** Every domain event that is published at all goes to
its own topic, named after the event itself (`order.created`, `order.approved`, `invoice.generated`
…) by `AbstractEvent.topicFor`, and is documented as AsyncAPI by springwolf - so a reporting or
notification service can be added later without orders changing. Which events are published and
which are kept internal is the table under "What is published, and what is not".

---

## Part 2 — Value objects

`OrderId`, `InvoiceId`, `TransactionId`, `ProductId`, `Money`, `Quantity`, `Embg`, `InvoiceNumber`
and `CustomerName`. Each guard:

| Value object | Guard |
|---|---|
| `Money` | scale ≤ 2 — money is held to the cent. Negative is allowed and deliberate: a refund is a negative amount |
| `Quantity` | ≥ 1 — a line for zero items is not a line |
| `Embg` | exactly 13 digits |
| `InvoiceNumber` | matches `INV-XXXX…` |
| `CustomerName` | neither name nor surname blank |
| `ProductId` | none — it is inventory's own identifier, carried verbatim (see below) |

`ProductId` is the odd one out and deliberately so: it holds a string this service did not mint.
Product ids belong to inventory, which generates UUIDs, so there is no rule orders could enforce
about their shape that would not be a guess about another team's format. It was once a `Long`, on
the assumption that inventory keyed products by a number; it does not, and every call built on that
assumption was a guaranteed 404. Unlike `OrderId` and `InvoiceId` it also carries no `Product:`
prefix, so what is printed and what goes on the wire are the same string.

### How they are mapped, and one deviation from the exercise

The exercise asks for every value object to be `@Embeddable`. `CustomerName` is — it is two columns,
which is exactly what an embeddable is for. The single-valued ones are mapped with JPA
`AttributeConverter`s (`autoApply = true`) instead, and the identifiers with a Hibernate `UserType`
(`StringIdentifierUserType`). The reason is concrete rather than stylistic:

* An `@Embeddable` identifier is a **composite** key as far as Hibernate is concerned — one
  component, but composite. Hibernate 7 cannot take the row lock the aggregate's repository asks for
  when loading through `EntityManager.find(…, PESSIMISTIC_WRITE)` on a composite key; it fails with
  `UnsupportedOperationException: Not implemented yet`. This was not theoretical — it was the first
  thing that broke.
* JPA forbids an `AttributeConverter` on an identifier outright, so a `UserType` is the supported
  route for the ids specifically.

Both still store one value in one column, which is what "stored inline" was asking for.

### Why `require(...)` lives in the value object's constructor, not in the command handler

Because it makes the invalid value **unconstructible** rather than merely rejected in one place. A
check in a command handler holds for commands that go through that handler; a check in the
constructor holds for every path into the system — REST, a Kafka message, a test fixture, a
migration, code written next year — and for every value already in memory. Once a `Quantity` exists,
every reader knows it is ≥ 1 without looking, so `OrderItem`, `PricedItem`, `OrderItemEventData` and
the aggregate's arithmetic never re-check it. There is exactly one line of code that decides what a
valid quantity is, and it cannot be bypassed.

There is a visible payoff in this codebase: `InventoryCatalog.requireAvailable` used to read
`if (quantity < 1 || product.availableQuantity < quantity)`. The `quantity < 1` half is gone, because
a `Quantity` that small cannot be handed to it.

### Value object vs entity

An **entity** has identity: two of them with identical fields are still two different things, and
one can change while the other does not. A **value object** is its value: two with the same fields
are interchangeable, and it is replaced rather than modified.

From this aggregate:

* **Entity —** `OrderItem`. Two lines both reading "2 × product 1 at 25.00" are two lines. One can be
  removed while the other stays, which is why it carries a generated `id`.
* **Value object —** `Money("25.00")`. Every 25.00 is the same 25.00. Nothing "changes" a Money; the
  line simply comes to hold a different one.

`Order` itself is the clearest entity of all: its items, status and payments all change over its
lifetime and it is still the same order, because `OrderId` says so.

---

## Part 3 — Commands, events, and the lifecycle

Update commands (each with `@TargetAggregateIdentifier`): `UpdateOrderItemsCommand`,
`RegisterPaymentCommand`, `UpdateInvoiceLineItemsCommand`, `ReverseInvoiceCommand`, plus the
lifecycle commands below.

Lifecycle: `OrderStatus` is `PENDING → APPROVED | REJECTED | CANCELLED`, driven by
`ApproveOrderCommand`, `RejectOrderCommand` and `CancelOrderCommand`, each guarded against an
invalid transition (only a pending order can be approved or rejected; only a pending or approved
order can be cancelled, and only by the customer who placed it).

Two more lifecycle commands exist for the moves inventory causes rather than a person:
`ApproveOrderForConfirmedStockCommand` (the goods left the shelf, so the order is committed) and
`RejectOrderForWithdrawnStockCommand` (the goods are gone, so it cannot be). Both are driven by
Kafka messages, which can be redelivered, so both treat an order that has already moved as a no-op
rather than an error - the second delivery finds the world as the first one asked for it. The
withdrawal command refuses one case loudly: an order with money against it is never closed this
way, because rejection moves no money and doing so would strand a customer's payment.

`Order` is a **state-stored** aggregate: `@Aggregate(repository = "axonOrderRepository")` with
`@Entity`, loaded by the `GenericJpaRepository` in `AxonRepositoriesConfiguration` whose
`identifierConverter { OrderId(it) }` rebuilds the typed id from the routing key's string form.
Every handler follows the same three steps — build the event, `on(event)` to move state,
`apply(event)` to publish it.

Two of them are not `@CommandHandler`s on the aggregate at all. `approve` and `generateInvoice` are
plain methods called by external handlers (`ApproveOrderCommandHandler`,
`GenerateInvoiceCommandHandler`), because both have to ask inventory whether the order's
reservation still stands before the order may move, and an aggregate may not reach outside itself.
The rule about *which* orders may be approved stays on the aggregate, with the state it judges; only
the question that needs the network sits outside. See
[cross-service-calls.md](cross-service-calls.md).

### After three update commands and one status command, how many rows are in `DOMAIN_EVENT_ENTRY`?

**Five** — one for the creation and one for each of the four commands. (More, if a command emits
more than one event: cancelling a paid order applies both `PaymentReversedEvent` and
`OrderCancelledEvent`.)

What this says about how state is recovered depends on which kind of aggregate it is, and it is
worth being precise, because this project deliberately chose one:

* An **event-sourced** aggregate would rebuild itself by replaying all five rows in order, applying
  each to a blank instance. The event store would be the source of truth, and the tables merely a
  projection of it.
* This aggregate is **state-stored**. Those five rows are the *record* of what happened and the way
  other services find out, but current state is read from the `orders` row and its children in one
  `SELECT`. Nothing is replayed.

The five rows still matter: they are the audit trail, and they are what `KafkaEventForwarder`
publishes so inventory and anything downstream can react.

### Why the guard belongs in the `@CommandHandler` and not in `on(event)`

Because they answer different questions. The command handler decides *whether this should happen*;
`on(event)` records *that it did*. An event is in the past tense — refusing it is meaningless, since
it has already been applied and, for an event-sourced aggregate, would already be sitting in the
store. A guard in `on(event)` would make a stored event unreplayable and would make a
`ProductDeactivatedEvent` reaction fail on history it cannot change. Rejecting the *command* is the
only moment where refusing still means something: nothing has been written and the caller gets the
error.

---

## Part 4 — The read side

`OrderView`, `OrderItemView`, `InvoiceView`, `InvoiceLineItemView` and `TransactionView` are
`@Immutable` read-only entities over the same tables the aggregate writes, with
`OrderViewJpaRepository` (`findByStatus`, `findByCustomerId`, `findByStatusAndProduct`),
`InvoiceViewJpaRepository`, `TransactionViewJpaRepository`, read services, and GET endpoints:
`/orders/all`, `/orders/{id}`, `/orders/by-status/{status}`, `/orders/mine`, `/invoices/{id}`.
Missing rows raise `OrderNotFoundException` / `InvoiceNotFoundException` /
`TransactionNotFoundException`, mapped to 404 by `GlobalExceptionHandler`.

One implementation detail: the views are declared with `@Subselect` (plus `@Synchronize`) rather
than `@Table`. They read the same tables and the same columns, but Hibernate refuses to generate
DDL for two entities mapped to one table — `SQL strings added more than once for: invoice_line_item`.
`@Subselect` tells Hibernate this is a view over existing tables, so nothing is exported for it, and
`@Synchronize` makes it flush pending writes to those tables before querying, which is what keeps
read-after-write correct inside a command's transaction.

### Why `@Immutable` improves performance

Hibernate keeps a **snapshot** of every managed entity's loaded state so it can compare it field by
field at flush time and work out what changed. `@Immutable` tells it the entity will never be
written, so it takes no snapshot, does no dirty checking, and generates no UPDATE — the memory for
the second copy of every row is not allocated and the flush does not walk them. On a list endpoint
returning a few hundred orders with their items, that is the difference between holding one copy of
the result set and two, and between a flush that scans everything and one that skips it.

### The view and the aggregate share a table — one advantage and one risk

**Advantage:** there is nothing to keep in sync. A separate projection table would be a second copy
of the data that can lag, drift, or be rebuilt wrongly; here a read cannot return anything but what
the last committed command wrote, and a query is a plain `SELECT` against the row that already
exists. It also halves the storage and removes a whole class of "why is the read model stale" bugs.

**Risk:** the two are coupled through the schema without the compiler knowing. Renaming a column for
the aggregate silently breaks the view — nothing references one from the other, so only a runtime
query fails. The `@Subselect` text names the columns explicitly, which turns that into a startup
failure rather than a failure on the first request, but the coupling is real and worth knowing
about. The related risk is that the read side inherits the write side's shape: the views can only
serve what the aggregate happens to store, so a query needing a different shape means a real
projection, not another view.

---

## Part 5 — Reacting to another aggregate

### 5.1 The trigger

Inventory publishes `ProductDeactivatedEvent` when a product is withdrawn from sale. Every order
still **pending** that has a line for that product has become unfulfillable, so orders rejects them.
That is the same outcome they would get the moment anyone tried to approve them — the difference is
that the customer finds out now instead of after waiting.

This cannot live in either aggregate. Inventory's product has no idea orders exist, and would be
reaching across a service boundary and a database boundary to find out. An `Order` cannot notice
something that happened in another service at all — nothing calls it. The rule belongs to neither, so
it lives outside both, in `ProductDeactivatedEventHandler`, which listens to one and sends commands
to the other.

Only `PENDING` orders are touched. An approved order has already had stock committed against it, and
a rejected or cancelled one is finished; reopening either from here would be the handler overruling a
decision the aggregate already made.

### 5.2 The handler

`ProductDeactivatedEventHandler` is a plain `@Component`: it looks affected orders up through
`OrderViewReadService` and dispatches `RejectOrderCommand` through the `CommandGateway`, and there
is no Kafka in it at all. The `@KafkaListener` lives one layer out, on `KafkaEventConsumer`, which
reads the bytes, hands them to `ProductDeactivatedTranslator` and calls this handler with a
`ProductId`. That split is what lets the decision be read and tested without a broker in sight; see
the anti-corruption layer diagram below.

### 5.3 Outgoing event

`OrderNullifiedExternalEvent` (`order.nullified`). It is the one event inventory acts on, and it is
the *end* of an order rather than its approval: cancelled, rejected or refunded, orders says all
three in the same words, and inventory releases whatever it is holding for that order.

Approval is not the moment stock is committed, and used not to be otherwise only for a while. Stock
is taken when the order is **placed**, synchronously over `POST /api/reservations`, because the
customer is told there and then whether the order stands — a promise of goods must not rest on a
message still in flight. By approval time the goods are already aside, so `order.approved` is
published for anyone following the life of an order and nothing subscribes to it to reserve
anything.

Inventory's side of the release is `OrderLifecycleSaga`, and it releases against *its own*
reservation record rather than the lines on the event — an order edited after approval, or one
whose reservation partly failed, does not match them. That handler is inventory's to write; orders
only guarantees the events.

### Why `sendAndWait` rather than `send`

Each rejection has to have actually happened before the next is dispatched. With fire-and-forget the
handler would return having only *queued* the work: a failure part-way through surfaces later with
nothing to tie it to, and — worse — Kafka commits the offset as though every order had been dealt
with, so the ones that failed are never retried. Blocking means a failure stops the batch and the
message is redelivered.

### What if it processed 200 records sequentially with `sendAndWait`?

Each call is a full round trip — load the order, run the handler, flush, commit — so 200 of them run
in series, and the Kafka consumer thread is held for all of it. At even 20 ms each that is four
seconds of one poll; slower, and the broker stops seeing heartbeats, decides the consumer is dead,
rebalances the partition to somebody else, and the work is redone from the last committed offset —
possibly forever, if the batch never finishes inside `max.poll.interval.ms`. The whole batch is also
all-or-nothing in an unhelpful way: fail at record 150 and the first 149 rejections stand, but the
offset does not move, so redelivery repeats them.

What to do instead, roughly in order of how far you would go:

* Keep it sequential but bound the batch — page the query and raise `max.poll.interval.ms` so the
  window is honestly sized for the work.
* Move the work off the consumer thread: hand the affected ids to a task that processes them and
  acknowledges when done, so the listener returns immediately.
* Dispatch asynchronously with `send` and collect the futures, waiting on all of them at the end —
  concurrency without losing the guarantee that everything finished before the offset moves. Fine
  here, since the orders are different aggregates and their order relative to each other does not
  matter.
* If the volume is genuinely large, stop treating it as one reaction: emit one command per affected
  order onto a queue and let them be handled independently, with retries per order rather than per
  batch.

Making each rejection **idempotent** is worth doing regardless, since redelivery is normal in Kafka
rather than exceptional. Here it already is, in effect: re-rejecting an order that is no longer
pending fails its guard rather than corrupting anything.

### Why not call the aggregate's Axon repository directly from an event handler

Two reasons, and the first is the mundane one:

1. **A repository is not a search.** `Repository<Order>` can load exactly one aggregate, by id, so it
   cannot answer "which orders contain product 7" at all. That question belongs to the read side, and
   that is why the handler takes `OrderViewReadService`.
2. **Loading it to mutate it puts the rule in the wrong place.** Reaching in and setting
   `status = REJECTED` bypasses the guards, applies no event, tells nobody, and means the reason an
   order can be rejected now lives in two places — the aggregate and this handler. Going through the
   `CommandGateway` sends the same `RejectOrderCommand` a REST caller sends: the same handler, the
   same guard, the same event, the same audit row. The event handler decides *what should happen*;
   the aggregate remains the only thing that decides *whether it may*.

---

# Cross-service messaging (Laboratory Exercise 4)

## Event hierarchy

`AbstractEvent` → `OrderEvent` → the concrete events. Every event in this service now extends it,
which is what allows a single `@EventHandler` for `AbstractEvent` to publish all of them. What this
replaced was three publisher classes and a forwarder that named every event twice — adding an event
meant remembering two files, and forgetting one failed silently.

`eventTopic()` derives the topic from the event's own class name (`OrderApprovedEvent` →
`order.approved`), so a topic cannot be mistyped and the name of the topic is always the name of the
thing that happened. `AsyncApiDocumentation` derives the documented names from the same function.

## What is published, and what is not

| Internal event | Published as | Consumed by | Kept back |
|---|---|---|---|
| `OrderCreatedEvent` | `OrderCreatedExternalEvent` on `order.created` | anything tracking demand | customer name, Keycloak subject |
| `OrderApprovedEvent` | `OrderApprovedExternalEvent` on `order.approved` | nobody today — anyone following the life of an order; the stock was reserved at placement | prices, customer |
| `OrderCancelledEvent` | `OrderCancelledExternalEvent` on `order.cancelled` **and** `OrderNullifiedExternalEvent` on `order.nullified` | inventory, on the nullification — release the reservation | refunded amount |
| `InvoiceGeneratedEvent` | `InvoiceGeneratedExternalEvent` on `invoice.generated` | anything reconciling sales | **EMBG**, line-level detail |
| `InvoiceReversedEvent` | `InvoiceReversedExternalEvent` on `invoice.reversed` **and** `OrderNullifiedExternalEvent` on `order.nullified` | inventory, on the nullification — the sale is undone | — |
| `OrderItemsUpdatedEvent` | *internal* | — | a pending order commits nothing |
| `OrderRejectedEvent` | `OrderNullifiedExternalEvent` on `order.nullified` only | inventory — close the record for this order | *why* it was refused; there is no `order.rejected` |
| `PaymentCreatedEvent` | *internal* | — | how somebody paid is between them and us |
| `PaymentReversedEvent` | *internal* | — | as above |
| `InvoiceLineItemsUpdatedEvent` | *internal* | — | bookkeeping; the sale did not change |

The EMBG is the clearest case. It exists in this service for exactly one reason — printing a valid
tax document — and it was sitting on the internal event next to the invoice number. Publishing the
event wholesale would have put a citizen identification number on a topic every service in the
system can read. A separate external class makes leaving it out a decision rather than an oversight.
`ExternalEventPublishingTest` asserts it, and the customer's name and subject, never appear in a
payload.

`OrderApprovedEvent` and `OrderCancelledEvent` gained the order's lines in this exercise. They
previously carried only the order id, which is enough for this service (it can read its own
database) and useless to a consumer, who would have to call back and ask what was on the order
before it could reserve anything.

### `order.nullified`

Three different things end an order badly — it is cancelled, it is rejected, or its invoice is
reversed — and a consumer that is only *holding* something on the order's behalf cares about none of
that distinction. It needs to know the order is void so it can let go.

So all three publish `OrderNullifiedExternalEvent` on `order.nullified`, carrying a `reason` for
anyone who does want to tell them apart. A consumer subscribes to one topic and follows one rule,
and a fourth way for an order to end does not become a change in every consumer. This is why
`AbstractEvent.toExternalEvents()` returns a *list*: cancellation and reversal are two announcements
each, their own topic plus the nullification, and neither replaces the other.

`OrderRejectedEvent` is the one that changed character. It used to be purely internal on the
grounds that a rejected order never committed anything — true of stock, but not of a consumer that
opened a record the moment the order was created. It now publishes the nullification and nothing
else: no lines, because nothing was ever reserved, and no `order.rejected` topic, because *why* this
service refused an order is its own business.

## Layering

```
Order (aggregate)  --apply(event)-->  Axon event bus
                                          |
                              EventMessagingEventHandler   (no Kafka imports)
                                          |  toExternalEvents(): topic + payload, 0..n
                                    EventMessagingService   (no Kafka imports)
                                          |
                                  EventMessagingRepository  (port, no Kafka imports)
                                          |
                              KafkaMessagingRepositoryImpl  (the only class that knows Kafka)
```

The publishing handler runs on a **tracking** processor: the event store already holds the event, so
a broker outage should delay delivery and catch up, not fail the command that produced it.

## Consuming: the anti-corruption layer

```
product.deactivated  -->  KafkaEventConsumer          (JSON and Kafka; knows nothing of orders)
                             |  ProductDeactivatedExternalEventDTO   (our mirror of their JSON)
                           ProductDeactivatedTranslator (DTO in, ProductId out; no dependencies)
                             |
                           ProductDeactivatedEventHandler  (the decision; no Kafka in it)
                             |  commandGateway.sendAndWait
                           RejectOrderCommand --> Order
```

The same layering carries the three stock topics, which arrived later and reuse every piece of it:

```
stock.confirmed            -->  KafkaEventConsumer
                                   |  StockConfirmedExternalEventDTO
                                 StockEventTranslator  (DTO in, OrderId / StockWithdrawnFromOrder out)
                                   |
                                 StockLifecycleEventHandler   (the decision; no Kafka in it)
                                   |  commandGateway.sendAndWait
                                 ApproveOrderForConfirmedStockCommand --> Order

stock.reservation.released  \
stock.returned              /   same path, ending in RejectOrderForWithdrawnStockCommand —
                                but only when `reason` is WITHDRAWN_BY_INVENTORY. A release this
                                service brought about (ORDER_NULLIFIED) is read and dropped;
                                acting on it would mean every cancellation rejected its own order,
                                a loop feeding on its own output. A reason orders has not been
                                taught about becomes UNKNOWN and is likewise left alone.
```

The DTOs are declared in this service, not imported from inventory. Importing inventory's class
would look like less code and would hand another team the ability to break this service by renaming
a field — and would need a shared jar both services have to upgrade in step, which is most of the
coupling microservices exist to avoid. Every DTO carries `ignoreUnknown = true`, everything orders
does not need is nullable, and everything it does need is not, so a message missing the product id
fails immediately rather than becoming a command with a hole in it.

**One deviation.** The exercise's translator returns a command. This one returns a `ProductId`,
because a deactivated product does not name the thing to change: it takes a query to find which
orders are affected, and one message can produce any number of commands. Putting that lookup in the
translator would mean giving it a repository, and it would stop being a translation. The lookup
lives in the reaction handler, and the translator stays what it claims to be — types in, types out.

## Verification

Infrastructure: `docker compose up -d` from the repository root. `KafkaEndToEndTest` drives all of
this against the real broker and **skips** rather than fails when there is none, so `mvn test` still
passes on a machine without Docker.

### Producer (Task 6.1)

```
INFO f.u.e.o.r.i.KafkaMessagingRepositoryImpl : Published event [Order:d5988bbe-…] to topic order.created partition 0 offset 0
INFO f.u.e.o.r.i.KafkaMessagingRepositoryImpl : Published event [Order:ff3edb20-…] to topic order.created partition 0 offset 1
INFO f.u.e.o.r.i.KafkaMessagingRepositoryImpl : Published event [Order:ff3edb20-…] to topic order.approved partition 0 offset 0
```

Read back off the broker with the console consumer:

```
$ docker exec broker /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic order.created --from-beginning

{"orderId":"Order:d5988bbe-366c-409a-99f6-e8081d85a53c",
 "lines":[{"productId":"a3f1c2d4-5e6b-47a8-9c10-2b3d4e5f6a7b","quantity":1}],
 "totalAmount":199.99,"createdAt":"2026-09-05T22:36:51.098861917"}
{"orderId":"Order:ff3edb20-7080-4c84-a5e8-a68751c27c51",
 "lines":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}],
 "totalAmount":39.98,"createdAt":"2026-09-05T22:36:52.05485339"}

$ … --topic order.approved --from-beginning

{"orderId":"Order:ff3edb20-7080-4c84-a5e8-a68751c27c51",
 "lines":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}],
 "approvedAt":"2026-09-05T22:36:52.068609074"}
```

No customer name, no Keycloak subject, no prices — only what a consumer needs.

Product ids are inventory's UUIDs. An earlier capture of this transcript showed them as small
integers, from when this service assumed inventory keyed products by a number; it does not.

### Consumer (Task 6.2)

Publishing an inventory event by hand, in the shape another team's service would send:

```
$ echo '{"productId":"a3f1c2d4-5e6b-47a8-9c10-2b3d4e5f6a7b","name":"Standing desk"}' \
    | docker exec -i broker /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9092 --topic product.deactivated

INFO f.u.e.o.i.kafka.KafkaEventConsumer : Processed external event: product a3f1c2d4-… was deactivated
```

The pending order for that product moved to `REJECTED`, which is what the test asserts.

`productId` is a flat string, and that is the whole of what inventory sends
(`ProductDeactivatedExternalEvent` is one field). An earlier version of the DTO on this side
expected `{"productId":{"value":7}}` — a guess at a shape nothing ever published, which meant every
message failed to parse and was logged and dropped. `name` is accepted if present and ignored.

### Resilience (Task 6.3)

With the service stopped, a message was published to `product.deactivated` (product 2). On the next
startup it was consumed before anything else happened:

```
22:38:02.415  INFO f.u.e.o.i.kafka.KafkaEventConsumer : Processed external event: product 7c2e19b0-… was deactivated
22:38:02.795  INFO f.u.e.o.i.kafka.KafkaEventConsumer : Processed external event: product a3f1c2d4-… was deactivated
```

`auto-offset-reset: earliest` plus a committed group offset is what makes that work: the consumer
resumes where it left off rather than skipping whatever arrived while it was away.

One thing this exercise surfaced. On the very first run the consumer did **not** pick the message up,
because the topic did not exist when the listener subscribed — Kafka does not notify a consumer that
a topic has appeared; it finds out on the next metadata refresh, five minutes later by default.
`KafkaTopicsConfig` now declares every topic this service consumes — `product.deactivated`,
`stock.confirmed`, `stock.reservation.released` and `stock.returned` — so each exists the moment
this service does and the subscriptions take effect immediately. Topics this service *publishes* to are not declared:
Kafka creates them on first send, and their names come from the events, so listing them would be a
second place to keep in step with `eventTopic()`.

## Documentation

The generic publisher left springwolf nothing per-topic to scan for, so the AsyncAPI description at
`/springwolf/docs` is now assembled by `AsyncApiDocumentation` from the same event classes the
publisher works from, deriving topic names with the same `AbstractEvent.topicFor`. It documents
which topics exist, which direction each goes, and which message is on it;
`AsyncApiDocumentationTest` asserts that what is advertised matches what is actually published, and
that internal events are not advertised at all.
