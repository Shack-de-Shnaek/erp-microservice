# MCP Server — ERP tools

An MCP (Model Context Protocol) server that exposes the ERP system as AI-callable tools: the
inventory read model, and the ordering lifecycle end to end.

One server for the whole system rather than one per service. MCP servers are configured on the
*client* side, so each additional server is another entry in every client's config, another process
to start, and another set of credentials - and a question that spans both services ("which pending
orders are blocked on stock?") has nowhere to live when the tools are split by service.

## What it offers

**Inventory (read):**

| Tool | Description |
|---|---|
| `catalog` | The catalogue joined to its stock: what exists, and how much of it can be ordered |
| `list_products` | List all products, optionally filtered by status |
| `get_product` | Get a product by its UUID |
| `list_stock` | List all stock items |
| `get_stock` | Get the stock record for a specific product |
| `low_stock_alerts` | List items below their reorder threshold |
| `stock_summary` | Aggregate statistics (total products, on-hand, reserved) |

`catalog` is the one to reach for before ordering. `list_products` and `list_stock` are inventory's
own two resources and neither answers "what can I order" alone - the catalogue carries no
quantities, the stock ledger carries no names or status, and a product whose stock is entirely
reserved for other orders reads as perfectly orderable in the first and as fully stocked in the
second. `catalog` joins them and says `available` and `orderable` outright.

**Orders (read):**

| Tool | Description |
|---|---|
| `list_orders` | Every order, in any status |
| `get_order` | One order by its id (`Order:9f1c...`) |
| `list_orders_by_status` | Orders in PENDING / APPROVED / REJECTED / CANCELLED |
| `my_orders` | Orders belonging to the identity this server authenticates as |
| `get_invoice` | An invoice by its own id (`Invoice:3ab2...`) |

**Orders (write)** — registered only when `ERP_ENABLE_WRITES` is true, which is the default:

| Tool | Description | Needs |
|---|---|---|
| `create_order` | Place an order; lines are validated and priced against inventory | — |
| `update_order_items` | Replace an order's lines wholesale and reprice them | — |
| `approve_order` | Approve a pending order, committing the stock behind it | ADMIN |
| `reject_order` | Reject a pending order | ADMIN |
| `cancel_order` | Withdraw a pending or approved order, releasing its reservations | own order |
| `register_payment` | Record money received against an approved order | — |
| `generate_invoice` | Issue the invoice for an approved, paid-off order | — |
| `update_invoice_line_items` | Correct an issued invoice's lines | — |
| `reverse_invoice` | Refund the invoice total as a reversal | — |

## How it communicates

```
MCP Client (LM Studio / Claude Desktop / opencode / terminal)
        │
        │ stdio (JSON-RPC 2.0)          — default
        │ or streamable HTTP on /mcp    — MCP_TRANSPORT=streamable-http
        ▼
   mcp-server (Python)
        │                                    ┌──────────┐
        │  client_credentials ─────────────▶│ Keycloak │  (erp realm)
        │  ◀─── access token                 └──────────┘
        │
        │ HTTP + Authorization: Bearer ...
        ▼
   API Gateway (:8088)
        │  /api/inventory/**  ──▶ inventory  /api/**
        │  /api/orders/**     ──▶ orders     /orders/**, /invoices/**
        ▼
   inventory-service        order-service
```

Every call goes through the gateway, at the same public URLs a browser or a curl script would use.
That is deliberate: `RoutesConfig` in the gateway is the single place that maps the public URL
space onto the services behind it - it absorbs the fact that inventory serves everything under
`/api` while orders serves `/orders` and `/invoices`. Reaching the services directly would mean
keeping a second copy of that mapping here, and would go around the token check rather than through
it. It also means service discovery and load balancing are the gateway's `lb://` routes, not
hardcoded ports.

## Authentication

One grant, because there is one thing this server is: the `erp-mcp` confidential client in
`keycloak/realm-erp.json`, with service accounts enabled and the ADMIN and CUSTOMER realm roles on
its service account. It takes a token with the client-credentials grant against the same `erp`
realm that issues every other token in the system. The client id and secret are the whole
credential; there is nothing for a person driving it to log into.

That is what keeps the three ways of reaching it (below) identical. A terminal client, the
Streamlit UI and LM Studio all speak MCP to this process and nothing else - none of them holds an
ERP credential of its own, and none of them needs a Keycloak client of its own.

The subject of that token is the service account, so orders placed with `create_order` belong to
*it* - they will not appear among the sample customer's orders, and only the same service account
can cancel them. That is the ordering rules working as intended: an order belongs to whoever's
token placed it.

Tokens are cached until 30 seconds before they expire and then re-fetched. A 401 from the gateway
also forces one retry with a fresh token, which covers a token invalidated early - a realm
reimport, a revoked service account. A 401 that survives the retry is reported as a real
authorization failure rather than retried in a loop.

> **On the write tools.** A tool that can `POST /api/orders` or approve one is reachable by
> anything that can put text in front of the model on the other end. Set `ERP_ENABLE_WRITES=false`
> wherever that conversation is not trusted: the write tools are then not registered at all, so
> they do not appear in the catalogue and cannot be called.

**Environment variables:**

| Variable | Default | Description |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8088` | API gateway base URL |
| `KEYCLOAK_TOKEN_URL` | `http://localhost:8080/realms/erp/protocol/openid-connect/token` | Token endpoint, as this process reaches it |
| `MCP_CLIENT_ID` | `erp-mcp` | Confidential client for the client-credentials grant |
| `MCP_CLIENT_SECRET` | `erp-mcp-secret` | Its secret |
| `ERP_ENABLE_WRITES` | `true` | `false` serves only the read tools |
| `ERP_HTTP_TIMEOUT` | `10.0` | Seconds, per request to the gateway |
| `MCP_TRANSPORT` | `stdio` | `stdio`, or `streamable-http` to listen on a port |
| `MCP_HOST` | `127.0.0.1` | Bind address, `streamable-http` only |
| `MCP_PORT` | `8765` | Port, `streamable-http` only. MCP is served under `/mcp` |

The token endpoint's hostname does not have to match the issuer. Under docker-compose tokens are
fetched from `keycloak:8080` while the `iss` claim still says `localhost:8080`, because
`KC_HOSTNAME` pins it - which is exactly what lets a token collected inside the network pass the
gateway's issuer check.

### Choosing a transport

Clients that *launch* the server (LM Studio's `command`, Claude Desktop, opencode) want **stdio**,
which is the default and needs no port. Clients configured with a **URL** need
`MCP_TRANSPORT=streamable-http`, and the URL is then `http://127.0.0.1:8765/mcp`.

Getting this backwards fails confusingly rather than cleanly: a URL-configured client will connect
to whatever else is on the port it was given and report that service's reply as an MCP error - a
404 from the gateway on 8088, a 415 "content-type did not match @Consumes" from Keycloak on 8080.
Neither is an MCP server; they are just the things listening there.

## Setup

The `erp-mcp` client has to exist in the realm. `--import-realm` creates the realm only if it is
not already there, so on a stack whose realm was imported before this client was added, either drop
the Keycloak state (`docker compose down -v`, which also drops the databases) or add the client by
hand in the admin console: **Clients → Create**, `erp-mcp`, client authentication on, service
accounts on, secret `erp-mcp-secret`, then **Service accounts roles → Assign role** → ADMIN and
CUSTOMER.

Then, for running it outside a container:

```bash
cd mcp-server
python -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Under docker-compose it is a service like any other and needs none of that:

```bash
docker compose up -d mcp-server     # serves streamable HTTP on http://localhost:8765/mcp
```

## Usage

### As an MCP server (stdio)

```bash
.venv/bin/python server.py
```

### As an MCP server over HTTP

For clients that are configured with a URL rather than a command:

```bash
MCP_TRANSPORT=streamable-http MCP_PORT=8765 .venv/bin/python server.py
# then point the client at http://127.0.0.1:8765/mcp
```

### With LM Studio

Program → Install → Edit `mcp.json`. Letting LM Studio launch the server (stdio) needs no port and
nothing started beforehand:

```json
{
  "mcpServers": {
    "erp-app": {
      "command": "/absolute/path/to/mcp-server/.venv/bin/python",
      "args": ["/absolute/path/to/mcp-server/server.py"],
      "env": {
        "GATEWAY_URL": "http://localhost:8088",
        "MCP_CLIENT_SECRET": "erp-mcp-secret"
      }
    }
  }
}
```

For a URL-style entry, either start the server with `MCP_TRANSPORT=streamable-http` or run the
compose service, and use:

```json
{
  "mcpServers": {
    "erp-app": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

### With opencode

Add to `opencode.jsonc` in the project root (already configured):

```jsonc
{
  "mcp": {
    "erp": {
      "type": "local",
      "command": [".venv/bin/python", "server.py"],
      "cwd": "mcp-server",
      "environment": {
        "GATEWAY_URL": "http://localhost:8088",
        "MCP_CLIENT_ID": "erp-mcp",
        "MCP_CLIENT_SECRET": "erp-mcp-secret",
        "ERP_ENABLE_WRITES": "true"
      }
    }
  }
}
```

### With Claude Desktop

```json
{
  "mcpServers": {
    "erp": {
      "command": "/absolute/path/to/mcp-server/.venv/bin/python",
      "args": ["/absolute/path/to/mcp-server/server.py"],
      "env": {
        "GATEWAY_URL": "http://localhost:8088",
        "MCP_CLIENT_SECRET": "erp-mcp-secret"
      }
    }
  }
}
```

## How it was tested

1. **Smoke test** (`test_client.py`): connects over stdio, lists the catalogue, calls every read
   tool bar `catalog`, and checks that deliberately absent ids come back 404 rather than as a
   transport error. `--write` adds a round trip that places an order against a product that
   actually has stock and cancels it again - nothing that cannot be undone - and that is where
   `catalog` is exercised, since it is what picks the product to order.
2. **Full lifecycle**, by hand against the running stack: create → update items → approve →
   invoice → get invoice → reverse, plus the refusals (`register_payment` on a pending order,
   `reject_order` on one already approved), confirming both that every gateway route resolves and
   that the aggregate's own error messages reach the caller intact.
3. **Containerised**: `docker compose up -d mcp-server`, then an MCP client against
   `http://localhost:8765/mcp` - verifying the service-account token is obtained over the Docker
   network and accepted by the gateway.

```bash
.venv/bin/python test_client.py            # reads only
.venv/bin/python test_client.py --write     # plus the create/cancel round trip
```

## Role in the architecture

The MCP server is the AI-facing adapter for the ERP system, and an ordinary gateway client rather
than a side door: it holds Keycloak credentials, presents a bearer token on every call, and is
authorized by the same realm roles as any other caller. Nothing it can do is anything a person with
the same roles could not do through the same URLs - which is the point. Restricting what the AI may
do is a matter of which roles its service account holds, and of `ERP_ENABLE_WRITES`, not of what
the tool list happens to contain.
