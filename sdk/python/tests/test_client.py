"""SDK tests: discovery validation, auth, task flow, event cursors.

Runs against a local stub HTTP server implementing the documented MAPI
behavior (stdlib http.server) — no JVM needed.
"""

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory

from mapi.client import Discovery, MapiClient, MapiError

TOKEN = "sdk-test-token-0123456789"


class StubApi(BaseHTTPRequestHandler):
    """Minimal MAPI stand-in covering the endpoints the SDK uses."""

    server_version = "StubMAPI/1"
    task_counter = 100
    tasks = {}

    def log_message(self, *args):
        pass

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        return self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def do_GET(self):
        if not self._authorized():
            self._json(401, {"error": {"code": "UNAUTHORIZED", "message": "nope", "requestId": "r1"}})
            return
        if self.path.startswith("/api/v1/health"):
            self._json(200, {"protocolVersion": 1, "status": "ok"})
        elif self.path.startswith("/api/v1/events"):
            after = int(self.path.split("after=")[1].split("&")[0])
            events = [] if after >= 3 else [
                {"seq": seq, "eventType": f"t.{seq}", "source": "instrumented", "wallClock": 1,
                 "monotonicNanos": seq, "processSessionId": "p", "data": {}}
                for seq in range(after + 4, after + 6)]
            self._json(200, {"protocolVersion": 1, "events": events, "truncated": False,
                             "headSeq": 10, "oldestSeq": 1})
        elif self.path.startswith("/api/v1/tasks/"):
            task_id = self.path.rsplit("/", 1)[1]
            self._json(200, StubApi.tasks.get(task_id, {"state": "running"}))
        else:
            self._json(404, {"error": {"code": "NOT_FOUND", "message": "", "requestId": "r"}})

    def do_POST(self):
        if not self._authorized():
            self._json(401, {"error": {"code": "UNAUTHORIZED", "message": "nope", "requestId": "r1"}})
            return
        if self.path == "/api/v1/tasks":
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
            key = self.headers.get("Idempotency-Key")
            if key and key in getattr(self.server, "idempotency", {}):
                self._json(202, self.server.idempotency[key])
                self.server.idempotency[key + ":replayed"] = True
                return
            StubApi.task_counter += 1
            task = {"id": f"{StubApi.task_counter:04d}", "kind": body.get("kind"), "state": "succeeded",
                    "createdAtEpochMs": 1, "deadlineEpochMs": 2, "partialEffects": [], "cleanup": []}
            StubApi.tasks[task["id"]] = task
            if key:
                self.server.idempotency[key] = task
            self.send_response(202)
            self.send_header("Location", f"/api/v1/tasks/{task['id']}")
            self.send_header("Content-Length", str(len(json.dumps(task).encode())))
            self.end_headers()
            self.wfile.write(json.dumps(task).encode())
        else:
            self._json(404, {"error": {"code": "NOT_FOUND", "message": "", "requestId": "r"}})


class StubServer:
    def __init__(self):
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), StubApi)
        self.httpd.idempotency = {}
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.httpd.server_address[1]

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.game_dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write_discovery(self, text):
        (self.game_dir / "mcapi").mkdir(exist_ok=True)
        (self.game_dir / "mcapi" / "discovery.json").write_text(text, encoding="utf-8")

    def test_valid_discovery_parses(self):
        self.write_discovery(json.dumps({
            "schemaVersion": 1, "instanceId": "client-2", "processSessionId": "abc",
            "pid": 5, "readiness": "worldReady",
            "api": {"host": "127.0.0.1", "port": 25586},
            "events": {"host": "127.0.0.1", "port": 25587}}))
        record = Discovery.load(self.game_dir)
        self.assertEqual(record.instance_id, "client-2")
        self.assertEqual(record.api_port, 25586)
        self.assertEqual(record.events_port, 25587)

    def test_untrusted_input_rejected(self):
        self.write_discovery('{"schemaVersion":2}')
        with self.assertRaises(ValueError):
            Discovery.load(self.game_dir)
        self.write_discovery(json.dumps({"schemaVersion": 1, "instanceId": "bad id!",
                                         "processSessionId": "x", "pid": 1, "readiness": "http",
                                         "api": {"port": 1}}))
        with self.assertRaises(ValueError):
            Discovery.load(self.game_dir)


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.stub = StubServer()
        self.client = MapiClient(f"http://127.0.0.1:{self.stub.port}", TOKEN)

    def tearDown(self):
        self.stub.stop()

    def test_auth_is_sent_and_401_maps_to_error(self):
        self.assertEqual(self.client.health()["status"], "ok")
        bad = MapiClient(f"http://127.0.0.1:{self.stub.port}", "wrong-token")
        with self.assertRaises(MapiError) as ctx:
            bad.health()
        self.assertEqual(ctx.exception.status, 401)
        self.assertEqual(ctx.exception.code, "UNAUTHORIZED")

    def test_create_and_wait_task_with_idempotency(self):
        first = self.client.create_task("wait-for-tick", {"targetTick": 5},
                                        idempotency_key="sdk-key-1")
        replay = self.client.create_task("wait-for-tick", {"targetTick": 5},
                                         idempotency_key="sdk-key-1")
        self.assertEqual(first["id"], replay["id"], "same key+body must replay")
        done = self.client.wait_task(first["id"])
        self.assertEqual(done["state"], "succeeded")

    def test_follow_events_yields_in_order_with_gap(self):
        stream = self.client.follow_events(after=0)
        first_event, gap = next(stream)
        # Stub returns seq 4,5 for after=0 → the cursor jumps 1→4: a gap.
        self.assertIsNone(gap)
        self.assertEqual(first_event["seq"], 4)
        second, _ = next(stream)
        self.assertEqual(second["seq"], 5)


if __name__ == "__main__":
    unittest.main()
