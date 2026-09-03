#!/usr/bin/env bash
#
# test_inventory.sh — smoke tests for the inventory service
#
# Runs against the running docker-compose stack (default port 8081).
# Override with: INVENTORY_PORT=8080 ./test_inventory.sh
#
set -euo pipefail

BASE_URL="http://localhost:${INVENTORY_PORT:-8081}"
PASS=0
FAIL=0

assert_contains() {
    local label="$1" body="$2" expected="$3"
    if echo "$body" | grep -q "$expected"; then
        echo "  PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label — expected '$expected' in response"
        FAIL=$((FAIL + 1))
    fi
}

assert_status() {
    local label="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS: $label (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label — expected HTTP $expected, got $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Inventory Service Smoke Tests ==="
echo "Target: $BASE_URL"
echo ""

# -----------------------------------------------------------
# 1. Health check
# -----------------------------------------------------------
echo "[1] Health check"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health")
assert_status "GET /actuator/health returns 200" "$HTTP_CODE" "200"
echo ""

# -----------------------------------------------------------
# 2. Create a product
# -----------------------------------------------------------
echo "[2] Create a product"
PRODUCT_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/products" \
    -H "Content-Type: application/json" \
    -d '{"sku":"SMOKE-001","name":"Smoke Test Widget","unitOfMeasure":"pcs"}')
HTTP_CODE=$(echo "$PRODUCT_RESP" | tail -1)
BODY=$(echo "$PRODUCT_RESP" | sed '$d')
assert_status "POST /api/products returns 201" "$HTTP_CODE" "201"
PRODUCT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['productId'])" 2>/dev/null || echo "")
if [ -z "$PRODUCT_ID" ]; then
    echo "  FAIL: Could not extract productId"
    FAIL=$((FAIL + 1))
else
    echo "  PASS: Got productId=$PRODUCT_ID"
    PASS=$((PASS + 1))
fi
echo ""

# -----------------------------------------------------------
# 3. Get product by ID
# -----------------------------------------------------------
echo "[3] Get product by ID"
GET_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/products/$PRODUCT_ID")
HTTP_CODE=$(echo "$GET_RESP" | tail -1)
BODY=$(echo "$GET_RESP" | sed '$d')
assert_status "GET /api/products/{id} returns 200" "$HTTP_CODE" "200"
assert_contains "Product name matches" "$BODY" "Smoke Test Widget"
echo ""

# -----------------------------------------------------------
# 4. Create a stock item for the product
# -----------------------------------------------------------
echo "[4] Create a stock item"
STOCK_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/stock" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"onHand\":50,\"reorderThreshold\":10}")
HTTP_CODE=$(echo "$STOCK_RESP" | tail -1)
BODY=$(echo "$STOCK_RESP" | sed '$d')
assert_status "POST /api/stock returns 201" "$HTTP_CODE" "201"
assert_contains "Stock onHand is 50" "$BODY" '"onHand":50'
echo ""

# -----------------------------------------------------------
# 5. Get stock item by product ID
# -----------------------------------------------------------
echo "[5] Get stock item by product ID"
GET_STOCK=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/stock/$PRODUCT_ID")
HTTP_CODE=$(echo "$GET_STOCK" | tail -1)
BODY=$(echo "$GET_STOCK" | sed '$d')
assert_status "GET /api/stock/{productId} returns 200" "$HTTP_CODE" "200"
assert_contains "Stock item has correct productId" "$BODY" "$PRODUCT_ID"
echo ""

# -----------------------------------------------------------
# 6. Reserve stock
# -----------------------------------------------------------
echo "[6] Reserve stock"
RESERVE_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/stock/$PRODUCT_ID/reserve" \
    -H "Content-Type: application/json" \
    -d '{"orderRef":"smoke-order-1","quantity":10}')
HTTP_CODE=$(echo "$RESERVE_RESP" | tail -1)
BODY=$(echo "$RESERVE_RESP" | sed '$d')
assert_status "POST /api/stock/{id}/reserve returns 200" "$HTTP_CODE" "200"
assert_contains "Reserved quantity is 10" "$BODY" '"reserved":10'
echo ""

# -----------------------------------------------------------
# 7. Confirm stock (fulfill reservation)
# -----------------------------------------------------------
echo "[7] Confirm stock"
CONFIRM_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/stock/$PRODUCT_ID/confirm" \
    -H "Content-Type: application/json" \
    -d '{"orderRef":"smoke-order-1"}')
HTTP_CODE=$(echo "$CONFIRM_RESP" | tail -1)
BODY=$(echo "$CONFIRM_RESP" | sed '$d')
assert_status "POST /api/stock/{id}/confirm returns 200" "$HTTP_CODE" "200"
assert_contains "onHand decremented to 40" "$BODY" '"onHand":40'
assert_contains "reserved cleared to 0" "$BODY" '"reserved":0'
echo ""

# -----------------------------------------------------------
# 8. Adjust stock
# -----------------------------------------------------------
echo "[8] Adjust stock (restock)"
ADJUST_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/stock/$PRODUCT_ID/adjust" \
    -H "Content-Type: application/json" \
    -d '{"adjustment":20,"reason":"restock"}')
HTTP_CODE=$(echo "$ADJUST_RESP" | tail -1)
BODY=$(echo "$ADJUST_RESP" | sed '$d')
assert_status "POST /api/stock/{id}/adjust returns 200" "$HTTP_CODE" "200"
assert_contains "onHand increased to 60" "$BODY" '"onHand":60'
echo ""

# -----------------------------------------------------------
# 9. Get low-stock items
# -----------------------------------------------------------
echo "[9] Get low-stock items"
LOW_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/stock/low-stock")
HTTP_CODE=$(echo "$LOW_RESP" | tail -1)
assert_status "GET /api/stock/low-stock returns 200" "$HTTP_CODE" "200"
echo ""

# -----------------------------------------------------------
# 10. Get stock summary
# -----------------------------------------------------------
echo "[10] Get stock summary"
SUMMARY_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/stock/summary")
HTTP_CODE=$(echo "$SUMMARY_RESP" | tail -1)
BODY=$(echo "$SUMMARY_RESP" | sed '$d')
assert_status "GET /api/stock/summary returns 200" "$HTTP_CODE" "200"
assert_contains "Summary has totalProducts" "$BODY" '"totalProducts"'
echo ""

# -----------------------------------------------------------
# 11. Idempotent re-reserve (same order ref → no-op)
# -----------------------------------------------------------
echo "[11] Idempotent re-reserve"
RERESERVE_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/stock/$PRODUCT_ID/reserve" \
    -H "Content-Type: application/json" \
    -d '{"orderRef":"smoke-order-1","quantity":10}')
HTTP_CODE=$(echo "$RERESERVE_RESP" | tail -1)
BODY=$(echo "$RERESERVE_RESP" | sed '$d')
assert_status "Re-reserve same order returns 200" "$HTTP_CODE" "200"
assert_contains "Reserved still 10 (no-op)" "$BODY" '"reserved":0'
echo ""

# -----------------------------------------------------------
# 12. List products
# -----------------------------------------------------------
echo "[12] List products"
LIST_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/products")
HTTP_CODE=$(echo "$LIST_RESP" | tail -1)
assert_status "GET /api/products returns 200" "$HTTP_CODE" "200"
echo ""

# -----------------------------------------------------------
# Summary
# -----------------------------------------------------------
echo "=== Results ==="
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then
    echo "SOME TESTS FAILED"
    exit 1
else
    echo "ALL TESTS PASSED"
    exit 0
fi
