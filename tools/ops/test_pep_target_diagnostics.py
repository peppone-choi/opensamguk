#!/usr/bin/env python3
"""Synthetic diagnostics proofs. Never contact Docker, a database or a workflow."""

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('diagnostics', Path(__file__).with_name('pep_target_diagnostics.py'))
d = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(d)
CANARY = 'password-JWT-PEM-https://secret.invalid/::error::SECRET'
NETWORK = 'f' * 64
REVISION = 'a' * 40


def encoded(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


def fixture(mode='PUBLIC', world=7):
    names = (d.GAME_PG, d.GAME_REDIS, d.ENGINE, *(d.PUBLIC if mode == 'PUBLIC' else d.PRIVATE),
             d.GATEWAY_API, d.GATEWAY_PG, d.GATEWAY_REDIS)
    containers, images, volumes = {}, {}, {}
    for index, name in enumerate(names, 1):
        identifier = f'{index:064x}'
        image = 'sha256:' + f'{index + 1000:064x}'
        role = d.ROLES[name][1]
        item = {'id': identifier, 'name': '/' + name, 'state': 'running', 'image': image,
                'project': d.ROLES[name][0], 'service': role, 'mounts': [],
                'networks': [{'id': NETWORK, 'aliases': [name, role]}, None],
                'extra_hosts': None, 'links': None, 'entrypoint': ['entrypoint'], 'cmd': ['application'], 'env': [], 'env_names': []}
        if name in d.APPS:
            gateway = name == d.GATEWAY_API
            item['env'] = ['GAME_DATABASE_URL=jdbc:postgresql://' + ('gateway-postgres:5432/sammo_gateway' if gateway else d.GAME_PG + ':5432/sammo'),
                           'GAME_DB_USER=sammo', 'GAME_DB_PASSWORD=' + CANARY,
                           'REDIS_HOST=' + ('gateway-redis' if gateway else d.GAME_REDIS),
                           'REDIS_PORT=6379', 'OPENSAMGUK_WORLD_ID=' + str(world), 'SERVER_ID=pep', None]
            item['env_names'] = [part.partition('=')[0] for part in item['env'] if part is not None]
        if name in (d.GAME_PG, d.GATEWAY_PG):
            item['env'] = ['POSTGRES_USER=sammo', 'POSTGRES_DB=' + ('sammo_gateway' if name == d.GATEWAY_PG else 'sammo'), None]
        containers[identifier] = item
        images[image] = {'id': image, 'revision': ('b' * 40 if name == d.GATEWAY_API else REVISION)
                          if role in ('game-api', 'game-engine', 'web-game', 'gateway-api') else None,
                          'digests': ['ghcr.io/fixture@' + image], 'entrypoint': ['entrypoint'], 'cmd': ['application']}
    for volume, (owner, destination, project, key) in d.VOLUMES.items():
        point = '/var/lib/docker/volumes/' + volume + '/_data'
        volumes[volume] = {'name': volume, 'driver': 'local', 'options': None, 'mountpoint': point, 'project': project, 'key': key}
        item = next(item for item in containers.values() if item['name'] == '/' + owner)
        item['mounts'] = [{'Type': 'volume', 'Name': volume, 'Destination': destination, 'Source': point, 'Driver': 'local', 'RW': True}]
    return {'containers': containers, 'images': images, 'volumes': volumes}


def named(state, name):
    return next(item for item in state['containers'].values() if item['name'] == '/' + name)


def replace_env(item, key, value):
    item['env'] = [part for part in item['env'] if part is None or not part.startswith(key + '=')]
    item['env'].append(key + '=' + value)


def sql_rows(world=7, maximum=50, block=1):
    return [
        {'kind': 'world', 'id': world, 'scenario': 'scenario_3190', 'max_present': True,
         'max': maximum, 'block_present': True, 'block': block},
        {'kind': 'kv', 'key': 'block_general_create', 'value': block},
        {'kind': 'kv', 'key': 'maxgeneral', 'value': maximum},
    ]


class FakeTransport:
    def __init__(self, before=None, after=None, rows=None):
        self.before = fixture() if before is None else before
        self.after = self.before if after is None else after
        self.active = self.before
        self.snapshots = 0
        self.commands = []
        self.pg_id = None
        self.sql = b''.join(encoded(row) for row in (sql_rows() if rows is None else rows))
        self.filter_override = None
        self.fail = None

    def authorize_sql(self, pg_id):
        self.pg_id = pg_id

    def run(self, args, stdin=None):
        args = tuple(args)
        if not d.command_allowed(args, stdin, self.pg_id):
            raise AssertionError('Unexpected command')
        self.commands.append((args, stdin))
        if self.fail:
            raise self.fail
        if args[:2] == ('container', 'ls'):
            if '--filter' not in args:
                self.active = self.before if self.snapshots == 0 else self.after
                self.snapshots += 1
                return ('\n'.join(sorted(self.active['containers'])) + '\n').encode()
            volume = args[5].removeprefix('volume=')
            values = sorted(item['id'] for item in self.active['containers'].values()
                            if any(m.get('Type') == 'volume' and m.get('Name') == volume for m in item['mounts']))
            if self.filter_override:
                values = self.filter_override(volume, values)
            return ('\n'.join(values) + '\n').encode() if values else b''
        if args[:2] == ('container', 'inspect'):
            return b''.join(encoded(self.active['containers'][identifier]) for identifier in args[4:])
        if args[:2] == ('image', 'inspect'):
            return encoded(self.active['images'][args[4]])
        if args[:2] == ('volume', 'inspect'):
            return encoded(self.active['volumes'][args[4]])
        if args[:2] == ('container', 'exec'):
            return self.sql
        raise AssertionError('Unknown command')


def run(fake, argv=None):
    output = io.StringIO()
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()) as error:
        code = d.main(['observe'] if argv is None else argv, fake)
    text = output.getvalue()
    if len(text.splitlines()) != 1 or error.getvalue():
        raise AssertionError('Output contract failed')
    if CANARY in text or '::error::' in text or 'secret.invalid' in text:
        raise AssertionError('Canary leak')
    return code, json.loads(text)


class DiagnosticsTests(unittest.TestCase):
    def test_public_and_private_positive(self):
        for mode in ('PUBLIC', 'PRIVATE'):
            with self.subTest(mode=mode):
                fake = FakeTransport(fixture(mode))
                code, report = run(fake)
                self.assertEqual((code, report['result'], report['mode']), (0, 'CONSISTENT', mode))
                self.assertEqual(report['world']['id'], 7)
                self.assertEqual(report['world']['stores'], 'MATCH')
                self.assertEqual(fake.snapshots, 2)
                self.assertEqual(len([c for c in fake.commands if c[0][:2] == ('container', 'exec')]), 1)
                self.assertNotIn('safe_to_delete', report)
                self.assertNotIn('deletion_approved', report)

    def test_actual_world_used_and_no_world_one_fallback(self):
        fake = FakeTransport(fixture(world=19), rows=sql_rows(world=19))
        self.assertEqual(run(fake)[0], 0)
        query = next(stdin for args, stdin in fake.commands if args[:2] == ('container', 'exec'))
        self.assertIn(b'world_id=19', query)
        self.assertIn(b'WHERE id=19', query)
        self.assertNotIn(b'world_id=1 ', query)

    def test_missing_or_mismatched_world_never_executes(self):
        for value in ('8', '0', '-1', '7; DROP TABLE users', '2147483648', '07'):
            with self.subTest(value=value):
                state = fixture()
                replace_env(named(state, d.PUBLIC[0]), 'OPENSAMGUK_WORLD_ID', value)
                fake = FakeTransport(state)
                self.assertNotEqual(run(fake)[0], 0)
                self.assertFalse(any(args[:2] == ('container', 'exec') for args, _ in fake.commands))

    def test_both_consumer_modes_and_stopped_opposite_are_unsafe(self):
        state = fixture()
        private = fixture('PRIVATE')
        for offset, name in enumerate(d.PRIVATE, 100):
            item = copy.deepcopy(named(private, name))
            item['id'] = f'{offset:064x}'
            item['state'] = 'exited'
            state['containers'][item['id']] = item
            state['images'][item['image']] = private['images'][item['image']]
        code, report = run(FakeTransport(state))
        self.assertEqual((code, report['mode']), (3, 'MIXED'))

    def test_missing_gateway_redis_is_unknown_without_name_guess(self):
        state = fixture()
        identifier = named(state, d.GATEWAY_REDIS)['id']
        del state['containers'][identifier]
        self.assertEqual(run(FakeTransport(state))[0], 2)

    def test_incomplete_and_none_topology_are_unknown(self):
        for remove in ((d.PUBLIC[0],), d.PUBLIC):
            state = fixture()
            for name in remove:
                del state['containers'][named(state, name)['id']]
            code, report = run(FakeTransport(state))
            self.assertEqual(code, 2)
            self.assertEqual(report['mode'], 'NONE' if len(remove) == 2 else 'UNKNOWN')
            self.assertNotIn(d.PRIVATE[0], report['bindings'])
            self.assertNotIn(d.PUBLIC[0], report['bindings'])

    def test_foreign_and_stopped_volume_consumers(self):
        for status in ('running', 'exited'):
            state = fixture()
            foreign = copy.deepcopy(named(state, d.GAME_PG))
            foreign.update(id='e' * 64, name='/' + CANARY, state=status, project=CANARY, service=CANARY, env=[])
            state['containers'][foreign['id']] = foreign
            code, report = run(FakeTransport(state))
            self.assertEqual(code, 3)
            self.assertIn('FOREIGN_VOLUME_CONSUMER', report['reasons'])

    def test_volume_filter_must_agree_with_full_stopped_inventory(self):
        fake = FakeTransport()
        fake.filter_override = lambda _volume, _values: []
        self.assertIn('VOLUME_FILTER_INCONSISTENT', run(fake)[1]['reasons'])

    def test_readonly_scenario_map_mounts_and_storage_path_overlap(self):
        state = fixture()
        for name in (d.ENGINE, d.PUBLIC[0]):
            named(state, name)['mounts'].append({'Type': 'bind', 'Source': '/stack/scenarios',
                'Destination': '/data/scenarios', 'RW': False})
        named(state, d.PUBLIC[0])['mounts'].append({'Type': 'bind', 'Source': '/stack/topdown/pep',
                'Destination': '/app/data/map/topdown', 'RW': False})
        self.assertEqual(run(FakeTransport(state))[0], 0)
        named(state, d.ENGINE)['mounts'][0]['Source'] = state['volumes']['spep-game-pgdata']['mountpoint'] + '/nested'
        self.assertEqual(run(FakeTransport(state))[0], 3)

    def test_container_and_image_id_collision_rejected(self):
        state = fixture()
        item = named(state, d.GATEWAY_PG)
        item['id'] = named(state, d.GAME_PG)['id']
        self.assertNotEqual(run(FakeTransport(state))[0], 0)
        state = fixture()
        state['images'][named(state, d.ENGINE)['image']]['id'] = 'sha256:' + 'e' * 64
        self.assertIn('IMAGE_ID_MISMATCH', run(FakeTransport(state))[1]['reasons'])

    def test_volume_name_and_mountpoint_collisions(self):
        for collision in ('name', 'mountpoint', 'bind_source'):
            state = fixture()
            pg = named(state, d.GAME_PG)['mounts'][0]
            gw = named(state, d.GATEWAY_PG)['mounts'][0]
            if collision == 'name':
                gw['Name'] = pg['Name']
            elif collision == 'mountpoint':
                state['volumes']['opensamguk-shared_gateway-pgdata']['mountpoint'] = pg['Source']
                gw['Source'] = pg['Source']
            else:
                gw.update(Type='bind', Source=pg['Source'], Name=None)
            self.assertEqual(run(FakeTransport(state))[0], 3)

    def test_bind_driver_options_ownership_and_pgdata(self):
        for change in ('bind', 'driver', 'options', 'owner', 'pgdata', 'extra_mount'):
            state = fixture()
            volume = state['volumes']['spep-game-pgdata']
            pg = named(state, d.GAME_PG)
            if change == 'bind':
                pg['mounts'][0]['Type'] = 'bind'
            elif change == 'driver':
                volume['driver'] = 'external'
            elif change == 'options':
                volume['options'] = {'device': CANARY}
            elif change == 'owner':
                volume['project'] = CANARY
            elif change == 'pgdata':
                replace_env(pg, 'PGDATA', '/foreign/data')
            else:
                pg['mounts'].append({'Type': 'bind', 'Source': '/foreign', 'Destination': '/extra'})
            self.assertEqual(run(FakeTransport(state))[0], 3, change)

    def test_different_url_aliases_same_endpoint_not_separate(self):
        state = fixture()
        named(state, d.GAME_PG)['networks'][0]['aliases'].append('other-db')
        replace_env(named(state, d.GATEWAY_API), 'GAME_DATABASE_URL', 'jdbc:postgresql://other-db:5432/sammo')
        code, report = run(FakeTransport(state))
        self.assertEqual(code, 3)
        self.assertIn('CROSS_STORE_BINDING', report['reasons'])

    def test_alias_collision_even_on_stopped_foreign_container(self):
        state = fixture()
        item = copy.deepcopy(named(state, d.GATEWAY_PG))
        item.update(id='e' * 64, name='/foreign', mounts=[], state='exited', env=[])
        item['networks'][0]['aliases'] = [d.GAME_PG]
        state['containers'][item['id']] = item
        self.assertIn('ALIAS_COLLISION', run(FakeTransport(state))[1]['reasons'])

    def test_external_or_disconnected_endpoints_are_unknown(self):
        for url in ('jdbc:postgresql://secret.invalid:5432/sammo', 'jdbc:postgresql://127.0.0.1:5432/sammo',
                    'jdbc:postgresql://user:password@host:5432/sammo', 'jdbc:postgresql://host:5432/sammo?password=SECRET'):
            state = fixture()
            replace_env(named(state, d.ENGINE), 'GAME_DATABASE_URL', url)
            self.assertEqual(run(FakeTransport(state))[0], 2)
        state = fixture()
        named(state, d.ENGINE)['networks'][0]['id'] = 'e' * 64
        self.assertEqual(run(FakeTransport(state))[0], 2)

    def test_connection_and_command_overrides_not_accepted(self):
        for change in ('spring', 'extra_hosts', 'cmd'):
            state = fixture()
            app = named(state, d.ENGINE)
            if change == 'spring':
                app['env_names'].append('SPRING_APPLICATION_JSON')
            elif change == 'extra_hosts':
                app['extra_hosts'] = [CANARY]
            else:
                app['cmd'] = [CANARY]
            self.assertNotEqual(run(FakeTransport(state))[0], 0)

    def test_source_backed_jvm_flags_and_override_names(self):
        neutral = '-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=40.0 -Djava.security.egd=file:/dev/./urandom -Xms64m -Xmx256m'
        state = fixture()
        replace_env(named(state, d.ENGINE), 'JAVA_OPTS', neutral)
        self.assertEqual(run(FakeTransport(state))[0], 0)
        replace_env(named(state, d.ENGINE), 'JAVA_OPTS', '-Dspring.datasource.url=' + CANARY)
        self.assertEqual(run(FakeTransport(state))[0], 2)
        state = fixture()
        named(state, d.ENGINE)['env_names'].append('SPRING_DATASOURCE_URL')
        self.assertEqual(run(FakeTransport(state))[0], 2)

    def test_duplicate_env_nul_control_and_pem_never_reflected(self):
        for value in ('GAME_DB_USER=duplicate', 'REDIS_HOST=bad\x00host', 'GAME_DB_PASSWORD=-----BEGIN PRIVATE KEY-----\nSECRET',
                      'SCENARIO_CODE=::error::\nSECRET', 'SCENARIO_CODE=' + CANARY):
            state = fixture()
            named(state, d.ENGINE)['env'].append(value)
            code, report = run(FakeTransport(state))
            if value.startswith('SCENARIO_CODE=') and '\n' not in value:
                # A non-connection selected setting still stays entirely private.
                self.assertNotIn(CANARY, json.dumps(report))
            else:
                self.assertEqual(code, 2)
                self.assertIn('ENV_INVALID', report['reasons'])

    def test_missing_and_different_revision(self):
        for value, reason in ((None, 'REVISION_UNAVAILABLE'), (CANARY, 'REVISION_UNAVAILABLE'), ('e' * 40, 'ROLE_REVISION_MISMATCH')):
            state = fixture()
            state['images'][named(state, d.ENGINE)['image']]['revision'] = value
            self.assertIn(reason, run(FakeTransport(state))[1]['reasons'])

    def test_canaries_in_all_metadata_output_positions(self):
        mutations = ('state', 'project', 'service', 'volume_key', 'digest_repo', 'scenario', 'max', 'block')
        for change in mutations:
            with self.subTest(change=change):
                state = fixture()
                rows = sql_rows()
                if change in ('state', 'project', 'service'):
                    named(state, d.ENGINE)[change] = CANARY
                elif change == 'volume_key':
                    state['volumes']['spep-game-pgdata']['key'] = CANARY
                elif change == 'digest_repo':
                    image = named(state, d.ENGINE)['image']
                    state['images'][image]['digests'] = [CANARY + '@' + image]
                else:
                    rows[0][change] = CANARY
                run(FakeTransport(state, rows=rows))

    def test_malformed_nested_inspect_is_unknown_and_never_reflected(self):
        for key, value in (('name', None), ('networks', [CANARY]), ('mounts', [CANARY]), ('env', CANARY)):
            with self.subTest(key=key):
                state = fixture()
                named(state, d.ENGINE)[key] = value
                self.assertEqual(run(FakeTransport(state))[0], 2)

    def test_connection_password_only_drift_is_detected_without_hash(self):
        before = fixture()
        after = copy.deepcopy(before)
        replace_env(named(after, d.ENGINE), 'GAME_DB_PASSWORD', 'different-secret')
        code, report = run(FakeTransport(before, after))
        self.assertEqual((code, report['result']), (4, 'IDENTITY_DRIFT'))
        self.assertIn('containers', report['drift'])
        self.assertNotIn('different-secret', json.dumps(report))

    def test_state_image_volume_consumer_and_mount_drift(self):
        for change in ('state', 'revision', 'volume', 'mount', 'consumer'):
            before = fixture()
            after = copy.deepcopy(before)
            if change == 'state':
                named(after, d.ENGINE)['state'] = 'exited'
            elif change == 'revision':
                after['images'][named(after, d.ENGINE)['image']]['revision'] = 'e' * 40
            elif change == 'volume':
                after['volumes']['spep-game-pgdata']['key'] = 'foreign'
            elif change == 'mount':
                named(after, d.GAME_PG)['mounts'][0]['RW'] = False
            else:
                foreign = copy.deepcopy(named(after, d.GAME_PG))
                foreign.update(id='e' * 64, name='/foreign', env=[])
                after['containers'][foreign['id']] = foreign
            self.assertEqual(run(FakeTransport(before, after))[0], 4, change)

    def test_sql_types_range_missing_and_mismatch(self):
        for value in ('50', True, 0, -1, None, {}, 2147483648, 1.5):
            rows = sql_rows(maximum=value)
            code, report = run(FakeTransport(rows=rows))
            self.assertEqual(code, 2, value)
            self.assertIn('SQL_VALUE_UNAVAILABLE', report['reasons'])
        for rows in ([], sql_rows()[0:1], sql_rows() + [sql_rows()[1]]):
            self.assertEqual(run(FakeTransport(rows=rows))[0], 2)
        rows = sql_rows()
        rows[2]['value'] = 51
        self.assertEqual(run(FakeTransport(rows=rows))[0], 3)

    def test_json_numeric_integer_decimal_and_bit1_semantics(self):
        fake = FakeTransport(rows=sql_rows(block=3))
        fake.sql = fake.sql.replace(b'50', b'50.0')
        code, report = run(fake)
        self.assertEqual(code, 0)
        self.assertEqual(report['world']['config']['maxgeneral']['value'], 50)
        self.assertTrue(report['world']['config']['block_general_create']['bit1_set'])
        self.assertEqual(run(FakeTransport(rows=sql_rows(block=2)))[0], 2)

    def test_sql_scenario_world_invalid_and_injection_no_leak(self):
        for key, value in (('scenario', CANARY), ('scenario', 'scenario_0007'), ('id', True), ('id', 8)):
            rows = sql_rows()
            rows[0][key] = value
            self.assertEqual(run(FakeTransport(rows=rows))[0], 2)
        rows = sql_rows(maximum=CANARY, block={'secret': CANARY})
        self.assertEqual(run(FakeTransport(rows=rows))[0], 2)

    def test_sql_row_and_line_caps_and_auth_failure(self):
        for raw in (b'{}\n' * 9, b'"' + b'x' * 4096 + b'"\n', b'{"kind":"world","id":7,"id":7}\n'):
            fake = FakeTransport()
            fake.sql = raw
            self.assertEqual(run(fake)[0], 2)
        fake = FakeTransport()
        original = fake.run
        def denied(args, stdin=None):
            if args[:2] == ('container', 'exec'):
                raise d.Fault('COMMAND_FAILED')
            return original(args, stdin)
        fake.run = denied
        self.assertEqual(run(fake)[0], 2)

    def test_inventory_cap_batches_and_invalid_inventory(self):
        state = fixture()
        for index in range(100, 165):
            state['containers'][f'{index:064x}'] = {'id': f'{index:064x}', 'name': '/foreign', 'state': 'exited',
                'image': 'sha256:' + 'e' * 64, 'mounts': [], 'networks': [], 'env': []}
        fake = FakeTransport(state)
        self.assertEqual(run(fake)[0], 0)
        batches = [len(args[4:]) for args, _ in fake.commands if args[:2] == ('container', 'inspect')]
        self.assertEqual(batches, [64, 9, 64, 9])
        for raw in (b'x\n', b'\n', (('a' * 64 + '\n') * 513).encode()):
            with self.assertRaises(d.Fault):
                d.ids(raw)

    def test_exact_512_inventory_and_cap_before_any_inspect(self):
        state = fixture()
        for index in range(100, 604):
            identifier = f'{index:064x}'
            state['containers'][identifier] = {'id': identifier, 'name': '/foreign' + str(index),
                'state': 'exited', 'mounts': [], 'networks': [], 'env': []}
        fake = FakeTransport(state)
        self.assertEqual(run(fake)[0], 0)
        self.assertEqual([len(args[4:]) for args, _ in fake.commands if args[:2] == ('container', 'inspect')], [64] * 16)
        identifier = 'e' * 64
        state['containers'][identifier] = {'id': identifier, 'name': '/foreignExtra', 'mounts': [], 'networks': []}
        fake = FakeTransport(state)
        self.assertEqual(run(fake)[1]['reasons'], ['INVENTORY_LIMIT'])
        self.assertEqual(len(fake.commands), 1)

    def test_cli_and_error_reflection(self):
        for argv in ([], ['reset'], ['observe', CANARY], ['--help']):
            self.assertEqual(run(FakeTransport(), argv)[0], 64)
        fake = FakeTransport()
        fake.fail = RuntimeError(CANARY)
        self.assertEqual(run(fake)[0], 1)
        fake.fail = d.Fault(CANARY)
        self.assertEqual(run(fake)[0], 1)

    def test_sql_and_subprocess_command_allowlist(self):
        for command in ('pull', 'run', 'start', 'stop', 'rm', 'prune', 'logs', 'cp', 'redis-cli'):
            self.assertFalse(d.command_allowed((command, CANARY), None, None))
        self.assertFalse(d.command_allowed(('container', 'inspect', '--format', '{{json .}}', 'a' * 64), None, None))
        self.assertFalse(d.command_allowed(('image', 'inspect', '--format', d.IMAGE_TEMPLATE, 'tag'), None, None))
        fake = FakeTransport()
        self.assertEqual(run(fake)[0], 0)
        for args, stdin in fake.commands:
            self.assertTrue(d.command_allowed(args, stdin, fake.pg_id))
        query = d.world_sql(7)
        for text in (b'BEGIN READ ONLY', b"statement_timeout='5s'", b"lock_timeout='1s'", b"idle_in_transaction_session_timeout='10s'", b'ROLLBACK'):
            self.assertIn(text, query)
        for forbidden in (b'users', b'general ', b'Flyway', b'SELECT *', b'UPDATE ', b'INSERT ', b'DELETE ', b'CREATE '):
            self.assertNotIn(forbidden, query)
        self.assertLess(len(query), 4096)
        self.assertIn('env -i ', d.PSQL_SCRIPT)
        self.assertIn('PGPASSFILE=/dev/null', d.PSQL_SCRIPT)
        self.assertIn('-w ', d.PSQL_SCRIPT)
        self.assertNotIn('POSTGRES_PASSWORD', d.PSQL_SCRIPT)
        exec_args = next(args for args, stdin in fake.commands if stdin)
        self.assertFalse(d.command_allowed(exec_args, query.replace(b'ROLLBACK;', b'DELETE FROM users;'), fake.pg_id))
        self.assertFalse(d.command_allowed(exec_args, query, 'e' * 64))


class StreamingTransportTests(unittest.TestCase):
    def spawn(self, code):
        original = subprocess.Popen
        def child(argv, **kwargs):
            self.assertEqual(argv[:2], list(d.DOCKER))
            self.assertIs(kwargs['shell'], False)
            self.assertEqual(set(kwargs['env']), {'PATH', 'LANG', 'LC_ALL'})
            return original([sys.executable, '-I', '-B', '-c', code], **kwargs)
        return patch.object(d.subprocess, 'Popen', side_effect=child)

    def test_stdout_and_stderr_are_stream_bounded(self):
        for pipe in ('stdout', 'stderr'):
            with self.subTest(pipe=pipe), self.spawn(f'import sys; sys.{pipe}.buffer.write(b"x" * 50000)'), patch.object(d, 'LIMIT', 4096):
                with self.assertRaises(d.Fault) as result:
                    d.Transport().run(('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}'))
                self.assertEqual(result.exception.reason, 'OUTPUT_LIMIT')
        with self.spawn('import sys; sys.stdout.buffer.write(b"a" * 3000); sys.stderr.buffer.write(b"b" * 3000)'), patch.object(d, 'LIMIT', 4096):
            with self.assertRaises(d.Fault) as result:
                d.Transport().run(('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}'))
            self.assertEqual(result.exception.reason, 'OUTPUT_LIMIT')

    def test_timeout_and_nonzero_stderr_not_reflected(self):
        with self.spawn('import time; time.sleep(1)'), patch.object(d, 'COMMAND_SECONDS', 0.05):
            with self.assertRaises(d.Fault) as result:
                d.Transport().run(('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}'))
            self.assertEqual(result.exception.reason, 'COMMAND_TIMEOUT')
        with self.spawn('import sys; sys.stderr.write(' + repr(CANARY) + '); sys.exit(1)'):
            with self.assertRaises(d.Fault) as result:
                d.Transport().run(('container', 'ls', '--all', '--no-trunc', '--format', '{{.ID}}'))
            self.assertEqual(result.exception.reason, 'COMMAND_FAILED')
            self.assertNotIn(CANARY, str(result.exception))

    def test_cli_budget_and_missing_docker(self):
        with patch.object(d.subprocess, 'Popen', side_effect=PermissionError(CANARY)):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(d.main(['observe']), 2)
            self.assertNotIn(CANARY, output.getvalue())
        with patch.object(d.Transport, 'run', side_effect=d.Fault('OBSERVATION_TIMEOUT')):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(d.main(['observe']), 2)
            self.assertIn('OBSERVATION_TIMEOUT', output.getvalue())

    def test_global_alarm_includes_non_command_work(self):
        with patch.object(d, 'TOOL_SECONDS', 1), patch.object(d, 'observe', side_effect=lambda _transport: time.sleep(2)):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(d.main(['observe']), 2)
            self.assertIn('OBSERVATION_TIMEOUT', output.getvalue())

    def test_psql_uses_separate_deadline(self):
        transport = d.Transport()
        transport.authorize_sql('a' * 64)
        args = ('container', 'exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                'a' * 64, '/bin/sh', '-c', d.PSQL_SCRIPT)
        with self.spawn('import sys,time; sys.stdin.buffer.read(); time.sleep(1)'), patch.object(d, 'SQL_SECONDS', 0.05):
            with self.assertRaises(d.Fault) as result:
                transport.run(args, d.world_sql(7))
            self.assertEqual(result.exception.reason, 'COMMAND_TIMEOUT')


class TemplateProjectionTests(unittest.TestCase):
    """Execute the actual Go template before feeding its bytes to snapshot()."""

    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='pep-diagnostic-template-')
        cls.addClassCleanup(cls.directory.cleanup)
        root = Path(cls.directory.name)
        go = os.environ.get('PEP_DIAGNOSTICS_GO') or shutil.which('go')
        if not go:
            raise RuntimeError('Go compiler required for template regression; not a skipped PASS')
        source = root / 'projection.go'
        source.write_text('''package main
import ("bytes"; "encoding/json"; "os"; "strings"; "text/template")
func main() {
    var input struct { Template string; Containers []map[string]any }
    if json.NewDecoder(os.Stdin).Decode(&input) != nil { os.Exit(1) }
    functions := template.FuncMap{"split": strings.Split, "json": func(value any) string {
        raw, err := json.Marshal(value); if err != nil { os.Exit(1) }; return string(raw)
    }}
    projected, err := template.New("container").Funcs(functions).Parse(input.Template)
    if err != nil { os.Exit(1) }
    for _, container := range input.Containers {
        var buffer bytes.Buffer
        if projected.Execute(&buffer, container) != nil { os.Exit(1) }
        var object map[string]any
        if json.Unmarshal(buffer.Bytes(), &object) != nil { os.Exit(1) }
        os.Stdout.Write(buffer.Bytes()); os.Stdout.Write([]byte("\\n"))
    }
}
''')
        cls.renderer = root / 'projection'
        build = subprocess.run([go, 'build', '-o', str(cls.renderer), str(source)], shell=False,
            env={'PATH': '/usr/bin:/bin', 'GOCACHE': str(root / 'cache'), 'GOPROXY': 'off', 'GOTOOLCHAIN': 'local'},
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        if build.returncode:
            raise RuntimeError('Go template regression build failed; raw compiler output suppressed')

    def projected_transport(self, override=None, mode='PUBLIC'):
        renderer = self.renderer
        state = fixture(mode)

        class ProjectedTransport(FakeTransport):
            def run(self, args, stdin=None):
                if args[:2] != ('container', 'inspect'):
                    return super().run(args, stdin)
                if not d.command_allowed(tuple(args), stdin, self.pg_id):
                    raise AssertionError('Unexpected projection command')
                self.commands.append((tuple(args), stdin))
                containers = []
                for identifier in args[4:]:
                    item = self.active['containers'][identifier]
                    environment = [part for part in item['env'] if part is not None]
                    environment.append('JWT_PRIVATE_KEY=' + CANARY)
                    if item['name'] == '/' + d.ENGINE and override:
                        environment.append(override)
                    containers.append({
                        'Id': item['id'], 'Name': item['name'], 'State': {'Status': item['state']}, 'Image': item['image'],
                        'Config': {'Labels': {'com.docker.compose.project': item['project'],
                                             'com.docker.compose.service': item['service']},
                                   'Env': environment, 'Entrypoint': item['entrypoint'], 'Cmd': item['cmd']},
                        'Mounts': item['mounts'], 'NetworkSettings': {'Networks': {
                            str(index): {'NetworkID': network['id'], 'Aliases': network['aliases']}
                            for index, network in enumerate(item['networks']) if network is not None}},
                        'HostConfig': {'ExtraHosts': item['extra_hosts'], 'Links': item['links']},
                    })
                result = subprocess.run([str(renderer)], shell=False,
                    input=json.dumps({'Template': d.CONTAINER_TEMPLATE, 'Containers': containers}).encode(),
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                if result.returncode:
                    raise AssertionError('Template projection failed; raw output suppressed')
                return result.stdout

        return ProjectedTransport(state)

    def test_actual_template_positive_public_private(self):
        for mode in ('PUBLIC', 'PRIVATE'):
            with self.subTest(mode=mode):
                self.assertEqual(run(self.projected_transport(mode=mode))[0], 0)

    def test_actual_template_rejects_reviewed_jvm_and_dotted_spring_overrides(self):
        for key in ('_JAVA_OPTIONS', 'spring.datasource.url'):
            with self.subTest(key=key):
                value = '-Dspring.datasource.url=jdbc:postgresql://foreign:5432/private-' + CANARY
                transport = self.projected_transport(key + '=' + value)
                code, report = run(transport)
                self.assertEqual(code, 2)
                self.assertIn('CONNECTION_OVERRIDE_UNVERIFIED', report['reasons'])
                self.assertIsNone(transport.pg_id)
                self.assertFalse(any(args[:2] == ('container', 'exec') for args, _ in transport.commands))
                public = json.dumps(report)
                self.assertNotIn(key, public)
                self.assertNotIn(value, public)


class WorkflowTests(unittest.TestCase):
    def test_fixed_dispatch_checkout_permissions_runner_and_no_mutation(self):
        source = (ROOT / '.github/workflows/pep-target-diagnostics.yml').read_text()
        self.assertIn('workflow_dispatch:\n', source)
        self.assertNotIn('inputs:', source)
        self.assertIn("github.repository == 'peppone-choi/opensamguk'", source)
        self.assertIn("github.ref == 'refs/heads/main'", source)
        self.assertIn("github.event_name == 'workflow_dispatch'", source)
        self.assertIn('runs-on: [self-hosted, Linux, X64, gcp-prod-opensamguk]', source)
        self.assertIn('timeout-minutes: 10', source)
        self.assertIn('contents: read', source)
        self.assertIn('ref: ${{ github.sha }}', source)
        self.assertIn('persist-credentials: false', source)
        self.assertIn('cancel-in-progress: false', source)
        self.assertIn('group: pep-target-diagnostics', source)
        self.assertIn('sudo -n python3 -I -B', source)
        for text in ('secrets.', 'secrets: inherit', 'packages: write', 'actions: write', 'pep_loop.py', 'docker ', 'sudo -S', 'workflow_call:', 'push:', 'pull_request:'):
            self.assertNotIn(text, source)
        self.assertNotIn('{{json .Config.Env}}', d.CONTAINER_TEMPLATE)
        self.assertNotIn('{{json .Config.Labels}}', d.CONTAINER_TEMPLATE)
        self.assertNotIn('{{json .}}', d.CONTAINER_TEMPLATE)
        self.assertNotIn('POSTGRES_PASSWORD', d.CONTAINER_TEMPLATE)
        self.assertNotIn('JWT_', d.CONTAINER_TEMPLATE)


if __name__ == '__main__':
    unittest.main()
