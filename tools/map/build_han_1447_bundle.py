#!/usr/bin/env python3
"""Build/check the 1447-city release; older frozen releases are never rewritten.

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
BUNDLE = ROOT / 'data/map/han-world-v3-1447-artifacts-v1'
PREVIOUS = ROOT / 'data/map/han-world-v3-1224-artifacts-v1'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def encode(doc):
    return (json.dumps(doc, ensure_ascii=False, indent=2)+'\n').encode()

def outputs():
    catalog = json.loads((PREVIOUS/'catalog.json').read_bytes())
    catalog.update(artifactId='han-world-v3-1447', cityCount=1447,
                   sourceBaseCommit='016a089e5ed5dad367f5555ec30931cf39730133')
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
    constants['variantId'] = 'han-world-v3-1447'
    for entry in constants['files']:
        entry['snapshot'] = entry['snapshot'].replace('1224','1447')
        source = (ROOT/entry['source']).read_text()
        snapshot = ('// Frozen 1447-identity release snapshot. Future generators must not overwrite.\n'+source.replace(Path(entry['source']).stem, Path(entry['snapshot']).stem)).encode()
        entry.update(sourceSha256=sha(source.encode()), snapshotSha256=sha(snapshot))
        out[ROOT/entry['snapshot']] = snapshot
    out[BUNDLE/'catalog.json'] = encode(catalog)
    out[BUNDLE/'runtime-constants.json'] = encode(constants)
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__);mode=p.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write',action='store_true');mode.add_argument('--check',action='store_true');a=p.parse_args()
    if a.write:
        p.error('1447 is frozen; write a new variant instead')
    catalog_bytes=(BUNDLE/'catalog.json').read_bytes()
    constants_bytes=(BUNDLE/'runtime-constants.json').read_bytes()
    catalog=json.loads(catalog_bytes);constants=json.loads(constants_bytes)
    problems=[]
    pins={'catalog':sha(catalog_bytes), 'constants':sha(constants_bytes)}
    if catalog['artifactId']!='han-world-v3-1447' or catalog['cityCount']!=1447:
        problems.append('1447 frozen catalog identity')
    for entry in catalog['files']:
        blob=BUNDLE/entry['blob']
        if not blob.is_file():
            problems.append(str(blob.relative_to(ROOT)));continue
        compressed=blob.read_bytes()
        if sha(compressed)!=entry['compressedSha256']:
            problems.append(str(blob.relative_to(ROOT)));continue
        data=gzip.decompress(compressed)
        if len(data)!=entry['bytes'] or sha(data)!=entry['sha256']:
            problems.append(str(blob.relative_to(ROOT)))
    for entry in constants['files']:
        snapshot=ROOT/entry['snapshot']
        if not snapshot.is_file() or sha(snapshot.read_bytes())!=entry['snapshotSha256']:
            problems.append(str(snapshot.relative_to(ROOT)))
    if pins['catalog'] not in (ROOT/'infra/src/main/kotlin/opensamguk/infra/seed/Archive1447Artifacts.kt').read_text():problems.append('1447 Kotlin catalog pin')
    if pins['constants'] not in (ROOT/'infra/src/test/kotlin/opensamguk/infra/seed/ArchiveRuntimeConstantsIntegrityTest.kt').read_text():problems.append('1447 constants test pin')
    print(json.dumps({'pins':pins,'drift':problems}));return bool(problems)
if __name__=='__main__':raise SystemExit(main())
