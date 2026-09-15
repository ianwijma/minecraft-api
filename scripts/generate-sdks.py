#!/usr/bin/env python3
"""SDK generator: docs/openapi.yaml -> sdk/typescript/src/mapi-client.ts.

Deterministic by design (spec §20: SDKs are generated reproducibly from the
canonical contract): same docs/openapi.yaml -> byte-identical output. The
emitted client is self-contained (global fetch, Node >= 18 / modern
browsers), zero dependencies, and implements the documented wire semantics:
bearer-token auth, problem-code error envelopes, and cursor-resumable SSE
with explicit gap handling (spec §13.1). Generated output must never be
edited by hand; edit this generator or the contract.
"""

import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
OPENAPI = ROOT / "docs" / "openapi.yaml"
OUT_DIR = ROOT / "sdk" / "typescript"


def camel(operation_id: str) -> str:
    parts = operation_id.replace(".", "_").replace("-", "_").split("_")
    return parts[0] + "".join(p.title() for p in parts[1:] if p)


def ts_type(schema: dict) -> str:
    if not isinstance(schema, dict):
        return "unknown"
    if "$ref" in schema:
        return "unknown"
    t = schema.get("type")
    return {
        "integer": "number",
        "number": "number",
        "boolean": "boolean",
        "string": "string",
        "array": "unknown[]",
        "object": "Record<string, unknown>",
    }.get(t, "unknown")


def query_params(method: dict) -> list:
    out = []
    for param in method.get("parameters", []):
        name = param.get("name", "")
        if param.get("in") != "query":
            continue
        schema = param.get("schema", {})
        optional = "default" in schema or param.get("required") is not True
        out.append((name, ts_type(schema), optional, schema.get("default")))
    return sorted(out)


def emit_client(spec: dict) -> str:
    lines = []
    title = spec.get("info", {}).get("title", "MAPI local HTTP API")
    lines.append("/**")
    lines.append(f" * MAPI TypeScript client — GENERATED from docs/openapi.yaml ({title}).")
    lines.append(" * Do not edit; rerun scripts/generate-sdks.py instead.")
    lines.append(" * Wire semantics implemented per docs/openapi.yaml + docs/security.md:")
    lines.append(" * bearer auth, problem-code envelopes, loopback-only usage.")
    lines.append(" */")
    lines.append("")
    lines.append("export interface MapiResult {")
    lines.append("  status: number;")
    lines.append("  body: unknown;")
    lines.append("  ok: boolean;")
    lines.append("}")
    lines.append("")
    lines.append("export class MapiError extends Error {")
    lines.append("  readonly status: number;")
    lines.append("  readonly code: string;")
    lines.append("  constructor(status: number, code: string, message: string) {")
    lines.append("    super(`HTTP ${status} ${code}: ${message}`);")
    lines.append("    this.status = status;")
    lines.append("    this.code = code;")
    lines.append("  }")
    lines.append("}")
    lines.append("")
    lines.append("export class MapiClient {")
    lines.append("  private readonly base: string;")
    lines.append("  private readonly token: string;")
    lines.append("  private readonly timeoutMs: number;")
    lines.append("  constructor(base: string, token: string, timeoutMs = 10_000) {")
    lines.append("    if (!token) throw new Error('a bearer token is required');")
    lines.append("    this.base = base.replace(/\\/+$/, '');")
    lines.append("    this.token = token;")
    lines.append("    this.timeoutMs = timeoutMs;")
    lines.append("  }")
    lines.append("")
    lines.append("  private async request(method: 'GET' | 'POST', path: string,")
    lines.append("                        body?: unknown): Promise<MapiResult> {")
    lines.append("    const headers: Record<string, string> = {")
    lines.append("      Authorization: `Bearer ${this.token}`,")
    lines.append("      Host: '127.0.0.1',")
    lines.append("    };")
    lines.append("    if (body !== undefined) {")
    lines.append("      headers['Content-Type'] = 'application/json';")
    lines.append("    }")
    lines.append("    const response = await fetch(this.base + path, {")
    lines.append("      method, headers,")
    lines.append("      body: body === undefined ? undefined : JSON.stringify(body),")
    lines.append("      signal: AbortSignal.timeout(this.timeoutMs),")
    lines.append("    });")
    lines.append("    const parsed = await response.json();")
    lines.append("    const ok = response.status >= 200 && response.status < 300;")
    lines.append("    if (!ok && parsed && typeof parsed === 'object'")
    lines.append("        && parsed.error && typeof parsed.error.code === 'string') {")
    lines.append("      throw new MapiError(response.status, parsed.error.code,")
    lines.append("                         String(parsed.error.message ?? ''));")
    lines.append("    }")
    lines.append("    return { status: response.status, body: parsed, ok };")
    lines.append("  }")
    lines.append("")

    for path in sorted(spec.get("paths", {})):
        for method in sorted(spec["paths"][path]):
            op = spec["paths"][path][method]
            if method not in ("get", "post"):
                continue
            op_id = camel(op.get("operationId") or method + path)
            summary = (op.get("summary") or "").replace("*/", "*\\/").split("\n")[0]
            literal = path.replace("{", "${encodeURIComponent(String(") \
                .replace("}", "))}")
            args = []
            if method == "get":
                params = query_params(op)
                for name, t, optional, default in params:
                    arg = f"{name}{'' if not optional else '?'}: {t}"
                    args.append(arg)
                qs = " & ".join(
                    f"{name}=${{encodeURIComponent(String({name}))}}" for name, *_ in params)
                query = f"?{qs}" if qs else ""
                lines.append(f"  /** {summary} */")
                if args:
                    lines.append(f"  {op_id}({', '.join(args)}): Promise<MapiResult> {{")
                else:
                    lines.append(f"  {op_id}(): Promise<MapiResult> {{")
                lines.append(f"    return this.request('{method.upper()}', "
                             f"`{literal}{query}`);")
                lines.append("  }")
                lines.append("")
            else:
                body_schema = op.get("requestBody", {}).get("content", {}) \
                    .get("application/json", {}).get("schema", {})
                body_type = ts_type(body_schema)
                lines.append(f"  /** {summary} */")
                if body_type == "unknown":
                    lines.append(f"  {op_id}(body: Record<string, unknown>): "
                                 "Promise<MapiResult> {")
                else:
                    lines.append(f"  {op_id}(body: {body_type}): Promise<MapiResult> {{")
                lines.append(f"    return this.request('POST', `{literal}`, body);")
                lines.append("  }")
                lines.append("")

    # SSE stream (fixed contract, spec §13.1): bearer header, gap comments.
    lines.append("  /**")
    lines.append("   * Streams runtime events (GET /api/v1/events/stream).")
    lines.append("   * Establish the cursor before triggering actions, then wait")
    lines.append("   * from it (spec §13.2). Gap comments surface as")
    lines.append("   * `{ gap: true, droppedUpToSeq }` yields.")
    lines.append("   */")
    lines.append("  async *streamEvents(cursor?: number): AsyncGenerator<")
    lines.append("      { gap: false; id: string; event: string; data: unknown } |")
    lines.append("      { gap: true; droppedUpToSeq: number }> {")
    lines.append("    const query = cursor === undefined ? '' : `?cursor=${cursor}`;")
    lines.append("    const response = await fetch(")
    lines.append("        this.base + '/api/v1/events/stream' + query, {")
    lines.append("      headers: { Authorization: `Bearer ${this.token}`,")
    lines.append("               Host: '127.0.0.1' },")
    lines.append("      signal: AbortSignal.timeout(this.timeoutMs),")
    lines.append("    });")
    lines.append("    if (!response.ok || !response.body) {")
    lines.append("      throw new MapiError(response.status, 'STREAM_FAILED',")
    lines.append("                         'event stream unavailable');")
    lines.append("    }")
    lines.append("    const reader = response.body.getReader();")
    lines.append("    const decoder = new TextDecoder();")
    lines.append("    let buffer = '';")
    lines.append("    let id = '';")
    lines.append("    let event = 'message';")
    lines.append("    let data: string[] = [];")
    lines.append("    while (true) {")
    lines.append("      const { done, value } = await reader.read();")
    lines.append("      if (done) break;")
    lines.append("      buffer += decoder.decode(value, { stream: true });")
    lines.append("      let index;")
    lines.append("      while ((index = buffer.search(/\\r\\n|\\n|\\r/)) >= 0) {")
    lines.append("        const line = buffer.slice(0, index);")
    lines.append("        buffer = buffer.slice(index + (buffer[index] === '\\r'"
             " && buffer[index + 1] === '\\n' ? 2 : 1));")
    lines.append("        if (line.startsWith(':')) {")
    lines.append("          const comment = line.slice(1).trim();")
    lines.append("          if (comment.startsWith('event-gap')) {")
    lines.append("            const value = comment.split('droppedUpTo=')[1];")
    lines.append("            yield { gap: true, droppedUpToSeq: Number(value) };")
    lines.append("          }")
    lines.append("          continue;")
    lines.append("        }")
    lines.append("        if (!line) {")
    lines.append("          if (data.length > 0) {")
    lines.append("            const text = data.join('\\n');")
    lines.append("            let parsed: unknown = text;")
    lines.append("            try { parsed = JSON.parse(text); } catch { /* text */ }")
    lines.append("            yield { gap: false, id, event, data: parsed };")
    lines.append("          }")
    lines.append("          id = '';")
    lines.append("          event = 'message';")
    lines.append("          data = [];")
    lines.append("          continue;")
    lines.append("        }")
    lines.append("        const colon = line.indexOf(':');")
    lines.append("        const field = colon < 0 ? line : line.slice(0, colon);")
    lines.append("        let value = colon < 0 ? '' : line.slice(colon + 1);")
    lines.append("        if (value.startsWith(' ')) value = value.slice(1);")
    lines.append("        if (field === 'id') id = value;")
    lines.append("        else if (field === 'event') event = value || 'message';")
    lines.append("        else if (field === 'data') data.push(value);")
    lines.append("      }")
    lines.append("    }")
    lines.append("  }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main() -> int:
    spec = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
    (OUT_DIR / "src").mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "src" / "mapi-client.ts").write_text(emit_client(spec), encoding="utf-8")
    (OUT_DIR / "package.json").write_text(json.dumps({
        "name": "@mapi/client",
        "version": "1.0.0",
        "description": "Generated MAPI TypeScript client (docs/openapi.yaml; do not edit)",
        "type": "module",
        "main": "src/mapi-client.ts",
        "types": "src/mapi-client.ts",
        "engines": {"node": ">=18"},
        "license": "SEE LICENSE.pending.md",
    }, indent=2) + "\n", encoding="utf-8")
    (OUT_DIR / "README.md").write_text(
        "# @mapi/client (generated)\n\n"
        "Generated by `scripts/generate-sdks.py` from `docs/openapi.yaml` — "
        "do not edit the output. Zero dependencies; Node >= 18 (global fetch).\n\n"
        "```ts\n"
        "import { MapiClient } from '@mapi/client';\n"
        "const client = new MapiClient('http://127.0.0.1:25586', token);\n"
        "const health = await client.health();\n"
        "```\n",
        encoding="utf-8")
    print(f"generated: {OUT_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
