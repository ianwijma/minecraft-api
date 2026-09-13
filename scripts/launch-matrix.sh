#!/usr/bin/env bash
# Launch matrix (slice 0.7): one API-enabled dev server per loader, verified
# through discovery + the HTTP API, then shut down.
#
# Usage: MAPI_ACCEPT_EULA=true scripts/launch-matrix.sh
#
# IMPORTANT: like server-smoke.sh, this script refuses to run unless the
# operator sets MAPI_ACCEPT_EULA=true (dedicated servers need eula.txt).
# Run dirs live under build/matrix/ (gitignored).
set -euo pipefail

FABRIC_PORT="${MAPI_MATRIX_FABRIC_PORT:-26001}"
NEOFORGE_PORT="${MAPI_MATRIX_NEOFORGE_PORT:-26002}"
TIMEOUT_SECONDS="${MAPI_MATRIX_TIMEOUT:-600}"
RUNROOT="build/matrix"

if [ "${MAPI_ACCEPT_EULA:-}" != "true" ]; then
  echo "refusing to run: Minecraft EULA acceptance is an explicit operator step." >&2
  echo "If you accept Mojang's EULA for this machine, re-run with MAPI_ACCEPT_EULA=true" >&2
  exit 3
fi

rm -rf "${RUNROOT}"
mkdir -p "${RUNROOT}"

start_instance() {
  local loader="$1" port="$2" instance="$3"
  local run_dir="$(pwd)/${RUNROOT}/${loader}/run"
  mkdir -p "${run_dir}"
  printf 'eula=true\n' > "${run_dir}/eula.txt"
  echo "matrix: starting :${loader}:runServerApi (port ${port}, instance ${instance})"
  ./gradlew ":${loader}:runServerApi" -PmapiApi -PmapiApiPort="${port}" \
    -PmapiInstanceId="${instance}" -PmapiServerRunDir="${run_dir}" \
    --console=plain --no-daemon >"${RUNROOT}/${loader}-console.log" 2>&1 &
  echo "$!"
}

wait_ready() {
  local run_dir="$1" label="$2" waited=0
  while [ "${waited}" -lt "${TIMEOUT_SECONDS}" ]; do
    if python3 - "$run_dir/mcapi/discovery.json" <<'PY' 2>/dev/null; then
import json, sys
try:
    with open(sys.argv[1]) as f:
        d = json.load(f)
    print(d.get("readiness", ""))
except Exception:
    print("")
PY
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

assert_http() {
  local base="$1" token="$2"
  local info events task
  info="$(curl -s --max-time 5 -H "Authorization: Bearer ${token}" "${base}/api/v1/info")"
  echo "${info}" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["protocolVersion"]==1; assert "processSessionId" in d; assert "support" in d'
  events="$(curl -s --max-time 5 -H "Authorization: Bearer ${token}" "${base}/api/v1/events?after=0")"
  echo "${events}" | python3 -c 'import json,sys; d=json.load(sys.stdin); types=[e["eventType"] for e in d["events"]]; assert "api.started" in types, types'
  task="$(curl -s --max-time 5 -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
    -d '{"kind":"wait-for-tick","payload":{"targetTick":1000000000},"deadlineMs":5000}' "${base}/api/v1/tasks")"
  echo "${task}" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["state"] in ("queued","running"), d'
}

declare -a PIDS=()
RESULT=fail
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    kill -TERM "${pid}" 2>/dev/null || true
  done
  sleep 5
  for pid in "${PIDS[@]:-}"; do
    kill -9 "${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT

PIDS+=("$(start_instance fabric "${FABRIC_PORT}" "mx-fabric-1")")
PIDS+=("$(start_instance neoforge "${NEOFORGE_PORT}" "mx-neoforge-1")")

FABRIC_READY=$(wait_ready "${RUNROOT}/fabric/run" fabric || true)
NEOFORGE_READY=$(wait_ready "${RUNROOT}/neoforge/run" neoforge || true)

if [ "${FABRIC_READY}" = "worldReady" ] && [ "${NEOFORGE_READY}" = "worldReady" ]; then
  echo "matrix: both instances report worldReady via discovery"
  TOKEN_FABRIC="$(cat "${RUNROOT}/fabric/run/mcapi/token")"
  TOKEN_NEO="$(cat "${RUNROOT}/neoforge/run/mcapi/token")"
  assert_http "http://127.0.0.1:${FABRIC_PORT}" "${TOKEN_FABRIC}"
  echo "matrix: fabric HTTP contract PASS"
  assert_http "http://127.0.0.1:${NEOFORGE_PORT}" "${TOKEN_NEO}"
  echo "matrix: neoforge HTTP contract PASS"
  RESULT=pass
else
  echo "matrix: discovery did not reach worldReady (fabric=${FABRIC_READY:-none} neoforge=${NEOFORGE_READY:-none})" >&2
fi

cleanup
trap - EXIT

if [ "${RESULT}" = "pass" ]; then
  echo "matrix: PASS (both loaders: discovery, readiness, HTTP contract, task protocol)"
  exit 0
fi
echo "matrix: FAIL — console excerpts:" >&2
tail -30 "${RUNROOT}/fabric-console.log" 2>/dev/null || true
tail -30 "${RUNROOT}/neoforge-console.log" 2>/dev/null || true
exit 1
