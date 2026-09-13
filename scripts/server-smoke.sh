#!/usr/bin/env bash
# Dedicated-server startup smoke test for minecraft-api.
#
# Usage: MAPI_ACCEPT_EULA=true scripts/server-smoke.sh <fabric|neoforge>
#
# IMPORTANT: accepting the Minecraft EULA is an explicit operator decision.
# This script refuses to run unless you set MAPI_ACCEPT_EULA=true. It never
# commits eula.txt (the run dir is inside build/, which is gitignored).
#
# Optional live HTTP probe: if MAPI_HTTP_TOKEN is exported together with
# MAPI_HTTP_ENABLED=true, the script waits for the listener, then verifies
# that /api/v1/{health,info,server/status} answer inside the running game
# (401 without token, 200 with token) before shutting the server down.
set -euo pipefail

LOADER="${1:-}"
RUNROOT="build/smoke"
TIMEOUT_SECONDS="${MAPI_SMOKE_TIMEOUT:-600}"

if [ "${LOADER}" != "fabric" ] && [ "${LOADER}" != "neoforge" ]; then
  echo "usage: MAPI_ACCEPT_EULA=true scripts/server-smoke.sh <fabric|neoforge>" >&2
  exit 2
fi
if [ "${MAPI_ACCEPT_EULA:-}" != "true" ]; then
  echo "refusing to run: Minecraft EULA acceptance is an explicit operator step." >&2
  echo "If you accept Mojang's EULA for this machine, re-run with MAPI_ACCEPT_EULA=true" >&2
  exit 3
fi

RUN_DIR="$(pwd)/${RUNROOT}/${LOADER}/run"
rm -rf "${RUNROOT}"
mkdir -p "${RUN_DIR}"

# Absolute run dir for this loader: both Loom and ModDevGradle resolve
# relative run-dir values against their own MODULE directory, so an
# absolute path is required to stay under build/ regardless of module.
GRADLE_PROPS="-PmapiServerRunDir=${RUN_DIR}"

printf 'eula=true\n' > "${RUN_DIR}/eula.txt"
echo "smoke: eula.txt written to ${RUN_DIR} (operator accepted via MAPI_ACCEPT_EULA=true)"

LOG_FILE="${RUN_DIR}/logs/latest.log"
HTTP_PROBE=0
if [ -n "${MAPI_HTTP_TOKEN:-}" ]; then
  HTTP_PROBE=1
fi

echo "smoke: starting :${LOADER}:runServer (timeout ${TIMEOUT_SECONDS}s)"
STARTED_AT=$(date +%s)
set +e
MAPI_HTTP_ENABLED="${MAPI_HTTP_ENABLED:-false}" \
MAPI_HTTP_TOKEN="${MAPI_HTTP_TOKEN:-}" \
timeout --signal=TERM --kill-after=30 "${TIMEOUT_SECONDS}" \
  ./gradlew ":${LOADER}:runServer" ${GRADLE_PROPS} ${GRADLE_ARGS:-} \
  --console=plain --no-daemon >"${RUNROOT}/gradle-console.log" 2>&1 &
GRADLE_PID=$!
set -e

WAIT_FOR_MARKER() {
  local pattern="$1" budget="$2" waited=0
  while [ "${waited}" -lt "${budget}" ]; do
    if grep -q "${pattern}" "${LOG_FILE}" 2>/dev/null; then return 0; fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

FOUND_DONE=0
FOUND_MAPI=0
while kill -0 "${GRADLE_PID}" 2>/dev/null; do
  sleep 5
  if grep -q "Done (" "${LOG_FILE}" 2>/dev/null; then FOUND_DONE=1; fi
  if grep -qE "MAPI .* initialized \(platform=" "${LOG_FILE}" 2>/dev/null; then FOUND_MAPI=1; fi
  if [ "${FOUND_DONE}" = "1" ] && [ "${FOUND_MAPI}" = "1" ]; then
    echo "smoke: server reached 'Done' with MAPI initialized — startup PASS"
    break
  fi
  ELAPSED=$(( $(date +%s) - STARTED_AT ))
  if [ "${ELAPSED}" -ge "${TIMEOUT_SECONDS}" ]; then
    echo "smoke: timeout reached" >&2
    break
  fi
done

HTTP_PROBE_RESULT=skipped
if [ "${FOUND_DONE}" = "1" ] && [ "${FOUND_MAPI}" = "1" ] && [ "${HTTP_PROBE}" = "1" ]; then
  echo "smoke: waiting for the local HTTP API listener"
  if WAIT_FOR_MARKER "MAPI HTTP API listening on http://127.0.0.1:" 90; then
    HTTP_PORT="$(grep -oE 'MAPI HTTP API listening on http://127.0.0.1:[0-9]+' "${LOG_FILE}" | head -1 | grep -oE '[0-9]+$')"
    AUTH="Authorization: Bearer ${MAPI_HTTP_TOKEN}"
    BASE="http://127.0.0.1:${HTTP_PORT}"
    HTTP_PROBE_RESULT=pass
    for endpoint in health info "server/status"; do
      code_unauth="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' "${BASE}/api/v1/${endpoint}" 2>/dev/null || echo conn-fail)"
      body="$(curl -s --max-time 5 -H "${AUTH}" "${BASE}/api/v1/${endpoint}" 2>/dev/null || echo curl-failed)"
      code_auth="$(printf '%s' "${body}" | python3 -c 'import json,sys; json.load(sys.stdin); print(200)' 2>/dev/null || echo bad-json)"
      echo "smoke: http /api/v1/${endpoint} -> unauth=${code_unauth} auth=${code_auth} body=$(printf '%s' "${body}" | head -c 120)"
      if [ "${code_unauth}" != "401" ] || [ "${code_auth}" != "200" ]; then HTTP_PROBE_RESULT=fail; fi
    done
    if [ "${HTTP_PROBE_RESULT}" = "pass" ]; then
      echo "smoke: live HTTP API probe PASS (401 unauthenticated, 200 + valid JSON authenticated)"
    else
      echo "smoke: live HTTP probe FAILED" >&2
    fi
  else
    HTTP_PROBE_RESULT=fail
    echo "smoke: HTTP listener did not appear while enabled" >&2
  fi
fi

echo "smoke: stopping server"
kill -INT "${GRADLE_PID}" 2>/dev/null || true
sleep 20
kill -TERM "${GRADLE_PID}" 2>/dev/null || true
kill -9 "${GRADLE_PID}" 2>/dev/null || true
wait "${GRADLE_PID}" 2>/dev/null || true

if [ "${FOUND_DONE}" = "1" ] && [ "${FOUND_MAPI}" = "1" ]; then
  if [ "${HTTP_PROBE_RESULT:-skipped}" != "fail" ]; then
    echo "smoke: PASS (${LOADER} dedicated server started, MAPI initialized${HTTP_PROBE:+, live HTTP verified})"
    exit 0
  fi
fi
echo "smoke: FAIL — log excerpt:" >&2
tail -40 "${LOG_FILE}" 2>/dev/null || tail -60 "${RUNROOT}/gradle-console.log" 2>/dev/null || true
exit 1