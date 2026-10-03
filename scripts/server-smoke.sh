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
RUNROOT="build/smoke/${1:-invalid}/run-$(date +%Y%m%dT%H%M%S)-$$"
TIMEOUT_SECONDS="${MAPI_SMOKE_TIMEOUT:-600}"
LAUNCH_MODE="${MAPI_SMOKE_LAUNCH_MODE:-development}"

if [ "${LOADER}" != "fabric" ] && [ "${LOADER}" != "neoforge" ]; then
  echo "usage: MAPI_ACCEPT_EULA=true scripts/server-smoke.sh <fabric|neoforge>" >&2
  exit 2
fi
if [ "${MAPI_ACCEPT_EULA:-}" != "true" ]; then
  echo "refusing to run: Minecraft EULA acceptance is an explicit operator step." >&2
  echo "If you accept Mojang's EULA for this machine, re-run with MAPI_ACCEPT_EULA=true" >&2
  exit 3
fi
if [ "${LAUNCH_MODE}" != development ] && [ "${LAUNCH_MODE}" != release ]; then
  echo "MAPI_SMOKE_LAUNCH_MODE must be development or release" >&2
  exit 64
fi
if [ "${LAUNCH_MODE}" = release ] && [ -z "${MAPI_HTTP_TOKEN:-}" ]; then
  echo "release smoke requires MAPI_HTTP_TOKEN for packaged-JAR provenance" >&2
  exit 64
fi
if [ "${LAUNCH_MODE}" = release ] && [ "${MAPI_HTTP_ENABLED:-false}" != true ]; then
  echo "release smoke requires MAPI_HTTP_ENABLED=true for packaged-JAR provenance" >&2
  exit 64
fi

RUN_DIR="$(pwd)/${RUNROOT}/run"
mkdir -p "${RUN_DIR}"
rm -f "${RUN_DIR}/eula.txt" "${RUNROOT}/gradle-console.log"
SERVER_PORT="${MAPI_SMOKE_GAME_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')}"
if ! [[ "${SERVER_PORT}" =~ ^[0-9]+$ ]] || [ "${SERVER_PORT}" -lt 1 ] || [ "${SERVER_PORT}" -gt 65535 ]; then
  echo "invalid MAPI_SMOKE_GAME_PORT" >&2
  exit 64
fi
printf 'server-ip=127.0.0.1\nserver-port=%s\n' "${SERVER_PORT}" > "${RUN_DIR}/server.properties"

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
if [ "${LAUNCH_MODE}" = release ]; then HTTP_PROBE=1; fi
ARTIFACT_HASH=""
ARTIFACT_FILE=""
RUNTIME_HASH=""
PROVENANCE_RESULT=skipped
if [ "${LAUNCH_MODE}" = release ]; then
  MAPI_VERSION="$(sed -n 's/^mapiVersion=//p' gradle.properties | head -1)"
  if [ -z "${MAPI_VERSION}" ]; then
    echo "mapiVersion missing from gradle.properties" >&2
    exit 1
  fi
  ARTIFACT_FILE="${LOADER}/build/libs/minecraft-api-${LOADER}-${MAPI_VERSION}.jar"
fi

RESULT=fail
FIRST_FAILURE=startup
HTTP_PROBE_RESULT=skipped
GRADLE_PID=""
stop_server() {
  [ -n "${GRADLE_PID}" ] || return 0
  kill -INT -- "-${GRADLE_PID}" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 -- "-${GRADLE_PID}" 2>/dev/null || break
    sleep 1
  done
  kill -TERM -- "-${GRADLE_PID}" 2>/dev/null || true
  sleep 2
  kill -KILL -- "-${GRADLE_PID}" 2>/dev/null || true
  wait "${GRADLE_PID}" 2>/dev/null || true
  GRADLE_PID=""
}
write_report() {
  local code="$1"
  local acceptance_report="$(pwd)/build/acceptance/server-smoke/${LOADER}/$(basename "${RUNROOT}").json"
  mkdir -p "$(dirname "${acceptance_report}")"
  python3 - "${RUN_DIR}/acceptance-report.json" "${acceptance_report}" "${LOADER}" "${RUN_DIR}" "${SERVER_PORT}" "${MAPI_HTTP_PORT:-25586}" "${LAUNCH_MODE}" "${ARTIFACT_HASH}" "${RUNTIME_HASH}" "${PROVENANCE_RESULT}" "${RESULT}" "${FIRST_FAILURE}" "${FOUND_DONE:-0}" "${FOUND_MAPI:-0}" "${HTTP_PROBE_RESULT}" "${code}" <<'PY'
import json,sys,datetime
path,acceptance_path,loader,run_dir,game_port,http_port,mode,artifact_hash,runtime_hash,provenance,result,first_failure,done,mapi,http,code=sys.argv[1:]
data={"gate":"dedicated-server-smoke","loader":loader,"runDir":run_dir,
      "gamePort":int(game_port),"httpPort":int(http_port),
      "launchMode":mode,"artifactSha256":artifact_hash or None,
      "runtimeArtifactSha256":runtime_hash or None,"provenance":provenance,
      "timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),
      "pass":result=="pass","firstFailure":None if result=="pass" else first_failure,
      "serverDone":done=="1","mapiInitialized":mapi=="1",
      "httpProbe":http,"exitCode":int(code)}
for output in (path,acceptance_path):
  with open(output,"w",encoding="utf-8") as f: json.dump(data,f,indent=2); f.write("\n")
PY
}
on_exit() {
  local code=$?
  trap - EXIT
  stop_server
  write_report "${code}"
  exit "${code}"
}
trap on_exit EXIT

TASK="runServer"
if [ "${LAUNCH_MODE}" = release ]; then TASK="runReleaseServer"; fi
echo "smoke: starting :${LOADER}:${TASK} (timeout ${TIMEOUT_SECONDS}s)"
STARTED_AT=$(date +%s)
set +e
MAPI_HTTP_ENABLED="${MAPI_HTTP_ENABLED:-false}" \
MAPI_HTTP_TOKEN="${MAPI_HTTP_TOKEN:-}" \
setsid timeout --signal=TERM --kill-after=30 "${TIMEOUT_SECONDS}" \
  ./gradlew ":${LOADER}:${TASK}" ${GRADLE_PROPS} ${GRADLE_ARGS:-} \
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
    FIRST_FAILURE="http_probe"
    break
  fi
  ELAPSED=$(( $(date +%s) - STARTED_AT ))
  if [ "${ELAPSED}" -ge "${TIMEOUT_SECONDS}" ]; then
    echo "smoke: timeout reached" >&2
    break
  fi
done

if [ "${FOUND_DONE}" = "1" ] && [ "${FOUND_MAPI}" = "1" ] && [ "${HTTP_PROBE}" = "1" ]; then
  echo "smoke: waiting for the local HTTP API listener"
  if WAIT_FOR_MARKER "MAPI HTTP API listening on http://127.0.0.1:" 90; then
    HTTP_PORT="$(grep -oE 'MAPI HTTP API listening on http://127.0.0.1:[0-9]+' "${LOG_FILE}" | head -1 | grep -oE '[0-9]+$')"
    AUTH="Authorization: Bearer ${MAPI_HTTP_TOKEN}"
    BASE="http://127.0.0.1:${HTTP_PORT}"
    HTTP_PROBE_RESULT=pass
    for endpoint in health info "server/status"; do
      code_unauth="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' "${BASE}/api/v1/${endpoint}" 2>/dev/null || echo conn-fail)"
      response_file="${RUN_DIR}/http-response.json"
      code_auth="$(curl -s --max-time 5 -o "${response_file}" -w '%{http_code}' -H "${AUTH}" "${BASE}/api/v1/${endpoint}" 2>/dev/null || echo conn-fail)"
      valid_json=0
      python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "${response_file}" 2>/dev/null && valid_json=1 || true
      if [ "${endpoint}" = "info" ] && [ "${LAUNCH_MODE}" = release ] && [ "${valid_json}" = "1" ]; then
        if [ ! -f "${ARTIFACT_FILE}" ]; then
          PROVENANCE_RESULT=fail
          HTTP_PROBE_RESULT=fail
          continue
        fi
        ARTIFACT_HASH="$(python3 - "${ARTIFACT_FILE}" <<'PY'
import hashlib,sys
h=hashlib.sha256()
with open(sys.argv[1],'rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
print(h.hexdigest())
PY
)"
        RUNTIME_HASH="$(python3 - "${response_file}" <<'PY'
import json,sys
info=json.load(open(sys.argv[1],encoding='utf-8'))
artifact=info.get('runtimeArtifact') or {}
print(artifact.get('sha256',''))
PY
)"
        if [ "${RUNTIME_HASH}" = "${ARTIFACT_HASH}" ] && [ -n "${ARTIFACT_HASH}" ]; then
          PROVENANCE_RESULT=pass
        else
          PROVENANCE_RESULT=fail
        fi
      fi
      echo "smoke: http /api/v1/${endpoint} -> unauth=${code_unauth} auth=${code_auth} json=${valid_json}"
      if [ "${code_unauth}" != "401" ] || [ "${code_auth}" != "200" ] || [ "${valid_json}" != "1" ]; then HTTP_PROBE_RESULT=fail; fi
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
stop_server

if [ "${FOUND_DONE}" = "1" ] && [ "${FOUND_MAPI}" = "1" ]; then
  if [ "${HTTP_PROBE_RESULT:-skipped}" != "fail" ] && [ "${PROVENANCE_RESULT}" != "fail" ] \
    && { [ "${LAUNCH_MODE}" != release ] || [ "${PROVENANCE_RESULT}" = "pass" ]; }; then
    RESULT=pass
    FIRST_FAILURE=""
    if [ "${HTTP_PROBE_RESULT}" = "pass" ]; then
      echo "smoke: PASS (${LOADER} dedicated server started, MAPI initialized, live HTTP verified)"
    else
      echo "smoke: PASS (${LOADER} dedicated server started, MAPI initialized)"
    fi
    exit 0
  fi
fi
echo "smoke: FAIL — log excerpt:" >&2
if [ -f "${LOG_FILE}" ]; then
  tail -40 "${LOG_FILE}" 2>/dev/null
else
  tail -60 "${RUNROOT}/gradle-console.log" 2>/dev/null || true
fi | MAPI_REDACT_TOKEN="${MAPI_HTTP_TOKEN:-}" python3 -c 'import os,sys; token=os.environ.get("MAPI_REDACT_TOKEN", ""); text=sys.stdin.read(); print(text.replace(token, "[REDACTED]") if token else text, end="")'
exit 1
