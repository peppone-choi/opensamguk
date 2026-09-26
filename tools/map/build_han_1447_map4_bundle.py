#!/usr/bin/env python3
"""Build/check the fourfold-grid 1447 release without touching frozen 1447."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIOR = ROOT / 'data/map/han-world-v3-1447-artifacts-v1'
BUNDLE = ROOT / 'data/map/han-world-v3-1447-map4-artifacts-v1'
ARTIFACT_ID = 'han-world-v3-1447-map4'
SOURCE_BASE_COMMIT = 'd57b9ac55460e5409c7b4e1247c3af5aa9fe5b26'
WORLD_MANIFEST = 'data/map/han-world-v3-manifest-v1.json'
RENAMED_SOURCES = {
    'HanWorldV3CityConst': 'ArchiveCityConst',
    'HanWorldV3GateIndex': 'ArchiveGateIndex',
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encoded(data: dict) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode()


def outputs() -> dict[Path, bytes]:
    catalog = json.loads((PRIOR / 'catalog.json').read_bytes())
    frozen_catalog = json.loads((BUNDLE / 'catalog.json').read_bytes())
    frozen_manifest = next(entry for entry in frozen_catalog['files'] if entry['path'] == WORLD_MANIFEST)
    frozen_compressed = (BUNDLE / frozen_manifest['blob']).read_bytes()
    frozen_data = gzip.decompress(frozen_compressed)
    if sha(frozen_compressed) != frozen_manifest['compressedSha256'] or sha(frozen_data) != frozen_manifest['sha256']:
        raise ValueError('corrupt frozen map4 world manifest')
    catalog.update(artifactId=ARTIFACT_ID, sourceBaseCommit=SOURCE_BASE_COMMIT)
    catalog['files'].append({'path': 'data/map/han-land-roads-v1.json'})
    result = {}
    for entry in catalog['files']:
        # The bundled manifest contributes to persisted spatial pins. Its
        # original bytes stay frozen when current code-constant hashes change.
        data = frozen_data if entry['path'] == WORLD_MANIFEST else (ROOT / entry['path']).read_bytes()
        # Preserve the checked-in gzip stream when its payload matches. zlib's
        # output differs across platforms even with a fixed mtime, while the
        # release catalog pins the exact compressed bytes.
        existing = BUNDLE / f'blobs/{sha(data)}.json.gz'
        compressed = existing.read_bytes() if existing.exists() else gzip.compress(data, compresslevel=9, mtime=0)
        if gzip.decompress(compressed) != data:
            raise ValueError(f'corrupt map4 payload: {existing}')
        entry.update(sha256=sha(data), bytes=len(data), blob=f'blobs/{sha(data)}.json.gz',
                     compressedSha256=sha(compressed))
        result[BUNDLE / entry['blob']] = compressed
    constants = json.loads((PRIOR / 'runtime-constants.json').read_bytes())
    constants['variantId'] = ARTIFACT_ID
    for entry in constants['files']:
        original_name = Path(entry['source']).stem
        current_name = RENAMED_SOURCES[original_name]
        current_path = Path(entry['source']).with_name(f'{current_name}.kt')
        source = (ROOT / current_path).read_text()
        # Reconstruct the historical source bytes used by the frozen release.
        source = source.replace(f'object {current_name}', f'object {original_name}', 1)
        source = source.replace('HistoricalCityConstVariant', 'HanCityConstVariant')
        snapshot_path = Path(entry['snapshot'].replace('1447', '1447Map4'))
        snapshot_name = snapshot_path.stem
        snapshot = ('// Frozen 1447-map4 release snapshot. Future generators must not overwrite.\n'
                    + source.replace(original_name, snapshot_name)).encode()
        entry.update(sourceSha256=sha(source.encode()), snapshot=str(snapshot_path),
                     snapshotSha256=sha(snapshot))
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
    if args.check:
        pin = sha(expected[BUNDLE / 'catalog.json'])
        loader = ROOT / 'infra/src/main/kotlin/opensamguk/infra/seed/Han1447Map4Artifacts.kt'
        if not loader.is_file() or pin not in loader.read_text():
            problems.append('1447-map4 Kotlin catalog pin')
    print(json.dumps({'catalogSha256': sha(expected[BUNDLE / 'catalog.json']),
                      'blobCount': sum(path.suffix == '.gz' for path in expected),
                      'drift': problems}))
    return int(bool(problems))


if __name__ == '__main__':
    raise SystemExit(main())
