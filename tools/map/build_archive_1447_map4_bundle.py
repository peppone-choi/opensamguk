#!/usr/bin/env python3
"""Check the frozen fourfold-grid 1447 release; it is never rewritten.

Until 2026-09-27 this release tracked the live artifacts. The 1428 release
(tools/map/build_archive_1428_bundle.py) now does; this one only proves its own
blobs, catalog and constant snapshots are intact.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'data/map/han-world-v3-1447-map4-artifacts-v1'
ARTIFACT_ID = 'han-world-v3-1447-map4'
LOADER = ROOT / 'infra/src/main/kotlin/opensamguk/infra/seed/Archive1447Map4Artifacts.kt'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def problems() -> tuple[str, list[str]]:
    catalog_bytes = (BUNDLE / 'catalog.json').read_bytes()
    catalog = json.loads(catalog_bytes)
    constants = json.loads((BUNDLE / 'runtime-constants.json').read_bytes())
    found = []
    if catalog['artifactId'] != ARTIFACT_ID or catalog['cityCount'] != 1447:
        found.append('1447-map4 frozen catalog identity')
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
        found.append('1447-map4 Kotlin catalog pin')
    return pin, found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.write:
        parser.error('1447-map4 is frozen; write a new variant instead (build_archive_1428_bundle.py)')
    pin, drift = problems()
    print(json.dumps({'catalogSha256': pin, 'drift': drift}))
    return int(bool(drift))


if __name__ == '__main__':
    raise SystemExit(main())
