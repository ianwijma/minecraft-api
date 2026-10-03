#!/usr/bin/env bash
# Live resolver/destination policy proof using one disposable release client
# per case. Each E2E run owns its process group and report directory.
set -euo pipefail

LOADER="${1:-}"
if [[ "$LOADER" != fabric && "$LOADER" != neoforge ]]; then
  echo "usage: scripts/acceptance/connection-policy-smoke.sh <fabric|neoforge>" >&2
  exit 2
fi
if [[ -z "${DISPLAY:-}" && "${MAPI_USE_XVFB:-}" != true ]] && ! command -v xvfb-run >/dev/null 2>&1; then
  echo "no DISPLAY or xvfb-run available for the client" >&2
  exit 3
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
BASE_OUT="$(pwd)/build/acceptance/connection-policy/${LOADER}/${RUN_ID}"
mkdir -p "$BASE_OUT"
status=0
first_failure=""
for mode in deny allow; do
  out="$BASE_OUT/$mode"
  mkdir -p "$out"
  echo "connection policy $LOADER/$mode; report: $out/report.json"
  if MAPI_HTTP_ENABLED=true MAPI_USE_XVFB="${MAPI_USE_XVFB:-true}" \
    node --experimental-strip-types --no-warnings e2e/run.ts \
      --loader "$LOADER" --scenario house --release-jar \
      --connection-probe "$mode" --out-dir "$out"; then
    :
  else
    status=1
    if [[ -z "$first_failure" ]]; then first_failure="$mode"; fi
  fi
done
python3 - "$BASE_OUT/summary.json" "$LOADER" "$status" "$first_failure" <<'PY'
import json,sys,datetime,os
path,loader,status,first_failure=sys.argv[1:]
data={"gate":"connection-policy-live-smoke","loader":loader,
      "timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),
      "pass":status=="0","firstFailure":first_failure or None,
      "cases":["deny-hostname-only-final-ip","allow-explicit-ip-port"],
      "caseReports":[os.path.join(os.path.dirname(path),"deny","report.json"),
                     os.path.join(os.path.dirname(path),"allow","report.json")]}
with open(path,"w",encoding="utf-8") as f: json.dump(data,f,indent=2); f.write("\n")
PY
exit "$status"
