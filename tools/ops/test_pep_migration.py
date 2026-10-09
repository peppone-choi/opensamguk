import copy
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from game_server_recovery import RecoveryError, SERVICES
import pep_migration as migration
from pep_migration_clone import StorageClone, identifier


def inspections():
    return {s: {'Id': 'id-' + s, 'Name': '/spep-' + s, 'Image': 'image-' + s,
        'Config': {'Env': []}, 'HostConfig': {'PortBindings': None}, 'Mounts': [],
        'NetworkSettings': {'Networks': {'net': {'Aliases': [s], 'NetworkID': 'network-id'}}},
        'State': {'Running': True}} for s in SERVICES}


class RehearsalTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.stack = self.root / 'stack'
        self.stack.mkdir()
        self.checkout = self.root / 'checkout'
        self.checkout.mkdir()
        self.bundle = self.root / 'pep-abcdefgh'
        self.bundle.mkdir()
        (self.bundle / 'manifest.json').write_text('{}')
        self.recovery = Mock()
        @contextmanager
        def locked():
            yield
        self.recovery.locked.side_effect = locked
        self.before = inspections()
        self.operator = migration.PreservingMigration(self.recovery, Mock())
        self.operator.preflight = Mock(return_value=({}, 'a' * 40, self.before))
        self.operator.admission.verify.return_value = {r: 'candidate-' + r for r in migration.pep_loop.ROLES}
        self.operator.stop = Mock()
        self.operator.inbox_empty = Mock()
        self.operator.resume = Mock()
        self.operator.validate_scenario = Mock()
        self.operator.clone_stage = Mock(side_effect=lambda *args, **kwargs: {'identity': 'candidate' if kwargs['candidate'] else 'old'})
        self.recovery.capture.return_value = self.bundle
        self.recovery.postgres_check.return_value = {'pg': 'original'}
        self.recovery.redis_check.return_value = {'redis': 'original'}
        self.storage = {'postgres': {'pg': 'original'}, 'redis': {'redis': 'original'}}
        self.recovery.verify.return_value = self.storage
        self.env = {'OPENSAMGUK_WORLD_ID': '1', 'GAME_POSTGRES_USER': 'fixture', 'GAME_POSTGRES_DB': 'fixture'}
        self.manifest = {'containers': {'original': True}}
        self.recovery.validate_bundle.return_value = self.manifest, self.env
        self.recovery.source.return_value = self.manifest['containers'], {}

    def execute(self):
        with patch.object(migration, 'selected_env', return_value=self.env), \
             patch.object(migration.SourceEngineInputs, 'from_inspections', return_value=object()), \
             patch.object(migration, 'redis_fingerprint', return_value='d' * 64), \
             patch.object(migration, 'preserve_scenario_tree', return_value=(self.root, Mock(tree_sha256='b' * 64))):
            return self.operator.rehearse(stack=self.stack, backup_root=self.root, checkout=self.checkout,
                source_sha='c' * 40, images={}, confirm='REHEARSE AND RESUME pep')

    def test_success_is_rehearsal_and_exact_original_resume_not_live_deployment(self):
        result = self.execute()
        self.assertTrue(result['cold_rehearsal_verified'])
        self.assertTrue(result['original_resumed'])
        self.assertFalse(result['ready_for_deployment'])
        self.assertFalse(result['candidate_applied_live'])
        self.operator.resume.assert_called_once_with(self.before, self.env, {'pg': 'original'}, {'redis': 'original'}, 'd' * 64)
        self.assertEqual(self.operator.clone_stage.call_count, 3)
        self.assertEqual(self.operator.admission.verify.call_count, 2)
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())
        self.recovery.docker.run.assert_not_called()

    def test_private_or_fullbundle_shape_fails_before_stop_or_journal(self):
        self.operator.preflight.side_effect = RecoveryError('source scenario bind mismatch')
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.stop.assert_not_called()
        self.recovery.capture.assert_not_called()
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())

    def test_unverified_candidate_fails_before_stop(self):
        self.operator.admission.verify.side_effect = migration.pep_loop.AdmissionDeferred('CI unavailable')
        with self.assertRaises(migration.pep_loop.AdmissionDeferred): self.execute()
        self.operator.stop.assert_not_called()
        self.recovery.capture.assert_not_called()

    def test_source_drift_after_ci_fails_before_stop(self):
        changed = copy.deepcopy(self.before)
        changed['game-engine']['Image'] = 'other-image'
        self.operator.preflight.side_effect = [({}, 'a' * 40, self.before), ({}, 'a' * 40, changed)]
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.stop.assert_not_called()

    def test_inbox_pending_leaves_journal_and_does_not_capture_or_resume(self):
        self.operator.inbox_empty.side_effect = RecoveryError('pending inbox')
        with self.assertRaises(RecoveryError): self.execute()
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())

    def test_corrupt_storage_blocks_rehearsal_and_resume(self):
        self.recovery.verify.return_value = {'postgres': {'pg': 'changed'}, 'redis': {'redis': 'original'}}
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.clone_stage.assert_not_called()
        self.operator.resume.assert_not_called()

    def test_candidate_failure_never_promotes_or_blindly_resumes(self):
        self.operator.clone_stage.side_effect = [{'identity': 'old'}, RecoveryError('migration failed')]
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.resume.assert_not_called()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())

    def test_rollback_mismatch_blocks_resume(self):
        self.operator.clone_stage.side_effect = [{'identity': 'old'}, {'identity': 'new'}, {'identity': 'wrong'}]
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.resume.assert_not_called()

    def test_scenario_drift_after_old_boot_blocks_candidate_and_resume(self):
        self.operator.validate_scenario.side_effect = [None, RecoveryError('scenario changed')]
        with self.assertRaises(RecoveryError): self.execute()
        self.assertEqual(self.operator.clone_stage.call_count, 1)
        self.operator.resume.assert_not_called()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())

    def test_resume_refuses_equal_key_count_with_changed_redis_value(self):
        operator = migration.PreservingMigration(self.recovery)
        self.recovery.inspect.side_effect = lambda kind, name: self.before[name.removeprefix('spep-')]
        with patch.object(migration, 'redis_fingerprint', return_value='changed'):
            with self.assertRaisesRegex(RecoveryError, 'Redis values/expiries changed'):
                operator.resume(self.before, self.env, {'pg': 'original'}, {'redis': 'original'}, 'original')
        starts = [call.args[0] for call in self.recovery.docker.run.call_args_list]
        self.assertEqual(starts, [['container', 'start', 'id-game-postgres'],
                                  ['container', 'start', 'id-game-redis']])

    def test_resume_failure_keeps_recovery_journal(self):
        self.operator.resume.side_effect = RecoveryError('unhealthy original')
        with self.assertRaises(RecoveryError): self.execute()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())
        status = json.loads(next(self.root.glob('pep-migration-*/status.json')).read_text())
        self.assertFalse(status['success'])
        self.assertIn('resuming-original', status['phase'])

    def test_confirmation_must_name_the_operation(self):
        with self.assertRaises(RecoveryError):
            self.operator.rehearse(stack=self.stack, backup_root=self.root, checkout=self.checkout,
                source_sha='c' * 40, images={}, confirm='RESET pep')
        self.operator.preflight.assert_not_called()


class AdmissionTests(unittest.TestCase):
    def test_candidate_requires_exact_ci_and_immutable_image_source_for_each_role(self):
        recovery = Mock()
        contracts = {r: {'ref': migration.pep_loop.REGISTRY + '@sha256:' + '1' * 64,
                         'manifest': 'sha256:' + '1' * 64, 'config': 'sha256:' + '2' * 64}
                     for r in migration.pep_loop.ROLES}
        recovery.inspect.return_value = {'Id': 'sha256:' + '2' * 64, 'Os': 'linux', 'Architecture': 'amd64',
            'RepoDigests': [contracts['game-api']['ref']],
            'Config': {'Labels': {'org.opencontainers.image.revision': 'a' * 40}}}
        with patch.object(migration.pep_loop, 'admit_snapshot') as ci:
            result = migration.CandidateAdmission().verify(recovery, Path('/checkout'), 'a' * 40, 'b' * 40, contracts)
            self.assertEqual(set(result), set(migration.pep_loop.ROLES))
            ci.assert_called_once_with(Path('/checkout'), 'a' * 40, 'b' * 40)
            recovery.inspect.return_value['Config']['Labels']['org.opencontainers.image.revision'] = 'c' * 40
            with self.assertRaises(ValueError):
                migration.CandidateAdmission().verify(recovery, Path('/checkout'), 'a' * 40, 'b' * 40, contracts)

    def test_pass_booleans_are_not_an_admission_contract(self):
        with self.assertRaises(RecoveryError):
            migration.CandidateAdmission().verify(Mock(), Path('/checkout'), 'a' * 40, 'b' * 40,
                                                  {'backup': True, 'ci': True, 'rollback': True})

    def test_identifier_quoting_cannot_inject_sql(self):
        self.assertEqual(identifier('odd";drop table city;--'), '"odd"";drop table city;--"')
        with self.assertRaises(RecoveryError): identifier('bad\0identifier')

    def test_clone_rejects_removed_original_columns(self):
        clone = StorageClone(Mock(), Path('/bundle'), {}, {})
        clone.columns = Mock(return_value={'troop': ['id']})
        with self.assertRaises(RecoveryError): clone.data_fingerprint({'troop': ['id', 'nation']})

    def test_bundle_drift_fails_before_allocating_clone_resources(self):
        recovery = Mock()
        recovery.validate_bundle.return_value = {'server': 'fixture', 'changed': True}, {}
        clone = StorageClone(recovery, Path('/bundle'), {'server': 'fixture'}, {})
        with self.assertRaisesRegex(RecoveryError, 'bundle changed'):
            with clone.restored(): self.fail('drifted bundle must not be restored')
        recovery.docker.run.assert_not_called()


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.stack = Path(self.temporary.name)
        (self.stack / '.pep-loop-source').write_text('a' * 40)
        self.source = inspections()
        for obj in self.source.values():
            obj['HostConfig']['Memory'] = 512 * 1024**2
        for service in ('game-api', 'game-engine'):
            self.source[service]['Config']['Env'] = ['SCENARIO_DIR=/data/scenarios']
        self.recovery = Mock()
        self.recovery.inspect.side_effect = lambda kind, name: (
            self.source[name.removeprefix('spep-')] if kind == 'container'
            else {'Config': {'Labels': {'org.opencontainers.image.revision': 'a' * 40}}})
        self.extra_consumer = False
        self.private_consumers = False
        self.ready = True
        def run(args):
            if args[:2] == ['container', 'ls']:
                if '--format' in args:
                    return b'spep-game-api-validation\nspep-web-game-validation\n' if self.private_consumers else b'spep-game-api\n'
                service = 'game-postgres' if args[-1].endswith('pgdata') else 'game-redis'
                return (self.source[service]['Id'] + ('\nextra' if self.extra_consumer else '') + '\n').encode()
            return json.dumps({'recoveryMode': 'READY' if self.ready else 'RELOAD_REQUIRED',
                'recoveryReady': self.ready, 'serviceMaterialized': True, 'clockError': None}).encode()
        self.recovery.docker.run.side_effect = run
        self.operator = migration.PreservingMigration(self.recovery)

    def preflight(self):
        with patch.object(migration.PepColdCapturePreflight, 'inspect', return_value={'maintenance': 'drained'}):
            return self.operator.preflight(self.stack)

    def assert_blocked(self):
        with self.assertRaises(RecoveryError): self.preflight()
        self.assertTrue(all(call.args[0][:2] not in (['container', 'stop'], ['container', 'start'])
                            for call in self.recovery.docker.run.call_args_list))

    def test_exact_external_source_has_read_only_preflight(self):
        observation, source, _ = self.preflight()
        self.assertEqual(observation['maintenance'], 'drained')
        self.assertEqual(source, 'a' * 40)
        self.assertEqual(self.recovery.docker.run.call_count, 4)

    def test_private_consumers_alongside_public_are_rejected_before_observation(self):
        self.private_consumers = True
        with patch.object(migration.PepColdCapturePreflight, 'inspect') as cold:
            with self.assertRaisesRegex(RecoveryError, 'PRIVATE validation consumers unsupported'):
                self.operator.preflight(self.stack)
            cold.assert_not_called()
        self.assertEqual(self.recovery.docker.run.call_count, 1)

    def test_bundled_effective_input_blocks_before_any_stop(self):
        self.source['game-engine']['Config']['Env'] = ['SCENARIO_DIR=']
        self.assert_blocked()

    def test_extra_volume_consumer_blocks_before_any_stop(self):
        self.extra_consumer = True
        self.assert_blocked()

    def test_mixed_runtime_source_blocks_before_any_stop(self):
        self.recovery.inspect.side_effect = lambda kind, name: (
            self.source[name.removeprefix('spep-')] if kind == 'container'
            else {'Config': {'Labels': {'org.opencontainers.image.revision': 'b' * 40}}})
        self.assert_blocked()

    def test_unbounded_memory_blocks_before_any_stop(self):
        self.source['game-api']['HostConfig']['Memory'] = 0
        self.assert_blocked()

    def test_reload_required_blocks_before_any_stop(self):
        self.ready = False
        self.assert_blocked()


if __name__ == '__main__':
    unittest.main()
