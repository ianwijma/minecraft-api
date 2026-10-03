# Timing and pause — clocks, scheduling, tick freeze, pause behavior

> Status: **draft** (execution-plan chunk 0.5). Normative once chunks 1.4,
> 1.5, 1.8, 2.3, 2.4, 3.2 land; final form due by chunk 8.5. Spec source:
> `docs/product-spec.md` §4, §5.

## Section contract (must contain before `status: final`)

1. Named clocks (`wall`, `client-tick`, `client-frame`, `server-tick`,
   `server-simulation-step`): definitions, availability and progress
   reporting, and the prohibition on world time as an action scheduler
   (spec §4.1).
2. Input scheduling: the hold-N-ticks boundary contract, non-collapsing
   presses, mouse-delta application boundaries and reporting (spec §4.2).
3. Wall-clock deadlines on every wait; failure modes they prevent (spec
   §4.3).
4. Pause semantics: the distinct states (singleplayer pause, tick freeze,
   empty-server pause, focus loss, rendering suspension, world loading,
   game-thread stall), `SERVER_PAUSED` / `CLOCK_NOT_ADVANCING` responses
   with reasons and recovery operations, `pausePolicy: "wait"`, and what
   stays available without simulation progress (spec §4.4).
5. Cleanup under stalled threads: API-level watchdog revocation vs
   game-thread cleanup, supervisor termination expectations (spec §4.5).
6. Tick-control timing: freeze limitations (players/ridden entities),
   `stepAndObserve` scoping, lease ownership and restoration rules, what
   tick control does and does not govern (spec §5).

## Current state

No tick, frame, or clock surfaces exist. The current runtime uses monotonic
elapsed-time deadlines for jobs and leases while preserving epoch-millisecond
timestamps in views and lease metadata. The serial job worker admits at most
128 pending jobs by default; additional submissions fail with `SERVER_BUSY`,
and terminal job views (including cancelled pending jobs) share the configured
completion-retention limit. Lease acquisition is atomic per topic; an expired
lease is revoked and its expiry listeners run before a replacement lease is
installed. Lease listeners must remain fast and must not reenter the manager
for the same topic during notification. These bounds do not add tick, frame,
or clock surfaces.
