# Public Java API (`dev.example.mapi.api`)

Status: **experimental** (MAPI 0.x). See "Versioning" below.

## Types

| Type | Purpose |
| --- | --- |
| `MapiApi` | Static facade: `get()` → `Optional<Mapi>`, `require()` (throws before bootstrap) |
| `Mapi` | `modVersion()`, `apiVersion()`, `minecraftVersion()`, `platform()`, `platformVersion()`, `serverStatus()`, `services()` |
| `PlatformType` | `FABRIC` / `NEOFORGE`, each with a stable `id()` (`"fabric"`, `"neoforge"`) |
| `ServerStatusSnapshot` | Immutable record: `capturedAtEpochMs`, `startedAtEpochMs`, `playerCount`, `maxPlayers`, `tickCount`, `averageTickTimeMs`, `motd`; plus `uptimeMs()` |
| `MapiServices` | Thread-safe registry: `register(id, service)`, `get(id)`, `all()` |
| `MapiService` | Extension: `id()` plus `onServerStart()`/`onServerStop()` callbacks |

## Initialization and registration timing

MAPI is bootstrapped by the loader entrypoint:

- **Fabric**: during `ModInitializer.onInitialize()`.
- **NeoForge**: during `@Mod` class construction.

After bootstrap:

- `MapiApi.get()` returns the instance; `MapiApi.require()` throws
  `IllegalStateException` if called earlier (this is deliberate — do not
  cache the instance before bootstrap).
- **Service registration** may happen at any time after bootstrap, from any
  thread (`ConcurrentHashMap`-backed). Typical timing: your mod's
  construction or `onInitialize`. Duplicate ids throw
  `IllegalStateException`; ids must match `[a-z][a-z0-9_-]{1,63}`.
- Service lifecycle callbacks fire on the **server thread** when a server
  starts/stops; keep them fast and non-blocking. Callback exceptions are
  logged and never propagate to the game.

## Thread affinity and asynchronous behavior

- All `Mapi` methods are safe from any thread.
- `serverStatus()` schedules a snapshot onto the Minecraft server thread and
  waits at most 500 ms. It **never blocks the tick thread** and never blocks
  the caller longer than the bound. Outcomes:
  - no server running → `Optional.empty()`
  - server thread busy past the bound → `Optional.empty()` (debug-logged)
- `ServerStatusSnapshot` is immutable; store and share freely.

## Nullability, immutability, errors

- Absent values use `java.util.Optional`; public methods never return
  `null`.
- Snapshot fields are clamped non-negative; a `null` motd normalizes to
  `""`.
- Runtime failures (e.g. snapshot timeout) degrade to `Optional.empty()`;
  nothing throws during normal operation. `require()` is the single
  documented throwing method (pre-bootstrap misuse).

## Public vs internal

- `dev.example.mapi.api` — public; documented; compiled into every
  distributable jar and published as `minecraft-api-common`.
- `dev.example.mapi.internal` and everything under loader modules — internal;
  may change without notice; do not compile against it.

## Versioning and compatibility policy

- `modVersion` and `apiVersion` are both `0.1.0` today. They may diverge
  after 1.0.0 (mod release vs API surface).
- Until 1.0.0 the API is experimental: minor versions may add functionality
  **and** make source-incompatible changes. Breaking changes require a minor
  bump and an entry in this file.
- Deprecation (post-1.0.0): `@Deprecated` + javadoc explaining the
  replacement; removal only in a major version.
- **No cross-Minecraft binary compatibility is promised.** The API is
  loader-neutral, but Minecraft-adjacent behavior (e.g. tick metrics) can
  change with game updates; consumers must re-verify per Minecraft version.
- HTTP protocol version (1) is independent of both (see
  `docs/http-api.md#versioning`).

## Consumer quickstart

```kotlin
repositories { mavenLocal() }
dependencies { implementation("dev.example.mapi:minecraft-api-common:0.1.0") }
```

```java
// after MAPI bootstrap (your mod constructor / onInitialize):
Mapi mapi = MapiApi.require();
mapi.services().register("your-service", yourService);
Optional<ServerStatusSnapshot> status = MapiApi.get().flatMap(Mapi::serverStatus);
```

See `example-consumer/` for a compiling example and
`scripts/verify-published-api.sh` to validate the mavenLocal path.