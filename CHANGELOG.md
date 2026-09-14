# Changelog

All notable changes to MAPI and its HTTP contract. The HTTP protocol version
(`/api/v1/` prefix + `protocolVersion`) changes only on breaking contract
changes; additive changes (new optional fields, new endpoints) keep protocol
version 1 and consumers must ignore unknown fields. See
`docs/http-api.md#versioning` and `docs/api.md` for the compatibility rules.

## 0.1.0 (2026-09-13)

Experimental release (pre-1.0.0): breaking changes are allowed with a minor
bump and a note in `docs/api.md`. Everything below landed in this repository
across the Phase 0–5 delivery slices (see `docs/roadmap.md`).

### Added

- **Session identity**: `processSessionId` (per launch), `worldSessionId`
  (per server/world session, absent between sessions), `physicalSide`
  (`client`/`dedicatedServer`), `availableLogicalSides`, coarse `readiness`.
- **Secure bootstrap**: bearer token from `MAPI_HTTP_TOKEN`, `http.token`,
  or an auto-generated owner-restricted token file `<gameDir>/mcapi/token`
  (atomic, POSIX 0600 where available, non-secret SHA-256 fingerprint in
  logs; values never logged).
- **Discovery**: `<gameDir>/mcapi/discovery.json` (schema 1, atomic write,
  sanitized `instanceId`, no secrets), heartbeat refresh, removed on stop.
- **HTTP surface (protocol version 1)**, loopback-only, bearer-token on
  every endpoint, per-source rate limiting, Host/Origin allowlists,
  CORS disabled:
  - Common: `health`, `live`, `ready`, `time`, `info` (+ identity, scopes,
    support), `capabilities` (§5 model), `mods`, `registry/{type}`,
    `tags/{type}`.
  - Tasks: `POST /api/v1/tasks` (202 protocol, states, progress, partial
    effects, cancellation, deadlines, `LIFECYCLE_CHANGED`), reads, cancel;
    `Idempotency-Key` with replay + `422 IDEMPOTENCY_MISMATCH`.
  - Events: cursor polling (`?after=`) with explicit `gap` objects and the
    WebSocket stream (dedicated loopback port; RFC 6455; single-use 30s
    tickets for browsers; `drop-oldest`/`disconnect` slow-consumer policies).
  - Server: `status`, `players` (paginated, field-selected), console
    `commands/execute` with a permission ceiling, `world/block`,
    `world/block-entity` (typed NBT JSON), `world/storage` (container read),
    `world/time`.
  - Client: `status`, `screen/tree` (best-effort), `input/key` (input mode,
    no silent fallbacks), `screenshot` (monotonic `frameId`).
  - Leases: acquire/renew/release/list with `reject|queue|preempt` and
    key-release cleanup hooks.
  - Diagnostics: `threads`, `memory/gc` (`diagnostics` scope).
  - Logs: `logs`, `logs/errors`, `crash-reports` with
    `provenance: game-logs-untrusted`.
  - Extension SPI: `GET|POST /api/v1/ext/{id}/…` + `$schema` from the public
    `MapiHttpExtension` API.
  - Experimental: `unsafe/reflect`, `unsafe/invoke` (`unsafe.execute` +
    `reflection.enabled`, audited).
  - Sandboxed files: `GET/POST /api/v1/files` with real-path containment,
    symlink-escape rejection, no-follow writes, and a security denylist.
- **Fine-grained switches** (§4.5): `reflection.enabled`, `files.enabled`
  (both default off) in addition to scope gating.
- **Agent tooling**: MCP adapter (stdio JSON-RPC, 11 tools), Python / JVM /
  TypeScript SDKs (token-file auth, discovery, tasks, leases, commands,
  event cursors with gap reporting), harness module (discovery validation,
  readiness waiting).
- **Developer workflow**: `runServerApi`/`runClientApi` Gradle tasks,
  `launchMatrix` (EULA-gated), `scripts/collect-artifacts.sh` (§13.9
  artifact bundle with token-redaction sweep), doctor script.
- **Contract tripwires**: routes ↔ `openapi.yaml` ↔ `http-api.md` sync,
  pinned OpenAPI subset, distributable-jar client-class scan.
- **Docs** (`docs/`): ARCHITECTURE (with add-endpoint checklist),
  CAPABILITIES, SEMANTICS, EVENTS, DATA, TESTING, RECIPES, HTTP API,
  SECURITY, SUPPORT, TOOLCHAIN, ROADMAP; `llms.txt`.

### Contract compatibility notes

- Additive-only so far: protocol version remains **1**; consumers must
  ignore unknown fields. Identity fields on `/info` are additive.
- Until 1.0.0 the public Java API is experimental (see `docs/api.md`);
  `MapiHttpExtension` (2.4) and the physical-side seam were added after the
  initial 0.1.0 types under that policy.
- The 0.1.0-era rule "player identities are never exposed" was replaced by
  the documented posture change (D7, `docs/security.md`):
  `/server/players` exposes name/UUID/dimension/position.
- The 0.1.0-era "will never do" list was replaced by the scope model +
  capability tiers; the current phase still excludes command *mutation*
  beyond console execution, world mutation, chat, and source editing.

### Changed (2026-09-14, later in the day)

- **Breaking config change**: the configuration file is now **TOML** and is
  **generated automatically on first start** (no manual setup needed to
  configure connect + auth):
  - NeoForge: loader-managed `mapi-common.toml` from a registered
    `ModConfigSpec` (keys identical to the shared table; the HTTP listener
    binds on `ModConfigEvent.Loading` and rebinds on `Reloading`).
  - Fabric: `<configDir>/mapi.toml` created at process init with defaults and
    comments (TOML is the de-facto standard on Fabric, which ships no config
    system).
  - Legacy `mapi.properties` is still read when present (migration), but is
    never fabricated. `mapi.properties.example` replaced by
    `docs/examples/mapi.toml.example`.

### Verified (2026-09-14)

The operator accepted the EULA and the game-run checks were executed:
`launchMatrix` PASS on both loaders (discovery → `worldReady`, HTTP
contract, task protocol) and `server-smoke` PASS on both loaders with the
live HTTP probe. The dedicated-server classload check is authoritative.
See `docs/TESTING.md`.

### Known gaps (planned, not built — see `docs/roadmap.md` §6)

Profiler start/stop, `/eval`, storage insert/extract, GameTest, region
fixtures, navigation/goto, dynamic worlds, packet inspection, session
replay, offscreen rendering, video, dashboard, `/mcapi stop` keybind, and
physical-client (display) verification of the client endpoints.
