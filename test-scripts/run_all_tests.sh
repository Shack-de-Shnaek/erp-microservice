#!/usr/bin/env bash
#
# run_all_tests.sh — orchestrates all smoke test scripts
#
# Expects the docker-compose stack to be running.
# Override ports with environment variables if needed.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=0
FAIL=0

run_test() {
    local script="$1"
    local label
    label=$(basename "$script" .sh | sed 's/^test_//' | tr '_' ' ')
    echo ""
    echo "============================================"
    echo " Running: $label"
    echo "============================================"
    if bash "$script"; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
    fi
}

echo "=== ERP Microservice — Full Smoke Test Suite ==="

# Inventory
run_test "$SCRIPT_DIR/test_inventory.sh"

# Add more test scripts here as they are created:
# run_test "$SCRIPT_DIR/test_orders.sh"

echo ""
echo "============================================"
echo " OVERALL RESULTS"
echo "============================================"
echo " Suites passed: $PASS"
echo " Suites failed: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then
    echo "SOME SUITES FAILED"
    exit 1
else
    echo "ALL SUITES PASSED"
    exit 0
fi
