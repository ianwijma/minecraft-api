# Browser dashboard

The production dashboard is a static frontend in [`../dashboard/`](../dashboard/).
It calls the MAPI HTTP API directly from the browser and has no application
backend or hosted token proxy. The plain HTML design preview at
[`dashboard-preview/index.html`](dashboard-preview/index.html) remains
simulated and never contacts a game.

## Browser access setup

The MAPI listener remains disabled by default, binds to IPv4 loopback, and
requires its bearer token. For a hosted dashboard, enable the API and configure
the exact page origin in `http.allowedOrigins` (Fabric JSON or NeoForge TOML)
or in `MAPI_HTTP_ALLOWED_ORIGINS` (comma-separated; overrides the file).
For example, a Pages URL such as
`https://owner.github.io/minecraft-api/` has origin `https://owner.github.io`.
The origin includes scheme, host, and optional non-default port, but not the
repository path. Entries must be exact `http(s)` origins; wildcards, URL paths,
and arbitrary preview domains are rejected. Leave the list empty to deny
cross-origin browser access.

Generate a strong token and configure it on the Minecraft instance. Enter the
MAPI URL and token in the dashboard setup screen. The dashboard default is
`http://127.0.0.1:25586`; the Minecraft multiplayer port is unrelated. Keep the
token out of URLs, screenshots, pasted logs, build settings, and shared
devices. Remembered credentials are stored in browser localStorage, which is
not encrypted secret storage. The setup screen permits disabling persistence.

Local-network browser rules vary. In particular, an HTTPS dashboard calling
an HTTP loopback API may be blocked by the browser even when the origin is
allowlisted. A remote dashboard host does not make a remote Minecraft server's
loopback listener reachable. Use only browser and network configurations that
permit the request; do not bypass browser security. A local Chromium check
passed from a static `/preview` export to a live Fabric 26.2 instance, including
authenticated requests, 401 for an invalid token, the allowlisted CORS
preflight, all 48 operation cards, and dedicated-server capability gating.
This same-device check does not establish hosted-page or Firefox/Safari
compatibility; that browser matrix remains unverified.

The activity event stream can be stopped independently, and removing a
connection disposes its stream. Both paths abort the request and clean up its
response reader; expected body-cancellation errors are observed during cleanup
instead of surfacing as unhandled browser errors.

## Build and hosting

See [`../dashboard/README.md`](../dashboard/README.md) for local development,
GitHub Pages, and Vercel setup. Both use static export. The Pages workflow is
manual and publishing also requires selecting Actions as the Pages source in
repository settings. Vercel's project root is `dashboard`, with output in
`out/`; it needs no runtime environment variables.

The frontend dependency versions and verification notes are in
[`toolchain.md`](toolchain.md). CI checks the generated OpenAPI operation
catalog, TypeScript types, and root/representative-subpath static exports in
Chromium. The manual Pages workflow builds the repository-specific subpath.
Live MAPI behavior and hosted browser compatibility remain separate
acceptance work.
