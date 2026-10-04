#!/usr/bin/env python3
"""Generate the browser operation catalog and resolved schemas from OpenAPI."""

import argparse
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SPEC_PATH = ROOT / "docs/openapi.yaml"
OVERRIDES_PATH = ROOT / "docs/dashboard-preview/operations.json"
OUT = ROOT / "dashboard/src/generated/operations.ts"


def pointer(spec, ref):
    if not ref.startswith("#/"):
        raise ValueError(f"external OpenAPI reference is unsupported: {ref}")
    value = spec
    for part in ref[2:].split("/"):
        value = value[part.replace("~1", "/").replace("~0", "~")]
    return value


def resolve(spec, value, stack=()):
    if isinstance(value, list):
        return [resolve(spec, item, stack) for item in value]
    if not isinstance(value, dict):
        return value
    if "$ref" in value:
        ref = value["$ref"]
        if ref in stack:
            raise ValueError(f"cyclic reference: {' -> '.join((*stack, ref))}")
        target = resolve(spec, pointer(spec, ref), (*stack, ref))
        siblings = {k: v for k, v in value.items() if k != "$ref"}
        return merge(target, resolve(spec, siblings, stack)) if siblings else target
    result = {k: resolve(spec, v, stack) for k, v in value.items()
              if k not in ("allOf",)}
    if "allOf" in value:
        merged = {}
        for part in value["allOf"]:
            merged = merge(merged, resolve(spec, part, stack))
        result = merge(merged, result)
    return result


def merge(left, right):
    out = dict(left)
    for key, value in right.items():
        if key == "required":
            out[key] = list(dict.fromkeys([*out.get(key, []), *value]))
        elif key == "properties":
            props = dict(out.get(key, {}))
            props.update(value)
            out[key] = props
        elif key in out and isinstance(out[key], dict) and isinstance(value, dict):
            out[key] = merge(out[key], value)
        else:
            out[key] = value
    return out


def media_schema(spec, content):
    for media in ("application/json", "text/event-stream"):
        if media in (content or {}):
            schema = (content[media] or {}).get("schema")
            if schema:
                return resolve(spec, schema)
    return None


def generate(spec, overrides):
    override_by_id = {entry["id"]: entry for entry in overrides}
    operations = []
    for path, path_item in spec.get("paths", {}).items():
        for method in ("get", "post"):
            op = path_item.get(method)
            if not op:
                continue
            oid = op.get("operationId")
            if not oid:
                raise ValueError(f"operation lacks operationId: {method} {path}")
            params = {}
            for p in [*path_item.get("parameters", []), *op.get("parameters", [])]:
                p = resolve(spec, p)
                params[(p.get("in"), p.get("name"))] = p
            for name in __import__("re").findall(r"\{([^}]+)\}", path):
                params.setdefault(("path", name), {"name": name, "in": "path",
                                                       "required": True, "schema": {"type": "string"}})
            normalized = []
            for p in params.values():
                if p.get("in") not in ("path", "query"):
                    continue
                normalized.append({"name": p["name"], "in": p["in"],
                                   "required": p.get("required") is True,
                                   "schema": resolve(spec, p.get("schema", {}))})
            normalized.sort(key=lambda p: (p["in"] != "path", p["name"]))
            body = resolve(spec, op.get("requestBody", {})) if op.get("requestBody") else {}
            body_schema = media_schema(spec, body.get("content", {}))
            responses = {}
            for code, response in op.get("responses", {}).items():
                try:
                    response = resolve(spec, response)
                except KeyError:
                    # The contract currently contains one dangling error-response
                    # reference; it has no declared payload schema to generate.
                    continue
                schema = media_schema(spec, response.get("content", {}))
                if schema is not None:
                    responses[str(code)] = schema
            security = op.get("x-mapi-security", {})
            override = override_by_id.get(oid, {})
            operations.append({
                "id": oid,
                "label": override.get("label", oid),
                "group": override.get("group", "Other"),
                "method": method.upper(), "path": path,
                "summary": op.get("summary", ""),
                "parameters": normalized,
                **({"bodySchema": body_schema} if body_schema is not None else {}),
                "security": {
                    "requiredScopes": security.get("requiredScopes", []),
                    "destructive": security.get("destructive", False),
                    "sideEffectClass": security.get("sideEffectClass", "read-only"),
                    "requiresLease": security.get("requiresLease", False),
                    "supportedExecutionModes": security.get("supportedExecutionModes", []),
                },
                **({"responses": responses} if responses else {}),
            })
    if set(override_by_id) - {op["id"] for op in operations}:
        raise ValueError("preview overrides include unknown operation IDs")
    ids = [op["id"] for op in operations]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate operationId in OpenAPI")
    operations.sort(key=lambda op: op["id"])
    serialized = json.dumps(operations, ensure_ascii=False, indent=2)
    return ('/** Generated by scripts/generate-dashboard.py from docs/openapi.yaml. */\n'
            'import type { Operation } from "../lib/catalog";\n\n'
            f'export const operations: Operation[] = {serialized} satisfies Operation[];\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    overrides = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    content = generate(spec, overrides)
    actual = OUT.read_text(encoding="utf-8") if OUT.exists() else None
    expected_ids = {o.get("operationId") for p in spec.get("paths", {}).values()
                    for m, o in p.items() if m in ("get", "post")}
    if len(expected_ids) != 48:
        print(f"expected 48 operations, found {len(expected_ids)}", file=sys.stderr)
        return 1
    if args.check:
        if actual != content:
            print(f"generated dashboard catalog is stale: {OUT.relative_to(ROOT)}", file=sys.stderr)
            return 1
        print("dashboard catalog check passed (48 operations)")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(content, encoding="utf-8")
    print(f"generated {OUT.relative_to(ROOT)} ({len(expected_ids)} operations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
