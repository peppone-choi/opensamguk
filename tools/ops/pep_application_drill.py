#!/usr/bin/env python3
"""Isolated archived-engine proof. Never contacts a live service or restores Redis."""
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import tarfile
import tempfile
from types import MappingProxyType
import unicodedata
import uuid

from game_server_recovery import (RecoveryError, checked_path, digest, read_json,
                                 require, unique_object, validate_target)

LABEL = 'org.opensamguk.pep-drill'
SERVICES = ('game-postgres', 'game-redis', 'game-engine')


@dataclass(frozen=True)
class ScenarioEntry:
    path: str
    type: str
    size: int
    sha256: str | None


@dataclass(frozen=True)
class ScenarioTreeDigest:
    bundle_manifest_sha256: str
    entries: tuple[ScenarioEntry, ...]
    tree_sha256: str
    file_count: int
    total_bytes: int

    @classmethod
    def capture(cls, tree, bundle_manifest_sha256):
        tree = checked_path(tree, directory=True, private=True)
        require(isinstance(bundle_manifest_sha256, str) and
                re.fullmatch('[0-9a-f]{64}', bundle_manifest_sha256), 'invalid bundle digest')
        entries = []
        def walk(directory):
            for path in directory.iterdir():
                relative = path.relative_to(tree).as_posix()
                require(relative == unicodedata.normalize('NFC', relative) and
                        '\\' not in relative and all(p not in ('', '.', '..') for p in relative.split('/')),
                        'ambiguous scenario path')
                info = path.lstat()
                require(not info.st_mode & 0o077, 'private scenario permissions required')
                if stat.S_ISDIR(info.st_mode):
                    entries.append(ScenarioEntry(relative, 'directory', 0, None))
                    walk(path)
                else:
                    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                            'scenario links or special files refused')
                    item = digest(path)
                    entries.append(ScenarioEntry(relative, 'file', item['size'], item['sha256']))
        walk(tree)
        entries.sort(key=lambda entry: entry.path.encode('utf-8'))
        aggregate = hashlib.sha256(b'opensamguk-scenario-tree-v1\0')
        for entry in entries:
            data = json.dumps(asdict(entry), ensure_ascii=False, sort_keys=True,
                              separators=(',', ':')).encode('utf-8')
            aggregate.update(len(data).to_bytes(8, 'big'))
            aggregate.update(data)
        return cls(bundle_manifest_sha256, tuple(entries), aggregate.hexdigest(),
                   sum(e.type == 'file' for e in entries), sum(e.size for e in entries))

    def manifest(self):
        return dict(version=1, bundle_manifest_sha256=self.bundle_manifest_sha256,
                    entries=[asdict(e) for e in self.entries], tree_sha256=self.tree_sha256,
                    file_count=self.file_count, total_bytes=self.total_bytes)


@dataclass(frozen=True)
class SourceService:
    container_id: str
    image_id: str
    memory: int


@dataclass(frozen=True, repr=False)
class SourceEngineInputs:
    server: str
    services: object = field(repr=False)
    engine_env: object = field(repr=False)
    engine_mounts: tuple = field(repr=False)
    api_mounts: tuple = field(repr=False)

    def __repr__(self):
        return 'SourceEngineInputs(<redacted source inspection>)'

    @classmethod
    def from_inspections(cls, server, inspections):
        validate_target(server, 'VERIFY ' + server, 'VERIFY')
        try:
            env = {}
            for entry in inspections['game-engine']['Config']['Env']:
                key, separator, value = entry.partition('=')
                require(separator and key and key not in env and '\0' not in entry,
                        'invalid or duplicate engine environment')
                env[key] = value
            services = {s: SourceService(inspections[s]['Id'], inspections[s]['Image'],
                                         inspections[s]['HostConfig']['Memory']) for s in (*SERVICES, 'game-api')}
            mounts = lambda s: tuple(MappingProxyType(dict(m)) for m in inspections[s]['Mounts'])
            return cls(server, MappingProxyType(services), MappingProxyType(env),
                       mounts('game-engine'), mounts('game-api'))
        except (KeyError, TypeError, ValueError, AttributeError):
            raise RecoveryError('invalid source engine inspection') from None


def instant(value):
    require(isinstance(value, str), 'deterministic world clock required')
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(result.tzinfo is not None, 'deterministic world clock required')
        return result.astimezone(timezone.utc)
    except ValueError:
        raise RecoveryError('deterministic world clock required') from None


def expected_clock(rows):
    require(isinstance(rows, list) and len(rows) == 1, 'selected clone world is not unique')
    row = rows[0]
    for key in ('current_year', 'current_month', 'current_phase', 'tick_seconds'):
        require(type(row.get(key)) is int, 'malformed world clock')
    require(1 <= row['current_month'] <= 12 and 1 <= row['current_phase'] <= 3 and row['tick_seconds'] > 0,
            'malformed world clock')
    meta = row.get('meta') or {}
    require(isinstance(meta, dict), 'malformed world clock')
    last = instant(meta.get('lastTurnTime') or row.get('start_time'))
    return dict(currentYear=row['current_year'], currentMonth=row['current_month'],
                currentPhase=row['current_phase'], currentPhaseText={1: '상순', 2: '중순', 3: '하순'}[row['current_phase']],
                tickSeconds=row['tick_seconds'], lastTurnTime=last.isoformat(),
                nextRunTime=(last + timedelta(seconds=row['tick_seconds'])).isoformat())


def status_matches(value, profile, clock):
    expected = dict(profile=profile, state='paused', running=False, paused=True, loopAlive=True,
                    statusLabel='동결중', serviceMaterialized=True, autoStartEnabled=True,
                    recoveryMode='READY', recoveryReady=True, recoveryReason=None,
                    consecutiveFailures=0, failedTicks=0, successfulTicks=0,
                    lastTickStartedAt=None, lastTickCompletedAt=None, lastTickFailedAt=None,
                    lastTickError=None, lastSuccessfulTickAgeSeconds=None, clockError=None)
    if not isinstance(value, dict) or any(k not in value or type(value[k]) is not type(v) or value[k] != v
                                          for k, v in expected.items()):
        return False
    if type(value.get('loopUptimeSeconds')) is not int or value['loopUptimeSeconds'] < 0:
        return False
    actual = value.get('clock')
    if not isinstance(actual, dict) or set(actual) != set(clock):
        return False
    try:
        return all(instant(actual[k]) == instant(v) if k in ('lastTurnTime', 'nextRunTime')
                   else type(actual[k]) is type(v) and actual[k] == v for k, v in clock.items())
    except (RecoveryError, ValueError, TypeError):
        return False


@dataclass(frozen=True)
class ApplicationProof:
    bundle_manifest_sha256: str
    scenario_tree_sha256: str
    world_id: int
    profile: str
    clock: dict
    plock_delta: dict
    status: dict
    cleanup: dict


class PepApplicationDrill:
    def __init__(self, *, token_factory=lambda: uuid.uuid4().hex, attempts=90):
        self.token_factory, self.attempts = token_factory, attempts
        self.last_cleanup = {'success': True, 'remaining_resources': []}
        self.owned = []

    def _scenario(self, bundle, tree, scenario, manifest_sha):
        require(Path(tree) == bundle.with_name(bundle.name + '.scenario') / 'tree',
                'scenario companion location mismatch')
        checked_path(tree.parent, directory=True, private=True)
        require({p.name for p in tree.parent.iterdir()} == {'manifest.json', 'tree'},
                'scenario companion inventory mismatch')
        metadata = read_json(checked_path(tree.parent / 'manifest.json', private=True))
        try:
            current = ScenarioTreeDigest.capture(tree, manifest_sha)
            require(current == scenario and metadata == current.manifest() and
                    type(metadata['version']) is int and type(metadata['file_count']) is int and
                    type(metadata['total_bytes']) is int and
                    all(type(e['size']) is int for e in metadata['entries']), 'effective scenario input changed')
        except (OSError, ValueError, TypeError, KeyError):
            raise RecoveryError('effective scenario input changed') from None

    def _inputs(self, source, manifest, env):
        for service in (*SERVICES, 'game-api'):
            record = source.services[service]
            archived = manifest['containers'][service]
            require(record.container_id == archived['id'] and record.image_id == archived['image_id'],
                    'source archived identity mismatch')
            if service in SERVICES:
                require(type(record.memory) is int and record.memory > 0, 'original finite memory limit required')
        def bind(mounts):
            require(len(mounts) == 1, 'single read-only scenario bind required')
            mount = dict(mounts[0])
            require(mount.get('Type') == 'bind' and mount.get('RW') is False and
                    isinstance(mount.get('Destination'), str) and mount['Destination'].startswith('/') and
                    '..' not in Path(mount['Destination']).parts and ',' not in mount['Destination'],
                    'single read-only scenario bind required')
            return mount
        archived = bind(manifest['containers']['game-engine']['mounts'])
        require(bind(source.engine_mounts) == archived and
                bind(source.api_mounts) == bind(manifest['containers']['game-api']['mounts']) and
                bind(source.api_mounts) == archived, 'engine/API scenario bind mismatch')
        original = source.engine_env
        require(original.get('OPENSAMGUK_WORLD_ID') == env['OPENSAMGUK_WORLD_ID'], 'source world identity mismatch')
        require(original.get('GAME_DB_USER') == env['GAME_POSTGRES_USER'], 'source database user mismatch')
        url = original.get('GAME_DATABASE_URL', '')
        match = re.fullmatch(r'jdbc:postgresql://[^/?#]+/([a-zA-Z_][a-zA-Z0-9_]{0,62})(\?[^#]*)?', url)
        require(match and match[1] == env['GAME_POSTGRES_DB'], 'source database identity mismatch')
        require(bool(original.get('TURN_PROFILE_NAME')), 'explicit source turn profile required')
        port = original.get('GAME_ENGINE_PORT', '8082')
        require(re.fullmatch('[0-9]{1,5}', port) and 0 < int(port) <= 65535, 'invalid engine port')
        return archived['Destination'], match[2] or '', port

    def _report(self, bundle, server, manifest_sha):
        report = read_json(checked_path(bundle / 'verification.json', private=True))
        require(isinstance(report, dict) and set(report) == {'version', 'server', 'manifest_sha256', 'started_at',
                'finished_at', 'success', 'application_boot_verified', 'authenticated_smoke_verified',
                'cleanup', 'postgres', 'redis'} and type(report.get('version')) is int and report['version'] == 1 and
                report.get('server') == server and report.get('manifest_sha256') == manifest_sha and
                report.get('success') is True and report.get('cleanup') == {'success': True, 'remaining_resources': []} and
                report['cleanup']['success'] is True and report['application_boot_verified'] is False and
                report['authenticated_smoke_verified'] is False and
                all(isinstance(report[k], str) and bool(report[k]) for k in ('started_at', 'finished_at')),
                'successful matching storage verification required')
        redis = report['redis']
        require(isinstance(redis, dict) and set(redis) == {'pong', 'loading', 'appendonly', 'persistence_ok', 'key_count'} and
                redis['pong'] is True and redis['loading'] is False and redis['appendonly'] is True and
                redis['persistence_ok'] is True and type(redis['key_count']) is int and redis['key_count'] >= 0,
                'invalid storage verification assertions')
        pg = report.get('postgres')
        require(isinstance(pg, dict) and set(pg) == {'counts', 'versions', 'migration_success',
                'selected_world_matches', 'city_min', 'city_max', 'logical_dump_sha256'} and
                pg['migration_success'] is True and pg['selected_world_matches'] is True and
                isinstance(pg['counts'], dict) and set(pg['counts']) == {'world_state', 'city', 'nation', 'general'} and
                all(type(n) is int and n >= 0 for n in pg['counts'].values()) and
                pg['counts']['world_state'] > 0 and pg['counts']['city'] > 0 and
                isinstance(pg['versions'], list) and bool(pg['versions']) and
                all(isinstance(v, str) for v in pg['versions']) and
                type(pg['city_min']) is int and type(pg['city_max']) is int and pg['city_min'] <= pg['city_max'] and
                isinstance(pg['logical_dump_sha256'], str) and re.fullmatch('[0-9a-f]{64}', pg['logical_dump_sha256']),
                'invalid storage verification assertions')
        return pg

    def _identity(self, kind, obj):
        # Local Docker volumes have no opaque Id. Bind the daemon name plus immutable creation identity.
        return obj.get('Id') if kind != 'volume' else (obj.get('Name'), obj.get('CreatedAt'), obj.get('Mountpoint'))

    def _load_missing_images(self, recovery, bundle, missing):
        """Reduce the validated Docker-save archive to missing configs/layers, with no tag changes."""
        with tarfile.open(bundle / 'images.tar', 'r:') as source, tempfile.TemporaryFile() as spool:
            try:
                records = json.load(source.extractfile('manifest.json'), object_pairs_hook=unique_object)
                require(isinstance(records, list), 'unsupported saved image index')
                selected, paths, found = [], set(), set()
                for record in records:
                    require(isinstance(record, dict) and isinstance(record.get('Config'), str) and
                            isinstance(record.get('Layers'), list) and all(isinstance(p, str) for p in record['Layers']),
                            'unsupported saved image record')
                    config = source.extractfile(record['Config']).read()
                    identity = 'sha256:' + hashlib.sha256(config).hexdigest()
                    if identity in missing:
                        require(identity not in found, 'duplicate saved image identity')
                        found.add(identity)
                        selected.append(dict(Config=record['Config'], RepoTags=[], Layers=record['Layers']))
                        paths.update([record['Config'], *record['Layers']])
                require(found == set(missing), 'missing archived image config')
                with tarfile.open(fileobj=spool, mode='w:') as output:
                    payload = json.dumps(selected, separators=(',', ':')).encode()
                    info = tarfile.TarInfo('manifest.json'); info.size = len(payload)
                    output.addfile(info, io.BytesIO(payload))
                    for path in sorted(paths):
                        info = source.getmember(path)
                        require(info.isreg(), 'saved image data must be regular')
                        output.addfile(info, source.extractfile(info))
                spool.seek(0)
                recovery.docker.run(['image', 'load'], stdin=spool)
            except (KeyError, ValueError, TypeError, AttributeError, tarfile.TarError):
                raise RecoveryError('unsupported saved image archive') from None

    def _create(self, recovery, kind, name, args):
        result = recovery.docker.run(args).decode().strip()
        # Record the create response before inspection, so even a changed/missing object is reported.
        provisional = result if kind != 'volume' else (name, None, None)
        self.owned.append((kind, name, provisional))
        obj = recovery.inspect(kind, name)
        identity = self._identity(kind, obj)
        labels = (obj.get('Config', {}).get('Labels') if kind == 'container' else obj.get('Labels')) or {}
        require(labels.get(LABEL) == self.token and obj.get('Name') == ('/' + name if kind == 'container' else name)
                and identity and (kind == 'volume' or result == identity), 'created resource ownership mismatch')
        self.owned[-1] = (kind, name, identity)
        if kind == 'volume':
            require(obj.get('Driver') == 'local' and not obj.get('Options') and all(identity),
                    'unsupported clone volume')
        if kind == 'network':
            require(obj.get('Internal') is True, 'clone network must be internal')
        return name

    def _cleanup(self, recovery):
        remaining = []
        for kind, name, identity in reversed(self.owned):
            try:
                obj = recovery.inspect(kind, name)
                labels = (obj.get('Config', {}).get('Labels') if kind == 'container' else obj.get('Labels')) or {}
                require(labels.get(LABEL) == self.token and self._identity(kind, obj) == identity and
                        obj.get('Name') == ('/' + name if kind == 'container' else name), 'cleanup ownership mismatch')
                recovery.docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])
            except (RecoveryError, KeyError, TypeError, OSError):
                remaining.append(dict(type=kind, name=name, id=identity, label=self.token))
        self.owned = []
        self.last_cleanup = dict(success=not remaining, remaining_resources=remaining)
        return self.last_cleanup

    def _sql(self, recovery, pg, env, query):
        return recovery.docker.run(['container', 'exec', '-i', pg, 'psql', '-X', '-A', '-t',
            '-v', 'ON_ERROR_STOP=1', '-h', '/tmp', '-U', env['GAME_POSTGRES_USER'], '-d', env['GAME_POSTGRES_DB']],
            stdin=io.BytesIO(query.encode()))

    def _plock(self, recovery, pg, env):
        world = env['OPENSAMGUK_WORLD_ID']
        predicate = f'''world_id = {world} AND "table" = 'game_env' AND namespace IN ('', 'game_env') AND key = 'plock' '''
        query = f'''/* plock-read */ SELECT coalesce(json_agg(row_to_json(q) ORDER BY id), '[]'::json)
            FROM (SELECT id, namespace, value FROM game_kv WHERE {predicate} ORDER BY id ASC) q;'''
        before = json.loads(self._sql(recovery, pg, env, query))
        require(isinstance(before, list) and all(type(r.get('id')) is int and r['id'] > 0 and
                r.get('namespace') in ('', 'game_env') and 'value' in r for r in before) and
                [r['id'] for r in before] == sorted({r['id'] for r in before}), 'invalid clone plock lookup')
        already_paused = bool(before) and type(before[-1]['value']) is int and before[-1]['value'] == 1
        if before:
            mutation = f"UPDATE game_kv SET value = '1'::jsonb WHERE {predicate} AND id = {before[-1]['id']} RETURNING id"
        else:
            mutation = f'''INSERT INTO game_kv (world_id, "table", namespace, key, value)
                VALUES ({world}, 'game_env', 'game_env', 'plock', '1'::jsonb) RETURNING id'''
        if not already_paused:
            count = self._sql(recovery, pg, env, '/* plock-write */ WITH changed AS (' + mutation + ') SELECT count(*) FROM changed;')
            require(count.strip() == b'1', 'clone plock mutation must affect exactly one row')
        after = json.loads(self._sql(recovery, pg, env, query))
        require(isinstance(after, list) and len(after) == max(1, len(before)) and
                after[:-1] == before[:-1] and type(after[-1].get('value')) is int and after[-1]['value'] == 1,
                'effective clone plock is not one')
        if before:
            require(after[-1] == dict(before[-1], value=1), 'clone plock winner changed')
        else:
            require(after[-1].get('namespace') == 'game_env' and type(after[-1].get('id')) is int,
                    'canonical clone plock insert required')
        return dict(updated_rows=int(bool(before) and not already_paused), inserted_rows=int(not before),
                    loader_visible_rows=len(after), effective_value=1)

    def prove(self, recovery, bundle, source_inputs, preserved_scenario, scenario):
        bundle, tree = Path(bundle), Path(preserved_scenario)
        self.owned = []
        self.last_cleanup = {'success': True, 'remaining_resources': []}
        with recovery.locked():
            validate_target(source_inputs.server, 'VERIFY ' + source_inputs.server, 'VERIFY')
            manifest, env = recovery.validate_bundle(source_inputs.server, bundle)
            manifest_sha = digest(bundle / 'manifest.json')['sha256']
            prior = self._report(bundle, source_inputs.server, manifest_sha)
            destination, url_options, port = self._inputs(source_inputs, manifest, env)
            self._scenario(bundle, tree, scenario, manifest_sha)
            self.token = self.token_factory()
            require(isinstance(self.token, str) and re.fullmatch('[a-z0-9]{8,40}', self.token), 'invalid resource token')
            prefix = 'pep-drill-' + self.token + '-'
            names = {k: prefix + k for k in ('network', 'pgdata', 'redisdata', 'extract', *SERVICES)}
            for kind in ('container', 'volume', 'network'):
                existing = set(recovery.docker.run([kind, 'ls', *(['--all'] if kind == 'container' else []),
                                                    '--format', '{{.Names}}' if kind == 'container' else '{{.Name}}']).decode().splitlines())
                require(not existing.intersection(names.values()), 'clone resource collision')
            try:
                images = {source_inputs.services[s].image_id for s in SERVICES}
                missing = []
                for image in sorted(images):
                    try:
                        require(recovery.inspect('image', image)['Id'] == image, 'archived image identity mismatch')
                    except RecoveryError:
                        missing.append(image)
                if missing:
                    self._load_missing_images(recovery, bundle, missing)
                for image in images:
                    require(recovery.inspect('image', image)['Id'] == image, 'archived image unavailable')
                label = [ '--label', LABEL + '=' + self.token ]
                network = self._create(recovery, 'network', names['network'],
                    ['network', 'create', '--internal', *label, names['network']])
                for key in ('pgdata', 'redisdata'):
                    self._create(recovery, 'volume', names[key], ['volume', 'create', *label, names[key]])
                def container(key, image, args, *, network_name=network, memory=None):
                    argv = ['container', 'create', '--name', names[key], '--network', network_name,
                            *label, '--pull=never', '--log-driver', 'none']
                    if memory is not None:
                        argv += ['--memory', str(memory)]
                    return self._create(recovery, 'container', names[key], [*argv, *args, image])
                pgimage = source_inputs.services['game-postgres'].image_id
                extract = self._create(recovery, 'container', names['extract'],
                    ['container', 'create', '--name', names['extract'], '--network', 'none', *label,
                     '--pull=never', '--log-driver', 'none', '--interactive', '--entrypoint', 'tar',
                     '--mount', f'type=volume,source={names["pgdata"]},target=/var/lib/postgresql/data',
                     pgimage, '-xpf', '-', '--numeric-owner', '-C', '/var/lib/postgresql/data'])
                with (bundle / 'postgres.tar').open('rb') as archive:
                    recovery.docker.run(['container', 'start', '--attach', '--interactive', extract], stdin=archive)
                pg = self._create(recovery, 'container', names['game-postgres'],
                    ['container', 'create', '--name', names['game-postgres'], '--network', network, *label,
                     '--pull=never', '--log-driver', 'none', '--memory', str(source_inputs.services['game-postgres'].memory),
                     '--user', 'postgres', '--entrypoint', 'postgres', '--mount',
                     f'type=volume,source={names["pgdata"]},target=/var/lib/postgresql/data', pgimage,
                     '-D', '/var/lib/postgresql/data', '-c', 'listen_addresses=*', '-c', 'unix_socket_directories=/tmp'])
                recovery.docker.run(['container', 'start', pg])
                require(recovery.postgres_check(pg, env) == prior, 'clone storage differs from verified bundle')
                clock = expected_clock(json.loads(self._sql(recovery, pg, env,
                    '/* clock-source */ SELECT coalesce(json_agg(row_to_json(q)), \'[]\'::json) FROM '
                    '(SELECT current_year, current_month, current_phase, tick_seconds, meta, start_time '
                    'FROM world_state WHERE id = ' + env['OPENSAMGUK_WORLD_ID'] + ') q;')))
                delta = self._plock(recovery, pg, env)
                redis = self._create(recovery, 'container', names['game-redis'],
                    ['container', 'create', '--name', names['game-redis'], '--network', network, *label,
                     '--pull=never', '--log-driver', 'none', '--memory', str(source_inputs.services['game-redis'].memory),
                     '--mount', f'type=volume,source={names["redisdata"]},target=/data',
                     source_inputs.services['game-redis'].image_id, 'redis-server', '--save', '', '--appendonly', 'no'])
                recovery.docker.run(['container', 'start', redis])
                require(recovery.wait_ready(['container', 'exec', redis, 'redis-cli', '--raw', 'PING']).strip() == b'PONG' and
                        recovery.docker.run(['container', 'exec', redis, 'redis-cli', '--raw', 'DBSIZE']).strip() == b'0',
                        'fresh clone Redis must be empty')
                recovery.docker.run(['container', 'exec', redis, 'sh', '-c', 'test -z "$(ls -A /data)"'])
                self._scenario(bundle, tree, scenario, manifest_sha)
                clone_env = dict(source_inputs.engine_env)
                clone_env.update(GAME_DATABASE_URL=f'jdbc:postgresql://{pg}:5432/{env["GAME_POSTGRES_DB"]}{url_options}',
                                 REDIS_HOST=redis, REDIS_PORT='6379', SCENARIO_SEED_ENABLED='false',
                                 OPENSAMGUK_DAEMON_ENABLED='true')
                environment = [part for k, v in sorted(clone_env.items()) for part in ('-e', k + '=' + v)]
                engine = container('game-engine', source_inputs.services['game-engine'].image_id,
                    [*environment, '--mount', f'type=bind,source={tree},target={destination},readonly'],
                    memory=source_inputs.services['game-engine'].memory)
                recovery.docker.run(['container', 'start', engine])
                verified = None
                for _ in range(self.attempts):
                    try:
                        value = json.loads(recovery.docker.run(['container', 'exec', engine, 'wget', '-T', '2', '-t', '1', '-qO-',
                            f'http://localhost:{port}/admin/turn-daemon/status']))
                        if status_matches(value, clone_env['TURN_PROFILE_NAME'], clock):
                            # Whitelist output: never serialize arbitrary error/status extension fields.
                            verified = {key: value[key] for key in (
                                'profile', 'state', 'running', 'paused', 'loopAlive', 'statusLabel', 'serviceMaterialized',
                                'autoStartEnabled', 'recoveryMode', 'recoveryReady', 'recoveryReason', 'consecutiveFailures',
                                'failedTicks', 'successfulTicks', 'lastTickStartedAt', 'lastTickCompletedAt', 'lastTickFailedAt',
                                'lastTickError', 'lastSuccessfulTickAgeSeconds', 'clockError', 'loopUptimeSeconds', 'clock')}
                            break
                    except (RecoveryError, ValueError, TypeError):
                        pass
                    recovery.sleep(1)
                require(verified is not None, 'archived engine did not satisfy strict paused recovery diagnostics')
                self._scenario(bundle, tree, scenario, manifest_sha)
            finally:
                cleanup = self._cleanup(recovery)
            require(cleanup['success'], 'application drill cleanup failed; inspect remaining private resources')
            return ApplicationProof(manifest_sha, scenario.tree_sha256, int(env['OPENSAMGUK_WORLD_ID']),
                                    clone_env['TURN_PROFILE_NAME'], clock, delta, verified, cleanup)
