# MAPI Product Specification

> Status: approved as the target product definition on 2026-09-13. The
> architecture ADRs required by §15.2 are **not yet approved**; until they
> are, coding agents must keep code changes within the currently implemented
> read-only HTTP status surface (see `AGENTS.md`). This document defines
> target scope, contracts, and acceptance criteria — not the current
> implementation state.

---

## 1. Revised product boundary

The project has three deliverable groups:

1. **Minecraft automation mod**
   - Fabric and NeoForge artifacts.
   - Secure HTTP API.
   - Observation, input, game-state access, synchronization, and bounded execution.
2. **API contract and SDKs**
   - OpenAPI contract.
   - Generated TypeScript, Python, and Java clients.
   - Small handwritten helpers where generation is insufficient.
3. **Reference automation runner**
   - Out-of-process testing utilities and CLI.
   - Uses only the public API for game observation and control.
   - Provides the reference E2E harness and reusable functionality for the future MCP project.

The mod must work without the runner, MCP, an LLM, or an IDE.

### 1.1 Responsibility allocation

| Capability | Mod | SDK helpers | Reference runner |
|---|---|---|---|
| Authentication and authorization | Enforce | Supply credentials | Manage credential references |
| Instance identity | Publish | Read and validate | Discover and assign participants |
| Input injection | Execute | Typed calls | Compose actions |
| Tick/frame synchronization | Execute locally | Wait helpers | Coordinate instances |
| Screenshots | Capture and encode | Download | Store and compare |
| UI inspection | Capture semantic data | Typed selectors | Workflow composition |
| World inspection | Capture bounded state | Typed queries | Assertions |
| Snapshot differences | Bounded server-side differences | Typed results | Cross-instance and historical analysis |
| Navigation | Execute bounded movement | Action helpers | Plan routes |
| Recording | Emit action receipts/events | Stream handling | Persist recordings |
| Replay | Execute requested actions | Retry-safe transport | Replay and detect divergence |
| Reproducibility metadata | Publish local facts | Retrieve | Assemble bundles |
| Fixture preparation | Apply game operations | Typed calls | Sequence setup and teardown |
| World backup/reset | Report lifecycle and request shutdown | Lifecycle calls | Copy/restore at safe boundaries |
| Process lifecycle | Graceful local shutdown | Request shutdown | Launch, restart, kill, provision |
| Visual diffs and reports | Not required | Not required | Required |

**No independently maintained runner implementation is required in every SDK language.** Ship one reference runner with a versioned CLI and machine-readable results. All SDKs can invoke it externally; none must depend on it.

---

## 2. Non-goals

The initial complete product does not include:

- Launcher implementation.
- Microsoft/Minecraft account authentication or account purchasing.
- Authentication bypass against online-mode servers.
- Anti-cheat evasion or undetectable automation.
- Protocol-level bot clients.
- Automatic compatibility with unsupported Minecraft versions.
- Universal semantic understanding of every custom-rendered interface.
- Operating-system desktop automation.
- Arbitrary remote Java evaluation, shell execution, or reflection.
- Hot-reloading arbitrary Java code, mixins, or registrations.
- Guaranteed deterministic behavior from arbitrary third-party mods.
- Transactional rollback of arbitrary game/mod side effects.
- A general-purpose distributed test scheduler.
- MCP transport, LLM planning, or source-code editing.

External launch/build integration used by the reference harness is allowed; implementing a general launcher is not required.

---

## 3. Input execution contract

### 3.1 Explicit execution modes

Every action operation must declare its supported execution modes.

| `executionMode` | Meaning |
|---|---|
| `raw-input` | Synthetic key, character, mouse, or scroll events delivered through the supported game input path |
| `client-logic` | Calls ordinary client interaction logic without reproducing every underlying input event |
| `privileged` | Direct development/setup operation that bypasses the ordinary player interaction path |

Examples:

| Action | Mode |
|---|---|
| Press the inventory key | `raw-input` |
| Hold forward | `raw-input` |
| Mouse delta interpreted through normal camera input | `raw-input` |
| Invoke a container slot-click handler | `client-logic` |
| Invoke a recognized widget action directly | `client-logic` |
| Set camera orientation directly | `privileged` |
| Set player position through server control | `privileged` |
| Connect directly without navigating menus | `privileged` |
| Execute a console-context server command | `privileged` |

A `client-logic` inventory click can remain **player-faithful at the gameplay level**, while not being a raw-input test. These are different fidelity claims.

### 3.2 Separate mechanism from authorization

Each operation also declares:

- Required scopes.
- Whether a control lease is required.
- Whether it is destructive.
- Whether it has unrestricted or unknown side effects.
- Which normal game validations remain active.
- Which input or UI hooks it bypasses.

`executionMode` is not a permission system.

For example, direct connection setup requires `client:connect`, even though it does not grant world-editing authority.

### 3.3 No silent fallback

- The caller selects an execution mode.
- Unsupported modes return `EXECUTION_MODE_UNSUPPORTED`.
- The mod must not silently replace raw input with widget invocation.
- The mod must not silently replace movement with teleportation.
- A future explicit fallback policy may permit selected alternatives, but the actual mode must always be reported.

### 3.4 Action receipts

Every executed action returns or produces a receipt containing:

- `actionId`.
- `requestId` and correlation identifiers.
- Requested and actual execution mode.
- Input backend identifier and version.
- Start/end tick or frame information.
- Relevant world, connection, screen, and container generations.
- Dispatch outcome.
- Whether an effect was verified.
- Verification evidence, if requested.
- Partial execution and cancellation information.

“Input delivered,” “client state changed,” and “server effect confirmed” must remain distinct outcomes.

### 3.5 Input backend requirements

The baseline backend must support:

- Process-local keyboard and mouse events.
- Synthetic held-state tracking.
- Modifier state.
- Character input separate from key presses.
- Tick-aware keybinding behavior.
- Frame-aware pointer and camera behavior.
- Consistent synthetic state through explicitly supported game-level polling paths.
- Release-all behavior on cancellation, lease expiry, and emergency stop.

The backend must report its coverage:

- Callback/event dispatch.
- Game keybinding state.
- Game helper polling.
- Screen dispatch.
- Known unsupported native polling paths.

**Boundary:** dispatching callbacks does not necessarily change the state returned by direct native GLFW polling. Compatibility with such mods requires an explicitly tested bridge or adapter, not an unsupported claim of universal fidelity.

Fabric’s test-input documentation itself distinguishes character input from key presses and notes that many keybindings need a tick to react. This reinforces the need for an explicit timing contract. [Reference](https://maven.fabricmc.net/docs/fabric-api-0.141.3+1.21.11/net/fabricmc/fabric/api/client/gametest/v1/TestInput.html)

---

## 4. Input timing and clocks

### 4.1 Named clocks

The API must distinguish:

| Clock | Meaning |
|---|---|
| `wall` | Monotonic elapsed time, independent of game progress |
| `client-tick` | Client update boundaries |
| `client-frame` | Rendered frame boundaries |
| `server-tick` | Server tick-loop boundaries |
| `server-simulation-step` | Simulation advancement under the supported tick-control implementation |

Expose clock availability and progress state.

Do not use world time as an action scheduler: commands and game rules can alter it.

### 4.2 Input scheduling

For a key held for `N` client ticks:

1. Dispatch key-down before the selected client input-processing boundary.
2. Keep synthetic held state active for `N` qualifying client ticks.
3. Dispatch key-up before the following qualifying boundary.
4. Report the boundaries actually observed.

A press action must not collapse key-down and key-up into an interval in which the relevant consumer never observes the press.

For mouse look:

- Raw deltas are applied at a defined input/frame boundary.
- Report the applied frame and resulting observed camera orientation.
- Sensitivity and other normal input settings remain relevant in raw-input mode.

Exact direct orientation changes are separately classified as privileged.

### 4.3 Every wait has a wall-clock deadline

Tick- and frame-based operations also require a wall-clock deadline.

This prevents indefinite waiting when:

- The integrated server is paused.
- Rendering stops.
- A client is minimized or throttled.
- The requested world unloads.
- The game thread stalls.

Lease expiry and request deadlines use a monotonic wall clock, not game ticks.

### 4.4 Pause semantics

Expose separate states for:

- Singleplayer pause.
- Tick freeze.
- Empty-server pause where applicable.
- Client focus loss.
- Rendering suspension.
- World loading.
- Game-thread stall.

An open menu does not, by itself, prove the server is paused.

Default behavior for waits requiring unavailable simulation progress:

- Return `SERVER_PAUSED` or `CLOCK_NOT_ADVANCING`.
- Include the reason and available recovery operations.
- Do not silently unpause.

An explicit `pausePolicy: "wait"` may wait for progress to resume, but remains bounded by the wall-clock deadline.

Read-only cached status, cancellation, emergency stop, and lifecycle information must remain available without requiring simulation progress. Fresh game-state reads must report when they cannot safely execute.

### 4.5 Cleanup under stalled threads

A wall-clock watchdog must revoke expired control immediately at the API level and prevent further dispatch.

Actual game-state cleanup occurs at the next safe game-thread opportunity. A deadlocked JVM cannot promise immediate physical key-release processing; the supervisor must terminate it when necessary.

---

## 5. First-class tick control

### Required operations

Suggested route family: `/server/v1/ticks/*`

- Query tick-control state.
- Freeze.
- Unfreeze.
- Set target tick rate within configured bounds.
- Step a bounded number of simulation ticks.
- Stop stepping.
- Sprint a bounded number of simulation ticks.
- Stop sprinting.
- Step and capture a bounded observation at the completion boundary.

Long-running operations use the standard job system.

### Required reporting

- Initial and final tick-control state.
- Requested and completed steps.
- Completion boundary.
- Elapsed wall time.
- Relevant tick duration statistics.
- Interruption reason.
- Whether the resulting snapshot was captured at the requested boundary.
- Any known subsystems not governed by tick control.

### Important limitations

Vanilla’s documented tick freeze excludes players and ridden entities. Therefore, freezing is not equivalent to freezing all game state. [Minecraft tick command documentation](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3)

Consequently:

- `stepAndObserve` is a scoped synchronization primitive.
- Client rendering and network activity are not automatically synchronized.
- Mod-owned threads and external services may continue.
- Exact step counts do not imply global deterministic execution.
- Tick controls do not silently override a singleplayer pause.

Tick-control ownership requires an exclusive lease. Automatic restoration on lease expiry is configurable and must not overwrite a later manual change.

For versions without a compatible implementation, support must be explicitly excluded or supplied by a separately certified bridge.

---

## 6. World lifecycle and outstanding work

Each loaded server world session has a unique `worldSessionId`. Connection sessions, screens, and containers have corresponding identities or generations.

On world unload:

- Stop accepting new world-scoped operations.
- Terminate outstanding world-scoped jobs with `WORLD_UNLOADED`.
- Release world-scoped leases and chunk tickets.
- Invalidate world object handles and relevant snapshots.
- Cancel client gameplay actions tied to the departing connection.
- Release held gameplay inputs.
- Emit ordered lifecycle events.
- Never migrate pending actions into the next world.

New requests with no loaded world return `WORLD_NOT_LOADED`.

Requests carrying a previous world identity return `STALE_WORLD`.

Process-level observers and explicitly process-scoped sessions may remain connected.

The `/server/*` route namespace remains stable on integrated-server-capable clients; its availability changes with lifecycle state. It never becomes a proxy to an unrelated remote server.

---

## 7. Multiplayer account identity and participant mapping

### 7.1 Supported identity strategies

#### Isolated offline test environment — required harness support

- Server configured with `online-mode=false`.
- Distinct per-client launch usernames.
- Version-specific launch configuration supplied by the external harness.
- Server restricted to loopback or an isolated test network.
- Explicit warnings that Minecraft player authentication is disabled.
- Profile/chat-related settings validated for the selected Minecraft version.

Do not assume an arbitrary launch UUID is the authoritative server UUID. Read the established identity after joining.

Offline mode disables account verification; it must not be exposed publicly as an ordinary test default. [Server configuration reference](https://minecraft.wiki/w/Server.properties)

#### Online-mode environment — supported, externally provisioned

- Each simultaneous participant on the same server uses a distinct authenticated profile.
- Accounts and session credentials are supplied externally.
- No account credential storage in the automation API.
- No account switching or authentication implementation required in the mod.

The duplicate-profile issue concerns simultaneous participants on the **same server**. Separate test servers are a different deployment case.

### 7.2 Participant mapping

Expose:

- Runner-assigned `participantId`.
- Client `instanceId` and `bootId`.
- `connectionSessionId`.
- Local launch profile name/UUID where available.
- Joined player name/UUID.
- Current dimension and entity identifier.
- Configured target address.
- Server identity when verified through an available cooperative mechanism.

The runner maintains:

> Participant → client process → connection session → authoritative server player.

A client’s assertion about which server it joined is not itself proof of server identity. Report whether mapping is address-based, observed through both APIs, or verified through an optional handshake.

---

## 8. Versioned test profiles

Ship versioned test-profile templates and a preflight validator.

These profiles are opt-in and apply only to dedicated test directories. Installing the mod must not silently overwrite a user’s normal settings.

### 8.1 Client profile

Define and verify effective values for:

- Window size.
- Fullscreen state.
- GUI scale.
- Language.
- Mouse sensitivity.
- Auto-jump.
- Toggle sprint/sneak behavior.
- `pauseOnLostFocus`.
- Background frame limiting.
- Render and simulation distances.
- Frame-rate limit and VSync.
- Tutorial/onboarding state.
- Chat/multiplayer warning state where supported.
- Narrator state.
- Resource-pack set.
- Audio levels when relevant to tests.

Where onboarding cannot be configured through a supported setting, document the required bootstrap workflow instead of patching around it invisibly.

Maintain a separate **fresh-profile test suite** so suppression of onboarding does not conceal regressions in menu automation.

### 8.2 Server profile

Define:

- Authentication mode.
- Bind address and port.
- Player capacity.
- Seed and generation settings.
- View/simulation distances.
- Difficulty and game mode.
- Relevant gamerules.
- Empty-server pause behavior where supported.
- Resource/datapack set.
- API configuration and limits.

### 8.3 EULA responsibility

The supervisor must require explicit operator-provided EULA acceptance before provisioning a server.

The mod must not accept it automatically.

### 8.4 Preflight output

Before a test begins, produce:

- Profile ID and version.
- Requested settings.
- Effective settings.
- Deviations.
- Unsupported settings.
- Identity conflicts.
- Rendering readiness.
- Missing permissions.
- Fatal versus advisory issues.

---

## 9. Window, connection, and LAN control

### 9.1 Window operations — required

Suggested route family: `/client/v1/window/*`

- Query window state.
- Set windowed dimensions.
- Enter/leave fullscreen.
- Set GUI scale.
- Wait for the resulting layout and framebuffer state.
- Read effective dimensions after platform adjustment.

Report:

- Logical window size.
- Framebuffer size.
- GUI coordinate dimensions.
- GUI scale.
- Content scale/DPI information when available.
- Coordinate transforms.
- Fullscreen state.
- Window/screen revision.

The operating system may adjust requested dimensions. Return actual values and never assume one logical pixel equals one framebuffer pixel.

### 9.2 Direct connection — required

`POST /client/v1/connections`

- Uses privileged setup mode.
- Requires `client:connect`.
- Requires connection-control ownership.
- Enforces target allowlists at the final connection boundary.
- Reports resolution, connection, login, configuration, and world-ready stages.
- Supports cancellation and deadlines.

Allowlist enforcement must account for resolved addresses and relevant address redirection, not only the text typed by the caller.

The same target policy applies when an API-controlled player joins through menus. Otherwise raw input would bypass the connection restriction.

### 9.3 Publish integrated server to LAN — required

`POST /server/v1/lan`

- Available only for an active integrated server.
- Requires a distinct network-exposure permission.
- Uses privileged mode.
- Accepts supported port and publication settings.
- Reports the actual game port and exposure state.
- Does not alter the API listener.

Publishing a world can expose the **Minecraft game port**, independently of API authentication.

If the underlying version cannot safely unpublish without unloading the world, report that limitation. Do not promise a reversible “close LAN” operation that does not exist.

---

## 10. UI semantics and tooltip capture

### 10.1 Semantic-source hierarchy

Use these sources in order of reliability:

1. Explicit automation IDs and registered mod adapters.
2. Recognized widgets and container structures.
3. Accessibility/narration information.
4. Captured text and render-associated metadata where reliable.
5. Screenshot plus raw input.

Narration information is a useful source of labels and state, but not necessarily a complete hierarchical accessibility tree.

Each field should identify its source where ambiguity matters. For example:

- `adapter`.
- `widget`.
- `narration`.
- `render-capture`.

Do not invent bounds, roles, or actions from narration text alone.

### 10.2 Tooltip operations

Distinguish:

- **Rendered tooltip capture:** what the client actually displayed.
- **Computed tooltip data:** text/components calculated without reproducing hover.

For rendered capture:

1. Validate the screen and target.
2. Move the synthetic pointer to the target.
3. Apply requested modifiers.
4. Wait for at least one completed render pass and any required hover delay.
5. Capture the tooltip emitted during that pass.
6. Return frame and screen revision.

Hovering requires control ownership because it changes input state.

Custom-rendered tooltips may yield only a screenshot region. Return `TOOLTIP_SEMANTICS_UNAVAILABLE` rather than an invented text result.

### 10.3 Inventory scope adjustment

**Required high-level operations:**

- Pick up/place.
- Split stacks.
- Shift-click.
- Hotbar swap.
- Drop.
- Basic crafting interactions.
- Cursor-stack observation.
- Server-confirmed postconditions where available.

**Extension/Future high-level convenience operations:**

- Drag-distribution planning.
- Complex recipe workflows.
- Mod-specific ghost-slot semantics.
- Automatic ingredient allocation across arbitrary crafting systems.

Raw dragging remains required, so advanced interactions remain possible through action sequences even without a dedicated convenience operation.

---

## 11. Canonical data encoding

The API must publish a versioned encoding specification.

### 11.1 Separate three representations

1. **Normalized API DTOs**
   - Stable fields for common entities, blocks, inventories, and items.
2. **Codec JSON**
   - Diagnostic or import/export representation produced by an available vanilla/mod codec.
3. **Typed NBT representation**
   - Explicitly preserves NBT types without relying on JSON type inference.

Optional SNBT is a human-readable companion representation where supported.

Minecraft codecs can target JSON or NBT, but those formats are not interchangeable without limitations. In particular, numeric-list conversion can lose the typing needed for NBT. [Fabric codecs](https://docs.fabricmc.net/develop/serialization/codecs) · [Numeric conversion caveat](https://docs.minecraftforge.net/en/1.21.x/datastorage/codecs/)

### 11.2 Canonical typed representation

| Type | Wire representation |
|---|---|
| Byte/short/int | Explicit type plus bounded JSON integer |
| Long | Explicit type plus decimal string |
| Float/double | Explicit type plus round-trippable value representation |
| Non-finite float | Tagged `"NaN"`, `"Infinity"`, or `"-Infinity"` |
| Byte array | Tagged base64 payload |
| Int array | Tagged array of bounded JSON integers |
| Long array | Tagged array of decimal strings |
| List | Explicit list type information and typed elements |
| Compound | Map of names to typed values |
| String | JSON string |

Requirements:

- Strict valid JSON; no bare NaN or Infinity tokens.
- Preserve negative zero where relevant.
- Define ordering and normalization for hashing/diffing.
- Define binary byte order for binary artifacts.
- Reject oversized/deep payloads before game-thread work.
- Report unsupported or unserializable fields explicitly.
- Do not use Java object stringification as a serialization fallback.

Exact NaN payload-bit preservation is not required in the normal JSON representation; binary export may provide that level of fidelity.

### 11.3 Item components

Report:

- Item registry ID.
- Count.
- Effective component view where available.
- Component patch, including explicit removals where supported.
- Namespaced component IDs.
- Serialization status per component.
- Registry context required to decode.

Do not conflate “component absent,” “default inherited,” and “component removed.”

### 11.4 Version metadata

Include:

- API encoding version.
- Minecraft version.
- Minecraft data version where applicable.
- Modpack/registry fingerprint.
- Relevant adapter schema version.

A data version is compatibility metadata, not a guarantee that arbitrary mod data can be upgraded automatically.

Cross-version import must be explicitly supported or rejected.

---

## 12. Snapshot differences and efficient verification

### Required bounded server-side diffing

Suggested route: `POST /server/v1/snapshot-diffs`

Compare retained snapshots for:

- Entity presence and selected fields.
- Player state.
- Inventory slots and cursor/container state where applicable.
- Selected block and block-entity state.

Return:

- Added/removed/changed records.
- Before/after values or selected projections.
- Snapshot identities.
- Capture boundaries.
- Truncation information.
- Unavailable fields.
- Query coverage changes.

**Important:** absence from a bounded query does not prove an entity was destroyed. An entity may have left the region, unloaded, or stopped matching a filter.

Snapshots have quotas and retention periods. Expired comparisons return `SNAPSHOT_EXPIRED`.

Cross-process comparisons, long-term history, and complex assertions remain runner responsibilities.

---

## 13. Event streaming and waits

### 13.1 Browser authentication decision

**Use fetch-based SSE streaming with the normal bearer header.**

Do not add query-string tokens or stream tickets in the initial implementation.

Native browser `EventSource` exposes URL and credential-mode options, not arbitrary authorization headers. [MDN constructor reference](https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource)

SDK streaming helpers must implement:

- Authorization headers.
- Incremental SSE parsing.
- Reconnect policy.
- Resume cursors.
- Cancellation.
- Authentication failure handling.
- Explicit event-gap handling.
- No silent abandonment when a browser tab becomes hidden.

CORS remains disabled unless explicitly configured.

### 13.2 Additional wait operations

Add:

- Wait for job state/completion.
- Wait for an event matching a bounded filter.
- Wait for an event after a specified cursor.
- Wait for a predicate to remain true for a duration.

A caller must be able to establish an event cursor before triggering an action, then wait from that cursor. This prevents the common “event occurred before subscription” race.

Event filter expressions must be declarative and bounded, not executable uploaded code.

---

## 14. Security scope refinement

Add:

- `client:connect`.
- `client:settings`.
- `server:tick-control`.
- `server:publish`.
- `operations:destructive`.
- `operations:unrestricted`.

Each operation publishes:

- `requiredScopes`.
- `destructive`.
- `sideEffectClass`.
- `requiresLease`.
- `supportedExecutionModes`.

### Destructive authorization

A destructive request requires:

1. Its normal operation scope.
2. Explicit destructive permission.
3. Explicit request intent.
4. A matching target/world identity where applicable.

A request flag alone does not grant permission.

### Unrestricted command caveat

Arbitrary mod commands cannot be safely classified by parsing their names.

Therefore:

- Unrestricted command execution requires an explicit unrestricted grant.
- It may bypass finer-grained world mutation restrictions.
- It must be documented as administrative access.
- Commands are not made safe merely because the structured routes are restrictive.

Similarly, raw UI control can cause ordinary gameplay destruction. A `destructive` flag is not a promise to prevent all destructive gameplay.

Known privileged boundaries—network connection, world deletion, LAN publication—must enforce policy regardless of whether reached through a direct route or API-driven UI actions.

---

## 15. Cross-version architecture and mixin policy

### 15.1 Build strategy decision

**Recommended baseline: one repository with common non-Minecraft modules and explicit per-version bridges.**

Suggested module groups:

- Contract and DTOs.
- Transport/authentication.
- Jobs, leases, events, and scheduling abstractions.
- Minecraft-version bridge.
- Fabric platform integration.
- NeoForge platform integration.
- Optional adapters.
- Cross-loader fixture mod.
- SDK generation.
- Reference runner.

Minecraft classes must not leak into the transport contract.

Share implementation within compatible version families where useful, but do not force materially different Minecraft internals through a large set of conditional branches.

### 15.2 Before Milestone 1

Approve an ADR defining:

- Initial supported Minecraft version or versions.
- Mapping strategy.
- Java toolchains.
- Per-version source/build organization.
- Loader dependency policy.
- CI matrix.
- Backport policy.
- End-of-support policy.
- Criteria for introducing preprocessing later.

Supporting multiple Minecraft versions simultaneously is a separate commitment from supporting both loaders. Do not declare an unspecified range supported.

### 15.3 Loader events first, mixins last

Prefer:

1. Public Minecraft APIs.
2. Loader lifecycle/input/render events.
3. Narrow access bridges.
4. Targeted mixins where necessary.

For every mixin, document:

- Target and injection point.
- Reason no suitable supported hook exists.
- Required versus optional status.
- Expected interaction with other modifications.
- Failure behavior.
- Covered tests.

Maintain `docs/mixin-surface.md`.

A failed optional compatibility hook disables only its capability and reports why. A missing required safety/input hook must not silently advertise full functionality.

Compatibility testing should include declared rendering and input-mod configurations. It must not claim compatibility with every such mod.

---

## 16. Scope adjustments

| Capability | Revised classification |
|---|---|
| Raw input, screenshots, UI observation | Required mod |
| Tick control | Required for declared compatible versions |
| Bounded loaded-world observations | Required mod |
| Straight-line movement and waypoint execution | Required mod |
| Basic loaded-world path planning with stop-on-stuck | Required runner |
| Hazard-aware replanning across complex terrain | Extension/Future |
| Arbitrary mod-specific traversal | Extension |
| Visual baseline comparison and diff generation | Required runner |
| Recording/replay and divergence detection | Required runner |
| Reproducibility bundle assembly | Required runner |
| Generic fixture operations | Required mod |
| Fixture orchestration | Required runner |
| Arbitrary machine-state fixture adapters | Extension |
| Cross-loader example machine adapter | Required test fixture |
| Synthetic/fake players | Extension/Future |
| Protocol bots | Non-goal |
| Sound-event observation | Future |
| Particle-event observation | Future |

For future sound/particle observations, distinguish a request/event from actual audible output or a rendered particle. They are not equivalent assertions.

---

## 17. Logs, backups, and resets

### 17.1 Log capture

Attach a bounded appender to the supported process logging backend, using Log4j integration for the declared environments that use it.

Requirements:

- No global logging reconfiguration.
- Bounded buffering.
- Severity/logger filters.
- Secret redaction.
- Avoid recursive logging from the appender itself.
- Capture-start timestamp and coverage metadata.

Loader/launcher messages emitted before attachment are not guaranteed to appear in API log history.

The supervisor captures process stdout/stderr and relevant files to cover early startup and crashes.

### 17.2 Safe backup boundary

**Required full-world backup strategy:**

- Dedicated server: process stopped, files closed, no competing writer.
- Integrated server: server fully stopped and world storage closed.
- Mod-owned external files: separately documented and quiesced, or excluded with an explicit warning.

Restore occurs only while the affected world/server is stopped.

### Optional live backup

`save-off` followed by `save-all flush` may be part of a documented live-backup strategy, but is not a universal consistent snapshot of arbitrary mods.

It does not automatically quiesce:

- External databases.
- Mod-owned background writers.
- Files outside the world directory.
- Every asynchronous task.

Live backup should therefore be Extension/Future unless a precise consistency scope is implemented and tested. Saving must be restored in a cleanup path after failure.

---

## 18. Measurable acceptance criteria

The following are **proposed release targets**, not claims of already measured performance. Adopt or revise them before implementation and pin the reference hardware, JVM, rendering stack, fixtures, and workload.

| Area | Proposed gate |
|---|---|
| Contract compliance | Every documented core operation covered by schema/behavior tests |
| Loader parity | All applicable required tests pass on both loaders |
| Deterministic baseline reliability | Zero unexplained failures across 300 consecutive runs of each designated critical scenario per loader |
| Lifecycle reliability | Zero leaked held inputs, leases, or tickets across 100 load/unload cycles |
| Parallel isolation | Two server groups with two clients each complete repeated tests without cross-instance interference |
| Smoke-suite duration | At most 15 minutes per loader on the reference CI worker, excluding build and dependency download |
| API transport | Local metadata request p95 below 100 ms under the declared test load |
| Safe-boundary dispatch | Eligible actions dispatched within two qualifying boundaries under the declared load |
| Disabled-mode overhead | No listener or automation jobs; measured gameplay overhead within the agreed noise budget |
| Enabled idle overhead | No more than 5% regression in the declared tick/frame benchmark |
| Security | No unresolved critical/high findings in the project’s security test suite |
| Packaged artifacts | Dedicated server, client, and integrated-server tests pass using release JARs |
| SDK interoperability | Same behavioral SDK tests pass against corresponding Fabric and NeoForge instances |

Also require:

- First-attempt outcomes remain visible even when diagnostic retries occur.
- No relabeling automation failures as infrastructure failures without evidence.
- Every threshold has a version-controlled measurement procedure.
- Stress/soak tests check bounded memory and artifact retention.
- Visual tests use environment-specific baselines and explicit tolerances.

Zero failures in a finite run is a release gate, not proof that the true failure probability is zero.

---

## 19. Additional documentation deliverables

Add these documents to the original documentation requirements:

| Document | Purpose |
|---|---|
| `docs/input-contract.md` | Modes, backends, polling coverage, fidelity |
| `docs/timing-and-pause.md` | Clocks, scheduling, tick freeze, pause behavior |
| `docs/participant-identity.md` | Account strategies and server/client mapping |
| `docs/test-profiles.md` | Versioned client/server profiles and preflight |
| `docs/data-encoding.md` | Typed NBT, codec JSON, item components |
| `docs/mixin-surface.md` | All injection targets and compatibility risks |
| `docs/runner-boundary.md` | Mod/SDK/runner responsibilities |
| `docs/acceptance-targets.md` | Benchmarks, reliability gates, reference environment |
| `docs/non-goals.md` | Explicit project boundaries |

The architecture ADRs must be approved before agent-driven implementation begins. Otherwise, different coding agents are likely to make incompatible assumptions about versions, input semantics, or runner ownership.

---

## 20. Restored and revised definition of done

### Platform and distribution

- [ ] Fabric and NeoForge pass the same applicable core behavior suite.
- [ ] Dedicated server, client, and integrated-server environments work.
- [ ] Release JARs work outside development launches.
- [ ] Supported Minecraft/loader/Java versions are explicitly declared.
- [ ] A version-maintenance ADR and backport policy are approved.
- [ ] Dedicated servers never load client-only classes.
- [ ] The mod remains usable without the runner or MCP.

### Security

- [ ] Every API connection requires valid config-backed authentication.
- [ ] Credentials are never synchronized to Minecraft players.
- [ ] Remote exposure requires explicit secure configuration.
- [ ] Connection and LAN-publication permissions are separate from input control.
- [ ] Destructive and unrestricted operations require explicit grants.
- [ ] Policy enforcement cannot be bypassed through supported alternate API paths.
- [ ] Event streams and artifacts enforce the same authorization model.
- [ ] Emergency stop and lease expiry prevent further control dispatch.

### Input and timing

- [ ] Every action declares and reports its execution mode.
- [ ] Input backend coverage and unsupported polling paths are documented.
- [ ] Raw input never silently falls back to client logic or privileged mutation.
- [ ] Tick, frame, and wall-clock timing are distinct.
- [ ] Input holds have precise boundary semantics.
- [ ] All waits have wall-clock deadlines.
- [ ] Paused/frozen/stalled states are distinguished.
- [ ] World unload invalidates outstanding world-scoped work.
- [ ] Tick-control operations report actual completion milestones.

### Server functionality

- [ ] Server commands include vanilla and modded commands with context and feedback.
- [ ] Command dispatch and asynchronous effects are distinguished.
- [ ] Bounded world, entity, inventory, registry, and chunk queries work.
- [ ] Queries do not generate chunks unless explicitly requested.
- [ ] Chunk-generation jobs report milestones and release temporary tickets.
- [ ] Structured mutations disclose authority and side effects.
- [ ] Bounded snapshot differences support efficient verification.
- [ ] Arbitrary mod data uses documented safe serialization.

### Client functionality

- [ ] Movement, look, attack, use, placement, and breaking work.
- [ ] Vanilla menu workflows work.
- [ ] Standard modded widgets are inspectable and actionable.
- [ ] Custom-rendered screens remain operable through screenshots and raw input.
- [ ] Narration is used as an explicitly identified fallback where available.
- [ ] Tooltips distinguish rendered capture from computed data.
- [ ] Container actions reject stale references.
- [ ] Window dimensions, fullscreen, GUI scale, and coordinate transforms are controllable or explicitly reported unsupported.
- [ ] Direct connection and integrated-server LAN publication work within configured policy.
- [ ] Screenshots include frame, scale, and screen metadata.

### Multiplayer and testing

- [ ] Multiple processes run without port, filesystem, input, or artifact collisions.
- [ ] Multiple observers and lease-controlled consumers work safely.
- [ ] Offline test identities and externally provisioned online identities are documented.
- [ ] Participant mapping correlates client instances with authoritative server players.
- [ ] Versioned test profiles and preflight validation are provided.
- [ ] Cross-loader fixture mods exercise custom screens, machines, commands, and networking.
- [ ] The reference runner provides recording, replay, visual comparison, and failure bundles.
- [ ] Full-world reset uses a documented safe lifecycle boundary.
- [ ] Reliability, performance, parallelism, and suite-duration targets pass.

### Contract and documentation

- [ ] OpenAPI is the canonical HTTP contract.
- [ ] TypeScript, Python, and Java SDKs are generated reproducibly.
- [ ] Corresponding Fabric and NeoForge instances work through the same SDK calls.
- [ ] Browser SSE uses authenticated fetch streaming.
- [ ] Job/event waits handle completion races and event-history gaps.
- [ ] Examples execute in CI.
- [ ] Input, timing, encoding, security, lifecycle, and mixin documentation is complete.
- [ ] Non-goals and known limitations are published.
- [ ] The future MCP can build on the public API without requiring internal game classes.

---

**The main architectural change is to keep the mod responsible for trustworthy observation and bounded execution, while moving planning, comparison, replay, and process orchestration outside Minecraft.** That makes the completion criteria achievable without weakening the API’s ability to support sophisticated AI-driven mod development.
