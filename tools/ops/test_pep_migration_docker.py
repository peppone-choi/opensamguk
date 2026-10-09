"""Opt-in synthetic storage and actual application cold-copy/rollback proofs."""
import base64
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid

from game_server_recovery import (Docker, Recovery, REDIS_CMD, RecoveryError,
                                 digest, require, write_private)
from pep_migration_clone import StorageClone
from pep_application_drill import SourceEngineInputs, status_matches
from pep_cold_capture_operator import preserve_scenario_tree
from pep_migration import PreservingMigration
from pep_migration_clone import application_clock, redis_fingerprint


SQL = """
CREATE TABLE world_state(id bigint PRIMARY KEY, current_year int, current_month int,
 current_phase int,tick_seconds int,meta jsonb,start_time timestamptz);
INSERT INTO world_state VALUES(7,184,1,1,3600,'{}','2026-01-01T00:00:00Z');
CREATE TABLE city(id int PRIMARY KEY,world_id bigint,name text,gold int,rice int);
INSERT INTO city VALUES(1,7,'synthetic city',42,93);
CREATE TABLE nation(world_id bigint,id int,name text,PRIMARY KEY(world_id,id));
INSERT INTO nation VALUES(7,1,'synthetic nation');
CREATE TABLE general(world_id bigint,id int,name text,nation_id int,gold int,rice int,
 PRIMARY KEY(world_id,id));
INSERT INTO general VALUES(7,1,'synthetic general',1,421,931);
CREATE TABLE troop(id bigserial PRIMARY KEY,world_id bigint,nation int,leader int,soldiers int,
 CONSTRAINT troop_world_nation_fkey FOREIGN KEY(world_id,nation) REFERENCES nation(world_id,id)
 DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT troop_world_leader_fkey FOREIGN KEY(world_id,leader) REFERENCES general(world_id,id));
INSERT INTO troop(world_id,nation,leader,soldiers) VALUES(7,1,1,1200);
CREATE TABLE command_inbox(id bigserial PRIMARY KEY,world_id bigint,status text,request_identity text);
INSERT INTO command_inbox(world_id,status,request_identity) VALUES(7,'DONE','synthetic-receipt');
CREATE TABLE flyway_schema_history(installed_rank int PRIMARY KEY,version text,success bool);
INSERT INTO flyway_schema_history VALUES(1,'77',true);
"""


@unittest.skipUnless(os.environ.get('RUN_PEP_MIGRATION_DOCKER_TESTS') == '1', 'explicit native Docker opt-in required')
class NativeMigrationTests(unittest.TestCase):
    def test_full_cold_pg_redis_copy_v78_preserves_old_rows_and_backup_rolls_back(self):
        token = uuid.uuid4().hex[:12]
        server = 'fixture' + token
        project = 'opensamguk-s' + server
        label = 'org.opensamguk.migration.fixture=' + token
        docker = Docker()
        owned = []
        with tempfile.TemporaryDirectory(prefix='pep-migration-fixture-') as temporary:
            root = Path(temporary)
            root.chmod(0o700)
            recovery = Recovery(docker, lock_path=root / 'production.lock')
            def create(kind, name, args):
                recovery.refuse_collisions([name])
                raw = docker.run([kind, 'create', *(['--name', name] if kind == 'container' else []),
                                  '--label', label, *args, *([] if kind == 'container' else [name])])
                obj = recovery.inspect(kind, name)
                identity = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                require(identity and (kind == 'volume' or raw.decode().strip() == identity), 'fixture create identity mismatch')
                owned.append((kind, name, identity))
                return name
            try:
                pgimage = recovery.inspect('image', 'postgres:16-alpine')['Id']
                redisimage = recovery.inspect('image', 'redis:7-alpine')['Id']
                placeholderimage = recovery.inspect('image', 'eclipse-temurin:21-jdk')['Id']
                network = create('network', 'pep-native-' + token, ['--internal'])
                stack = root / 'stack'
                (stack / 'servers').mkdir(parents=True, mode=0o700)
                tree = stack / 'data/scenarios'
                tree.mkdir(parents=True, mode=0o700)
                write_private(tree / 'scenario_fixture.json', b'{"synthetic":true}\n')
                write_private(stack / f'servers/s{server}.env',
                    f'SERVER_ID={server}\nOPENSAMGUK_WORLD_ID=7\nGAME_POSTGRES_USER=fixture\nGAME_POSTGRES_DB=fixture\n'.encode())
                write_private(stack / 'docker-compose.server.yml', b'# native synthetic fixture\n')
                for suffix in ('game-pgdata', 'game-redisdata'):
                    create('volume', f's{server}-{suffix}',
                        ['--label', 'com.docker.compose.project=' + project,
                         '--label', 'com.docker.compose.volume=' + suffix])
                def container(service, image, args):
                    return create('container', f's{server}-{service}',
                        ['--network', network, '--pull=never', '--log-driver', 'none',
                         '--label', 'com.docker.compose.project=' + project,
                         '--label', 'com.docker.compose.service=' + service, *args, image])
                pg = container('game-postgres', pgimage,
                    ['--memory', str(384 * 1024**2), '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_DB=fixture',
                     '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '--mount',
                     f'type=volume,source=s{server}-game-pgdata,target=/var/lib/postgresql/data'])
                redis = create('container', f's{server}-game-redis',
                    ['--network', network, '--pull=never', '--log-driver', 'none', '--memory', str(128 * 1024**2),
                     '--label', 'com.docker.compose.project=' + project,
                     '--label', 'com.docker.compose.service=game-redis', '--mount',
                     f'type=volume,source=s{server}-game-redisdata,target=/data', redisimage, *REDIS_CMD])
                for name in (pg, redis): docker.run(['container', 'start', name])
                # The image's initdb helper uses a temporary socket-only server. Wait for final TCP readiness.
                recovery.wait_ready(['container', 'exec', pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'fixture', '-d', 'fixture'])
                docker.run(['container', 'exec', '-i', pg, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'fixture', '-d', 'fixture'],
                           stdin=io.BytesIO(SQL.encode()))
                recovery.wait_ready(['container', 'exec', redis, 'redis-cli', 'PING'])
                for command in [('SET', 'sentinel', 'synthetic-value'), ('LPUSH', 'list', 'a', 'b'),
                                ('SADD', 'set', 'a', 'b'), ('ZADD', 'zset', '1', 'a'),
                                ('HSET', 'hash', 'field', 'value'), ('XADD', 'stream', '1-0', 'field', 'value'),
                                ('XGROUP', 'CREATE', 'stream', 'group', '0')]:
                    docker.run(['container', 'exec', redis, 'redis-cli', *command])
                expiry = int(time.time() * 1000) + 3600000
                docker.run(['container', 'exec', redis, 'redis-cli', 'SET', 'expiry-sentinel', 'same-value', 'PXAT', str(expiry)])
                docker.run(['container', 'exec', '-i', redis, 'redis-cli', '-n', '15', '-x', 'SET', 'binary-sentinel'],
                           stdin=io.BytesIO(b'\0synthetic\r\n"\\value'))
                docker.run(['container', 'exec', redis, 'redis-cli', 'XGROUP', 'CREATE', 'empty-stream', 'idle-group', '$', 'MKSTREAM'])
                docker.run(['container', 'exec', redis, 'redis-cli', 'XREADGROUP', 'GROUP', 'idle-group', 'runtime-reader',
                            'STREAMS', 'empty-stream', '>'])
                self.assertIn(b'runtime-reader', docker.run(['container', 'exec', redis, 'redis-cli', 'XINFO', 'CONSUMERS', 'empty-stream', 'idle-group']))
                for service in ('game-api', 'game-engine', 'web-game'):
                    args = ['--entrypoint', 'true', '-e', 'OPENSAMGUK_WORLD_ID=7']
                    if service != 'web-game': args += ['--mount', f'type=bind,source={tree},target=/data/scenarios,readonly']
                    placeholder = container(service, placeholderimage, args)
                    docker.run(['container', 'start', '--attach', placeholder])
                source_env = {'OPENSAMGUK_WORLD_ID': '7', 'GAME_POSTGRES_USER': 'fixture', 'GAME_POSTGRES_DB': 'fixture'}
                before_pg = recovery.postgres_check(pg, source_env, socket='/var/run/postgresql')
                before_redis = recovery.redis_check(redis, socket=None)
                before_redis_hash = redis_fingerprint(recovery, redis)
                for name in (redis, pg): docker.run(['container', 'stop', '--time', '120', name])
                with recovery.locked():
                    bundle = recovery.capture(server=server, confirm='BACKUP ' + server, stack_dir=stack, backup_root=root)
                    proof = recovery.verify(server=server, confirm='VERIFY ' + server, bundle=bundle)
                    self.assertEqual(proof['postgres'], before_pg)
                    self.assertEqual(proof['redis'], before_redis)
                    manifest, env = recovery.validate_bundle(server, bundle)
                    origin = {s: recovery.inspect('volume', manifest['volumes'][s]['name'])['CreatedAt'] for s in manifest['volumes']}
                    sql_path = Path(os.environ.get('PEP_MIGRATION_V78_SQL', str(Path(__file__).resolve().parents[2] /
                        'infra/src/main/resources/db/migration/V78__free_troop_nation_reference.sql')))
                    self.assertTrue(sql_path.is_file(), 'native V78 source SQL required; no substituted fixture DDL')
                    self.assertEqual(digest(sql_path)['sha256'],
                        'cc1768f38c9166cc538ada1b9337fa7057751c7b0ffa5765e6ab5011e43153eb',
                        'native rehearsal must use the approved V78 source unchanged')
                    v78 = sql_path.read_text()
                    # These native calls test the actual DDL and clone safety, not Spring/Flyway/image admission.
                    with StorageClone(recovery, bundle, manifest, env).restored() as clone:
                        columns = clone.columns()
                        rows, redis_hash = clone.data_fingerprint(columns), clone.redis_fingerprint()
                        self.assertEqual(redis_hash, before_redis_hash)
                        self.assertEqual(recovery.postgres_check(clone.pg, env), before_pg)
                        self.assertEqual(recovery.redis_check(clone.redis, socket=None), before_redis)
                        clone.sql('BEGIN;\n' + v78 + '\nCOMMIT;')
                        self.assertEqual(clone.data_fingerprint(columns), rows)
                        self.assertEqual(clone.redis_fingerprint(), redis_hash)
                        self.assertEqual(clone.sql('SELECT nation_ref FROM troop;').strip(), b'1')
                        clone.sql('BEGIN; INSERT INTO troop(world_id,nation,leader,soldiers) VALUES(7,0,1,3); COMMIT;')
                        self.assertEqual(clone.sql('SELECT count(*) FROM troop WHERE nation=0 AND nation_ref IS NULL;').strip(), b'1')
                        self.assertNotEqual(clone.data_fingerprint(columns), rows, 'row mutation negative control must change fingerprint')
                        clone.sql("INSERT INTO nation VALUES(8,99,'other-world synthetic nation');")
                        for nation in (-1, 99):
                            with self.assertRaises(RecoveryError):
                                clone.sql(f'BEGIN; INSERT INTO troop(world_id,nation,leader,soldiers) VALUES(7,{nation},1,3); COMMIT;')
                        with self.assertRaises(RecoveryError):
                            clone.sql('BEGIN; INSERT INTO troop(world_id,nation,leader,soldiers) VALUES(7,0,99,3); COMMIT;')
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'SET', 'sentinel', 'changed'])
                        self.assertNotEqual(clone.redis_fingerprint(), redis_hash, 'equal DBSIZE must not hide value mutation')
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'SET', 'sentinel', 'synthetic-value'])
                        self.assertEqual(clone.redis_fingerprint(), redis_hash)
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'PEXPIREAT', 'expiry-sentinel', str(expiry + 1000)])
                        self.assertNotEqual(clone.redis_fingerprint(), redis_hash, 'equal values/counts must not hide expiry mutation')
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'PEXPIREAT', 'expiry-sentinel', str(expiry)])
                        self.assertEqual(clone.redis_fingerprint(), redis_hash)
                        docker.run(['container', 'exec', '-i', clone.redis, 'redis-cli', '-n', '15', '-x', 'SET', 'binary-sentinel'],
                                   stdin=io.BytesIO(b'\0different\r\n"\\value'))
                        self.assertNotEqual(clone.redis_fingerprint(), redis_hash, 'DB15 binary value mutation must be detected')
                        changed = clone.redis_fingerprint()
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'XGROUP', 'SETID', 'stream', 'group', '1-0'])
                        self.assertNotEqual(clone.redis_fingerprint(), changed, 'group delivery cursor changes must be detected')
                        changed = clone.redis_fingerprint()
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'XGROUP', 'CREATE', 'stream', 'new-group', '0'])
                        self.assertNotEqual(clone.redis_fingerprint(), changed, 'consumer group changes must be detected')
                        docker.run(['container', 'exec', clone.redis, 'redis-cli', 'XREADGROUP', 'GROUP', 'new-group', 'reader',
                                    'COUNT', '1', 'STREAMS', 'stream', '>'])
                        with self.assertRaisesRegex(RecoveryError, 'Redis fingerprint failed'):
                            clone.redis_fingerprint()
                    with StorageClone(recovery, bundle, manifest, env).restored() as rollback:
                        self.assertEqual(recovery.postgres_check(rollback.pg, env), before_pg)
                        self.assertEqual(rollback.data_fingerprint(columns), rows)
                        self.assertEqual(rollback.redis_fingerprint(), redis_hash)
                        self.assertNotIn('nation_ref', rollback.columns()['troop'])
                    for service, created in origin.items():
                        volume = recovery.inspect('volume', manifest['volumes'][service]['name'])
                        self.assertEqual(volume['CreatedAt'], created, 'source volume must never be replaced/deleted')
                # Same source storage objects can resume after clones are cleaned up.
                for name in (pg, redis): docker.run(['container', 'start', name])
                self.assertEqual(recovery.postgres_check(pg, env, socket='/var/run/postgresql'), before_pg)
                self.assertEqual(recovery.redis_check(redis, socket=None), before_redis)
                self.assertEqual(redis_fingerprint(recovery, redis), before_redis_hash)
            finally:
                for kind, name, identity in reversed(owned):
                    obj = recovery.inspect(kind, name)
                    actual = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                    labels = (obj.get('Config', {}).get('Labels') if kind == 'container' else obj.get('Labels')) or {}
                    require(actual == identity and labels.get('org.opensamguk.migration.fixture') == token,
                            'fixture cleanup ownership changed')
                    docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])


@unittest.skipUnless(os.environ.get('RUN_PEP_MIGRATION_APP_TESTS') == '1',
                     'explicit actual application Docker opt-in required')
class ApplicationMigrationTests(unittest.TestCase):
    def test_actual_flyway_apps_cold_backup_restore_v78_rollback_and_source_resume(self):
        # Local real Boot JAR images are sufficient for this test, not for GHCR/CI admission.
        docker = Docker()
        token = uuid.uuid4().hex[:12]
        label_key = 'org.opensamguk.migration.fixture'
        owned = []
        with tempfile.TemporaryDirectory(prefix='pep-migration-app-fixture-') as temporary:
            root = Path(temporary)
            root.chmod(0o700)
            recovery = Recovery(docker, lock_path=root / 'fixture.lock')
            images = {}
            for generation in ('OLD', 'CANDIDATE'):
                revision = os.environ.get('PEP_MIGRATION_' + generation + '_SOURCE', '')
                self.assertRegex(revision, r'^[0-9a-f]{40}$', 'explicit image source revision required')
                for role in ('API', 'ENGINE'):
                    ref = os.environ.get('PEP_MIGRATION_' + generation + '_' + role + '_IMAGE', '')
                    self.assertRegex(ref, r'^sha256:[0-9a-f]{64}$', 'immutable local image ID required')
                    obj = recovery.inspect('image', ref)
                    self.assertEqual(obj['Id'], ref)
                    self.assertEqual((obj['Os'], obj['Architecture']), ('linux', 'amd64'))
                    self.assertEqual((obj['Config'].get('Labels') or {}).get('org.opencontainers.image.revision'), revision)
                    images[generation + '_' + role] = ref
            self.assertNotEqual(images['OLD_API'], images['CANDIDATE_API'])
            self.assertNotEqual(images['OLD_ENGINE'], images['CANDIDATE_ENGINE'])

            def create(kind, name, args):
                recovery.refuse_collisions([name])
                if kind == 'network':
                    self.assertNotIn(name, docker.run(['network', 'ls', '--format', '{{.Name}}']).decode().splitlines())
                raw = docker.run([kind, 'create', *(['--name', name] if kind == 'container' else []),
                    '--label', label_key + '=' + token, *args, *([] if kind == 'container' else [name])])
                obj = recovery.inspect(kind, name)
                identity = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                require(identity and (kind == 'volume' or raw.decode().strip() == identity), 'fixture create identity mismatch')
                owned.append((kind, name, identity))
                return name

            def wait_json(name, command, predicate):
                for _ in range(120):
                    try:
                        result = json.loads(docker.run(['container', 'exec', name, *command]))
                        if predicate(result): return result
                    except (RecoveryError, ValueError):
                        pass
                    if not recovery.inspect('container', name)['State']['Running']: break
                    time.sleep(1)
                self.fail('synthetic application did not satisfy its health/status contract')

            def stop(name):
                docker.run(['container', 'stop', '--time', '120', name])
                state = recovery.inspect('container', name)['State']
                self.assertFalse(state['Running'])
                self.assertFalse(state.get('OOMKilled'))
                self.assertIn(state.get('ExitCode'), (0, 143))

            try:
                pgimage = recovery.inspect('image', 'postgres:16-alpine')['Id']
                redisimage = recovery.inspect('image', 'redis:7-alpine')['Id']
                placeholder = recovery.inspect('image', 'eclipse-temurin:21-jdk')['Id']
                network = create('network', 'pep-app-fixture-' + token, ['--internal'])
                self.assertTrue(recovery.inspect('network', network)['Internal'])
                server = 'pep'
                project = 'opensamguk-s' + server
                stack = root / 'stack'
                (stack / 'servers').mkdir(parents=True, mode=0o700)
                tree = stack / 'data/scenarios'
                tree.mkdir(parents=True, mode=0o700)
                repository = Path(__file__).resolve().parents[2]
                shutil.copyfile(repository / 'tools/e2e/fixtures/yuzhou/scenario_990002.json', tree / 'scenario_990002.json')
                (tree / 'scenario_990002.json').chmod(0o600)
                write_private(stack / f'servers/s{server}.env',
                    f'SERVER_ID={server}\nOPENSAMGUK_WORLD_ID=7\nGAME_POSTGRES_USER=fixture\nGAME_POSTGRES_DB=fixture\n'.encode())
                write_private(stack / 'docker-compose.server.yml', b'# synthetic actual-app fixture only\n')
                env = {'SERVER_ID': server, 'OPENSAMGUK_WORLD_ID': '7',
                       'GAME_POSTGRES_USER': 'fixture', 'GAME_POSTGRES_DB': 'fixture'}
                for suffix in ('game-pgdata', 'game-redisdata'):
                    create('volume', f's{server}-{suffix}', ['--label', 'com.docker.compose.project=' + project,
                        '--label', 'com.docker.compose.volume=' + suffix])

                def container(service, image, args, command=()):
                    name = f's{server}-{service}' + ('-validation' if service in ('game-api', 'web-game') else '')
                    return create('container', name, ['--network', network, '--pull=never',
                        '--log-driver', 'none', '--label', 'com.docker.compose.project=' + project,
                        '--label', 'com.docker.compose.service=' + service, *args, image, *command])

                pg = container('game-postgres', pgimage, ['--memory', str(512 * 1024**2),
                    '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_DB=fixture', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                    '--mount', f'type=volume,source=s{server}-game-pgdata,target=/var/lib/postgresql/data'])
                redis = container('game-redis', redisimage, ['--memory', str(256 * 1024**2),
                    '--mount', f'type=volume,source=s{server}-game-redisdata,target=/data'], REDIS_CMD)
                for name in (pg, redis): docker.run(['container', 'start', name])
                recovery.wait_ready(['container', 'exec', pg, 'pg_isready', '-U', 'fixture', '-d', 'fixture'])
                recovery.wait_ready(['container', 'exec', redis, 'redis-cli', 'PING'])

                def sql(query):
                    return docker.run(['container', 'exec', '-i', pg, 'psql', '-X', '-A', '-t',
                        '-v', 'ON_ERROR_STOP=1', '-U', 'fixture', '-d', 'fixture'], stdin=io.BytesIO(query.encode()))

                common = dict(GAME_DATABASE_URL=f'jdbc:postgresql://{pg}:5432/fixture', GAME_DB_USER='fixture',
                    GAME_DB_PASSWORD='synthetic-password', REDIS_HOST=redis, REDIS_PORT='6379',
                    OPENSAMGUK_WORLD_ID='7', SCENARIO_DIR='/data/scenarios', SCENARIO_CODE='scenario_990002',
                    SENTRY_DSN='', JAVA_OPTS='-XX:MaxRAMPercentage=60.0 -Djava.security.egd=file:/dev/./urandom')
                mount = f'type=bind,source={tree},target=/data/scenarios,readonly'
                engine_env = dict(common, TURN_PROFILE_NAME='pep:scenario_990002',
                                  SCENARIO_SEED_ENABLED='false', OPENSAMGUK_DAEMON_ENABLED='true')

                from test_pep_topdown_catalog import make_bake
                import pep_topdown_catalog as topdown_contract
                import pep_preserving_topology as topology_contract
                import hashlib
                topdown_root = stack / 'data/topdown/pep'
                pins = {'region': None}
                for key, relative in [('tilesSha256', 'data/map/province-tiles.json'), ('worldJsonSha256', 'infra/src/main/resources/map/han-world-v3.json'), ('roadsSha256', 'data/map/han-land-roads-v1.json')]:
                    raw = subprocess.run(['git', 'show', os.environ['PEP_MIGRATION_OLD_SOURCE'] + ':' + relative], cwd=repository, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
                    pins[key] = hashlib.sha256(raw).hexdigest()
                selected_bake = make_bake(topdown_root, pins=pins)
                make_bake(topdown_root, kit='two', pins=pins)
                full_catalog = topdown_contract.catalog(topdown_root)
                def app(service, image, settings):
                    map_mount = ['--mount', f'type=bind,source={topdown_root},target=/app/data/map/topdown,readonly'] if service == 'game-api' else []
                    return container(service, image, ['--memory', str(1536 * 1024**2),
                        *[a for k, v in sorted(settings.items()) for a in ('-e', k + '=' + v)], '--mount', mount, *map_mount])

                # Seed only this fresh synthetic fixture. All restoration stages disable seeding.
                bootstrap = app('seed-bootstrap', images['OLD_ENGINE'],
                    dict(engine_env, SCENARIO_SEED_ENABLED='true', OPENSAMGUK_DAEMON_ENABLED='false'))
                docker.run(['container', 'start', bootstrap])
                health_engine = ['wget', '-T', '2', '-t', '1', '-qO-', 'http://localhost:8082/actuator/health']
                wait_json(bootstrap, health_engine, lambda value: value.get('status') == 'UP')
                stop(bootstrap)
                self.assertEqual(sql('SELECT count(*) FROM world_state WHERE id=7;').strip(), b'1')
                sql("""UPDATE game_kv SET value='1'::jsonb WHERE world_id=7 AND "table"='game_env'
                    AND namespace IN ('','game_env') AND key='plock';
                    INSERT INTO game_kv(world_id,"table",namespace,key,value)
                    SELECT 7,'game_env','game_env','plock','1'::jsonb WHERE NOT EXISTS(
                      SELECT 1 FROM game_kv WHERE world_id=7 AND "table"='game_env' AND key='plock');
                    UPDATE general SET gold=421,rice=931 WHERE world_id=7;
                    INSERT INTO troop(world_id,troop_leader,nation,name)
                    SELECT world_id,id,nation_id,'synthetic preserved troop' FROM general
                    WHERE world_id=7 AND nation_id>0 ORDER BY id LIMIT 1;""")
                self.assertEqual(sql('SELECT count(*) FROM troop WHERE world_id=7;').strip(), b'1')

                # Generated test-only key material never leaves this process or enters the bundle.
                private = subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
                public = subprocess.run(['openssl', 'pkey', '-pubout', '-outform', 'DER'], input=private,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
                api_env = dict(common, SCENARIO_SEED_ENABLED='false', OPENSAMGUK_PROFILE='pep:scenario_990002',
                    SERVER_ID=server, TOPDOWN_MAP_ROOT='/app/data/map/topdown', TOPDOWN_BAKE_ID=selected_bake, JWT_PUBLIC_KEY=base64.b64encode(public).decode(),
                    INTERNAL_SERVICE_TOKEN='synthetic-service-token', GATEWAY_API_URL='http://fixture-unavailable:8080')
                api = app('game-api', images['OLD_API'], api_env)
                engine = app('game-engine', images['OLD_ENGINE'], engine_env)
                health_api = ['curl', '-fsS', '--max-time', '2', 'http://localhost:8081/actuator/health']
                status_engine = ['wget', '-T', '2', '-t', '1', '-qO-', 'http://localhost:8082/admin/turn-daemon/status']
                clock = application_clock(json.loads(sql("SELECT json_agg(row_to_json(q)) FROM (SELECT id,current_year,current_month,current_phase,tick_seconds,meta,start_time FROM world_state WHERE id=7) q;")),
                    json.loads(sql("SELECT json_agg(server_id ORDER BY id) FROM ng_games WHERE world_id=7;")), 7)
                for name in (api, engine): docker.run(['container', 'start', name])
                wait_json(api, health_api, lambda value: value.get('status') == 'UP')
                wait_json(engine, status_engine, lambda value: status_matches(value, engine_env['TURN_PROFILE_NAME'], clock))
                topdown_contract.probe(recovery, api, selected_bake, full_catalog)
                web = container('web-game', placeholder, ['--entrypoint', 'sh', '-e', 'OPENSAMGUK_WORLD_ID=7',
                    '-e', 'GAME_API_URL=http://spep-game-api-validation:8081'], ['-c', 'trap "exit 0" TERM INT; while :; do sleep 1 & wait $!; done'])
                docker.run(['container', 'start', web])
                topology, before = topology_contract.observe(recovery)
                preserving = {'topology': topology, 'topdown': {'selected_bake': selected_bake, 'catalog': full_catalog}}
                stop(web)
                source = SourceEngineInputs.from_inspections(server, before)
                for name in (api, engine): stop(name)
                original_pg = recovery.postgres_check(pg, env, socket='/var/run/postgresql')
                original_redis = recovery.redis_check(redis, socket=None)
                original_redis_hash = redis_fingerprint(recovery, redis)
                self.assertNotIn('78', original_pg['versions'])
                for name in (redis, pg): stop(name)
                with recovery.locked():
                    bundle = recovery.capture(server=server, confirm='BACKUP ' + server, stack_dir=stack, backup_root=root, preserving=preserving)
                    topdown_contract.preserve(topdown_root, bundle, full_catalog)
                    storage = recovery.verify(server=server, confirm='VERIFY ' + server, bundle=bundle)
                    self.assertEqual(storage['postgres'], original_pg)
                    self.assertEqual(storage['redis'], original_redis)
                    print('Synthetic actual-app cold capture/storage restore: PASS', flush=True)
                    storage['redis_fingerprint'] = original_redis_hash
                    manifest, captured_env = recovery.validate_bundle(server, bundle)
                    self.assertEqual(captured_env, env)
                    tree_copy, scenario = preserve_scenario_tree(tree, bundle)
                    operator = PreservingMigration(recovery)
                    proofs = {}
                    for stage, generation, candidate in [('old', 'OLD', False), ('candidate', 'CANDIDATE', True),
                                                         ('rollback', 'OLD', False)]:
                        operator.validate_scenario(bundle, tree_copy, scenario, tree)
                        operator.validate_topdown(bundle, stack, preserving, before)
                        proofs[stage] = operator.clone_stage(bundle, manifest, env, source, tree_copy, storage,
                            images[generation + '_API'], images[generation + '_ENGINE'], candidate=candidate)
                        self.assertTrue(proofs[stage]['engine']['same_world_paused_ready'])
                        self.assertTrue(proofs[stage]['engine']['original_data_preserved'])
                        self.assertEqual(proofs[stage]['redis_fingerprint'], original_redis_hash)
                        operator.validate_scenario(bundle, tree_copy, scenario, tree)
                        operator.validate_topdown(bundle, stack, preserving, before)
                        print('Synthetic actual-app stage ' + stage + ': PASS', flush=True)
                    self.assertIn('78', proofs['candidate']['versions'])
                    self.assertNotIn('78', proofs['rollback']['versions'])
                    self.assertEqual(proofs['old'], proofs['rollback'])
                # Resume the exact fixture objects and verify schema/data before starting apps.
                for name in (pg, redis): docker.run(['container', 'start', before['game-postgres' if name == pg else 'game-redis']['Id']])
                self.assertEqual(recovery.postgres_check(pg, env, socket='/var/run/postgresql'), original_pg)
                self.assertEqual(redis_fingerprint(recovery, redis), original_redis_hash)
                print('Synthetic actual-app original storage resume: PASS', flush=True)
                for name in (api, engine): docker.run(['container', 'start', before['game-api' if name == api else 'game-engine']['Id']])
                wait_json(api, health_api, lambda value: value.get('status') == 'UP')
                wait_json(engine, status_engine, lambda value: status_matches(value, engine_env['TURN_PROFILE_NAME'], clock))
                topdown_contract.probe(recovery, api, selected_bake, full_catalog)
                for name in (api, engine): stop(name)
                self.assertEqual(recovery.postgres_check(pg, env, socket='/var/run/postgresql'), original_pg)
                self.assertEqual(redis_fingerprint(recovery, redis), original_redis_hash)
                for service, obj in before.items():
                    current = recovery.inspect('container', obj['Name'].removeprefix('/'))
                    self.assertEqual(current['Id'], obj['Id'])
                    self.assertEqual(current['Mounts'], obj['Mounts'])
                    self.assertEqual(current['HostConfig']['PortBindings'], obj['HostConfig']['PortBindings'])
                    self.assertFalse(current['HostConfig']['PortBindings'])
                for image in images.values(): self.assertEqual(recovery.inspect('image', image)['Id'], image)
                print('Synthetic actual-app exact source app resume/data preservation: PASS', flush=True)
            finally:
                for kind, name, identity in reversed(owned):
                    obj = recovery.inspect(kind, name)
                    actual = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                    labels = (obj.get('Config', {}).get('Labels') if kind == 'container' else obj.get('Labels')) or {}
                    require(actual == identity and labels.get(label_key) == token, 'fixture cleanup ownership changed')
                    docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])
                for kind in ('container', 'volume', 'network'):
                    leftovers = docker.run([kind, 'ls', *(['--all'] if kind == 'container' else []),
                        '--filter', 'label=' + label_key + '=' + token, '--format', '{{.Name}}' if kind == 'volume' else '{{.ID}}'])
                    self.assertFalse(leftovers.strip(), 'fixture resources remain')


if __name__ == '__main__':
    unittest.main()
