#!/usr/bin/env bash
# One disposable client run with MAPI loaded from the built release JAR.
# The E2E report records first-attempt failures and verifies JAR SHA-256
# provenance. This does not claim dedicated-server or integrated-server gates.
set -euo pipefail

LOADER="${1:-}"
if [[ "$LOADER" != fabric && "$LOADER" != neoforge ]]; then
  echo "usage: scripts/acceptance/release-client-smoke.sh <fabric|neoforge>" >&2
  exit 2
fi

if [[ -z "${MAPI_HTTP_TOKEN:-}" ]]; then
  MAPI_HTTP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  export MAPI_HTTP_TOKEN
fi
export MAPI_HTTP_ENABLED=true
export MAPI_HTTP_RATE_LIMIT_PER_MINUTE=3600
if [[ -n "${DISPLAY:-}" || "${MAPI_USE_XVFB:-}" == true ]]; then
  :
elif command -v xvfb-run >/dev/null 2>&1; then
  export MAPI_USE_XVFB=true
else
  echo "no DISPLAY or xvfb-run available for the client" >&2
  exit 3
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
OUT_DIR="$(pwd)/build/acceptance/release-client/${LOADER}/${RUN_ID}"
mkdir -p "$OUT_DIR"
echo "release client smoke: $LOADER; launch runtime is loader-specific; report: $OUT_DIR/report.json"
node --experimental-strip-types --no-warnings e2e/run.ts \
  --loader "$LOADER" --scenario house --release-jar --out-dir "$OUT_DIR"
