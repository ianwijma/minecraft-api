# MAPI browser dashboard

Status: **implementation in progress**, 2026-10-04. The production static
dashboard and opt-in browser-origin support are being added incrementally.
This document records the delivered surface and remaining acceptance work;
availability of source code does not establish hosted browser compatibility.

## Goal and preview

The production app in `dashboard/` is a frontend-only Next.js App Router application using
TypeScript and Tailwind CSS. Export static files for GitHub Pages and Vercel;
all runtime requests go directly from the user's browser to MAPI. No API routes,
Server Actions, application backend, or hosted token proxy.

Open [`dashboard-preview/index.html`](dashboard-preview/index.html) in a browser.
The prototype uses simulated data, never contacts Minecraft, and never saves
credentials. It opens on a simulated connected workspace so the tabs/actions
are immediately visible; `?view=setup` opens initial setup. JavaScript is embedded
in the HTML so file-preview viewers need no sibling script request. `preview.js`
is the matching source snapshot; keep it and the embedded runtime synchronized.
Viewers must still allow JavaScript for interaction. It includes all **48 HTTP operations** in the current OpenAPI
contract, two-column field/history dialogs, removable connection tabs, a dedicated-server preview, search,
and a responsive layout. This covers the public HTTP surface; the in-process
Java API remains available to mods rather than to the browser.

The preview remains plain HTML for design review. Its `operations.json` is a
review snapshot; the generated production catalog is maintained from
`docs/openapi.yaml` by `scripts/generate-dashboard.py`.

## Prerequisite: browser connectivity

The mod binds IPv4 loopback and authenticates every endpoint. Browser access
uses the default exact origin `https://mapi.wij.ma`; additional origins can be
configured through `http.allowedOrigins` or `MAPI_HTTP_ALLOWED_ORIGINS`, and an
empty list disables browser access. The SDK generator removes the
browser-forbidden Host header and supports
authenticated streaming separately. Generated SDK output must not be edited by
hand.

First implementation milestone:

- Keep an exact-origin allowlist in MAPI configuration, defaulting to
  `https://mapi.wij.ma` while allowing operators to remove it. Retain loopback
  binding, Host validation, scopes, leases, and bearer authentication. Do not
  allow wildcard origins or arbitrary Vercel preview domains. The origin is
  scheme + host + port, without the Pages repository path.
- Handle allowlisted OPTIONS preflights before bearer authentication, with no
  access to game operations. Advertise only the necessary GET/POST methods and
  Authorization/Content-Type headers. Emit exact Access-Control-Allow-Origin and
  Vary: Origin on permitted success, error, and SSE responses. Expose the headers
  the UI reads, including Retry-After and X-MAPI-Protocol-Version. Reject foreign
  origins and test that denied preflights cannot authorize mutations.
- Test local-network permission and HTTPS-page-to-HTTP-loopback behavior in
  Chromium, Firefox, and Safari against actual hosted static pages. Some browsers
  treat loopback specially; support cannot be promised from CORS alone. Handle
  the browser's applicable local/private-network preflight mechanism within the
  same opt-in policy if required. Never recommend bypassing browser security.
- If hosted-to-loopback access is blocked in a target browser, support serving
  the same exported UI locally as the documented fallback. That is static file
  hosting, not an application backend, and still requires an allowed origin
  when served on a different port.

A remote Minecraft server's loopback listener is not reachable directly from
this browser. `127.0.0.1` means the machine running the browser, not the machine
hosting the dashboard. Baseline support is multiple local MAPI clients/servers
on distinct ports. Remote access would require separately configured networking
or a changed exposure model; do not imply that typing a remote IP enables it.
For a phone, loopback refers to the phone; responsive design does not make a
Minecraft instance on another computer directly reachable.

## Screen 1: connections

Persistent tab bar with named connections, status dots, and a final + button.
Each saved tab independently holds setup, connecting, connected, reconnecting,
or error state. Add, rename, duplicate, edit, and remove connections. Provide an
accessible remove button inside every tab’s visual boundary, sharing its active
background/border with the name (separate sibling buttons, never nested buttons),
plus Remove connection in settings
and on the setup screen for an existing disconnected profile. Every removal entry
point opens the same named confirmation dialog before changing anything. Explain
that removing the profile clears local credentials/presets/history but leaves
Minecraft running. Default keyboard focus to Cancel; Cancel or Escape preserves
the profile, selected tab and history. Only the explicit Remove connection button
commits deletion. Capture the target connection by stable ID so selection/index
changes cannot remove the wrong profile; cancelling from settings keeps settings
open. Removing a profile
clears its stored token, local drafts/history/presets, requests and subscriptions;
it never shuts down Minecraft. Select a neighboring tab after removal; removing
the last connection returns to the initial setup screen.

The + button opens a single connection popup containing the same form and setup
explanation as the initial screen. It creates **no pending tab**. Closing or
pressing Escape discards the unsaved draft and leaves existing tabs untouched.
Test connection does not add a tab. Connect validates and tests the connection;
only successful connection creates and selects a saved tab. Failure stays in the
popup for correction. Disable duplicate submission while connecting. Editing an
existing profile uses the same popup and preserves its identity/history; a
failed edit retains the last saved profile. Switching tabs preserves drafts,
responses, saved presets, and per-operation history.

Setup fields: friendly name, MAPI URL (default `http://127.0.0.1:25586`), masked
bearer token, and remember-on-this-device checkbox. Explain that this is the
MAPI HTTP port, not the Minecraft multiplayer port. Test connection verifies
health/authentication and protocol compatibility; Connect also reads instance,
client, world, and operation metadata. Show actionable failure messages, while
acknowledging that browser fetch errors can hide the exact CORS/network cause.
Never mark a saved profile as currently connected without a fresh check.

Save versioned profiles and the selected tab in localStorage after hydration.
Remember profiles by default; explicitly disclose and permit opting out of
saving tokens. Remembered tokens allow reconnect after reload on a trusted
personal device; otherwise use session memory and ask for the token again.
Handle malformed storage, schema migrations, and blocked/full storage gracefully.
No token in URLs, analytics, logs, exports, screenshots, or copied requests.
Browser storage is not encrypted secret storage; include Clear saved credentials.
Restore connectivity without re-executing actions or reacquiring control leases.

## Screen 2: action workspace

Warm off-white background, white cards, forest-green primary actions, restrained
amber accents for administrative/destructive operations, and a dark JSON viewer.
System fonts and small icons keep the static app self-contained.

- Header: instance name, address, loader/Minecraft/API versions, connection and
  world status, capability badges, edit/disconnect controls.
- Main area: compact action groups, search by name/path/operation ID, available
  filter, and optional favorites. All groups share one page; large screens show
  a dense grid, smaller screens scroll vertically. Do not promise 48 controls
  plus results in a single mobile viewport.
- Capability state: dedicated server, client at menu, client connected to a
  remote server, or client with integrated world. Client and server features
  can coexist. Detect from `/client`, `/server/world`, `/server/status`, and
  runtime capabilities; expected unavailable responses are not failed auth.
  Show unsupported controls disabled with an accessible reason. World queries
  and tick controls may be unavailable on a client joined to a remote server.
- Action dialog: **fields on the left, saved/recent calls on the right** for every
  operation, including parameter-free reads. Use an explicitly light background
  with dark text and high-contrast inputs/buttons in both OS color schemes.
  Preserve readable labels, keyboard focus, and disabled-state explanations.
  Open at its full viewport-constrained height (90dvh with safe outer margins)
  from the first action, independent of run count. Keep the popup itself from
  scrolling; its heading/close control stays fixed. The fields pane can scroll
  independently when a complex form exceeds its space. The saved/recent heading
  and clear controls stay fixed while only the items list scrolls. On mobile,
  stack the two bounded panes with independent scrolling within the same fixed
  popup. New runs, loading inputs and rerunning must not resize/reposition the
  popup or reset an already scrolled history list; preserve its visible item
  anchor when records are inserted.
- Field editor: schema-derived required/optional labels, type-appropriate inputs,
  enums, units, bounds, nested object sections and repeatable array rows. Optional
  values have an inclusion toggle; disabled values are omitted rather than sent
  empty. For open objects, provide Add field with name, value type, and matching
  value controls, plus remove field; allow nested objects/arrays. Enforce unique
  names and schema additionalProperties rules. No editable raw JSON is required;
  an optional read-only generated request preview can aid debugging.
- Request metadata: HTTP method/path, supported execution modes, scopes and
  destructive intent. Automatically managed lease IDs never appear as editable
  fields. Every normal action uses its current connection's lease manager.
- Saved/recent calls: scope by connection ID + operation ID. Record timestamp,
  input values, execution state, status/duration and complete redacted request and
  response/error snapshots. Each executed-call card includes distinct Request
  and Response sections with method/path, parameters/body, response status,
  duration, and response body; both are visible without leaving the action popup.
  Use independently collapsible sections and bounded scrolling for large JSON,
  with image previews for screenshot responses. Keep the popup open after a run
  and immediately add that run’s request/response to the list. Saved
  calls/presets always sort first, then recent calls newest first. Load fields
  repopulates the editable form; Rerun executes those values through the same
  validation, capability, world-session and lease checks as a new run. Save a
  preset before execution or save/unsave a previous run. Clear recent preserves
  saved calls; a separate Clear saved removes favorites. Cap unsaved history.
  Saving an executed call retains its request and response in the session; an
  unexecuted preset shows “Not run yet” rather than inventing a response. Reruns
  create their own request/response entry and never overwrite a prior outcome.
  Changing fields or rerunning never silently updates an existing saved preset.
  Never save bearer tokens, transient lease IDs or stale world/container/screen
  identifiers for reuse. Saved game-operation inputs must be checked against
  the current session; destructive intent must be reconfirmed on rerun rather
  than treating a saved confirm flag as fresh approval.
- Results/activity: status, duration, redacted request, structured errors, JSON,
  screenshot display/download, bounded logs, job milestones and final outcome,
  snapshot comparison, and event feed. Bound the inspector to the remaining
  viewport height on desktop; the Activity & events heading stays fixed and only
  its entries scroll. Large latest-response payloads have their own bounded
  scroll region. Appending events must not increase the inspector/page height or
  force a scrolled list back to the top; optionally provide a jump-to-latest
  affordance. Recompute available height on viewport/header changes. Mobile uses
  a bounded viewport-sized inspector below the action grid; the page can still
  scroll for the grid, but incoming activity never adds page height. Mobile puts this below the action grid
  with a shortcut to jump to the latest result. Dialogs become full-width sheets.

Proposed action groups (current operation counts):

| Group | Count |
| --- | ---: |
| Instance & diagnostics | 6 |
| Tick control | 9 |
| World queries | 5 |
| Snapshots & commands | 3 |
| Input & movement | 3 |
| Screen & capture | 3 |
| Inventory | 4 |
| Window settings | 4 |
| Worlds & multiplayer | 7 |
| Events, jobs & process | 4 |
| **Total** | **48** |

## API coverage and transport

Generate a typed operation/form catalog from `docs/openapi.yaml`, resolving
references, parameters, request schemas, responses, and `x-mapi-*` metadata.
Use small handwritten overrides for better labels and specialized forms; all
operations must retain a usable generic form. Runtime `/operations` enriches
POST authorization metadata but is not a complete read-endpoint catalog.
Future unknown fields remain visible in raw responses. Unsupported schema
features should fail coverage checks or get a structured control extension rather
than silently disappear. Open-object support uses field rows, not a JSON textarea.

Improve the existing SDK generator for browser-safe requests: remove the forced
Host header from fetch; preserve normal Node use and test both environments.
Keep REST timeouts, but treat authenticated streaming separately with cancellation
and reconnect/backoff. Use fetch-based SSE because native EventSource cannot
supply the bearer header. Preserve sequence cursors and surface event gaps.
Expose response headers/structured failures for the inspector and rate limiter.
The current SDK returns largely `unknown` responses and generic record bodies;
use generated schema types plus boundary validation rather than assuming fully
typed DTOs already exist.

One request scheduler per endpoint within this app coalesces equivalent reads
across duplicate connection tabs, respects the configured request budget and
Retry-After, and reduces background-tab traffic. The default 60 requests/minute
makes frequent independent polling unsuitable. Offer manual refresh and modest
optional polling; prefer one SSE stream per active endpoint when enabled, with
bounded in-memory event/history buffers. Cancel local requests and streams on
disconnect; cancelling a fetch does not undo a dispatched mutation.

Manage input and tick-control leases **automatically in the frontend**, separately
per connection/token and lease topic. Before a lease-required operation, reuse a
valid lease owned by this dashboard or acquire the appropriate lease through the
existing API. Inject the ID only at dispatch; never ask the user to paste it.
Display a small acquiring/ready/conflict status, not a lease-ID input. Serialize
acquisition/renewal so concurrent button presses do not compete for the same
lease. Duplicate profiles pointing at the same endpoint with the same token
should share a manager within the app; another browser tab remains an independent
client unless explicit coordination is added.

Renew input leases while an active control action needs them, through the
existing input renewal endpoint. Tick leases have different semantics: there is
no invented renew/release route. Reuse a valid tick lease and acquire after expiry
when no holder remains. Lost/expired leases or LEASE_HELD errors show actionable
status; never steal another holder's lease and never automatically replay a
mutation whose outcome is ambiguous. Restore profiles after reload, but not
lease ownership; the existing holder may need to expire before acquisition.

Keep explicit Acquire input/tick lease actions for full API coverage and advanced
inspection, but those also update the manager without ID entry. Removing a
connection stops local renewals and streams; it cannot revoke a mod-side lease
without a supported endpoint. Do not invent lease-release, job-cancel,
disconnect-game, or release-all-input endpoints. Stop tick work remains available;
stopping a local wait is distinct from stopping the game operation. Invalidate
cached world state and world-scoped lease/job references when the session changes.

A 202 response is admission, not successful completion. Follow job-backed actions
through `/jobs/{id}`; follow world-load/create transitions through world phase.
Render dispatch versus verified effect faithfully, including inventory's
`effectVerified: false`. Never automatically retry mutation POSTs, including
commands and world creation, after an ambiguous timeout.

## Stack and repository layout

The planned stack is Next.js, React, TypeScript, and Tailwind CSS; shadcn/ui with
Radix primitives for accessible tabs/dialogs/tooltips; Lucide icons; Zod for
validation and React Hook Form for parameter forms. Start with a small shared
store/context rather than an extra state library. Use native fetch and storage.
Dashboard dependency versions and package scripts are recorded in
`docs/toolchain.md` after verification against the checked-in lockfile and
official sources.

```text
dashboard/
  src/app/                 static layout + client workspace
  src/components/          connections, cards, dialogs, inspector
  src/lib/                 transport, storage, scheduler, capability rules
  src/generated/           OpenAPI-derived catalog and types
  tests/                   meaningful unit and browser scenarios
  next.config.ts           output: 'export'; configurable basePath
  package.json + lockfile
scripts/                   catalog generator/check
.github/workflows/         dashboard checks + optional Pages deployment
```

Use a single route with connection IDs held locally, no runtime dynamic routes.
Next's build can prerender the shell; all live MAPI access happens client-side.
GitHub project Pages needs a build-time repository basePath, trailing-slash-safe
assets/links, and the exported `out/` directory. Account-root/custom-domain Pages
uses no repository prefix. Vercel also serves the static export with no runtime
functions. Configure root directory/build output explicitly for this subfolder.
No token or instance credential in build-time environment variables.

## Implementation sequence and acceptance

The production application, exact-origin opt-in policy, generated catalog,
static-export configuration, CI gates, and manual Pages workflow are now in the
repository. `./gradlew verify --no-daemon` and the Fabric dedicated-server
HTTP smoke passed. A static `/preview` export served on local port 4180
connected in Chromium to a live Fabric 26.2 server on port 60876: authenticated
API requests succeeded, unauthenticated health returned 401, preflight returned
204 with the exact origin/method/header policy and conditional private-network
permission, all 48 operations appeared, and 22 client-only actions were gated
for the dedicated server. Dashboard typecheck and 18 unit tests pass; the
48-operation catalog check, SDK generation check, and `git diff --check` pass.
The fixture-backed Chromium suite passed for both root and `/preview` static
exports. It verified transactional connection creation/cancellation, token
persistence choices, confirmation and removal, reload, all 48 operation cards,
structured fields and optional omission, lease acquisition/reuse, saved and
recent calls, bounded history and mobile layout, SSE start/stop, and error
flows. Firefox/Safari, NeoForge/client live smoke, hosted deployment, and the
production-origin browser matrix remain pending.

1. **Browser access proof:** opt-in mod policy, SDK generator changes, and
   focused contract tests are complete; the local Chromium-to-Fabric proof
   passed. Real hosted-origin checks across the browser matrix remain pending.
2. **Static shell and connections — implemented and fixture-tested:** Next.js static export, responsive UI,
   per-tab state, storage migration, probe/reconnect/errors. Verify keyboard and
   mobile behavior, reload persistence, token opt-out, connection isolation,
   removal of active/inactive/last profiles through confirmation, Cancel/Escape
   preservation from every removal entry point, popup cancellation, failed connect,
   and repeated + / Connect presses without extra tabs.
3. **Full operation coverage — implemented and fixture-tested:** generated catalog/forms, capability gating,
   automatic leases/modes/intent, safe execution, response inspector, and two-column
   operation history. Verify automatic acquisition/reuse/expiry/conflict handling,
   hidden lease fields, structured nested/open objects, optional omission,
   saved-first ordering, load/rerun, history isolation, separate clear actions,
   fixed popup bounds under growing history, and independent list/form scrolling.
   Verify all
   48 operation IDs and future additive schema handling; representative complex
   forms must produce correct wire requests.
4. **Observation tools — implemented and fixture-tested:** authenticated SSE,
   logs, bounded history, screenshots, jobs/world transitions, snapshots/diffs,
   copy-redacted requests, favorites.
   Verify stream cancellation/gaps, 429 backoff, stale sessions, ambiguous POST
   failures, and admission versus completion.
5. **Deployment and maintenance — configured:** Pages workflow, Vercel instructions, pinned
   lockfile, generated-catalog CI gate, contribution docs and manifest entries.
   Build/run the exported site at root and a repository subpath. Run frontend
   checks, browser scenarios against fixtures and real Fabric/NeoForge instances,
   and the repository's `./gradlew verify`.

Each milestone should be a reviewable change; retain existing execution-plan
human gates. Done means production browser connectivity is demonstrated, every
HTTP operation has a working action/form, both loaders and applicable client/
server states are covered, profiles restore correctly, and both static hosting
configurations build and serve correctly. Deployment itself is a separate step.

## Useful additions

Include searchable favorites, last-used parameter presets, redacted copy-as-curl,
JSON download, protocol mismatch warnings, request timings, clear history,
capability explanations, screenshot previews, and a clearly labeled demo mode
for design and onboarding. Profiles can be exported/imported **without tokens**.
Keep recent run bodies/results in bounded session memory by default. Explicitly
saved calls/presets persist locally so they survive reload; show that disclosure
when saving. Results stay session-only unless separately exported. Commands and
world data can contain sensitive content, so offer clear per-action/all history
and clear saved presets. Tokens and lease IDs are excluded from persistence.

Defer cross-instance macro execution, visual screenshot diffing, automated test
suites, and distributed orchestration until basic full-API operation is solid.

## Design revision — user feedback

The simulated preview demonstrates connection deletion, transactional +
popup creation, automatic lease attachment, a light action dialog, recursive
field controls, saved presets, per-action recent runs with request/response sections, load/rerun and separate
clearing of recent/saved entries. Preview history is intentionally memory-only;
production persistence follows the rules above.

## Validation and remaining acceptance

Chromium design checks passed: popup cancellation/test/submit, connection removal
including the last profile, light dialogs with a dark OS theme, all 48 actions,
automatic lease attachment, saved-first ordering, load/rerun/clear and history
isolation, optional omission, dynamic fields, nested arrays, dedicated-server
gating and mobile popup widths. Follow-up checks passed for the remove button inside
the highlighted tab, per-run request/response snapshots, popup retention,
save/clear/rerun snapshot preservation, unexecuted preset labels, and mobile
transcript widths. Fixed-height checks passed after 12 runs and a rerun:
the popup retained its bounds, the history list scrolled independently, the
visible history anchor stayed in place, and mobile form/list panes remained
independently bounded. Removal-confirmation checks passed for every entry point,
Cancel/Escape preservation and correct-target deletion. Twenty additional events
did not increase inspector/page height; desktop/mobile activity scrolling and
scrolled-position preservation passed. For the current implementation, JDK 25
`./gradlew verify --no-daemon` passes, including formatting, unit and HTTP
contract tests, both loader builds, distribution checks, and manifest
validation. The focused config, HTTP, and SSE contract tests also passed. A
supervised Fabric dedicated-server smoke reached the running server, confirmed
401 without a bearer token, and passed authenticated health, info, and
server-status JSON probes. A production static `/preview` export also connected
from local Chromium to a live Fabric 26.2 instance: authenticated health, info,
operations, status, world, client, and tick requests succeeded; an invalid
token returned 401; the allowlisted preflight returned 204 with the configured
CORS headers and conditional private-network permission; all 48 operation
cards rendered and the 22 client-only actions were disabled in dedicated mode.
Dashboard TypeScript checking, all 18 unit tests, the 48-operation generated
catalog check, the SDK generation check, and `git diff --check` passed. Root and
representative-subpath static exports build, and fixture-backed Chromium
acceptance passed for both. Live Firefox/Safari, NeoForge/client smoke, hosted
deployment, and hosted-origin browser matrix remain NOT RUN. These results do
not establish cross-browser hosted access.
