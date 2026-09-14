# Support manifest

MAPI supports **two explicitly named Minecraft releases at a time**, one per
active branch line. Everything below is verified per `docs/toolchain.md`
(verification date 2026-09-13); versions are pinned in
`gradle/libs.versions.toml`.

## Branch `main` (this repository)

| Component | Version | Notes |
| --- | --- | --- |
| Minecraft | **26.2** | target pinned in `libs.versions.toml`; do not change without owner approval |
| Java | **25** | required by Minecraft 26.2 (`javaVersion.majorVersion=25`) |
| Gradle | **9.7.1** | wrapper-pinned |
| Fabric Loader | **0.19.5** | |
| Fabric API | **0.160.0+26.2** | Fabric artifact only |
| NeoForge | **26.2.0.87** | version range `[26.2]` in `neoforge.mods.toml` |
| ModDevGradle | **2.0.147** | |
| Fabric Loom | **1.17.20** | |

## Version policy

- The HTTP API surface (`docs/http-api.md`, `docs/openapi.yaml`) is the
  contract; additive changes keep protocol version 1. Breaking changes land
  in the spec repo first (protocol version 2), then are forward-ported here
  (target-architecture policy, `docs/roadmap.md` §2.1 D1).
- Storage/transfer access is per-branch: new branches use the NeoForge
  transactional transfer API; older branches use `IItemHandler`. Fabric
  uses the Fabric Transfer API on all supported branches.
- Bug fixes are forward-ported; features are developed on `main` first.

## Runtime-reported support block

`GET /api/v1/info` includes a `support` object mirroring the runtime-relevant
rows above (Minecraft, Java feature version, platform, loader version) so
tools can verify the instance against this manifest without reading files.
