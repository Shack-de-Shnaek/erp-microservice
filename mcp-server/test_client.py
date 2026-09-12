"""MCP Test Client — exercises the ERP tools over stdio.

Reads run by default. Passing --write also runs a write round trip: it places an order against a
real product and cancels it again, which leaves the read model where it found it apart from one
cancelled order. Nothing here approves, invoices or takes payment - those are not reversible, and
a smoke test should not need cleaning up after.

The environment is whatever `server.py` reads, so this exercises the same gateway and the same
credentials the configured clients will use:

    python test_client.py                # reads only, against localhost:8088
    python test_client.py --write        # plus the create/cancel round trip
"""

import asyncio
import ast
import os
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

MISSING_UUID = "00000000-0000-0000-0000-000000000000"

# Tools that take no arguments and should simply answer. `expect_missing` marks the calls that are
# *supposed* to come back empty-handed - a 404 there is the correct answer, not a failure.
READ_CASES: list[tuple[str, str, dict, bool]] = [
    ("list_products", "list_products", {}, False),
    ("list_products (status=ACTIVE)", "list_products", {"status": "ACTIVE"}, False),
    ("get_product (non-existent)", "get_product", {"product_id": MISSING_UUID}, True),
    ("list_stock", "list_stock", {}, False),
    ("get_stock (non-existent)", "get_stock", {"product_id": MISSING_UUID}, True),
    ("low_stock_alerts", "low_stock_alerts", {}, False),
    ("stock_summary", "stock_summary", {}, False),
    ("list_orders", "list_orders", {}, False),
    ("list_orders_by_status (PENDING)", "list_orders_by_status", {"status": "PENDING"}, False),
    ("my_orders", "my_orders", {}, False),
    ("get_order (non-existent)", "get_order", {"order_id": "Order:" + MISSING_UUID}, True),
    ("get_invoice (non-existent)", "get_invoice", {"invoice_id": "Invoice:" + MISSING_UUID}, True),
]

results: dict[str, bool] = {}


def record(label: str, output: str, ok: bool) -> None:
    results[label] = ok
    print(f"  {'PASS' if ok else 'FAIL'}: {output[:300]}")
    print()


async def call(session: ClientSession, tool: str, args: dict) -> str:
    res = await session.call_tool(tool, args)
    return res.content[0].text if res.content else ""


def is_error(output: str) -> bool:
    return output.startswith("Error:")


async def run_reads(session: ClientSession) -> None:
    for label, tool, args, expect_missing in READ_CASES:
        print(f"--- Test: {label} ---")
        output = await call(session, tool, args)
        # A 404 on a deliberately absent id is the expected answer; any other error is not.
        ok = ("404" in output or "not found" in output.lower()) if expect_missing else not is_error(output)
        record(label, output, ok)


async def run_writes(session: ClientSession) -> None:
    """Place an order against a product that can actually be ordered, then cancel it.

    It has to be a *real* product with enough on hand: the orders service validates every line
    against inventory before it will create anything, so an invented product id would only ever
    test the rejection path.

    The product comes from `catalog`, not from `list_stock`, for the reason that tool exists. The
    stock ledger carries no status, so choosing from it picks withdrawn products as readily as
    sold ones - and this test did, then reported the orders service correctly refusing one as a
    failure of the write path.
    """
    print("--- Test: create_order ---")
    catalog = await call(session, "catalog", {"only_orderable": True})
    product_id = pick_available_product(catalog)
    if product_id is None:
        record("create_order", "SKIP: no product with available stock to order", False)
        return

    output = await call(
        session,
        "create_order",
        {
            "name": "Smoke",
            "surname": "Test",
            "items": [{"product_id": product_id, "quantity": 1}],
        },
    )
    ok = not is_error(output)
    record("create_order", output, ok)
    if not ok:
        return

    order_id = extract_order_id(output)
    if order_id is None:
        record("cancel_order", f"Could not find an order id in the response: {output[:200]}", False)
        return

    print("--- Test: cancel_order ---")
    output = await call(session, "cancel_order", {"order_id": order_id})
    record("cancel_order", output, not is_error(output))


def pick_available_product(catalog_output: str) -> str | None:
    """Find a product that can be ordered right now.

    The tools return the service's JSON rendered with str(), so this reads it back the same way.
    It is deliberately forgiving: a shape it does not recognise means "skip the write tests", not
    a failure of the write path itself.
    """
    try:
        rows = ast.literal_eval(catalog_output)
    except (ValueError, SyntaxError):
        return None
    if not isinstance(rows, list):
        return None
    for row in rows:
        if not isinstance(row, dict):
            continue
        # `orderable` is already "in the catalogue, still sold, and some of it free", which is the
        # whole question - but the availability is checked too, so that asking for one unit is
        # something the row actually claims to support.
        if row.get("orderable") and row.get("available", 0) >= 1 and row.get("product_id"):
            return str(row["product_id"])
    return None


def extract_order_id(output: str) -> str | None:
    try:
        order = ast.literal_eval(output)
    except (ValueError, SyntaxError):
        return None
    if isinstance(order, dict):
        value = order.get("id")
        if isinstance(value, dict):
            value = value.get("id") or value.get("value")
        if value:
            return str(value)
    return None


async def main() -> int:
    include_writes = "--write" in sys.argv

    # The environment is forwarded explicitly: stdio_client passes the child a scrubbed one by
    # default, keeping little more than PATH, so GATEWAY_URL and the credentials would not reach
    # the server this launches. That is invisible on a host where the defaults are already right
    # and total where they are not - inside the compose network the scrubbed child looks for the
    # gateway on localhost and every test fails at once, against a system that is working.
    server_params = StdioServerParameters(
        command=sys.executable, args=["server.py"], env=dict(os.environ)
    )

    async with stdio_client(server_params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()

            tools = await session.list_tools()
            print(f"=== Available tools ({len(tools.tools)}) ===")
            for tool in tools.tools:
                first_line = (tool.description or "").strip().splitlines()[0:1]
                print(f"  - {tool.name}: {first_line[0] if first_line else ''}")
            print()

            await run_reads(session)
            if include_writes:
                await run_writes(session)
            else:
                print("(write tools not exercised; pass --write to include them)\n")

            passed = sum(1 for v in results.values() if v)
            failed = len(results) - passed
            print(f"=== Results: {passed} passed, {failed} failed out of {len(results)} ===")
            return 0 if failed == 0 else 1


if __name__ == "__main__":
    # The exit happens out here rather than inside the session: raising SystemExit inside anyio's
    # task group makes it a sub-exception of an ExceptionGroup, and the run ends in a page of
    # traceback that says nothing except that the tests finished.
    sys.exit(asyncio.run(main()))
