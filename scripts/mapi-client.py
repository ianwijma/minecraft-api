#!/usr/bin/env python3
"""MAPI local HTTP API client example (stdlib only).

Usage:
    export MAPI_HTTP_TOKEN='...'
    python3 scripts/mapi-client.py health
    python3 scripts/mapi-client.py info
    python3 scripts/mapi-client.py status [--base http://127.0.0.1:25586]

The token comes from the MAPI_HTTP_TOKEN environment variable; it is never
logged or echoed. The API is loopback-only and read-only.
"""

import argparse
import json
import os
import sys
import urllib.request
import urllib.error

ENDPOINTS = {
    "health": "/api/v1/health",
    "info": "/api/v1/info",
    "status": "/api/v1/server/status",
}


def request(base, path, token, timeout=5.0):
    req = urllib.request.Request(base.rstrip("/") + path, method="GET")
    req.add_header("Authorization", f"Bearer {os.environ['MAPI_HTTP_TOKEN']}")
    req.add_header("Host", "127.0.0.1")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description="MAPI local HTTP API client")
    parser.add_argument("endpoint", choices=sorted(ENDPOINTS))
    parser.add_argument("--base", default="http://127.0.0.1:25586")
    args = parser.parse_args()

    if not os.environ.get("MAPI_HTTP_TOKEN"):
        sys.exit("error: set the MAPI_HTTP_TOKEN environment variable first")

    url = args.base.rstrip("/") + ENDPOINTS[args.endpoint]
    try:
        status, body = request(args.base, ENDPOINTS[args.endpoint], os.environ["MAPI_HTTP_TOKEN"])
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        sys.exit(f"error: HTTP {e.code}: {detail}")
    except OSError as e:
        sys.exit(f"error: cannot reach {args.base} ({e}) — is the server running with http.enabled=true?")

    print(json.dumps(body, indent=2, sort_keys=False))
    return 0 if status == 200 else 1


if __name__ == "__main__":
    sys.exit(main())