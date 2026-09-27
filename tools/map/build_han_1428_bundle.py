#!/usr/bin/env python3
"""Build/check the 1428-city release (2026-09-27): duplicate synthetic counties retired, homonym gaps added.

Same file set and fourfold grid as 1447-map4; 1447 and 1447-map4 stay frozen. `--check` compares the
current (live) artifacts with this bundle, the way the newest release is always checked.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIOR = ROOT / 'data/map/han-world-v3-1447-map4-artifacts-v1'
BUNDLE = ROOT / 'data/map/han-world-v3-1428-artifacts-v1'
ARTIFACT_ID = 'han-world-v3-1428'
CITY_COUNT = 1428
# main 조상 커밋이어야 한다 — 작업 브랜치 커밋은 squash 머지로 고아가 된다.
SOURCE_BASE_COMMIT = 'c7ff8430b99123abfbde5679b4eff8ed5d33ebba'
CONSTANTS = (
    ('common/src/main/kotlin/opensamguk/common/constants/ArchiveCityConst.kt',
     'common/src/main/kotlin/opensamguk/common/constants/Archive1428CityConst.kt'),
    ('common/src/main/kotlin/opensamguk/common/constants/ArchiveGateIndex.kt',
     'common/src/main/kotlin/opensamguk/common/constants/Archive1428GateIndex.kt'),
)
LOADER = ROOT / 'infra/src/main/kotlin/opensamguk/infra/seed/Archive1428Artifacts.kt'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encoded(data: dict) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode()


def outputs() -> dict[Path, bytes]:
    prior = json.loads((PRIOR / 'catalog.json').read_bytes())
    catalog = {'artifactId': ARTIFACT_ID, 'cityCount': CITY_COUNT, 'files': [],
               'logicalMapName': prior['logicalMapName'], 'schemaVersion': prior['schemaVersion'],
               'sourceBaseCommit': SOURCE_BASE_COMMIT}
    result = {}
    for prior_entry in prior['files']:
        path = prior_entry['path']
        data = (ROOT / path).read_bytes()
        # 이미 커밋된 gzip 스트림은 페이로드가 같으면 그대로 둔다 — zlib 출력은 플랫폼마다 다르다.
        existing = BUNDLE / f'blobs/{sha(data)}.json.gz'
        compressed = existing.read_bytes() if existing.exists() else gzip.compress(data, compresslevel=9, mtime=0)
        if gzip.decompress(compressed) != data:
            raise ValueError(f'corrupt 1428 payload: {existing}')
        catalog['files'].append({'blob': f'blobs/{sha(data)}.json.gz', 'bytes': len(data),
                                 'compressedSha256': sha(compressed), 'path': path, 'sha256': sha(data)})
        result[BUNDLE / f'blobs/{sha(data)}.json.gz'] = compressed
    constants = {'variantId': ARTIFACT_ID, 'files': []}
    for source_path, snapshot_path in CONSTANTS:
        source = (ROOT / source_path).read_text()
        source_name, snapshot_name = Path(source_path).stem, Path(snapshot_path).stem
        if snapshot_name in source:
            raise ValueError(f'source already names the snapshot: {source_path}')
        snapshot = ('// Frozen 1428 release snapshot. Future generators must not overwrite.\n'
                    + source.replace(source_name, snapshot_name)).encode()
        constants['files'].append({'source': source_path, 'sourceSha256': sha(source.encode()),
                                   'snapshot': snapshot_path, 'snapshotSha256': sha(snapshot)})
        result[ROOT / snapshot_path] = snapshot
    result[BUNDLE / 'catalog.json'] = encoded(catalog)
    result[BUNDLE / 'runtime-constants.json'] = encoded(constants)
    return result


def main() -> int:
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
    problems = [str(path.relative_to(ROOT)) for path, data in expected.items()
                if not path.is_file() or path.read_bytes() != data]
    pin = sha(expected[BUNDLE / 'catalog.json'])
    if args.check and (not LOADER.is_file() or pin not in LOADER.read_text()):
        problems.append('1428 Kotlin catalog pin')
    print(json.dumps({'catalogSha256': pin, 'constantsSha256': sha(expected[BUNDLE / 'runtime-constants.json']),
                      'blobCount': sum(path.suffix == '.gz' for path in expected), 'drift': problems}))
    return int(bool(problems))


if __name__ == '__main__':
    raise SystemExit(main())
