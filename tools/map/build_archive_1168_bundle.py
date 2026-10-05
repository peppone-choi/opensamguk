#!/usr/bin/env python3
"""Build/check the 1168-city release; older frozen releases are never rewritten.

--write is for preparing this reviewed release locally. A later roster/content
revision needs its own release decision, not an automatic runtime fallback.
"""
import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'data/map/han-world-v3-1168-artifacts-v1'
PREVIOUS = ROOT / 'data/map/han-world-v3-1133-artifacts-v1'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def encode(doc):
    return (json.dumps(doc, ensure_ascii=False, indent=2)+'\n').encode()

def outputs():
    catalog = json.loads((PREVIOUS/'catalog.json').read_bytes())
    catalog.update(artifactId='han-world-v3-1168', cityCount=1168,
                   sourceBaseCommit='c8962513164b6dbcaf29fd8e099e5cd0e5980db2')
    out = {}
    for entry in catalog['files']:
        data = (ROOT/entry['path']).read_bytes()
        # Preserve verified frozen bytes: zlib streams/header OS bytes can differ across
        # Python/platform versions. Content and catalog hash checks remain exact.
        existing = BUNDLE / f'blobs/{sha(data)}.json.gz'
        compressed = existing.read_bytes() if existing.exists() else gzip.compress(data, compresslevel=9, mtime=0)
        if gzip.decompress(compressed) != data:
            raise ValueError(f'corrupt frozen payload: {existing}')
        entry.update(sha256=sha(data), bytes=len(data), blob=f'blobs/{sha(data)}.json.gz', compressedSha256=sha(compressed))
        out[BUNDLE/entry['blob']] = compressed
    constants = json.loads((PREVIOUS/'runtime-constants.json').read_bytes())
    constants['variantId'] = 'han-world-v3-1168'
    for entry in constants['files']:
        entry['snapshot'] = entry['snapshot'].replace('1133','1168')
        source = (ROOT/entry['source']).read_text()
        snapshot = ('// Frozen 1168-identity release snapshot. Future generators must not overwrite.\n'+source.replace(Path(entry['source']).stem, Path(entry['snapshot']).stem)).encode()
        entry.update(sourceSha256=sha(source.encode()), snapshotSha256=sha(snapshot))
        out[ROOT/entry['snapshot']] = snapshot
    out[BUNDLE/'catalog.json'] = encode(catalog)
    out[BUNDLE/'runtime-constants.json'] = encode(constants)
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__);mode=p.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write',action='store_true');mode.add_argument('--check',action='store_true');a=p.parse_args()
    expected=outputs()
    if a.write:
        for path,data in expected.items():
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        for path in (BUNDLE/'blobs').glob('*.json.gz'):
            if path not in expected:path.unlink()
    problems=[str(p.relative_to(ROOT)) for p,b in expected.items() if not p.exists() or p.read_bytes()!=b]
    pins={'catalog':sha(expected[BUNDLE/'catalog.json']), 'constants':sha(expected[BUNDLE/'runtime-constants.json'])}
    if not a.write:
        if pins['catalog'] not in (ROOT/'infra/src/main/kotlin/opensamguk/infra/seed/Archive1168Artifacts.kt').read_text():problems.append('1168 Kotlin catalog pin')
        if pins['constants'] not in (ROOT/'infra/src/test/kotlin/opensamguk/infra/seed/ArchiveRuntimeConstantsIntegrityTest.kt').read_text():problems.append('1168 constants test pin')
    print(json.dumps({'pins':pins,'drift':problems}));return bool(problems)
if __name__=='__main__':raise SystemExit(main())
