import contextlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from game_server_recovery import REDIS_CMD, VOLUMES, RecoveryError
from pep_cold_capture_operator import (PepColdCapturePreflight, SERVICES,
                                       preserve_scenario_tree,
                                       require_complete_old_application_proof)


IMAGE = 'sha256:' + 'a' * 64


class FakeDocker:
    def __init__(self):
        self.calls = []
        self.maintenance_ok = True

    def run(self, args):
        self.calls.append(args)
        if not self.maintenance_ok:
            raise RecoveryError('maintenance is not drained')
        return b'{"capability":"maintenance-v1","state":"drained"}'


class FakeRecovery:
    def __init__(self, stack):
        self.stack = stack
        self.docker = FakeDocker()
        self.running = True
        self.owner = 'opensamguk-spep'
        self.locked_calls = 0

    @contextlib.contextmanager
    def locked(self):
        self.locked_calls += 1
        yield

    def inspect(self, kind, name):
        if kind == 'image':
            return {'Id': IMAGE}
        if kind == 'volume':
            suffix = name.removeprefix('spep-')
            return {'Name': name, 'Driver': 'local', 'Options': None, 'Labels': {
                'com.docker.compose.project': self.owner,
                'com.docker.compose.volume': suffix,
            }}
        service = name.removeprefix('spep-')
        assert service in SERVICES
        mounts = ([{'Type': 'bind', 'Source': str(self.stack / 'data/scenarios'),
                    'Destination': '/data/scenarios', 'RW': False}]
                  if service in ('game-api', 'game-engine') else [])
        if service in VOLUMES:
            suffix, destination = VOLUMES[service]
            mounts = [{'Type': 'volume', 'Name': 'spep-' + suffix,
                       'Destination': destination, 'RW': True}]
        env = ['OPENSAMGUK_WORLD_ID=1'] if service in ('game-api', 'game-engine') else []
        if service == 'game-postgres':
            env = ['POSTGRES_USER=sammo', 'POSTGRES_DB=sammo']
        return {'Name': '/' + name, 'Id': 'b' * 64, 'Image': IMAGE,
                'State': {'Running': self.running, 'Status': 'running' if self.running else 'exited',
                          'OOMKilled': False},
                'Config': {'Labels': {'com.docker.compose.project': self.owner,
                                      'com.docker.compose.service': service},
                           'Env': env,
                           'Cmd': REDIS_CMD if service == 'game-redis' else ['postgres']},
                'Mounts': mounts}


class PepColdCapturePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.stack = Path(self.temp.name).resolve()
        (self.stack / 'servers').mkdir()
        (self.stack / 'data/scenarios').mkdir(parents=True)
        (self.stack / 'servers/.deployer-maintenance').write_text('held')
        env = self.stack / 'servers/spep.env'
        env.write_text('SERVER_ID=pep\nOPENSAMGUK_WORLD_ID=1\nSCENARIO_CODE=scenario_1020\n'
                       'SCENARIO_LOOKUP_DIR=\nIMAGE_TAG=' + 'c' * 40 + '\nWEB_GAME_TAG=' + 'd' * 40 + '\n')
        env.chmod(0o600)
        self.recovery = FakeRecovery(self.stack)
        self.subject = PepColdCapturePreflight(self.recovery)

    def test_preflight_is_read_only_and_uses_one_lock(self):
        report = self.subject.prepare(self.stack)
        self.assertEqual(report['server'], 'pep')
        self.assertEqual(report['maintenance'], 'drained')
        self.assertEqual(report['scenario_lookup'], 'bundled')
        self.assertFalse(report['cold_capture_executed'])
        self.assertEqual(self.recovery.locked_calls, 1)
        self.assertEqual(len(self.recovery.docker.calls), 1)
        self.assertEqual(self.recovery.docker.calls[0][:3],
                         ['container', 'exec', 'opensamguk-deployer'])

    def test_open_maintenance_blocks_before_inspection(self):
        self.recovery.docker.maintenance_ok = False
        with self.assertRaises(RecoveryError):
            self.subject.prepare(self.stack)

    def test_missing_marker_blocks(self):
        (self.stack / 'servers/.deployer-maintenance').unlink()
        with self.assertRaisesRegex(RecoveryError, 'marker'):
            self.subject.prepare(self.stack)
        self.assertEqual(self.recovery.docker.calls, [])

    def test_lifecycle_journal_blocks(self):
        (self.stack / 'servers/.deployer-lifecycle-journal').touch()
        with self.assertRaisesRegex(RecoveryError, 'journal'):
            self.subject.prepare(self.stack)

    def test_stopped_source_blocks(self):
        self.recovery.running = False
        with self.assertRaisesRegex(RecoveryError, 'not running'):
            self.subject.prepare(self.stack)

    def test_foreign_source_blocks(self):
        self.recovery.owner = 'another-project'
        with self.assertRaisesRegex(RecoveryError, 'ownership'):
            self.subject.prepare(self.stack)

    def test_unexpected_scenario_lookup_blocks(self):
        env = self.stack / 'servers/spep.env'
        env.write_text(env.read_text().replace('SCENARIO_LOOKUP_DIR=\n',
                                              'SCENARIO_LOOKUP_DIR=/tmp/other\n'))
        with self.assertRaisesRegex(RecoveryError, 'lookup'):
            self.subject.prepare(self.stack)

    def test_scenario_companion_is_private_and_tied_to_bundle_manifest(self):
        source = self.stack / 'data/scenarios'
        (source / 'scenario_1020.json').write_text('{"map":"old"}')
        bundle = self.stack / 'pep-abcdefgh'
        bundle.mkdir(mode=0o700)
        (bundle / 'manifest.json').write_text('{"server":"pep"}')
        (bundle / 'manifest.json').chmod(0o600)
        tree, scenario = preserve_scenario_tree(source, bundle)
        self.assertEqual(scenario.file_count, 1)
        self.assertEqual((tree / 'scenario_1020.json').read_text(), '{"map":"old"}')
        self.assertEqual((tree / 'scenario_1020.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual(tree.stat().st_mode & 0o777, 0o700)
        self.assertFalse((tree.parent / 'INCOMPLETE').exists())
        self.assertIn(scenario.tree_sha256, (tree.parent / 'manifest.json').read_text())

    def test_scenario_link_refused_before_companion_creation(self):
        source = self.stack / 'data/scenarios'
        (source / 'scenario_1020.json').symlink_to('/tmp/other')
        bundle = self.stack / 'pep-abcdefgh'
        bundle.mkdir(mode=0o700)
        (bundle / 'manifest.json').write_text('{}')
        (bundle / 'manifest.json').chmod(0o600)
        with self.assertRaisesRegex(RecoveryError, 'link'):
            preserve_scenario_tree(source, bundle)
        self.assertFalse(bundle.with_name(bundle.name + '.scenario').exists())

    def test_engine_only_proof_cannot_claim_cold_recovery_success(self):
        storage = {'success': True, 'manifest_sha256': 'e' * 64}
        engine = SimpleNamespace(bundle_manifest_sha256='e' * 64, world_id=1,
                                 status={'serviceMaterialized': True, 'recoveryReady': True},
                                 cleanup={'success': True, 'remaining_resources': []})
        with self.assertRaisesRegex(RecoveryError, 'authenticated read'):
            require_complete_old_application_proof(storage, engine, None)
        alleged = {'source': 'isolated', 'bundle_manifest_sha256': 'e' * 64,
                   'world_id': 1, 'checks': {'login': True, 'identity': True,
                                             'server_entry': True, 'world_read': True,
                                             'map_read': False}}
        with self.assertRaisesRegex(RecoveryError, 'authenticated read'):
            require_complete_old_application_proof(storage, engine, alleged)
        alleged['checks']['map_read'] = True
        require_complete_old_application_proof(storage, engine, alleged)


if __name__ == '__main__':
    unittest.main()
