# Data encoding — typed NBT, codec JSON, item components

> Status: **draft** (execution-plan chunk 0.5). Normative once chunk 1.9
> lands; final form due by chunk 8.5. Spec source:
> `docs/product-spec.md` §11.

## Section contract (must contain before `status: final`)

1. The three representations and when each applies: normalized API DTOs,
   codec JSON (diagnostic/import-export), typed NBT (type-preserving), plus
   optional SNBT (spec §11.1) — and the codec↔NBT interchange caveats that
   justify keeping them separate.
2. The canonical typed representation table (byte/short/int, long as decimal
   string, float/double round-trippable, tagged non-finite values, tagged
   base64 byte arrays, typed int/long arrays, explicit list types, compound
   maps, strings) with the requirements list: strict JSON, negative zero,
   hashing/normalization order, binary byte order, pre-game-thread size/depth
   rejection, explicit unsupported-field reporting, no Java stringification
   fallback (spec §11.2).
3. Item components: registry id, count, effective component view, patch
   semantics with explicit removals, namespaced ids, per-component
   serialization status, registry context; the absent vs inherited vs removed
   distinction (spec §11.3).
4. Version metadata block: API encoding version, Minecraft version, data
   version, registry fingerprint, adapter schema version; cross-version
   import support/rejection policy (spec §11.4).

## Current state

Not yet implemented. The only JSON surface today is the hand-written
`JsonWriter` for the read-only status endpoints.
