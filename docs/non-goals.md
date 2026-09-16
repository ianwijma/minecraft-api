# Non-goals — explicit project boundaries

> Status: **draft** (execution-plan chunk 0.5; finalized at chunk 8.5).
> Spec source: `docs/product-spec.md` §2, §16, §17. These boundaries are
> permanent unless the owner revises `docs/product-spec.md`.

## Product non-goals (spec §2)

- Launcher implementation.
- Microsoft/Minecraft account authentication or account purchasing.
- Authentication bypass against online-mode servers.
- Anti-cheat evasion or undetectable automation.
- Protocol-level bot clients.
- Automatic compatibility with unsupported Minecraft versions.
- Universal semantic understanding of every custom-rendered interface.
- Operating-system desktop automation.
- Arbitrary remote Java evaluation, shell execution, or reflection.
- Hot-reloading arbitrary Java code, mixins, or registrations.
- Guaranteed deterministic behavior from arbitrary third-party mods.
- Transactional rollback of arbitrary game/mod side effects.
- A general-purpose distributed test scheduler.
- MCP transport, LLM planning, or source-code editing.

External launch/build integration used by the reference harness is allowed;
implementing a general launcher is not required.

## Scope classifications (spec §16)

| Capability | Classification |
| --- | --- |
| Protocol bots | Non-goal |
| Sound-event observation | Future |
| Particle-event observation | Future |
| Hazard-aware replanning across complex terrain | Extension/Future |
| Arbitrary mod-specific traversal | Extension |
| Arbitrary machine-state fixture adapters | Extension |
| Synthetic/fake players | Extension/Future |
| Live (in-place) world backup | Extension/Future unless a precise consistency scope is implemented and tested |

## Related honesty rules

- Freezing ticks is not freezing all game state (players/ridden entities are
  excluded by vanilla's documented behavior) — never claim otherwise.
- Absence from a bounded snapshot query does not prove an entity was
  destroyed.
- A client's claim about which server it joined is not proof of server
  identity.
- Sound/particle request-events are not equivalent to audible output or
  rendered particles (when those surfaces land).
- `destructive` flags and scope grants do not make arbitrary gameplay
  non-destructive.
- Zero failures in a finite acceptance run is a release gate, not proof of
  zero failure probability.
