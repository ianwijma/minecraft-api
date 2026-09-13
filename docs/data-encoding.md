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

## Current state (encoding v1 — implemented in `internal/encoding`)

The typed NBT wire representation is implemented loader-neutrally
(`Tag` value tree + `TagJson` writer + `TagHash` canonical hashing); the
Minecraft bridge feeds typed values into it from chunk 2.7 onward.

### Wire table (encoding v1)

| Type | JSON representation |
| --- | --- |
| byte / short / int | `{"type":"byte"\|"short"\|"int","value":<bounded JSON integer>}` — range-checked at construction |
| long | `{"type":"long","value":"<decimal string>"}` |
| float / double (finite) | `{"type":"float"\|"double","value":<round-trippable JSON number>}` (`Float.toString` / `Double.toString` forms; negative zero preserved as `-0.0`) |
| float / double (non-finite) | `{"type":"float"\|"double","value":"NaN"\|"Infinity"\|"-Infinity"}` |
| byte array | `{"type":"byte[]","value":"<base64>"}` |
| int array | `{"type":"int[]","value":[<bounded JSON integers>]}` |
| long array | `{"type":"long[]","value":["<decimal strings>"]}` |
| list | `{"type":"list","elementType":"<type>","value":[typed elements]}` |
| compound | `{"type":"compound","value":{name: typed value}}` |
| string | bare JSON string |

### Rules

- Strict valid JSON only; no bare NaN/Infinity tokens.
- Compound key order on the wire is insertion order; the canonical hashing
  form sorts keys byte-wise. Hash = SHA-256 over the canonical JSON text
  (`TagHash.sha256Hex`).
- Limits (`EncodingLimits`, defaults: depth 32, 100k nodes, 1M chars per
  string) are enforced by validation *before* scheduling game-thread work.
- Java object stringification is never used as a serialization fallback;
  unserializable fields must be reported explicitly by the bridge.
- Item components: `present` / `default-inherited` / `removed` are distinct
  states; a present component without a decodable value must declare
  `serialization: unsupported`.
- `EncodingMetadata` carries encodingVersion (currently 1), minecraftVersion,
  dataVersion, registryFingerprint, adapterSchemaVersion.

### Still open (later chunks)

- Codec-JSON rules (bridge-side, chunk 2.7), SNBT companion (optional),
  binary export format (optional), cross-version import policy decisions.
