#!/usr/bin/env python3
"""Check that the administrative jurisdiction pin still matches its committed inputs."""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PIN = ROOT / "data/curated/han/administrative-axis-pin.json"


def check() -> int:
    pin = json.loads(PIN.read_text(encoding="utf-8"))
    stale = []
    for path_key, hash_key in (("worldPath", "worldSha256"), ("zhouAxisPath", "zhouAxisSha256")):
        path = ROOT / pin[path_key]
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != pin[hash_key]:
            stale.append(f"{path_key}: {pin[path_key]} (actual {actual}, pinned {pin[hash_key]})")
    if stale:
        print("STALE administrative axis pin; review the new map and jurisdiction counts before repinning:")
        print("\n".join(stale))
        return 1
    print("OK administrative axis pin")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True)
    parser.parse_args()
    raise SystemExit(check())
