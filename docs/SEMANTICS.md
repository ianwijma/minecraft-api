# Semantics

Execution and consistency semantics for the implemented surface (spec §2;
see `docs/roadmap.md` for the target model).

## Completion levels (§2.1)

Every operation in this build reports the level it actually reached:

- `applied` — executed on the owning thread against local state. The default
  for all mutating operations (`tasks.create`, `client.input.key`,
  `files` writes, `server/commands/execute`).
- `observed` — a stated postcondition was verified. Block/storage reads
  return data copied on the owning thread, so reads are observed by
  construction. Client key actions report the authoritative `isDown` state
  read after the action.
- `rendered` — a captured frame reflects the change. **Not implemented**:
  `frameId` on screenshots is a monotonic capture id, not a render-lifecycle
  correlation. Documented Phase 0 semantics.
- `persisted` — a requested save boundary completed. Not exposed yet.

If authoritative confirmation is unavailable, operations report the weaker
outcome with a reason — never a stronger claim.

## Threading model (§2.2)

- All game-state reads/writes execute on the **owning thread** (server tick
  thread for `/server/*`, client thread for `/client/*`) via
  `MapiRuntime.tryReadOnServerThread` / `tryReadOnClientThread` with a
  bounded 500 ms wait. Owning threads copy state into immutable DTOs;
  serialization and image encoding happen off-thread.
- Live Minecraft objects never cross into HTTP workers.
- Filesystem endpoints (`/files`, `/logs`, `/crash-reports`) are
  HTTP-worker-side: no game state involved.

## Revisions & preconditions (§2.3)

Mutations accept `expectedWorldSessionId`; a mismatch → **409**
`STALE_SESSION` with the current id (explicitly `null` between sessions).
`expectedConnectionSessionId` is accepted but rejected with
`CONNECTION_SESSION_UNAVAILABLE` until connection sessions exist.
Screen/menu revisions (`expectedScreenRevision`) land with screen-tree
revisions.

## Time model (§2.4)

`GET /api/v1/time` reports `wallClock` (epoch ms), `monotonicNanos`
(process-local), and `serverTick` (explicit `available`/`reason` shape).
Cross-instance coordination uses observed conditions (task `wait-for-tick`,
event cursors), never timestamp alignment.

## Action modes (§5.2)

Every interaction endpoint takes `mode` where applicable — currently
`client.input.key` enforces `mode: "input"` (the game's own key-mapping
path); other modes are rejected, **no silent fallbacks**. `semantic`,
`admin`, and `unsafe` modes attach to endpoints that do not exist yet.

## Control leases (§5.3)

`POST /api/v1/leases` for `client.input`, `client.ui`, `client.camera`,
`server.tick`, `world.bulkEdit` — expiring (default 60s, clamp 1s–1h),
renewable, conflict policy `reject` (409 `LEASE_HELD`), `queue` (FIFO), or
`preempt`. On expiry/release/preemption/world-end/shutdown, per-type
cleanup hooks run — `client.input` releases every API-held key (physical
input untouched). `/mcapi stop` local emergency stop is a Phase 1+ item.
