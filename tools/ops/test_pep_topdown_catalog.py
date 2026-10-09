"""Synthetic reader-contract bytes; no rendered-map, CI admission or live-service claims."""
import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from game_server_recovery import RecoveryError
import pep_topdown_catalog as topdown


def make_bake(root, kit='one', pins=None):
    manifest = {'schemaVersion': 1, 'formatVersion': 1, 'artifactId': 'topdown-bake',
        'mapRelease': 'province-world-20261003', 'kitVersion': kit, 'chunkSize': 256,
        'undrawnTile': 65535, 'partial': False, 'shape': {'cols': 256, 'rows': 256},
        'inputFingerprint': pins or {'region': None, 'tilesSha256': 'a' * 64, 'worldJsonSha256': 'b' * 64, 'roadsSha256': 'c' * 64}}
    bake = topdown.identity(manifest)
    manifest['bakeId'] = bake
    root = Path(root) / bake
    root.mkdir(parents=True)
    files, public = [], {}
    for name, raw in {'grid/L0/0_0.bin.gz': bytes(262144), 'grid/L2.bin.gz': bytes(4),
                      'places.json.gz': b'{"places":[]}', 'defects.json': b'{"defects":[]}'}.items():
        body = gzip.compress(raw, mtime=0) if name.endswith('.gz') else raw
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
        entry = dict(file=name, bytes=len(body), sha256=hashlib.sha256(body).hexdigest(),
                     rawSha256=hashlib.sha256(raw).hexdigest(), compression='gzip' if name.endswith('.gz') else 'none')
        files.append(entry)
        public[name] = dict(entry)
    manifest.update(files=files, chunks=[public['grid/L0/0_0.bin.gz']],
        overview=dict(public['grid/L2.bin.gz'], cols=1, rows=1), places=public['places.json.gz'], defects=public['defects.json'])
    (root / 'manifest.json').write_bytes(json.dumps(manifest).encode())
    return bake


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tree = self.root / 'source'
        self.first = make_bake(self.tree)
        self.second = make_bake(self.tree, kit='two')
        self.expected = topdown.catalog(self.tree)
        self.bundle = self.root / 'cold'
        self.bundle.mkdir(mode=0o700)
        (self.bundle / 'manifest.json').write_text('{"version":2}')

    def mutate(self, **values):
        p = self.tree / self.second / 'manifest.json'
        data = json.loads(p.read_bytes()); data.update(values); p.write_text(json.dumps(data))

    def test_identity_matches_app_reader_independent_bytes(self):
        data = {'mapRelease': 'fixture-map', 'kitVersion': 'kit-pin', 'formatVersion': 1, 'inputFingerprint': {'z': 2, 'a': {'z': 4, 'a': 3}}}
        canonical = b'{"formatVersion":1,"inputFingerprint":{"a":{"a":3,"z":4},"z":2},"kitVersion":"kit-pin","mapRelease":"fixture-map"}'
        self.assertEqual(topdown.identity(data), hashlib.sha256(canonical).hexdigest())
        data['inputFingerprint']['z'] = 2.0
        with self.assertRaises(RecoveryError): topdown.identity(data)

    def test_two_bakes_entire_root_private_companion_and_linkage(self):
        self.assertEqual(set(self.expected['bakes']), {self.first, self.second})
        tree = topdown.preserve(self.tree, self.bundle, self.expected)
        self.assertEqual(topdown.companion(self.bundle, self.expected), tree)
        for p in (tree, *tree.rglob('*')): self.assertEqual(p.stat().st_mode & 0o077, 0)
        (self.bundle / 'manifest.json').write_text('{"version":2,"drift":true}')
        with self.assertRaises(RecoveryError): topdown.companion(self.bundle, self.expected)

    def test_unselected_corruption_is_rejected(self):
        (self.tree / self.second / 'defects.json').write_bytes(b'{"defects":[1]}')
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)

    def test_missing_extra_duplicate_partial_and_reader_constants(self):
        original = (self.tree / self.second / 'manifest.json').read_bytes()
        for values in ({'partial': True}, {'chunkSize': 128}, {'undrawnTile': 0}, {'artifactId': 'wrong'}, {'schemaVersion': 2}, {'shape': {'cols': 0, 'rows': 1}}):
            with self.subTest(values=values):
                self.mutate(**values)
                with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
                (self.tree / self.second / 'manifest.json').write_bytes(original)
        extra = self.tree / self.second / 'undeclared'
        extra.write_bytes(b'x')
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        extra.unlink()
        (self.tree / self.second / 'defects.json').unlink()
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)

    def test_links_fifo_foreign_paths_and_hardlinks_refused(self):
        asset = self.tree / self.second / 'defects.json'
        raw = asset.read_bytes(); asset.unlink(); asset.symlink_to(self.tree / self.first / 'defects.json')
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        asset.unlink(); asset.hardlink_to(self.tree / self.first / 'defects.json')
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        asset.unlink()
        import os
        os.mkfifo(asset)
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        asset.unlink(); asset.write_bytes(raw)
        alias = self.root / 'alias'; alias.symlink_to(self.tree, target_is_directory=True)
        with self.assertRaises(RecoveryError): topdown.catalog(alias)

    def test_transport_raw_sizes_gzip_and_duplicate_inventory(self):
        p = self.tree / self.second / 'manifest.json'
        original = p.read_bytes()
        for field, value in [('rawSha256', '0' * 64), ('bytes', 0), ('compression', 'none'), ('file', '../outside')]:
            data = json.loads(original); data['files'][0][field] = value; p.write_text(json.dumps(data))
            with self.subTest(field=field):
                with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        data = json.loads(original); data['files'].append(data['files'][0]); p.write_text(json.dumps(data))
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        p.write_bytes(original)
        asset = self.tree / self.second / 'grid/L0/0_0.bin.gz'
        asset.write_bytes(gzip.compress(b'short', mtime=0))
        data = json.loads(original)
        for entry in (data['files'][0], data['chunks'][0]):
            entry.update(bytes=asset.stat().st_size, sha256=hashlib.sha256(asset.read_bytes()).hexdigest(), rawSha256=hashlib.sha256(b'short').hexdigest())
        p.write_text(json.dumps(data))
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)

    def test_manifest_raw_limits_gzip_corruption_and_overview_dimensions(self):
        p = self.tree / self.second / 'manifest.json'
        original = p.read_bytes()
        p.write_bytes(b' ' * (topdown.MAX_MANIFEST + 1))
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        p.write_bytes(original)
        for raw in (b'wrong-length', bytes(topdown.MAX_RAW + 1)):
            data = json.loads(original)
            body = gzip.compress(raw, mtime=0)
            asset = self.tree / self.second / 'grid/L2.bin.gz'; asset.write_bytes(body)
            for entry in (data['files'][1], data['overview']):
                entry.update(bytes=len(body), sha256=hashlib.sha256(body).hexdigest(), rawSha256=hashlib.sha256(raw).hexdigest())
            p.write_text(json.dumps(data))
            with self.assertRaises(RecoveryError): topdown.catalog(self.tree)
        broken = b'not gzip'
        (self.tree / self.second / 'grid/L2.bin.gz').write_bytes(broken)
        for entry in (data['files'][1], data['overview']):
            entry.update(bytes=len(broken), sha256=hashlib.sha256(broken).hexdigest())
        p.write_text(json.dumps(data))
        with self.assertRaises(RecoveryError): topdown.catalog(self.tree)

    def test_companion_incomplete_mutation_and_source_drift(self):
        copied = topdown.preserve(self.tree, self.bundle, self.expected)
        (copied / self.second / 'defects.json').write_bytes(b'changed')
        with self.assertRaises(RecoveryError): topdown.companion(self.bundle, self.expected)
        (copied.parent / 'INCOMPLETE').write_text('hold')
        with self.assertRaises(RecoveryError): topdown.companion(self.bundle, self.expected)
        (self.tree / self.first / 'defects.json').write_bytes(b'changed')
        with self.assertRaises(RecoveryError): topdown.preserve(self.tree, self.bundle, self.expected)

    def test_readonly_probe_every_bake_and_asset_detects_mismatch(self):
        recovery = Mock()
        def run(args):
            url = args[-1]
            if url.endswith('/preview'): return json.dumps({'topdownBakeId': self.first}).encode()
            path = url.split('/api/map/topdown/')[1]
            return (self.tree / path).read_bytes()
        recovery.docker.run.side_effect = run
        topdown.probe(recovery, 'synthetic-api', self.first, self.expected)
        self.assertEqual(recovery.docker.run.call_count, 11)
        with self.assertRaises(RecoveryError): topdown.probe(recovery, 'synthetic-api', '0' * 64, self.expected)
        (self.tree / self.second / 'defects.json').write_bytes(b'corrupt')
        with self.assertRaises(RecoveryError): topdown.probe(recovery, 'synthetic-api', self.first, self.expected)



class PreservingBundleTests(unittest.TestCase):
    def test_private_fullbundle_capture_companion_restore_validation_and_v1_compatibility(self):
        from game_server_recovery import Recovery
        from test_game_server_recovery import RecordingDocker
        import pep_preserving_topology as topology
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); root.chmod(0o700)
            stack = root / 'stack'; (stack / 'servers').mkdir(parents=True)
            (stack / 'data/scenarios').mkdir(parents=True)
            (stack / 'servers/spep.env').write_text('SERVER_ID=pep\nOPENSAMGUK_WORLD_ID=7\n')
            (stack / 'docker-compose.server.yml').write_text('# synthetic only')
            tree = stack / 'data/topdown/pep'
            selected = make_bake(tree); make_bake(tree, kit='two')
            expected = topdown.catalog(tree)
            class Adapter(RecordingDocker):
                def run(self, args, **kwargs):
                    if args[:2] == ['container', 'ls']:
                        self.calls.append(list(args)); return ('\n'.join(self.containers) + '\n').encode()
                    return super().run(args, **kwargs)
            docker = Adapter(stack)
            for obj in docker.containers.values():
                obj['State'].update(Running=True, Status='running')
                obj['HostConfig'] = {'PortBindings': {}}
                obj['NetworkSettings'] = {'Networks': {'private': {'Aliases': [], 'NetworkID': 'fixture-network-id'}}}
                obj['Config']['ExposedPorts'] = {}
                if obj['Config']['Labels']['com.docker.compose.service'] in ('game-api', 'game-engine'):
                    obj['Config']['Env'] += ['SCENARIO_DIR=/data/scenarios']
            for service in ('game-api', 'web-game'):
                obj = docker.containers.pop('spep-' + service)
                obj['Name'] += '-validation'; docker.containers['spep-' + service + '-validation'] = obj
            api = docker.containers['spep-game-api-validation']
            api['Config']['Env'] += ['SERVER_ID=pep', 'TOPDOWN_MAP_ROOT=/app/data/map/topdown', 'TOPDOWN_BAKE_ID=' + selected]
            api['Mounts'].append({'Type': 'bind', 'Source': str(tree), 'Destination': '/app/data/map/topdown', 'RW': False})
            docker.containers['spep-web-game-validation']['Config']['Env'] += ['GAME_API_URL=http://spep-game-api-validation:8081']
            recovery = Recovery(docker, lock_path=root / 'lock', token_factory=lambda: 'fixturetoken')
            observed, _ = topology.observe(recovery)
            preserving = {'topology': observed, 'topdown': {'selected_bake': selected, 'catalog': expected}}
            for obj in docker.containers.values(): obj['State'].update(Running=False, Status='exited')
            bundle = recovery.capture(server='pep', confirm='BACKUP pep', stack_dir=stack, backup_root=root, preserving=preserving)
            with self.assertRaises(RecoveryError): recovery.validate_bundle('pep', bundle)
            copied = topdown.preserve(tree, bundle, expected)
            manifest, _ = recovery.validate_bundle('pep', bundle)
            self.assertEqual(manifest['version'], 2)
            self.assertEqual(manifest['containers']['game-api']['name'], 'spep-game-api-validation')
            from pep_migration_clone import StorageClone
            clone = StorageClone(recovery, bundle, manifest, {})
            clone.network = 'synthetic-only'; clone.create = Mock(return_value='synthetic-api')
            clone.app('api', 'sha256:' + 'a' * 64, {}, stack / 'data/scenarios', 1024)
            argv = clone.create.call_args.args[-1]
            self.assertEqual(argv.count('--mount'), 2)
            self.assertIn(f'type=bind,source={copied},target=/app/data/map/topdown,readonly', argv)
            clone.app('engine', 'sha256:' + 'a' * 64, {}, stack / 'data/scenarios', 1024)
            self.assertEqual(clone.create.call_args.args[-1].count('--mount'), 1)
            self.assertNotIn('--publish', argv); self.assertNotIn('--network-alias', argv)
            (copied / selected / 'defects.json').write_bytes(b'changed')
            with self.assertRaises(RecoveryError): recovery.validate_bundle('pep', bundle)
            with self.assertRaises(RecoveryError): clone.app('api', 'sha256:' + 'a' * 64, {}, stack / 'data/scenarios', 1024)
            legacy_root = root / 'legacy'; legacy_root.mkdir(mode=0o700)
            legacy = Recovery(RecordingDocker(stack), lock_path=root / 'legacy-lock', token_factory=lambda: 'fixturetoken')
            old = legacy.capture(server='pep', confirm='BACKUP pep', stack_dir=stack, backup_root=legacy_root)
            old_manifest, _ = legacy.validate_bundle('pep', old)
            self.assertEqual(old_manifest['version'], 1)
            self.assertNotIn('topology', old_manifest); self.assertNotIn('topdown', old_manifest)

if __name__ == '__main__': unittest.main()
