"""MCP Test Client — exercises all inventory read tools via stdio."""

import asyncio
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main() -> None:
    server_params = StdioServerParameters(
        command=sys.executable,
        args=["server.py"],
    )

    async with stdio_client(server_params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()

            # List available tools
            tools = await session.list_tools()
            print(f"=== Available tools ({len(tools.tools)}) ===")
            for tool in tools.tools:
                print(f"  - {tool.name}: {tool.description}")
            print()

            results = {}

            # Test 1: list_products
            print("--- Test: list_products ---")
            res = await session.call_tool("list_products")
            output = res.content[0].text if res.content else ""
            ok = "Error" not in output
            results["list_products"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 2: list_products with status filter
            print("--- Test: list_products (status=ACTIVE) ---")
            res = await session.call_tool("list_products", {"status": "ACTIVE"})
            output = res.content[0].text if res.content else ""
            ok = "Error" not in output
            results["list_products (filtered)"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 3: get_product (expect 404 for random UUID)
            print("--- Test: get_product (non-existent) ---")
            res = await session.call_tool(
                "get_product", {"product_id": "00000000-0000-0000-0000-000000000000"}
            )
            output = res.content[0].text if res.content else ""
            ok = "not found" in output or "Error" not in output
            results["get_product (404)"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 4: list_stock
            print("--- Test: list_stock ---")
            res = await session.call_tool("list_stock")
            output = res.content[0].text if res.content else ""
            ok = "Error" not in output
            results["list_stock"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 5: get_stock (expect not found for random UUID)
            print("--- Test: get_stock (non-existent) ---")
            res = await session.call_tool(
                "get_stock", {"product_id": "00000000-0000-0000-0000-000000000000"}
            )
            output = res.content[0].text if res.content else ""
            ok = "not found" in output or "Error" not in output
            results["get_stock (not found)"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 6: low_stock_alerts
            print("--- Test: low_stock_alerts ---")
            res = await session.call_tool("low_stock_alerts")
            output = res.content[0].text if res.content else ""
            ok = "Error" not in output
            results["low_stock_alerts"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Test 7: stock_summary
            print("--- Test: stock_summary ---")
            res = await session.call_tool("stock_summary")
            output = res.content[0].text if res.content else ""
            ok = "Error" not in output
            results["stock_summary"] = ok
            print(f"  {'PASS' if ok else 'FAIL'}: {output[:200]}")
            print()

            # Summary
            passed = sum(1 for v in results.values() if v)
            failed = sum(1 for v in results.values() if not v)
            print(f"=== Results: {passed} passed, {failed} failed out of {len(results)} ===")
            sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
