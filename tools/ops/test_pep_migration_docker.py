"""Native synthetic cold-copy/migration/rollback proof, never operating data or app QA."""
import io
import os
from pathlib import Path
import tempfile
import unittest
import uuid

from game_server_recovery import (Docker, Recovery, REDIS_CMD, RecoveryError,
                                 digest, require, write_private)
from pep_migration_clone import StorageClone


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
                recovery.wait_ready(['container', 'exec', pg, 'pg_isready', '-U', 'fixture', '-d', 'fixture'])
                docker.run(['container', 'exec', '-i', pg, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'fixture', '-d', 'fixture'],
                           stdin=io.BytesIO(SQL.encode()))
                recovery.wait_ready(['container', 'exec', redis, 'redis-cli', 'PING'])
                for command in [('SET', 'sentinel', 'synthetic-value'), ('LPUSH', 'list', 'a', 'b'),
                                ('SADD', 'set', 'a', 'b'), ('ZADD', 'zset', '1', 'a'),
                                ('HSET', 'hash', 'field', 'value'), ('XADD', 'stream', '1-0', 'field', 'value'),
                                ('XGROUP', 'CREATE', 'stream', 'group', '0')]:
                    docker.run(['container', 'exec', redis, 'redis-cli', *command])
                for service in ('game-api', 'game-engine', 'web-game'):
                    args = ['--entrypoint', 'true', '-e', 'OPENSAMGUK_WORLD_ID=7']
                    if service != 'web-game': args += ['--mount', f'type=bind,source={tree},target=/data/scenarios,readonly']
                    placeholder = container(service, placeholderimage, args)
                    docker.run(['container', 'start', '--attach', placeholder])
                source_env = {'OPENSAMGUK_WORLD_ID': '7', 'GAME_POSTGRES_USER': 'fixture', 'GAME_POSTGRES_DB': 'fixture'}
                before_pg = recovery.postgres_check(pg, source_env, socket='/var/run/postgresql')
                before_redis = recovery.redis_check(redis, socket=None)
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
            finally:
                for kind, name, identity in reversed(owned):
                    obj = recovery.inspect(kind, name)
                    actual = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                    labels = (obj.get('Config', {}).get('Labels') if kind == 'container' else obj.get('Labels')) or {}
                    require(actual == identity and labels.get('org.opensamguk.migration.fixture') == token,
                            'fixture cleanup ownership changed')
                    docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])


if __name__ == '__main__':
    unittest.main()
