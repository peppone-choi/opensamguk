#!/usr/bin/env python3
"""Verify CI ran every city shard, with no missing or repeated added city."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def check(root: Path) -> tuple[int, int]:
    paths = sorted(root.rglob("shard-*.json"))
    if not paths:
        raise ValueError("no city shard manifests")
    manifests = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    count = manifests[0]["shardCount"]
    if not isinstance(count, int) or count < 1:
        raise ValueError("invalid city shard count")
    indices = [manifest["shardIndex"] for manifest in manifests]
    if sorted(indices) != list(range(count)):
        raise ValueError(f"city shard indices incomplete or duplicated: {indices}; expected 0..{count - 1}")
    expected = manifests[0]["allCityIds"]
    if not expected or expected != sorted(set(expected)):
        raise ValueError("invalid complete city list")
    actual: list[int] = []
    for manifest in manifests:
        if manifest["shardCount"] != count or manifest["allCityIds"] != expected:
            raise ValueError("shards disagree on complete city roster or shard count")
        selected = manifest["selectedCityIds"]
        if not selected or selected != sorted(set(selected)):
            raise ValueError(f"shard {manifest['shardIndex']} has invalid city selection")
        actual.extend(selected)
    if sorted(actual) != expected:
        raise ValueError("city shard union differs from complete city roster (missing or duplicate city)")
    return count, len(expected)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    count, cities = check(args.root)
    print(f"city shards complete: {count} shards, {cities} distinct added cities")


if __name__ == "__main__":
    main()
