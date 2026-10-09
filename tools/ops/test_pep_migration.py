import copy
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from game_server_recovery import RecoveryError, SERVICES
import pep_migration as migration
from pep_migration_clone import StorageClone, application_clock, identifier


def inspections():
    return {s: {'Id': 'id-' + s, 'Name': '/spep-' + s, 'Image': 'image-' + s,
        'Config': {'Env': []}, 'HostConfig': {'PortBindings': None}, 'Mounts': [],
        'NetworkSettings': {'Networks': {'net': {'Aliases': [s], 'NetworkID': 'network-id'}}},
        'State': {'Running': True, 'Status': 'running'}} for s in SERVICES}


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
        self.observation = {'preserving': {'topology': {'mode': 'PUBLIC'}, 'topdown': None}}
        self.operator.preflight = Mock(return_value=(self.observation, 'a' * 40, self.before))
        self.operator.validate_topdown = Mock()
        self.operator.admission.verify.return_value = {r: 'candidate-' + r for r in migration.pep_loop.ROLES}
        self.operator.stop = Mock()
        self.operator.inbox_empty = Mock()
        self.operator.resume = Mock()
        self.operator.validate_scenario = Mock()
        self.operator.clone_stage = Mock(side_effect=lambda *args, **kwargs: {'identity': 'candidate' if kwargs['candidate'] else 'old'})
        self.recovery.inspect.side_effect = lambda kind, name: self.before[name.removeprefix('spep-')]
        self.recovery.capture.return_value = self.bundle
        self.recovery.postgres_check.return_value = {'pg': 'original'}
        self.recovery.redis_check.return_value = {'redis': 'original'}
        self.storage = {'postgres': {'pg': 'original'}, 'redis': {'redis': 'original'}}
        self.recovery.verify.return_value = self.storage
        self.env = {'OPENSAMGUK_WORLD_ID': '1', 'GAME_POSTGRES_USER': 'fixture', 'GAME_POSTGRES_DB': 'fixture'}
        self.manifest = {'containers': {'original': True}}
        self.recovery.validate_bundle.return_value = self.manifest, self.env
        self.recovery.source.return_value = self.manifest['containers'], {}

    def execute(self, *, redis_effect=None):
        with patch.object(migration, 'selected_env', return_value=self.env), \
             patch.object(migration.SourceEngineInputs, 'from_inspections', return_value=object()), \
             patch.object(migration, 'redis_fingerprint', return_value='d' * 64, side_effect=redis_effect), \
             patch.object(migration, 'preserve_scenario_tree', return_value=(self.root, Mock(tree_sha256='b' * 64))):
            return self.operator.rehearse(stack=self.stack, backup_root=self.root, checkout=self.checkout,
                source_sha='c' * 40, images={}, confirm='REHEARSE AND RESUME pep')

    def test_success_is_rehearsal_and_exact_original_resume_not_live_deployment(self):
        measurements = []
        def measure(*args):
            measurements.append(self.operator.stop.call_count)
            return ('e' if len(measurements) == 1 else 'd') * 64
        result = self.execute(redis_effect=measure)
        self.assertEqual(measurements, [0, 3])
        self.assertTrue(result['cold_rehearsal_verified'])
        self.assertTrue(result['original_resumed'])
        self.assertFalse(result['ready_for_deployment'])
        self.assertFalse(result['candidate_applied_live'])
        self.operator.resume.assert_called_once_with(self.before, self.env, {'pg': 'original'}, {'redis': 'original'}, 'd' * 64)
        self.assertEqual(self.operator.clone_stage.call_count, 3)
        self.assertEqual(self.operator.admission.verify.call_count, 2)
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())
        self.recovery.docker.run.assert_not_called()

    def test_original_replacement_at_clone_boundary_is_refused(self):
        current = copy.deepcopy(self.before)
        current['game-engine']['Id'] = 'replacement-engine'
        self.recovery.inspect.side_effect = lambda kind, name: current[name.removeprefix('spep-')]
        with patch.object(migration.topology_contract, 'assert_current'), \
             self.assertRaisesRegex(RecoveryError, 'original identity drift'):
            migration.PreservingMigration.validate_topdown(self.operator, self.bundle, self.stack,
                self.observation['preserving'], self.before)

    def test_unsupported_mount_shape_fails_before_stop_or_journal(self):
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
        self.operator.preflight.side_effect = [(self.observation, 'a' * 40, self.before), (self.observation, 'a' * 40, changed)]
        with self.assertRaises(RecoveryError): self.execute()
        self.operator.stop.assert_not_called()

    def test_inbox_pending_leaves_journal_and_does_not_capture_or_resume(self):
        self.operator.inbox_empty.side_effect = RecoveryError('pending inbox')
        with self.assertRaises(RecoveryError): self.execute()
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())

    def test_existing_redis_pending_fails_after_admission_before_stop_or_journal(self):
        with self.assertRaisesRegex(RecoveryError, 'Redis fingerprint failed'):
            self.execute(redis_effect=RecoveryError('Redis fingerprint failed'))
        self.assertEqual(self.operator.preflight.call_count, 2)
        self.operator.admission.verify.assert_called_once()
        self.operator.stop.assert_not_called()
        self.recovery.docker.run.assert_not_called()
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())
        self.assertEqual(list(self.root.glob('pep-migration-*')), [])

    def test_redis_pending_after_shutdown_remains_fail_closed(self):
        with self.assertRaisesRegex(RecoveryError, 'Redis fingerprint failed'):
            self.execute(redis_effect=['d' * 64, RecoveryError('Redis fingerprint failed')])
        self.assertEqual([call.args[0] for call in self.operator.stop.call_args_list],
                         ['web-game', 'game-api', 'game-engine'] * 2)
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.assertTrue((self.stack / '.pep-migration-incomplete').exists())
        status = json.loads(next(self.root.glob('pep-migration-*/status.json')).read_text())
        self.assertEqual(status['phase'], 'failed-after-stopping-game-engine')
        self.assertFalse(status['success'])

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


    def test_catalog_drift_during_admission_has_no_stop_or_journal(self):
        changed = {'preserving': {'topology': {}, 'topdown': {'changed': True}}}
        self.operator.preflight.side_effect = [(self.observation, 'a' * 40, self.before), (changed, 'a' * 40, self.before)]
        with self.assertRaisesRegex(RecoveryError, 'catalog drift'): self.execute()
        self.operator.stop.assert_not_called()
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())

    def test_fullbundle_without_isolated_publication_contract_defers_before_stop(self):
        self.observation['preserving']['topdown'] = {'selected_bake': 'b' * 64, 'catalog': {}}
        with self.assertRaisesRegex(RecoveryError, 'isolated Gateway publication source'):
            self.execute()
        self.operator.admission.verify.assert_not_called()
        self.operator.stop.assert_not_called()
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())
        self.assertEqual(list(self.root.glob('pep-migration-*')), [])

    def test_private_scenario_only_defers_before_admission_or_any_mutation(self):
        self.observation['preserving']['topology']['mode'] = 'PRIVATE'
        for service in ('game-api', 'web-game'):
            self.before[service]['Name'] += '-validation'
        with self.assertRaisesRegex(RecoveryError, 'PRIVATE scenario-only rehearsal deferred'):
            self.execute()
        self.operator.admission.verify.assert_not_called()
        self.operator.stop.assert_not_called()
        self.recovery.capture.assert_not_called()
        self.operator.resume.assert_not_called()
        self.recovery.docker.run.assert_not_called()
        self.assertFalse((self.stack / '.pep-migration-incomplete').exists())
        self.assertEqual(list(self.root.glob('pep-migration-*')), [])

    def test_topdown_drift_at_each_stage_or_before_resume_never_resumes(self):
        for index in range(7):
            with self.subTest(boundary=index):
                case = RehearsalTests()
                case.setUp()
                try:
                    case.operator.validate_topdown.side_effect = [None] * index + [RecoveryError('catalog drift')]
                    with self.assertRaisesRegex(RecoveryError, 'catalog drift'): case.execute()
                    case.operator.resume.assert_not_called()
                    self.assertTrue((case.stack / '.pep-migration-incomplete').exists())
                    self.assertEqual(case.operator.clone_stage.call_count, min((index + 1) // 2, 3))
                finally: case.doCleanups()

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



class TopologyTests(unittest.TestCase):
    def setUp(self):
        import pep_preserving_topology as topology
        self.topology = topology
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.stack = Path(self.temp.name)
        (self.stack / 'data/scenarios').mkdir(parents=True)
        self.objects = inspections()
        for service in ('game-api', 'game-engine'):
            self.objects[service]['Config']['Env'] = ['SCENARIO_DIR=/data/scenarios']
            self.objects[service]['Mounts'] = [{'Type': 'bind', 'Source': str(self.stack / 'data/scenarios'), 'Destination': '/data/scenarios', 'RW': False}]
        self.names = ['spep-' + s for s in SERVICES]
        self.recovery = Mock()
        self.recovery.docker.run.side_effect = lambda args: ('\n'.join(self.names) + '\n').encode()
        self.recovery.inspect.side_effect = lambda kind, name: next(v for v in self.objects.values() if v['Name'] == '/' + name)

    def private(self):
        for service in ('game-api', 'web-game'):
            previous = 'spep-' + service
            self.names.remove(previous); self.names.append(previous + '-validation')
            self.objects[service]['Name'] += '-validation'
            for network in self.objects[service]['NetworkSettings']['Networks'].values(): network['Aliases'] = []
        self.objects['web-game']['Config']['Env'] = ['GAME_API_URL=http://spep-game-api-validation:8081']

    def test_public_and_private_identity_exposure_are_observed_without_mutation(self):
        self.objects['game-api']['HostConfig']['PortBindings'] = {'8081/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '28081'}]}
        self.objects['game-api']['Config']['ExposedPorts'] = {'8081/tcp': {}}
        public, before = self.topology.observe(self.recovery)
        self.assertEqual(public['exposure']['game-api']['exposed_ports'], {'8081/tcp': {}})
        self.topology.assert_current(self.recovery, public, running=True)
        self.private(); self.objects['game-api']['HostConfig']['PortBindings'] = {}
        private, _ = self.topology.observe(self.recovery)
        self.assertEqual(private['mode'], 'PRIVATE')
        self.assertEqual(private['names']['game-api'], 'spep-game-api-validation')
        self.assertTrue(all(call.args[0][:2] == ['container', 'ls'] for call in self.recovery.docker.run.call_args_list))

    def test_mixed_partial_stopped_and_opposite_leftovers_block(self):
        initial = list(self.names)
        for names in (initial + ['spep-game-api-validation'], [n for n in initial if n != 'spep-web-game']):
            self.names = names
            with self.assertRaises(RecoveryError): self.topology.observe(self.recovery)
        self.names = initial; self.objects['web-game']['State']['Running'] = False
        with self.assertRaises(RecoveryError): self.topology.observe(self.recovery)

    def test_private_ports_aliases_and_upstream_block(self):
        self.private()
        obj = self.objects['game-api']
        obj['HostConfig']['PortBindings'] = {'8081/tcp': [{}]}
        with self.assertRaises(RecoveryError): self.topology.observe(self.recovery)
        obj['HostConfig']['PortBindings'] = {}; obj['NetworkSettings']['Networks']['net']['Aliases'] = ['public']
        with self.assertRaises(RecoveryError): self.topology.observe(self.recovery)
        obj['NetworkSettings']['Networks']['net']['Aliases'] = []
        self.objects['web-game']['Config']['Env'] = ['GAME_API_URL=http://spep-game-api:8081']
        with self.assertRaises(RecoveryError): self.topology.observe(self.recovery)

    def test_mount_order_fullbundle_and_violations(self):
        from test_pep_topdown_catalog import make_bake
        root = self.stack / 'data/topdown/pep'; make_bake(root)
        api = self.objects['game-api']
        extra = {'Type': 'bind', 'Source': str(root), 'Destination': '/app/data/map/topdown', 'RW': False}
        api['Mounts'].insert(0, extra)
        self.topology.mounts(api, 'game-api', self.stack, fullbundle=True)
        self.assertEqual(self.topology.mount(api, '/data/scenarios')['Source'], str(self.stack / 'data/scenarios'))
        for field, value in (('RW', True), ('Type', 'volume'), ('Destination', '/data/scenarios'), ('Source', str(self.stack))):
            original = extra[field]; extra[field] = value
            with self.assertRaises(RecoveryError): self.topology.mounts(api, 'game-api', self.stack, fullbundle=True)
            extra[field] = original
        extra['Source'] = str(self.stack / 'linked'); (self.stack / 'linked').symlink_to(root, target_is_directory=True)
        with self.assertRaises(RecoveryError): self.topology.mounts(api, 'game-api', self.stack, fullbundle=True)
        extra['Source'] = str(root)
        with patch.object(self.topology, 'checked_path', return_value=Mock(stat=Mock(return_value=Mock(st_dev=-1)))):
            with self.assertRaises(RecoveryError): self.topology.mounts(api, 'game-api', self.stack, fullbundle=True)
        api['Config']['Env'] = ['SCENARIO_DIR=']
        with self.assertRaises(RecoveryError): self.topology.mounts(api, 'game-api', self.stack, fullbundle=True)

    def test_public_exposure_drift_is_refused(self):
        observed, _ = self.topology.observe(self.recovery)
        self.objects['game-api']['Config']['ExposedPorts'] = {'8081/tcp': {}}
        with self.assertRaises(RecoveryError): self.topology.assert_current(self.recovery, observed)


class ApplicationClockTests(unittest.TestCase):
    def setUp(self):
        self.rows = [{'id': 7, 'current_year': 190, 'current_month': 1, 'current_phase': 1,
            'tick_seconds': 3600, 'meta': {'serverId': 'synthetic-active'}, 'start_time': '2026-01-01T00:00:00Z'}]

    def test_clock_uses_selected_world_and_configured_active_server(self):
        clock = application_clock(self.rows, ['older', 'synthetic-active'], 7)
        self.assertEqual((clock['worldId'], clock['serverId']), (7, 'synthetic-active'))
        self.assertEqual(clock['nextRunTime'], '2026-01-01T01:00:00+00:00')

    def test_wrong_world_and_unknown_active_server_are_rejected(self):
        with self.assertRaises(RecoveryError): application_clock(self.rows, ['synthetic-active'], 8)
        with self.assertRaises(RecoveryError): application_clock(self.rows, ['other'], 7)

    def test_multiple_servers_without_persisted_selection_are_rejected(self):
        self.rows[0]['meta'] = {}
        with self.assertRaises(RecoveryError): application_clock(self.rows, ['one', 'two'], 7)
        self.assertEqual(application_clock(self.rows, ['only'], 7)['serverId'], 'only')

    def test_legacy_identity_alias_and_unavailable_identity_are_explicit(self):
        self.rows[0]['meta'] = {'server_id': 'synthetic-active'}
        self.assertEqual(application_clock(self.rows, ['synthetic-active'], 7)['serverId'], 'synthetic-active')
        self.rows[0]['meta'] = {}
        self.assertIsNone(application_clock(self.rows, [], 7)['serverId'])


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.stack = Path(self.temporary.name)
        (self.stack / '.pep-loop-source').write_text('a' * 40)
        (self.stack / 'data/scenarios').mkdir(parents=True)
        self.source = inspections()
        for obj in self.source.values():
            obj['HostConfig']['Memory'] = 512 * 1024**2
        for service in ('game-api', 'game-engine'):
            self.source[service]['Config']['Env'] = ['SCENARIO_DIR=/data/scenarios']
            self.source[service]['Mounts'] = [{'Type': 'bind', 'Source': str(self.stack / 'data/scenarios'), 'Destination': '/data/scenarios', 'RW': False}]
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
                    return b'spep-game-api\nspep-web-game\nspep-game-api-validation\nspep-web-game-validation\n' if self.private_consumers else b'spep-game-api\nspep-web-game\n'
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
            with self.assertRaisesRegex(RecoveryError, 'mixed/partial/stopped opposite'):
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
