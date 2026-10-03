#!/usr/bin/env python3
"""Check the frozen fourfold-grid 1447 release; it is never rewritten.

Current inputs belong to build_province_world_bundle.py. This command verifies only
this release's approved catalog, payloads and constant snapshots.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'data/map/han-world-v3-1428-artifacts-v1'
ARTIFACT_ID = 'han-world-v3-1428'
LOADER = ROOT / 'infra/src/main/kotlin/opensamguk/infra/seed/Archive1428Artifacts.kt'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def problems() -> tuple[str, list[str]]:
    catalog_bytes = (BUNDLE / 'catalog.json').read_bytes()
    catalog = json.loads(catalog_bytes)
    constants = json.loads((BUNDLE / 'runtime-constants.json').read_bytes())
    found = []
    if sha(catalog_bytes) != '346325cae5f50ec0fe9fcd3ba37d771c2627e14ae4d07512e6fdee946314c254':
        found.append('1428 approved catalog pin')
    if sha((BUNDLE / 'runtime-constants.json').read_bytes()) != '80f2ba758bfd54e78f3e31e818ae842636b557cfc326a3343f956ea5bda676c5':
        found.append('1428 approved constants pin')
    if catalog['artifactId'] != ARTIFACT_ID or catalog['cityCount'] != 1428:
        found.append('1428 frozen catalog identity')
    for entry in catalog['files']:
        blob = BUNDLE / entry['blob']
        if not blob.is_file() or sha(blob.read_bytes()) != entry['compressedSha256']:
            found.append(str(blob.relative_to(ROOT)))
            continue
        data = gzip.decompress(blob.read_bytes())
        if len(data) != entry['bytes'] or sha(data) != entry['sha256']:
            found.append(str(blob.relative_to(ROOT)))
    for entry in constants['files']:
        snapshot = ROOT / entry['snapshot']
        if not snapshot.is_file() or sha(snapshot.read_bytes()) != entry['snapshotSha256']:
            found.append(str(snapshot.relative_to(ROOT)))
    pin = sha(catalog_bytes)
    if not LOADER.is_file() or pin not in LOADER.read_text():
        found.append('1428 Kotlin catalog pin')
    return pin, found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.write:
        parser.error('1428 is frozen; write a new variant instead (build_province_world_bundle.py)')
    pin, drift = problems()
    print(json.dumps({'catalogSha256': pin, 'drift': drift}))
    return int(bool(drift))


if __name__ == '__main__':
    raise SystemExit(main())
