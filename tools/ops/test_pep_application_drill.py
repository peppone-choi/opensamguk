#!/usr/bin/env python3
"""Behavior tests; the Docker process boundary is the only synthetic dependency."""
import copy
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import tarfile
import unittest

import game_server_recovery as base

MODULE = Path(__file__).with_name('pep_application_drill.py')
if MODULE.exists():
    import pep_application_drill as app
else:
    app = None


def status():
    return dict(profile='fixture', state='paused', running=False, paused=True, loopAlive=True,
                statusLabel='동결중', serviceMaterialized=True, autoStartEnabled=True,
                recoveryMode='READY', recoveryReady=True, recoveryReason=None,
                consecutiveFailures=0, failedTicks=0, successfulTicks=0,
                lastTickStartedAt=None, lastTickCompletedAt=None, lastTickFailedAt=None,
                lastTickError=None, lastSuccessfulTickAgeSeconds=None, clockError=None,
                loopUptimeSeconds=0, clock=dict(currentYear=180, currentMonth=1,
                currentPhase=1, currentPhaseText='상순', tickSeconds=60,
                lastTurnTime='2026-01-01T00:00:00Z', nextRunTime='2026-01-01T00:01:00Z'))


class BoundaryDocker:
    def __init__(self):
        self.calls, self.objects, self.rows, self.queries = [], {}, [], []
        self.status = status()
        self.foreign = False
        self.world = dict(current_year=180, current_month=1, current_phase=1,
                          tick_seconds=60, meta={'lastTurnTime': '2026-01-01T00:00:00+00:00'},
                          start_time=None)

    def run(self, args, *, stdin=None, stdout=None):
        self.calls.append(list(args))
        kind, verb = args[:2]
        if verb == 'ls':
            return ('\n'.join(name for (k, name) in self.objects if k == kind)).encode()
        if verb == 'inspect':
            if kind == 'image':
                return json.dumps([{'Id': args[2]}]).encode()
            if (kind, args[2]) not in self.objects:
                raise base.RecoveryError('missing test object')
            obj = copy.deepcopy(self.objects[kind, args[2]])
            if self.foreign and kind == 'container':
                obj['Id'] = 'foreign'
            return json.dumps([obj]).encode()
        if verb == 'create':
            name = args[args.index('--name') + 1] if kind == 'container' else args[-1]
            labels = dict(args[i + 1].split('=', 1) for i, a in enumerate(args) if a == '--label')
            obj = dict(Name='/' + name if kind == 'container' else name,
                       Id='id-' + name, Labels=labels, Config={'Labels': labels},
                       Driver='local', Options=None, CreatedAt='fixed', Mountpoint='/owned/' + name,
                       Internal=True)
            self.objects[kind, name] = obj
            return (obj['Id'] if kind != 'volume' else name).encode()
        if verb == 'rm':
            del self.objects[kind, args[-1]]
            return b''
        if verb == 'exec':
            if 'psql' in args:
                query = stdin.getvalue().decode()
                self.queries.append(query)
                if 'clock-source' in query:
                    return json.dumps([self.world]).encode()
                if 'plock-read' in query:
                    return json.dumps(self.rows).encode()
                if 'plock-write' in query:
                    if self.rows:
                        self.rows[-1]['value'] = 1
                    else:
                        self.rows.append(dict(id=90, namespace='game_env', value=1))
                    return b'1'
            if 'redis-cli' in args:
                return b'PONG' if args[-1] == 'PING' else b'0'
            if 'wget' in args:
                return json.dumps(self.status).encode()
        return b''


class ApplicationDrillTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(app, 'Task 3 application drill implementation is missing')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.bundle = self.root / 'fixture-abcdefgh'
        self.bundle.mkdir(mode=0o700)
        self.tree = self.root / (self.bundle.name + '.scenario') / 'tree'
        self.tree.mkdir(mode=0o700, parents=True)
        os.chmod(self.tree.parent, 0o700)
        base.write_private(self.tree / 'data.json', b'{}')
        mount = {'Type': 'bind', 'Source': '/live/scenarios', 'Destination': '/data/scenarios', 'RW': False}
        self.inspections = {}
        self.manifest = {'containers': {}}
        for i, service in enumerate(['game-postgres', 'game-redis', 'game-engine', 'game-api']):
            env = ['OPENSAMGUK_WORLD_ID=7', 'TURN_PROFILE_NAME=fixture',
                   'GAME_DATABASE_URL=jdbc:postgresql://source:5432/sammo',
                   'GAME_DB_USER=sammo', 'GAME_DB_PASSWORD=secret-never-emit', 'SCENARIO_DIR=']
            obj = dict(Id='source-' + service, Image='sha256:' + str(i + 1) * 64,
                       Config={'Env': env}, HostConfig={'Memory': (i + 1) * 1024},
                       Mounts=[mount] if service in ('game-engine', 'game-api') else [])
            self.inspections[service] = obj
            self.manifest['containers'][service] = dict(id=obj['Id'], image_id=obj['Image'], mounts=obj['Mounts'])
        self.env = dict(OPENSAMGUK_WORLD_ID='7', GAME_POSTGRES_USER='sammo', GAME_POSTGRES_DB='sammo')
        base.write_private(self.bundle / 'manifest.json', base.json_bytes(self.manifest))
        base.write_private(self.bundle / 'postgres.tar', b'postgres fixture archive')
        self.pg = dict(counts={'world_state': 1, 'city': 2, 'nation': 1, 'general': 3}, versions=['1'],
                       migration_success=True, selected_world_matches=True, city_min=1, city_max=2,
                       logical_dump_sha256='a' * 64)
        self.report = dict(version=1, server='fixture', manifest_sha256=base.digest(self.bundle / 'manifest.json')['sha256'],
                           started_at='start', finished_at='end', success=True,
                           application_boot_verified=False, authenticated_smoke_verified=False,
                           cleanup={'success': True, 'remaining_resources': []}, postgres=self.pg,
                           redis={'pong': True, 'loading': False, 'appendonly': True, 'persistence_ok': True, 'key_count': 1})
        self.write_report()
        self.scenario = app.ScenarioTreeDigest.capture(self.tree, self.report['manifest_sha256'])
        base.write_private(self.tree.parent / 'manifest.json', base.json_bytes(self.scenario.manifest()))
        self.docker = BoundaryDocker()
        self.recovery = base.Recovery(self.docker, lock_path=self.root / 'lock', sleep=lambda _: None)
        # Base strict validator has its own tests. Keep this consumer's correlation and post-clone checks real.
        self.validated = []
        def validate(server, bundle):
            self.validated.append((server, bundle))
            return copy.deepcopy(self.manifest), self.env
        self.recovery.validate_bundle = validate
        self.recovery.postgres_check = lambda *_: copy.deepcopy(self.pg)
        self.drill = app.PepApplicationDrill(token_factory=lambda: 'abcdefgh', attempts=2)

    def write_report(self):
        base.write_private(self.bundle / 'verification.json', base.json_bytes(self.report), replace=True)

    def prove(self):
        return self.drill.prove(self.recovery, self.bundle,
            app.SourceEngineInputs.from_inspections('fixture', self.inspections), self.tree, self.scenario)

    def test_internal_clone_preserves_env_memory_and_scenario_bind(self):
        proof = self.prove()
        self.assertTrue(proof.cleanup['success'])
        self.assertEqual(self.validated, [('fixture', self.bundle)])
        creates = [a for a in self.docker.calls if a[:2] == ['container', 'create']]
        services = creates[-3:]
        for args, memory in zip(services, [1024, 2048, 3072]):
            self.assertEqual(args[args.index('--memory') + 1], str(memory))
            self.assertNotIn('-p', args)
            self.assertNotIn('--publish', args)
            self.assertNotIn('opensamguk-net', args)
        engine = services[-1]
        values = dict(engine[i + 1].split('=', 1) for i, a in enumerate(engine) if a == '-e')
        self.assertEqual(values['SCENARIO_DIR'], '')
        self.assertEqual(values['GAME_DB_PASSWORD'], 'secret-never-emit')
        self.assertEqual(values['SCENARIO_SEED_ENABLED'], 'false')
        self.assertEqual(values['OPENSAMGUK_DAEMON_ENABLED'], 'true')
        self.assertIn(f'type=bind,source={self.tree},target=/data/scenarios,readonly', engine)
        self.assertNotIn('secret-never-emit', repr(proof))
        self.assertNotIn('secret-never-emit', repr(app.SourceEngineInputs.from_inspections('fixture', self.inspections)))
        self.assertFalse(self.docker.objects)
        self.assertTrue(any(a[:2] == ['network', 'create'] and '--internal' in a for a in self.docker.calls))
        self.assertFalse(any('redis.tar' in str(a) for a in self.docker.calls))
        request = next(a for a in self.docker.calls if 'wget' in a)
        self.assertEqual(request[request.index('-T') + 1] if '-T' in request else None, '2')
        self.assertEqual(request[request.index('-t') + 1] if '-t' in request else None, '1')

    def test_plock_zero_one_and_last_id_empty_namespace_winner(self):
        for rows in [[], [dict(id=3, namespace='game_env', value=0)],
                     [dict(id=3, namespace='game_env', value=0), dict(id=8, namespace='', value=0)]]:
            with self.subTest(rows=rows):
                self.docker.rows = copy.deepcopy(rows)
                proof = self.prove()
                self.assertEqual(self.docker.rows[-1]['value'], 1)
                self.assertEqual(proof.plock_delta['updated_rows'], int(bool(rows)))
                self.assertEqual(proof.plock_delta['inserted_rows'], int(not rows))
                if len(rows) == 2:
                    self.assertEqual(self.docker.rows[0]['value'], 0)

    def test_strict_status_rejects_every_missing_or_bad_required_field(self):
        for key, value in status().items():
            with self.subTest(key=key):
                bad = status()
                del bad[key]
                self.assertFalse(app.status_matches(bad, 'fixture', status()['clock']))
        for key, value in [('running', True), ('successfulTicks', 1), ('loopUptimeSeconds', True),
                           ('recoveryMode', 'BLOCKED'), ('clockError', 'private')]:
            bad = status(); bad[key] = value
            self.assertFalse(app.status_matches(bad, 'fixture', status()['clock']))

    def test_equivalent_iso_instants_match(self):
        value = status(); value['clock']['lastTurnTime'] = '2026-01-01T09:00:00+09:00'
        self.assertTrue(app.status_matches(value, 'fixture', status()['clock']))

    def test_scenario_digest_drift_prevents_engine_create(self):
        (self.tree / 'data.json').write_bytes(b'changed')
        with self.assertRaisesRegex(base.RecoveryError, 'effective scenario input changed'):
            self.prove()
        self.assertFalse(any('game-engine' in a for a in self.docker.calls))
        self.assertFalse(self.docker.objects)

    def test_source_identity_memory_world_and_bind_refusals_precede_allocation(self):
        for field, value in [('Id', 'wrong'), ('Image', 'sha256:' + 'f' * 64),
                             ('HostConfig', {'Memory': 0}), ('HostConfig', {'Memory': True}),
                             ('Mounts', []), ('Mounts', [{'Type': 'bind', 'Destination': '/data/scenarios', 'RW': True}])]:
            original = copy.deepcopy(self.inspections['game-engine'])
            self.inspections['game-engine'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(base.RecoveryError):
                self.prove()
            self.inspections['game-engine'] = original
        self.assertFalse(any(a[1] == 'create' for a in self.docker.calls))

    def test_bundle_report_companion_correlations_fail_closed(self):
        for key, value in [('server', 'other'), ('success', False), ('manifest_sha256', 'f' * 64),
                           ('cleanup', {'success': False, 'remaining_resources': ['owned']})]:
            original = copy.deepcopy(self.report)
            self.report[key] = value; self.write_report()
            with self.subTest(key=key), self.assertRaises(base.RecoveryError):
                self.prove()
            self.report = original; self.write_report()
        wrong = self.scenario.manifest(); wrong['bundle_manifest_sha256'] = 'f' * 64
        base.write_private(self.tree.parent / 'manifest.json', base.json_bytes(wrong), replace=True)
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.docker.objects)

    def test_count_or_hash_mismatch_blocks_engine(self):
        self.recovery.postgres_check = lambda *_: dict(self.pg, logical_dump_sha256='b' * 64)
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(any('wget' in a for a in self.docker.calls))
        self.assertFalse(self.docker.objects)

    def test_collision_never_removes_foreign_resource(self):
        name = 'pep-drill-abcdefgh-network'
        self.docker.objects['network', name] = {'Name': name, 'Id': 'foreign'}
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertIn(('network', name), self.docker.objects)

    def test_cleanup_identity_mismatch_retains_resources(self):
        original = self.recovery.postgres_check
        def foreign(*args):
            self.docker.foreign = True
            return original(*args)
        self.recovery.postgres_check = foreign
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.drill.last_cleanup['success'])
        self.assertTrue(self.drill.last_cleanup['remaining_resources'])
        self.assertFalse(any(a[:2] == ['container', 'rm'] for a in self.docker.calls))

    def test_tree_rejects_symlinks_hardlinks_and_non_regular_files(self):
        for kind in ['symlink', 'hardlink', 'fifo']:
            path = self.tree / 'bad'
            if kind == 'symlink': path.symlink_to(self.tree / 'data.json')
            elif kind == 'hardlink': os.link(self.tree / 'data.json', path)
            else: os.mkfifo(path, 0o600)
            with self.subTest(kind=kind), self.assertRaises(base.RecoveryError):
                app.ScenarioTreeDigest.capture(self.tree, 'a' * 64)
            path.unlink()

    def test_missing_and_external_scenario_lookup_are_preserved(self):
        original = self.inspections['game-engine']['Config']['Env']
        for lookup in [None, '/outside/archive/lookup']:
            self.inspections['game-engine']['Config']['Env'] = [s for s in original if not s.startswith('SCENARIO_DIR=')]
            if lookup is not None:
                self.inspections['game-engine']['Config']['Env'].append('SCENARIO_DIR=' + lookup)
            self.prove()
            args = [a for a in self.docker.calls if a[:2] == ['container', 'create']][-1]
            env = dict(args[i + 1].split('=', 1) for i, a in enumerate(args) if a == '-e')
            self.assertEqual(env.get('SCENARIO_DIR'), lookup)

    def test_clock_refuses_nondeterministic_or_invalid_interval(self):
        for key, value in [('tick_seconds', 0), ('tick_seconds', True), ('meta', {})]:
            world = copy.deepcopy(self.docker.world); world[key] = value
            with self.subTest(key=key), self.assertRaises(base.RecoveryError):
                app.expected_clock([world])

    def test_api_source_identity_must_correlate(self):
        self.inspections['game-api']['Id'] = 'wrong-api'
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.docker.objects)

    def test_duplicate_env_and_wrong_world_are_refused(self):
        original = self.inspections['game-engine']['Config']['Env'][:]
        self.inspections['game-engine']['Config']['Env'].append('GAME_DB_PASSWORD=other')
        with self.assertRaises(base.RecoveryError): self.prove()
        self.inspections['game-engine']['Config']['Env'] = [s.replace('OPENSAMGUK_WORLD_ID=7', 'OPENSAMGUK_WORLD_ID=8') for s in original]
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.docker.objects)

    def test_create_ownership_failure_records_unremoved_resource(self):
        run = self.docker.run
        def mismatch(args, **kw):
            result = run(args, **kw)
            if args[:2] == ['network', 'create']:
                self.docker.objects['network', args[-1]]['Id'] = 'foreign'
            return result
        self.docker.run = mismatch
        with self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.drill.last_cleanup['success'])
        self.assertEqual(self.drill.last_cleanup['remaining_resources'][0]['id'], 'id-pep-drill-abcdefgh-network')

    def test_already_paused_winner_has_zero_row_delta_and_ordered_replay(self):
        self.docker.rows = [dict(id=3, namespace='game_env', value=0), dict(id=8, namespace='', value=1)]
        proof = self.prove()
        self.assertEqual(proof.plock_delta['updated_rows'], 0)
        self.assertEqual(proof.plock_delta['inserted_rows'], 0)
        reads = [q for q in self.docker.queries if 'plock-read' in q]
        self.assertEqual(len(reads), 2)
        for query in reads:
            self.assertIn('ORDER BY id ASC', query)
            self.assertIn('world_id = 7', query)
            self.assertIn('"table" = \'game_env\'', query)
            self.assertIn("namespace IN ('', 'game_env')", query)
            self.assertIn("key = 'plock'", query)
        self.assertFalse(any('plock-write' in q for q in self.docker.queries))

    def test_only_last_id_loader_winner_is_update_target(self):
        self.docker.rows = [dict(id=3, namespace='game_env', value=0), dict(id=8, namespace='', value=0)]
        self.prove()
        writes = [q for q in self.docker.queries if 'plock-write' in q]
        self.assertEqual(len(writes), 1)
        self.assertIn('AND id = 8 RETURNING id', writes[0])
        self.assertIn('world_id = 7', writes[0])

    def test_manifest_entry_boolean_size_is_not_an_integer(self):
        wrong = self.scenario.manifest(); wrong['entries'][0]['size'] = True
        # Match a genuine size-one file to expose Python bool/int equality.
        (self.tree / 'data.json').write_bytes(b'x')
        self.scenario = app.ScenarioTreeDigest.capture(self.tree, self.report['manifest_sha256'])
        wrong = self.scenario.manifest(); wrong['entries'][0]['size'] = True
        base.write_private(self.tree.parent / 'manifest.json', base.json_bytes(wrong), replace=True)
        with self.assertRaises(base.RecoveryError): self.prove()

    def test_missing_image_load_excludes_present_images_and_tags(self):
        configs = [b'{"fixture":1}', b'{"fixture":2}']
        identities = ['sha256:' + hashlib.sha256(c).hexdigest() for c in configs]
        records = [dict(Config=identity[7:] + '.json', RepoTags=['source:tag' + str(i)],
                        Layers=['layer' + str(i) + '.tar']) for i, identity in enumerate(identities)]
        with tarfile.open(self.bundle / 'images.tar', 'w') as archive:
            entries = {'manifest.json': json.dumps(records).encode()}
            for i, record in enumerate(records):
                entries[record['Config']] = configs[i]; entries[record['Layers'][0]] = b'layer'
            for name, payload in entries.items():
                info = tarfile.TarInfo(name); info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
        loaded = []
        def run(args, *, stdin=None, stdout=None):
            self.assertEqual(args, ['image', 'load'])
            with tarfile.open(fileobj=stdin, mode='r:') as archive:
                metadata = json.load(archive.extractfile('manifest.json'))
                loaded.append((archive.getnames(), metadata))
            return b''
        self.docker.run = run
        self.assertTrue(callable(getattr(self.drill, '_load_missing_images', None)), 'missing selective archived-image loader')
        self.drill._load_missing_images(self.recovery, self.bundle, [identities[1]])
        names, records_loaded = loaded[0]
        self.assertNotIn(records[0]['Config'], names)
        self.assertNotIn('layer0.tar', names)
        self.assertEqual(records_loaded, [dict(records[1], RepoTags=[])])

    def test_fresh_redis_directory_and_dbsize_fence_precedes_engine_creation(self):
        self.prove()
        checks = [i for i, a in enumerate(self.docker.calls) if a[:2] == ['container', 'exec'] and
                  a[-1] == 'test -z "$(ls -A /data)"']
        self.assertEqual(len(checks), 1)
        engine = next(i for i, a in enumerate(self.docker.calls) if a[:2] == ['container', 'create'] and
                      'pep-drill-abcdefgh-game-engine' in a)
        dbsize = next(i for i, a in enumerate(self.docker.calls) if a[-1] == 'DBSIZE')
        self.assertLess(checks[0], engine)
        self.assertLess(dbsize, engine)

    def test_verification_report_requires_complete_typed_success_schema(self):
        original = copy.deepcopy(self.report)
        variants = [dict(original, unknown=True), {k:v for k,v in original.items() if k != 'redis'},
                    dict(original, cleanup={'success': 1, 'remaining_resources': []}),
                    dict(original, postgres=dict(self.pg, city_min=True))]
        for report in variants:
            self.report = report; self.write_report()
            with self.subTest(report_keys=sorted(report)), self.assertRaises(base.RecoveryError): self.prove()
        self.assertFalse(self.docker.objects)


if __name__ == '__main__':
    unittest.main(verbosity=2)
