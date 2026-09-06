#!/usr/bin/env bash
#
# seed.sh — Populate the inventory database with sample data
#
# Usage: ./seed.sh
# Override port: INVENTORY_PORT=8080 ./seed.sh
#
set -euo pipefail

BASE_URL="http://localhost:${INVENTORY_PORT:-8081}"
PASS=0
FAIL=0

post() {
    local path="$1" data="$2"
    RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL$path" \
        -H "Content-Type: application/json" \
        -d "$data")
    HTTP_CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    echo "$BODY"
}

extract() {
    echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin)['$2'])" 2>/dev/null || echo ""
}

check() {
    local label="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  OK: $label"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Seeding inventory database ==="
echo "Target: $BASE_URL"
echo ""

# -----------------------------------------------------------
# 1. Health check
# -----------------------------------------------------------
echo "[1] Health check"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health")
check "GET /actuator/health" "$HTTP_CODE" "200"
echo ""

# -----------------------------------------------------------
# 2. Create products
# -----------------------------------------------------------
echo "[2] Creating products..."

declare -A PRODUCT_IDS=()

PRODUCTS=(
    "LAPTOP-001|Dell Latitude 5540|unit"
    "MOUSE-001|Logitech MX Master 3S|unit"
    "KB-001|Keychron K2 Mechanical Keyboard|unit"
    "MON-001|Dell U2723QE 27\" 4K Monitor|unit"
    "HEADSET-001|Sony WH-1000XM5 Headphones|unit"
    "USB-HUB-001|Anker 7-in-1 USB-C Hub|unit"
    "CABLE-001|HDMI 2.1 Cable 2m|unit"
    "PAPER-A4|Copy Paper A4 500 sheets|ream"
    "PEN-BLACK|Pilot G2 Black Gel Pen|dozen"
    "TONER-BK|HP 305A Black Toner|unit"
    "TONER-CY|HP 305A Cyan Toner|unit"
    "TONER-MG|HP 305A Magenta Toner|unit"
    "TONER-YL|HP 305A Yellow Toner|unit"
    "WHITEBOARD|Quartet Magnetic Whiteboard 4x3|unit"
    "MARKER-SET|Expo Dry Erase Markers 8pk|pack"
    "STAPLER-001|Swingline Heavy Duty Stapler|unit"
    "TAPE-DISP|Scotch Tape Dispenser|unit"
    "FOLDER-001|Manila File Folders 100pk|box"
    "LABEL-001|Dymo Labels 45012|roll"
    "MOUSEPAD-XL|Corsair MM350 XL Mousepad|unit"
)

for entry in "${PRODUCTS[@]}"; do
    IFS='|' read -r sku name uom <<< "$entry"
    RESP=$(post "/api/products" "{\"sku\":\"$sku\",\"name\":\"$name\",\"unitOfMeasure\":\"$uom\"}")
    PID=$(extract "$RESP" "productId")
    if [ -n "$PID" ]; then
        PRODUCT_IDS[$sku]="$PID"
        echo "  Created $sku -> $PID"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: Could not create $sku"
        FAIL=$((FAIL + 1))
    fi
done
echo ""

# -----------------------------------------------------------
# 3. Create stock items
# -----------------------------------------------------------
echo "[3] Creating stock items..."

STOCK_DATA=(
    "LAPTOP-001|45|10"
    "MOUSE-001|120|25"
    "KB-001|80|15"
    "MON-001|30|8"
    "HEADSET-001|60|12"
    "USB-HUB-001|200|50"
    "CABLE-001|350|75"
    "PAPER-A4|500|100"
    "PEN-BLACK|90|20"
    "TONER-BK|15|5"
    "TONER-CY|12|5"
    "TONER-MG|12|5"
    "TONER-YL|12|5"
    "WHITEBOARD|8|3"
    "MARKER-SET|40|10"
    "STAPLER-001|25|5"
    "TAPE-DISP|35|8"
    "FOLDER-001|100|30"
    "LABEL-001|50|10"
    "MOUSEPAD-XL|70|15"
)

for entry in "${STOCK_DATA[@]}"; do
    IFS='|' read -r sku on_hand reorder <<< "$entry"
    PID="${PRODUCT_IDS[$sku]:-}"
    if [ -z "$PID" ]; then
        echo "  SKIP: No productId for $sku"
        continue
    fi
    RESP=$(post "/api/stock" "{\"productId\":\"$PID\",\"onHand\":$on_hand,\"reorderThreshold\":$reorder}")
    S_ID=$(extract "$RESP" "stockItemId")
    if [ -n "$S_ID" ]; then
        echo "  Created stock for $sku (onHand=$on_hand, reorder=$reorder)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: Could not create stock for $sku"
        FAIL=$((FAIL + 1))
    fi
done
echo ""

# -----------------------------------------------------------
# 4. Stock adjustments (simulate restocking)
# -----------------------------------------------------------
echo "[4] Adjusting stock (simulating restocks)..."

for sku in "TONER-BK" "PAPER-A4" "USB-HUB-001"; do
    PID="${PRODUCT_IDS[$sku]:-}"
    [ -z "$PID" ] && continue
    RESP=$(post "/api/stock/$PID/adjust" '{"adjustment":25,"reason":"supplier restock"}')
    OH=$(extract "$RESP" "onHand")
    if [ -n "$OH" ]; then
        echo "  Restocked $sku to onHand=$OH"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: Could not restock $sku"
        FAIL=$((FAIL + 1))
    fi
done

# Negative adjustments (simulating damage/loss)
for sku in "MOUSE-001" "PEN-BLACK"; do
    PID="${PRODUCT_IDS[$sku]:-}"
    [ -z "$PID" ] && continue
    RESP=$(post "/api/stock/$PID/adjust" '{"adjustment":-5,"reason":"damaged in transit"}')
    OH=$(extract "$RESP" "onHand")
    if [ -n "$OH" ]; then
        echo "  Damaged $sku, onHand=$OH"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: Could not adjust $sku"
        FAIL=$((FAIL + 1))
    fi
done
echo ""

# -----------------------------------------------------------
# 5. Reserve stock (simulate pending orders)
# -----------------------------------------------------------
echo "[5] Reserving stock for pending orders..."

ORDERS=(
    "ORD-1001|MOUSE-001|5"
    "ORD-1001|KB-001|2"
    "ORD-1001|USB-HUB-001|3"
    "ORD-1002|LAPTOP-001|2"
    "ORD-1002|MON-001|2"
    "ORD-1003|PAPER-A4|10"
    "ORD-1003|TONER-BK|3"
    "ORD-1004|HEADSET-001|4"
    "ORD-1004|MOUSEPAD-XL|4"
)

for entry in "${ORDERS[@]}"; do
    IFS='|' read -r order_ref sku qty <<< "$entry"
    PID="${PRODUCT_IDS[$sku]:-}"
    [ -z "$PID" ] && continue
    RESP=$(post "/api/stock/$PID/reserve" "{\"orderRef\":\"$order_ref\",\"quantity\":$qty}")
    RSV=$(extract "$RESP" "reserved")
    if [ -n "$RSV" ]; then
        echo "  Reserved $qty of $sku for $order_ref (reserved=$RSV)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: Could not reserve $sku for $order_ref"
        FAIL=$((FAIL + 1))
    fi
done
echo ""

# -----------------------------------------------------------
# 6. Create multi-line reservation via ReservationController
# -----------------------------------------------------------
echo "[6] Creating multi-line reservation via /api/reservations..."

STAPLER_PID="${PRODUCT_IDS['STAPLER-001']}"
FOLDER_PID="${PRODUCT_IDS['FOLDER-001']}"
TAPE_PID="${PRODUCT_IDS['TAPE-DISP']}"

LINES='[
    {"productId":"'"$STAPLER_PID"'","quantity":2},
    {"productId":"'"$FOLDER_PID"'","quantity":5},
    {"productId":"'"$TAPE_PID"'","quantity":3}
]'

RESP=$(post "/api/reservations" "{\"orderRef\":\"ORD-1005\",\"lines\":$LINES}")
STATUS=$(extract "$RESP" "status")
if [ -n "$STATUS" ]; then
    echo "  Created ORD-1005 reservation (status=$STATUS)"
    PASS=$((PASS + 1))
else
    echo "  FAIL: Could not create multi-line reservation"
    FAIL=$((FAIL + 1))
fi
echo ""

# -----------------------------------------------------------
# 7. Query endpoints
# -----------------------------------------------------------
echo "[7] Querying endpoints..."

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products?status=ACTIVE")
check "List active products" "$HTTP_CODE" "200"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/stock")
check "List stock items" "$HTTP_CODE" "200"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/stock/low-stock")
check "List low-stock items" "$HTTP_CODE" "200"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/stock/summary")
check "Get stock summary" "$HTTP_CODE" "200"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/reservations")
check "List reservations" "$HTTP_CODE" "200"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/reservations/ORD-1005")
check "Get reservation by orderRef" "$HTTP_CODE" "200"
echo ""

# -----------------------------------------------------------
# 8. Deactivate a product (soft-delete)
# -----------------------------------------------------------
echo "[8] Deactivating a product..."
PID="${PRODUCT_IDS['CABLE-001']:-}"
if [ -n "$PID" ]; then
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/products/$PID/deactivate")
    check "Deactivate HDMI cable" "$HTTP_CODE" "200"
else
    echo "  SKIP: No productId for CABLE-001"
fi
echo ""

# -----------------------------------------------------------
# Summary
# -----------------------------------------------------------
echo "=== Seeding complete ==="
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then
    echo "SOME OPERATIONS FAILED"
    exit 1
else
    echo "ALL OPERATIONS SUCCEEDED"
    exit 0
fi
