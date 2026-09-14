"""MAPI client: auth, discovery, tasks, events (stdlib only)."""

import base64
import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

IDENTIFIER = re.compile(r"[a-zA-Z0-9._-]{1,64}")
READINESS = {"http", "worldReady", "clientJoined"}
TERMINAL_TASK_STATES = {"succeeded", "failed", "cancelled", "expired"}


class MapiError(RuntimeError):
    """A MAPI request failed at the transport or protocol level."""

    def __init__(self, status, code, message):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(f"{status} {code}: {message}")


class Discovery:
    """Validated view of a discovery file (untrusted input, spec section 9)."""

    def __init__(self, raw):
        if not isinstance(raw, dict):
            raise ValueError("discovery content must be a JSON object")
        if raw.get("schemaVersion") != 1:
            raise ValueError(f"unsupported schemaVersion: {raw.get('schemaVersion')}")
        self.instance_id = Discovery._identifier(raw, "instanceId")
        self.process_session_id = Discovery._identifier(raw, "processSessionId")
        self.pid = Discovery._int(raw, "pid")
        self.readiness = raw.get("readiness")
        if self.readiness not in READINESS:
            raise ValueError(f"unknown readiness: {self.readiness}")
        self.api_port = Discovery._port(raw, "api")
        events = raw.get("events")
        if events is not None and not isinstance(events, dict):
            raise ValueError("events must be an object")
        self.events_port = Discovery._port(raw, "events") if events is not None else None

    @staticmethod
    def _identifier(raw, field):
        value = raw.get(field)
        if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
            raise ValueError(f"{field} is not a valid identifier")
        return value

    @staticmethod
    def _int(raw, field):
        value = raw.get(field)
        if not isinstance(value, int) or value < 0:
            raise ValueError(f"{field} must be a non-negative integer")
        return value

    @staticmethod
    def _port(raw, field):
        section = raw.get(field) if isinstance(raw, dict) else None
        if not isinstance(section, dict) or not isinstance(section.get("port"), int):
            raise ValueError(f"{field}.port must be a valid port")
        value = section["port"]
        if not 1 <= value <= 65535:
            raise ValueError(f"{field}.port must be a valid port")
        return value

    @classmethod
    def load(cls, game_dir):
        """Reads and validates <gameDir>/mcapi/discovery.json."""
        path = Path(game_dir) / "mcapi" / "discovery.json"
        with open(path, "r", encoding="utf-8") as handle:
            return cls(json.load(handle))


class MapiClient:
    """HTTP client for one MAPI instance (loopback, bearer token)."""

    def __init__(self, base_url, token, timeout=10.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    @classmethod
    def from_game_dir(cls, game_dir, timeout=10.0):
        """Resolves the instance via its discovery file and token file."""
        discovery = Discovery.load(game_dir)
        token = (Path(game_dir) / "mcapi" / "token").read_text(encoding="utf-8").strip()
        if not token:
            raise ValueError(f"empty token file under {game_dir}/mcapi")
        return cls(f"http://127.0.0.1:{discovery.api_port}", token, timeout=timeout)

    @classmethod
    def from_url(cls, base_url, token_file, timeout=10.0):
        """Resolves the instance from an explicit URL and token file."""
        token = Path(token_file).read_text(encoding="utf-8").strip()
        if not token:
            raise ValueError("empty token file")
        return cls(base_url, token, timeout=timeout)

    # -- transport ------------------------------------------------------------

    def _request(self, method, path, body=None, headers=None):
        request = urllib.request.Request(self.base_url + path, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        try:
            with urllib.request.urlopen(request, data=data, timeout=self.timeout) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            try:
                payload = json.loads(error.read().decode("utf-8"))
                error_body = payload.get("error", {})
                raise MapiError(error.code, error_body.get("code", "UNKNOWN"),
                                error_body.get("message", "")) from error
            except (ValueError, AttributeError):
                raise MapiError(error.code, "UNKNOWN", "") from error
        except urllib.error.URLError as error:
            raise MapiError(0, "CONNECTION_FAILED", str(error.reason)) from error

    # -- reads ----------------------------------------------------------------

    def health(self):
        return self._request("GET", "/api/v1/health")[1]

    def info(self):
        return self._request("GET", "/api/v1/info")[1]

    def ready(self):
        return self._request("GET", "/api/v1/ready")[1]

    def server_status(self):
        return self._request("GET", "/api/v1/server/status")[1]

    def players(self, fields=None, limit=50, offset=0):
        query = f"/api/v1/server/players?limit={limit}&offset={offset}"
        if fields:
            query += "&fields=" + ",".join(fields)
        return self._request("GET", query)[1]

    def block(self, dimension, x, y, z):
        from urllib.parse import quote
        return self._request("GET", f"/api/v1/server/world/block?dimension={quote(dimension)}"
                             f"&x={x}&y={y}&z={z}")[1]

    def world_time(self, dimension):
        from urllib.parse import quote
        return self._request("GET", f"/api/v1/server/world/time?dimension={quote(dimension)}")[1]

    # -- tasks ----------------------------------------------------------------

    def create_task(self, kind, payload=None, deadline_ms=None, idempotency_key=None,
                    expected_world_session_id=None):
        body = {"kind": kind, "payload": payload or {}}
        if deadline_ms is not None:
            body["deadlineMs"] = deadline_ms
        if expected_world_session_id is not None:
            body["expectedWorldSessionId"] = expected_world_session_id
        headers = {"Idempotency-Key": idempotency_key} if idempotency_key else None
        status, task = self._request("POST", "/api/v1/tasks", body=body, headers=headers)
        if status != 202:
            raise MapiError(status, "UNEXPECTED_STATUS", f"task creation returned {status}")
        return task

    def task(self, task_id):
        return self._request("GET", f"/api/v1/tasks/{task_id}")[1]

    def wait_task(self, task_id, timeout_s=30.0, poll_s=0.2):
        """Polls until the task is terminal; returns the last snapshot."""
        deadline = time.monotonic() + timeout_s
        snapshot = self.task(task_id)
        while snapshot.get("state") not in TERMINAL_TASK_STATES:
            if time.monotonic() >= deadline:
                return snapshot
            time.sleep(poll_s)
            snapshot = self.task(task_id)
        return snapshot

    def cancel_task(self, task_id):
        return self._request("DELETE", f"/api/v1/tasks/{task_id}")[1]

    # -- leases / commands ------------------------------------------------------

    def list_leases(self):
        return self._request("GET", "/api/v1/leases")[1]

    def acquire_lease(self, lease, ttl_ms=None, conflict=None):
        body = {"lease": lease}
        if ttl_ms is not None:
            body["ttlMs"] = ttl_ms
        if conflict is not None:
            body["conflict"] = conflict
        return self._request("POST", "/api/v1/leases", body=body)[1]

    def renew_lease(self, lease_id, ttl_ms=None):
        body = {"ttlMs": ttl_ms} if ttl_ms is not None else {}
        return self._request("POST", f"/api/v1/leases/{lease_id}/renew", body=body)[1]

    def release_lease(self, lease_id):
        return self._request("DELETE", f"/api/v1/leases/{lease_id}")[1]

    def execute_command(self, command, expected_world_session_id=None):
        body = {"command": command}
        if expected_world_session_id is not None:
            body["expectedWorldSessionId"] = expected_world_session_id
        return self._request("POST", "/api/v1/server/commands/execute", body=body)[1]

    # -- events ---------------------------------------------------------------

    def events(self, after=0, limit=100):
        return self._request("GET", f"/api/v1/events?after={after}&limit={limit}")[1]

    def follow_events(self, after=None, poll_s=0.25):
        """Generator yielding (event, gap) in sequence order, resuming by cursor.

        `gap` is (from, to) when the instance reports dropped history; the
        generator never re-delivers events the caller already saw.
        """
        cursor = after if after is not None else 0
        while True:
            page = self.events(after=cursor)
            for gap in [page["gap"]] if "gap" in page else []:
                yield {"type": "gap", "from": gap["from"], "to": gap["to"]}, (gap["from"], gap["to"])
            for event in page["events"]:
                if event["seq"] > cursor:
                    yield event, None
                    cursor = event["seq"]
            if page["headSeq"] > cursor:
                cursor = page["headSeq"] if not page["events"] else cursor
            time.sleep(poll_s)
