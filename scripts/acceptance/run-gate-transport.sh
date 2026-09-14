#!/usr/bin/env bash
# Acceptance gate: API transport latency (spec §18: local metadata request
# p95 below 100 ms under the declared test load).
#
# Measures p50/p95/p99 of /api/v1/info against a running MAPI instance.
# NOTE: reference-hardware pinning is still pending (docs/acceptance-
# targets.md); numbers produced before pinning are indicative only.
#
# Usage: run-gate-transport.sh <base-url> <token-env-name> [requests]
set -euo pipefail

BASE="${1:?usage: run-gate-transport.sh <base-url> <token-env-name> [requests]}"
TOKEN_ENV="${2:?missing token env name}"
REQUESTS="${3:-200}"

if [ -z "${!TOKEN_ENV:-}" ]; then
  echo "error: \$${TOKEN_ENV} is not set" >&2
  exit 64
fi
AUTH="Authorization: Bearer ${!TOKEN_ENV}"

LATENCIES=()
for i in $(seq 1 "$REQUESTS"); do
  START=$(date +%s%N)
  CODE=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' -H "$AUTH" \
    "$BASE/api/v1/info")
  END=$(date +%s%N)
  if [ "$CODE" != "200" ]; then
    echo "error: request $i returned HTTP $CODE (expected 200)" >&2
    exit 1
  fi
  LATENCIES+=($(( (END - START) / 1000 )))  # microseconds
done

SORTED=($(printf '%s\n' "${LATENCIES[@]}" | sort -n))
P50=${SORTED[$(( ${#SORTED[@]} * 50 / 100 ))]}
P95=${SORTED[$(( ${#SORTED[@]} * 95 / 100 ))]}
P99=${SORTED[$(( ${#SORTED[@]} * 99 / 100 ))]}
MIN=${SORTED[0]}
MAX=${SORTED[$(( ${#SORTED[@]} - 1 ))]}

GATE_US=100000  # 100 ms in microseconds
PASS=false
if [ "$P95" -lt "$GATE_US" ]; then PASS=true; fi

OUT=$(printf '{"gate":"transport","requests":%d,"p50Us":%d,"p95Us":%d,"p99Us":%d,"minUs":%d,"maxUs":%d,"gateUs":%d,"pass":%s}' \
  "$REQUESTS" "$P50" "$P95" "$P99" "$MIN" "$MAX" "$GATE_US" "$PASS")
echo "$OUT"
mkdir -p build/acceptance
echo "$OUT" >> build/acceptance/transport.jsonl
[ "$PASS" = "1" ]
