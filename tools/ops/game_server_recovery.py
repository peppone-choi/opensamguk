#!/usr/bin/env python3
"""Cold, server-scoped capture and isolated storage verification (never live restore).

The restricted local filesystem and the coordinating production lock are trusted.
Checksums detect corruption; they are not signatures against a malicious operator.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tarfile
import threading
import time
import uuid


SERVICES = ('game-postgres', 'game-redis', 'game-engine', 'game-api', 'web-game')
VOLUMES = {'game-postgres': ('game-pgdata', '/var/lib/postgresql/data'),
           'game-redis': ('game-redisdata', '/data')}
PAYLOADS = {'server.env', 'compose.yml', 'images.tar', 'postgres.tar', 'redis.tar'}
RESERVED = set('all admin1 admin2 admin5 admin7 admin8 auction battle-center betting board '
               'chief-center city coming-soon diplomacy generals global-diplomacy history '
               'inherit join mailbox main map my my-boss my-cities my-generals my-nation nation '
               'nation-betting nation-finance npc-control rankings register select-pool simulator '
               'tournament tournament-admin troop vote world-log'.split())
LABEL = 'org.opensamguk.recovery'
REDIS_CMD = ['redis-server', '--appendonly', 'yes', '--maxmemory', '256mb',
             '--maxmemory-policy', 'allkeys-lru']
IMAGE_ID = re.compile(r'sha256:[0-9a-f]{64}\Z')


class RecoveryError(Exception):
    """Only safe, fixed summaries belong in this exception."""


def require(condition, message):
    if not condition:
        raise RecoveryError(message)


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def validate_target(server, confirm, verb):
    require(isinstance(server, str) and re.fullmatch('[a-z0-9]{1,32}', server)
            and server not in RESERVED, 'invalid or reserved server ID')
    require(confirm == f'{verb} {server}', 'confirmation mismatch')


def checked_path(value, directory=False, private=False):
    path = Path(value)
    require(path.is_absolute() and '..' not in path.parts, 'absolute canonical path required')
    # Resolve macOS /var -> /private/var only via caller paths; no symlink component accepted.
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlink path refused')
    require(path.is_dir() if directory else path.is_file(), 'required path missing')
    mode = path.stat().st_mode
    if not directory:
        require(stat.S_ISREG(mode) and path.stat().st_nlink == 1, 'regular unlinked file required')
    if private:
        require(mode & 0o077 == 0, 'private permissions required')
    return path


def write_private(path, payload, replace=False):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    target = path.with_name(path.name + '.pending.' + uuid.uuid4().hex) if replace else path
    fd = os.open(target, flags, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(payload)
        output.flush()
        os.fsync(output.fileno())
    if replace:
        os.replace(target, path)


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return {'sha256': result.hexdigest(), 'size': path.stat().st_size}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate manifest field')
        result[key] = value
    return result


def read_json(path):
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        raise RecoveryError('invalid JSON metadata') from None


def selected_env(path, server):
    wanted = {'SERVER_ID', 'OPENSAMGUK_WORLD_ID', 'GAME_POSTGRES_USER',
              'GAME_POSTGRES_DB', 'SCENARIO_DIR', 'COMPOSE_HOST_DIR'}
    result = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition('=')
        key = key.strip()
        if separator and key in wanted:
            require(key not in result, 'duplicate selected env field')
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            require('$' not in value and '\\' not in value, 'interpolated selected env unsupported')
            result[key] = value
    require(result.get('SERVER_ID') == server, 'captured env server mismatch')
    require(re.fullmatch('[1-9][0-9]{0,18}', result.get('OPENSAMGUK_WORLD_ID', '')),
            'explicit positive world ID required')
    for key in ['GAME_POSTGRES_DB', 'GAME_POSTGRES_USER']:
        result.setdefault(key, 'sammo')
        require(re.fullmatch('[a-zA-Z_][a-zA-Z0-9_]{0,62}', result[key]),
                'unsupported database identity')
    require(result.get('SCENARIO_DIR', '/data/scenarios') == '/data/scenarios',
            'unsupported scenario mount')
    return result


def validate_archive(path):
    """Validate every header before any extraction; reject links, devices and duplicate names."""
    seen = set()
    try:
        with tarfile.open(path, 'r:') as archive:
            for member in archive:
                name = PurePosixPath(member.name)
                require(not name.is_absolute() and '..' not in name.parts and '\\' not in member.name,
                        'unsafe archive path')
                require(member.isdir() or member.isreg(), 'archive links or special entries refused')
                normalized = str(name)
                require(normalized not in seen, 'duplicate archive entry')
                require(member.uid >= 0 and member.gid >= 0 and member.size >= 0,
                        'invalid archive ownership or size')
                seen.add(normalized)
        require(bool(seen), 'empty archive refused')
    except (tarfile.TarError, EOFError, OSError):
        raise RecoveryError('invalid archive') from None
    return seen


def logical_dump_hash(stream):
    """Normalize only pg_dump's generated psql guard key, retaining every data byte."""
    result = hashlib.sha256()
    for line in stream:
        if re.fullmatch(rb'\\(?:un)?restrict [A-Za-z0-9]+\r?\n', line):
            continue
        result.update(line)
    return result.hexdigest()


def validate_storage_archive(path, postgres):
    entries = validate_archive(path)
    if postgres:
        require({'PG_VERSION', 'global/pg_control'} <= entries
                and any(name.startswith('base/') for name in entries)
                and 'postmaster.pid' not in entries, 'incomplete or running PostgreSQL layout')
        with tarfile.open(path, 'r:') as archive:
            for member in archive:
                if str(PurePosixPath(member.name)) == 'PG_VERSION':
                    require(member.isreg() and member.size <= 4, 'invalid PostgreSQL version marker')
                    require(archive.extractfile(member).read() == b'16\n', 'only PostgreSQL 16 layout supported')
                    break
    else:
        require('appendonlydir/appendonly.aof.manifest' in entries and
                any(name.startswith('appendonlydir/') and name.endswith(('.rdb', '.aof')) for name in entries),
                'missing Redis multipart AOF layout')


class Docker:
    def run(self, args, *, stdin=None, stdout=None):
        env = {key: value for key, value in os.environ.items() if not key.startswith('DOCKER_')}
        try:
            stream_args = {'input': stdin.getvalue()} if isinstance(stdin, io.BytesIO) else {'stdin': stdin}
            result = subprocess.run(['docker', '--host', 'unix:///var/run/docker.sock', *args],
                check=True, **stream_args, stdout=stdout if stdout is not None else subprocess.PIPE,
                stderr=subprocess.PIPE, env=env, timeout=900)
            return result.stdout or b''
        except (subprocess.SubprocessError, OSError):
            raise RecoveryError('Docker command failed; inspect privately before retrying') from None


class Recovery:
    def __init__(self, docker=None, *, lock_path=Path('/tmp/opensamguk-production.lock'),
                 token_factory=lambda: uuid.uuid4().hex, sleep=time.sleep):
        self.docker = docker or Docker()
        self.lock_path = Path(lock_path)
        self.token_factory = token_factory
        self.sleep = sleep
        self.owned = []
        self.token = None
        self._lock_owner = None
        self._lock_guard = threading.Lock()

    @contextmanager
    def locked(self):
        owner = (os.getpid(), threading.get_ident())
        if self._lock_owner is not None:
            require(self._lock_owner == owner, 'operation lock belongs to another process/thread')
            # The outer context retains its descriptor; an inner exit never unlocks it.
            yield
            return
        require(self._lock_guard.acquire(blocking=False), 'operation lock acquisition already in progress')
        fd = None
        try:
            fd = os.open(self.lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            require(stat.S_ISREG(os.fstat(fd).st_mode), 'invalid operation lock')
            deadline = time.monotonic() + 10
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    require(time.monotonic() < deadline, 'production lock acquisition timed out')
                    self.sleep(0.1)
            self._lock_owner = owner
            yield
        finally:
            self._lock_owner = None
            if fd is not None:
                os.close(fd)
            self._lock_guard.release()

    def inspect(self, kind, name):
        try:
            result = json.loads(self.docker.run([kind, 'inspect', name]))
            require(isinstance(result, list) and len(result) == 1, 'ambiguous Docker identity')
            return result[0]
        except (ValueError, KeyError, TypeError):
            raise RecoveryError('invalid Docker inspection') from None

    def source(self, server, stack, env):
        project = 'opensamguk-s' + server
        containers = {}
        volumes = {}
        for service in SERVICES:
            name = f's{server}-{service}'
            obj = self.inspect('container', name)
            require(obj['Name'] == '/' + name, 'source container name mismatch')
            state = obj['State']
            require(not state['Running'] and state['Status'] == 'exited', 'source service must be stopped')
            require(not state.get('OOMKilled') and state.get('ExitCode') not in (137, -9),
                    'unclean source shutdown refused')
            if service in VOLUMES:
                require(state.get('ExitCode') == 0, 'database shutdown must be clean')
            labels = obj['Config'].get('Labels') or {}
            require(labels.get('com.docker.compose.project') == project and
                    labels.get('com.docker.compose.service') == service, 'source project/service mismatch')
            mounts = obj['Mounts']
            if service in VOLUMES:
                suffix, destination = VOLUMES[service]
                volume_name = f's{server}-{suffix}'
                require(len(mounts) == 1 and mounts[0].get('Type') == 'volume'
                        and mounts[0].get('Name') == volume_name
                        and mounts[0].get('Destination') == destination, 'source storage mount mismatch')
                volume = self.inspect('volume', volume_name)
                require(volume['Name'] == volume_name and volume['Driver'] == 'local'
                        and not volume.get('Options'), 'unsupported volume driver/layout')
                require((volume.get('Labels') or {}).get('com.docker.compose.project') == project and
                        (volume.get('Labels') or {}).get('com.docker.compose.volume') == suffix,
                        'source volume ownership mismatch')
                volumes[service] = {'name': volume_name, 'destination': destination,
                                    'driver': 'local', 'labels': volume['Labels']}
            elif service in ('game-engine', 'game-api'):
                require(len(mounts) == 1 and mounts[0].get('Type') == 'bind'
                        and mounts[0].get('Source') == str(stack / 'data/scenarios')
                        and mounts[0].get('Destination') == '/data/scenarios'
                        and mounts[0].get('RW') is False, 'source scenario mount mismatch')
            else:
                require(mounts == [], 'unexpected web mount')
            config_env = dict(item.split('=', 1) for item in obj['Config'].get('Env', []) if '=' in item)
            if service == 'game-postgres':
                require(config_env.get('PGDATA', '/var/lib/postgresql/data') == '/var/lib/postgresql/data',
                        'unsupported PGDATA')
                require(config_env.get('POSTGRES_USER') == env['GAME_POSTGRES_USER'] and
                        config_env.get('POSTGRES_DB') == env['GAME_POSTGRES_DB'], 'database env drift')
                require(obj['Config']['Cmd'] == ['postgres'], 'unsupported PostgreSQL command')
            if service in ('game-engine', 'game-api'):
                require(config_env.get('OPENSAMGUK_WORLD_ID') == env['OPENSAMGUK_WORLD_ID'], 'world env drift')
            if service == 'game-redis':
                require(obj['Config']['Cmd'] == REDIS_CMD, 'unsupported Redis persistence contract')
            require(IMAGE_ID.fullmatch(obj['Image']), 'exact source image ID required')
            image = self.inspect('image', obj['Image'])
            require(image['Id'] == obj['Image'], 'source image mismatch')
            containers[service] = {'name': name, 'id': obj['Id'], 'image_id': obj['Image'],
                                   'image_ref': obj['Config']['Image'], 'labels': labels,
                                   'mounts': mounts}
        return containers, volumes

    def begin(self, token=None):
        self.token = token if token is not None else self.token_factory()
        require(re.fullmatch('[a-z0-9]{8,40}', self.token), 'invalid resource token')
        self.owned = []

    def scratch_name(self, suffix):
        return f'os-recovery-{self.token}-{suffix}'

    def refuse_collisions(self, names):
        existing = set(self.docker.run(['container', 'ls', '--all', '--format', '{{.Names}}']).decode().splitlines())
        existing.update(self.docker.run(['volume', 'ls', '--format', '{{.Name}}']).decode().splitlines())
        require(not existing.intersection(names), 'scratch resource collision')

    def create_container(self, suffix, image, args, mount=None, interactive=False, user='0', readonly=False):
        name = self.scratch_name(suffix)
        command = ['container', 'create', '--name', name, '--label', f'{LABEL}={self.token}',
                   '--network', 'none', '--user', user]
        if readonly:
            command.append('--read-only')
        if interactive:
            command.append('-i')
        if mount:
            command += ['--mount', mount]
        command += ['--entrypoint', args[0], image, *args[1:]]
        identity = self.docker.run(command).decode().strip()
        self.owned.append(('container', name, identity))
        return name

    def create_volume(self, suffix):
        name = self.scratch_name(suffix)
        # Docker volume create is idempotent: re-check just before it, while holding the operation lock.
        self.refuse_collisions([name])
        self.docker.run(['volume', 'create', '--label', f'{LABEL}={self.token}', name])
        volume = self.inspect('volume', name)
        require(volume['Name'] == name and (volume.get('Labels') or {}).get(LABEL) == self.token
                and volume['Driver'] == 'local' and not volume.get('Options'),
                'scratch volume creation ownership mismatch')
        self.owned.append(('volume', name, None))
        return name

    def cleanup(self):
        failures = []
        for kind, name, identity in reversed(self.owned):
            try:
                obj = self.inspect(kind, name)
                labels = (obj['Config'].get('Labels') if kind == 'container' else obj.get('Labels')) or {}
                require(labels.get(LABEL) == self.token, 'cleanup ownership mismatch')
                require(obj['Name'] == ('/' + name if kind == 'container' else name), 'cleanup name mismatch')
                if kind == 'container':
                    require(obj['Id'] == identity, 'cleanup identity mismatch')
                self.docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])
            except (RecoveryError, KeyError):
                failures.append(name)
        self.owned = []
        return {'success': not failures, 'remaining_resources': failures}

    def capture(self, *, server, confirm, stack_dir, backup_root):
        validate_target(server, confirm, 'BACKUP')
        stack = checked_path(stack_dir, directory=True)
        root = checked_path(backup_root, directory=True, private=True)
        source_env = checked_path(stack / 'servers' / f's{server}.env')
        compose = checked_path(stack / 'docker-compose.server.yml')
        env = selected_env(source_env, server)
        require(env.get('COMPOSE_HOST_DIR', str(stack)) == str(stack), 'control directory mismatch')
        token = self.token_factory()
        require(re.fullmatch('[a-z0-9]{8,40}', token), 'invalid resource token')
        bundle = root / f'{server}-{token}'
        require(not bundle.exists() and not bundle.is_symlink(), 'existing bundle refused')
        with self.locked():
            self.begin(token)
            started = timestamp()
            before = self.source(server, stack, env)
            self.refuse_collisions([self.scratch_name('capture-postgres'), self.scratch_name('capture-redis')])
            bundle.mkdir(mode=0o700)
            write_private(bundle / 'INCOMPLETE', b'Capture has not completed. Do not restore.\n')
            succeeded = False
            try:
                for origin, destination in [(source_env, 'server.env'), (compose, 'compose.yml')]:
                    write_private(bundle / destination, origin.read_bytes())
                require(selected_env(bundle / 'server.env', server) == env, 'env changed during capture')
                with self.private_output(bundle / 'images.tar') as output:
                    self.docker.run(['image', 'save', *sorted({c['image_id'] for c in before[0].values()})], stdout=output)
                for service, filename in [('game-postgres', 'postgres.tar'), ('game-redis', 'redis.tar')]:
                    destination = VOLUMES[service][1]
                    name = self.create_container('capture-' + filename[:-4], before[0][service]['image_id'],
                        ['tar', '-cpf', '-', '-C', destination, '.'], readonly=True,
                        mount=f'type=volume,source={before[1][service]["name"]},target={destination},readonly')
                    with self.private_output(bundle / filename) as output:
                        self.docker.run(['container', 'start', '--attach', name], stdout=output)
                    validate_storage_archive(bundle / filename, service == 'game-postgres')
                validate_archive(bundle / 'images.tar')
                require(self.source(server, stack, env) == before, 'source identity/state changed during capture')
                require(source_env.read_bytes() == (bundle / 'server.env').read_bytes() and
                        compose.read_bytes() == (bundle / 'compose.yml').read_bytes(), 'control files changed during capture')
                succeeded = True
            finally:
                cleanup = self.cleanup()
                write_private(bundle / 'INCOMPLETE', json_bytes({'capture_complete': False, 'cleanup': cleanup}), replace=True)
            require(succeeded and cleanup['success'], 'capture cleanup failed; incomplete bundle retained')
            manifest = {'version': 1, 'server': server, 'project': 'opensamguk-s' + server,
                        'started_at': started, 'finished_at': timestamp(), 'containers': before[0],
                        'volumes': before[1], 'redis_command': REDIS_CMD,
                        'payloads': {name: digest(bundle / name) for name in sorted(PAYLOADS)}}
            write_private(bundle / 'manifest.json', json_bytes(manifest))
            (bundle / 'INCOMPLETE').unlink()
        return bundle

    @contextmanager
    def private_output(self, path):
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as output:
            yield output
            output.flush()
            os.fsync(output.fileno())

    def validate_bundle(self, server, bundle):
        checked_path(bundle, directory=True, private=True)
        inventory = {entry.name for entry in bundle.iterdir()}
        require(PAYLOADS | {'manifest.json'} <= inventory and
                inventory <= PAYLOADS | {'manifest.json', 'verification.json'}, 'bundle inventory mismatch/incomplete')
        for filename in inventory:
            checked_path(bundle / filename, private=True)
        manifest = read_json(bundle / 'manifest.json')
        require(isinstance(manifest, dict) and set(manifest) == {'version', 'server', 'project', 'started_at', 'finished_at',
                                  'containers', 'volumes', 'redis_command', 'payloads'}, 'manifest schema mismatch')
        require(type(manifest['version']) is int and manifest['version'] == 1, 'unsupported manifest version')
        require(all(isinstance(manifest[key], str) for key in ['server', 'project', 'started_at', 'finished_at']),
                'manifest scalar types mismatch')
        require(manifest['server'] == server and manifest['project'] == 'opensamguk-s' + server,
                'manifest server/project mismatch')
        require(isinstance(manifest['payloads'], dict) and set(manifest['payloads']) == PAYLOADS,
                'manifest payload inventory mismatch')
        for filename, record in manifest['payloads'].items():
            require(isinstance(record, dict) and set(record) == {'sha256', 'size'}
                    and type(record['size']) is int and record['size'] >= 0
                    and isinstance(record['sha256'], str) and re.fullmatch('[0-9a-f]{64}', record['sha256'])
                    and record == digest(bundle / filename), 'payload integrity mismatch')
        require(isinstance(manifest['containers'], dict) and isinstance(manifest['volumes'], dict)
                and set(manifest['containers']) == set(SERVICES) and set(manifest['volumes']) == set(VOLUMES),
                'manifest source inventory mismatch')
        for service, container in manifest['containers'].items():
            require(isinstance(container, dict) and set(container) == {'name', 'id', 'image_id', 'image_ref', 'labels', 'mounts'},
                    'manifest container schema mismatch')
            require(all(isinstance(container[key], str) and container[key] for key in ['name', 'id', 'image_id', 'image_ref']),
                    'manifest container scalar types mismatch')
            require(isinstance(container['labels'], dict) and
                    all(isinstance(key, str) and isinstance(value, str) for key, value in container['labels'].items()),
                    'manifest container labels type mismatch')
            require(isinstance(container['mounts'], list), 'manifest mounts type mismatch')
            for mount in container['mounts']:
                require(isinstance(mount, dict) and {'Type', 'Destination', 'RW'} <= set(mount)
                        and set(mount) <= {'Type', 'Name', 'Source', 'Destination', 'Driver', 'Mode', 'RW', 'Propagation'},
                        'manifest mount schema mismatch')
                require(type(mount['RW']) is bool and
                        all(isinstance(value, str) for key, value in mount.items() if key != 'RW'),
                        'manifest mount scalar types mismatch')
            require(container['name'] == f's{server}-{service}' and IMAGE_ID.fullmatch(container['image_id']),
                    'manifest container identity mismatch')
            require(container['labels'].get('com.docker.compose.project') == manifest['project'] and
                    container['labels'].get('com.docker.compose.service') == service, 'manifest ownership mismatch')
        for service, volume in manifest['volumes'].items():
            suffix, destination = VOLUMES[service]
            require(isinstance(volume, dict) and set(volume) == {'name', 'destination', 'driver', 'labels'} and
                    volume['name'] == f's{server}-{suffix}' and volume['destination'] == destination
                    and volume['driver'] == 'local', 'manifest volume mismatch')
            require(isinstance(volume['labels'], dict) and
                    all(isinstance(key, str) and isinstance(value, str) for key, value in volume['labels'].items()),
                    'manifest volume labels type mismatch')
            require(volume['labels'].get('com.docker.compose.project') == manifest['project'] and
                    volume['labels'].get('com.docker.compose.volume') == suffix, 'manifest volume ownership mismatch')
        require(manifest['redis_command'] == REDIS_CMD, 'unsupported archived Redis contract')
        env = selected_env(bundle / 'server.env', server)
        validate_storage_archive(bundle / 'postgres.tar', True)
        validate_storage_archive(bundle / 'redis.tar', False)
        validate_archive(bundle / 'images.tar')
        return manifest, env

    def wait_ready(self, args):
        for _ in range(30):
            try:
                return self.docker.run(args)
            except RecoveryError:
                self.sleep(1)
        raise RecoveryError('restored database did not become ready')

    def postgres_check(self, name, env):
        user, database = env['GAME_POSTGRES_USER'], env['GAME_POSTGRES_DB']
        self.wait_ready(['container', 'exec', name, 'pg_isready', '-h', '/tmp', '-U', user, '-d', database])
        query = f"""SELECT json_build_object(
          'world_state', (SELECT count(*) FROM world_state),
          'city', (SELECT count(*) FROM city), 'nation', (SELECT count(*) FROM nation),
          'general', (SELECT count(*) FROM general),
          'selected_world', (SELECT count(*) FROM world_state WHERE id = {env['OPENSAMGUK_WORLD_ID']}),
          'failed_migrations', (SELECT count(*) FROM flyway_schema_history WHERE NOT success),
          'migration_count', (SELECT count(*) FROM flyway_schema_history),
          'versions', (SELECT coalesce(json_agg(version ORDER BY installed_rank), '[]'::json)
                       FROM flyway_schema_history WHERE version IS NOT NULL),
          'city_min', (SELECT min(id) FROM city), 'city_max', (SELECT max(id) FROM city));
"""
        result = json.loads(self.docker.run(['container', 'exec', '-i', name, 'psql', '-X', '-A', '-t',
            '-v', 'ON_ERROR_STOP=1', '-h', '/tmp', '-U', user, '-d', database], stdin=io.BytesIO(query.encode())))
        require(result['world_state'] > 0 and result['city'] > 0 and result['selected_world'] == 1,
                'restored world/city identity assertion failed')
        require(result['failed_migrations'] == 0 and result['migration_count'] > 0 and bool(result['versions']),
                'restored migration assertion failed')
        # Anonymous temporary spool never publishes dump content or world/player identifiers.
        import tempfile
        with tempfile.TemporaryFile() as dump:
            self.docker.run(['container', 'exec', name, 'pg_dump', '--no-owner', '--no-privileges',
                '-h', '/tmp', '-U', user, '-d', database], stdout=dump)
            dump.seek(0)
            fingerprint = logical_dump_hash(dump)
        return {'counts': {table: result[table] for table in ['world_state', 'city', 'nation', 'general']},
                'versions': result['versions'], 'migration_success': True, 'selected_world_matches': True,
                'city_min': result['city_min'], 'city_max': result['city_max'],
                'logical_dump_sha256': fingerprint}

    def redis_check(self, name):
        base = ['container', 'exec', name, 'redis-cli', '-s', '/tmp/redis.sock', '--raw']
        require(self.wait_ready([*base, 'PING']).strip() == b'PONG', 'restored Redis PING failed')
        info = dict(line.split(':', 1) for line in self.docker.run([*base, 'INFO', 'persistence']).decode().splitlines()
                    if ':' in line and not line.startswith('#'))
        require(info.get('loading') == '0' and info.get('aof_enabled') == '1' and
                all(info.get(key) == 'ok' for key in ['aof_last_write_status', 'aof_last_bgrewrite_status',
                                                      'rdb_last_bgsave_status']), 'restored Redis persistence failed')
        count = int(self.docker.run([*base, 'DBSIZE']).strip())
        require(count >= 0, 'invalid Redis key count')
        return {'pong': True, 'loading': False, 'appendonly': True, 'persistence_ok': True, 'key_count': count}

    def verify(self, *, server, confirm, bundle):
        validate_target(server, confirm, 'VERIFY')
        bundle = checked_path(bundle, directory=True, private=True)
        require(re.fullmatch(re.escape(server) + r'-[a-z0-9]{8,40}', bundle.name),
                'bundle path does not identify the requested server')
        report_path = bundle / 'verification.json'
        if report_path.exists() or report_path.is_symlink():
            checked_path(report_path, private=True)
        with self.locked():
            self.begin()
            # Invalidate prior success before inspecting the payloads, even when they are corrupt/missing.
            report = {'version': 1, 'server': server, 'manifest_sha256': None,
                      'started_at': timestamp(), 'finished_at': None, 'success': False,
                      'application_boot_verified': False, 'authenticated_smoke_verified': False,
                      'cleanup': {'success': False, 'remaining_resources': []}}
            write_private(bundle / 'verification.json', json_bytes(report), replace=True)
            failure = None
            completed_storage = False
            try:
                manifest, env = self.validate_bundle(server, bundle)
                report['manifest_sha256'] = digest(bundle / 'manifest.json')['sha256']
                self.refuse_collisions([self.scratch_name(suffix) for suffix in
                    ['pgdata', 'redisdata', 'extract-postgres', 'extract-redis', 'postgres', 'redis']])
                image_ids = {item['image_id'] for item in manifest['containers'].values()}
                missing = False
                for image_id in image_ids:
                    try:
                        require(self.inspect('image', image_id)['Id'] == image_id, 'image identity mismatch')
                    except RecoveryError:
                        missing = True
                if missing:
                    with (bundle / 'images.tar').open('rb') as image_archive:
                        self.docker.run(['image', 'load'], stdin=image_archive)
                for image_id in image_ids:
                    require(self.inspect('image', image_id)['Id'] == image_id, 'preserved image unavailable')
                scratch = {}
                for service, suffix, filename in [('game-postgres', 'pgdata', 'postgres.tar'),
                                                  ('game-redis', 'redisdata', 'redis.tar')]:
                    scratch[service] = self.create_volume(suffix)
                    image_id = manifest['containers'][service]['image_id']
                    destination = VOLUMES[service][1]
                    name = self.create_container('extract-' + filename[:-4], image_id,
                        ['tar', '-xpf', '-', '--numeric-owner', '-C', destination], interactive=True,
                        mount=f'type=volume,source={scratch[service]},target={destination}')
                    with (bundle / filename).open('rb') as archive:
                        self.docker.run(['container', 'start', '--attach', '--interactive', name], stdin=archive)
                postgres = self.create_container('postgres', manifest['containers']['game-postgres']['image_id'],
                    ['postgres', '-D', '/var/lib/postgresql/data', '-c', 'listen_addresses=',
                     '-c', 'unix_socket_directories=/tmp'], user='postgres',
                    mount=f'type=volume,source={scratch["game-postgres"]},target=/var/lib/postgresql/data')
                self.docker.run(['container', 'start', postgres])
                report['postgres'] = self.postgres_check(postgres, env)
                redis = self.create_container('redis', manifest['containers']['game-redis']['image_id'],
                    [*REDIS_CMD, '--port', '0', '--unixsocket', '/tmp/redis.sock', '--unixsocketperm', '700',
                     '--dir', '/data', '--aof-load-truncated', 'no'], user='redis',
                    mount=f'type=volume,source={scratch["game-redis"]},target=/data')
                self.docker.run(['container', 'start', redis])
                report['redis'] = self.redis_check(redis)
                completed_storage = True
            except (RecoveryError, ValueError, KeyError, TypeError, OSError, KeyboardInterrupt) as error:
                failure = error
            finally:
                report['cleanup'] = self.cleanup()
                report['finished_at'] = timestamp()
                report['success'] = completed_storage and failure is None and report['cleanup']['success']
                write_private(bundle / 'verification.json', json_bytes(report), replace=True)
            require(report['success'], 'storage verification failed; inspect private verification.json and remaining resources')
            return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for command in ('capture', 'verify'):
        sub = commands.add_parser(command)
        sub.add_argument('--server', required=True)
        sub.add_argument('--confirm', required=True)
        if command == 'capture':
            sub.add_argument('--stack-dir', required=True)
            sub.add_argument('--backup-root', required=True)
        else:
            sub.add_argument('--bundle', required=True)
    args = vars(parser.parse_args())
    command = args.pop('command')
    try:
        result = getattr(Recovery(), command)(**args)
        print(json.dumps({'bundle': str(result), 'capture_complete': True}) if command == 'capture'
              else json.dumps({'storage_verified': True, 'application_boot_verified': False}))
        return 0
    except (RecoveryError, OSError, KeyError, TypeError, ValueError):
        print('Recovery operation failed closed. Inspect private artifacts and owned scratch resources; do not retry blindly.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
