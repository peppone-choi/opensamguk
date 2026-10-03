#!/usr/bin/env python3
"""Build/check current neutral inputs; historical release bytes are never rewritten."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIOR = ROOT / 'data/map/han-world-v3-1428-artifacts-v1'
BUNDLE = ROOT / 'data/map/province-world-20261003-artifacts'
ARTIFACT_ID = 'province-world-20261003'
SOURCE_BASE_COMMIT = 'e430921d2ac5e33e0766745c673848d22c270ed7'
LOADER = ROOT / 'infra/src/main/kotlin/opensamguk/infra/seed/ProvinceWorldArtifacts.kt'
CONSTANTS = (
    ('common/src/main/kotlin/opensamguk/common/constants/ArchiveCityConst.kt',
     'common/src/main/kotlin/opensamguk/common/constants/ProvinceCityConst.kt'),
    ('common/src/main/kotlin/opensamguk/common/constants/ArchiveGateIndex.kt',
     'common/src/main/kotlin/opensamguk/common/constants/ProvinceGateIndex.kt'),
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def outputs():
    prior = json.loads((PRIOR / 'catalog.json').read_bytes())
    catalog = dict(artifactId=ARTIFACT_ID, cityCount=1428, files=[], logicalMapName=prior['logicalMapName'],
                   schemaVersion=1, sourceBaseCommit=SOURCE_BASE_COMMIT)
    result = {}
    for entry in prior['files']:
        path = entry['path'].replace('data/map/han-tiles.json', 'data/map/province-tiles.json')
        data = (ROOT / path).read_bytes()
        blob = f'blobs/{sha(data)}.json.gz'
        target = BUNDLE / blob
        compressed = target.read_bytes() if target.exists() else gzip.compress(data, compresslevel=9, mtime=0)
        if gzip.decompress(compressed) != data:
            raise ValueError(f'corrupt neutral release payload: {target}')
        catalog['files'].append(dict(blob=blob, bytes=len(data), compressedSha256=sha(compressed),
                                     path=path, sha256=sha(data)))
        result[target] = compressed
    constants = dict(variantId=ARTIFACT_ID, files=[])
    for source_path, snapshot_path in CONSTANTS:
        source = (ROOT / source_path).read_text()
        source_name, snapshot_name = Path(source_path).stem, Path(snapshot_path).stem
        if snapshot_name in source:
            raise ValueError(f'generator source already names the frozen snapshot: {source_path}')
        snapshot = ('// Frozen neutral release snapshot. Future generators must not overwrite.\n' +
                    source.replace(source_name, snapshot_name)).encode()
        constants['files'].append(dict(source=source_path, sourceSha256=sha(source.encode()),
                                      snapshot=snapshot_path, snapshotSha256=sha(snapshot)))
        result[ROOT / snapshot_path] = snapshot
    result[BUNDLE / 'catalog.json'] = encode(catalog)
    result[BUNDLE / 'runtime-constants.json'] = encode(constants)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()
    expected = outputs()
    if args.write:
        for path, data in expected.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        for path in (BUNDLE / 'blobs').glob('*.json.gz'):
            if path not in expected:
                path.unlink()
    drift = [str(path.relative_to(ROOT)) for path, data in expected.items()
             if not path.is_file() or path.read_bytes() != data]
    pin = sha(expected[BUNDLE / 'catalog.json'])
    if args.check and (not LOADER.is_file() or pin not in LOADER.read_text()):
        drift.append('neutral Kotlin catalog pin')
    print(json.dumps(dict(catalogSha256=pin,
                         constantsSha256=sha(expected[BUNDLE / 'runtime-constants.json']), drift=drift)))
    return int(bool(drift))


if __name__ == '__main__':
    raise SystemExit(main())
