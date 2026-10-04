# MAPI browser dashboard

This is a static, browser-only dashboard for the optional local MAPI HTTP API.
The exported files contain no backend, hosted token proxy, or server-side
secrets. Requests go from the browser to the MAPI listener on the same device.

## Local development

Use Node.js 22 and npm:

```sh
npm ci
npm run dev
npm run typecheck
npm run test
npm run build
```

`npm run test:browser` installs no browser by itself; install Chromium with
`npx playwright install chromium` first. It builds root and subpath exports and
checks their browser connection flow against a local fixture API.

The production build writes the static export to `out/`. To build for a
repository subpath, set the base path at build time:

```sh
NEXT_PUBLIC_BASE_PATH=/minecraft-api npm run build
```

The root build has an empty base path. No token, API URL, or game credentials
belong in build-time environment variables.

## GitHub Pages

The `dashboard-pages` workflow is manually triggered from GitHub Actions and
builds the project path `/minecraft-api`. In repository Settings → Pages, select
GitHub Actions as the build and deployment source. A maintainer must trigger
the workflow to publish. A custom domain or account-root Pages site needs a
build with an empty base path instead.

## Vercel

Create a Vercel project with repository root directory `dashboard`. Use the
checked-in npm lockfile and `npm run build`; the Next.js static export is in
`out/`. The Next configuration defaults to the site root. No runtime functions
or environment secrets are required. For a deployment mounted under a path,
set `NEXT_PUBLIC_BASE_PATH` to that path at build time. CI exercises `/preview`
as a representative subpath; the manual Pages workflow builds `/minecraft-api`.

## Connecting to MAPI

The dashboard defaults to `http://127.0.0.1:25586`, the MAPI HTTP port. It
means the computer running the browser, even when the dashboard itself is
hosted remotely. A remote Minecraft server is not reachable by entering its
address: MAPI binds loopback. Serving this static dashboard does not create a
relay or change that network boundary.

Browser access is disabled unless the operator allows the exact origin in the
MAPI instance config (`http.allowedOrigins`) or sets
`MAPI_HTTP_ALLOWED_ORIGINS`. Origins contain scheme, host, and optional port;
they exclude paths. For example, a project Pages site at
`https://owner.github.io/minecraft-api/` uses origin `https://owner.github.io`.
List each exact origin separately; wildcard and preview-domain patterns are
not supported. See [`../docs/http-api.md`](../docs/http-api.md) for config
examples and [`../docs/security.md`](../docs/security.md) for the trust model.

Each request still uses the configured bearer token and normal MAPI
authorization. The dashboard stores remembered tokens in browser localStorage
only when the user opts in. LocalStorage is not encrypted secret storage; use
remembered credentials only on a trusted personal device, and clear them when
finished. The token is never part of the dashboard URL or static build.

An HTTPS-hosted dashboard calling the local HTTP listener may encounter
browser-specific local/private network restrictions. CORS allowlisting does
not guarantee that every browser permits this connection. No hosted
Chromium/Firefox/Safari compatibility matrix is claimed until it has been run.
