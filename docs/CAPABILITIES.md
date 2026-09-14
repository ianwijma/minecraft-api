# Capability model (spec §5)

`GET /api/v1/capabilities` returns one entry per implemented operation with
dynamic state evaluated for the **requesting token**:

```json
{"op":"server.world.block","supported":true,"enabled":true,"available":false,
 "authorized":true,"coverage":"adapter-backed","tier":"core"}
```

- `supported` — the op exists in this build (always `true` in the table).
- `enabled` — the fine-grained switch (§4.5) allows it (`reflection.enabled`
  for `unsafe.*`, `files.enabled` for `files.*`; otherwise `true`).
- `available` — runtime state (server session active, client ops registered).
- `authorized` — the requesting token's scopes cover the op's scope.
- `coverage` — `full` (documented, contract-tested) | `adapter-backed`
  (loader seam, parity-tested) | `best-effort` (widget-tree style).
- `tier` — `core` (mandatory, zero parity exceptions), `extended`,
  `experimental`.

Filter with `?tier=core|extended|experimental`. The table below is the
contract; `internal.capability.CapabilityEntry.TABLE` is the source of truth
and `ContractSyncTest` keeps routes documented.

## Core tier (parity-required on both loaders)

| Op | Scope | Coverage |
| --- | --- | --- |
| info, live, ready, time, capabilities | observe | full |
| mods.read | observe | full |
| registry.read, tags.read | observe | adapter-backed |
| tasks.create / read / cancel | observe | full |
| events.poll, events.stream | observe | full |
| leases.acquire, leases.manage | observe (per-type scope on acquire) | full |
| logs.read, crash-reports.read | diagnostics | full |
| server.status, server.players | observe | adapter-backed |
| server.commands.execute | commands.execute | adapter-backed |
| server.world.block / block-entity / storage / time | world.read | adapter-backed |
| client.status | client.control | full |
| client.screen.tree | client.control | best-effort |
| client.input.key | client.control | adapter-backed |

## Extended

| Op | Scope | Coverage | Notes |
| --- | --- | --- | --- |
| client.screenshot | client.control | adapter-backed | `frameId` = monotonic capture id |
| diagnostics.threads, diagnostics.memory-gc | diagnostics | full | pure JDK |
| files.read, files.write | files.* | full | `files.enabled` switch, sandboxed |
| ext.spi | per-extension | adapter-backed | `/api/v1/ext/{id}/…` |

## Experimental

| Op | Scope | Coverage | Switch |
| --- | --- | --- | --- |
| unsafe.reflect, unsafe.invoke | unsafe.execute | full | `reflection.enabled` |

Operations deliberately **absent** from the table are not implemented in
this build (see `docs/roadmap.md` for the planned-not-built list): profiler
start/stop, `/eval`, storage insert/extract, GameTest, region fixtures,
navigation/goto, dynamic worlds, packet inspection, session replay,
offscreen rendering, video, dashboard.
