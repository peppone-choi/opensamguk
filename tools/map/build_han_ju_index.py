#!/usr/bin/env python3
"""Expose scenario 州 assignments without changing pinned historical tile bytes."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.scenario.build_han_world import assign_ju_to_juns

OUT = ROOT / 'data/map/han-ju-index-v1.json'


def build() -> dict:
    by_hash = {}
    for bundle in sorted((ROOT / 'data/map').glob('han-world-v3-*-artifacts-v1')):
        catalog = json.loads((bundle / 'catalog.json').read_text())
        entry = next(row for row in catalog['files'] if row['path'] == 'data/map/han-tiles.json')
        blob_path = bundle / entry['blob']
        blob = blob_path.read_bytes()
        tiles_bytes = gzip.decompress(blob) if blob_path.suffix == '.gz' else blob
        digest = hashlib.sha256(tiles_bytes).hexdigest()
        if digest != entry['sha256']:
            raise ValueError(f'{bundle.name}: tile digest mismatch')
        tiles = json.loads(tiles_bytes)
        if len(tiles['juns']) != len(tiles['parentRegions']):
            raise ValueError(f'{bundle.name}: 郡 and parent index mismatch')
        for jun, parent in zip(tiles['juns'], tiles['parentRegions']):
            if jun['nameCh'] != parent['nameCh']:
                raise ValueError(f'{bundle.name}: parent name mismatch')
        assigned = assign_ju_to_juns(tiles['juns'])
        if digest in by_hash and by_hash[digest] != assigned:
            raise ValueError(f'{bundle.name}: conflicting assignment for same tile hash')
        by_hash[digest] = assigned
    return {'schemaVersion': 1, 'source': 'tools/scenario/build_han_world.py assign_ju_to_juns',
            'byTerrainSha256': dict(sorted(by_hash.items()))}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = json.dumps(build(), ensure_ascii=False, separators=(',', ':')) + '\n'
    if args.check:
        if OUT.read_text() != content:
            raise SystemExit('han-ju-index-v1.json drift')
        print('han-ju-index-v1.json: no drift')
    else:
        OUT.write_text(content)
        print(OUT.relative_to(ROOT))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
