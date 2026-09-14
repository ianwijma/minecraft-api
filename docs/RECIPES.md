# Recipes

Task-oriented recipes for the implemented surface. Schemas: `docs/http-api.md`
and `docs/openapi.yaml`; semantics: `docs/SEMANTICS.md`.

## Find a running instance and verify it

```bash
# Read discovery (no secrets) and wait for a world session:
GAME_DIR=run/server
cat "$GAME_DIR/mcapi/discovery.json"
TOKEN=$(cat "$GAME_DIR/mcapi/token")
BASE=http://127.0.0.1:$(python3 -c "import json;print(json.load(open('$GAME_DIR/mcapi/discovery.json'))['api']['port'])")
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/ready"
```

## Wait for the server to reach a tick, safely

```bash
# Idempotent task creation: safe to retry after a lost response.
TASK=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: wait-1000" \
  -H "Content-Type: application/json" \
  -d '{"kind":"wait-for-tick","payload":{"targetTick":1000},"deadlineMs":60000}' \
  "$BASE/api/v1/tasks")
TASK_ID=$(echo "$TASK_ID" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
# poll GET /api/v1/tasks/$TASK_ID until state is terminal
```

Or with the Python SDK: `client.wait_task(task["id"], timeout_s=60)`.

## Coordinate a mutation with the world you saw (stale-session guard)

```bash
WORLD=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/info" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['worldSessionId'])")
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"command\":\"say hello\",\"expectedWorldSessionId\":\"$WORLD\"}" \
  "$BASE/api/v1/server/commands/execute"
# 409 STALE_SESSION → the world changed under you; re-read /info and retry.
```

## Exclusive client control between two tools

```bash
# Tool A holds the input lease; Tool B queues behind it.
curl -s -X POST -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
  -d '{"lease":"client.input","ttlMs":60000,"conflict":"reject"}' "$BASE/api/v1/leases"
curl -s -X POST -H "Authorization: Bearer $TOKEN_B" -H "Content-Type: application/json" \
  -d '{"lease":"client.input","conflict":"queue"}' "$BASE/api/v1/leases"
# On A's release/expiry, B activates and A's keys are released automatically.
```

## Inspect the world

```bash
curl -s -H "$AUTH" "$BASE/api/v1/server/world/block?dimension=minecraft:overworld&x=0&y=-60&z=0"
curl -s -H "$AUTH" "$BASE/api/v1/server/world/block-entity?dimension=minecraft:overworld&x=1&y=-60&z=1"
curl -s -H "$AUTH" "$BASE/api/v1/server/world/storage?dimension=minecraft:overworld&x=1&y=-60&z=1"
curl -s -H "$AUTH" "$BASE/api/v1/registry/item?limit=5"
curl -s -H "$AUTH" "$BASE/api/v1/tags/block?tag=minecraft:planks"
```

## Follow the event stream with resume

Python: `client.follow_events(after=0)` yields events and gap markers.
TypeScript: `client.followEvents(0)` (async generator). Resume cursors are
per-process-session; compare `processSessionId` across restarts.

## Diagnose a broken instance post-mortem

```bash
cat "$GAME_DIR/mcapi/discovery.json"          # identity + endpoints
tail -50 "$GAME_DIR/logs/latest.log"          # untrusted observed data
ls "$GAME_DIR/crash-reports/"                 # or GET /api/v1/crash-reports
```

## Give a mod its own HTTP surface

Implement `MapiHttpExtension` (see `docs/api.md`), register it with
`mapi.services().register("your-ext", ext)` — operations appear under
`/api/v1/ext/your-ext/…` with a `$schema` endpoint and per-extension scope
enforcement.
