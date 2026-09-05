#!/usr/bin/env python3
"""Explicit real Docker rehearsal using only local, labeled synthetic fixtures.

Requires locally available postgres:16-alpine, redis:7-alpine and
opensamguk-web-game:latest (used only as /bin/true, never as an application).
No pulls, GHCR authentication, production env or published ports are used.
"""
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
import uuid

import game_server_recovery as recovery


@unittest.skipUnless(os.environ.get('RUN_RECOVERY_DOCKER_TESTS') == '1',
                     'NOT RUN: set RUN_RECOVERY_DOCKER_TESTS=1 for the real Docker rehearsal')
class DockerRoundtrip(unittest.TestCase):
    def test_real_cold_capture_and_isolated_storage_restore(self):
        docker = recovery.Docker()
        token = uuid.uuid4().hex[:12]
        server = 'fixture' + token
        project = 'opensamguk-s' + server
        owned_containers, owned_volumes, owned_tags = {}, [], []
        with tempfile.TemporaryDirectory(prefix='opensamguk-recovery-fixture-') as temporary:
            root = Path(temporary).resolve()
            stack = root / 'stack'
            (stack / 'servers').mkdir(parents=True)
            (stack / 'data/scenarios').mkdir(parents=True)
            backups = root / 'backups'
            backups.mkdir(mode=0o700)
            (stack / f'servers/s{server}.env').write_text(
                f'SERVER_ID={server}\nOPENSAMGUK_WORLD_ID=7\nGAME_POSTGRES_USER=sammo\nGAME_POSTGRES_DB=sammo\n')
            (stack / 'docker-compose.server.yml').write_text('# Synthetic fixture only; never compose up.\n')
            helper = recovery.Recovery(docker, lock_path=root / 'fixture.lock')
            original_volumes = set(docker.run(['volume', 'ls', '--format', '{{.Name}}']).decode().splitlines())
            try:
                images = {}
                for key, base in [('pg', 'postgres:16-alpine'), ('redis', 'redis:7-alpine'),
                                  ('app', 'opensamguk-web-game:latest')]:
                    base_id = json.loads(docker.run(['image', 'inspect', base]))[0]['Id']
                    tag = f'opensamguk-recovery-fixture-{token}:{key}'
                    content = f'FROM {base}\nLABEL org.opensamguk.recovery.fixture="{token}"\n'.encode()
                    context = io.BytesIO()
                    with tarfile.open(fileobj=context, mode='w') as archive:
                        member = tarfile.TarInfo('Dockerfile')
                        member.size = len(content)
                        archive.addfile(member, io.BytesIO(content))
                    context.seek(0)
                    # A seekable real descriptor is required by subprocess stdin.
                    with tempfile.TemporaryFile() as source:
                        source.write(context.read())
                        source.seek(0)
                        docker.run(['build', '--pull=false', '--network', 'none', '-t', tag, '-'], stdin=source)
                    owned_tags.append(tag)
                    self.assertEqual(json.loads(docker.run(['image', 'inspect', base]))[0]['Id'], base_id)
                    image = json.loads(docker.run(['image', 'inspect', tag]))[0]
                    self.assertEqual(image['Config']['Labels']['org.opensamguk.recovery.fixture'], token)
                    images[key] = (tag, image['Id'])

                for suffix in ['game-pgdata', 'game-redisdata']:
                    name = f's{server}-{suffix}'
                    self.assertNotIn(name, original_volumes)
                    docker.run(['volume', 'create', '--label', f'com.docker.compose.project={project}',
                        '--label', f'com.docker.compose.volume={suffix}',
                        '--label', f'org.opensamguk.recovery.fixture={token}', name])
                    owned_volumes.append(name)

                for service in recovery.SERVICES:
                    name = f's{server}-{service}'
                    command = ['container', 'create', '--name', name, '--network', 'none',
                        '--label', f'com.docker.compose.project={project}',
                        '--label', f'com.docker.compose.service={service}',
                        '--label', f'org.opensamguk.recovery.fixture={token}']
                    if service == 'game-postgres':
                        command += ['-e', 'POSTGRES_DB=sammo', '-e', 'POSTGRES_USER=sammo',
                            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '--mount',
                            f'type=volume,source=s{server}-game-pgdata,target=/var/lib/postgresql/data', images['pg'][0]]
                    elif service == 'game-redis':
                        command += ['--mount', f'type=volume,source=s{server}-game-redisdata,target=/data',
                                    images['redis'][0], *recovery.REDIS_CMD]
                    else:
                        if service in ('game-engine', 'game-api'):
                            command += ['-e', 'OPENSAMGUK_WORLD_ID=7', '--mount',
                                f'type=bind,source={stack / "data/scenarios"},target=/data/scenarios,readonly']
                        command += ['--entrypoint', '/bin/true', images['app'][0]]
                    identity = docker.run(command).decode().strip()
                    owned_containers[name] = identity
                    docker.run(['container', 'start', name])

                postgres = f's{server}-game-postgres'
                redis = f's{server}-game-redis'
                # The entrypoint's temporary init server has no TCP listener. Wait for final boot.
                helper.wait_ready(['container', 'exec', postgres, 'pg_isready', '-h', '127.0.0.1',
                                   '-U', 'sammo', '-d', 'sammo'])
                schema = b'''CREATE TABLE world_state (id integer PRIMARY KEY, scenario_code text);
CREATE TABLE city (id integer PRIMARY KEY, name text);
CREATE TABLE nation (id integer PRIMARY KEY, name text);
CREATE TABLE general (id integer PRIMARY KEY, name text);
CREATE TABLE flyway_schema_history (installed_rank integer, version text, success boolean);
INSERT INTO world_state VALUES (7, 'synthetic');
INSERT INTO city VALUES (1, 'fixture city one'), (2, 'fixture city two');
INSERT INTO nation VALUES (1, 'fixture nation');
INSERT INTO general VALUES (1, 'fixture one'), (2, 'fixture two'), (3, 'fixture three');
INSERT INTO flyway_schema_history VALUES (1, '1', true), (2, '48', true);
'''
                with tempfile.TemporaryFile() as sql:
                    sql.write(schema)
                    sql.seek(0)
                    docker.run(['container', 'exec', '-i', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                        '-U', 'sammo', '-d', 'sammo'], stdin=sql)
                self.assertEqual(helper.wait_ready(['container', 'exec', redis, 'redis-cli', 'PING']).strip(), b'PONG')
                docker.run(['container', 'exec', redis, 'redis-cli', 'SET', 'synthetic:durable', 'fixture'])
                docker.run(['container', 'exec', redis, 'redis-cli', 'SAVE'])
                with tempfile.TemporaryFile() as dump:
                    docker.run(['container', 'exec', postgres, 'pg_dump', '--no-owner', '--no-privileges',
                        '-U', 'sammo', '-d', 'sammo'], stdout=dump)
                    dump.seek(0)
                    source_hash = recovery.logical_dump_hash(dump)
                for name in owned_containers:
                    docker.run(['container', 'stop', '--time', '30', name])

                # Exercise same-instance reentrancy against the actual local Docker operations.
                with helper.locked():
                    bundle = helper.capture(server=server, confirm='BACKUP ' + server, stack_dir=stack, backup_root=backups)
                    result = helper.verify(server=server, confirm='VERIFY ' + server, bundle=bundle)
                self.assertTrue(result['success'])
                self.assertEqual(result['postgres']['counts'], {'world_state': 1, 'city': 2, 'nation': 1, 'general': 3})
                self.assertEqual(result['postgres']['versions'], ['1', '48'])
                self.assertEqual(result['postgres']['logical_dump_sha256'], source_hash)
                self.assertEqual(result['redis']['key_count'], 1)
                self.assertTrue(result['cleanup']['success'])
                self.assertFalse(result['application_boot_verified'])
                self.assertEqual(set(docker.run(['volume', 'ls', '--format', '{{.Name}}']).decode().splitlines()),
                                 original_volumes | set(owned_volumes), 'scratch/anonymous volume leak')
                print('Synthetic Docker proof: world_state=1 city=2 nation=1 general=3; Flyway=1,48; '
                      'Redis durable key=1; normalized full DB hash matches; scratch cleanup confirmed.')
            finally:
                for name, identity in reversed(list(owned_containers.items())):
                    obj = json.loads(docker.run(['container', 'inspect', name]))[0]
                    self.assertEqual(obj['Id'], identity)
                    self.assertEqual(obj['Config']['Labels']['org.opensamguk.recovery.fixture'], token)
                    docker.run(['container', 'rm', '--force', name])
                for name in reversed(owned_volumes):
                    obj = json.loads(docker.run(['volume', 'inspect', name]))[0]
                    self.assertEqual(obj['Labels']['org.opensamguk.recovery.fixture'], token)
                    docker.run(['volume', 'rm', name])
                for tag in reversed(owned_tags):
                    obj = json.loads(docker.run(['image', 'inspect', tag]))[0]
                    self.assertEqual(obj['Config']['Labels']['org.opensamguk.recovery.fixture'], token)
                    docker.run(['image', 'rm', tag])


if __name__ == '__main__':
    if os.environ.get('RUN_RECOVERY_DOCKER_TESTS') != '1':
        raise SystemExit('NOT RUN: explicitly set RUN_RECOVERY_DOCKER_TESTS=1 for the real synthetic Docker rehearsal.')
    unittest.main(verbosity=2)
