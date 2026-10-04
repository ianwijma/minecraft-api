"""Offline contract checks for the stdlib-only Python client."""

import json
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer

from mapi_client import MapiClient, MapiError, Result

TOKEN = "python-smoke-token"


class FakeApi(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.requests.append((self.path, self.headers.get("Authorization")))
        if self.path.startswith("/stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.write(
                b":event-gap droppedUpTo=4\n\nid: 5\nevent: changed\n"
                b"data: {\"ok\":true}\n\n"
            )
            return
        if self.path == "/error":
            status, body = 403, {"error": {"code": "FORBIDDEN", "message": "denied"}}
        else:
            status, body = 200, {"status": "ok"}
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_args):
        pass


class MapiClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        FakeApi.requests = []
        cls.server = HTTPServer(("127.0.0.1", 0), FakeApi)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.client = MapiClient(f"http://127.0.0.1:{cls.server.server_port}", TOKEN)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def test_success_sends_bearer_token_and_parses_json(self):
        result = self.client.get("/ok")
        self.assertEqual((result.status, result.ok, result.body),
                         (200, True, {"status": "ok"}))
        self.assertEqual(FakeApi.requests[-1], ("/ok", f"Bearer {TOKEN}"))

    def test_problem_envelope_maps_to_mapi_error(self):
        with self.assertRaises(MapiError) as caught:
            self.client.get("/error")
        self.assertEqual(caught.exception.status, 403)
        self.assertEqual(caught.exception.code, "FORBIDDEN")
        self.assertEqual(caught.exception.message, "denied")

    def test_sse_parses_events_and_retains_gap_cursor(self):
        events = list(self.client.stream("/stream", cursor=7, types=["changed"], world="world-1"))
        self.assertEqual(len(events), 1)
        self.assertEqual((events[0].id, events[0].event, events[0].data),
                         ("5", "changed", {"ok": True}))
        self.assertEqual(self.client.last_gap, 4)
        path, auth = FakeApi.requests[-1]
        self.assertEqual(path, "/stream?keepaliveSeconds=1&cursor=7&types=changed&world=world-1")
        self.assertEqual(auth, f"Bearer {TOKEN}")

    def test_wait_helper_observes_transition_with_a_controllable_clock(self):
        pending = Result(200, {"phase": "LOADING"}, True)
        active = Result(200, {"phase": "ACTIVE"}, True)
        with patch.object(self.client, "get", side_effect=[pending, active]) as get, \
                patch("time.monotonic", side_effect=[0, 0, 1]), patch("time.sleep") as sleep:
            self.assertTrue(self.client.wait_world_active(timeout_s=2, poll_s=0.25))
            self.assertEqual(get.call_count, 2)
            sleep.assert_called_once_with(0.25)

    def test_wait_helper_stops_when_its_clock_reaches_the_deadline(self):
        with patch.object(self.client, "get", return_value=Result(200, {"phase": "LOADING"}, True)) as get, \
                patch("time.monotonic", side_effect=[0, 0, 2]), patch("time.sleep"):
            self.assertFalse(self.client.wait_world_active(timeout_s=2))
            self.assertEqual(get.call_count, 1)

    def test_closing_stream_iterator_closes_its_response(self):
        responses = []
        stream = self.client.stream("/stream", on_open=responses.append)
        self.assertEqual(next(stream).id, "5")
        stream.close()
        self.assertTrue(responses[0].closed)


if __name__ == "__main__":
    unittest.main()
