#!/usr/bin/env bash
# Stable entrypoint for acceptance-gate harnesses (docs/acceptance-targets.md).
# Gates marked NOT IMPLEMENTED exit 2 and never report success.
set -euo pipefail

GATES=(
  reliability
  leaks
  parallel
  smoke
  transport
  boundaries
  overhead
  security
  release-jars
  sdk-interop
)

usage() {
  echo "usage: run-gate.sh <gate>"
  echo "gates: ${GATES[*]}"
}

GATE="${1:-}"
if [ -z "$GATE" ]; then
  usage
  exit 64
fi

case "$GATE" in
  reliability|leaks|parallel|smoke|transport|boundaries|overhead|security|release-jars|sdk-interop)
    echo "NOT IMPLEMENTED: gate '$GATE' harness lands in a later execution-plan chunk" >&2
    echo "(see scripts/acceptance/README.md for the drafted procedure)" >&2
    exit 2
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 64
    ;;
esac
