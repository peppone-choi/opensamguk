import contextlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from game_server_recovery import REDIS_CMD, VOLUMES, RecoveryError, digest
from pep_cold_capture_operator import (PepColdCaptureOperator, PepColdCapturePreflight, SERVICES,
                                       preserve_scenario_tree,
                                       require_complete_old_application_proof, require_qa_gate)


IMAGE = 'sha256:' + 'a' * 64


class FakeDocker:
    def __init__(self, recovery):
        self.recovery = recovery
        self.calls = []
        self.maintenance_ok = True
        self.stop_failure = None

    def run(self, args, *, stdin=None):
        self.calls.append(args)
        if not self.maintenance_ok:
            raise RecoveryError('maintenance is not drained')
        if args[:2] == ['container', 'stop']:
            self.recovery.stopped.add(args[-1].removesuffix('-id'))
            if args[-1] == self.stop_failure:
                raise RecoveryError('simulated stop transport failure')
            return b''
        if 'psql' in args:
            return b'0'
        return b'{"capability":"maintenance-v1","state":"drained"}'


class FakeRecovery:
    def __init__(self, stack):
        self.stack = stack
        self.docker = FakeDocker(self)
        self.running = True
        self.stopped = set()
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
        env = (['OPENSAMGUK_WORLD_ID=1', 'SCENARIO_DIR=', 'GAME_DB_USER=sammo',
                'GAME_DATABASE_URL=jdbc:postgresql://db/sammo', 'TURN_PROFILE_NAME=che:scenario_1020']
               if service in ('game-api', 'game-engine') else [])
        if service == 'game-postgres':
            env = ['POSTGRES_USER=sammo', 'POSTGRES_DB=sammo']
        active = self.running and service not in self.stopped
        return {'Name': '/' + name, 'Id': service + '-id', 'Image': IMAGE,
                'State': {'Running': active, 'Status': 'running' if active else 'exited',
                          'OOMKilled': False, 'ExitCode': 0 if service in VOLUMES else 143},
                'Config': {'Labels': {'com.docker.compose.project': self.owner,
                                      'com.docker.compose.service': service},
                           'Env': env,
                           'Cmd': REDIS_CMD if service == 'game-redis' else ['postgres']},
                'HostConfig': {'Memory': 1024},
                'Mounts': mounts}

    def postgres_check(self, *args, **kwargs):
        return {'counts': {'world_state': 1, 'city': 774, 'nation': 1, 'general': 1},
                'versions': ['59'], 'migration_success': True, 'selected_world_matches': True,
                'city_min': 1, 'city_max': 774, 'logical_dump_sha256': 'e' * 64}

    def redis_check(self, *args, **kwargs):
        return {'pong': True, 'loading': False, 'appendonly': True,
                'persistence_ok': True, 'key_count': 1}

    def capture(self, **kwargs):
        assert self.stopped == set(SERVICES)
        bundle = self.stack / 'backups/pep-abcdefgh'
        bundle.mkdir(mode=0o700)
        (bundle / 'manifest.json').write_text('{}')
        (bundle / 'manifest.json').chmod(0o600)
        return bundle

    def verify(self, **kwargs):
        return {'success': True, 'manifest_sha256': digest(kwargs['bundle'] / 'manifest.json')['sha256'],
                'postgres': self.postgres_check(), 'redis': self.redis_check()}


class FakeDrill:
    def prove(self, recovery, bundle, source_inputs, tree, scenario, *, authenticated_probe):
        assert authenticated_probe is not None
        manifest_sha = digest(bundle / 'manifest.json')['sha256']
        return SimpleNamespace(bundle_manifest_sha256=manifest_sha,
                               world_id=1, status={'serviceMaterialized': True, 'recoveryReady': True},
                               cleanup={'success': True, 'remaining_resources': []},
                               authenticated_read={'source': 'isolated',
                                   'bundle_manifest_sha256': manifest_sha, 'world_id': 1,
                                   'checks': {'login': True, 'identity': True, 'server_entry': True,
                                              'world_read': True, 'map_read': True}})


class EngineOnlyDrill(FakeDrill):
    def prove(self, *args, **kwargs):
        proof = super().prove(*args, **kwargs)
        proof.authenticated_read = None
        return proof


class FakeAttestor:
    def verify(self, gate):
        require_qa_gate(gate)


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

    def test_declared_lookup_must_match_effective_container_environment(self):
        original = self.recovery.inspect
        def drifted(kind, name):
            value = original(kind, name)
            if kind == 'container' and name == 'spep-game-api':
                value['Config']['Env'] = [entry for entry in value['Config']['Env']
                                           if not entry.startswith('SCENARIO_DIR=')]
                value['Config']['Env'].append('SCENARIO_DIR=/data/scenarios')
            return value
        self.recovery.inspect = drifted
        with self.assertRaisesRegex(RecoveryError, 'effective scenario lookup differ'):
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

    def test_w4_gate_blocks_cold_stop_and_full_proof_leaves_reset_blocked(self):
        backup_root = self.stack / 'backups'
        backup_root.mkdir(mode=0o700)
        (self.stack / 'data/scenarios/scenario_1020.json').write_text('{}')
        operator = PepColdCaptureOperator(self.recovery, FakeDrill(), qa_attestor=FakeAttestor())
        with self.assertRaisesRegex(RecoveryError, 'W4 PASS'):
            operator.capture_and_prove(stack=self.stack, backup_root=backup_root, qa_gate={})
        self.assertEqual(self.recovery.docker.calls, [])
        gate = {'battle': 'PASS', 'w4': 'PASS', 'head_sha': 'f' * 40,
                'scenario_code': 'scenario_990002', 'city_count': 1447}
        proof = operator.capture_and_prove(stack=self.stack, backup_root=backup_root, qa_gate=gate)
        self.assertTrue(proof['storage_verified'])
        self.assertTrue(proof['old_engine_materialized'])
        self.assertTrue(proof['authenticated_read_verified'])
        self.assertFalse(proof['ready_for_reset'])
        self.assertEqual(self.recovery.stopped, set(SERVICES))
        self.assertEqual([args[-1] for args in self.recovery.docker.calls if args[:2] == ['container', 'stop']],
                         ['web-game-id', 'game-api-id', 'game-engine-id',
                          'game-redis-id', 'game-postgres-id'])

    def test_engine_only_result_cannot_claim_old_application_recovery(self):
        backup_root = self.stack / 'backups'
        backup_root.mkdir(mode=0o700)
        (self.stack / 'data/scenarios/scenario_1020.json').write_text('{}')
        gate = {'battle': 'PASS', 'w4': 'PASS', 'head_sha': 'f' * 40,
                'scenario_code': 'scenario_990002', 'city_count': 1447}
        with self.assertRaisesRegex(RecoveryError, 'authenticated read proof'):
            PepColdCaptureOperator(self.recovery, EngineOnlyDrill(), qa_attestor=FakeAttestor()).capture_and_prove(
                stack=self.stack, backup_root=backup_root, qa_gate=gate)
        status_file = next(backup_root.glob('pep-cold-operation-*/status.json'))
        self.assertFalse(json.loads(status_file.read_text())['authenticated_read_verified'])

    def test_stop_transport_failure_is_recorded_without_blind_restart(self):
        backup_root = self.stack / 'backups'
        backup_root.mkdir(mode=0o700)
        self.recovery.docker.stop_failure = 'game-api-id'
        gate = {'battle': 'PASS', 'w4': 'PASS', 'head_sha': 'f' * 40,
                'scenario_code': 'scenario_990002', 'city_count': 1447}
        with self.assertRaisesRegex(RecoveryError, 'transport failure'):
            PepColdCaptureOperator(self.recovery, FakeDrill(), qa_attestor=FakeAttestor()).capture_and_prove(
                stack=self.stack, backup_root=backup_root, qa_gate=gate)
        status_files = list(backup_root.glob('pep-cold-operation-*/status.json'))
        self.assertEqual(len(status_files), 1)
        status = json.loads(status_files[0].read_text())
        self.assertEqual(status['phase'], 'failed-after-stopping-game-api')
        self.assertFalse(status['success'])
        self.assertEqual(self.recovery.stopped, {'web-game', 'game-api'})
        self.assertFalse(any(args[:2] == ['container', 'start']
                             for args in self.recovery.docker.calls))

    def test_default_attestor_refuses_even_well_formed_pass_before_any_stop(self):
        backup_root = self.stack / 'backups'
        backup_root.mkdir(mode=0o700)
        gate = {'battle': 'PASS', 'w4': 'PASS', 'head_sha': 'f' * 40,
                'scenario_code': 'scenario_990002', 'city_count': 1447}
        with self.assertRaisesRegex(RecoveryError, 'attestation unavailable'):
            PepColdCaptureOperator(self.recovery, FakeDrill()).capture_and_prove(
                stack=self.stack, backup_root=backup_root, qa_gate=gate)
        self.assertEqual(self.recovery.docker.calls, [])


if __name__ == '__main__':
    unittest.main()
