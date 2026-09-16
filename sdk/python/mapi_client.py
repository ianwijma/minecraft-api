"""MAPI Python client (stdlib only, spec §1 deliverable 2).

A small handwritten helper where generated SDKs are insufficient. Speaks the
documented HTTP contract only: bearer-token auth, JSON envelopes, explicit
error mapping, SSE with the normal header (no query-string tokens), and
cursor-based resumes with gap detection. Depends on nothing but the standard
library and the OpenAPI document (docs/openapi.yaml) — never on MAPI
internals (ADR-0002).

Example:
    from mapi_client import MapiClient
    client = MapiClient("http://127.0.0.1:25586", token)
    health = client.get("/api/v1/health")            # -> Result(200, {...})
    world = client.get("/api/v1/server/world")
    if world.ok and world.body.get("phase") == "ACTIVE":
        shots = client.get("/api/v1/client/screenshots")
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Iterator, Optional


@dataclass(frozen=True)
class Result:
    """One HTTP outcome with the parsed JSON body."""

    status: int
    body: Any
    ok: bool

    @property
    def error_code(self) -> Optional[str]:
        error = self.body.get("error") if isinstance(self.body, dict) else None
        if isinstance(error, dict):
            return error.get("code")  # type: ignore[return-value]
        return None


@dataclass
class SseEvent:
    """One parsed server-sent event."""

    id: str
    event: str
    data: Any
    raw: str = field(repr=False, default="")


class MapiError(RuntimeError):
    """A problem-code envelope returned by the API."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"HTTP {status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class MapiClient:
    """Typed-enough HTTP client for the MAPI local API."""

    def __init__(self, base: str = "http://127.0.0.1:25586", token: str = "",
                 timeout: float = 10.0) -> None:
        if not token:
            raise ValueError("a bearer token is required")
        self._base = base.rstrip("/")
        self._token = token
        self._timeout = timeout

    # -- core ---------------------------------------------------------------

    def get(self, path: str) -> Result:
        return self._request("GET", path)

    def post(self, path: str, body: Optional[dict] = None) -> Result:
        return self._request("POST", path, body if body is not None else {})

    def _request(self, method: str, path: str, body: Optional[dict] = None) -> Result:
        data = None
        headers = {"Authorization": f"Bearer {self._token}",
                   "Host": "127.0.0.1"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self._base + path, data=data, method=method)
        for key, value in headers.items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                return Result(resp.status, json.loads(resp.read().decode("utf-8")),
                              200 <= resp.status < 300)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            try:
                parsed = json.loads(detail)
                error = parsed.get("error", {}) if isinstance(parsed, dict) else {}
                raise MapiError(e.code, error.get("code", "UNKNOWN"),
                                error.get("message", detail)) from e
            except json.JSONDecodeError as inner:
                raise MapiError(e.code, "UNKNOWN", detail) from inner

    # -- streaming (spec §13.1: fetch-style SSE with the bearer header) -----

    def stream(self, path: str = "/api/v1/events/stream",
               cursor: Optional[int] = None, types: Optional[list] = None,
               world: Optional[str] = None) -> Iterator[SseEvent]:
        """Yields parsed SSE events; resumes from `cursor` and reports gaps.

        The caller can establish a cursor (`client.latest_cursor()`) before
        triggering an action, then stream from that cursor - the documented
        anti-race pattern. A `:event-gap` comment raises StopIteration with
        the gap carried on the iterator (see `last_gap`).
        """
        params = []
        if cursor is not None:
            params.append(f"cursor={cursor}")
        if types:
            params.append("types=" + ",".join(types))
        if world:
            params.append(f"world={world}")
        url = self._base + path + ("?" + "&".join(params) if params else "")
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {self._token}")
        req.add_header("Host", "127.0.0.1")
        self.last_gap: Optional[int] = None
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            yield from self._parse_sse(resp)

    last_gap: Optional[int] = None

    @staticmethod
    def latest_cursor(client_result: Result) -> Optional[int]:
        """Extracts the stream cursor position from a logs/world response."""
        body = client_result.body
        if isinstance(body, dict) and "cursor" in body:
            return int(body["cursor"])  # type: ignore[arg-type]
        return None

    def _parse_sse(self, resp: Any) -> Iterator[SseEvent]:
        event_id, event_type, data_lines = "", "message", []
        for raw in resp:
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith(":"):
                comment = line[1:].strip()
                if comment.startswith("event-gap"):
                    # explicit gap: dropped-up-to=N (spec §13.2)
                    _, _, value = comment.partition("droppedUpTo=")
                    self.last_gap = int(value) if value.isdigit() else None
                continue
            if not line:
                if data_lines or event_type != "message" or event_id:
                    data = "\n".join(data_lines)
                    try:
                        parsed: Any = json.loads(data) if data else None
                    except json.JSONDecodeError:
                        parsed = data
                    yield SseEvent(event_id, event_type, parsed, line)
                event_id, event_type, data_lines = "", "message", []
                continue
            if ":" in line:
                field, _, value = line.partition(":")
                value = value.lstrip(" ")
            else:
                field, value = line, ""
            if field == "id":
                event_id = value
            elif field == "event":
                event_type = value or "message"
            elif field == "data":
                data_lines.append(value)

    # -- convenience flows --------------------------------------------------

    def wait_world_active(self, timeout_s: float = 60.0,
                          poll_s: float = 0.25) -> bool:
        """Bounded wait until the world session is ACTIVE (spec §6)."""
        import time

        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            world = self.get("/api/v1/server/world")
            if world.ok and world.body.get("phase") == "ACTIVE":
                return True
            time.sleep(poll_s)
        return False

    def snapshot_diff(self, first: str, second: str, **options: Any) -> Result:
        body = {"firstId": first, "secondId": second, **options}
        return self.post("/api/v1/server/snapshot-diffs", body)
