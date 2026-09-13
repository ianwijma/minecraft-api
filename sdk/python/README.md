# MAPI Python SDK

Dependency-free (stdlib-only) Python client for the MAPI local HTTP API,
with the hand-written helper layer from the target architecture (§10):
token-file auth, discovery parsing (untrusted-input hardened), task
polling with idempotent creation, and event cursors with gap detection.

## Usage

```python
from mapi.client import MapiClient

# Resolve a running instance via its discovery + token files:
client = MapiClient.from_game_dir("/path/to/instance/run")

# ...or explicitly:
client = MapiClient.from_url("http://127.0.0.1:25586", "/path/to/mcapi/token")

print(client.ready())
print(client.server_status())
print(client.players(fields=["name", "id"]))

# Tasks: create (idempotent), wait, cancel
task = client.create_task("wait-for-tick", {"targetTick": 1000},
                          idempotency_key="my-op-1")
task = client.wait_task(task["id"], timeout_s=30)

# Events: polling generator with cursor resume + explicit gap reporting
for event, gap in client.follow_events(after=0):
    if gap:
        print("history gap", gap)
    else:
        print(event["seq"], event["eventType"])
```

## Tests

```bash
python3 -m unittest discover -s tests    # or: ./gradlew sdkPythonTest
```

The tests run against a local stub server implementing the documented
contract (no JVM needed).
