# Mixin surface — injection targets and compatibility risks

> Status: **draft** (execution-plan chunk 0.5; registry starts at chunk 2.1).
> Spec source: `docs/product-spec.md` §15.3; policy owner: ADR-0005
> (preprocessing) and the §15.3 hook preference order.

## Policy

Preference order: public Minecraft APIs → loader lifecycle/input/render
events → narrow access bridges → targeted mixins. Mixins are last resort and
each one must be registered in the table below with all required fields. A
failed optional hook disables only its capability and reports why; a missing
required safety/input hook must prevent the affected capability from being
advertised.

## Registry

No mixins exist in the repository today. The current implementation uses
loader events and the `MapiPlatform` seam only (docs/architecture.md).

| Id | Target + injection point | Why no supported hook | Required/optional | Interaction with other mods | Failure behavior | Covered tests |
| --- | --- | --- | --- | --- | --- | --- |

(Empty by design. Rows are added only by the chunks that introduce the
mixin, in the same change set as the mixin itself.)
