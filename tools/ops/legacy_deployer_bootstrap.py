#!/usr/bin/env python3
"""One-time, fail-closed legacy deployer replacement on the Linux Docker host.

Only bootstrap and remove-owned-fence are public. Run with host /proc visibility,
Docker access and NET_ADMIN/SYS_ADMIN. The shared host operation flock and private
evidence directory are trusted. Tokens, leases, env bytes and durable rows never
appear in diagnostics or evidence. Failed attempts require explicit inspection;
this tool neither repairs journals nor reopens an incompletely verified process.

Store continuity is checked at observed checkpoints. The retained fence covers
the old network namespace only; the replacement's durable marker closes its
coordinator, but does not prevent pre-admission durable operation reservations.
"""
import argparse
from contextlib import contextmanager
from dataclasses import asdict, replace
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

import deployer_socket_drain as drain

MARKER = b'{"capability":"maintenance-v1"}\n'
NAME = 'opensamguk-deployer'
STORE = '.deployer-operations.json'
JOURNAL = '.deployer-lifecycle-journal'
MARKER_NAME = '.deployer-maintenance'
LOCK = Path('/tmp/opensamguk-production.lock')
PATH_SETTINGS = ('COMPOSE_DIR', 'SERVERS_DIR', 'DEPLOYER_MAINTENANCE_FILE',
                 'DEPLOYER_LIFECYCLE_JOURNAL_FILE', 'DEPLOYER_OPERATION_STORE_FILE')
# Projection is evaluated by Docker. Never copy Config.Env (and its token) onto
# the host just to inspect the few non-secret filesystem path settings.
CONTAINER_FORMAT = ('[{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},'
    '"State":{"Pid":{{json .State.Pid}},"Running":{{json .State.Running}},'
    '"Status":{{json .State.Status}},"Restarting":{{json .State.Restarting}}},'
    '"HostConfig":{"RestartPolicy":{{json .HostConfig.RestartPolicy}}},'
    '"Mounts":{{json .Mounts}},"Config":{"Labels":{'
    '"com.docker.compose.project":{{json (index .Config.Labels "com.docker.compose.project")}},'
    '"com.docker.compose.service":{{json (index .Config.Labels "com.docker.compose.service")}}},'
    '"Env":[{{range .Config.Env}}{{if or ' + ' '.join(
        '(eq (index (split . "=") 0) "' + key + '")' for key in PATH_SETTINGS) +
    '}}{{json .}},{{end}}{{end}}""]}}]')

# The program is constant; mode is the only argv input. Authentication and the
# same-tag request body are built inside the old container, never on the host.
HTTP_PROGRAM = r'''
import hashlib, json, os, re, stat, sys, urllib.request, urllib.error
def unique(pairs):
    result = {}
    for k, v in pairs:
        if k in result: raise ValueError('duplicate')
        result[k] = v
    return result
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise ValueError('redirect')
def request(method, path, body=None):
    token = os.environ['DEPLOYER_TOKEN']
    if not token: raise ValueError('missing token')
    req = urllib.request.Request('http://127.0.0.1:9000' + path,
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                 'Connection': 'close'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try: response = opener.open(req, timeout=20)
    except urllib.error.HTTPError as error: response = error
    with response:
        raw = response.read(1048577)
        if len(raw) > 1048576: raise ValueError('large response')
        return {'status': response.code, 'body': json.loads(raw, object_pairs_hook=unique)}
try:
    mode = sys.argv[1]
    if mode == 'identity':
        raw = open('/proc/1/stat').read()
        result = {'startTime': int(raw[raw.rindex(')') + 2:].split()[19]),
                  'netns': os.readlink('/proc/1/ns/net')}
    elif mode == 'marker':
        root = os.open('/workspace', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        directory = os.open('servers', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root)
        marker = os.open('.deployer-maintenance', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        try:
            if not stat.S_ISREG(os.fstat(marker).st_mode): raise ValueError('marker type')
            if os.read(marker, 128) != b'{"capability":"maintenance-v1"}\n': raise ValueError('marker content')
            result = {'markerVerified': True,
                'identities': [[os.fstat(fd).st_dev, os.fstat(fd).st_ino] for fd in (root, directory, marker)]}
        finally:
            os.close(marker)
            os.close(directory)
            os.close(root)
    elif mode == 'barrier':
        raw = open('/workspace/.env', 'rb').read()
        values = re.findall(rb'^IMAGE_TAG=([^\r\n]*)\r?$', raw, re.M)
        if len(values) != 1 or not re.fullmatch(rb'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', values[0]):
            raise ValueError('ambiguous tag')
        tag = values[0].decode('ascii')
        before = request('GET', '/env/shared')
        field = before['body']['fields']['IMAGE_TAG']
        if (before['status'] != 200 or before['body']['ok'] is not True or
            before['body']['scope'] != 'shared' or field['key'] != 'IMAGE_TAG' or
            field['value'] != tag or field['configured'] is not True or
            field['writeOnly'] is not False or field['masked'] is not False):
            raise ValueError('GET mismatch')
        result = request('PATCH', '/env/shared', {'values': {'IMAGE_TAG': tag}})
        body = result['body']
        if (result['status'] != 200 or body['ok'] is not True or body['scope'] != 'shared' or
            body['fields']['IMAGE_TAG'] != field or body.get('jobId', '') != '' or
            open('/workspace/.env', 'rb').read() != raw):
            raise ValueError('barrier mismatch')
        result = {'verified': True, 'envHash': hashlib.sha256(raw).hexdigest()}
    else:
        routes = {'enter': ('POST', '/maintenance/enter'),
            'closed': ('GET', '/maintenance'), 'capability': ('POST', '/maintenance/enter-if-idle'),
            'ready': ('GET', '/readyz'), 'leave': ('POST', '/maintenance/leave')}
        method, path = routes[mode]
        result = request(method, path)
    print(json.dumps(result))
except Exception:
    print('{"transportError":true}')
    sys.exit(1)
'''

HOLDER_PROGRAM = r'''
import json, os, signal, sys
print(json.dumps({'pid': os.getpid()}), flush=True)
while True:
    line = sys.stdin.buffer.readline(32)
    if line == b'RELEASE\n':
        print('RELEASED', flush=True)
        sys.exit(0)
    if not line:
        # EOF is not authorization to discard the last namespace reference.
        while True: signal.pause()
'''


class Refusal(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON field')
        result[key] = value
    return result


def decode(raw):
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(Refusal('invalid JSON number')))


def checked(argv, *, stdin=None):
    result = subprocess.run(argv, input=stdin, text=True, capture_output=True,
                            close_fds=True, timeout=60)
    require(result.returncode == 0, 'command failed')
    return result.stdout


def open_directory(path):
    path = Path(path)
    require(path.is_absolute() and '..' not in path.parts, 'absolute canonical directory required')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:]:
            nxt = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = nxt
        return fd
    except BaseException:
        os.close(fd)
        raise


@contextmanager
def regular_file(directory, name):
    require('/' not in name and name not in ('.', '..'), 'invalid file name')
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    try:
        require(stat.S_ISREG(os.fstat(fd).st_mode), 'regular file required')
        yield fd
    finally:
        os.close(fd)


def read_file(directory, name, limit=1048576):
    with regular_file(directory, name) as fd:
        raw = os.read(fd, limit + 1)
        require(len(raw) <= limit, 'file too large')
        return raw


def sync_marker(stack):
    fd = open_directory(Path(stack) / 'servers')
    try:
        with regular_file(fd, MARKER_NAME) as marker:
            require(os.read(marker, 128) == MARKER, 'maintenance marker mismatch')
            identity = (os.fstat(marker).st_dev, os.fstat(marker).st_ino)
            os.fsync(marker)
            os.fsync(fd)
            now = os.stat(MARKER_NAME, dir_fd=fd, follow_symlinks=False)
            require(identity == (now.st_dev, now.st_ino), 'maintenance marker changed')
            return identity
    finally:
        os.close(fd)


def timestamp(value):
    require(isinstance(value, str) and re.fullmatch(
        r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)', value),
        'invalid operation timestamp')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.year > 1, 'zero operation timestamp')
    return result


def terminal_store(stack):
    directory = open_directory(Path(stack) / 'servers')
    try:
        try:
            os.stat(JOURNAL, dir_fd=directory, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise Refusal('lifecycle journal present')
        document = decode(read_file(directory, STORE))
    finally:
        os.close(directory)
    require(isinstance(document, dict) and set(document) == {'version', 'operations'} and
            type(document['version']) is int and document['version'] == 1 and
            isinstance(document['operations'], list) and len(document['operations']) <= 512,
            'invalid durable operation document')
    rows = {}
    fields = {'operationId', 'kind', 'subjectId', 'requestFingerprint', 'status', 'httpStatus',
              'publicMessage', 'createdAt', 'updatedAt'}
    messages = {'', '요청 검증에 실패했습니다.', 'Docker를 사용할 수 없어 작업을 시작하지 못했습니다.',
        '다른 서버 수명주기 작업이 진행 중입니다.', '서버 생성 준비에 실패했습니다.',
        '서버 종료 준비에 실패했습니다.', '서버 리셋 준비에 실패했습니다.',
        '서버 복구 확인이 필요합니다. 운영 복구가 끝날 때까지 기다려 주세요.',
        '서버 수명주기 검증에 실패했습니다.', '서버 수명주기 작업이 취소되었습니다.',
        'deployer 재시작 전에 작업이 중단되었습니다. 다시 요청해 주세요.',
        '서버 생성이 완료되었습니다.', '서버 종료가 완료되었습니다.', '서버 리셋이 완료되었습니다.'}
    for row in document['operations']:
        require(isinstance(row, dict) and set(row) == fields, 'invalid operation fields')
        for key in fields - {'httpStatus'}:
            require(isinstance(row[key], str), 'invalid operation field type')
        identity = row['operationId']
        require(re.fullmatch('[a-f0-9]{32}', identity) and identity not in rows and
                re.fullmatch('[a-f0-9]{64}', row['requestFingerprint']) and
                re.fullmatch('[A-Za-z0-9]{1,48}', row['subjectId']) and
                row['kind'] in ('create', 'close', 'reset') and
                row['status'] in ('succeeded', 'failed', 'cancelled') and
                type(row['httpStatus']) is int and
                (row['httpStatus'] == 0 or 100 <= row['httpStatus'] <= 599) and
                row['publicMessage'] in messages and
                timestamp(row['updatedAt']) >= timestamp(row['createdAt']), 'invalid or nonterminal operation')
        rows[identity] = row
    return rows


def continuity(before, after, now):
    require(not (after.keys() - before.keys()), 'new durable operation observed')
    for key, row in before.items():
        if key in after:
            require(row == after[key], 'durable operation changed')
        else:
            require((now - timestamp(row['updatedAt'])).total_seconds() > 86400,
                    'unexpired durable operation removed')


class NamespaceHolder:
    """Network namespace reference; no listener and no inherited operation lock."""
    def __init__(self, fence, *, runner=checked, proc_root=Path('/proc'), timeout=10):
        self.runner, self.proc_root, self.timeout = runner, proc_root, timeout
        self.original = fence
        self.process = subprocess.Popen(['nsenter', '--target', str(fence.pid), '--net',
            '--no-fork', '--', 'python3', '-I', '-c', HOLDER_PROGRAM],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            close_fds=True, start_new_session=True)
        self.pid = self.process.pid
        self.fence = None

    def verify(self):
        require(self.process.poll() is None, 'namespace holder exited')
        if self.fence is None:
            require(select.select([self.process.stdout], [], [], self.timeout)[0], 'holder readiness timeout')
            value = decode(self.process.stdout.readline(128))
            require(value == {'pid': self.pid}, 'holder readiness mismatch')
            start, netns = drain._identity(self.runner, self.pid)
            require(netns == self.original.netns, 'holder namespace mismatch')
            self.fence = replace(self.original, pid=self.pid, start_time=start)
        verify_holder(self.runner, self.proc_root, self.fence)
        require(self.process.poll() is None, 'namespace holder exited')
        return self.fence

    def release(self):
        self.verify()
        self.process.stdin.write(b'RELEASE\n')
        self.process.stdin.flush()
        require(select.select([self.process.stdout], [], [], self.timeout)[0], 'holder release timeout')
        require(self.process.stdout.readline(128) == b'RELEASED\n', 'holder release mismatch')
        require(self.process.wait(timeout=self.timeout) == 0, 'holder release failed')
        self.process.stdin.close()
        self.process.stdout.close()


def verify_holder(runner, proc_root, fence):
    drain._check_identity(runner, fence)
    for _, target in drain._fds(Path(proc_root) / str(fence.pid) / 'fd'):
        require(not target.startswith('socket:'), 'holder owns unexpected socket')
    drain._check_identity(runner, fence)


class Operator:
    def __init__(self, *, runner=checked, proc_root=Path('/proc'), lock_path=LOCK,
                 timeout=30, holder_factory=None):
        self.runner, self.proc_root, self.lock_path = runner, Path(proc_root), Path(lock_path)
        self.timeout = timeout
        self.holder_factory = holder_factory or (lambda fence: NamespaceHolder(fence,
            runner=runner, proc_root=self.proc_root))
        self.record = None
        self.attempt = None

    @contextmanager
    def locked(self, stack):
        """Pin the operator-authorized stack (or authenticated prior attempt stack).

        Its owner and root may own the cooperating workflow lock. The exact stack
        bind is still checked inside bootstrap before any fence. An arbitrary UID
        is never an input. Trusted owners must not replace the lock during work.
        """
        stack = Path(stack)
        stack_fd = open_directory(stack)
        fd = None
        try:
            info = os.fstat(stack_fd)
            identity = (info.st_dev, info.st_ino, info.st_uid)
            self._stack_pin = (stack, stack_fd, identity)
            # O_NONBLOCK also prevents an unexpected special file from blocking
            # before the regular-file check; it has no effect on regular files.
            flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
            try:
                fd = os.open(self.lock_path, flags)
            except FileNotFoundError:
                fd = os.open(self.lock_path, flags | os.O_CREAT | os.O_EXCL, 0o600)
            def check_lock():
                descriptor = os.fstat(fd)
                pathname = os.stat(self.lock_path, follow_symlinks=False)
                require(stat.S_ISREG(descriptor.st_mode) and descriptor.st_nlink == 1 and
                        descriptor.st_uid in (0, identity[2]) and
                        stat.S_ISREG(pathname.st_mode) and pathname.st_nlink == 1 and
                        (pathname.st_dev, pathname.st_ino, pathname.st_uid) ==
                        (descriptor.st_dev, descriptor.st_ino, descriptor.st_uid),
                        'invalid host operation lock')
            check_lock()
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            check_lock()
            self.check_stack(stack)
            yield
        finally:
            self._stack_pin = None
            try:
                os.close(stack_fd)
            finally:
                if fd is not None:
                    os.close(fd)

    def check_stack(self, stack):
        pinned_path, pinned_fd, identity = self._stack_pin
        require(Path(stack) == pinned_path, 'authorized stack path changed')
        fd = open_directory(pinned_path)
        try:
            for descriptor in (pinned_fd, fd):
                info = os.fstat(descriptor)
                require((info.st_dev, info.st_ino, info.st_uid) == identity, 'authorized stack identity changed')
        finally:
            os.close(fd)

    def save(self, **fields):
        self.record.update(fields)
        directory = open_directory(self.attempt.parent)
        name = '.' + uuid.uuid4().hex + '.tmp'
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                         0o600, dir_fd=directory)
            try:
                raw = json.dumps(self.record, sort_keys=True).encode() + b'\n'
                with os.fdopen(fd, 'wb', closefd=False) as output:
                    output.write(raw)
                    output.flush()
                os.fsync(fd)
            finally:
                os.close(fd)
            os.rename(name, self.attempt.name, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
        finally:
            try:
                os.unlink(name, dir_fd=directory)
            except FileNotFoundError:
                pass
            os.close(directory)

    def inspect(self):
        rows = decode(self.runner(['docker', 'container', 'inspect', '--format', CONTAINER_FORMAT, NAME]))
        require(isinstance(rows, list) and len(rows) == 1, 'ambiguous container identity')
        obj = rows[0]
        require(obj['Name'] == '/' + NAME and re.fullmatch('[a-f0-9]{64}', obj['Id']) and
                re.fullmatch('sha256:[a-f0-9]{64}', obj['Image']), 'invalid deployer identity')
        return obj

    def bind(self, obj, stack):
        self.check_stack(stack)
        labels = obj['Config']['Labels']
        project = labels.get('com.docker.compose.project', '')
        require(re.fullmatch('[a-z0-9][a-z0-9_-]*', project) and
                labels.get('com.docker.compose.service') == 'deployer', 'Compose identity unavailable')
        if hasattr(self, 'project'):
            require(project == self.project, 'Compose project changed')
        self.project = project
        mounts = obj['Mounts']
        relevant = [m for m in mounts if m['Destination'] == '/workspace' or
                    m['Destination'].startswith('/workspace/')]
        require(len(relevant) == 1 and relevant[0]['Destination'] == '/workspace' and
                relevant[0]['Type'] == 'bind' and relevant[0]['RW'] is True and
                relevant[0]['Source'] == str(stack), 'stack bind mismatch')
        env = {}
        for value in obj['Config']['Env']:
            key, _, value = value.partition('=')
            require(key not in env, 'duplicate container setting')
            env[key] = value
        expected = {'COMPOSE_DIR': '/workspace', 'SERVERS_DIR': '/workspace/servers',
                    'DEPLOYER_MAINTENANCE_FILE': '/workspace/servers/' + MARKER_NAME,
                    'DEPLOYER_LIFECYCLE_JOURNAL_FILE': '/workspace/servers/' + JOURNAL,
                    'DEPLOYER_OPERATION_STORE_FILE': '/workspace/servers/' + STORE}
        for key, default in expected.items():
            require(env.get(key, default) == default, 'unsupported deployer path setting')

    def drain(self, fence):
        deadline = time.monotonic() + self.timeout
        while True:
            drain._check_identity(self.runner, fence)
            report = drain.inspect_port_drain(self.proc_root, fence.pid, fence.port)
            if report.drained:
                return
            require(time.monotonic() < deadline, 'socket drain did not prove idle')
            time.sleep(0.1)

    def http(self, mode, expected_id):
        require(self.inspect()['Id'] == expected_id, 'HTTP target changed')
        return decode(self.runner(['docker', 'exec', '-i', NAME, 'python3', '-', mode], stdin=HTTP_PROGRAM))

    def maintenance(self, mode, expected_id, state, lease=False):
        response = self.http(mode, expected_id)
        body = response.get('body', {})
        require(response.get('status') == 200 and body.get('capability') == 'maintenance-v1' and
                body.get('state') == state, 'maintenance state mismatch')
        if lease:
            require(isinstance(body.get('lease'), str) and 0 < len(body['lease']) <= 256,
                    'missing private maintenance lease')
        else:
            require('lease' not in body, 'unexpected maintenance lease')
        return body.get('lease')

    def marker(self, stack, expected_id, expected_marker=None):
        obj = self.inspect()
        require(obj['Id'] == expected_id, 'marker container changed')
        self.bind(obj, stack)
        marker = sync_marker(stack)
        if expected_marker is not None:
            require(marker == expected_marker, 'maintenance marker changed')
        identities = []
        for path in (stack, stack / 'servers'):
            fd = open_directory(path)
            try:
                info = os.fstat(fd)
                identities.append([info.st_dev, info.st_ino])
            finally:
                os.close(fd)
        identities.append(list(marker))
        response = self.http('marker', expected_id)
        require(response == {'markerVerified': True, 'identities': identities},
                'mounted marker identity mismatch')
        require(sync_marker(stack) == marker, 'marker changed during bind observation')
        return marker

    def compose(self, stack, image):
        fd, name = tempfile.mkstemp(prefix='.deployer-bootstrap-', suffix='.json', dir=stack)
        try:
            with os.fdopen(fd, 'w') as output:
                json.dump({'services': {'deployer': {'image': image}}}, output)
                output.flush()
                os.fsync(output.fileno())
            self.runner(['docker', 'compose', '--project-name', self.project,
                '--project-directory', str(stack), '--file',
                str(stack / 'docker-compose.shared.yml'), '--file', name,
                'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'deployer'])
        finally:
            os.unlink(name)

    def execute(self, stack, image, old_id, evidence):
        try:
            with self.locked(stack):
                return self.bootstrap(Path(stack), image, old_id, Path(evidence))
        except Exception:
            # Exception messages may contain captured response bodies or filenames.
            print('Bootstrap did not complete; inspect the private attempt record and current maintenance state.', file=sys.stderr)
            return 1

    def bootstrap(self, stack, image, old_id, evidence):
        require(re.fullmatch('sha256:[a-f0-9]{64}', image) and re.fullmatch('[a-f0-9]{64}', old_id),
                'exact image and container IDs required')
        for path in (stack, evidence):
            fd = open_directory(path)
            try:
                if path == evidence:
                    info = os.fstat(fd)
                    require(info.st_uid == os.geteuid() and info.st_mode & 0o777 == 0o700,
                            'private evidence directory required')
            finally:
                os.close(fd)
        self.attempt = evidence / ('bootstrap-' + uuid.uuid4().hex + '.json')
        self.record = {'version': 1, 'stack': str(stack), 'old_container_id': old_id,
                       'replacement_image_id': image, 'stage': 'preflight'}
        self.save()
        fence, holder = None, None
        replacing = False
        try:
            images = decode(self.runner(['docker', 'image', 'inspect', '--format',
                                         '[{"Id":{{json .Id}}}]', image]))
            require(len(images) == 1 and images[0]['Id'] == image, 'replacement image unavailable')
            old = self.inspect()
            require(old['Id'] == old_id and old['State']['Running'] is True and
                    type(old['State']['Pid']) is int and old['State']['Pid'] > 0, 'old deployer mismatch')
            self.bind(old, stack)
            identity = drain._identity(self.runner, old['State']['Pid'])
            require(self.http('identity', old_id) == {'startTime': identity[0], 'netns': identity[1]},
                    'container and host process identity mismatch')
            directory = open_directory(stack)
            try:
                env_before = read_file(directory, '.env')
            finally:
                os.close(directory)
            tags = re.findall(rb'^IMAGE_TAG=([^\r\n]*)\r?$', env_before, re.M)
            require(len(tags) == 1 and re.fullmatch(rb'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', tags[0]),
                    'shared image tag ambiguous')
            fence = drain.install_syn_fence(self.runner, old['State']['Pid'], 9000, uuid.uuid4().hex)
            self.save(stage='fenced', old_image_id=old['Image'], original_fence=asdict(fence), mode='old')
            require((fence.start_time, fence.netns) == identity, 'old identity changed at fence')
            self.drain(fence)
            result = self.http('barrier', old_id)
            require(result.get('verified') is True and
                    result.get('envHash') == hashlib.sha256(env_before).hexdigest(), 'same-tag barrier failed')
            directory = open_directory(stack)
            try:
                require(read_file(directory, '.env') == env_before, 'shared env bytes changed')
            finally:
                os.close(directory)
            self.drain(fence)
            baseline = terminal_store(stack)
            private_lease = self.maintenance('enter', old_id, 'drained', lease=True)
            self.drain(fence)
            marker = self.marker(stack, old_id)
            self.save(stage='closed', marker_identity=list(marker))
            holder = self.holder_factory(fence)
            self.save(holder_pid=holder.pid, holder_outstanding=True)
            retained = holder.verify()
            drain._check_identity(self.runner, fence)
            require(retained == replace(fence, pid=retained.pid, start_time=retained.start_time),
                    'holder fence changed')
            self.save(stage='holder-ready', holder_fence=asdict(retained), mode='holder')
            require(self.inspect()['Id'] == old_id, 'old deployer changed before replacement')
            drain._check_identity(self.runner, fence)
            holder.verify()
            self.marker(stack, old_id, marker)
            continuity(baseline, terminal_store(stack), datetime.now(timezone.utc))
            self.save(stage='replacing')
            replacing = True
            try:
                self.compose(stack, image)
                current = self.inspect()
                require(current['Id'] != old_id and current['Image'] == image and
                        current['State']['Running'] is True and current['State']['Pid'] > 0,
                        'replacement identity mismatch')
            except Exception:
                # A running or unknown replacement may have pre-admission work.
                # No cancellation, termination, store rollback or retry is safe.
                current = self.inspect()
                require(current['Id'] != old_id and current['Image'] == image and
                        current['State']['Running'] is False and current['State']['Pid'] == 0 and
                        current['State']['Restarting'] is False and
                        current['State']['Status'] in ('created', 'exited') and
                        (current['State']['Status'] == 'created' or
                         current['HostConfig']['RestartPolicy']['Name'] in ('', 'no')),
                        'replacement process not proved absent')
                self.bind(current, stack)
                holder.verify()
                require(sync_marker(stack) == marker, 'fallback marker changed')
                continuity(baseline, terminal_store(stack), datetime.now(timezone.utc))
                self.save(stage='fallback', replacement_container_id=current['Id'])
                require(self.inspect() == current, 'fallback identity changed')
                self.compose(stack, old['Image'])
                raise Refusal('old image recreated closed; manual verification required')
            self.bind(current, stack)
            self.save(stage='verifying', replacement_container_id=current['Id'])
            self.marker(stack, current['Id'], marker)
            self.maintenance('closed', current['Id'], 'drained')
            capability = self.http('capability', current['Id'])
            require(capability == {'status': 409, 'body': {'error': 'maintenance idle admission unavailable'}},
                    'closed capability check failed')
            readiness = self.http('ready', current['Id'])
            require(readiness == {'status': 200, 'body': {'status': 'ready'}}, 'replacement not ready')
            continuity(baseline, terminal_store(stack), datetime.now(timezone.utc))
            holder.verify()
            drain.remove_owned_fence(self.runner, retained)
            self.save(stage='rules-removed')
            holder.release()
            self.save(stage='holder-released', holder_outstanding=False)
            # Final observed store checkpoint, immediately before leave.
            self.marker(stack, current['Id'], marker)
            continuity(baseline, terminal_store(stack), datetime.now(timezone.utc))
            self.save(stage='leaving')
            self.maintenance('leave', current['Id'], 'open')
            private_lease = None
            self.save(stage='complete')
            print('Bootstrap completed; replacement verified and maintenance open.')
            return 0
        except Exception as error:
            if isinstance(error, drain.FenceError) and fence is None and error.fence is not None:
                fence = error.fence
                self.save(original_fence=asdict(fence), mode='old')
            if holder is not None and not replacing:
                try:
                    drain._check_identity(self.runner, fence)
                    holder.release()
                    self.save(mode='old', holder_outstanding=False)
                except Exception:
                    pass
            self.save(failed=True)
            raise


def fence_from_record(value):
    require(isinstance(value, dict) and set(value) == {'pid', 'port', 'token', 'start_time', 'netns', 'rules'},
            'invalid fence record')
    value = dict(value)
    value['rules'] = tuple((binary, tuple(rule)) for binary, rule in value['rules'])
    fence = drain.OwnedFence(**value)
    drain._validate(fence.pid, fence.port)
    require(fence.port == 9000 and re.fullmatch('[a-f0-9]{32}', fence.token) and
            type(fence.start_time) is int and fence.start_time > 0 and
            re.fullmatch(r'net:\[\d+\]', fence.netns), 'invalid recorded fence identity')
    spec = ('!', '-i', 'lo', '-p', 'tcp', '-m', 'tcp', '--dport', '9000',
            '--tcp-flags', 'FIN,SYN,RST,ACK', 'SYN', '-m', 'conntrack', '--ctstate', 'NEW',
            '-m', 'comment', '--comment', 'opensamguk-drain:' + fence.token,
            '-j', 'REJECT', '--reject-with', 'tcp-reset')
    require(fence.rules in ((('iptables', spec),), (('iptables', spec), ('ip6tables', spec))),
            'recorded firewall specification changed')
    return fence


def read_attempt(path):
    """Authenticate without effects; return bytes and identities for locked reread."""
    require(Path(path).is_absolute(), 'absolute attempt record required')
    path = Path(path)
    directory = open_directory(path.parent)
    def identity(info):
        return (info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode, info.st_nlink)
    try:
        info = os.fstat(directory)
        require(info.st_uid == os.geteuid() and info.st_mode & 0o777 == 0o700,
                'private attempt directory required')
        directory_identity = identity(info)
        with regular_file(directory, path.name) as fd:
            info = os.fstat(fd)
            require(info.st_uid == os.geteuid() and info.st_mode & 0o777 == 0o600 and info.st_nlink == 1,
                    'private attempt file required')
            file_identity = identity(info)
            raw = os.read(fd, 65537)
            require(len(raw) <= 65536 and identity(os.fstat(fd)) == file_identity and
                    identity(os.stat(path.name, dir_fd=directory, follow_symlinks=False)) == file_identity,
                    'private attempt file changed during read')
        observed_parent = open_directory(path.parent)
        try:
            require(identity(os.fstat(observed_parent)) == directory_identity,
                    'private attempt directory changed during read')
        finally:
            os.close(observed_parent)
        record = decode(raw)
        require(isinstance(record, dict) and type(record.get('version')) is int and
                record['version'] == 1, 'unsupported attempt record')
        require(isinstance(record.get('stack'), str) and Path(record['stack']).is_absolute() and
                '..' not in Path(record['stack']).parts, 'authenticated absolute stack path required')
        return record, raw, (directory_identity, file_identity)
    finally:
        os.close(directory)


def remove_recorded(operator, path):
    initial, raw, identities = read_attempt(path)
    stack = Path(initial['stack'])
    with operator.locked(stack):
        record, locked_raw, locked_identities = read_attempt(path)
        require(locked_raw == raw and locked_identities == identities,
                'authenticated attempt changed before lock acquisition')
        operator.check_stack(stack)
        original = fence_from_record(record['original_fence'])
        if record['mode'] == 'old':
            require(record.get('holder_outstanding') is not True, 'unverified holder remains outstanding')
            obj = operator.inspect()
            require(obj['Id'] == record['old_container_id'] and obj['State']['Running'] is True and
                    obj['State']['Pid'] == original.pid, 'old recovery identity mismatch')
            drain.remove_owned_fence(operator.runner, original)
        elif record['mode'] == 'holder':
            fence = fence_from_record(record['holder_fence'])
            require(fence == replace(original, pid=fence.pid, start_time=fence.start_time) and
                    record['holder_pid'] == fence.pid and record['holder_outstanding'] is True,
                    'holder recovery identity mismatch')
            # Pin process identity before removal; never signal a recycled PID.
            pidfd = os.pidfd_open(fence.pid)
            try:
                verify_holder(operator.runner, operator.proc_root, fence)
                drain.remove_owned_fence(operator.runner, fence)
                verify_holder(operator.runner, operator.proc_root, fence)
                signal.pidfd_send_signal(pidfd, signal.SIGTERM)
                require(select.select([pidfd], [], [], 10)[0], 'holder recovery release timeout')
            finally:
                os.close(pidfd)
        else:
            raise Refusal('unknown attempt recovery mode')
        operator.attempt, operator.record = Path(path), record
        operator.save(stage='explicit-fence-cleanup', holder_outstanding=False)
        print('Exact owned fence removed; maintenance state was not changed.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    start = commands.add_parser('bootstrap')
    start.add_argument('--stack-dir', required=True, type=Path)
    start.add_argument('--replacement-image-id', required=True)
    start.add_argument('--expected-old-container-id', required=True)
    start.add_argument('--evidence-root', required=True, type=Path)
    start.add_argument('--confirm', required=True, choices=['BOOTSTRAP DEPLOYER'])
    cleanup = commands.add_parser('remove-owned-fence')
    cleanup.add_argument('--attempt-record', required=True, type=Path)
    cleanup.add_argument('--confirm', required=True, choices=['REMOVE OWNED FENCE'])
    args = parser.parse_args(argv)
    if args.command == 'bootstrap':
        return Operator().execute(args.stack_dir, args.replacement_image_id,
                                  args.expected_old_container_id, args.evidence_root)
    try:
        remove_recorded(Operator(), args.attempt_record)
        return 0
    except Exception:
        print('Owned fence cleanup refused; private attempt requires inspection.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
