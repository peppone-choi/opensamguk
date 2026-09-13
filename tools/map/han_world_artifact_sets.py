"""Freeze and validate the nine inputs of the historical Han strategic loader.

This is an offline provenance contract, not runtime variant selection. Canonical
logical paths and bytes remain intact; no current-checkout fallback is permitted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

TILES = 'data/map/han-tiles.json'
WATER = 'data/map/han-water-topology-v1.json'
LEDGER = 'data/curated/han/water-topology-adjudications-v1.json'
STRATEGIC = 'data/map/han-strategic-topology-manifest-v1.json'
WORLD = 'infra/src/main/resources/map/han-world-v3.json'
WORLD_MANIFEST = 'data/map/han-world-v3-manifest-v1.json'
SELECTION = 'data/curated/han/route-node-selection-v1.json'
MIGRATION = 'data/curated/han/route-node-migration-v1.json'
LEGACY = 'infra/src/main/resources/map/han-780-v1.json'
PATHS = (TILES, WATER, LEDGER, STRATEGIC, WORLD, WORLD_MANIFEST, SELECTION, MIGRATION, LEGACY)
SOURCES = {
    'han-world-v3-832': ('cf5a77806212c1d8d08d617b292a6fb5fd7cc496', 832),
    'han-world-v3-835': ('91fad09734e472b72a3b8720b0bce9f5a7ac3ae5', 835),
}
APPROVED_CATALOG_SHA256 = 'bbf8efcb3691a4670ea15801bc379486926fbdc34528d07c0d0a9c0e7b17dd53'

def sha(data): return hashlib.sha256(data).hexdigest()
def encode(value): return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
def require(condition, message):
    if not condition: raise ValueError(message)
def decode(data):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, 'duplicate JSON field')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)

def identities(rows, id_field):
    result = [(r[id_field], r['routeNodeKey'], r['physicalPlaceRef']) for r in rows]
    require(all(type(i) is int and i > 0 and isinstance(k, str) and k and isinstance(p, str) and p for i,k,p in result), 'invalid identity')
    require(all(len({row[col] for row in result}) == len(result) for col in range(3)), 'duplicate identity')
    return sorted(result)

def validate(raw, read_blob, approved_hash):
    require(sha(raw) == approved_hash, 'unapproved catalog hash')
    catalog = decode(raw)
    require(catalog['schemaVersion'] == 1 and catalog['catalogId'] == 'han-world-strategic-artifact-sets-v1', 'catalog domain')
    variants = catalog['variants']
    require([v['variantId'] for v in variants] == list(SOURCES), 'variant identity/order')
    result = {}
    for variant in variants:
        key = variant['variantId']; commit, count = SOURCES[key]
        require(variant['sourceCommit'] == commit and variant['logicalMapName'] == 'han-world-v3', 'source domain')
        entries = variant['files']
        require(len(entries) == len(PATHS) and {f['path'] for f in entries} == set(PATHS), 'artifact path set')
        blobs = {}; hashes = {}
        for entry in entries:
            require(entry['blob'] == 'blobs/' + entry['sha256'] + '.json', 'blob path')
            data = read_blob(entry['blob'])
            require(len(data) == entry['bytes'] and sha(data) == entry['sha256'], 'blob hash/length mismatch')
            blobs[entry['path']] = decode(data); hashes[entry['path']] = sha(data)
        strategic = blobs[STRATEGIC]
        require(strategic['schemaVersion'] == 1 and strategic['manifestId'] == 'han-strategic-topology-manifest-v1', 'strategic domain')
        for field, path in [('baseHanTiles',TILES),('waterTopology',WATER),('adjudications',LEDGER)]:
            pin = strategic['files'][field]
            entry = next(e for e in entries if e['path'] == path)
            require(pin == {k:entry[k] for k in ('path','bytes','sha256')}, 'strategic inner pin')
        manifest = blobs[WORLD_MANIFEST]
        require(manifest['worldVersion'] == 'han-world-v3' and manifest['manifestId'] == 'han-world-v3-manifest-v1', 'world domain')
        for field,path in [('selectionSha256',SELECTION),('migrationSha256',MIGRATION),('hanTilesSha256',TILES),('legacy780Sha256',LEGACY)]:
            require(manifest['inputs'][field] == hashes[path], 'world input pin')
        require(manifest['outputs']['worldJsonSha256'] == hashes[WORLD], 'world JSON pin')
        selection = blobs[SELECTION]; migration = blobs[MIGRATION]
        require(selection['worldVersion'] == 'han-world-v3' and selection['reviewState'] == 'APPROVED', 'selection domain')
        require(migration['targetWorldVersion'] == 'han-world-v3' and migration['mode'] == 'NEW_WORLD_ONLY' and migration['sourceSelectionId'] == selection['selectionId'] and migration['sourceSelectionSha256'] == hashes[SELECTION], 'migration selection pin')
        require(all(r['reviewState'] == 'APPROVED' for r in selection['routeNodes']), 'unapproved selection')
        runtime = identities(blobs[WORLD]['cities'], 'id')
        require(len(runtime) == count and {i for i,_,_ in runtime} == set(range(1,count+1)), 'runtime identity roster')
        require(runtime == identities(selection['routeNodes'], 'numericCityId') == identities(manifest['routeNodes'], 'numericCityId'), 'route identity mismatch')
        require(variant['cityCount'] == count and variant['identitySha256'] == sha(encode(runtime)), 'catalog identity pin')
        result[key] = runtime
    require(set(result['han-world-v3-832']) <= set(result['han-world-v3-835']), 'existing identity changed')
    return result

def freeze(root, destination):
    """Read only immutable git objects; never substitute working-tree files."""
    blobs = {}; variants = []
    for key,(commit,count) in SOURCES.items():
        entries = []; world = None
        for path in PATHS:
            data = subprocess.check_output(['git','show',commit + ':' + path], cwd=root)
            digest = sha(data); blob = 'blobs/' + digest + '.json'
            blobs[blob] = data
            entries.append(dict(path=path,sha256=digest,bytes=len(data),blob=blob))
            if path == WORLD: world = decode(data)
        variants.append(dict(variantId=key,sourceCommit=commit,logicalMapName='han-world-v3',cityCount=count,identitySha256=sha(encode(identities(world['cities'],'id'))),files=entries))
    raw = encode(dict(schemaVersion=1,catalogId='han-world-strategic-artifact-sets-v1',variants=variants))
    validate(raw, blobs.__getitem__, APPROVED_CATALOG_SHA256)
    for path,data in {**blobs,'catalog.json':raw}.items():
        target = destination / path
        if target.exists(): require(target.read_bytes() == data, 'refusing to overwrite frozen artifact')
    for path,data in {**blobs,'catalog.json':raw}.items():
        target = destination / path; target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists(): target.write_bytes(data)
    return raw

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--freeze',action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    directory = root / 'data/map/han-world-artifacts-v1'
    if args.freeze: freeze(root,directory)
    rows = validate((directory/'catalog.json').read_bytes(), lambda p:(directory/p).read_bytes(), APPROVED_CATALOG_SHA256)
    print(json.dumps({key:len(value) for key,value in rows.items()}))
