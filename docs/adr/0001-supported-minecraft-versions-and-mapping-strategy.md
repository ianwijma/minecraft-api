# 0001. Supported Minecraft versions and mapping strategy

Date: 2026-09-13
Status: Accepted (2026-09-13)

## Context

The product specification (`docs/product-spec.md` §15.2) requires an approved
ADR declaring the initial supported Minecraft version or versions and the
mapping strategy before Milestone 1, and forbids declaring an unspecified
version range supported. Supporting multiple Minecraft versions
simultaneously is a separate commitment from supporting both loaders.

Verified facts (docs/toolchain.md, 2026-09-13):

- The pinned target is Minecraft Java Edition **26.2**; the repository builds
  and verifies against it today.
- MC 26.x ships **unobfuscated** jars with **no mapping files**; both loaders
  run on official class names, so shared code needs no remapping.
- Mojang ships Java 25 for 26.2 (`javaVersion.majorVersion=25` in the 26.2
  manifest).

## Decision

1. **Initial supported version: Minecraft Java Edition 26.2, exactly one
   version.** No other version is supported, tested, or claimed compatible.
   Any expansion requires a revision of this ADR.
2. **Mapping strategy: none.** No `mappings` declaration, no obfuscation
   remapping, no intermediary names. All Minecraft references use official
   names directly. This is only valid while the supported version ships
   unobfuscated; if a future supported version is obfuscated, this ADR must
   be revised.
3. **Version-specific code confinement.** Minecraft classes may appear only
   in loader modules (`fabric/`, `neoforge/`) behind the `MapiPlatform` seam
   while a single version is supported. If a second version is approved, a
   dedicated per-version bridge module is introduced (per ADR-0002) before
   any second-version code is written; shared modules never compile against
   Minecraft.
4. **"Both loaders" is not "multiple versions".** Fabric and NeoForge
   artifacts both target 26.2 only. Loader parity and version range are
   independent commitments (spec §15.2).
5. Tick-control, input, and other capabilities that depend on version-specific
   internals are declared compatible only for versions where they are
   implemented and tested (spec §5); on 26.2 that determination is made per
   capability and recorded in the relevant docs.

## Consequences

- Every dependency choice (Loom 1.17.x, ModDevGradle 2.x, Fabric API
  0.160.0+26.2, NeoForge 26.2.0.87) stays keyed to 26.2; no compatibility
  shims for other versions.
- The repository stays free of mappings tooling and version-conditional
  build logic until this ADR is revised.
- Users on other Minecraft versions are explicitly unsupported; `mapi` should
  degrade gracefully (API disabled with a clear log reason) rather than crash
  when loaded on an unsupported version.

## Compliance

- `gradle/libs.versions.toml` pins `minecraft = "26.2"` and the loader docs
  metadata declares 26.2 only.
- `grep` for Minecraft imports outside `fabric/`/`neoforge/` returns nothing
  (enforced today by `common` compiling against the JDK only).
- No `mappings` blocks in Fabric build scripts; no reobf/remap tasks.
