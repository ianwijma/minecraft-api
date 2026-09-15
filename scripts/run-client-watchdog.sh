#!/usr/bin/env bash
# Launches the dev client with a watchdog: if the MAPI API listener has not
# come up within WINDOW seconds, the launch is killed (prevents the zombie
# hang we hit when GLFW fails and the error dialog waits forever).
#
# Usage: scripts/run-client-watchdog.sh [window-seconds]
set -euo pipefail
WINDOW="${1:-180}"

nohup ./gradlew :neoforge:runClient --console=plain > /tmp/opencode/runclient-watchdog.log 2>&1 &
LAUNCHER=$!
echo "launcher pid $LAUNCHER, window ${WINDOW}s"

for i in $(seq 1 "$((WINDOW / 5))"); do
  sleep 5
  ELAPSED=$((i * 5))
  if grep -q "MAPI HTTP API listening" neoforge/run/client/logs/latest.log 2>/dev/null; then
    echo "READY: API listening after ~${ELAPSED}s"
    exit 0
  fi
  if grep -qE "Failed to locate a primary monitor|ERROR DISPLAY" \
      neoforge/run/client/logs/latest.log /tmp/opencode/runclient-watchdog.log 2>/dev/null; then
    echo "FAILED: display error detected after ~${ELAPSED}s — killing launch" >&2
    pkill -9 -f "[D]evLaunch" 2>/dev/null || true
    exit 1
  fi
  if ! kill -0 "$LAUNCHER" 2>/dev/null; then
    echo "FAILED: gradle process exited after ~${ELAPSED}s" >&2
    tail -10 /tmp/opencode/runclient-watchdog.log >&2
    exit 1
  fi
done

echo "TIMEOUT: no API listener within ${WINDOW}s — killing launch" >&2
pkill -9 -f "[D]evLaunch" 2>/dev/null || true
kill "$LAUNCHER" 2>/dev/null || true
exit 1
