# Architecture Decision Records

This directory holds MAPI's architecture decision records (ADRs). The
product specification (`../product-spec.md` §15.2) requires these ADRs to be
approved before agent-driven implementation of the expanded scope begins.

## Process

- One decision per file, numbered `NNNN-slug.md`.
- Statuses: `Proposed` → `Accepted` → `Superseded` (by a later ADR).
- Status changes and amendments are owner decisions (`AGENTS.md` §7);
  agents may draft but never self-approve.
- A code change that contradicts an Accepted ADR must either update the ADR
  first (owner approval) or not happen.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-supported-minecraft-versions-and-mapping-strategy.md) | Supported Minecraft versions and mapping strategy | Accepted |
| [0002](0002-repository-module-organization-and-java-toolchains.md) | Repository module organization and Java toolchains | Accepted |
| [0003](0003-loader-dependency-policy-and-ci-matrix.md) | Loader dependency policy and CI matrix | Accepted |
| [0004](0004-backport-and-end-of-support-policy.md) | Backport and end-of-support policy | Accepted |
| [0005](0005-preprocessing-introduction-criteria.md) | Criteria for introducing preprocessing | Accepted |
