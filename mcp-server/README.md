# MCP Server — Inventory Management

An MCP (Model Context Protocol) server that exposes inventory read operations as AI-callable tools.

## What it offers

Six read-only tools that query the inventory service through the API gateway:

| Tool | Description |
|---|---|
| `list_products` | List all products, optionally filtered by status |
| `get_product` | Get a product by its UUID |
| `list_stock` | List all stock items |
| `get_stock` | Get the stock record for a specific product |
| `low_stock_alerts` | List items below their reorder threshold |
| `stock_summary` | Aggregate statistics (total products, on-hand, reserved) |

## How it communicates

```
MCP Client (Claude Desktop / opencode / terminal)
        │
        │ stdio (JSON-RPC 2.0)
        ▼
   mcp-server (Python)
        │
        │ HTTP REST + Keycloak JWT
        ▼
   API Gateway (:8088)
        │
        │ Spring Cloud Gateway routing
        ▼
   Inventory Service (:8081)
```

The server never touches the database directly. All requests flow through the API gateway, which routes to the inventory service via Consul service discovery. Authentication uses Keycloak client credentials flow — the server automatically obtains and refreshes JWT tokens.

**Environment variables:**

| Variable | Default | Description |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8088` | API gateway base URL |
| `KEYCLOAK_URL` | _(empty)_ | Keycloak server URL (e.g. `http://localhost:8080`) |
| `KEYCLOAK_CLIENT_ID` | _(empty)_ | Client ID for Keycloak client credentials |
| `KEYCLOAK_CLIENT_SECRET` | _(empty)_ | Client secret from Keycloak |
| `BEARER_TOKEN` | _(empty)_ | Manual JWT token (fallback if Keycloak vars not set) |

If `KEYCLOAK_URL`, `KEYCLOAK_CLIENT_ID`, and `KEYCLOAK_CLIENT_SECRET` are all set, the server uses client credentials flow automatically. Otherwise it falls back to `BEARER_TOKEN` or sends no auth header.

## Authentication — Keycloak client credentials setup

1. Open Keycloak admin: `http://localhost:8080` (admin/admin)
2. Select the `finki-services` realm
3. Go to **Clients** → **Create client**
4. Fill in:
   - Client type: **OpenID Connect**
   - Client ID: `mcp-server`
5. Click **Next**
6. Enable:
   - **Client authentication**: ON
   - **Service accounts roles**: ON
7. Click **Next** → **Save**
8. Go to the **Credentials** tab → copy the **Client Secret**
9. Set `KEYCLOAK_CLIENT_SECRET` to the copied value

## How it was tested

1. **Unit smoke test** (`test_client.py`): connects via stdio, calls all tool variants, reports pass/fail
2. **Manual verification**: `echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | python server.py` returns the tool catalog
3. **Integration**: tested against the running `docker-compose` stack with the inventory service and gateway

## Role in the architecture

The MCP server is the AI-facing read adapter for the ERP system. It allows AI assistants (Claude Desktop, opencode, etc.) to query inventory data using the MCP protocol without needing direct database access or knowledge of the underlying REST API. This keeps the security boundary intact — reads go through the gateway's JWT validation — while making inventory data accessible to AI workflows.

## Setup

```bash
cd mcp-server
python -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Usage

### As an MCP server (stdio)

```bash
KEYCLOAK_URL=http://localhost:8080 \
KEYCLOAK_CLIENT_ID=mcp-server \
KEYCLOAK_CLIENT_SECRET=<your-secret> \
GATEWAY_URL=http://localhost:8088 \
.venv/bin/python server.py
```

### With opencode

Add to `opencode.jsonc` in the project root (already configured):

```jsonc
{
  "mcp": {
    "inventory": {
      "type": "local",
      "command": [".venv/bin/python", "server.py"],
      "cwd": "mcp-server",
      "environment": {
        "GATEWAY_URL": "http://localhost:8088",
        "KEYCLOAK_URL": "http://localhost:8080",
        "KEYCLOAK_CLIENT_ID": "mcp-server",
        "KEYCLOAK_CLIENT_SECRET": "<your-secret>"
      }
    }
  }
}
```

### With Claude Desktop

Add to your Claude Desktop MCP config:

```json
{
  "mcpServers": {
    "inventory": {
      "command": ".venv/bin/python",
      "args": ["server.py"],
      "cwd": "/path/to/mcp-server",
      "env": {
        "GATEWAY_URL": "http://localhost:8088",
        "KEYCLOAK_URL": "http://localhost:8080",
        "KEYCLOAK_CLIENT_ID": "mcp-server",
        "KEYCLOAK_CLIENT_SECRET": "<your-secret>"
      }
    }
  }
}
```

### Running the test client

```bash
GATEWAY_URL=http://localhost:8088 .venv/bin/python test_client.py
```
