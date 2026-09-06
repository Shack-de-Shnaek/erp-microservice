"""MCP Server — Inventory Read Tools

Exposes inventory read-only queries as MCP tools.
Communicates directly with the inventory service via HTTP.
"""

import os
from typing import Optional

import httpx
from mcp.server.fastmcp import FastMCP

INVENTORY_URL = os.environ.get("INVENTORY_URL", "http://localhost:8081")

mcp = FastMCP("inventory-mcp-server")


async def _get(path: str) -> dict | list:
    async with httpx.AsyncClient(base_url=INVENTORY_URL, timeout=10.0) as client:
        resp = await client.get(path)
        resp.raise_for_status()
        return resp.json()


@mcp.tool()
async def list_products(status: Optional[str] = None) -> str:
    """List all products.

    Args:
        status: Optional filter by status (e.g. "ACTIVE", "INACTIVE").
    """
    params = f"?status={status}" if status else ""
    try:
        data = await _get(f"/api/products{params}")
        return str(data)
    except httpx.HTTPStatusError as e:
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


@mcp.tool()
async def get_product(product_id: str) -> str:
    """Get a product by its ID.

    Args:
        product_id: The product UUID.
    """
    try:
        data = await _get(f"/api/products/{product_id}")
        return str(data)
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            return f"Product {product_id} not found"
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


@mcp.tool()
async def list_stock() -> str:
    """List all stock items."""
    try:
        data = await _get("/api/stock")
        return str(data)
    except httpx.HTTPStatusError as e:
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


@mcp.tool()
async def get_stock(product_id: str) -> str:
    """Get the stock record for a specific product.

    Args:
        product_id: The product UUID to check stock for.
    """
    try:
        data = await _get(f"/api/stock/{product_id}")
        return str(data)
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            return f"No stock record found for product {product_id}"
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


@mcp.tool()
async def low_stock_alerts() -> str:
    """Get all stock items that are below their reorder threshold."""
    try:
        data = await _get("/api/stock/low-stock")
        return str(data)
    except httpx.HTTPStatusError as e:
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


@mcp.tool()
async def stock_summary() -> str:
    """Get aggregate stock statistics (total products, total on hand, total reserved)."""
    try:
        data = await _get("/api/stock/summary")
        return str(data)
    except httpx.HTTPStatusError as e:
        return f"Error: HTTP {e.response.status_code}"
    except httpx.ConnectError:
        return f"Error: Cannot connect to inventory service at {INVENTORY_URL}"


if __name__ == "__main__":
    mcp.run(transport="stdio")
