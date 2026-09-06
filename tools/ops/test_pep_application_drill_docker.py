#!/usr/bin/env python3
"""Real local synthetic application boundary proof; never a live PEP proof."""
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

import game_server_recovery as base
import pep_application_drill as app

FIXTURE_LABEL = 'org.opensamguk.pep-drill.fixture'


class DesktopDocker:
    """Explicit context, hidden diagnostics/env, and no build/pull/login authority."""
    def __init__(self, token):
        base.require(os.environ.get('DOCKER_CONTEXT') == 'desktop-linux', 'explicit desktop-linux context required')
        self.token, self.calls, self.last_status_keys = token, [], None

    def run(self, args, *, stdin=None, stdout=None):
        base.require(args[0] not in ('build', 'pull', 'login') and args[:2] not in
                     (['image', 'build'], ['image', 'pull']), 'fixture forbids registry/build access')
        # Preserve argv shape only; never store env values in diagnostic records.
        self.calls.append(['<env-redacted>' if i and args[i-1] == '-e' else a for i, a in enumerate(args)])
        args = list(args)
        if args[0] in ('container', 'volume', 'network') and args[1] == 'create':
            args[2:2] = ['--label', FIXTURE_LABEL + '=' + self.token]
        kwargs = {'input': stdin.getvalue()} if isinstance(stdin, io.BytesIO) else {'stdin': stdin}
        result = subprocess.run(['docker', '--context', 'desktop-linux', *args],
                                **kwargs, stdout=stdout or subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=900)
        if result.returncode:
            raise base.RecoveryError('local fixture Docker command failed')
        if 'wget' in args and result.stdout:
            try:
                value = json.loads(result.stdout)
                self.last_status_keys = sorted(value)
            except ValueError:
                pass
        return result.stdout or b''


@unittest.skipUnless(os.environ.get('RUN_PEP_APPLICATION_DRILL_DOCKER_TESTS') == '1', 'explicit Docker opt-in required')
class DockerDrillTests(unittest.TestCase):
    def test_internal_clone_materializes_paused_world(self):
        token = uuid.uuid4().hex[:12]
        server = 'fixture' + token
        project = 'opensamguk-s' + server
        docker = DesktopDocker(token)
        owned = []
        images = {}
        with tempfile.TemporaryDirectory(prefix='pep-drill-fixture-') as temporary:
            root = Path(temporary).resolve()
            helper = base.Recovery(docker, lock_path=root / 'lock')
            drill = app.PepApplicationDrill(attempts=180)
            def create(kind, name, args):
                before = docker.run([kind, 'ls', *(['--all'] if kind == 'container' else []), '--format',
                                     '{{.Names}}' if kind == 'container' else '{{.Name}}']).decode().splitlines()
                self.assertNotIn(name, before)
                raw = docker.run([kind, 'create', *args])
                obj = helper.inspect(kind, name)
                identity = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                owned.append((kind, name, identity))
                labels = obj['Config']['Labels'] if kind == 'container' else obj['Labels']
                self.assertEqual(labels[FIXTURE_LABEL], token)
                if kind != 'volume': self.assertEqual(raw.decode().strip(), identity)
                return name
            def container(service, image, extra):
                name = f's{server}-{service}'
                return create('container', name, ['--name', name, '--network', network, '--pull=never',
                    '--label', 'com.docker.compose.project=' + project,
                    '--label', 'com.docker.compose.service=' + service, *extra, image])
            try:
                for key, ref in [('pg', 'postgres:16-alpine'), ('redis', 'redis:7-alpine'),
                                 ('engine', 'opensamguk-game-engine:latest'), ('placeholder', 'opensamguk-web-game:latest')]:
                    obj = helper.inspect('image', ref)
                    self.assertEqual((obj['Os'], obj['Architecture']), ('linux', 'amd64'))
                    images[key] = obj['Id']
                network = create('network', 'pep-source-' + token, ['--internal', 'pep-source-' + token])
                self.assertTrue(helper.inspect('network', network)['Internal'])
                stack = root / 'stack'; (stack / 'servers').mkdir(parents=True, mode=0o700)
                tree = stack / 'data' / 'scenarios'
                shutil.copytree(Path(__file__).resolve().parents[2] / 'infra/src/main/resources/scenario', tree)
                for p in [tree, *tree.rglob('*')]: os.chmod(p, 0o700 if p.is_dir() else 0o600)
                base.write_private(stack / f'servers/s{server}.env',
                    f'SERVER_ID={server}\nOPENSAMGUK_WORLD_ID=7\nGAME_POSTGRES_USER=sammo\nGAME_POSTGRES_DB=sammo\n'.encode())
                base.write_private(stack / 'docker-compose.server.yml', b'# private synthetic fixture only\n')
                backups = root / 'backups'; backups.mkdir(mode=0o700)
                for suffix in ('game-pgdata', 'game-redisdata'):
                    name = f's{server}-{suffix}'
                    create('volume', name, ['--label', 'com.docker.compose.project=' + project,
                        '--label', 'com.docker.compose.volume=' + suffix, name])
                pg = container('game-postgres', images['pg'], ['--memory', str(512 * 1024**2),
                    '-e', 'POSTGRES_USER=sammo', '-e', 'POSTGRES_DB=sammo', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                    '--mount', f'type=volume,source=s{server}-game-pgdata,target=/var/lib/postgresql/data'])
                redis_name = f's{server}-game-redis'
                redis = create('container', redis_name, ['--name', redis_name, '--network', network, '--pull=never',
                    '--memory', str(256 * 1024**2), '--label', 'com.docker.compose.project=' + project,
                    '--label', 'com.docker.compose.service=game-redis', '--mount',
                    f'type=volume,source=s{server}-game-redisdata,target=/data', images['redis'], *base.REDIS_CMD])
                for name in (pg, redis): docker.run(['container', 'start', name])
                helper.wait_ready(['container', 'exec', pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'sammo', '-d', 'sammo'])
                helper.wait_ready(['container', 'exec', redis, 'redis-cli', 'PING'])
                docker.run(['container', 'exec', redis, 'redis-cli', 'SET', 'synthetic:source-sentinel', '1'])
                engine_env = dict(GAME_DATABASE_URL=f'jdbc:postgresql://{pg}:5432/sammo', GAME_DB_USER='sammo',
                    GAME_DB_PASSWORD='fixture', REDIS_HOST=redis, REDIS_PORT='6379', OPENSAMGUK_WORLD_ID='7',
                    TURN_PROFILE_NAME='che:scenario_2', SCENARIO_CODE='scenario_2',
                    SCENARIO_SEED_ENABLED='true', OPENSAMGUK_DAEMON_ENABLED='false')
                mount = f'type=bind,source={tree},target=/data/scenarios,readonly'
                engine = container('game-engine', images['engine'], ['--memory', str(1536 * 1024**2),
                    *[a for k, v in engine_env.items() for a in ('-e', k + '=' + v)], '--mount', mount])
                docker.run(['container', 'start', engine])
                ready = False
                for _ in range(120):
                    try:
                        value = json.loads(docker.run(['container', 'exec', engine, 'wget', '-T', '2', '-t', '1', '-qO-',
                                               'http://localhost:8082/admin/turn-daemon/status']))
                        ready = True
                        break
                    except (base.RecoveryError, ValueError):
                        if not helper.inspect('container', engine)['State']['Running']:
                            break
                        time.sleep(1)
                if not ready:
                    # Fixed diagnostic names only; no source logs or environment values escape.
                    state = helper.inspect('container', engine)['State']
                    probe = docker.run(['container', 'exec', engine, 'sh', '-c', 'command -v wget || command -v curl || true']) if state['Running'] else b''
                    self.fail('cached engine fixture boot/status unavailable; running=' + str(state['Running']) +
                              '; local HTTP client=' + probe.decode().strip())
                required = {'serviceMaterialized', 'autoStartEnabled', 'recoveryMode', 'recoveryReady',
                            'consecutiveFailures', 'clockError', 'loopUptimeSeconds'}
                missing = sorted(required - set(value))
                if missing:
                    self.fail('cached archived engine status contract incompatible; missing fields=' + ','.join(missing))
                docker.run(['container', 'stop', '--time', '30', engine])
                # Fixture world only: deterministic clock plus the higher-ID empty-namespace winner.
                sql = b'''UPDATE world_state SET current_year=180, current_month=1, current_phase=1,
                    tick_seconds=600, start_time='2026-01-01T00:00:00Z',
                    meta=jsonb_set(coalesce(meta,'{}'::jsonb), '{lastTurnTime}', '"2026-01-01T00:00:00Z"') WHERE id=7;
                    DELETE FROM game_kv WHERE world_id=7 AND "table"='game_env' AND key='plock';
                    INSERT INTO game_kv(world_id,"table",namespace,key,value) VALUES(7,'game_env','game_env','plock','0');
                    INSERT INTO game_kv(world_id,"table",namespace,key,value) VALUES(7,'game_env','','plock','0');'''
                docker.run(['container', 'exec', '-i', pg, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'sammo', '-d', 'sammo'], stdin=io.BytesIO(sql))
                for service in ('game-api', 'web-game'):
                    extra = ['--entrypoint', '/bin/true']
                    if service == 'game-api': extra += ['-e', 'OPENSAMGUK_WORLD_ID=7', '--mount', mount]
                    name = container(service, images['placeholder'], extra)
                    docker.run(['container', 'start', name])
                docker.run(['container', 'exec', redis, 'redis-cli', 'SAVE'])
                self.assertEqual(docker.run(['container', 'exec', redis, 'redis-cli', '--raw', 'GET',
                                              'synthetic:source-sentinel']).strip(), b'1')
                source_key_count = int(docker.run(['container', 'exec', redis, 'redis-cli', '--raw', 'DBSIZE']).strip())
                self.assertGreaterEqual(source_key_count, 1)
                for name in (redis, pg): docker.run(['container', 'stop', '--time', '30', name])
                inspections = {s: helper.inspect('container', f's{server}-{s}') for s in (*app.SERVICES, 'game-api')}
                inputs = app.SourceEngineInputs.from_inspections(server, inspections)
                bundle = helper.capture(server=server, confirm='BACKUP ' + server, stack_dir=stack, backup_root=backups)
                verification = helper.verify(server=server, confirm='VERIFY ' + server, bundle=bundle)
                self.assertEqual(verification['redis']['key_count'], source_key_count)
                companion = bundle.with_name(bundle.name + '.scenario'); companion.mkdir(mode=0o700)
                shutil.copytree(tree, companion / 'tree')
                scenario = app.ScenarioTreeDigest.capture(companion / 'tree', base.digest(bundle / 'manifest.json')['sha256'])
                base.write_private(companion / 'manifest.json', base.json_bytes(scenario.manifest()))
                proof = drill.prove(helper, bundle, inputs, companion / 'tree', scenario)
                self.assertTrue(proof.cleanup['success'])
                self.assertEqual(proof.plock_delta, dict(updated_rows=1, inserted_rows=0, loader_visible_rows=2, effective_value=1))
                # A second cold capture detects any source-volume mutation caused by the drill.
                after = helper.capture(server=server, confirm='BACKUP ' + server, stack_dir=stack, backup_root=backups)
                for filename in ('postgres.tar', 'redis.tar'):
                    self.assertEqual(base.digest(bundle / filename), base.digest(after / filename))
                self.assertFalse(any(a[:2] == ['image', 'load'] for a in docker.calls))
                for args in docker.calls:
                    self.assertNotIn('--publish', args); self.assertNotIn('-p', args)
                print('Synthetic application-drill boundary proof: PASS')
            finally:
                failures = []
                for kind, name, identity in reversed(owned):
                    try:
                        obj = helper.inspect(kind, name)
                        actual = obj.get('Id') if kind != 'volume' else (obj['Name'], obj['CreatedAt'], obj['Mountpoint'])
                        labels = obj['Config']['Labels'] if kind == 'container' else obj['Labels']
                        base.require(actual == identity and labels.get(FIXTURE_LABEL) == token, 'fixture cleanup ownership mismatch')
                        docker.run([kind, 'rm', *(['--force'] if kind == 'container' else []), name])
                    except base.RecoveryError: failures.append((kind, name))
                self.assertFalse(failures, 'fixture cleanup left owned resources')
                for image in images.values(): self.assertEqual(helper.inspect('image', image)['Id'], image)
                for kind in ('container', 'volume', 'network'):
                    leftovers = docker.run([kind, 'ls', *(['--all'] if kind == 'container' else []),
                        '--filter', 'label=' + FIXTURE_LABEL + '=' + token, '--format', '{{.ID}}' if kind != 'volume' else '{{.Name}}'])
                    self.assertFalse(leftovers.strip(), 'fixture resources remain')
                print('Fixture exact identity/label cleanup and cached image identity checks: PASS')


if __name__ == '__main__':
    if os.environ.get('RUN_PEP_APPLICATION_DRILL_DOCKER_TESTS') != '1':
        raise SystemExit('NOT RUN: RUN_PEP_APPLICATION_DRILL_DOCKER_TESTS=1 is required')
    unittest.main(verbosity=2)
