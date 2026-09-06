#!/usr/bin/env python3
"""Synthetic operator tests; Docker/process boundaries are recording doubles."""
import contextlib
import copy
import io
import hashlib
import builtins
import json
import os
from pathlib import Path
import tempfile
import stat
import unittest
from unittest.mock import patch

try:
    import legacy_deployer_bootstrap as bootstrap
except ModuleNotFoundError:
    bootstrap = None
from test_deployer_socket_drain import Firewall, HEADER, row

OLD = 'a' * 64
NEW = 'b' * 64
OLD_IMAGE = 'sha256:' + 'c' * 64
NEW_IMAGE = 'sha256:' + 'd' * 64
MARKER = b'{"capability":"maintenance-v1"}\n'
LEASE = 'private-fixture-lease'
TAG = 'fixture-env-value'


class Holder:
    def __init__(self, owner, fence):
        self.owner = owner
        self.pid = 43
        self.fence = bootstrap.replace(fence, pid=43)
        self.owner.events.append('holder')

    def verify(self):
        if self.owner.fault == 'holder' or (self.owner.fault == 'holder-lost' and self.owner.replaced):
            raise RuntimeError('holder identity uncertain')
        return self.fence

    def release(self):
        self.owner.events.append('release')
        if self.owner.fault == 'release':
            raise RuntimeError('release uncertain')


class Boundary:
    def __init__(self, root, fault=None):
        self.root, self.fault = root, fault
        self.events, self.commands = [], []
        self.firewall = Firewall()
        self.current = OLD
        self.image = OLD_IMAGE
        self.replaced = False
        self.store_checks = 0
        self.compose = []

    def container(self):
        exited = self.fault in ('exited', 'exited-after-up', 'exited-drift', 'exited-journal', 'exited-restarting') and self.replaced
        return {'Id': self.current, 'Name': '/opensamguk-deployer',
                'Image': OLD_IMAGE if self.fault == 'wrong-new-image' and self.replaced else self.image,
                'State': {'Pid': 0 if exited else (42 if self.current == OLD else 44),
                          'Running': not exited, 'Restarting': self.fault == 'exited-restarting',
                          'Status': 'exited' if exited else 'running'},
                'HostConfig': {'RestartPolicy': {'Name': 'no'}},
                'Mounts': [{'Type': 'bind', 'Source': str(self.root / 'stack'),
                            'Destination': '/workspace', 'RW': True}],
                'Config': {'Labels': {'com.docker.compose.project': 'fixture-shared',
                                     'com.docker.compose.service': 'deployer'},
                           'Env': ['COMPOSE_DIR=/workspace', 'SERVERS_DIR=/workspace/servers',
                            'DEPLOYER_MAINTENANCE_FILE=/workspace/servers/.deployer-maintenance',
                            'DEPLOYER_LIFECYCLE_JOURNAL_FILE=/workspace/servers/.deployer-lifecycle-journal']}}

    def __call__(self, argv, *, stdin=None):
        self.commands.append(argv)
        if argv[:3] == ['docker', 'image', 'inspect']:
            self.events.append('image')
            return json.dumps([{'Id': NEW_IMAGE if self.fault != 'image' else OLD_IMAGE}])
        if argv[:3] == ['docker', 'container', 'inspect']:
            assert '--format' in argv, 'full inspect would copy the container token onto the host'
            self.events.append('identity')
            value = self.container()
            if self.fault == 'bind':
                value['Mounts'][0]['Source'] = '/wrong'
            if self.fault == 'identity':
                value['Id'] = NEW
            return json.dumps([value])
        if argv[:3] == ['docker', 'exec', '-i']:
            mode = argv[-1]
            self.events.append(mode)
            if mode == 'identity':
                return json.dumps({'startTime': 12345, 'netns': 'net:[999]' if self.fault == 'remote-identity' else 'net:[777]'})
            if mode == 'barrier':
                if self.fault == 'env':
                    (self.root / 'stack/.env').write_text('IMAGE_TAG=' + TAG + '\nDRIFT=1\n')
                return json.dumps({'verified': self.fault != 'barrier',
                    'envHash': hashlib.sha256((self.root / 'stack/.env').read_bytes()).hexdigest()})
            if mode == 'enter':
                if self.fault != 'marker':
                    (self.root / 'stack/servers/.deployer-maintenance').write_bytes(MARKER)
                return json.dumps({'status': 200, 'body': {'capability': 'maintenance-v1',
                    'state': 'drained', 'lease': '' if self.fault == 'lease' else LEASE}})
            if mode == 'marker':
                paths = [self.root / 'stack', self.root / 'stack/servers',
                         self.root / 'stack/servers/.deployer-maintenance']
                identities = [[p.stat().st_dev, p.stat().st_ino] for p in paths]
                if self.fault == 'remote-marker':
                    identities[2][1] += 1
                return json.dumps({'markerVerified': True, 'identities': identities})
            if mode == 'closed':
                return json.dumps({'status': 200, 'body': {'capability': 'maintenance-v1', 'state': 'drained'}})
            if mode == 'capability':
                return json.dumps({'status': 409, 'body': {'error': 'maintenance idle admission unavailable'}})
            if mode == 'ready':
                if self.fault in ('drift', 'late-drift'):
                    self.store_checks += 1
                    if self.fault == 'drift':
                        self.drift()
                return json.dumps({'status': 503 if self.fault == 'ready' else 200,
                                   'body': {'status': 'ready'}})
            if mode == 'leave':
                return json.dumps({'status': 200, 'body': {'capability': 'maintenance-v1', 'state': 'open'}})
            raise AssertionError(mode)
        if argv[:2] == ['docker', 'compose']:
            self.events.append('replace')
            self.compose.append(argv)
            self.assert_override(argv)
            self.replaced = True
            self.current, self.image = NEW, NEW_IMAGE
            if self.fault == 'exited-drift':
                self.drift()
            if self.fault == 'exited-journal':
                (self.root / 'stack/servers/.deployer-lifecycle-journal').write_text('{}')
            if self.fault in ('running', 'exited', 'exited-drift', 'exited-journal', 'exited-restarting') and len(self.compose) == 1:
                raise RuntimeError('synthetic compose failure')
            if len(self.compose) == 2:
                self.image = OLD_IMAGE
            return ''
        if argv[0] in ('cat', 'readlink', 'nsenter'):
            remapped = [a.replace('/proc/43/', '/proc/42/') for a in argv]
            if remapped[:3] == ['nsenter', '--target', '43']:
                remapped[2] = '42'
            if '-I' in argv:
                self.events.append('fence')
            if '-D' in argv:
                self.events.append('remove')
                if self.fault == 'late-drift':
                    self.drift()
                if self.fault == 'rules':
                    raise RuntimeError('rule mismatch')
            return self.firewall(remapped)
        raise AssertionError(argv)

    def drift(self):
        path = self.root / 'stack/servers/.deployer-operations.json'
        value = json.loads(path.read_text())
        value['operations'][0]['status'] = 'cancelled'
        path.write_text(json.dumps(value))

    def assert_override(self, argv):
        override = Path(argv[argv.index('--file', argv.index('--file') + 1) + 1])
        assert override.stat().st_mode & 0o777 == 0o600
        image = NEW_IMAGE if not self.compose[:-1] else OLD_IMAGE
        assert json.loads(override.read_text()) == {'services': {'deployer': {'image': image}}}
        assert argv[-7:] == ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'deployer']


class LegacyBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(bootstrap, 'legacy bootstrap implementation is missing')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        (self.root / 'stack/servers').mkdir(parents=True)
        (self.root / 'evidence').mkdir(mode=0o700)
        (self.root / 'stack/.env').write_text('IMAGE_TAG=' + TAG + '\nOTHER=private-fixture-token\n')
        (self.root / 'stack/docker-compose.shared.yml').write_text('services: {}\n')
        record = {'operationId': 'e' * 32, 'kind': 'close', 'subjectId': 'fixture',
                  'requestFingerprint': 'f' * 64, 'status': 'succeeded', 'httpStatus': 200,
                  'publicMessage': '', 'createdAt': '2026-09-06T00:00:00Z',
                  'updatedAt': '2026-09-06T00:00:01Z'}
        (self.root / 'stack/servers/.deployer-operations.json').write_text(
            json.dumps({'version': 1, 'operations': [record]}))
        base = self.root / 'proc/42'
        for part in ('net', 'fd', 'ns'):
            (base / part).mkdir(parents=True)
        (base / 'stat').write_text('42 (fixture) S ' + '0 ' * 18 + '12345 0\n')
        (base / 'ns/net').symlink_to('net:[777]')
        (base / 'net/tcp').write_text(HEADER + row())
        (base / 'net/tcp6').write_text(HEADER)
        (base / 'fd/3').symlink_to('socket:[123]')

    def execute(self, fault=None):
        self.boundary = Boundary(self.root, fault)
        self.output = io.StringIO()
        events = self.boundary.events
        class LockedOperator(bootstrap.Operator):
            @contextlib.contextmanager
            def locked(operator, *args):
                with super().locked(*args):
                    events.append('lock')
                    try:
                        yield
                    finally:
                        events.append('unlock')
        operator = LockedOperator(runner=self.boundary, proc_root=self.root / 'proc',
             lock_path=self.root / 'lock', timeout=0,
             holder_factory=lambda f: Holder(self.boundary, f))
        real_drain, real_store = bootstrap.drain.inspect_port_drain, bootstrap.terminal_store
        def inspected(*args):
            events.append('drain')
            return real_drain(*args)
        def stored(*args):
            events.append('store')
            return real_store(*args)
        with contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.output), \
             patch.object(bootstrap.drain, 'inspect_port_drain', side_effect=inspected), \
             patch.object(bootstrap, 'terminal_store', side_effect=stored):
            status = operator.execute(self.root / 'stack', NEW_IMAGE, OLD, self.root / 'evidence')
        for private in (TAG, LEASE, 'private-fixture-token', 'requestFingerprint', 'publicMessage'):
            self.assertNotIn(private, self.output.getvalue())
            for path in (self.root / 'evidence').iterdir():
                self.assertNotIn(private, path.read_text())
        return status

    def test_successful_exact_replacement(self):
        self.assertEqual(self.execute(), 0)
        events = self.boundary.events
        self.assertEqual(len(self.boundary.compose), 1)
        self.assertEqual(events[0], 'lock')
        self.assertEqual(events[-1], 'unlock')
        self.assertEqual([event for event in events if event in ('lock', 'image', 'drain', 'barrier',
            'store', 'enter', 'holder', 'replace', 'closed', 'capability', 'ready', 'release', 'leave', 'unlock')],
            ['lock', 'image', 'drain', 'barrier', 'drain', 'store', 'enter', 'drain', 'holder',
             'store', 'replace', 'closed', 'capability', 'ready', 'store', 'release', 'store', 'leave', 'unlock'])
        for first, second in [('image', 'fence'), ('fence', 'barrier'), ('barrier', 'enter'),
            ('enter', 'holder'), ('holder', 'replace'), ('replace', 'closed'),
            ('closed', 'capability'), ('capability', 'ready'), ('ready', 'remove'),
            ('remove', 'release'), ('release', 'leave')]:
            self.assertLess(events.index(first), events.index(second))
        self.assertEqual(self.boundary.firewall.rules, {'iptables': [], 'ip6tables': []})

    def test_unmapped_handler_fd_leaves_old_controller_fenced(self):
        (self.root / 'proc/42/fd/7').symlink_to('socket:[777]')
        self.assertNotEqual(self.execute(), 0)
        self.assertNotIn('enter', self.boundary.events)
        self.assertFalse(self.boundary.compose)
        self.assertEqual(len(self.boundary.firewall.rules['iptables']), 1)

    def test_failures_before_replacement_never_cancel_or_reopen(self):
        for fault in ('image', 'identity', 'bind', 'barrier', 'env', 'lease', 'marker', 'holder', 'remote-marker', 'remote-identity'):
            with self.subTest(fault=fault):
                self.setUp()
                self.assertNotEqual(self.execute(fault), 0)
                self.assertFalse(self.boundary.compose)
                self.assertNotIn('leave', self.boundary.events)

    def test_nonterminal_and_journal_block_before_enter(self):
        for status in ('pending', 'running', 'recovery_required', 'journal'):
            with self.subTest(status=status):
                self.setUp()
                path = self.root / 'stack/servers/.deployer-operations.json'
                if status == 'journal':
                    path.with_name('.deployer-lifecycle-journal').write_text('{}')
                else:
                    value = json.loads(path.read_text())
                    value['operations'][0]['status'] = status
                    path.write_text(json.dumps(value))
                self.assertNotEqual(self.execute(), 0)
                self.assertNotIn('enter', self.boundary.events)

    def test_post_replacement_failures_never_reopen(self):
        for fault in ('ready', 'drift', 'late-drift', 'rules', 'release', 'running', 'holder-lost',
                      'wrong-new-image', 'exited-drift', 'exited-journal', 'exited-restarting'):
            with self.subTest(fault=fault):
                self.setUp()
                self.assertNotEqual(self.execute(fault), 0)
                self.assertEqual(len(self.boundary.compose), 1)
                self.assertNotIn('leave', self.boundary.events)

    def test_exited_replacement_falls_back_closed_only(self):
        for fault in ('exited', 'exited-after-up'):
            with self.subTest(fault=fault):
                self.setUp()
                self.assertNotEqual(self.execute(fault), 0)
                self.assertEqual(len(self.boundary.compose), 2)
                self.assertNotIn('leave', self.boundary.events)
                self.assertNotIn('remove', self.boundary.events)

    def test_marker_file_and_directory_fsync_failure_blocks_replace(self):
        for directory in (False, True):
            with self.subTest(directory=directory):
                self.setUp()
                with patch.object(bootstrap, 'sync_marker', side_effect=OSError('synthetic marker fsync failure')):
                    self.assertNotEqual(self.execute(), 0)
                self.assertFalse(self.boundary.compose)

    def test_marker_fsync_errors_are_checked_and_inode_retained(self):
        path = self.root / 'stack/servers/.deployer-maintenance'
        path.write_bytes(MARKER)
        inode = (path.stat().st_dev, path.stat().st_ino)
        self.assertEqual(bootstrap.sync_marker(self.root / 'stack'), inode)
        for directory in (False, True):
            seen = []
            def fsync(fd):
                is_directory = stat.S_ISDIR(os.fstat(fd).st_mode)
                seen.append(is_directory)
                if is_directory == directory:
                    raise OSError('synthetic checked sync failure')
            with patch.object(bootstrap.os, 'fsync', side_effect=fsync):
                with self.assertRaises(OSError):
                    bootstrap.sync_marker(self.root / 'stack')
            self.assertIn(directory, seen)

    def test_marker_symlink_and_unexpected_content_refused(self):
        path = self.root / 'stack/servers/.deployer-maintenance'
        path.symlink_to(self.root / 'stack/.env')
        with self.assertRaises(OSError):
            bootstrap.sync_marker(self.root / 'stack')
        path.unlink()
        path.write_bytes(MARKER + b' ')
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.sync_marker(self.root / 'stack')

    def test_store_rejects_duplicate_unknown_and_new_records(self):
        path = self.root / 'stack/servers/.deployer-operations.json'
        original = path.read_text()
        for malformed in ('{"version":1,"version":1,"operations":[]}',
                          '{"version":1,"operations":[],"extra":1}',
                          '{"version":true,"operations":[]}', original + '{}'):
            path.write_text(malformed)
            with self.assertRaises((ValueError, bootstrap.Refusal)):
                bootstrap.terminal_store(self.root / 'stack')
        path.write_text(original)
        before = bootstrap.terminal_store(self.root / 'stack')
        after = copy.deepcopy(before)
        after['1' * 32] = dict(next(iter(before.values())), operationId='1' * 32)
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.continuity(before, after, bootstrap.datetime.now(bootstrap.timezone.utc))
        now = bootstrap.timestamp('2026-09-07T00:00:01Z')
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.continuity(before, {}, now)
        bootstrap.continuity(before, {}, bootstrap.timestamp('2026-09-07T00:00:02Z'))

    def test_modified_recorded_rule_refuses_recovery(self):
        self.assertEqual(self.execute(), 0)
        record = json.loads(next((self.root / 'evidence').iterdir()).read_text())
        fence = record['original_fence']
        bootstrap.fence_from_record(fence)
        fence['rules'][0][1] += ['-s', '127.0.0.1']
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.fence_from_record(fence)

    def test_old_recovery_refuses_unverified_outstanding_holder(self):
        (self.root / 'proc/42/fd/7').symlink_to('socket:[777]')
        self.assertNotEqual(self.execute(), 0)
        path = next((self.root / 'evidence').iterdir())
        record = json.loads(path.read_text())
        record['holder_outstanding'] = True
        record['holder_pid'] = 43
        path.write_text(json.dumps(record))
        operator = bootstrap.Operator(runner=self.boundary, proc_root=self.root / 'proc',
                                      lock_path=self.root / 'lock')
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.remove_recorded(operator, path)
        self.assertTrue(self.boundary.firewall.rules['iptables'])

    def test_old_recovery_removes_exact_fence_without_maintenance(self):
        (self.root / 'proc/42/fd/7').symlink_to('socket:[777]')
        self.assertNotEqual(self.execute(), 0)
        path = next((self.root / 'evidence').iterdir())
        operator = bootstrap.Operator(runner=self.boundary, proc_root=self.root / 'proc',
                                      lock_path=self.root / 'lock')
        with contextlib.redirect_stdout(io.StringIO()):
            bootstrap.remove_recorded(operator, path)
        self.assertEqual(self.boundary.firewall.rules, {'iptables': [], 'ip6tables': []})
        self.assertNotIn('enter', self.boundary.events)
        self.assertNotIn('leave', self.boundary.events)

    def test_lock_contention_and_private_directory_fail_before_commands(self):
        import fcntl
        boundary = Boundary(self.root)
        operator = bootstrap.Operator(runner=boundary, lock_path=self.root / 'lock')
        fd = os.open(self.root / 'lock', os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertNotEqual(operator.execute(self.root / 'stack', NEW_IMAGE, OLD,
                                                     self.root / 'evidence'), 0)
        finally:
            os.close(fd)
        self.assertFalse(boundary.commands)
        (self.root / 'evidence').chmod(0o755)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertNotEqual(operator.execute(self.root / 'stack', NEW_IMAGE, OLD,
                                                 self.root / 'evidence'), 0)
        self.assertFalse(boundary.commands)

    def test_holder_socket_and_identity_checks(self):
        fixture = self.root / 'proc/43/fd'
        fixture.mkdir(parents=True)
        self.assertEqual(self.execute(), 0)
        record = json.loads(next((self.root / 'evidence').iterdir()).read_text())
        fence = bootstrap.fence_from_record(record['holder_fence'])
        bootstrap.verify_holder(self.boundary, self.root / 'proc', fence)
        (fixture / '7').symlink_to('socket:[777]')
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.verify_holder(self.boundary, self.root / 'proc', fence)
        (fixture / '7').unlink()
        self.boundary.firewall.netns = 'net:[999]'
        with self.assertRaises(bootstrap.drain.FenceError):
            bootstrap.verify_holder(self.boundary, self.root / 'proc', fence)

    def test_exact_compose_project_is_retained(self):
        self.assertEqual(self.execute(), 0)
        argv = self.boundary.compose[0]
        self.assertIn('--project-name', argv)
        self.assertEqual(argv[argv.index('--project-name') + 1], 'fixture-shared')

    def test_custom_operation_store_setting_blocks_before_fence(self):
        boundary = Boundary(self.root)
        old_container = boundary.container
        def custom():
            obj = old_container()
            obj['Config']['Env'].append('DEPLOYER_OPERATION_STORE_FILE=/elsewhere/store.json')
            return obj
        boundary.container = custom
        operator = bootstrap.Operator(runner=boundary, proc_root=self.root / 'proc',
                                      lock_path=self.root / 'lock', timeout=0)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertNotEqual(operator.execute(self.root / 'stack', NEW_IMAGE, OLD,
                                                 self.root / 'evidence'), 0)
        self.assertNotIn('fence', boundary.events)

    def test_store_subject_schema_matches_controller(self):
        path = self.root / 'stack/servers/.deployer-operations.json'
        value = json.loads(path.read_text())
        value['operations'][0]['subjectId'] = 'Pep123'
        path.write_text(json.dumps(value))
        bootstrap.terminal_store(self.root / 'stack')
        value['operations'][0]['subjectId'] = 'bad_id'
        path.write_text(json.dumps(value))
        with self.assertRaises(bootstrap.Refusal):
            bootstrap.terminal_store(self.root / 'stack')


class LockIntegrationTests(unittest.TestCase):
    """Real local locks/files; UID-only simulation is supplemented by Linux chown proof."""
    setUp = LegacyBootstrapTests.setUp
    execute = LegacyBootstrapTests.execute
    @contextlib.contextmanager
    def root_ownership(self, foreign=False, root_lock=False):
        stack = self.root / 'stack'
        lock = self.root / 'lock'
        stack_id = (stack.stat().st_dev, stack.stat().st_ino)
        lock_id = (lock.stat().st_dev, lock.stat().st_ino) if lock.exists() else None
        real_fstat, real_stat = os.fstat, os.stat
        def adjusted(info):
            values = list(info)
            identity = (info.st_dev, info.st_ino)
            values[4] = (1001 if identity == stack_id else
                         (1002 if foreign else 0 if root_lock else 1001) if identity == lock_id else 0)
            return os.stat_result(values)
        with patch.object(bootstrap.os, 'geteuid', return_value=0), \
             patch.object(bootstrap.os, 'fstat', side_effect=lambda fd: adjusted(real_fstat(fd))), \
             patch.object(bootstrap.os, 'stat', side_effect=lambda *a, **kw: adjusted(real_stat(*a, **kw))):
            yield

    def make_lock(self):
        lock = self.root / 'lock'
        lock.write_bytes(b'workflow-owned-lock\n')
        lock.chmod(0o664)
        return lock

    def snapshot(self, path):
        info = path.stat()
        return (info.st_dev, info.st_ino, info.st_uid, info.st_gid,
                stat.S_IMODE(info.st_mode), info.st_nlink, path.read_bytes())

    def recover(self, path):
        operator = bootstrap.Operator(runner=self.boundary, proc_root=self.root / 'proc',
                                      lock_path=self.root / 'lock')
        with contextlib.redirect_stdout(io.StringIO()):
            bootstrap.remove_recorded(operator, path)

    def test_workflow_owner_bootstrap_and_cleanup_preserve_exact_lock(self):
        lock = self.make_lock()
        before = self.snapshot(lock)
        with self.root_ownership():
            self.assertEqual(self.execute(), 0)
            prior = set((self.root / 'evidence').iterdir())
            self.assertNotEqual(self.execute('barrier'), 0)
            self.recover(next(iter(set((self.root / 'evidence').iterdir()) - prior)))
        self.assertEqual(self.snapshot(lock), before)

    def test_root_owner_accepted_and_missing_lock_retained(self):
        lock = self.make_lock()
        before = self.snapshot(lock)
        with self.root_ownership(root_lock=True):
            self.assertEqual(self.execute(), 0)
        self.assertEqual(self.snapshot(lock), before)
        lock.unlink()
        with bootstrap.Operator(lock_path=lock).locked(self.root / 'stack'):
            created = self.snapshot(lock)
        self.assertEqual(created[4], 0o600)
        with bootstrap.Operator(lock_path=lock).locked(self.root / 'stack'):
            self.assertEqual(self.snapshot(lock), created)

    def test_foreign_symlink_hardlink_and_nonregular_lock_refused(self):
        for fault in ('foreign', 'symlink', 'hardlink', 'directory', 'fifo'):
            with self.subTest(fault=fault):
                self.setUp()
                lock = self.make_lock()
                target = self.root / 'target'
                if fault == 'symlink':
                    lock.rename(target)
                    lock.symlink_to(target)
                elif fault == 'hardlink':
                    os.link(lock, target)
                elif fault in ('directory', 'fifo'):
                    lock.unlink()
                    lock.mkdir() if fault == 'directory' else os.mkfifo(lock)
                with self.root_ownership(foreign=True) if fault == 'foreign' else contextlib.nullcontext():
                    self.assertNotEqual(self.execute(), 0)
                self.assertFalse(self.boundary.commands)

    def test_stack_or_lock_replacement_around_flock_blocks_effects(self):
        real_flock = bootstrap.fcntl.flock
        for target_name in ('stack', 'lock'):
            for after in (False, True):
                with self.subTest(target=target_name, after=after):
                    self.setUp()
                    self.make_lock()
                    def swapped(fd, flags):
                        if after:
                            real_flock(fd, flags)
                        target = self.root / target_name
                        target.rename(self.root / (target_name + '-prior'))
                        target.mkdir() if target_name == 'stack' else target.write_bytes(b'replacement')
                        if not after:
                            real_flock(fd, flags)
                    with patch.object(bootstrap.fcntl, 'flock', side_effect=swapped):
                        self.assertNotEqual(self.execute(), 0)
                    self.assertFalse(self.boundary.commands)

    def test_shared_inode_contention_blocks_bootstrap_and_cleanup(self):
        lock = self.make_lock()
        with self.root_ownership():
            self.assertNotEqual(self.execute('barrier'), 0)
            path = next((self.root / 'evidence').iterdir())
            before = self.snapshot(lock)
            holder = os.open(lock, os.O_RDWR)
            try:
                bootstrap.fcntl.flock(holder, bootstrap.fcntl.LOCK_EX | bootstrap.fcntl.LOCK_NB)
                self.assertNotEqual(self.execute(), 0)
                self.assertFalse(self.boundary.commands)
                with self.assertRaises(BlockingIOError):
                    self.recover(path)
                self.assertFalse(self.boundary.commands)
                self.assertEqual(self.snapshot(lock), before)
            finally:
                os.close(holder)

    def test_existing_open_flags_and_exclusive_creation_race(self):
        lock = self.make_lock()
        real_open = os.open
        flags_seen = []
        def observed(path, flags, *args, **kwargs):
            if Path(path) == lock:
                flags_seen.append(flags)
            return real_open(path, flags, *args, **kwargs)
        with patch.object(bootstrap.os, 'open', side_effect=observed):
            with bootstrap.Operator(lock_path=lock).locked(self.root / 'stack'):
                pass
        self.assertEqual(len(flags_seen), 1)
        self.assertFalse(flags_seen[0] & (os.O_CREAT | os.O_TRUNC))
        lock.unlink()
        attempts = []
        def raced(path, flags, *args, **kwargs):
            if Path(path) == lock:
                attempts.append(flags)
                if len(attempts) == 2:
                    winner = real_open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
                    os.write(winner, b'race-winner')
                    os.close(winner)
            return real_open(path, flags, *args, **kwargs)
        with patch.object(bootstrap.os, 'open', side_effect=raced):
            self.assertNotEqual(self.execute(), 0)
        self.assertFalse(self.boundary.commands)
        self.assertTrue(attempts[1] & os.O_EXCL)
        self.assertEqual(lock.read_bytes(), b'race-winner')

    def test_pinned_stack_descriptor_closes_before_flock_descriptor(self):
        lock = self.make_lock()
        closed, locked = [], []
        real_close, real_flock = os.close, bootstrap.fcntl.flock
        def close(fd):
            closed.append(fd)
            return real_close(fd)
        def flock(fd, flags):
            locked.append(fd)
            return real_flock(fd, flags)
        operator = bootstrap.Operator(lock_path=lock)
        with patch.object(bootstrap.os, 'close', side_effect=close), \
             patch.object(bootstrap.fcntl, 'flock', side_effect=flock):
            with operator.locked(self.root / 'stack'):
                pinned = operator._stack_pin[1]
                self.assertEqual(os.fstat(pinned).st_ino, (self.root / 'stack').stat().st_ino)
        self.assertEqual(closed[-2:], [pinned, locked[0]])

    def test_cleanup_authentication_and_reread_precede_effects(self):
        for fault in ('permissions', 'directory-permissions', 'missing-stack', 'bytes', 'inode', 'directory-inode', 'stack'):
            with self.subTest(fault=fault):
                self.setUp()
                self.assertNotEqual(self.execute('barrier'), 0)
                path = next((self.root / 'evidence').iterdir())
                if fault == 'permissions':
                    path.chmod(0o644)
                elif fault == 'directory-permissions':
                    path.parent.chmod(0o755)
                elif fault == 'missing-stack':
                    value = json.loads(path.read_text())
                    value.pop('stack')
                    path.write_text(json.dumps(value))
                self.boundary.commands.clear()
                real_flock = bootstrap.fcntl.flock
                def altered(fd, flags):
                    real_flock(fd, flags)
                    if fault == 'bytes':
                        path.write_bytes(path.read_bytes() + b' ')
                    elif fault == 'inode':
                        content = path.read_bytes()
                        path.rename(path.with_suffix('.prior'))
                        path.write_bytes(content)
                        path.chmod(0o600)
                    elif fault == 'directory-inode':
                        prior = path.parent.with_name('evidence-prior')
                        path.parent.rename(prior)
                        path.parent.mkdir(mode=0o700)
                        path.write_bytes((prior / path.name).read_bytes())
                        path.chmod(0o600)
                    elif fault == 'stack':
                        stack = self.root / 'stack'
                        stack.rename(self.root / 'stack-prior')
                        stack.mkdir()
                with patch.object(bootstrap.fcntl, 'flock', side_effect=altered):
                    with self.assertRaises((bootstrap.Refusal, OSError, KeyError)):
                        self.recover(path)
                self.assertFalse(self.boundary.commands)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(bootstrap)

    def test_constant_program_barrier_checks_every_response_field(self):
        import urllib.request
        base = {'ok': True, 'scope': 'shared', 'fields': {'IMAGE_TAG': {'key': 'IMAGE_TAG',
            'value': TAG, 'configured': True, 'writeOnly': False, 'masked': False, 'metadata': {}}},
            'restartRequired': True, 'affectedServices': ['gateway-api']}
        for fault in (None, 'status', 'ok', 'scope', 'fields', 'jobId', 'env', 'duplicate'):
            with self.subTest(fault=fault):
                calls = []
                output = io.StringIO()
                raw = ('IMAGE_TAG=' + TAG + '\nTOKEN=private-fixture-token\n').encode()
                reads = []
                def open_env(path, mode):
                    self.assertEqual((path, mode), ('/workspace/.env', 'rb'))
                    reads.append(path)
                    return io.BytesIO(raw + (b'DRIFT=1\n' if fault == 'env' and len(reads) == 2 else b''))
                class Response(io.BytesIO):
                    code = 200
                class Client:
                    def open(client, req, timeout):
                        calls.append(req)
                        body = copy.deepcopy(base)
                        if req.method == 'PATCH' and fault in ('ok', 'scope', 'jobId'):
                            body[fault] = False if fault == 'ok' else 'incorrect'
                        if req.method == 'PATCH' and fault == 'fields':
                            body['fields']['IMAGE_TAG']['value'] = 'wrong'
                        result = Response(json.dumps(body).encode())
                        if req.method == 'PATCH' and fault == 'status':
                            result.code = 202
                        if fault == 'duplicate':
                            result = Response(b'{"ok":true,"ok":true}')
                        return result
                with patch.object(builtins, 'open', side_effect=open_env), \
                     patch.object(urllib.request, 'build_opener', return_value=Client()), \
                     patch.dict(os.environ, {'DEPLOYER_TOKEN': 'private-fixture-token'}), \
                     patch.object(bootstrap.sys, 'argv', ['-', 'barrier']), contextlib.redirect_stdout(output):
                    if fault is None:
                        exec(bootstrap.HTTP_PROGRAM, {})
                    else:
                        with self.assertRaises(SystemExit):
                            exec(bootstrap.HTTP_PROGRAM, {})
                text = output.getvalue()
                for private in (TAG, 'private-fixture-token', 'values', 'metadata'):
                    self.assertNotIn(private, text)
                if fault is None:
                    self.assertEqual(json.loads(text), {'verified': True,
                        'envHash': hashlib.sha256(raw).hexdigest()})
                    self.assertEqual([r.method for r in calls], ['GET', 'PATCH'])
                    self.assertEqual(json.loads(calls[1].data), {'values': {'IMAGE_TAG': TAG}})


if __name__ == '__main__':
    unittest.main()
