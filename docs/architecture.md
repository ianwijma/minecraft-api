# Architecture

## Modules

```
minecraft-api/
├── common/            Shared, loader-neutral code (public API + internals)
├── fabric/            Fabric entrypoint + platform adapter + metadata
├── neoforge/          NeoForge entrypoint + platform adapter + mods.toml
├── example-consumer/  Consumer example (public API only; never shipped)
└── docs/, scripts/, .github/
```

- **common** compiles against the JDK only (plus compileOnly slf4j-api, which
  the game provides). It contains:
  - `dev.example.mapi.api` — the **public Java API** (the only package other
    mods should compile against).
  - `dev.example.mapi.internal` — runtime state, config parsing, JSON
    writer, rate limiter, and the loopback HTTP server.
- **fabric** and **neoforge** implement one seam each:
  `internal.MapiPlatform` (type, versions, directories, logger, lifecycle
  registration) plus `ServerHandle`/`RawServerInfo` for server-thread access.
  Entrypoint classes are deliberately thin.
- **example-consumer** depends on the public API only. With
  `-PmapiConsumerUseMavenLocal=true` it consumes the artifact published by
  `./gradlew publishLocal` from `~/.m2`, validating the real publishing path.

## Packaging

Each distributable jar (fabric/neoforge) **merges the common module's classes
and resources exactly once** (`tasks.jar { from(project(':common').sourceSets.main.output) }`).
No remapping is needed on MC 26.x because the game ships unobfuscated and
both loaders run on official names. `verifyDistributions` (root Gradle task)
asserts this, checks loader metadata, and forbids bundling slf4j, loader
classes, or example code.

## Data flow

```
process init (mod construction)
        │
        ▼
MapiRuntime ── starts when enabled ──► HttpApiServer  (process lifetime)
        │                                    │  workers (bounded, queue 32)
        ├─► MapiServicesImpl (registry)      ├─► GET reads  ─► trySnapshot/tryReadOnServerThread
        ├─► TaskManager (202 protocol)  ─────┤
        ├─► EventLog (seq, ring buffer)      ├─► /events poll + WS on its own loopback port
        └─► DiscoveryFile (heartbeat)        └─► owning-thread execution only
             ▲
loader lifecycle event (Fabric/NeoForge)
        │  (loader module adapter)
        ▼
ServerLifecycleListener.onServerStarting(ServerHandle)
        │  session identity flips: worldSessionId, readiness=worldReady
        ▼
server thread reads RawServerInfo/RawPlayerSnapshot/RawBlockRead/RawWorldTime
        └─► immutable DTOs cross back; serialization stays off-thread
```

Rules baked into this flow:

- The tick thread is never blocked by HTTP work; every read uses a bounded
  500 ms wait (`MapiRuntime.SNAPSHOT_WAIT_MS`) and immutable DTOs.
- The HTTP worker never touches live game objects directly.
- HTTP starts at process init (when enabled) and survives repeated world
  sessions; the WebSocket event stream runs on its own loopback port
  because the JDK HTTP stack cannot host protocol upgrades.
- Tasks run on a small daemon pool and schedule game work onto the owning
  thread; world-session end fails running tasks with `LIFECYCLE_CHANGED`.
- Events get per-process-session monotonic sequence numbers from EventLog;
  the same log serves polling (`?after=`) and WebSocket replay.

## Session model

The runtime stamps every payload with identity so external tools can detect
restarts and world changes:

- `processSessionId` — random UUID per `MapiRuntime` (per launch).
- `worldSessionId` — random UUID per server session (dedicated or
  integrated); absent while no world session is active. Repeated integrated
  sessions each get a fresh id.
- `physicalSide` — `client` or `dedicatedServer`, detected via the loader
  (`EnvType` on Fabric, `Dist` on NeoForge) through the `MapiPlatform` seam.
- `availableLogicalSides` — logical sides currently serviceable
  (`server` while a server session runs).
- `readiness` — `http` or `worldReady` (`clientJoined` reserved).

The optional discovery file (`<gameDir>/mcapi/discovery.json`, written by
`internal.discovery.DiscoveryFile`) exposes this identity plus the endpoint
to local tools; it never contains secrets.

## Client-only code

Client-only classes live **only** under `dev.example.mapi.client.*` and are
referenced exclusively from client-side code:

- **Fabric**: the `client` source set (`loom.splitEnvironmentSourceSets()`),
  entered via the `client` entrypoint in `fabric.mod.json`
  (`dev.example.mapi.client.fabric.MapiFabricClient`).
- **NeoForge**: main-source-set classes annotated `@OnlyIn(Dist.CLIENT)`
  behind a dist guard in the mod constructor
  (`dev.example.mapi.client.neoforge.NeoForgeClientOps`).

Both register a `MapiClientOps` implementation plus a client-thread
scheduler via `MapiBootstrap.registerClientOps`; the HTTP server reaches
client state only through that seam with bounded waits. The packaging
tripwire (`verifyDistributions`) forbids `net/minecraft/client` references
from every class *outside* `dev/example/mapi/client/`; the launch test on a
dedicated server remains the authoritative check.

## Extension points for growth

- New loader: add a module implementing `MapiPlatform`, keep `common` intact.
- New endpoint: `HttpApiServer.route()` + tests + `docs/openapi.yaml`.
- New public API: `api` interfaces + `MapiRuntime` + `docs/api.md`.