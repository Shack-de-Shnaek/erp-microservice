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
model pointed at this server cannot place or void an order it merely hallucinated a reason for. Worth
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

import json
import os
import time
from typing import Annotated, Any, Optional

import httpx
from mcp.server.fastmcp import FastMCP
from pydantic import Field

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


async def _render(action: Any) -> str:
    """The body every tool shares: do the work, render whatever comes back as text.

    Takes the work as a coroutine function rather than a method and path, so that the tools which
    make *several* calls and assemble an answer - `catalog` - report their failures in exactly the
    words the single-call tools do, instead of growing a second vocabulary for the same problems.

    ConnectError is separated out because it is the one failure that is never about the request -
    it means nothing is listening at GATEWAY_URL, and saying which URL was tried is the whole fix.
    """
    try:
        return str(await action())
    except ToolInputError as e:
        # The arguments never left this process. Naming what was wrong with them is the whole of
        # the answer, and "Error:" is what the smoke test and the models both read as a failure.
        return f"Error: {e}"
    except ErpError as e:
        return str(e)
    except httpx.ConnectError:
        return f"Error: Cannot connect to the API gateway at {GATEWAY_URL}"
    except httpx.HTTPStatusError as e:
        # Only the token endpoint raises this - _call renders the gateway's own failures itself.
        return f"Error: Could not obtain a token from Keycloak: HTTP {e.response.status_code}"
    except httpx.HTTPError as e:
        return f"Error: {type(e).__name__}: {e}"


async def _run(method: str, path: str, json_body: Any = None) -> str:
    """One call, rendered. What almost every tool below is."""
    return await _render(lambda: _call(method, path, json_body))


async def _collect(path: str, page_size: int = 200) -> list[dict]:
    """Every row behind a paged endpoint, following the pages to the end.

    Inventory serves its listings as Spring pages, which default to 20 rows. A tool that read only
    the first page would answer "this is the catalogue" with a fifth of it, and be wrong in the
    way that is hardest to notice - the answer looks complete. The page size is large because
    these are small listings and one request beats five; the loop is there for when they are not.
    """
    rows: list[dict] = []
    page = 0
    while True:
        sep = "&" if "?" in path else "?"
        body = await _call("GET", f"{path}{sep}page={page}&size={page_size}")
        # A listing that is not paged at all is a plain array; take it as the whole answer.
        if not isinstance(body, dict):
            return list(body or [])
        rows.extend(body.get("content") or [])
        if body.get("last", True) or not body.get("content"):
            return rows
        page += 1


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


@mcp.tool()
async def catalog(only_orderable: bool = False) -> str:
    """The catalogue and its stock in one answer: what exists, and how much of it can be ordered.

    Prefer this over `list_products` and `list_stock` when the next step is to place or amend an
    order. Those two are inventory's own resources and neither answers the question on its own:
    the catalogue says what a product *is* but not how much there is, the stock ledger says
    `on_hand` and `reserved` but not what the product is called. Deciding what can be ordered from
    them means joining the two by product id and subtracting - and a line that is on the shelf but
    entirely reserved for other orders reads, in the catalogue alone, as perfectly orderable. It
    is not, and `create_order` refuses it.

    Each row carries `available` (on hand minus what other orders are already holding, which is
    the number `create_order` is judged against, never `on_hand`) and `orderable`, which is that
    number being positive *and* the product still being sold. `product_id` is the id to pass to
    `create_order`.

    Args:
        only_orderable: Leave out everything that cannot be ordered right now - withdrawn products
            and ones whose stock is all spoken for. Use it to answer "what can I order"; leave it
            false to see the whole catalogue with the reason each line is or is not available.
    """

    async def build() -> list[dict]:
        products = await _collect("/api/inventory/products")
        stock = {
            row.get("productId"): row for row in await _collect("/api/inventory/stock")
        }

        rows = []
        for product in products:
            product_id = product.get("productId")
            # A product with no stock row is not an error: it is in the catalogue and simply has
            # nothing on the shelf, which is an availability of zero rather than a missing answer.
            held = stock.get(product_id) or {}
            on_hand = held.get("onHand", 0)
            reserved = held.get("reserved", 0)
            available = max(on_hand - reserved, 0)
            # Anything other than INACTIVE is read as still for sale, so a status inventory has
            # not told us about cannot start silently hiding products from the catalogue.
            active = not str(product.get("status") or "").upper() == "INACTIVE"
            rows.append(
                {
                    "product_id": product_id,
                    "name": product.get("name"),
                    "sku": product.get("sku"),
                    "unit_of_measure": product.get("unitOfMeasure"),
                    "status": product.get("status"),
                    "on_hand": on_hand,
                    "reserved": reserved,
                    "available": available,
                    "orderable": active and available > 0,
                }
            )

        if only_orderable:
            return [row for row in rows if row["orderable"]]
        return rows

    return await _render(build)


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


# Order and invoice lines arrive as plain objects rather than as declared models, and are read by
# the two functions below rather than by pydantic. That is a deliberate trade, made after the
# declared version turned out to be the thing standing between a model and a placed order.
#
# A `list[OrderItem]` parameter generates a schema that refers to the line's shape through
# `$defs`/`$ref`, and requires the property `product_id`. Two things went wrong with that. The
# strict clients - LM Studio among them - validate the model's arguments against that schema before
# sending anything, so a wrong key is not a failed call this server can answer helpfully: it is
# `Failed to parse arguments for tool "create_order": params.items.0 requires property
# "product_id"`, and the model is left guessing at a shape it cannot see. And the key it guesses
# wrong is not arbitrary - every response it has read from this system spells the field
# `productId`, because that is what the ERP API returns, so copying what it just saw is precisely
# what breaks. The weaker clients also flatten or drop `$defs` entirely, which leaves the line's
# fields undescribed at exactly the moment they matter.
#
# So the lines are declared as `Any` and the shape is stated in the parameter description, where
# every model reads it and no client can reject it. Declaring `list[dict]` instead would be the
# tidier-looking contract and was tried first, but it puts pydantic in front of the reader below:
# a single line sent unwrapped, or the `{"items": [...]}` of the API's own request body, is refused
# by the signature before anything here can accept it or explain it, and both are ordinary things
# for a model to send. What is lost is per-field validation by pydantic; what replaces it is
# stricter in the way that counts, because it answers in words a model can act on rather than in a
# parse failure it never sees.

_LINES_DESCRIPTION = (
    "The order lines, as a JSON array of objects: "
    '[{"product_id": "<product uuid>", "quantity": 2}]. '
    "`productId` is accepted for the id and `qty` for the quantity, since that is how the rest of "
    "the API spells them. The id is the bare uuid `catalog` reports, with no 'Product:' prefix."
)

_INVOICE_LINES_DESCRIPTION = (
    "The invoice lines, as a JSON array of objects: "
    '[{"inventory_item_id": "<product uuid>", "quantity": 2, "price": 9.99}]. '
    "`inventoryItemId` and `product_id` are accepted for the id. Unlike an order line each one "
    "carries its own price, because an invoice is a snapshot and correcting one must not reprice "
    "it against what inventory charges today."
)


class ToolInputError(Exception):
    """Arguments this server could not read, phrased so the next attempt is a better one.

    Separate from [ErpError]: nothing was sent anywhere, and the caller is the one that can fix it.
    It is rendered the same way, as text rather than as a raised exception, because a model that
    sees "Error: ..." reads it and tries again, where an exception is a dead end.
    """


def _key(name: Any) -> str:
    """A field name with the spelling filed off, so `product_id`, `productId` and `ProductID` are
    one key. The tolerance is deliberately this narrow: it forgives how a name is written, never
    which name was used."""
    return str(name).replace("_", "").replace("-", "").replace(" ", "").lower()


_ORDER_LINE_EXAMPLE = '{"product_id": "<product uuid>", "quantity": 2}'
_INVOICE_LINE_EXAMPLE = '{"inventory_item_id": "<product uuid>", "quantity": 2, "price": 9.99}'


class _Line:
    """One line as it arrived, readable by any spelling of its field names.

    It carries what it needs to complain about itself - which argument it came from, its position
    in it, and what a good line looks like - so that the readers below take a line and nothing
    else, and every message about a bad one is phrased the same way.

    [given] is kept because those messages are the point: telling a model that a line "has
    ['productid']" when it wrote `productId` sends it looking for a bug it did not write. What it
    sees quoted back is what it typed.
    """

    def __init__(self, raw: dict, index: int, what: str, example: str) -> None:
        self.index = index
        self.what = what
        self.example = example
        self.given = list(raw)
        self._by_key = {_key(name): value for name, value in raw.items()}

    def get(self, names: tuple[str, ...]) -> Any:
        for name in names:
            value = self._by_key.get(_key(name))
            if value is not None and value != "":
                return value
        return None

    def complain(self, problem: str) -> "ToolInputError":
        return ToolInputError(f"{self.what}[{self.index}] {problem} A line looks like: {self.example}")


def _rows(items: Any, what: str, example: str) -> list[_Line]:
    """Whatever arrived, as a list of readable lines.

    Three shapes beyond the expected one are taken, because they are what models actually send and
    each has exactly one sensible reading: a JSON string (the arguments were serialized twice), a
    single line that was not wrapped in an array, and `{"items": [...]}` (the request body copied
    from the API documentation rather than the tool's own arguments).
    """
    if isinstance(items, str):
        try:
            items = json.loads(items)
        except json.JSONDecodeError as e:
            raise ToolInputError(f"{what} is not valid JSON: {e}. Expected [{example}]") from None

    if isinstance(items, dict):
        inner = items.get("items")
        items = inner if isinstance(inner, list) else [items]

    if not isinstance(items, list) or not items:
        raise ToolInputError(f"{what} must be a non-empty array of line objects: [{example}]")

    lines = []
    for index, row in enumerate(items):
        if not isinstance(row, dict):
            raise ToolInputError(
                f"{what}[{index}] must be an object, not {type(row).__name__}: {example}"
            )
        lines.append(_Line(row, index, what, example))
    return lines


def _field(line: _Line, names: tuple[str, ...]) -> Any:
    value = line.get(names)
    if value is None:
        spelled = " or ".join(f"`{name}`" for name in names)
        raise line.complain(f"has no {spelled} - it has {line.given}.")
    return value


def _product_id(line: _Line, names: tuple[str, ...]) -> str:
    value = str(_field(line, names)).strip()
    # The id the API takes is the bare uuid. A "Product:" in front of it is this system's own way
    # of writing the id in prose, so a model that picked one up from somewhere meant the right
    # product and wrote it the wrong way; that is worth accepting rather than refusing.
    return value[len("Product:"):] if value.startswith("Product:") else value


def _quantity(line: _Line) -> int:
    value = _field(line, ("quantity", "qty", "amount", "count"))
    # bool is an int in Python and `true` is not a quantity anyone meant.
    if isinstance(value, bool):
        raise line.complain(f"has a quantity of {value!r}, which is not a number.")
    try:
        quantity = int(str(value).strip())
    except (TypeError, ValueError):
        raise line.complain(f"has a quantity of {value!r}, which is not a whole number.") from None
    if quantity < 1:
        raise line.complain(f"has a quantity of {quantity}; it must be at least 1.")
    return quantity


def _price(line: _Line) -> float:
    value = _field(line, ("price", "unit_price", "amount"))
    try:
        price = float(str(value).strip())
    except (TypeError, ValueError):
        raise line.complain(f"has a price of {value!r}, which is not a number.") from None
    if price < 0:
        raise line.complain(f"has a price of {price}; it cannot be negative.")
    return price


def _order_items(items: Any) -> list[dict]:
    # The API is camelCase; the tool arguments read as snake_case, because that is what reads
    # naturally as a tool argument. The translation lives here so no tool has to remember it.
    names = ("product_id", "productId", "product", "id")
    return [
        {"productId": _product_id(line, names), "quantity": _quantity(line)}
        for line in _rows(items, "items", _ORDER_LINE_EXAMPLE)
    ]


def _invoice_items(items: Any) -> list[dict]:
    names = ("inventory_item_id", "inventoryItemId", "product_id", "productId", "id")
    return [
        {
            "inventoryItemId": _product_id(line, names),
            "quantity": _quantity(line),
            "price": _price(line),
        }
        for line in _rows(items, "items", _INVOICE_LINE_EXAMPLE)
    ]


if ENABLE_WRITES:

    @mcp.tool()
    async def create_order(
        name: str,
        surname: str,
        items: Annotated[Any, Field(description=_LINES_DESCRIPTION)],
    ) -> str:
        """Place a new order.

        Every line is validated against inventory - the product must exist and have enough on hand
        - and the order is created PENDING, priced at what inventory quoted. The customer is taken
        from this server's token, not from `name`/`surname`, which are only the name on the order.

        Check `catalog` first and order against its `available`, not its `on_hand`. A product can
        sit on a full shelf and still be unorderable, because another order is already holding all
        of it; the catalogue listing alone does not show that, and the whole order is refused if
        any one line cannot be covered.

        Args:
            name: Customer's first name.
            surname: Customer's surname.
            items: The order lines.
        """
        return await _render(
            lambda: _call(
                "POST",
                "/api/orders",
                {"name": name, "surname": surname, "items": _order_items(items)},
            )
        )

    @mcp.tool()
    async def update_order_items(
        order_id: str,
        items: Annotated[Any, Field(description=_LINES_DESCRIPTION)],
    ) -> str:
        """Replace an order's lines wholesale and reprice them against inventory.

        Allowed while the order is pending or approved, and only until an invoice has been issued
        for it. This is a replacement, not a merge: lines left out are removed.

        Args:
            order_id: The order id, e.g. "Order:9f1c...".
            items: The complete new set of lines.
        """
        return await _render(
            lambda: _call(
                "PUT", f"/api/orders/{order_id}/items", {"items": _order_items(items)}
            )
        )

    @mcp.tool()
    async def confirm_reservation(order_id: str) -> str:
        """Confirm an order's stock out of the warehouse, which is what approves the order.

        The goods leave the shelf: inventory drops the hold, subtracts the quantity from what is
        on hand, and announces stock.confirmed - which the orders service reads and uses to move
        the order to APPROVED. There is no way to approve an order directly; approval means the
        goods have actually gone, and only inventory knows that.

        The order reaches APPROVED a moment later, once the event has been delivered, so a
        get_order straight after this may still say PENDING.

        Args:
            order_id: The order id, which is also the reservation's order reference,
                e.g. "Order:9f1c...".
        """
        return await _run("POST", f"/api/inventory/reservations/{order_id}/confirm")

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
    async def update_invoice_line_items(
        invoice_id: str,
        items: Annotated[Any, Field(description=_INVOICE_LINES_DESCRIPTION)],
    ) -> str:
        """Correct an issued invoice's lines - a billing correction, not an amendment of the order.

        The order's own items are untouched. A reversed invoice can no longer be modified.

        Args:
            invoice_id: The invoice id, e.g. "Invoice:3ab2...".
            items: The complete new set of invoice lines, each with its price.
        """
        return await _render(
            lambda: _call(
                "PUT",
                f"/api/orders/invoices/{invoice_id}/line-items",
                {"items": _invoice_items(items)},
            )
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
