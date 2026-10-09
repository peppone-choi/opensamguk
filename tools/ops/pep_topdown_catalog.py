"""Read-only reader-compatible catalog and private cold-bundle companion. No pin rebake."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import stat

from game_server_recovery import checked_path, digest, json_bytes, read_json, require, write_private

SHA = re.compile(r'[0-9a-f]{64}')
ASSET = re.compile(r'(?:grid/L0/[0-9]+_[0-9]+\.bin\.gz|grid/L2\.bin\.gz|places\.json\.gz|defects\.json)')
MAX_MANIFEST = 2 * 1024**2
MAX_RAW = 16 * 1024**2


def canonical(value):
    def valid(node):
        require(not isinstance(node, float), 'floating identity value refused')
        if isinstance(node, dict):
            require(all(isinstance(k, str) and all(32 <= ord(c) <= 126 for c in k) for k in node), 'identity keys must be ASCII')
            for v in node.values(): valid(v)
        elif isinstance(node, list):
            for v in node: valid(v)
        else:
            require(node is None or type(node) in (str, int, bool), 'unsupported identity value')
    valid(value)
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()


def identity(manifest):
    keys = ('inputFingerprint', 'mapRelease', 'kitVersion', 'formatVersion')
    require(all(k in manifest for k in keys), 'incomplete bake identity')
    return hashlib.sha256(canonical({k: manifest[k] for k in keys})).hexdigest()


def catalog(root):
    root = checked_path(root, directory=True)
    device = root.stat().st_dev
    inventory, bakes = {}, {}
    for path in root.rglob('*'):
        info = path.lstat()
        require(info.st_dev == device and not path.is_symlink(), 'topdown filesystem/link refused')
        relative = path.relative_to(root).as_posix()
        if stat.S_ISDIR(info.st_mode):
            inventory[relative] = {'type': 'directory'}
        else:
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'topdown links/special files refused')
            checked_path(path)
            inventory[relative] = {'type': 'file', **digest(path)}
    for directory in root.iterdir():
        bake = directory.name
        require(directory.is_dir() and SHA.fullmatch(bake), 'unexpected topdown bake root entry')
        path = checked_path(directory / 'manifest.json')
        require(path.stat().st_size <= MAX_MANIFEST, 'topdown manifest too large')
        manifest = read_json(path)
        require(isinstance(manifest, dict), 'invalid topdown manifest')
        require(type(manifest.get('schemaVersion')) is int and manifest['schemaVersion'] == 1 and
                type(manifest.get('formatVersion')) is int and manifest['formatVersion'] == 1 and
                manifest.get('artifactId') == 'topdown-bake' and manifest.get('bakeId') == bake and
                type(manifest.get('chunkSize')) is int and manifest['chunkSize'] == 256 and
                type(manifest.get('undrawnTile')) is int and manifest['undrawnTile'] == 65535,
                'unsupported topdown reader contract')
        require(manifest.get('partial') is False and isinstance(manifest.get('inputFingerprint'), dict) and
                'region' in manifest['inputFingerprint'] and manifest['inputFingerprint']['region'] is None and identity(manifest) == bake,
                'partial or mismatched topdown identity')
        shape = manifest.get('shape', {})
        require(all(type(shape.get(k)) is int and shape[k] > 0 for k in ('cols', 'rows')), 'invalid topdown shape')
        files, chunks = manifest.get('files'), manifest.get('chunks')
        require(isinstance(files, list) and files and isinstance(chunks, list), 'invalid topdown file inventory')
        entries = {}
        for item in files:
            require(isinstance(item, dict), 'invalid topdown entry')
            name = item.get('file')
            require(isinstance(name, str) and ASSET.fullmatch(name) and name not in entries, 'unsafe/duplicate topdown asset')
            require(type(item.get('bytes')) is int and 0 <= item['bytes'] <= MAX_RAW and
                    all(isinstance(item.get(k), str) and SHA.fullmatch(item[k]) for k in ('sha256', 'rawSha256')) and
                    item.get('compression') == ('gzip' if name.endswith('.gz') else 'none'), 'invalid topdown asset contract')
            asset = checked_path(directory / name)
            require(digest(asset) == {'size': item['bytes'], 'sha256': item['sha256']}, 'topdown transport mismatch')
            try:
                data = asset.read_bytes()
                raw = gzip.GzipFile(fileobj=io.BytesIO(data)).read(MAX_RAW + 1) if name.endswith('.gz') else data
            except (OSError, EOFError):
                require(False, 'corrupt topdown gzip')
            require(len(raw) <= MAX_RAW and hashlib.sha256(raw).hexdigest() == item['rawSha256'], 'topdown raw mismatch')
            if name.startswith('grid/L0/'):
                require(len(raw) == 262144, 'topdown L0 size mismatch')
            if name == 'grid/L2.bin.gz':
                overview = manifest.get('overview', {})
                require(all(type(overview.get(k)) is int and overview[k] > 0 for k in ('cols', 'rows')) and
                        len(raw) == 4 * overview['cols'] * overview['rows'], 'topdown L2 size mismatch')
            entries[name] = item
        public = [c for c in chunks if isinstance(c, dict) and 'file' in c] + [manifest.get(k) for k in ('overview', 'places', 'defects')]
        require(len(public) == len(entries) and all(isinstance(p, dict) and p.get('file') in entries and
                all(p.get(k) == entries[p['file']][k] for k in ('bytes', 'sha256', 'rawSha256')) for p in public),
                'topdown public/file-count mismatch')
        require(len({p['file'] for p in public}) == len(entries), 'duplicate public topdown asset')
        allowed = {'manifest.json', *entries}
        allowed_dirs = {str(p) for name in entries for p in Path(name).parents if str(p) != '.'}
        observed = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
        observed_dirs = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_dir()}
        require(observed == allowed and observed_dirs == allowed_dirs, 'extra/missing topdown entry')
        bakes[bake] = {'manifest': digest(path), 'files': {k: {'size': v['bytes'], 'sha256': v['sha256']} for k, v in entries.items()}}
    require(bakes, 'empty topdown catalog')
    return {'version': 1, 'bakes': bakes, 'inventory': inventory,
            'sha256': hashlib.sha256(canonical(inventory)).hexdigest()}


def preserve(source, bundle, expected):
    require(catalog(source) == expected, 'original topdown catalog drift')
    companion = bundle.with_name(bundle.name + '.topdown')
    require(not companion.exists() and not companion.is_symlink(), 'existing topdown companion refused')
    companion.mkdir(mode=0o700)
    write_private(companion / 'INCOMPLETE', b'Topdown copy incomplete.\n')
    tree = companion / 'tree'
    shutil.copytree(source, tree, symlinks=True)
    tree.chmod(0o700)
    for path in tree.rglob('*'):
        require(not path.is_symlink(), 'topdown copy link refused')
        path.chmod(0o700 if path.is_dir() else 0o600)
    require(catalog(source) == expected == catalog(tree), 'topdown copy/source drift')
    write_private(companion / 'manifest.json', json_bytes({'version': 1,
        'bundle_manifest_sha256': digest(bundle / 'manifest.json')['sha256'], 'catalog': expected}))
    (companion / 'INCOMPLETE').unlink()
    return tree


def companion(bundle, expected):
    root = checked_path(bundle.with_name(bundle.name + '.topdown'), directory=True, private=True)
    require({p.name for p in root.iterdir()} == {'manifest.json', 'tree'}, 'topdown companion incomplete/inventory mismatch')
    metadata = read_json(checked_path(root / 'manifest.json', private=True))
    require(metadata == {'version': 1, 'bundle_manifest_sha256': digest(bundle / 'manifest.json')['sha256'], 'catalog': expected},
            'topdown companion linkage drift')
    tree = checked_path(root / 'tree', directory=True, private=True)
    for p in tree.rglob('*'):
        require(p.stat().st_mode & 0o077 == 0, 'topdown companion permissions drift')
    require(catalog(tree) == expected, 'topdown companion bytes drift')
    return tree


def probe(recovery, api, selected, expected):
    def fetch(path):
        return recovery.docker.run(['container', 'exec', api, 'curl', '-fsS', '--max-time', '5', 'http://localhost:8081' + path])
    preview = json.loads(fetch('/api/map/preview'))
    require(preview.get('topdownBakeId') == selected, 'API selected bake probe mismatch')
    for bake, record in expected['bakes'].items():
        body = fetch('/api/map/topdown/' + bake + '/manifest.json')
        require({'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()} == record['manifest'], 'API manifest probe mismatch')
        for name, item in record['files'].items():
            body = fetch('/api/map/topdown/' + bake + '/' + name)
            require({'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()} == item, 'API asset probe mismatch')
