import json
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = "smoke-token-0123456789"
state = {"world_phase": "NONE"}


class Fake(BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization")
        path = self.path
        if auth != f"Bearer {TOKEN}":
            status, body = 401, {"error": {"code": "UNAUTHORIZED", "message": "no"}}
        elif path == "/api/v1/health":
            status, body = 200, {"protocolVersion": 1, "status": "ok"}
        elif path == "/api/v1/server/world":
            status, body = 200, {"phase": state["world_phase"]}
        else:
            status, body = 404, {"error": {"code": "NOT_FOUND", "message": "unknown"}}
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):
        pass


server = HTTPServer(("127.0.0.1", 0), Fake)
port = server.server_port
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()

NODE_SCRIPT = f"""
import {{ MapiClient, MapiError }} from
        '/home/ian/.t3/worktrees/minecraft-api/t3code-f47c6a6d/sdk/typescript/src/mapi-client.ts';
const client = new MapiClient('http://127.0.0.1:{port}', '{TOKEN}');

const health = await client.getHealth();
if (health.status !== 200 || health.ok !== true
    || health.body.status !== 'ok') {{
  console.error('FAIL health', JSON.stringify(health));
  process.exit(1);
}}

const world = await client.getWorldInfo();
console.log("world phase:", world.body.phase);
if (world.body.phase !== 'NONE') {{
  console.error('FAIL world', JSON.stringify(world));
  process.exit(1);
}}

try {{
  const bad = new MapiClient('http://127.0.0.1:{port}', 'wrong-token');
  await bad.getHealth();
  console.error('FAIL expected 401');
  process.exit(1);
}} catch (e) {{
  if (!(e instanceof MapiError) || e.status !== 401
      || e.code !== 'UNAUTHORIZED') {{
    console.error('FAIL wrong error shape', e);
    process.exit(1);
  }}
}}

console.log('TS CLIENT SMOKE PASS');
"""

try:
    result = subprocess.run(
        [sys.executable.replace("python", "node") if False else "node",
         "--experimental-strip-types", "--no-warnings", "-e", NODE_SCRIPT],
        capture_output=True, text=True, timeout=30)
    print(result.stdout, end="")
    if result.returncode != 0:
        print(result.stderr, end="", file=sys.stderr)
        sys.exit(1)
finally:
    server.shutdown()
