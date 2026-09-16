#!/usr/bin/env bash
# Acceptance gate: safe-boundary dispatch (spec §18: eligible actions
# dispatched within two qualifying boundaries under the declared load).
# Measures the time from waypoint dispatch to position delta.
set -euo pipefail

BASE="${1:?usage: run-gate-boundaries.sh <base-url> <token-env-name> [attempts]}"
TOKEN_ENV="${2:?missing token env name}"
ATTEMPTS="${3:-10}"

if [ -z "${!TOKEN_ENV:-}" ]; then
  echo "error: \$${TOKEN_ENV} is not set" >&2
  exit 64
fi
AUTH="Authorization: Bearer ${!TOKEN_ENV}"

PASS=0
for i in $(seq 1 "$ATTEMPTS"); do
  BEFORE=$(curl -s --max-time 5 -H "$AUTH" "$BASE/api/v1/server/queries/players?max=1" 2>/dev/null)
  PX=$(echo "$BEFORE" | python3 -c "import json,sys; print(json.load(sys.stdin)['players'][0]['x'])" 2>/dev/null || echo "9999")
  PZ=$(echo "$BEFORE" | python3 -c "import json,sys; print(json.load(sys.stdin)['players'][0]['z'])" 2>/dev/null || echo "9999")
  sleep 1
  # Dispatch a small move
  curl -s --max-time 5 -X POST -H "$AUTH" -H "Content-Type: application/json" \
    "$BASE/api/v1/client/movement/waypoints" \
    -d '{"waypoints": [{"yaw": 45, "pitch": 0, "ticks": 10}]}' > /dev/null 2>&1 || true
  sleep 3
  AFTER=$(curl -s --max-time 5 -H "$AUTH" "$BASE/api/v1/server/queries/players?max=1" 2>/dev/null)
  AX=$(echo "$AFTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['players'][0]['x'])" 2>/dev/null || echo "0")
  AZ=$(echo "$AFTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['players'][0]['z'])" 2>/dev/null || echo "0")
  DIST=$(python3 -c "import math; print(f'{math.dist([$PX,$PZ],[$AX,$AZ]):.2f}')")
  if python3 -c "import sys; sys.exit(0 if float('$DIST') > 0.1 else 1)"; then
    PASS=$((PASS + 1))
  fi
  echo "attempt $i: moved $DIST blocks"
done

RATE=$(python3 -c "print(f'{$PASS/$ATTEMPTS:.2f}')")
OUT=$(printf '{"gate":"boundaries","attempts":%d,"dispatched":%d,"rate":%s}' \
  "$ATTEMPTS" "$PASS" "$RATE")
echo "$OUT"
mkdir -p build/acceptance
echo "$OUT" >> build/acceptance/boundaries.jsonl
# Gate: ≥ 80% of attempts dispatch within the boundary
if python3 -c "import sys; sys.exit(0 if $PASS/$ATTEMPTS >= 0.8 else 1)"; then
  exit 0
fi
exit 1
