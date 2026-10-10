#!/usr/bin/env python3
"""Bounded, metadata-only observation of the exact pep/account-store boundary.

This module has no mutation mode. Connection settings stay in memory; reports
contain only validated identifiers, numbers and fixed enums. No sibling imports.
"""

import decimal
import json
import os
import posixpath
import re
import selectors
import signal
import subprocess
import sys
import time
import urllib.parse

SCHEMA = 'pep-target-diagnostics/v1'
DOCKER = ('/usr/bin/docker', '--host=unix:///var/run/docker.sock')
LIMIT = 4 * 1024 * 1024
MAX_CONTAINERS = 512
COMMAND_SECONDS = 20
SQL_SECONDS = 30
TOOL_SECONDS = 240
HEX = re.compile(r'[0-9a-f]{64}')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
REVISION = re.compile(r'[0-9a-f]{40}')
SCENARIO = re.compile(r'scenario_(0|[1-9][0-9]{0,54})')
POSITIVE = re.compile(r'[1-9][0-9]{0,9}')
MAX_INT = 2147483647  # world_state.id is PostgreSQL integer, not an invented ID.
PUBLIC = ('spep-game-api', 'spep-web-game')
PRIVATE = ('spep-game-api-validation', 'spep-web-game-validation')
ENGINE = 'spep-game-engine'
GAME_PG = 'spep-game-postgres'
GAME_REDIS = 'spep-game-redis'
GATEWAY_API = 'opensamguk-gateway-api'
GATEWAY_PG = 'opensamguk-gateway-postgres'
# Canonical control repo 6f6d2a6: docker-compose.shared.yml declares this name.
GATEWAY_REDIS = 'opensamguk-gateway-redis'
ROLES = {
    GAME_PG: ('opensamguk-spep', 'game-postgres'),
    GAME_REDIS: ('opensamguk-spep', 'game-redis'),
    ENGINE: ('opensamguk-spep', 'game-engine'),
    **{name: ('opensamguk-spep', 'game-api') for name in (PUBLIC[0], PRIVATE[0])},
    **{name: ('opensamguk-spep', 'web-game') for name in (PUBLIC[1], PRIVATE[1])},
    GATEWAY_API: ('opensamguk-shared', 'gateway-api'),
    GATEWAY_PG: ('opensamguk-shared', 'gateway-postgres'),
    GATEWAY_REDIS: ('opensamguk-shared', 'gateway-redis'),
}
VOLUMES = {
    'spep-game-pgdata': (GAME_PG, '/var/lib/postgresql/data', 'opensamguk-spep', 'game-pgdata'),
    'spep-game-redisdata': (GAME_REDIS, '/data', 'opensamguk-spep', 'game-redisdata'),
    'opensamguk-shared_gateway-pgdata': (GATEWAY_PG, '/var/lib/postgresql/data', 'opensamguk-shared', 'gateway-pgdata'),
    'opensamguk-shared_gateway-redisdata': (GATEWAY_REDIS, '/data', 'opensamguk-shared', 'gateway-redisdata'),
}
APPS = (ENGINE, PUBLIC[0], PRIVATE[0], GATEWAY_API)
ENV_KEYS = ('GAME_DATABASE_URL', 'GAME_DB_USER', 'GAME_DB_PASSWORD', 'REDIS_HOST', 'REDIS_PORT',
            'OPENSAMGUK_WORLD_ID', 'SERVER_ID', 'SCENARIO_CODE', 'POSTGRES_USER', 'POSTGRES_DB', 'PGDATA', 'JAVA_OPTS')
# These can supersede the checked application.yml connection declarations.
JVM_OVERRIDE_KEYS = frozenset(('_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'))
REASONS = frozenset('''SQL_WORLD_INVALID SQL_TARGET_UNVERIFIED COMMAND_REJECTED OBSERVATION_TIMEOUT
COMMAND_TIMEOUT COMMAND_FAILED OUTPUT_LIMIT INVENTORY_INVALID INVENTORY_LIMIT RESPONSE_INVALID
INVENTORY_INCONSISTENT IMAGE_ID_INVALID ENV_INVALID CONNECTION_OVERRIDE_UNVERIFIED MOUNTS_INVALID
NETWORKS_INVALID CONTAINER_COLLISION MIXED_TOPOLOGY TARGET_MISSING INCOMPLETE_TOPOLOGY
OWNERSHIP_MISMATCH TARGET_NOT_RUNNING IMAGE_ID_MISMATCH REVISION_UNAVAILABLE IMAGE_METADATA_INVALID
NETWORK_OVERRIDE_UNVERIFIED APP_COMMAND_OVERRIDE_UNVERIFIED ROLE_REVISION_MISMATCH
VOLUME_OWNERSHIP_MISMATCH VOLUME_LAYOUT_UNSUPPORTED VOLUME_PATH_UNVERIFIED STORAGE_PATH_COLLISION
FOREIGN_VOLUME_CONSUMER STORAGE_MOUNT_MISMATCH VOLUME_FILTER_INCONSISTENT STORAGE_CONSUMER_MISMATCH
STORAGE_UNAVAILABLE PGDATA_UNSUPPORTED APP_MOUNT_UNSUPPORTED CONNECTION_UNAVAILABLE
REDIS_PORT_UNVERIFIED CROSS_STORE_BINDING DATABASE_IDENTITY_UNVERIFIED ALIAS_COLLISION
WORLD_SETTING_UNAVAILABLE WORLD_SETTING_MISMATCH SERVER_BINDING_MISMATCH SERVER_BINDING_UNAVAILABLE DATABASE_ENDPOINT_UNVERIFIED
ENDPOINT_UNRESOLVED NETWORK_UNVERIFIED SQL_RESPONSE_INVALID SQL_WORLD_UNAVAILABLE SQL_SCENARIO_INVALID
SQL_STORE_AMBIGUOUS SQL_VALUE_UNAVAILABLE GENERAL_CREATION_BLOCK_UNSET WORLD_STORE_MISMATCH
IDENTITY_CHANGED USAGE INTERNAL'''.split())


def env_template():
    names = ' '.join('(eq .Name "/' + name + '")' for name in (*APPS, GAME_PG, GATEWAY_PG))
    keys = ' '.join('(eq (index (split . "=") 0) "' + key + '")' for key in ENV_KEYS)
    return '{{if or ' + names + '}}[{{range .Config.Env}}{{if or ' + keys + '}}{{json (printf "%s" .)}},{{end}}{{end}}null]{{else}}[]{{end}}'


def app_env_names_template():
    names = ' '.join('(eq .Name "/' + name + '")' for name in APPS)
    # Project only application key names; classify overrides in Python so dotted,
    # underscored and differently cased Spring names share the same check.
    return '{{if or ' + names + '}}[{{range .Config.Env}}{{json (index (split . "=") 0)}},{{end}}null]{{else}}[]{{end}}'


# The Docker daemon projects fields before sending them to this process. There is
# no full inspect/Env/Labels request, including for foreign inventory members.
CONTAINER_TEMPLATE = (
    '{"id":{{json .Id}},"name":{{json .Name}},"state":{{json .State.Status}},'
    '"image":{{json .Image}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},'
    '"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
    '"mounts":{{json .Mounts}},"networks":[{{range .NetworkSettings.Networks}}'
    '{"id":{{json .NetworkID}},"aliases":{{json .Aliases}}},{{end}}null],'
    '"extra_hosts":{{json .HostConfig.ExtraHosts}},"links":{{json .HostConfig.Links}},'
    '"entrypoint":{{json .Config.Entrypoint}},"cmd":{{json .Config.Cmd}},"env":' + env_template()
    + ',"env_names":' + app_env_names_template() + '}'
)
IMAGE_TEMPLATE = ('{"id":{{json .Id}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}},'
                  '"digests":{{json .RepoDigests}},"entrypoint":{{json .Config.Entrypoint}},"cmd":{{json .Config.Cmd}}}')
VOLUME_TEMPLATE = ('{"name":{{json .Name}},"driver":{{json .Driver}},"options":{{json .Options}},'
                   '"mountpoint":{{json .Mountpoint}},'
                   '"project":{{json (index .Labels "com.docker.compose.project")}},'
                   '"key":{{json (index .Labels "com.docker.compose.volume")}}}')
PSQL_SCRIPT = ("exec env -i PATH=/usr/bin:/bin PGOPTIONS='-c default_transaction_read_only=on' "
               "PGCONNECT_TIMEOUT=5 PGPASSFILE=/dev/null psql -X -A -t -q -w -v ON_ERROR_STOP=1 "
               '-U "$POSTGRES_USER" -d "$POSTGRES_DB" -h /var/run/postgresql -p 5432')


def world_sql(world):
    if type(world) is not int or not 0 < world <= MAX_INT:
        raise Fault('SQL_WORLD_INVALID')
    return ("BEGIN READ ONLY;\nSET LOCAL statement_timeout='5s';\nSET LOCAL lock_timeout='1s';\n"
            "SET LOCAL idle_in_transaction_session_timeout='10s';\n"
            "SELECT json_build_object('kind','world','id',id,'scenario',scenario_code,"
            "'max_present',config ? 'maxgeneral','max',config->'maxgeneral',"
            "'block_present',config ? 'block_general_create','block',config->'block_general_create') "
            f"FROM world_state WHERE id={world};\n"
            "SELECT json_build_object('kind','kv','key',key,'value',value) FROM game_kv "
            f"WHERE world_id={world} AND \"table\"='game_env' AND namespace='game_env' "
            "AND key IN ('maxgeneral','block_general_create') ORDER BY key LIMIT 3;\nROLLBACK;\n").encode()


class Fault(Exception):
    """Only a fixed, source-owned reason ever leaves an error path."""

    def __init__(self, reason):
        self.reason = reason if reason in REASONS else 'INTERNAL'
        super().__init__()  # Never carry subprocess output or exception text.


def is_id(value):
    return isinstance(value, str) and HEX.fullmatch(value) is not None


def ids(raw):
    try:
        values = raw.decode('ascii').splitlines()
    except (UnicodeError, AttributeError):
        raise Fault('INVENTORY_INVALID') from None
    if len(values) > MAX_CONTAINERS:
        raise Fault('INVENTORY_LIMIT')
    if any(not is_id(value) for value in values) or len(set(values)) != len(values):
        raise Fault('INVENTORY_INVALID')
    return sorted(values)


def command_allowed(args, stdin, pg_id):
    """A second allowlist at the actual subprocess boundary."""
    if args == ('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}'):
        return stdin is None
    if (len(args) == 8 and args[:4] == ('container', 'ls', '--all', '--no-trunc')
            and args[4] == '--filter' and args[5] in ('volume=' + v for v in VOLUMES)
            and args[6:] == ('--format', '{{.ID}}')):
        return stdin is None
    if len(args) >= 5 and args[:3] == ('container', 'inspect', '--format'):
        return (args[3] == CONTAINER_TEMPLATE and 1 <= len(args[4:]) <= 64
                and all(is_id(value) for value in args[4:]) and stdin is None)
    if len(args) == 5 and args[:3] == ('image', 'inspect', '--format'):
        return args[3] == IMAGE_TEMPLATE and bool(DIGEST.fullmatch(args[4])) and stdin is None
    if len(args) == 5 and args[:3] == ('volume', 'inspect', '--format'):
        return args[3] == VOLUME_TEMPLATE and args[4] in VOLUMES and stdin is None
    expected = ('container', 'exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                pg_id, '/bin/sh', '-c', PSQL_SCRIPT)
    if pg_id and args == expected and isinstance(stdin, bytes):
        match = re.search(rb'FROM world_state WHERE id=([1-9][0-9]{0,9});', stdin)
        return bool(match and stdin == world_sql(int(match[1])))
    return False


class Transport:
    """Stream both pipes under one cap; discard stderr without retaining it."""

    def __init__(self):
        self.deadline = time.monotonic() + TOOL_SECONDS
        self.pg_id = None

    def authorize_sql(self, pg_id):
        if not is_id(pg_id):
            raise Fault('SQL_TARGET_UNVERIFIED')
        self.pg_id = pg_id

    def run(self, args, stdin=None):
        args = tuple(args)
        if not command_allowed(args, stdin, self.pg_id):
            raise Fault('COMMAND_REJECTED')
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise Fault('OBSERVATION_TIMEOUT')
        seconds = min(SQL_SECONDS if stdin is not None else COMMAND_SECONDS, remaining)
        end = time.monotonic() + seconds
        process = None
        output = bytearray()
        count = 0
        try:
            process = subprocess.Popen(
                [*DOCKER, *args], shell=False, stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
                env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'},
            )
            if stdin is not None:
                process.stdin.write(stdin)  # Fixed SQL is below PIPE_BUF; no credentials.
                process.stdin.close()
            with selectors.DefaultSelector() as selector:
                for pipe in (process.stdout, process.stderr):
                    os.set_blocking(pipe.fileno(), False)
                    selector.register(pipe, selectors.EVENT_READ)
                while selector.get_map():
                    wait = end - time.monotonic()
                    if wait <= 0:
                        raise Fault('COMMAND_TIMEOUT')
                    for key, _ in selector.select(min(wait, 0.1)):
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        count += len(chunk)
                        if count > LIMIT:
                            raise Fault('OUTPUT_LIMIT')
                        if key.fileobj is process.stdout:
                            output.extend(chunk)
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise Fault('COMMAND_TIMEOUT')
                if process.wait(timeout=remaining) != 0:
                    raise Fault('COMMAND_FAILED')
            return bytes(output)
        except (OSError, subprocess.SubprocessError):
            raise Fault('COMMAND_FAILED') from None
        finally:
            if process is not None:
                if process.poll() is None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    pass  # Do not exceed the observer deadline on an unkillable client.
                for pipe in (process.stdin, process.stdout, process.stderr):
                    if pipe is not None:
                        pipe.close()


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise Fault('RESPONSE_INVALID')
        value[key] = item
    return value


def invalid_constant(_value):
    raise Fault('RESPONSE_INVALID')


def json_lines(raw, max_lines=64, max_line=LIMIT):
    if len(raw) > LIMIT:
        raise Fault('OUTPUT_LIMIT')
    try:
        lines = raw.splitlines()
        if len(lines) > max_lines or any(not line or len(line) > max_line for line in lines):
            raise Fault('RESPONSE_INVALID')
        values = [json.loads(line, object_pairs_hook=unique_object, parse_float=decimal.Decimal,
                             parse_constant=invalid_constant)
                  for line in lines]
        if any(not isinstance(value, dict) for value in values):
            raise Fault('RESPONSE_INVALID')
        return values
    except (ValueError, UnicodeError, RecursionError):
        raise Fault('RESPONSE_INVALID') from None


def one(raw):
    values = json_lines(raw, max_lines=1)
    if len(values) != 1:
        raise Fault('RESPONSE_INVALID')
    return values[0]


def snapshot(transport):
    inventory = ids(transport.run(('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}')))
    containers = {}
    for offset in range(0, len(inventory), 64):
        batch = inventory[offset:offset + 64]
        observed = json_lines(transport.run(('container', 'inspect', '--format', CONTAINER_TEMPLATE, *batch)))
        if (len(observed) != len(batch) or any(not is_id(item.get('id')) for item in observed)
                or sorted(item['id'] for item in observed) != batch):
            raise Fault('INVENTORY_INCONSISTENT')
        for item in observed:
            if (not isinstance(item.get('mounts'), list) or len(item['mounts']) > 64
                    or any(not isinstance(mount, dict) for mount in item['mounts'])):
                raise Fault('MOUNTS_INVALID')
            containers[item['id']] = item
    volumes, filtered, images = {}, {}, {}
    for name in VOLUMES:
        filtered[name] = ids(transport.run(('container', 'ls', '--all', '--no-trunc', '--filter',
                                          'volume=' + name, '--format', '{{.ID}}')))
        volumes[name] = one(transport.run(('volume', 'inspect', '--format', VOLUME_TEMPLATE, name)))
    for item in containers.values():
        if not isinstance(item.get('name'), str):
            raise Fault('INVENTORY_INVALID')
        if item['name'].lstrip('/') in ROLES:
            image = item.get('image')
            if not isinstance(image, str) or not DIGEST.fullmatch(image):
                raise Fault('IMAGE_ID_INVALID')
            if image not in images:
                images[image] = one(transport.run(('image', 'inspect', '--format', IMAGE_TEMPLATE, image)))
    return {'containers': containers, 'volumes': volumes, 'filtered': filtered, 'images': images}


def blank():
    return {'schema': SCHEMA, 'result': 'UNKNOWN', 'reasons': [], 'mode': 'UNKNOWN',
            'containers': [], 'volumes': [], 'bindings': {}, 'world': {}, 'drift': []}


def settings(item):
    raw = item.get('env')
    if not isinstance(raw, list) or len(raw) > 64:
        raise Fault('ENV_INVALID')
    result = {}
    for value in raw:
        if value is None:
            continue  # Go template sentinel, never an environment value.
        if not isinstance(value, str) or len(value) > 8192 or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise Fault('ENV_INVALID')
        key, sep, content = value.partition('=')
        if not sep or key not in ENV_KEYS or key in result:
            raise Fault('ENV_INVALID')
        result[key] = content
    names = item.get('env_names')
    if not isinstance(names, list) or len(names) > 512:
        raise Fault('ENV_INVALID')
    seen = set()
    for key in names:
        if key is None:
            continue  # Go template sentinel, never an environment name.
        if (not isinstance(key, str) or not 0 < len(key) <= 256 or key in seen
                or any(ord(c) < 32 or ord(c) == 127 for c in key)):
            raise Fault('ENV_INVALID')
        seen.add(key)
        if key.casefold().startswith('spring') or key.upper() in JVM_OVERRIDE_KEYS:
            raise Fault('CONNECTION_OVERRIDE_UNVERIFIED')
    # All three canonical Dockerfiles expand JAVA_OPTS. Admit only the neutral
    # GC/memory/entropy flags declared by those images and the control Compose.
    for option in result.get('JAVA_OPTS', '').split():
        if (option not in ('-XX:+UseG1GC', '-Djava.security.egd=file:/dev/./urandom')
                and not re.fullmatch(r'-Xm[sx][1-9][0-9]{0,8}[mMgG]', option)
                and not re.fullmatch(r'-XX:(Max|Initial)RAMPercentage=(100|[1-9][0-9]?)(\.[0-9]{1,2})?', option)):
            raise Fault('CONNECTION_OVERRIDE_UNVERIFIED')
    return result


def integer(value, minimum=1):
    if type(value) is int:
        number = value
    elif isinstance(value, decimal.Decimal) and value.is_finite() and value == value.to_integral_value():
        number = int(value) if minimum <= value <= MAX_INT else -1
    else:
        return None
    return number if minimum <= number <= MAX_INT else None


def world_setting(env):
    value = env.get('OPENSAMGUK_WORLD_ID', '')
    return int(value) if POSITIVE.fullmatch(value) and int(value) <= MAX_INT else None


def path_valid(value):
    return (isinstance(value, str) and 0 < len(value) <= 4096 and value.startswith('/')
            and not any(ord(c) < 32 or ord(c) == 127 for c in value)
            and posixpath.normpath(value) == value and value != '/')


def overlaps(a, b):
    return a == b or a.startswith(b.rstrip('/') + '/') or b.startswith(a.rstrip('/') + '/')


def analyze(snap):
    report = blank()
    unknown, unsafe = set(), set()
    by_name = {}
    inventory = snap['containers']
    for item in inventory.values():
        name = item.get('name')
        if not isinstance(name, str) or not name.startswith('/'):
            raise Fault('INVENTORY_INVALID')
        if name[1:] in ROLES:
            if name[1:] in by_name:
                unsafe.add('CONTAINER_COLLISION')
            by_name[name[1:]] = item
        if not isinstance(item.get('mounts'), list) or len(item['mounts']) > 64:
            raise Fault('MOUNTS_INVALID')
        if not isinstance(item.get('networks'), list) or len(item['networks']) > 64:
            raise Fault('NETWORKS_INVALID')
        if any(entry is not None and not isinstance(entry, dict) for entry in item['networks']):
            raise Fault('NETWORKS_INVALID')
    pub = [name in by_name for name in PUBLIC]
    private = [name in by_name for name in PRIVATE]
    if any(pub) and any(private):
        report['mode'] = 'MIXED'
        unsafe.add('MIXED_TOPOLOGY')
    elif all(pub):
        report['mode'] = 'PUBLIC'
    elif all(private):
        report['mode'] = 'PRIVATE'
    elif not any(pub + private):
        report['mode'] = 'NONE'
        unknown.add('TARGET_MISSING')
    else:
        unknown.add('INCOMPLETE_TOPOLOGY')
    targets = PUBLIC if report['mode'] == 'PUBLIC' else PRIVATE if report['mode'] == 'PRIVATE' else ()
    required = (GAME_PG, GAME_REDIS, ENGINE, *targets, GATEWAY_API, GATEWAY_PG, GATEWAY_REDIS)
    if any(name not in by_name for name in required):
        unknown.add('TARGET_MISSING')
    envs = {}
    revisions = {}
    for name, item in sorted(by_name.items()):
        own = (item.get('project'), item.get('service')) == ROLES[name]
        if not own:
            unsafe.add('OWNERSHIP_MISMATCH')
        state = item.get('state')
        if state not in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead'):
            state = 'UNKNOWN'
        if state != 'running':
            unknown.add('TARGET_NOT_RUNNING')
        image = snap['images'][item['image']]
        if image.get('id') != item['image']:
            unsafe.add('IMAGE_ID_MISMATCH')
        revision = image.get('revision')
        valid_revision = isinstance(revision, str) and bool(REVISION.fullmatch(revision))
        if ROLES[name][1] in ('game-api', 'game-engine', 'web-game', 'gateway-api') and not valid_revision:
            unknown.add('REVISION_UNAVAILABLE')
        revisions[name] = revision if valid_revision else None
        digests = image.get('digests') or []
        if not isinstance(digests, list) or len(digests) > 64:
            raise Fault('IMAGE_METADATA_INVALID')
        digest_ids = []
        for value in digests:
            if isinstance(value, str) and value.count('@') == 1 and DIGEST.fullmatch(value.split('@')[1]):
                digest_ids.append(value.split('@')[1])
            else:
                unknown.add('IMAGE_METADATA_INVALID')
        report['containers'].append({'name': name, 'id': item['id'], 'state': state, 'image': item['image'],
                                     'revision': revisions[name], 'digests': sorted(set(digest_ids)),
                                     'project': ROLES[name][0] if own else 'UNKNOWN',
                                     'service': ROLES[name][1] if own else 'UNKNOWN',
                                     'ownership': 'MATCH' if own else 'MISMATCH'})
        if name in (*APPS, GAME_PG, GATEWAY_PG):
            try:
                envs[name] = settings(item)
            except Fault as error:
                unknown.add(error.reason)
        if name in APPS:
            if item.get('extra_hosts') or item.get('links'):
                unsafe.add('NETWORK_OVERRIDE_UNVERIFIED')
            if item.get('entrypoint') != image.get('entrypoint') or item.get('cmd') != image.get('cmd'):
                unknown.add('APP_COMMAND_OVERRIDE_UNVERIFIED')
    if report['mode'] in ('PUBLIC', 'PRIVATE'):
        roles = [revisions.get(name) for name in (*targets, ENGINE)]
        if all(roles) and len(set(roles)) != 1:
            unsafe.add('ROLE_REVISION_MISMATCH')
    mountpoints = []
    for volume_name, (owner, destination, project, key) in VOLUMES.items():
        volume = snap['volumes'][volume_name]
        owned = volume.get('name') == volume_name and (volume.get('project'), volume.get('key')) == (project, key)
        if not owned:
            unsafe.add('VOLUME_OWNERSHIP_MISMATCH')
        local = volume.get('driver') == 'local' and (volume.get('options') is None or volume.get('options') == {})
        if not local:
            unsafe.add('VOLUME_LAYOUT_UNSUPPORTED')
        point = volume.get('mountpoint')
        if not path_valid(point):
            unknown.add('VOLUME_PATH_UNVERIFIED')
        else:
            if any(overlaps(point, other) for other in mountpoints):
                unsafe.add('STORAGE_PATH_COLLISION')
            mountpoints.append(point)
        consumers = []
        for item in inventory.values():
            for mount in item['mounts']:
                if not isinstance(mount, dict):
                    raise Fault('MOUNTS_INVALID')
                if mount.get('Type') == 'volume' and mount.get('Name') == volume_name:
                    consumers.append(item['id'])
                    if item.get('name') != '/' + owner:
                        unsafe.add('FOREIGN_VOLUME_CONSUMER')
                    if (mount.get('Destination') != destination or mount.get('Source') != point
                            or mount.get('Driver') != 'local' or mount.get('RW') is not True):
                        unsafe.add('STORAGE_MOUNT_MISMATCH')
                elif path_valid(point) and path_valid(mount.get('Source')) and overlaps(point, mount['Source']):
                    unsafe.add('STORAGE_PATH_COLLISION')
        if sorted(set(consumers)) != snap['filtered'][volume_name] or len(consumers) != len(set(consumers)):
            unknown.add('VOLUME_FILTER_INCONSISTENT')
        owner_item = by_name.get(owner)
        if not owner_item:
            unknown.add('STORAGE_UNAVAILABLE')
        elif len(owner_item['mounts']) != 1 or consumers != [owner_item['id']]:
            unsafe.add('STORAGE_CONSUMER_MISMATCH')
        report['volumes'].append({'name': volume_name, 'ownership': 'MATCH' if owned else 'MISMATCH',
                                  'project': project if owned else 'UNKNOWN', 'key': key if owned else 'UNKNOWN',
                                  'layout': 'LOCAL_DEFAULT' if local else 'UNKNOWN',
                                  'consumers': [owner] if owner_item and consumers == [owner_item['id']] else [],
                                  'consumer_match': bool(owner_item and consumers == [owner_item['id']]),
                                  'other_consumer_present': any(identifier != (owner_item or {}).get('id') for identifier in consumers),
                                  'filter_match': sorted(set(consumers)) == snap['filtered'][volume_name]})
    for name in (GAME_PG, GATEWAY_PG):
        if envs.get(name, {}).get('PGDATA', '/var/lib/postgresql/data') != '/var/lib/postgresql/data':
            unsafe.add('PGDATA_UNSUPPORTED')
    for name in (*APPS, *PUBLIC[1:], *PRIVATE[1:]):
        item = by_name.get(name)
        if item:
            for mount in item['mounts']:
                if (mount.get('Type') != 'bind' or mount.get('RW') is not False
                        or mount.get('Destination') not in ('/data/scenarios', '/app/data/map/topdown')):
                    unsafe.add('APP_MOUNT_UNSUPPORTED')
    bindings = {}
    for name in (ENGINE, *targets[:1], GATEWAY_API):
        app, env = by_name.get(name), envs.get(name)
        if not app or env is None:
            bindings[name] = {'postgres': 'UNKNOWN', 'redis': 'UNKNOWN'}
            unknown.add('CONNECTION_UNAVAILABLE')
            continue
        expected_pg = GATEWAY_PG if name == GATEWAY_API else GAME_PG
        expected_redis = GATEWAY_REDIS if name == GATEWAY_API else GAME_REDIS
        try:
            host, db = database_endpoint(env)
            pg = resolve(app, host, inventory)
            redis_host = env.get('REDIS_HOST', '')
            if env.get('REDIS_PORT', '6379') != '6379':
                raise Fault('REDIS_PORT_UNVERIFIED')
            redis = resolve(app, redis_host, inventory)
            pg_match = pg == by_name.get(expected_pg, {}).get('id')
            redis_match = redis == by_name.get(expected_redis, {}).get('id')
            if not pg_match or not redis_match:
                unsafe.add('CROSS_STORE_BINDING')
            if (db != envs.get(expected_pg, {}).get('POSTGRES_DB')
                    or env.get('GAME_DB_USER') != envs.get(expected_pg, {}).get('POSTGRES_USER')):
                unknown.add('DATABASE_IDENTITY_UNVERIFIED')
            bindings[name] = {'postgres': 'MATCH' if pg_match else 'MISMATCH', 'redis': 'MATCH' if redis_match else 'MISMATCH'}
        except Fault as error:
            (unsafe if error.reason == 'ALIAS_COLLISION' else unknown).add(error.reason)
            bindings[name] = {'postgres': 'UNKNOWN', 'redis': 'UNKNOWN'}
    report['bindings'] = bindings
    world = world_setting(envs.get(ENGINE, {}))
    api_world = world_setting(envs.get(targets[0], {})) if targets else None
    if world is None or api_world is None:
        unknown.add('WORLD_SETTING_UNAVAILABLE')
        world = None
    elif world != api_world:
        unsafe.add('WORLD_SETTING_MISMATCH')
        world = None
    if targets and targets[0] in envs:
        if 'SERVER_ID' not in envs[targets[0]]:
            unknown.add('SERVER_BINDING_UNAVAILABLE')
        elif envs[targets[0]]['SERVER_ID'] != 'pep':
            unsafe.add('SERVER_BINDING_MISMATCH')
    report['world'] = {'setting': world}
    report['reasons'] = sorted(unknown | unsafe)
    report['result'] = 'UNSAFE' if unsafe else 'UNKNOWN' if unknown else 'CONSISTENT'
    return report, world


def database_endpoint(env):
    value = env.get('GAME_DATABASE_URL', '')
    if not value.startswith('jdbc:postgresql://') or not env.get('GAME_DB_USER') or not env.get('GAME_DB_PASSWORD'):
        raise Fault('CONNECTION_UNAVAILABLE')
    try:
        parsed = urllib.parse.urlsplit(value[5:])
        if (parsed.scheme != 'postgresql' or parsed.port != 5432 or parsed.username is not None
                or parsed.password is not None or parsed.query or parsed.fragment
                or not re.fullmatch(r'/[a-zA-Z0-9_]{1,63}', parsed.path)):
            raise Fault('DATABASE_ENDPOINT_UNVERIFIED')
        return parsed.hostname, parsed.path[1:]
    except ValueError:
        raise Fault('DATABASE_ENDPOINT_UNVERIFIED') from None


def resolve(app, host, inventory):
    if not isinstance(host, str) or not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', host):
        raise Fault('ENDPOINT_UNRESOLVED')
    networks = app['networks']
    network_ids = {entry['id'] for entry in networks if entry is not None and is_id(entry.get('id'))}
    if not network_ids:
        raise Fault('NETWORK_UNVERIFIED')
    candidates = set()
    for item in inventory.values():
        for entry in item['networks']:
            if entry is None:
                continue
            if not isinstance(entry, dict) or not is_id(entry.get('id')):
                raise Fault('NETWORK_UNVERIFIED')
            aliases = entry.get('aliases') or []
            if not isinstance(aliases, list) or len(aliases) > 64 or any(not isinstance(alias, str) for alias in aliases):
                raise Fault('NETWORK_UNVERIFIED')
            if entry['id'] in network_ids and (host in aliases or item['name'] == '/' + host):
                candidates.add(item['id'])
    if len(candidates) > 1:
        raise Fault('ALIAS_COLLISION')
    if not candidates:
        raise Fault('ENDPOINT_UNRESOLVED')
    return next(iter(candidates))


def field(present, value, minimum):
    number = integer(value, minimum)
    if type(present) is not bool:
        raise Fault('SQL_RESPONSE_INVALID')
    value_type = ('missing' if not present else 'null' if value is None else 'boolean' if type(value) is bool
                  else 'number' if type(value) is int or isinstance(value, decimal.Decimal)
                  else 'string' if isinstance(value, str) else 'other')
    return {'present': present, 'type': value_type, 'value': number,
            **({'bit1_set': bool(number & 1) if number is not None else None} if minimum == 0 else {})}


def read_world(raw, world):
    rows = json_lines(raw, max_lines=8, max_line=4096)
    worlds = [row for row in rows if row.get('kind') == 'world']
    kv = [row for row in rows if row.get('kind') == 'kv']
    if (len(worlds) != 1 or len(worlds) + len(kv) != len(rows)
            or type(worlds[0].get('id')) is not int or worlds[0].get('id') != world):
        raise Fault('SQL_WORLD_UNAVAILABLE')
    row = worlds[0]
    scenario = row.get('scenario')
    if not isinstance(scenario, str) or not SCENARIO.fullmatch(scenario):
        raise Fault('SQL_SCENARIO_INVALID')
    values = {}
    for item in kv:
        key = item.get('key')
        if key not in ('maxgeneral', 'block_general_create') or key in values or 'value' not in item:
            raise Fault('SQL_STORE_AMBIGUOUS')
        values[key] = item['value']
    result = {'id': world, 'scenario': scenario,
              'config': {'maxgeneral': field(row.get('max_present'), row.get('max'), 1),
                         'block_general_create': field(row.get('block_present'), row.get('block'), 0)},
              'game_env': {key: field(key in values, values.get(key), 1 if key == 'maxgeneral' else 0)
                           for key in ('maxgeneral', 'block_general_create')}}
    reasons = set()
    for store in ('config', 'game_env'):
        if any(not value['present'] or value['value'] is None for value in result[store].values()):
            reasons.add('SQL_VALUE_UNAVAILABLE')
        if result[store]['block_general_create']['bit1_set'] is False:
            reasons.add('GENERAL_CREATION_BLOCK_UNSET')
    mismatch = any(result['config'][key]['value'] is not None and result['game_env'][key]['value'] is not None
                   and result['config'][key]['value'] != result['game_env'][key]['value'] for key in values)
    if mismatch:
        reasons.add('WORLD_STORE_MISMATCH')
    result['stores'] = 'MISMATCH' if mismatch else 'UNKNOWN' if reasons else 'MATCH'
    return result, reasons


def observe(transport):
    before = snapshot(transport)
    report, world = analyze(before)
    if report['result'] == 'CONSISTENT' and world is not None:
        pg_id = next(item['id'] for item in before['containers'].values() if item['name'] == '/' + GAME_PG)
        transport.authorize_sql(pg_id)
        try:
            raw = transport.run(('container', 'exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                                 pg_id, '/bin/sh', '-c', PSQL_SCRIPT), world_sql(world))
            report['world'], reasons = read_world(raw, world)
            report['reasons'] = sorted(set(report['reasons']) | reasons)
            if reasons:
                report['result'] = 'UNSAFE' if 'WORLD_STORE_MISMATCH' in reasons else 'UNKNOWN'
        except Fault as error:
            report['reasons'] = sorted(set(report['reasons']) | {error.reason})
            report['result'] = 'UNKNOWN'
    after = snapshot(transport)
    analyze(after)  # Validate types before comparison; no raw after-image is published.
    changed = [key for key in ('containers', 'volumes', 'filtered', 'images') if before[key] != after[key]]
    if changed:
        report['result'] = 'IDENTITY_DRIFT'
        report['reasons'] = sorted(set(report['reasons']) | {'IDENTITY_CHANGED'})
        report['drift'] = ['consumers' if key == 'filtered' else key for key in changed]
    return report


def main(argv=None, transport=None):
    argv = sys.argv[1:] if argv is None else argv
    report = blank()
    code = 64
    if argv != ['observe']:
        report['reasons'] = ['USAGE']
    else:
        previous_handler = None
        try:
            if transport is None:
                def expired(_signum, _frame):
                    raise Fault('OBSERVATION_TIMEOUT')
                previous_handler = signal.signal(signal.SIGALRM, expired)
                signal.alarm(TOOL_SECONDS)
            report = observe(transport if transport is not None else Transport())
            code = {'CONSISTENT': 0, 'UNKNOWN': 2, 'UNSAFE': 3, 'IDENTITY_DRIFT': 4}[report['result']]
        except Fault as error:
            report['reasons'] = [error.reason]
            code = 1 if error.reason == 'INTERNAL' else 2
        except Exception:
            report['reasons'] = ['INTERNAL']
            code = 1
        finally:
            if previous_handler is not None:
                signal.alarm(0)
                signal.signal(signal.SIGALRM, previous_handler)
    print(json.dumps(report, separators=(',', ':'), ensure_ascii=True, allow_nan=False))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
