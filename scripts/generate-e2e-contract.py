#!/usr/bin/env python3
"""Export OpenAPI for the dependency-free Node E2E harness."""
import argparse
import hashlib
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/openapi.yaml"
OUTPUT = ROOT / "e2e/api/contract.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    raw = SOURCE.read_bytes()
    text = json.dumps({"sourceSha256": hashlib.sha256(raw).hexdigest(),
                       "openapi": yaml.safe_load(raw)}, indent=2) + "\n"
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != text:
            raise SystemExit("E2E contract drift: run python3 scripts/generate-e2e-contract.py")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(text)


if __name__ == "__main__":
    main()
