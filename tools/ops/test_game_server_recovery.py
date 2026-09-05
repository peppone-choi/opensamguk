#!/usr/bin/env python3
"""Behavioral boundary tests; synthetic payloads contain no operational data."""
import copy
import hashlib
import fcntl
import importlib.util
import io
import json
import os
import re
import subprocess
from pathlib import Path
import tarfile
import tempfile
import threading
import textwrap
import unittest
from unittest.mock import patch

SPEC = importlib.util.find_spec('game_server_recovery')
if SPEC:
    import game_server_recovery as recovery
else:
    recovery = None

SERVICES = ('game-postgres', 'game-redis', 'game-engine', 'game-api', 'web-game')
REDIS_CMD = ['redis-server', '--appendonly', 'yes', '--maxmemory', '256mb',
             '--maxmemory-policy', 'allkeys-lru']


def archive(payload=b'fixture', name='./PG_VERSION', kind=tarfile.REGTYPE):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        entry = tarfile.TarInfo(name)
        entry.type = kind
        entry.linkname = '../../outside' if kind != tarfile.REGTYPE else ''
        entry.uid = entry.gid = 999
        entry.size = len(payload) if kind == tarfile.REGTYPE else 0
        tar.addfile(entry, io.BytesIO(payload) if entry.size else None)
    return output.getvalue()


def storage_archive(postgres):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        entries = {'PG_VERSION': b'16\n', 'global/pg_control': b'fixture', 'base/1': b'fixture'} if postgres else {
            'appendonlydir/appendonly.aof.manifest': b'fixture', 'appendonlydir/appendonly.aof.1.base.rdb': b'fixture'}
        for name, payload in entries.items():
            entry = tarfile.TarInfo(name)
            entry.uid = entry.gid = 999
            entry.size = len(payload)
            tar.addfile(entry, io.BytesIO(payload))
    return output.getvalue()


class RecordingDocker:
    """Models only supported Docker argv; unexpected commands fail the test."""
    def __init__(self, stack, server='pep'):
        self.calls = []
        self.containers = {}
        self.volumes = {}
        self.images = {'sha256:' + str(i) * 64 for i in range(1, 6)}
        self.archive = None
        self.start_failure = None
        self.ownership_failure = False
        self.absent_world = False
        self.failed_migration = False
        self.pg_queries = []
        self.redis_failure = False
        self.volume_race = False
        project = 'opensamguk-s' + server
        for i, service in enumerate(SERVICES):
            name = f's{server}-{service}'
            mounts = []
            if i < 2:
                suffix, dest = [('game-pgdata', '/var/lib/postgresql/data'),
                                ('game-redisdata', '/data')][i]
                volume = f's{server}-{suffix}'
                self.volumes[volume] = {'Name': volume, 'Driver': 'local', 'Options': None,
                    'Labels': {'com.docker.compose.project': project,
                               'com.docker.compose.volume': suffix}}
                mounts = [{'Type': 'volume', 'Name': volume, 'Destination': dest, 'RW': True}]
            elif i < 4:
                mounts = [{'Type': 'bind', 'Source': str(stack / 'data/scenarios'),
                           'Destination': '/data/scenarios', 'RW': False}]
            self.containers[name] = {'Id': f'source-{i}', 'Name': '/' + name,
                'Image': 'sha256:' + str(i + 1) * 64, 'Mounts': mounts,
                'State': {'Status': 'exited', 'Running': False, 'OOMKilled': False, 'ExitCode': 0},
                'Config': {'Image': f'fixture:{service}', 'Labels': {
                    'com.docker.compose.project': project, 'com.docker.compose.service': service},
                    'Env': ['POSTGRES_DB=sammo', 'POSTGRES_USER=sammo',
                            'PGDATA=/var/lib/postgresql/data', 'OPENSAMGUK_WORLD_ID=7'],
                    'Cmd': REDIS_CMD if i == 1 else ['postgres']}}

    def run(self, args, *, stdin=None, stdout=None):
        self.calls.append(list(args))
        if args[:2] == ['container', 'inspect']:
            obj = copy.deepcopy(self.containers[args[2]])
            if self.ownership_failure and args[2].startswith('os-recovery-'):
                obj['Config']['Labels'] = {}
            result = json.dumps([obj]).encode()
        elif args[:2] == ['volume', 'inspect']:
            result = json.dumps([self.volumes[args[2]]]).encode()
        elif args[:2] == ['image', 'inspect']:
            if args[2] not in self.images:
                raise recovery.RecoveryError('docker command failed')
            result = json.dumps([{'Id': args[2]}]).encode()
        elif args[:2] in (['container', 'ls'], ['volume', 'ls']):
            self.assert_argv(args[2:], ['--all', '--format', '{{.Names}}'] if args[0] == 'container'
                             else ['--format', '{{.Name}}'])
            result = '\n'.join(self.containers if args[0] == 'container' else self.volumes).encode()
        elif args[:2] == ['image', 'save']:
            if set(args[2:]) != self.images:
                raise AssertionError('all exact source image IDs must be saved once')
            result = archive(name='manifest.json')
        elif args[:2] == ['image', 'load']:
            self.assert_argv(args[2:], [])
            if stdin is None:
                raise AssertionError('image archive missing')
            self.images.update('sha256:' + str(i) * 64 for i in range(1, 6))
            result = b''
        elif args[:2] == ['volume', 'create']:
            self.assert_argv(args[2:4], ['--label', 'org.opensamguk.recovery=fixturetoken'])
            name = args[4]
            if name in self.volumes:
                raise AssertionError('volume create can reuse existing data!')
            self.volumes[name] = {'Name': name, 'Driver': 'local', 'Options': None,
                'Labels': {} if self.volume_race else {'org.opensamguk.recovery': 'fixturetoken'}}
            result = name.encode()
        elif args[:2] == ['container', 'create']:
            name = args[args.index('--name') + 1]
            if name in self.containers:
                raise recovery.RecoveryError('name collision')
            assert '--network' in args and args[args.index('--network') + 1] == 'none'
            assert not any(a in args for a in ['-p', '--publish', '--privileged'])
            assert args[args.index('--label') + 1] == 'org.opensamguk.recovery=fixturetoken'
            if 'tar' in args:
                mount = args[args.index('--mount') + 1]
                assert 'target=/var/lib/postgresql/data' in mount or 'target=/data' in mount
            self.containers[name] = {'Id': 'created-' + name, 'Name': '/' + name,
                'Config': {'Labels': {'org.opensamguk.recovery': 'fixturetoken'}}, 'argv': args}
            result = ('created-' + name).encode()
        elif args[:2] == ['container', 'start']:
            name = args[-1]
            config = self.containers[name]['argv']
            if self.start_failure and self.start_failure in name:
                raise recovery.RecoveryError('docker command failed')
            if '-cpf' in config:
                assert ',readonly' in config[config.index('--mount') + 1]
                result = self.archive if self.archive is not None else storage_archive('postgres' in name)
            elif '-xpf' in config:
                assert stdin is not None
                assert 'os-recovery-' in config[config.index('--mount') + 1]
                result = b''
            else:
                result = name.encode()
        elif args[:2] == ['container', 'exec']:
            if 'pg_isready' in args:
                result = b'accepting connections'
            elif 'psql' in args:
                assert stdin is not None and '-c' not in args
                query = stdin.read().decode()
                self.pg_queries.append(query)
                result = json.dumps({'world_state': 1, 'city': 2, 'nation': 1, 'general': 3,
                    'selected_world': 0 if self.absent_world else 1,
                    'failed_migrations': 1 if self.failed_migration else 0,
                    'migration_count': 2, 'versions': ['1', '48'], 'city_min': 1,
                    'city_max': 2}).encode()
            elif 'pg_dump' in args:
                assert '--no-owner' in args and '--no-privileges' in args and stdout is not None
                result = b'-- fixture dump\n\\restrict ABC123\nINSERT INTO city VALUES (1);\n\\unrestrict ABC123\n'
            elif 'redis-cli' in args:
                if args[-1] == 'PING':
                    result = b'NO' if self.redis_failure else b'PONG'
                elif args[-2:] == ['INFO', 'persistence']:
                    result = b'loading:0\r\naof_enabled:1\r\naof_last_write_status:ok\r\nrdb_last_bgsave_status:ok\r\naof_last_bgrewrite_status:ok\r\n'
                elif args[-1] == 'DBSIZE':
                    result = b'1'
                else:
                    raise AssertionError(args)
            else:
                raise AssertionError(args)
        elif args[:2] == ['container', 'rm']:
            self.assert_argv(args[2:3], ['--force'])
            assert args[3].startswith('os-recovery-')
            del self.containers[args[3]]
            result = b''
        elif args[:2] == ['volume', 'rm']:
            assert args[2].startswith('os-recovery-')
            del self.volumes[args[2]]
            result = b''
        else:
            raise AssertionError('unexpected Docker argv: ' + repr(args))
        if stdout is not None:
            stdout.write(result)
            return b''
        return result

    @staticmethod
    def assert_argv(actual, expected):
        if actual != expected:
            raise AssertionError((actual, expected))


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(recovery, 'recovery helper must implement behavioral contract')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.stack = self.root / 'stack'
        (self.stack / 'servers').mkdir(parents=True)
        (self.stack / 'data/scenarios').mkdir(parents=True)
        (self.stack / 'servers/spep.env').write_text('SERVER_ID=pep\nOPENSAMGUK_WORLD_ID=7\nGAME_POSTGRES_USER=sammo\nGAME_POSTGRES_DB=sammo\nPRIVATE_TOKEN=do-not-print\n')
        (self.stack / 'docker-compose.server.yml').write_text('name: synthetic-fixture\n')
        self.backups = self.root / 'backups'
        self.backups.mkdir(mode=0o700)
        self.docker = RecordingDocker(self.stack)
        self.helper = recovery.Recovery(self.docker, lock_path=self.root / 'lock',
                                        token_factory=lambda: 'fixturetoken', sleep=lambda _: None)

    def capture(self, **changes):
        args = dict(server='pep', confirm='BACKUP pep', stack_dir=self.stack, backup_root=self.backups)
        args.update(changes)
        return self.helper.capture(**args)

    def verify(self, bundle, **changes):
        args = dict(server='pep', confirm='VERIFY pep', bundle=bundle)
        args.update(changes)
        return self.helper.verify(**args)

    def rejected(self, action):
        with self.assertRaises(recovery.RecoveryError):
            action()

    def test_invalid_targets_rejected_before_lock_or_docker(self):
        for server in ['', 'PEP', 'all', 'admin1', '../pep', 'pep/../x', 'pep-x', 'map']:
            with self.subTest(server=server):
                self.rejected(lambda: self.capture(server=server, confirm='BACKUP ' + server))
        self.assertEqual(self.docker.calls, [])
        self.assertFalse((self.root / 'lock').exists())

    def test_confirmation_rejected_before_lock(self):
        self.rejected(lambda: self.capture(confirm='BACKUP other'))
        self.assertFalse((self.root / 'lock').exists())

    def test_any_running_service_refused_without_mutation(self):
        for service in SERVICES:
            source = self.docker.containers['spep-' + service]
            source['State']['Running'] = True
            self.rejected(self.capture)
            source['State']['Running'] = False
        self.assertEqual(list(self.backups.iterdir()), [])
        self.assertFalse(any(c[:2] == ['container', 'create'] for c in self.docker.calls))

    def test_wrong_project_refused(self):
        self.docker.containers['spep-game-api']['Config']['Labels']['com.docker.compose.project'] = 'shared'
        self.rejected(self.capture)

    def test_wrong_volume_refused(self):
        self.docker.containers['spep-game-postgres']['Mounts'][0]['Name'] = 'shared-pgdata'
        self.rejected(self.capture)

    def test_wrong_volume_label_refused(self):
        self.docker.volumes['spep-game-pgdata']['Labels']['com.docker.compose.project'] = 'shared'
        self.rejected(self.capture)

    def test_extra_mount_refused(self):
        self.docker.containers['spep-game-postgres']['Mounts'].append({'Type': 'bind', 'Destination': '/wal'})
        self.rejected(self.capture)

    def test_symlink_output_root_refused(self):
        linked = self.root / 'linked'
        linked.symlink_to(self.backups)
        self.rejected(lambda: self.capture(backup_root=linked))

    def test_existing_bundle_refused_and_original_preserved(self):
        bundle = self.capture()
        original = (bundle / 'manifest.json').read_bytes()
        self.rejected(self.capture)
        self.assertEqual((bundle / 'manifest.json').read_bytes(), original)

    def test_complete_capture_is_private_and_contains_all_five_images(self):
        bundle = self.capture()
        self.assertEqual(bundle.stat().st_mode & 0o777, 0o700)
        for path in bundle.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        manifest = json.loads((bundle / 'manifest.json').read_text())
        self.assertEqual(len(manifest['containers']), 5)
        self.assertNotIn('do-not-print', (bundle / 'manifest.json').read_text())

    def test_missing_payload_rejected_before_scratch_allocation(self):
        bundle = self.capture()
        (bundle / 'postgres.tar').unlink()
        self.docker.calls.clear()
        self.rejected(lambda: self.verify(bundle))
        self.assertEqual(self.docker.calls, [])

    def test_altered_payload_rejected_before_scratch_allocation(self):
        bundle = self.capture()
        (bundle / 'server.env').write_text('SERVER_ID=other')
        self.docker.calls.clear()
        self.rejected(lambda: self.verify(bundle))
        self.assertEqual(self.docker.calls, [])

    def test_manifest_server_mismatch_refused(self):
        bundle = self.capture()
        path = bundle / 'manifest.json'
        manifest = json.loads(path.read_text())
        manifest['server'] = 'other'
        path.write_text(json.dumps(manifest))
        self.rejected(lambda: self.verify(bundle))

    def test_archive_traversal_and_links_refused_leaving_incomplete_bundle(self):
        for name, kind in [('../escape', tarfile.REGTYPE), ('/absolute', tarfile.REGTYPE),
                           ('./pg_wal', tarfile.SYMTYPE), ('./link', tarfile.LNKTYPE)]:
            with self.subTest(name=name):
                self.docker.archive = archive(name=name, kind=kind)
                self.rejected(self.capture)
                bundle = next(self.backups.iterdir())
                self.assertTrue((bundle / 'INCOMPLETE').exists())
                self.assertFalse((bundle / 'manifest.json').exists())
                import shutil
                shutil.rmtree(bundle)

    def test_scratch_collision_does_not_delete_existing_resource(self):
        bundle = self.capture()
        collision = 'os-recovery-fixturetoken-pgdata'
        self.docker.volumes[collision] = {'Name': collision, 'Labels': {}}
        self.rejected(lambda: self.verify(bundle))
        self.assertIn(collision, self.docker.volumes)

    def test_postgres_start_failure_cleans_only_owned_scratch(self):
        bundle = self.capture()
        self.docker.start_failure = '-postgres'
        self.rejected(lambda: self.verify(bundle))
        self.assertEqual(set(self.docker.containers), {'spep-' + s for s in SERVICES})
        self.assertEqual(set(self.docker.volumes), {'spep-game-pgdata', 'spep-game-redisdata'})
        self.assertFalse(json.loads((bundle / 'verification.json').read_text())['success'])

    def test_redis_start_failure_refused(self):
        bundle = self.capture()
        self.docker.start_failure = '-redis'
        self.rejected(lambda: self.verify(bundle))

    def test_cleanup_ownership_mismatch_never_deletes_foreign_resource(self):
        bundle = self.capture()
        self.docker.ownership_failure = True
        self.rejected(lambda: self.verify(bundle))
        self.assertFalse(json.loads((bundle / 'verification.json').read_text())['cleanup']['success'])

    def test_absent_selected_world_refused(self):
        bundle = self.capture()
        self.docker.absent_world = True
        self.rejected(lambda: self.verify(bundle))

    def test_failed_migration_refused(self):
        bundle = self.capture()
        self.docker.failed_migration = True
        self.rejected(lambda: self.verify(bundle))

    def test_redis_pong_required(self):
        bundle = self.capture()
        self.docker.redis_failure = True
        self.rejected(lambda: self.verify(bundle))

    def test_happy_path_restores_before_database_start_and_preserves_source(self):
        bundle = self.capture()
        self.docker.calls.clear()
        result = self.verify(bundle)
        self.assertTrue(result['success'])
        self.assertEqual(result['postgres']['counts'], {'world_state': 1, 'city': 2, 'nation': 1, 'general': 3})
        self.assertEqual(result['postgres']['versions'], ['1', '48'])
        self.assertTrue(result['cleanup']['success'])
        self.assertFalse(result['application_boot_verified'])
        creates = [c for c in self.docker.calls if c[:2] == ['container', 'create']]
        self.assertEqual(len(creates), 4)
        self.assertIn('-xpf', creates[0])
        self.assertIn('-xpf', creates[1])
        self.assertTrue(all('sha256:' in ' '.join(c) for c in creates))
        self.assertNotIn('do-not-print', json.dumps(self.docker.calls))
        self.assertNotIn('OPENSAMGUK_WORLD_ID', json.dumps(result))
        self.assertEqual(set(self.docker.volumes), {'spep-game-pgdata', 'spep-game-redisdata'})

    def test_dump_fingerprint_ignores_only_generated_guard_lines(self):
        first = b'-- header\n\\restrict ABC123\nCOPY city FROM stdin;\n1\n\\.\n\\unrestrict ABC123\n'
        second = first.replace(b'ABC123', b'Z998X')
        expected = hashlib.sha256(b'-- header\nCOPY city FROM stdin;\n1\n\\.\n').hexdigest()
        self.assertEqual(recovery.logical_dump_hash(io.BytesIO(first)), expected)
        self.assertEqual(recovery.logical_dump_hash(io.BytesIO(second)), expected)
        changed = first.replace(b'\n1\n', b'\n2\n')
        self.assertNotEqual(recovery.logical_dump_hash(io.BytesIO(changed)), expected)

    def test_saved_images_reloaded_when_missing(self):
        bundle = self.capture()
        self.docker.images.clear()
        self.assertTrue(self.verify(bundle)['success'])
        self.assertIn(['image', 'load'], self.docker.calls)

    def test_private_sql_bytes_use_subprocess_input_not_a_fileno(self):
        def subprocess_boundary(argv, **kwargs):
            self.assertEqual(kwargs.get('input'), b'SELECT private_fixture;')
            self.assertNotIn('stdin', kwargs)
            self.assertNotIn('private_fixture', ' '.join(argv))
            self.assertEqual(argv[:3], ['docker', '--host', 'unix:///var/run/docker.sock'])
            return type('Result', (), {'stdout': b'ok'})()
        with patch.object(recovery.subprocess, 'run', side_effect=subprocess_boundary):
            self.assertEqual(recovery.Docker().run(['container', 'exec', '-i', 'fixture', 'psql'],
                stdin=io.BytesIO(b'SELECT private_fixture;')), b'ok')

    def test_image_archive_link_rejected_before_image_load(self):
        bundle = self.capture()
        path = bundle / 'images.tar'
        path.write_bytes(archive(name='link', kind=tarfile.SYMTYPE))
        metadata = bundle / 'manifest.json'
        manifest = json.loads(metadata.read_text())
        manifest['payloads']['images.tar'] = {'size': path.stat().st_size,
                                            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        metadata.write_text(json.dumps(manifest))
        self.docker.calls.clear()
        self.rejected(lambda: self.verify(bundle))
        self.assertEqual(self.docker.calls, [])

    def test_empty_pgdata_is_rejected_before_capture_can_complete(self):
        self.docker.archive = archive(name='unrelated')
        self.rejected(self.capture)

    def test_unclean_database_exit_refused(self):
        self.docker.containers['spep-game-postgres']['State']['ExitCode'] = 1
        self.rejected(self.capture)

    def test_interrupt_cannot_write_success_report(self):
        bundle = self.capture()
        original = self.docker.run
        def interrupted(args, **kwargs):
            if args[:2] == ['container', 'exec']:
                raise KeyboardInterrupt()
            return original(args, **kwargs)
        self.docker.run = interrupted
        self.rejected(lambda: self.verify(bundle))
        self.assertFalse(json.loads((bundle / 'verification.json').read_text())['success'])

    def test_volume_create_race_never_extracts_or_deletes_foreign_volume(self):
        bundle = self.capture()
        self.docker.calls.clear()
        self.docker.volume_race = True
        self.rejected(lambda: self.verify(bundle))
        self.assertFalse(any(call[:2] == ['container', 'create'] for call in self.docker.calls))
        self.assertIn('os-recovery-fixturetoken-pgdata', self.docker.volumes)

    def test_nested_operation_keeps_outer_lock_until_exception_exit(self):
        def no_wait(_):
            raise recovery.RecoveryError('competing lock remained blocked')
        self.helper.sleep = no_wait
        competitor = recovery.Recovery(lock_path=self.root / 'lock', sleep=no_wait)
        def try_competitor():
            with competitor.locked():
                self.fail('outer lock was released')
        with self.assertRaisesRegex(RuntimeError, 'outer failure'):
            with self.helper.locked():
                with self.helper.locked():
                    self.rejected(try_competitor)
                bundle = self.capture()
                self.rejected(try_competitor)
                self.assertTrue(self.verify(bundle)['success'])
                self.rejected(try_competitor)
                raise RuntimeError('outer failure')
        with competitor.locked():
            pass

    def test_other_thread_cannot_reenter_same_instance(self):
        def no_wait(_):
            raise recovery.RecoveryError('competing lock remained blocked')
        self.helper.sleep = no_wait
        results = []
        def contender():
            try:
                with self.helper.locked():
                    results.append('entered')
            except recovery.RecoveryError:
                results.append('rejected')
        with self.helper.locked():
            thread = threading.Thread(target=contender)
            thread.start()
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(results, ['rejected'])

    def test_forked_process_cannot_reenter_and_parent_lock_remains_held(self):
        def no_wait(_):
            raise recovery.RecoveryError('competing lock remained blocked')
        self.helper.sleep = no_wait
        with self.helper.locked():
            child = os.fork()
            if child == 0:
                try:
                    with self.helper.locked():
                        os._exit(3)
                except recovery.RecoveryError:
                    try:
                        competitor = recovery.Recovery(lock_path=self.root / 'lock', sleep=no_wait)
                        with competitor.locked():
                            os._exit(5)
                    except recovery.RecoveryError:
                        os._exit(0)
                except BaseException:
                    os._exit(4)
            _, status = os.waitpid(child, 0)
            self.assertEqual(os.waitstatus_to_exitcode(status), 0)
            descriptor = os.open(self.root / 'lock', os.O_RDWR)
            try:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(descriptor)

    def test_failed_reverification_invalidates_prior_success_without_docker(self):
        bundle = self.capture()
        for change in ('altered', 'missing'):
            with self.subTest(change=change):
                original = (bundle / 'postgres.tar').read_bytes()
                self.assertTrue(self.verify(bundle)['success'])
                if change == 'altered':
                    (bundle / 'postgres.tar').write_bytes(b'corrupt')
                else:
                    (bundle / 'postgres.tar').unlink()
                self.docker.calls.clear()
                self.rejected(lambda: self.verify(bundle))
                self.assertEqual(self.docker.calls, [])
                report = json.loads((bundle / 'verification.json').read_text())
                if not (bundle / 'postgres.tar').exists():
                    (bundle / 'postgres.tar').touch(mode=0o600)
                (bundle / 'postgres.tar').write_bytes(original)
                self.assertFalse(report['success'])
                self.assertIsNotNone(report['finished_at'])

    def test_invalid_verify_target_and_symlink_path_preserve_existing_report(self):
        bundle = self.capture()
        self.assertTrue(self.verify(bundle)['success'])
        before = (bundle / 'verification.json').read_bytes()
        self.rejected(lambda: self.verify(bundle, server='PEP', confirm='VERIFY PEP'))
        linked = self.root / 'linked-bundle'
        linked.symlink_to(bundle)
        self.rejected(lambda: self.verify(linked))
        self.assertEqual((bundle / 'verification.json').read_bytes(), before)

    def test_leftover_staging_file_cannot_preserve_an_old_success(self):
        bundle = self.capture()
        self.assertTrue(self.verify(bundle)['success'])
        pending = bundle / 'verification.json.pending'
        pending.touch(mode=0o600)
        self.docker.calls.clear()
        with self.assertRaises((recovery.RecoveryError, OSError)):
            self.verify(bundle)
        self.assertFalse(json.loads((bundle / 'verification.json').read_text())['success'])
        self.assertEqual(self.docker.calls, [])

    def test_rejected_cross_thread_operations_preserve_outer_resource_ownership(self):
        bundle = self.capture()
        self.helper.token_factory = lambda: 'threadcontender'
        for action in (self.capture, lambda: self.verify(bundle)):
            with self.subTest(action=action):
                results = []
                owned = [('container', 'os-recovery-existing-owned', 'existing-identity')]
                def contender():
                    try:
                        action()
                    except recovery.RecoveryError:
                        results.append('rejected')
                with self.helper.locked():
                    self.helper.owned = list(owned)
                    thread = threading.Thread(target=contender)
                    thread.start()
                    thread.join(timeout=2)
                    self.assertFalse(thread.is_alive())
                    self.assertEqual(results, ['rejected'])
                    self.assertEqual(self.helper.owned, owned)

    def test_readiness_exhaustion_still_reports_failure(self):
        attempts = []
        def unavailable(args, **kwargs):
            attempts.append(args)
            raise recovery.RecoveryError('not ready')
        self.docker.run = unavailable
        with self.assertRaisesRegex(recovery.RecoveryError, 'did not become ready'):
            self.helper.wait_ready(['container', 'exec', 'fixture', 'pg_isready'])
        self.assertEqual(len(attempts), 30)

    def test_malformed_nested_manifest_never_leaves_success_or_calls_docker(self):
        bundle = self.capture()
        path = bundle / 'manifest.json'
        original = json.loads(path.read_text())
        cases = [(['containers', 'game-postgres', 'labels'], []),
                 (['volumes', 'game-postgres', 'labels'], []),
                 (['containers', 'game-postgres', 'image_id'], []),
                 (['containers', 'game-postgres', 'mounts'], 'invalid'),
                 (['containers', 'game-postgres', 'mounts'], [[]]),
                 (['containers', 'game-postgres'], None),
                 (['volumes', 'game-postgres'], None),
                 (['containers'], []), (['payloads'], []), (['volumes'], [])]
        for keys, value in cases:
            with self.subTest(keys=keys):
                path.write_text(json.dumps(original))
                self.assertTrue(self.verify(bundle)['success'])
                malformed = copy.deepcopy(original)
                target = malformed
                for key in keys[:-1]:
                    target = target[key]
                target[keys[-1]] = value
                path.write_text(json.dumps(malformed))
                self.docker.calls.clear()
                caught = None
                try:
                    self.verify(bundle)
                except Exception as error:
                    caught = error
                report = json.loads((bundle / 'verification.json').read_text())
                self.assertFalse(report['success'])
                self.assertEqual(self.docker.calls, [])
                self.assertIsInstance(caught, recovery.RecoveryError)

    def test_unexpected_exception_before_checks_never_publishes_success(self):
        bundle = self.capture()
        self.assertTrue(self.verify(bundle)['success'])
        self.docker.calls.clear()
        with patch.object(self.helper, 'validate_bundle', side_effect=RuntimeError('unexpected fixture failure')):
            with self.assertRaises(RuntimeError):
                self.verify(bundle)
        self.assertFalse(json.loads((bundle / 'verification.json').read_text())['success'])
        self.assertEqual(self.docker.calls, [])

    def test_unexpected_exception_after_postgres_check_never_publishes_success(self):
        bundle = self.capture()
        self.assertTrue(self.verify(bundle)['success'])
        with patch.object(self.helper, 'redis_check', side_effect=RuntimeError('unexpected fixture failure')):
            with self.assertRaises(RuntimeError):
                self.verify(bundle)
        report = json.loads((bundle / 'verification.json').read_text())
        self.assertIn('postgres', report)
        self.assertNotIn('redis', report)
        self.assertFalse(report['success'])
        self.assertTrue(report['cleanup']['success'])
        self.assertEqual(set(self.docker.volumes), {'spep-game-pgdata', 'spep-game-redisdata'})


class RollbackRunbookTests(unittest.TestCase):
    """Execute the actual documented shell with recording commands, never a Docker daemon."""
    def execute_runbook(self, fail_at):
        document = (Path(__file__).resolve().parents[2] / 'docs/admin/game-server-recovery.md').read_text()
        section = document.split('## 승인된 rollback의 정확한 교체 순서', 1)[1].split('## 로컬 검증', 1)[0]
        blocks = [textwrap.dedent(block) for block in re.findall(r'```bash\n(.*?)```', section, re.S)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / 'bundle').mkdir()
            (root / 'stack').mkdir()
            (root / 'operation').mkdir()
            (root / 'failed').mkdir()
            for filename in ['postgres.tar', 'redis.tar']:
                (root / 'bundle' / filename).write_bytes(b'fixture')
            record = root / 'calls'
            # These shell functions replace external commands while the published control flow runs unchanged.
            boundary = r'''
python3() {
  printf 'python3 %s\n' "$*" >> "$RECORD"
  [ "$FAIL_AT" != preflight ] || return 19
  case "$*" in *game-postgres*) printf 'sha256:1111111111111111111111111111111111111111111111111111111111111111\n';;
                 *game-redis*) printf 'sha256:2222222222222222222222222222222222222222222222222222222222222222\n';; esac
  return 0
}
docker() {
  [ "$1" = --host ] && [ "$2" = unix:///var/run/docker.sock ] || return 26
  shift 2
  printf 'docker %s\n' "$*" >> "$RECORD"
  case "$FAIL_AT:$*" in
    delete:'container rm '*) return 21;;
    create:'volume create '*) return 22;;
    empty_pg:*'--entrypoint sh'*spep-game-pgdata*) return 23;;
    empty_redis:*'--entrypoint sh'*spep-game-redisdata*) return 24;;
    extract:*'--entrypoint tar'*) return 25;;
  esac
  return 0
}
install() { printf 'install %s\n' "$*" >> "$RECORD"; return 0; }
flock() { printf 'flock %s\n' "$*" >> "$RECORD"; return 0; }
'''
            env = {**os.environ, 'BUNDLE': str(root / 'bundle'), 'FAILED_BUNDLE': str(root / 'failed'),
                   'STACK': str(root / 'stack'), 'OPERATION_DIR': str(root / 'operation'), 'TOOL': '/fixture/tool.py',
                   'RECORD': str(record), 'FAIL_AT': fail_at}
            # Prevent the documentation's fixed host lock redirect from touching an operational lock.
            # The lock itself is independently tested above; only this filesystem boundary is redirected.
            script = (boundary + '\n'.join(blocks)).replace('/tmp/opensamguk-production.lock', str(root / 'lock'))
            result = subprocess.run(['bash'], input=script, text=True, capture_output=True, env=env)
            return result.returncode, record.read_text().splitlines() if record.exists() else []

    def test_documented_preflight_failure_prevents_every_destructive_command(self):
        status, calls = self.execute_runbook('preflight')
        self.assertNotEqual(status, 0)
        self.assertFalse(any(line.startswith('docker ') for line in calls), calls)

    def test_documented_nonempty_target_prevents_all_extraction_and_env_restore(self):
        for failure in ['empty_pg', 'empty_redis']:
            with self.subTest(failure=failure):
                status, calls = self.execute_runbook(failure)
                self.assertNotEqual(status, 0)
                self.assertFalse(any('--entrypoint tar' in line or line.startswith('install -m 600') for line in calls), calls)

    def test_documented_mutation_failure_prevents_later_phases(self):
        for failure, forbidden in [('delete', 'docker volume rm'), ('create', '--entrypoint sh'),
                                   ('extract', 'install -m 600')]:
            with self.subTest(failure=failure):
                status, calls = self.execute_runbook(failure)
                self.assertNotEqual(status, 0)
                self.assertFalse(any(forbidden in line for line in calls), calls)

    def test_documented_success_checks_both_empty_volumes_before_extraction(self):
        status, calls = self.execute_runbook('none')
        self.assertEqual(status, 0, calls)
        checks = [index for index, line in enumerate(calls) if '--entrypoint sh' in line]
        extracts = [index for index, line in enumerate(calls) if '--entrypoint tar' in line]
        self.assertEqual(len(checks), 2)
        self.assertEqual(len(extracts), 2)
        self.assertLess(max(checks), min(extracts))


if __name__ == '__main__':
    unittest.main(verbosity=2)
