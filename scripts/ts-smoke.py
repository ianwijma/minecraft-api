#!/usr/bin/env python3
"""Offline behavioral check for the generated TypeScript SDK (Node >= 18)."""

import json
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOKEN = "smoke-token-0123456789"
requests = []


class Fake(BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append((self.path, self.headers.get("Authorization")))
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":{"code":"UNAUTHORIZED","message":"no"}}')
            return
        if self.path.startswith("/api/v1/events/stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.write(b":event-gap droppedUpTo=4\n\nid: 5\nevent: changed\ndata: {\"ok\":true}\n\n")
            self.wfile.flush()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-MAPI-Protocol-Version", "1")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *_args):
        pass


server = HTTPServer(("127.0.0.1", 0), Fake)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
module_url = (ROOT / "sdk/typescript/src/mapi-client.ts").as_uri()
script = f"""
import {{ MapiClient, MapiError }} from {json.dumps(module_url)};
const client = new MapiClient('http://127.0.0.1:{server.server_port}', '{TOKEN}');
const health = await client.getHealth();
if (!health.ok || health.body.ok !== true || health.headers['x-mapi-protocol-version'] !== '1') throw new Error('health request failed');
await client.getJob('job a/x');
await client.readLogs();
await client.readLogs(0, 10);
await client.queryRegistryEntries('minecraft:block');
const events = [];
for await (const event of client.streamEvents(7, ['world.phase', 'player.join'], 'test world', 5)) events.push(event);
if (events.length !== 2 || !events[0].gap || events[0].droppedUpToSeq !== 4
    || events[1].id !== '5' || events[1].event !== 'changed' || events[1].data.ok !== true) {{
  throw new Error('SSE parsing failed: ' + JSON.stringify(events));
}}
try {{
  await new MapiClient('http://127.0.0.1:{server.server_port}', 'wrong').getHealth();
  throw new Error('expected MapiError');
}} catch (error) {{
  if (!(error instanceof MapiError) || error.status !== 401 || error.code !== 'UNAUTHORIZED') throw error;
  if (error.body.error.code !== 'UNAUTHORIZED' || error.headers['content-type'] !== 'application/json') throw new Error('structured error details missing');
}}
const realFetch = globalThis.fetch;
let captured;
globalThis.fetch = async (_url, options) => {{
  captured = options;
  return new Response('{{"ok":true}}', {{status: 200, headers: {{'Content-Type':'application/json'}}}});
}};
await client.get('/browser-check');
if (Object.keys(captured.headers).some(key => key.toLowerCase() === 'host')) throw new Error('SDK sets forbidden Host header');
globalThis.fetch = realFetch;
console.log('TS SDK offline smoke PASS');
"""

try:
    result = subprocess.run(
        ["node", "--experimental-strip-types", "--no-warnings", "-e", script],
        capture_output=True, text=True, timeout=30)
    print(result.stdout, end="")
    if result.returncode:
        print(result.stderr, end="", file=sys.stderr)
        sys.exit(result.returncode)
    paths = [path for path, auth in requests if auth == f"Bearer {TOKEN}"]
    expected = [
        "/api/v1/health",
        "/api/v1/jobs/job%20a%2Fx",
        "/api/v1/logs",
        "/api/v1/logs?cursor=0&limit=10",
        "/api/v1/server/queries/registry?registryId=minecraft%3Ablock",
        "/api/v1/events/stream?cursor=7&types=world.phase%2Cplayer.join&world=test+world&keepaliveSeconds=5",
    ]
    if paths[:len(expected)] != expected:
        raise RuntimeError(f"unexpected requests: {paths!r}")
finally:
    server.shutdown()
    thread.join(timeout=2)
