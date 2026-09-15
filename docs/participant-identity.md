# Participant identity — account strategies and server/client mapping

> Status: **draft** (execution-plan chunk 0.5). Normative once chunks 1.11
> and 5.4 land; final form due by chunk 8.5. Spec source:
> `docs/product-spec.md` §7.

## Section contract (must contain before `status: final`)

1. Supported identity strategies:
   - Isolated offline test environment (harness-provisioned): requirements,
     constraints, mandatory warnings, and the rule that a launch UUID is
     never assumed authoritative (spec §7.1).
   - Online-mode environments: externally provisioned accounts only; no
     credential storage or switching in the automation API (spec §7.1).
2. Participant mapping schema: `participantId` → `instanceId`/`bootId` →
   `connectionSessionId` → authoritative joined player, plus dimension/
   entity, target address, and server-identity verification basis
   (address-based / observed through both APIs / optional handshake) (spec
   §7.2).
3. Runner responsibilities vs mod responsibilities for identity data.

## Current state

Not yet implemented. No identity or participant surfaces exist.
