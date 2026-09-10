"""MCP Server — ERP tools over the API gateway.

Exposes the inventory read model and the ordering lifecycle as MCP tools, reaching both through
the api-gateway rather than each service's own port.

## Why the gateway

`RoutesConfig` in the gateway is the one place that maps the public URL space onto the services
behind it - it absorbs the fact that inventory serves `/api/*` while orders serves `/orders` and
`/invoices`. Talking to the services directly would mean re-deriving that mapping here, in a second
place that has to be kept in step with the first, and it would go around the token check rather
than through it. Everything below addresses `/api/inventory/...` and `/api/orders/...`, which is
the same URL space a browser or a curl script sees, and every call carries a bearer token.

## Authentication

One grant, because there is one thing this server is: the `erp-mcp` confidential client, whose
service account holds ADMIN and CUSTOMER. It authenticates with client credentials against the same
`erp` realm that issues every other token in the system, so the client id and secret are this
server's whole credential - there is nothing for a person driving it to log into.

That is what keeps the three ways of reaching it identical. A terminal client, a Streamlit UI and
LM Studio all speak MCP to this process and nothing else; none of them holds an ERP credential, and
none of them needs a Keycloak client of its own.

The subject of that token is the service account, so orders placed by `create_order` belong to
*it* - they will not show up as the sample customer's, and only the same service account can cancel
them. That is a property of the ordering rules, not a limitation here: an order belongs to
whoever's token placed it.

Tokens are cached until shortly before they expire and then re-fetched; a 401 from the gateway
also forces one retry with a fresh token, which covers a token invalidated early (a realm reimport,
a revoked service account).

## Writes

The write tools are registered only when ERP_ENABLE_WRITES is true, which is the default. Setting
it to false leaves the read tools in place and removes the others from the catalogue entirely, so a
model pointed at this server cannot approve an order it merely hallucinated a reason for. Worth
doing wherever the conversation on the other end is not trusted: a tool that can `POST /api/orders`
is reachable by anything that can put text in front of the model.

## Transports

Two, chosen with MCP_TRANSPORT, because MCP clients disagree about how they want to reach a
server:

  stdio (default)  The client launches this file itself and talks over the pipe. Nothing listens
                   on a port, so there is no URL to configure and no way to point a client at the
                   wrong one.
  streamable-http  This process listens on MCP_HOST:MCP_PORT and serves MCP under /mcp, for
                   clients that are configured with a URL instead of a command.

The distinction matters because the failure is silent and confusing in one direction: a client
configured with a URL while this runs on stdio will happily connect to whatever *is* on that port
- the gateway on 8088, Keycloak on 8080 - and report their 404 or 415 as an MCP error.
"""

import os
import time
from typing import Any, Optional

import httpx
from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel, Field

# The gateway, not a service: these paths are the public ones from RoutesConfig.
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:8088")

# Keycloak's token endpoint as *this process* reaches it. On the host that is localhost:8080; from
# inside the compose network it is keycloak:8080. Only tokens are fetched here - they are validated
# by the gateway against the issuer that minted them, so the hostname used to collect one does not
# have to match the issuer string.
TOKEN_URL = os.environ.get(
    "KEYCLOAK_TOKEN_URL", "http://localhost:8080/realms/erp/protocol/openid-connect/token"
)

CLIENT_ID = os.environ.get("MCP_CLIENT_ID", "erp-mcp")
CLIENT_SECRET = os.environ.get("MCP_CLIENT_SECRET", "erp-mcp-secret")

ENABLE_WRITES = os.environ.get("ERP_ENABLE_WRITES", "true").lower() not in ("false", "0", "no")

TRANSPORT = os.environ.get("MCP_TRANSPORT", "stdio")

REQUEST_TIMEOUT = float(os.environ.get("ERP_HTTP_TIMEOUT", "10.0"))

mcp = FastMCP(
    "erp-mcp-server",
    host=os.environ.get("MCP_HOST", "127.0.0.1"),
    port=int(os.environ.get("MCP_PORT", "8765")),
)


# --------------------------------------------------------------------------------------- tokens


class _TokenCache:
    """One access token, re-fetched when it is about to expire.

    A token is asked for on nearly every tool call, so fetching one per call would put a Keycloak
    round trip in front of every request for no gain - the realm issues them with an hour's
    lifespan. The 30-second margin is there because the token has to still be valid when the
    *gateway* checks it, not when we send it.
    """

    def __init__(self) -> None:
        self._token: Optional[str] = None
        self._expires_at: float = 0.0

    def invalidate(self) -> None:
        self._token = None
        self._expires_at = 0.0

    async def get(self) -> str:
        if self._token and time.monotonic() < self._expires_at:
            return self._token

        form = {
            "grant_type": "client_credentials",
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
        }

        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            resp = await client.post(TOKEN_URL, data=form)
            resp.raise_for_status()
            payload = resp.json()

        self._token = payload["access_token"]
        self._expires_at = time.monotonic() + max(payload.get("expires_in", 60) - 30, 10)
        return self._token


_tokens = _TokenCache()


# ------------------------------------------------------------------------------------- requests


class ErpError(Exception):
    """An HTTP call that came back as something other than success, phrased for the model."""


async def _call(method: str, path: str, json_body: Any = None) -> Any:
    """One authenticated request to the gateway, retried once on a 401.

    The retry is not a loop: a 401 that survives a freshly minted token is a real authorization
    failure - a missing role, a realm that was reimported without the client - and repeating it
    only turns one clear error into several.
    """
    for attempt in (1, 2):
        token = await _tokens.get()
        async with httpx.AsyncClient(base_url=GATEWAY_URL, timeout=REQUEST_TIMEOUT) as client:
            resp = await client.request(
                method, path, json=json_body, headers={"Authorization": f"Bearer {token}"}
            )

        if resp.status_code == 401 and attempt == 1:
            _tokens.invalidate()
            continue

        if resp.is_success:
            # 204s and empty bodies are legitimate answers from the write endpoints.
            return resp.json() if resp.content else None

        raise ErpError(_describe(resp))

    raise ErpError("Unreachable")


def _describe(resp: httpx.Response) -> str:
    """Turn a failed response into something worth reading.

    The body matters here: a 400 from the orders service is a bean-validation message naming the
    field that was wrong ("quantity must be at least 1"), and a 409 is the aggregate refusing -
    "an approved order cannot be rejected". Both are exactly what the caller needs to fix the call,
    and both are lost if this only reports the status code.
    """
    detail = (resp.text or "").strip()
    if len(detail) > 800:
        detail = detail[:800] + "..."

    if resp.status_code == 401:
        return (
            "Error: 401 Unauthorized - the gateway rejected the token. Check MCP_CLIENT_ID and "
            "MCP_CLIENT_SECRET, and that the erp realm is imported."
        )
    if resp.status_code == 403:
        return (
            "Error: 403 Forbidden - authenticated, but this identity lacks the role for that "
            f"operation. {detail}"
        )
    if resp.status_code == 404:
        return f"Error: 404 Not found. {detail}"
    return f"Error: HTTP {resp.status_code}. {detail}"


async def _run(method: str, path: str, json_body: Any = None) -> str:
    """The body every tool shares: make the call, render whatever comes back as text.

    ConnectError is separated out because it is the one failure that is never about the request -
    it means nothing is listening at GATEWAY_URL, and saying which URL was tried is the whole fix.
    """
    try:
        return str(await _call(method, path, json_body))
    except ErpError as e:
        return str(e)
    except httpx.ConnectError:
        return f"Error: Cannot connect to the API gateway at {GATEWAY_URL}"
    except httpx.HTTPStatusError as e:
        # Only the token endpoint raises this - _call renders the gateway's own failures itself.
        return f"Error: Could not obtain a token from Keycloak: HTTP {e.response.status_code}"
    except httpx.HTTPError as e:
        return f"Error: {type(e).__name__}: {e}"


# ------------------------------------------------------------------------- inventory (read only)


@mcp.tool()
async def list_products(status: Optional[str] = None) -> str:
    """List all products.

    Args:
        status: Optional filter by status (e.g. "ACTIVE", "INACTIVE").
    """
    query = f"?status={status}" if status else ""
    return await _run("GET", f"/api/inventory/products{query}")


@mcp.tool()
async def get_product(product_id: str) -> str:
    """Get a product by its ID.

    Args:
        product_id: The product UUID.
    """
    return await _run("GET", f"/api/inventory/products/{product_id}")


@mcp.tool()
async def list_stock() -> str:
    """List all stock items."""
    return await _run("GET", "/api/inventory/stock")


@mcp.tool()
async def get_stock(product_id: str) -> str:
    """Get the stock record for a specific product.

    Args:
        product_id: The product UUID to check stock for.
    """
    return await _run("GET", f"/api/inventory/stock/{product_id}")


@mcp.tool()
async def low_stock_alerts() -> str:
    """Get all stock items that are below their reorder threshold."""
    return await _run("GET", "/api/inventory/stock/low-stock")


@mcp.tool()
async def stock_summary() -> str:
    """Get aggregate stock statistics (total products, total on hand, total reserved)."""
    return await _run("GET", "/api/inventory/stock/summary")


# ----------------------------------------------------------------------------- orders (read only)


@mcp.tool()
async def list_orders() -> str:
    """List every order in the system, in any status. Back-office view."""
    return await _run("GET", "/api/orders/all")


@mcp.tool()
async def get_order(order_id: str) -> str:
    """Get one order by its id.

    Args:
        order_id: The order id in its string form, e.g. "Order:9f1c...".
    """
    return await _run("GET", f"/api/orders/{order_id}")


@mcp.tool()
async def list_orders_by_status(status: str) -> str:
    """List the orders currently in a given status - use it to work a queue.

    Args:
        status: One of PENDING, APPROVED, REJECTED, CANCELLED.
    """
    return await _run("GET", f"/api/orders/by-status/{status}")


@mcp.tool()
async def my_orders() -> str:
    """List the orders belonging to the identity this server authenticates as.

    That identity is the `erp-mcp` service account, so this returns the orders this server placed
    itself rather than any person's - which is also the set `cancel_order` is allowed to withdraw.
    """
    return await _run("GET", "/api/orders/mine")


@mcp.tool()
async def get_invoice(invoice_id: str) -> str:
    """Get an invoice by its own id.

    Args:
        invoice_id: The invoice id in its string form, e.g. "Invoice:3ab2...".
    """
    return await _run("GET", f"/api/orders/invoices/{invoice_id}")


# --------------------------------------------------------------------------------- orders (write)


class OrderItem(BaseModel):
    """One line of an order. `product_id` is an inventory product UUID, not a stock id."""

    product_id: str = Field(description="Inventory product UUID.")
    quantity: int = Field(ge=1, description="How many units, at least 1.")


class InvoiceLineItem(BaseModel):
    """One line of an invoice. Unlike an order line it carries its own price, because an invoice is
    a snapshot: correcting one must not reprice it against what inventory charges today."""

    inventory_item_id: str = Field(description="Inventory product UUID.")
    quantity: int = Field(ge=1, description="How many units, at least 1.")
    price: float = Field(ge=0, description="Unit price on the invoice.")


def _order_items(items: list[OrderItem]) -> list[dict]:
    # The API is camelCase; the tool schema is snake_case, because that is what reads naturally as
    # a tool argument. The translation lives here so no tool has to remember it.
    return [{"productId": i.product_id, "quantity": i.quantity} for i in items]


def _invoice_items(items: list[InvoiceLineItem]) -> list[dict]:
    return [
        {"inventoryItemId": i.inventory_item_id, "quantity": i.quantity, "price": i.price}
        for i in items
    ]


if ENABLE_WRITES:

    @mcp.tool()
    async def create_order(name: str, surname: str, items: list[OrderItem]) -> str:
        """Place a new order.

        Every line is validated against inventory - the product must exist and have enough on hand
        - and the order is created PENDING, priced at what inventory quoted. The customer is taken
        from this server's token, not from `name`/`surname`, which are only the name on the order.

        Args:
            name: Customer's first name.
            surname: Customer's surname.
            items: The order lines.
        """
        body = {"name": name, "surname": surname, "items": _order_items(items)}
        return await _run("POST", "/api/orders", body)

    @mcp.tool()
    async def update_order_items(order_id: str, items: list[OrderItem]) -> str:
        """Replace an order's lines wholesale and reprice them against inventory.

        Allowed while the order is pending or approved, and only until an invoice has been issued
        for it. This is a replacement, not a merge: lines left out are removed.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
            items: The complete new set of lines.
        """
        return await _run("PUT", f"/api/orders/{order_id}/items", {"items": _order_items(items)})

    @mcp.tool()
    async def approve_order(order_id: str) -> str:
        """Approve a pending order, committing the stock behind it.

        Re-checks that the stock the order was accepted on is still there, then announces
        order.approved so inventory can commit the goods. Requires the ADMIN role.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
        """
        return await _run("POST", f"/api/orders/{order_id}/approve")

    @mcp.tool()
    async def reject_order(order_id: str) -> str:
        """Reject a pending order: nothing is committed and no invoice follows.

        Only a pending order can be rejected. Requires the ADMIN role.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
        """
        return await _run("POST", f"/api/orders/{order_id}/reject")

    @mcp.tool()
    async def cancel_order(order_id: str) -> str:
        """Withdraw a pending or approved order, releasing anything reserved against it.

        Money already taken on an approved order is reversed. The order must belong to the
        identity this server authenticates as - one customer cannot cancel another's order.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
        """
        return await _run("POST", f"/api/orders/{order_id}/cancel")

    @mcp.tool()
    async def register_payment(order_id: str, amount: float, payment_type: str) -> str:
        """Record money received against an order.

        Payments accumulate until the order is paid off, which is what allows it to be invoiced.
        An order cannot be paid beyond its total - an attempt to is rejected outright rather than
        partially accepted.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
            amount: The amount received, greater than zero.
            payment_type: One of CASH, CARD, BANK_TRANSFER.
        """
        body = {"amount": amount, "paymentType": payment_type}
        return await _run("POST", f"/api/orders/{order_id}/payments", body)

    @mcp.tool()
    async def generate_invoice(order_id: str, embg: str) -> str:
        """Issue the single invoice an order may have.

        The order must be approved and fully paid off. The EMBG is the customer's 13-digit national
        id; it is held on the invoice and never leaves the orders service on an event.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
            embg: Exactly 13 digits.
        """
        return await _run("POST", f"/api/orders/{order_id}/invoice", {"embg": embg})

    @mcp.tool()
    async def update_invoice_line_items(invoice_id: str, items: list[InvoiceLineItem]) -> str:
        """Correct an issued invoice's lines - a billing correction, not an amendment of the order.

        The order's own items are untouched. A reversed invoice can no longer be modified.

        Args:
            invoice_id: The invoice id, e.g. "Invoice:3ab2...".
            items: The complete new set of invoice lines, each with its price.
        """
        return await _run(
            "PUT", f"/api/orders/invoices/{invoice_id}/line-items", {"items": _invoice_items(items)}
        )

    @mcp.tool()
    async def reverse_invoice(invoice_id: str) -> str:
        """Undo a sale: refund the invoice total as a reversal transaction.

        Announces invoice.reversed. An invoice can only be reversed once.

        Args:
            invoice_id: The invoice id, e.g. "Invoice:3ab2...".
        """
        return await _run("POST", f"/api/orders/invoices/{invoice_id}/reverse")


if __name__ == "__main__":
    # A URL-configured client wants "streamable-http"; one that launches this file wants "stdio".
    mcp.run(transport=TRANSPORT)
