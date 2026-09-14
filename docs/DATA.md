# Data contracts

Serialization conventions for payloads (spec §7) as implemented in this
build.

## Typed NBT JSON (lossless)

`/api/v1/server/world/block-entity` serializes NBT with explicit type
markers so every tag survives a round trip:

| Tag type | JSON form |
| --- | --- |
| byte | `{"b": 1}` |
| short | `{"s": 1234}` |
| long | `{"l": "123456789012"}` (string — JS-safe) |
| float | `{"f": "1.5"}` (string of the value) |
| double, int, string, compound | JSON-native |
| byte/int/long array | `{"ba": […]}` / `{"ia": […]}` / `{"la": ["…"]}` |
| list | `{"list": […]}` |

Longs (including seeds, storage amounts, long arrays) are **strings** to
avoid precision loss in JavaScript consumers.

## One authoritative input format

Conflicting duplicate formats → **422**-style rejection. Implemented today
as: request bodies are JSON only (`INVALID_JSON` otherwise); unknown input
is never silently coerced (the strict bounded JSON parser rejects trailing
garbage, control characters, and over-deep nesting).

## Unknown ≠ empty

Absent or unknowable data is explicit, never an empty guess:
`serverTick: {"available": false, "reason": "SERVER_NOT_RUNNING"}`,
block entity `{"available": false, "reason": "NO_BLOCK_ENTITY"}`. Block
reads on unloaded chunks fail with **409** `CHUNK_UNLOADED` rather than
guessing.

## Resource IDs in query/body, never path segments

Registry/world **resource ids** (blocks, items, dimensions, players) travel
in query parameters (`?dimension=minecraft:overworld`, `?id=minecraft:stone`)
or bodies — contract-tested. Opaque instance handles (task/lease ids) are
the documented exception and may appear as path segments
(`/api/v1/tasks/{id}`).

## Context stamps

- `dataVersion` — world serialization stamp on block/block-entity/storage
  payloads (`-1` when unavailable).
- `processSessionId` / `worldSessionId` on identity payloads.
- `protocolVersion` (1) on every HTTP payload.

## Partial serialization

When a payload omits known fields, the omission is visible: empty slots are
dropped from storage snapshots, `worldSessionId` is absent (not null/empty)
between sessions, `isDown` is absent for `tap` actions.

## Error model

```json
{"error": {"code": "STALE_SESSION", "message": "…", "requestId": "…",
           "currentWorldSessionId": "…"}, "protocolVersion": 1}
```

Codes: `UNSUPPORTED`, `INVALID_JSON`, `INVALID_PAYLOAD`, `INVALID_QUERY`,
`INVALID_HEADER`, `UNAUTHORIZED`, `FORBIDDEN_HOST`, `FORBIDDEN_ORIGIN`,
`FORBIDDEN_SCOPE`, `NOT_FOUND`, `DIMENSION_NOT_FOUND`, `REGISTRY_TYPE_NOT_FOUND`,
`METHOD_NOT_ALLOWED`, `WRONG_STATE`, `CHUNK_UNLOADED`, `STALE_SESSION`,
`LEASE_HELD`, `DENIED_PATH`, `DISABLED`, `PAYLOAD_TOO_LARGE`,
`IDEMPOTENCY_MISMATCH`, `RATE_LIMITED`, `SERVER_BUSY`, `INTERNAL`.
Task-level codes: `INVALID_PAYLOAD`, `WRONG_STATE`, `DEADLINE_EXCEEDED`,
`CANCELLED`, `LIFECYCLE_CHANGED`, `INTERNAL`.

All list reads support `limit`/`offset` (or `cursor` for logs) and return
`truncated` plus `total` metadata. HTTP methods are audited: reads are
`GET`; everything else is `POST`/`DELETE`.
