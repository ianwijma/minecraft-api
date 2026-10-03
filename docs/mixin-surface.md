# Mixin surface — injection targets and compatibility risks

> Status: **active registry** (client connection safety hooks are required).
> Spec source: `docs/product-spec.md` §15.3; policy owner: ADR-0005
> (preprocessing) and the §15.3 hook preference order.

## Policy

Preference order: public Minecraft APIs → loader lifecycle/input/render
events → narrow access bridges → targeted mixins. Mixins are last resort and
each one must be registered in the table below with all required fields. A
missing required safety/input hook must prevent the affected capability from
being advertised; these connection hooks are registered as required so loader
startup fails rather than running with incomplete connection protection.

## Registry

The server bridge continues to use loader lifecycle events. Client connection
policy requires targeted hooks because vanilla resolves DNS and SRV redirects
on a worker thread before opening the socket. No loader event carries both the
API request identity and every resolver input plus the final numeric address.

| Id | Target + injection point | Why no supported hook | Required/optional | Interaction with other mods | Failure behavior | Covered tests |
| --- | --- | --- | --- | --- | --- | --- |
| connection-request | `ConnectScreen.connect` HEAD; capture API context and requested host/port | Loader events do not identify API-owned menu/direct joins | Required | Other mixins must preserve the target method; normal manual joins have no captured API context | Required injection (`require=1`); loader startup fails if absent | `ConnectionPolicyTest` checks scope, requested host, manual bypass, and context cleanup |
| connection-final-target | `ConnectScreen$1.run()` call to `ServerNameResolver.resolveAddress`; `ServerNameResolver.resolveAddress` calls to `ServerAddressResolver.resolve` (ordinals 0 and 1) | No public hook exposes every DNS/SRV input and final numeric socket address | Required | Redirects must preserve vanilla resolver calls; conflicting redirects cause required-mixin failure | Required injections (`require=1`); startup fails closed | `ConnectionPolicyTest` checks SRV target approval and independently pinned numeric IP:port |

Both loader configs are registered as client mixins and marked required. Each
loader bridge also force-loads the three transformed targets and checks their
unique marker methods before advertising client capabilities, so missing
hooks fail during client setup instead of on the first connection. The
allowlist uses exact names and ports; final numeric IP and port approval is
independent. A hostname entry does not authorize its DNS results. API origin
is retained only for server transfers from the same Minecraft session and
cleared at a new manual join; the original input lease is rechecked at each
boundary. Ordinary manual connections retain vanilla behavior.
