#!/usr/bin/env bash
# CI artifact collection (spec §13.9): bundle enough state from a run
# directory to diagnose a failure without rerunning the game.
#
# Usage: scripts/collect-artifacts.sh <game-dir> [out-dir]
#
# Collects (when present): discovery snapshot, logs (truncated tail),
# crash reports, task/event/screenshot listings via the live API when the
# listener is up, and the mcapi file listing (never token contents).
set -euo pipefail

GAME_DIR="${1:-}"
OUT_DIR="${2:-build/artifacts}"

if [ -z "${GAME_DIR}" ]; then
  echo "usage: scripts/collect-artifacts.sh <game-dir> [out-dir]" >&2
  exit 2
fi
if [ ! -d "${GAME_DIR}" ]; then
  echo "collect: game dir not found: ${GAME_DIR}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
GAME_DIR="$(cd "${GAME_DIR}" && pwd)"

# Discovery (contains no secrets).
if [ -f "${GAME_DIR}/mcapi/discovery.json" ]; then
  cp "${GAME_DIR}/mcapi/discovery.json" "${OUT_DIR}/discovery.json"
  PORT="$(python3 -c "import json;print(json.load(open('${OUT_DIR}/discovery.json'))['api']['port'])")"
  PID="$(python3 -c "import json;print(json.load(open('${OUT_DIR}/discovery.json'))['pid'])")"
  PROCESS_SESSION="$(python3 -c "import json;print(json.load(open('${OUT_DIR}/discovery.json'))['processSessionId'])")"
else
  echo "collect: no discovery file in ${GAME_DIR}/mcapi (API disabled or never started)" >&2
  PORT=""
fi

# Live API snapshots (best-effort; the process may already be dead).
if [ -n "${PORT:-}" ] && [ -n "${MAPI_HTTP_TOKEN:-}" ]; then
  AUTH="Authorization: Bearer ${MAPI_HTTP_TOKEN}"
  curl -s --max-time 5 -H "${AUTH}" "http://127.0.0.1:${PORT}/api/v1/info" \
    >"${OUT_DIR}/info.json" 2>/dev/null || true
  curl -s --max-time 5 -H "${AUTH}" "http://127.0.0.1:${PORT}/api/v1/tasks" \
    >"${OUT_DIR}/tasks.json" 2>/dev/null || true
  curl -s --max-time 5 -H "${AUTH}" "http://127.0.0.1:${PORT}/api/v1/events?after=0&limit=200" \
    >"${OUT_DIR}/events.json" 2>/dev/null || true
  curl -s --max-time 5 -H "${AUTH}" "http://127.0.0.1:${PORT}/api/v1/leases" \
    >"${OUT_DIR}/leases.json" 2>/dev/null || true
  curl -s --max-time 5 -H "${AUTH}" "http://127.0.0.1:${PORT}/api/v1/capabilities" \
    >"${OUT_DIR}/capabilities.json" 2>/dev/null || true
fi

# Log tail (bounded; untrusted observed data).
if [ -f "${GAME_DIR}/logs/latest.log" ]; then
  tail -c 200000 "${GAME_DIR}/logs/latest.log" >"${OUT_DIR}/latest.log.tail" || true
fi

# Crash reports (bounded count).
if [ -d "${GAME_DIR}/crash-reports" ]; then
  find "${GAME_DIR}/crash-reports" -maxdepth 1 -type f | head -5 | while read -r f; do
    cp "${f}" "${OUT_DIR}/" || true
  done
fi

# Screenshot listing (metadata only; images stay in place).
if [ -d "${GAME_DIR}/mcapi/screenshots" ]; then
  ls -la "${GAME_DIR}/mcapi/screenshots" >"${OUT_DIR}/screenshots.listing" || true
fi

# mcapi directory listing (names only — never token contents).
if [ -d "${GAME_DIR}/mcapi" ]; then
  ls -la "${GAME_DIR}/mcapi" >"${OUT_DIR}/mcapi.listing" || true
fi

# Redaction sweep: the bundle must never contain token material.
if [ -n "${MAPI_HTTP_TOKEN:-}" ] && grep -rq -- "${MAPI_HTTP_TOKEN}" "${OUT_DIR}" 2>/dev/null; then
  echo "collect: TOKEN LEAK DETECTED in artifacts — scrubbing" >&2
  grep -rl -- "${MAPI_HTTP_TOKEN}" "${OUT_DIR}" 2>/dev/null | while read -r f; do
    python3 - "$f" "${MAPI_HTTP_TOKEN}" <<'PY'
import sys
path, token = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8", errors="replace") as fh:
    text = fh.read()
with open(path, "w", encoding="utf-8") as fh:
    fh.write(text.replace(token, "<redacted>"))
PY
  done
fi

echo "artifacts: collected into ${OUT_DIR} (processSessionId=${PROCESS_SESSION:-unknown}, pid=${PID:-unknown})"
