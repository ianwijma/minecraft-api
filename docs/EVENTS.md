# Events

The event stream (spec §3.3): per-process-session monotonic `seq`, ring
buffer (1024) with explicit GAP semantics, polling via
`GET /api/v1/events?after=` and live delivery via the WebSocket port
(see `docs/http-api.md` for the wire formats). Every event carries
`source`, `wallClock`, `monotonicNanos`, session ids, and `data`.

## Sources

| Source | Meaning |
| --- | --- |
| `server-authoritative` | produced by the owning game thread (reserved for future server-tick events) |
| `client-observed` | local client observation (reserved for client lifecycle) |
| `instrumented` | MAPI's own hooks (readiness, server lifecycle) |
| `mod-provided` | registered extensions (reserved) |
| `api-originated` | the API itself (tasks, leases, files, unsafe, ext invocations) |

## Event catalog (implemented)

| eventType | Source | Data |
| --- | --- | --- |
| `api.started` | api-originated | `port`, `eventsPort` |
| `api.stopped` | api-originated | — |
| `server.starting` | instrumented | `readiness`, `previousReadiness` |
| `server.stopped` | instrumented | `readiness`, `previousReadiness` |
| `readiness.changed` | instrumented | `readiness`, `previousReadiness` |
| `task.state_changed` | api-originated | `taskId`, `kind`, `state`, `errorCode?` |
| `lease.changed` | api-originated | `leaseId`, `lease`, `state` |
| `server.command` | api-originated | `command`, `permissionLevel`, `success` |
| `client.input.key` | api-originated | `mapping`, `action` |
| `client.screenshot` | api-originated | `frameId`, `path` |
| `files.written` | api-originated | `path`, `bytes` |
| `ext.invoked` | api-originated | `extension`, `path`, `method` |
| `unsafe.reflect` / `unsafe.invoke` | api-originated | target of the call |

## Semantics

- Sequence numbers are monotonic per **process session**; a restart resets
  them (consumers detect restarts via `processSessionId`).
- No global cross-instance ordering is promised; cross-instance
  coordination uses observed conditions and barriers.
- History is a bounded ring (1024). Consumers resuming from an evicted
  cursor receive an explicit `gap {from, to}` (polling) or `{"type":"gap"}`
  (WS) — never silent loss.
- Slow WS consumers choose `drop-oldest` (GAP marker emitted) or
  `disconnect` at subscribe time.
- Per-event authorization: the WS connection requires the token's `observe`
  scope; there is no per-event-type scope split yet.
- Coverage per event type is the table above; anything not listed is not
  published by this build.
