# Contributing to minecraft-api (MAPI)

Thanks for helping. Read `AGENTS.md` first — it is the authoritative guide
for humans and coding agents alike; this file adds the contribution-specific
rules.

## Ground rules

- Minecraft target is fixed at **26.2**; changing it requires explicit owner
  approval.
- Versions are pinned in `gradle/libs.versions.toml` with verification notes
  in `docs/toolchain.md`. Never bump silently; propose and cite a source.
- Never commit: tokens (`MAPI_HTTP_TOKEN`, `http.token`), `eula.txt`, run
  directories, logs, worlds, or anything under `build/`/`.gradle/`.
- Do not choose the project license for the owner; see
  `LICENSE.pending.md`.

## Required checks before every PR

```bash
./gradlew verify
```

Formatting failures are fixed with `./gradlew formatApply` (review the
diff afterwards).

## What every change must include

1. Code change in the right module (see `AGENTS.md` §3).
2. Tests that cover it (`common/src/test/java`).
3. Documentation updates in the same change set (`docs/…`, `README.md`,
   `project.manifest.json` where relevant).
4. A clear description of what you ran and what you did not (mark NOT RUN
   items explicitly).

## Commit style

Short imperative subject, focused diffs, no generated files
(`build/`, `run*/`, `~/.m2` artifacts), no secrets.

## Review focus

- Public API changes: javadoc completeness, `docs/api.md` sync, version
  policy (experimental 0.x — see `docs/api.md`).
- HTTP changes: `docs/openapi.yaml` + `docs/http-api.md` + tests must match
  implementation exactly; security defaults never regress.
- Loader modules stay thin; shared logic goes in `common`.

## Browser dashboard maintenance

The frontend-only Next.js dashboard lives in [`dashboard/`](dashboard/); setup,
hosting and connection constraints are in [`docs/dashboard.md`](docs/dashboard.md).
The separate [design preview](docs/dashboard-preview/index.html) remains simulated.

Every HTTP API feature or contract change must update dashboard coverage in the
same change set. This includes reads, mutations, event payloads, parameters,
response schemas, execution modes, scopes, leases, and capability availability.
Regenerate the production catalog from `docs/openapi.yaml` with
`python scripts/generate-dashboard.py`, update forms
and specialized displays as needed, and check both client and server behavior.
CI rejects missing operation coverage or stale generated artifacts and runs
the dashboard typecheck and static export builds. Keep the simulated preview's
operation snapshot/catalog in sync for contract changes; it does not prove live
API coverage.

Keep the dashboard static and browser-only, compatible with GitHub Pages and
Vercel. No backend routes or hosted credential proxy. Never include bearer
tokens in examples, deployment configuration, screenshots, or exported profiles.
