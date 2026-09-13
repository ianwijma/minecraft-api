# 0005. Criteria for introducing preprocessing

Date: 2026-09-13
Status: Accepted (2026-09-13)

## Context

Spec §15.2 requires criteria for introducing preprocessing (build-time
source preprocessing such as preprocessor plugins) "later". Spec §15.3
already fixes the hook preference order: public Minecraft APIs → loader
lifecycle/input/render events → narrow access bridges → targeted mixins.
Today exactly one version (26.2) is supported and no version-conditional
compilation exists anywhere in the repository.

## Decision

Preprocessing may be introduced only when **all** of the following hold,
verified and documented:

1. **Version pressure:** at least three Minecraft versions are supported
   simultaneously (ADR-0001 revision), and the same functional area diverges
   across them.
2. **Duplication threshold:** per-version bridge modules for that area
   duplicate more than ~30% of their logic, or a single bridge path needs
   more than a handful of version conditionals — measured against the
   affected source, not asserted.
3. **No shared-code escape:** the divergence cannot be removed by better
   abstraction in shared, non-Minecraft code, nor by moving the divergence
   behind `MapiPlatform`-style seams or mixins per §15.3.

Additionally:

- The preference order is **separate bridge modules → targeted mixins →
  preprocessing**; preprocessing is the last resort, after ADR-0002's
  "explicit per-version bridges" baseline has demonstrably failed for the
  area.
- Introducing preprocessing (or swapping the chosen tool) requires an ADR
  revision with: the tool, its configuration scope, a build reproducibility
  note (preprocessed sources must not obscure IDE/debug output more than
  necessary), and a rollback statement.
- Preprocessing is never applied to the transport contract, public API
  signatures, or runner/SDK modules — only to Minecraft-version bridge and
  loader code.
- Mixins remain governed by spec §15.3 and `docs/mixin-surface.md`
  regardless of preprocessing.

## Consequences

- The build stays simple (no preprocessor plugin) for the foreseeable
  single/two-version lifetime.
- Pressure valve exists: when version proliferation makes bridge modules
  untenable, there is a pre-agreed, evidence-based path to adopt tooling
  rather than an ad-hoc emergency decision.

## Compliance

- `libs.versions.toml` contains no preprocessor plugin until this ADR is
  revised; any future introduction appears in a revised ADR plus
  `docs/toolchain.md` verification notes.
