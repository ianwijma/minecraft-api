# 0004. Backport and end-of-support policy

Date: 2026-09-13
Status: Proposed

## Context

Spec §15.2 requires a backport policy and an end-of-support (EOS) policy,
and §20 requires a version-maintenance ADR before "done". Currently exactly
one Minecraft version is supported (ADR-0001: 26.2), so backporting is
moot today; this ADR fixes the policy that applies the day that changes.

## Decision

1. **Supported set (once multi-version support exists):** the latest stable
   supported Minecraft version (N) plus the previous one (N−1). Supporting
   more than two requires an ADR revision.
2. **Backports.**
   - Security fixes: backported to N−1 while it is supported, and to any
     older version only by explicit owner decision.
   - Correctness/data-loss bugs: backported to N−1.
   - Features, behavior changes, new API surface: never backported.
   - Backports ship as patch releases of the affected MAPI minor line and
     must pass the same verify gate as main.
3. **End of support.**
   - A version leaves support when (a) the N−1 window moves past it, or
     (b) Mojang/loader ecosystems make maintenance impractical (verified and
     documented, e.g. no loader toolchain for the version).
   - EOS is announced in release notes and `docs/toolchain.md`/ADR-0001 at
     least one MAPI release cycle before the release that drops it.
   - Dropping a version requires an ADR-0001 revision; it is never done
     silently.
4. **Runtime floor follows the game:** the Java runtime requirement of a
   MAPI release equals the runtime shipped by the lowest supported Minecraft
   version's game (Java 25 for 26.2). Loader minimums follow the loader
   toolchain pinned for that version.
5. **MAPI's own API stability** is governed by `docs/api.md` (experimental
   before 1.0.0); this ADR covers Minecraft-version support only.

## Consequences

- Today: single-version support, no backport releases; all fixes land on
  main and release from it.
- At most two Minecraft lines are built/tested at once, keeping the CI
  matrix small (ADR-0003) until preprocessing criteria (ADR-0005) are met.
- Users get a predictable, announced migration path instead of surprise
  drops.

## Compliance

- ADR-0001 lists the currently supported set; release notes list any EOS
  announcements; patch branches (if any) are named `support/mc-<version>`.
