"""JSON-lines acceptance adapter; all API traffic uses the Python SDK."""
import json
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "sdk/python"))
from mapi_client import MapiClient, MapiError

lock = threading.Lock()
streams = {}


def emit(value):
    with lock:
        print(json.dumps(value), flush=True)


def subscribe(request):
    client = MapiClient(request["base"], request["token"], request.get("timeoutMs", 30000) / 1000)
    stream_id = request["streamId"]
    state = {"response": None, "closed": False}
    streams[stream_id] = state

    def opened(response):
        state["response"] = response
        if state["closed"]:
            response.close()
        emit({"id": request["id"], "status": 200, "body": {"subscribed": True}})

    def run():
        try:
            for event in client.stream(cursor=request.get("cursor"), types=request.get("types"),
                                       world=request.get("world"), on_open=opened,
                                       on_gap=lambda seq: emit({"streamId": stream_id,
                                           "event": {"gap": True, "droppedUpToSeq": seq}})):
                emit({"streamId": stream_id, "event": {"gap": False, "id": event.id,
                      "event": event.event, "data": event.data}})
        except Exception as error:
            if not state["closed"]:
                emit({"streamId": stream_id, "error": str(error)})
                if state["response"] is None:
                    failure(request, error)
        finally:
            emit({"streamId": stream_id, "closed": True})

    threading.Thread(target=run, daemon=True).start()


def failure(request, error):
    if isinstance(error, MapiError):
        emit({"id": request["id"], "status": error.status,
              "body": {"error": {"code": error.code, "message": error.message}}})
    else:
        emit({"id": request["id"], "error": str(error)})


for line in sys.stdin:
    request = json.loads(line)
    try:
        if request["command"] == "subscribe":
            subscribe(request)
        elif request["command"] == "close":
            state = streams.pop(request["streamId"], None)
            if state:
                state["closed"] = True
                if state["response"]:
                    threading.Thread(target=state["response"].close, daemon=True).start()
            emit({"id": request["id"], "status": 200, "body": {"closed": True}})
        else:
            client = MapiClient(request["base"], request["token"], request.get("timeoutMs", 30000) / 1000)
            if request["operationId"] == "diffSnapshots" and all(
                    key in request.get("body", {}) for key in ("firstId", "secondId")):
                arguments = dict(request.get("body", {}))
                result = client.snapshot_diff(arguments.pop("firstId"), arguments.pop("secondId"), **arguments)
            else:
                result = client.get(request["path"]) if request["method"] == "GET" else client.post(
                    request["path"], request.get("body", {}))
            emit({"id": request["id"], "status": result.status, "body": result.body})
    except Exception as error:
        failure(request, error)
