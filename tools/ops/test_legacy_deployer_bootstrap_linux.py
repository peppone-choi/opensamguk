#!/usr/bin/env python3
"""Opt-in owned Linux proof of the bootstrap namespace holder, with EOF recovery.

RUN_LEGACY_BOOTSTRAP_LINUX_TESTS=1 DOCKER_CONTEXT=desktop-linux python3
tools/ops/test_legacy_deployer_bootstrap_linux.py

The container init remains in its original network namespace. The original test
process creates a separate unshare --net namespace; only it and the task holder
reference that namespace. No host ports, mounts, host PID or unrelated cleanup.
"""
import argparse
import fcntl
from dataclasses import asdict
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import uuid

import legacy_deployer_bootstrap as bootstrap
import deployer_socket_drain as drain

LABEL = 'org.opensamguk.bootstrap-holder.fixture'


def lock_integration():
    """Actual root/non-root lock ownership on fixture-only temporary paths."""
    assert os.geteuid() == 0
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        stack, lock = root / 'stack', root / 'workflow-lock'
        stack.mkdir()
        os.chown(stack, 1001, 0)
        lock.write_bytes(b'owned-workflow-lock\n')
        lock.chmod(0o664)
        os.chown(lock, 1001, 0)
        def snapshot():
            info = lock.stat()
            return {'device': info.st_dev, 'inode': info.st_ino, 'uid': info.st_uid,
                    'gid': info.st_gid, 'mode': info.st_mode & 0o777,
                    'links': info.st_nlink, 'contents': lock.read_text()}
        before = snapshot()
        operator = bootstrap.Operator(lock_path=lock)
        with operator.locked(stack):
            probe = os.open(lock, os.O_RDWR | os.O_NOFOLLOW)
            try:
                try:
                    fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    pass
                else:
                    raise AssertionError('independent descriptor bypassed workflow lock')
            finally:
                os.close(probe)
        assert snapshot() == before
        probe = os.open(lock, os.O_RDWR | os.O_NOFOLLOW)
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                with operator.locked(stack):
                    raise AssertionError('operator bypassed independent workflow lock')
            except BlockingIOError:
                pass
        finally:
            os.close(probe)
        assert snapshot() == before
        return {'before': before, 'after': snapshot(), 'root_euid': os.geteuid(),
                'authorized_stack_uid': stack.stat().st_uid, 'bidirectional_contention': True}


def inside():
    lock_proof = lock_integration()
    observations = []
    commands = []
    def checked(argv, *, stdin=None):
        result = subprocess.run(argv, input=stdin, text=True, capture_output=True)
        commands.append({'argv': argv, 'exit': result.returncode,
                         'stdout': result.stdout, 'stderr': result.stderr})
        result.check_returncode()
        return result.stdout
    for eof in (False, True):
        original = subprocess.Popen(['unshare', '--net', '--', 'python3', '-I', '-c',
            'import os,signal; print(os.getpid(),flush=True); signal.pause()'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, close_fds=True)
        assert select.select([original.stdout], [], [], 10)[0], 'original readiness timeout'
        assert int(original.stdout.readline()) == original.pid
        original_identity = drain._identity(checked, original.pid)
        assert original_identity[1] != drain._identity(checked, 1)[1], 'init must not hold target namespace'
        token = uuid.uuid4().hex
        fence = drain.install_syn_fence(checked, original.pid, 9000, token)
        with tempfile.TemporaryDirectory() as locked_directory:
            lock_path = Path(locked_directory) / 'operation-lock'
            lock_fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
            try:
                # Deliberately make it inheritable: close_fds must still exclude it.
                os.set_inheritable(lock_fd, True)
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                holder = bootstrap.NamespaceHolder(fence, runner=checked)
                retained = holder.verify()
                holder_fds = drain._fds(Path('/proc') / str(holder.pid) / 'fd')
                assert all(target != str(lock_path) for _, target in holder_fds)
            finally:
                os.close(lock_fd)
            probe = os.open(lock_path, os.O_RDWR)
            try:
                fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(probe)
        drain._check_identity(checked, fence)
        assert retained.netns == fence.netns and retained.pid != original.pid
        entry = {'eof': eof, 'original': asdict(fence), 'holder': asdict(retained),
                 'init_netns': drain._identity(checked, 1)[1],
                 'holder_fds': holder_fds, 'operation_lock_not_inherited': True}
        entry['before'] = {binary: drain._rules(checked, holder.pid, binary)
                           for binary, _ in retained.rules}
        original_fd = os.pidfd_open(original.pid)
        try:
            drain._check_identity(checked, fence)
            signal.pidfd_send_signal(original_fd, signal.SIGTERM)
            assert original.wait(timeout=10) == -signal.SIGTERM
        finally:
            os.close(original_fd)
            original.stdout.close()
            original.stderr.close()
        assert not Path('/proc', str(original.pid)).exists(), 'original was not reaped'
        holder.verify()
        entry['after_original_exit'] = {binary: drain._rules(checked, holder.pid, binary)
                                       for binary, _ in retained.rules}
        assert entry['before'] == entry['after_original_exit']
        if eof:
            holder.process.stdin.close()
            assert not select.select([holder.process.stdout], [], [], 0.2)[0], 'EOF released holder'
            holder.verify()
            with tempfile.TemporaryDirectory() as temporary:
                stack, lock = Path(temporary) / 'stack', Path(temporary) / 'lock'
                stack.mkdir()
                os.chown(stack, 1001, 0)
                lock.write_bytes(b'owned-recovery-lock\n')
                lock.chmod(0o664)
                os.chown(lock, 1001, 0)
                lock_before = lock.stat()
                operator = bootstrap.Operator(runner=checked, lock_path=lock)
                operator.attempt = Path(temporary) / 'attempt.json'
                operator.record = {'version': 1, 'mode': 'holder', 'original_fence': asdict(fence),
                    'holder_fence': asdict(retained), 'holder_pid': retained.pid,
                    'holder_outstanding': True, 'stack': str(stack)}
                operator.save()
                bootstrap.remove_recorded(operator, operator.attempt)
                assert holder.process.wait(timeout=10) == -signal.SIGTERM
                holder.process.stdout.close()
                entry['recovery_stage'] = json.loads(operator.attempt.read_text())['stage']
                lock_after = lock.stat()
                assert (lock_before.st_dev, lock_before.st_ino, lock_before.st_uid,
                        lock_before.st_gid, lock_before.st_mode) == (lock_after.st_dev,
                        lock_after.st_ino, lock_after.st_uid, lock_after.st_gid, lock_after.st_mode)
                assert lock.read_bytes() == b'owned-recovery-lock\n'
                entry['workflow_lock_preserved'] = True
        else:
            drain.remove_owned_fence(checked, retained)
            entry['after_removal'] = {binary: drain._rules(checked, holder.pid, binary)
                                      for binary, _ in retained.rules}
            assert all(not rules for rules in entry['after_removal'].values())
            holder.release()
        assert not Path('/proc', str(holder.pid)).exists(), 'holder was not reaped'
        entry['owned_processes_after'] = []
        observations.append(entry)
    print(json.dumps({'observations': observations, 'namespace_commands': commands, 'lock_integration': lock_proof}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--evidence', type=Path,
                        default=Path('.superpowers/sdd/pep-recovery-operator/task-2-linux.json'))
    args = parser.parse_args()
    if args.inside:
        assert sys.platform == 'linux' and Path('/fixture').is_file()
        inside()
        return
    if os.environ.get('RUN_LEGACY_BOOTSTRAP_LINUX_TESTS') != '1':
        raise SystemExit('NOT RUN: explicitly set RUN_LEGACY_BOOTSTRAP_LINUX_TESTS=1.')
    assert os.environ.get('DOCKER_CONTEXT') == 'desktop-linux'
    token = uuid.uuid4().hex
    tag = 'bootstrap-holder-' + token + ':fixture'
    name = 'bootstrap-holder-' + token
    trace = {'commands': [], 'cleanup': {}}
    container_id = image_id = None
    def docker(*argv, check=True, timeout=120):
        command = ['docker', '--context', 'desktop-linux', *argv]
        result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
        trace['commands'].append({'argv': command, 'exit': result.returncode,
                                 'stdout': result.stdout, 'stderr': result.stderr})
        if check:
            result.check_returncode()
        return result.stdout.strip()
    def obj(kind, name):
        return json.loads(docker(kind, 'inspect', name))[0]
    directory = Path(__file__).resolve().parent
    try:
        assert docker('version', '--format', '{{.Server.Os}}/{{.Server.Arch}}') == 'linux/amd64'
        for kind, command in [('containers', ('ps', '-a')), ('images', ('image', 'ls'))]:
            inventory = docker(*command, '--filter', 'label=' + LABEL, '--format', '{{.ID}}')
            trace['cleanup']['before_' + kind] = inventory
            assert not inventory, 'pre-existing fixture inventory; no authority to remove'
        docker('build', '--platform', 'linux/amd64', '--label', LABEL + '=' + token,
               '-t', tag, str(directory / 'fixtures/deployer_socket_drain'), timeout=900)
        image_id = obj('image', tag)['Id']
        container_id = docker('container', 'create', '--name', name, '--network', 'none',
            '--label', LABEL + '=' + token, '--cap-drop', 'ALL', '--cap-add', 'NET_ADMIN',
            '--cap-add', 'SYS_ADMIN', '--cap-add', 'CHOWN', '--entrypoint', '/fixture', tag, 'idle')
        details = obj('container', name)
        config = details['HostConfig']
        assert not config['Privileged'] and not config['PortBindings'] and config['PidMode'] != 'host'
        assert not details['Mounts'] and config['NetworkMode'] == 'none'
        assert config['CapDrop'] == ['ALL']
        assert {c.removeprefix('CAP_') for c in config['CapAdd']} == {'NET_ADMIN', 'SYS_ADMIN', 'CHOWN'}
        projection = json.loads(docker('container', 'inspect', '--format',
                                       bootstrap.CONTAINER_FORMAT, container_id))[0]
        assert projection['Id'] == container_id and projection['Image'] == image_id
        assert projection['Config']['Env'] == [''], 'inspect copied non-allowlisted environment'
        trace['inspect_projection_verified'] = True
        docker('container', 'start', container_id)
        for filename in ('legacy_deployer_bootstrap.py', 'deployer_socket_drain.py',
                         'test_legacy_deployer_bootstrap_linux.py'):
            docker('cp', str(directory / filename), container_id + ':/' + filename)
        result = docker('exec', container_id, 'python3', '/test_legacy_deployer_bootstrap_linux.py', '--inside')
        trace['proof'] = json.loads(result.splitlines()[-1])
    finally:
        errors = []
        if container_id:
            try:
                details = obj('container', name)
                assert details['Id'] == container_id and details['Config']['Labels'][LABEL] == token
                docker('container', 'rm', '--force', container_id)
            except Exception as error:
                errors.append(type(error).__name__)
        if image_id:
            try:
                details = obj('image', tag)
                assert details['Id'] == image_id and details['Config']['Labels'][LABEL] == token
                docker('image', 'rm', tag)
            except Exception as error:
                errors.append(type(error).__name__)
        for kind, command in [('containers', ('ps', '-a')), ('images', ('image', 'ls'))]:
            try:
                inventory = docker(*command, '--filter', 'label=' + LABEL, '--format', '{{.ID}}')
                trace['cleanup']['after_' + kind] = inventory
                assert not inventory
            except Exception as error:
                errors.append(type(error).__name__)
        trace['cleanup']['errors'] = errors
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(trace, indent=2) + '\n')
        assert not errors, 'exact fixture cleanup failed'
    print('Synthetic bootstrap namespace-holder proof: PASS')


if __name__ == '__main__':
    main()
