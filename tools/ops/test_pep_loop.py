"""Offline D143 boundaries. No Docker, network, env files or operating DB access."""
import argparse
from copy import deepcopy
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import pep_loop as pep


SOURCE = 'a' * 40
MANIFEST = 'sha256:' + '1' * 64
CONFIG = 'sha256:' + '2' * 64
IMAGES = {role: pep.image_contract(pep.REGISTRY + '@' + MANIFEST, CONFIG) for role in pep.ROLES}
WORLD = {'worlds': 1, 'id': 1, 'scenario_code': 'scenario_3190', 'tick_seconds': 3600,
         'maxgeneral': 50, 'max_type': 'number', 'env_max': 50, 'env_max_type': 'number',
         'block': 1, 'env_block': 1, 'first_policy': 'immediate', 'last_turn': '2026-10-07T00:00:00Z'}
TICK = {'loopAlive': True, 'paused': False, 'recoveryReady': True, 'failedTicks': 0,
        'successfulTicks': 1, 'clock': {'tickSeconds': 3600, 'lastTurnTime': '2026-10-07T00:00:00Z',
                                     'year': 190, 'month': 1, 'phase': 1}}


class PepContractTests(unittest.TestCase):
    def test_json_media_accepts_parameters_case_and_structured_suffix(self):
        for media in ('application/json', ' application/JSON ; charset=UTF-8',
                      'application/vnd.spring-boot.actuator.v3+json',
                      'Application/Vnd.Spring-Boot.Actuator.V3+JSON; charset=utf-8',
                      'application/problem+json'):
            with self.subTest(media=media), patch.object(pep, 'fetch', return_value=(b'{"status":"UP"}', media)):
                self.assertEqual(pep.get_json(pep.PRIVATE[0], 8081, '/actuator/health'), {'status': 'UP'})

    def test_json_media_rejects_other_types_and_embedded_json_label(self):
        for media in ('text/html', 'text/plain', 'application/octet-stream', 'application/jsonp',
                      'text/plain; profile=application/json', 'text/vnd.example+json',
                      'application/+json', 'application/bad/type+json', 'application/bad type+json', ''):
            with self.subTest(media=media), patch.object(pep, 'fetch', return_value=(b'{}', media)):
                with self.assertRaisesRegex(ValueError, 'internal API did not return JSON'):
                    pep.get_json(pep.PRIVATE[0], 8081, '/actuator/health')

    def test_json_media_does_not_accept_malformed_body(self):
        for media in ('application/json', 'application/vnd.spring-boot.actuator.v3+json'):
            with self.subTest(media=media), patch.object(pep, 'fetch', return_value=(b'{broken', media)):
                with self.assertRaises(json.JSONDecodeError):
                    pep.get_json(pep.PRIVATE[0], 8081, '/actuator/health')

    def test_json_media_does_not_bypass_http_200_requirement(self):
        for status in (b'201', b'302', b'500'):
            with self.subTest(status=status), patch.object(pep, 'command', return_value=(
                    b'{"status":"UP"}\n' + status + b'\napplication/vnd.spring-boot.actuator.v3+json')):
                with self.assertRaisesRegex(ValueError, 'internal API did not return 200'):
                    pep.get_json(pep.PRIVATE[0], 8081, '/actuator/health')

    def test_first_immediate_tick_can_keep_initial_calendar(self):
        pep.check_tick(TICK)
        for field, value in [('failedTicks', 1), ('successfulTicks', 0), ('recoveryReady', False),
                             ('loopAlive', False), ('paused', True)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                pep.check_tick({**TICK, field: value})

    def test_config_and_game_env_both_require_numeric_50(self):
        pep.check_world(WORLD)
        for field, value in [('maxgeneral', '50'), ('env_max', '50'), ('max_type', 'string'),
                             ('env_max_type', 'string'), ('block', 0), ('env_block', 0),
                             ('id', 2), ('worlds', 2), ('tick_seconds', 60)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                pep.check_world({**WORLD, field: value})

    def test_docker_image_id_can_be_platform_or_config_digest(self):
        image = {'Id': CONFIG, 'Os': 'linux', 'Architecture': 'amd64',
                 'RepoDigests': [pep.REGISTRY + '@' + MANIFEST], 'Revision': SOURCE}
        for identifier in (CONFIG, MANIFEST):
            pep.check_image({**image, 'Id': identifier}, IMAGES['game-api'], SOURCE)
        for field, value in [('Id', 'sha256:' + '3' * 64), ('Architecture', 'arm64'),
                             ('RepoDigests', ['ghcr.io/another/repo@' + MANIFEST]), ('Revision', 'b' * 40)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                pep.check_image({**image, field: value}, IMAGES['game-api'], SOURCE)

    def test_private_ports_and_aliases_are_rejected(self):
        private = {'Running': True, 'Ports': {'8081/tcp': None},
                   'Networks': {'opensamguk-net': {'Aliases': None}},
                   'Labels': {'com.docker.compose.project': 'opensamguk-spep'}}
        pep.check_private(private)
        for changed in ({'Ports': {'8081/tcp': [{'HostPort': '8081'}]}},
                        {'Networks': {'opensamguk-net': {'Aliases': ['game-api']}}},
                        {'Labels': {'com.docker.compose.project': 'opensamguk-sother'}},
                        {'Running': False}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                pep.check_private({**private, **changed})

    def test_reset_classification(self):
        for path in ['infra/src/main/resources/db/migration/V900.sql', 'data/curated/han/world.json',
                     'infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt',
                     'infra/src/main/resources/scenario/scenario_3190.json',
                     'app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt',
                     'docker/game-engine.Dockerfile',
                     'app/game-engine/src/main/kotlin/opensamguk/engine/GameEngineApplication.kt',
                     'app/game-engine/src/main/resources/application.yml']:
            with self.subTest(path=path):
                self.assertEqual(pep.select_mode(['web/game/page.tsx', path]), 'reset')
        self.assertEqual(pep.select_mode(['web/game/page.tsx', 'app/game-api/read.kt']), 'refresh')

    def test_coalesced_and_reverted_seed_commit_still_requires_reset(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            def git(*args):
                return subprocess.check_output(['git', '-C', temp, *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'offline')
            git('config', 'user.email', 'offline@example.invalid')
            (repo / 'README').write_text('base')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            curated = repo / 'data/curated/world.json'
            curated.parent.mkdir(parents=True)
            curated.write_text('{}')
            git('add', '.')
            git('commit', '-qm', 'seed change')
            curated.unlink()
            git('add', '.')
            git('commit', '-qm', 'revert seed')
            head = git('rev-parse', 'HEAD')
            self.assertEqual(pep.unapplied_mode(head, base, repo), 'reset')
            self.assertEqual(pep.unapplied_mode(head, head, repo), 'refresh')

    def test_wrong_server_is_denied_before_any_command(self):
        args = argparse.Namespace(server='PEP', mode='reset', source=SOURCE, checkout=Path('.'))
        with patch.object(pep, 'command') as cmd, self.assertRaises(ValueError):
            pep.apply(args)
        cmd.assert_not_called()

    def test_override_reuses_private_bundle_and_blocks_creation(self):
        spec = pep.override(IMAGES, [{'type': 'bind', 'source': '/existing/bundle',
                                     'target': '/app/data/map/topdown', 'read_only': True}], 'f' * 64, True)
        self.assertEqual(set(spec['services']), set(pep.ROLES))
        for role in ('game-engine', 'game-api'):
            env = spec['services'][role]['environment']
            self.assertEqual(env['SCENARIO_CODE'], 'scenario_3190')
            self.assertEqual(env['OPENSAMGUK_WORLD_ID'], '1')
            self.assertEqual(env['RESET_TURNTERM'], '60')
            self.assertEqual(env['RESET_FIRST_TURN'], 'immediate')
            self.assertEqual(env['RESET_MAXGENERAL'], '50')
            self.assertEqual(env['RESET_BLOCK_GENERAL_CREATE'], '1')
            self.assertEqual(env['SERVER_GENERATION'], '0')
        self.assertEqual(spec['services']['web-game']['environment']['GAME_API_URL'],
                         'http://spep-game-api-validation:8081')
        self.assertNotIn('ports', spec['services']['game-api'])

    def test_api_probe_checks_entire_current_bundle_and_never_gateway(self):
        bake = 'f' * 64
        assets = {
            'grid/L0/0_0.bin.gz': gzip.compress(b'chunk planes', mtime=0),
            'grid/L2.bin.gz': gzip.compress(b'overview planes', mtime=0),
            'places.json.gz': gzip.compress(b'{"places":[]}', mtime=0),
            'defects.json': b'{"defects":[]}',
        }
        base = '/api/map/topdown/' + bake + '/'
        fixtures = {
            '/actuator/health': {'status': 'UP'},
            '/api/server-basic-info': {'game': {'maxUserCnt': 50, 'turnTerm': 60,
                'blockGeneralCreate': 1, 'scenario': '동탁의 전횡과 반동탁연합'}, 'me': None},
            '/api/map/preview': {'cities': [{}], 'nations': [{}], 'topdownBakeId': bake},
            base + 'manifest.json': {'bakeId': bake, 'partial': False,
                'inputFingerprint': {'region': None}, 'files': [{'file': name,
                    'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}
                    for name, payload in assets.items()]},
        }
        calls = []
        def fetch(name, port, path, token=''):
            calls.append((name, port, path))
            if path.startswith(base) and path[len(base):] in assets:
                return assets[path[len(base):]], 'application/octet-stream'
            return json.dumps(fixtures[path]).encode(), 'application/json'
        with patch.object(pep, 'fetch', side_effect=fetch), patch.dict(os.environ, {'PEP_SMOKE_JWT': ''}):
            self.assertFalse(pep.smoke_api())
            self.assertTrue(all(name == pep.PRIVATE[0] and port == 8081 for name, port, _ in calls))
            self.assertEqual({path for _, _, path in calls if path.startswith(base) and not path.endswith('manifest.json')},
                             {base + name for name in assets})
            files = fixtures[base + 'manifest.json']['files']
            for entry in files:
                with self.subTest(tampered=entry['file']):
                    digest = entry['sha256']
                    entry['sha256'] = '0' * 64
                    with self.assertRaisesRegex(ValueError, 'fullbundle asset mismatch'):
                        pep.smoke_api()
                    entry['sha256'] = digest
            for unsafe in ('places.json', '../places.json.gz', 'grid/L0/../L2.bin.gz',
                           'grid//L2.bin.gz', '/grid/L2.bin.gz', 'grid\\L2.bin.gz',
                           'grid/L2.bin.gz?x=1', 'grid/L2.bin.gz#x', 'grid%2FL2.bin.gz', None):
                with self.subTest(unsafe=unsafe):
                    fixtures[base + 'manifest.json']['files'] = [{**files[0], 'file': unsafe}]
                    calls.clear()
                    with self.assertRaisesRegex(ValueError, 'unsafe bundle asset path'):
                        pep.smoke_api()
                    self.assertFalse(any(path.startswith(base) and not path.endswith('manifest.json')
                                         for _, _, path in calls))


    def test_new_image_map_inputs_must_match_existing_full_bundle_before_mutation(self):
        paths = {'tilesSha256': 'data/map/province-tiles.json',
                 'worldJsonSha256': 'infra/src/main/resources/map/han-world-v3.json',
                 'roadsSha256': 'data/map/han-land-roads-v1.json'}
        bake = 'f' * 64
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pins = {}
            for key, path in paths.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(key.encode())
                pins[key] = hashlib.sha256(key.encode()).hexdigest()
            manifest = {'bakeId': bake, 'partial': False, 'mapRelease': 'province-world-20261003',
                        'inputFingerprint': {**pins, 'region': None}}
            with patch.object(pep, 'get_json', side_effect=lambda n, port, path:
                              {'topdownBakeId': bake} if path == '/api/map/preview' else manifest):
                self.assertEqual(pep.preserve_map(pep.PUBLIC[0], bake, root),
                                 {'release': 'province-world-20261003', **pins})
                for key, path in paths.items():
                    (root / path).write_bytes(b'drift')
                    with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'candidate main differs'):
                        pep.preserve_map(pep.PUBLIC[0], bake, root)
                    (root / path).write_bytes(key.encode())
                manifest['partial'] = True
                with self.assertRaisesRegex(ValueError, 'must be full'):
                    pep.preserve_map(pep.PUBLIC[0], bake, root)
                manifest['partial'] = False
                manifest['mapRelease'] = 'other-release'
                with self.assertRaisesRegex(ValueError, 'unsupported existing map release'):
                    pep.preserve_map(pep.PUBLIC[0], bake, root)

    def test_existing_compose_provenance_keeps_operator_paths_without_reading_contents(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(pep, 'ROOT', Path(temp)):
            files = [Path(temp) / name for name in ('docker-compose.server.yml', 'live.compose.json', 'operator-web-game.compose.json')]
            for path in files:
                path.write_text('unparsed operator content')
            info = {'Labels': {'com.docker.compose.project.config_files': ','.join(map(str, files))}}
            with patch.object(Path, 'read_text', side_effect=AssertionError('operator contents must not be read')):
                self.assertEqual(pep.compose_files(info), files)
            info['Labels']['com.docker.compose.project.config_files'] += ',/etc/other.json'
            with self.assertRaises(ValueError):
                pep.compose_files(info)

    def test_deleted_legacy_overlay_requires_exact_private_provenance(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(pep, 'ROOT', Path(temp)):
            base = Path(temp) / 'docker-compose.server.yml'
            base.write_text('{}')
            missing = '/tmp/pep-loop-a1b2c3d4/live.json'
            info = {'Running': True, 'Labels': {
                'com.docker.compose.project': 'opensamguk-spep',
                'com.docker.compose.service': 'game-api',
                'com.docker.compose.project.working_dir': temp,
                'com.docker.compose.project.config_files': str(base) + ',' + missing}}
            self.assertEqual(pep.compose_files(info, private=True), [base])
            with self.assertRaises(ValueError):
                pep.compose_files(info)  # PUBLIC cannot inherit legacy PRIVATE state.
            for key, value in [
                    ('com.docker.compose.project', 'opensamguk-sother'),
                    ('com.docker.compose.service', 'gateway-api'),
                    ('com.docker.compose.project.working_dir', '/other'),
                    ('com.docker.compose.project.config_files', str(base) + ',/tmp/operator/live.json'),
                    ('com.docker.compose.project.config_files', str(base) + ',/tmp/pep-loop-a1b2c3d4/paused.json'),
                    ('com.docker.compose.project.config_files', str(base) + ',/tmp/pep-loop-a1b2c3d4/live.json,/missing.json'),
                    ('com.docker.compose.project.config_files', str(base) + ',' + temp + '/missing.json')]:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    pep.compose_files({**info, 'Labels': {**info['Labels'], key: value}}, private=True)
            base.unlink()
            with self.assertRaises(ValueError):
                pep.compose_files(info, private=True)
            backing = Path(temp) / 'backing.yml'
            backing.write_text('{}')
            base.symlink_to(backing)
            with self.assertRaises(ValueError):
                pep.compose_files(info, private=True)
            base.unlink()
            base.write_text('{}')
            with tempfile.TemporaryDirectory(prefix='pep-loop-', dir='/tmp') as live:
                changed = {**info, 'Labels': {**info['Labels'],
                    'com.docker.compose.project.config_files': str(base) + ',' + live + '/live.json'}}
                with self.assertRaises(ValueError):
                    pep.compose_files(changed, private=True)  # Existing unverified stages are not replayed.

    def test_partial_or_mixed_exposure_never_selects_consumers(self):
        names = [*pep.PUBLIC, *pep.PRIVATE]
        for running in ([pep.PUBLIC[0]], [*pep.PUBLIC, pep.PRIVATE[0]], [pep.PRIVATE[1]], []):
            with self.subTest(running=running), patch.object(pep, 'inspect_container',
                    side_effect=lambda name: {'Running': name in running}), self.assertRaises(ValueError):
                pep.consumers(names)
        for expected in (pep.PUBLIC, pep.PRIVATE):
            with patch.object(pep, 'inspect_container', side_effect=lambda name: {'Running': name in expected}):
                self.assertEqual(pep.consumers(names), expected)



class PepResumeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.args = argparse.Namespace(server='pep', source=pep.RESUME_SOURCE, checkout=self.runtime,
                                       helper_source='f' * 40, helper_checkout=Path(pep.__file__).resolve().parents[2])
        self.web = {'ports': {'3001/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '3101'}]},
                    'aliases': {'opensamguk-net': ['spep-web-game', 'web-game']}}
        self.args.web_declaration = 'docker66-public-3101'
        self.declaration = {'image': pep.RESUME_IMAGES['web-game']['ref'], 'name': pep.PUBLIC[1], 'ports': [{'published': '3101', 'target': 3001, 'protocol': 'tcp'}],
                            'expose': ['3001'], 'networks': {'opensamguk-net': {'aliases': []}}}
        self.root_patch = patch.object(pep, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        for name in ('docker-compose.server.yml', 'operator-web-game.compose.json', 'pep-loop.compose.json'):
            (self.root / name).write_text('Compose alone consumes this file')
        self.interrupted = self.root / '.pep-loop-incomplete'
        self.marker = self.root / '.pep-loop-source'
        self.interrupted.write_text('reset\n')
        self.pins = {'release': 'province-world-20261003'}
        for key, relative in {'tilesSha256': 'data/map/province-tiles.json',
                              'worldJsonSha256': 'infra/src/main/resources/map/han-world-v3.json',
                              'roadsSha256': 'data/map/han-land-roads-v1.json'}.items():
            path = self.runtime / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(key.encode())
            self.pins[key] = hashlib.sha256(key.encode()).hexdigest()
        manifest = self.root / 'data/topdown/pep' / pep.RESUME_BAKE / 'manifest.json'
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps({'bakeId': pep.RESUME_BAKE, 'partial': False,
                                       'mapRelease': self.pins['release'],
                                       'inputFingerprint': {**self.pins, 'region': None}}))
        self.publication = {'state': 'PUBLIC', 'publicly_visible': True, 'revision': 3,
                            'generation': 0, 'scenario_code': 'scenario_3190'}
        self.world = deepcopy(WORLD)
        self.infos = {}
        for name, role in zip((pep.PUBLIC[0], pep.ENGINE, *pep.DATA),
                              ('game-api', 'game-engine', 'game-postgres', 'game-redis')):
            self.infos[name] = self.info(role, name in pep.DATA)
        for name, volume in zip(pep.DATA, pep.VOLUMES):
            self.infos[name]['Mounts'] = [{'Type': 'volume', 'Name': volume}]
        self.infos[pep.PUBLIC[0]]['Mounts'] = [{'Type': 'bind', 'Source': str(self.root / 'data/topdown/pep'),
                                             'Destination': '/app/data/map/topdown', 'RW': False}]
        self.infos['opensamguk-deployer'] = {'Running': False}
        self.commands = []
        self.effects = None
        self.patches = [patch.object(pep, 'RESUME_PINS', self.pins),
                        patch.object(pep, 'resume_sources'),
                        patch.object(pep, 'inspect_container', side_effect=lambda name: deepcopy(self.infos[name])),
                        patch.object(pep, 'command', side_effect=self.command),
                        patch.object(pep, 'get_json', side_effect=lambda name, *rest: TICK if name == pep.ENGINE else {'status': 'UP'}),
                        patch.object(pep, 'preserve_map', return_value=self.pins),
                        patch.object(pep, 'smoke_api', return_value=False),
                        patch.object(pep, 'scenario', return_value=('scenario_3190', 'scenario title')),
                        patch.object(pep, 'poll', side_effect=lambda check, seconds: check()),
                        patch.object(pep, 'summary'), patch.object(pep.fcntl, 'flock'),
                        patch.object(pep, 'open', create=True), patch.object(pep.subprocess, 'run'),
                        patch.object(pep, 'compose_web_projection', side_effect=lambda *args: deepcopy(self.declaration))]
        self.mocks = [p.start() for p in self.patches]
        for p in reversed(self.patches):
            self.addCleanup(p.stop)

    def info(self, role, running):
        return {'Running': running, 'Ports': {}, 'Networks': {'opensamguk-net': {'Aliases': []}}, 'Mounts': [],
                'Labels': {'com.docker.compose.project': 'opensamguk-spep', 'com.docker.compose.service': role,
                           'com.docker.compose.project.config_files': str(self.root / 'docker-compose.server.yml')
                           + ',' + str(self.root / 'pep-loop.compose.json')}}

    def settings(self, name):
        if name == pep.PUBLIC[1]:
            return {'SERVER_ID': 'pep', 'GAME_API_URL': 'http://' + pep.PUBLIC[0] + ':8081'}
        values = {'SCENARIO_CODE': 'scenario_3190', 'SCENARIO_SEED_ENABLED': 'true', 'SCENARIO_DIR': '',
                  'SCENARIO_LOOKUP_DIR': '', 'OPENSAMGUK_WORLD_ID': '1', 'SERVER_GENERATION': '0',
                  'RESET_TURNTERM': '60', 'RESET_MAXGENERAL': '50', 'RESET_FIRST_TURN': 'immediate',
                  'RESET_BLOCK_GENERAL_CREATE': '1'}
        if name == pep.ENGINE:
            values['JAVA_OPTS'] = '-XX:+UseG1GC -Dopensamguk.daemon.enabled=true'
        else:
            values.update(SERVER_ID='pep', TOPDOWN_MAP_ROOT='/app/data/map/topdown', TOPDOWN_BAKE_ID=pep.RESUME_BAKE)
        return values

    def command(self, args, **kwargs):
        self.commands.append(args)
        if self.effects:
            self.effects(args)
        if args[:3] == ['docker', 'ps', '-a']:
            return '\n'.join(self.infos).encode()
        if args[:3] == ['docker', 'image', 'inspect']:
            expected = next(v for v in pep.RESUME_IMAGES.values() if v['ref'] == args[-1])
            return json.dumps({'Id': expected['config'], 'Os': 'linux', 'Architecture': 'amd64',
                               'RepoDigests': [expected['ref']], 'Revision': pep.RESUME_SOURCE}).encode()
        if args[:3] == ['docker', 'inspect', '--format']:
            template, name = args[-2:]
            if template == '{{.Id}}': return b'1' * 64
            if '.State.Health' in template:
                return b'healthy'
            if '.HostConfig.PortBindings' in template:
                ports = self.infos[name]['Ports']
                return json.dumps({'Bindings': ports, 'Exposed': {key: {} for key in ports}}).encode()
            if '.Config.Env' in template:
                # Each Go-template println adds LF; docker inspect adds one final LF.
                return (''.join(k + '=' + v + '\n' for k, v in self.settings(name).items()) + '\n').encode()
            role = {pep.PUBLIC[0]: 'game-api', pep.ENGINE: 'game-engine', pep.PUBLIC[1]: 'web-game'}[name]
            return json.dumps({'Id': pep.RESUME_IMAGES[role]['config'], 'Ref': pep.RESUME_IMAGES[role]['ref']}).encode()
        if args[:3] == ['docker', 'exec', '-i']:
            self.assertIn(b'BEGIN READ ONLY', kwargs['stdin'])
            self.assertIn(b'ROLLBACK', kwargs['stdin'])
            return json.dumps(self.world if args[3] == pep.DATA[0] else self.publication).encode()
        if 'config' in args:
            # Compose includes dependency images even when web-game is named.
            return '\n'.join([pep.RESUME_IMAGES['game-api']['ref'], pep.RESUME_IMAGES['web-game']['ref'],
                              'postgres:fixture', 'redis:fixture', pep.RESUME_IMAGES['game-engine']['ref']]).encode()
        if args[:2] == ['docker', 'start']:
            for name in args[2:]:
                self.infos[name]['Running'] = True
        if 'up' in args:
            self.infos[pep.PUBLIC[1]] = self.info('web-game', True)
            self.infos[pep.PUBLIC[1]]['Ports'] = deepcopy(self.web['ports'])
            self.infos[pep.PUBLIC[1]]['Networks'] = {key: {'Aliases': value} for key, value in self.web['aliases'].items()}
        return b''

    def assert_untouched_checkpoint(self):
        self.assertEqual(self.interrupted.read_text(), 'reset\n')
        self.assertFalse(self.marker.exists())
        self.assertTrue(all(self.infos[name]['Running'] for name in pep.DATA))

    def assert_no_mutation(self):
        self.assertFalse(any(args[:2] == ['docker', 'start'] or 'up' in args for args in self.commands))
        self.mocks[12].assert_not_called()
        self.assert_untouched_checkpoint()

    def test_resume_preserves_seed_and_images_and_finalizes_only_after_smoke(self):
        data = {name: deepcopy(self.infos[name]) for name in pep.DATA}
        def smoke(*args):
            self.assert_untouched_checkpoint()
            self.assertTrue(all(self.infos[name]['Running'] for name in (*pep.PUBLIC, pep.ENGINE)))
            return False
        self.mocks[6].side_effect = smoke
        pep.resume_reset(self.args)
        self.assertEqual(self.marker.read_text().strip(), pep.RESUME_SOURCE)
        self.assertFalse(self.interrupted.exists())
        self.assertEqual({name: self.infos[name] for name in pep.DATA}, data)
        self.assertFalse(any(set(args) & {'pull', 'build', 'rm', 'down', 'reset', 'prune'} for args in self.commands))
        up = next(args for args in self.commands if 'up' in args)
        self.assertEqual(up[-8:], ['up', '-d', '--no-deps', '--no-build', '--no-recreate', '--pull', 'never', 'web-game'])
        self.assertEqual(self.mocks[1].call_count, 2)
        self.mocks[10].assert_called_once_with(self.mocks[11].return_value.__enter__.return_value,
                                              pep.fcntl.LOCK_EX | pep.fcntl.LOCK_NB)

    def test_existing_stopped_web_is_started_without_compose_recreation(self):
        self.infos[pep.PUBLIC[1]] = self.info('web-game', False)
        self.infos[pep.PUBLIC[1]]['Ports'] = deepcopy(self.web['ports'])
        self.infos[pep.PUBLIC[1]]['Networks'] = {key: {'Aliases': value} for key, value in self.web['aliases'].items()}
        pep.resume_reset(self.args)
        self.assertIn(['docker', 'start', pep.PUBLIC[1]], self.commands)
        self.assertFalse(any('up' in args for args in self.commands))
        self.assertFalse(self.interrupted.exists())

    def test_selected_settings_accept_docker_final_lf_and_blank_lines_without_losing_empty_values(self):
        for name in (pep.PUBLIC[0], pep.ENGINE, pep.PUBLIC[1]):
            expected = self.settings(name)
            output = ''.join(k + '=' + v + '\n' for k, v in expected.items())
            for raw in (output, output + '\n', '\n' + output + '\n\n', output.replace('\n', '\n\n')):
                with self.subTest(name=name, ending=repr(raw[-4:])):
                    with patch.object(pep, 'command', return_value=raw.encode()):
                        self.assertEqual(pep.resume_settings(name), expected)
        self.assertEqual(self.settings(pep.PUBLIC[0])['SCENARIO_DIR'], '')
        self.assertEqual(self.settings(pep.PUBLIC[0])['SCENARIO_LOOKUP_DIR'], '')

    def test_selected_settings_keep_rejecting_nonempty_malformed_keys_and_duplicates(self):
        valid = ''.join(k + '=' + v + '\n' for k, v in self.settings(pep.PUBLIC[0]).items())
        for invalid in (' ', 'SCENARIO_CODE', '=fixture', 'NOT_ALLOWED=fixture', 'SCENARIO_CODE=scenario_3190'):
            with self.subTest(invalid=invalid):
                with patch.object(pep, 'command', return_value=(valid + '\n' + invalid + '\n\n').encode()):
                    with self.assertRaisesRegex(ValueError, 'invalid selected pep settings'):
                        pep.resume_settings(pep.PUBLIC[0])

    def test_selected_settings_empty_or_drifted_docker_output_still_rejects(self):
        for key, value in (('SCENARIO_CODE', 'scenario_3191'), ('RESET_MAXGENERAL', '49'),
                           ('RESET_BLOCK_GENERAL_CREATE', '0'), ('RESET_TURNTERM', '59')):
            settings = self.settings(pep.ENGINE)
            settings[key] = value
            raw = ''.join(k + '=' + v + '\n' for k, v in settings.items()) + '\n'
            with self.subTest(key=key), patch.object(pep, 'command', return_value=raw.encode()):
                with self.assertRaisesRegex(ValueError, 'approved seeded pep settings changed'):
                    pep.resume_settings(pep.ENGINE)
        for raw in (b'', b'\n\n'):
            with self.subTest(raw=raw), patch.object(pep, 'command', return_value=raw):
                with self.assertRaisesRegex(ValueError, 'approved seeded pep settings changed'):
                    pep.resume_settings(pep.PUBLIC[0])

    def test_selected_container_setting_drift_rejects_before_start(self):
        settings = self.settings
        def drift(name):
            result = settings(name)
            if name == pep.ENGINE: result['RESET_BLOCK_GENERAL_CREATE'] = '0'
            return result
        with patch.object(self, 'settings', side_effect=drift):
            with self.assertRaisesRegex(ValueError, 'seeded pep settings changed'): pep.resume_reset(self.args)
        self.assert_no_mutation()

    def test_container_and_image_ids_can_use_either_approved_digest_representation(self):
        expected = pep.RESUME_IMAGES['game-api']
        for image_id, container_id in ((expected['manifest'], expected['config']),
                                       (expected['config'], expected['manifest'])):
            actual = {'Id': image_id, 'Os': 'linux', 'Architecture': 'amd64',
                      'RepoDigests': [expected['ref']], 'Revision': pep.RESUME_SOURCE}
            bound = {'Id': container_id, 'Ref': expected['ref']}
            with patch.object(pep, 'command', side_effect=[json.dumps(actual).encode(), json.dumps(bound).encode()]):
                pep.resume_image('game-api', pep.PUBLIC[0])
            for key, value in [('Id', 'sha256:' + '9' * 64), ('Ref', pep.REGISTRY + '@sha256:' + '9' * 64)]:
                with patch.object(pep, 'command', side_effect=[json.dumps(actual).encode(),
                                  json.dumps({**bound, key: value}).encode()]):
                    with self.assertRaises(ValueError): pep.resume_image('game-api', pep.PUBLIC[0])

    def test_wrong_target_source_and_unapproved_web_declaration_reject_before_mutation(self):
        for field, value in [('server', 'PEP'), ('source', SOURCE), ('web_declaration', 'unknown')]:
            with self.subTest(field=field):
                original = getattr(self.args, field)
                setattr(self.args, field, value)
                with self.assertRaises(ValueError):
                    pep.resume_reset(self.args)
                setattr(self.args, field, original)
                self.assert_no_mutation()

    def test_incomplete_marker_cursor_world_publication_and_data_drift_reject(self):
        cases = [('marker', None), ('cursor', None), ('world', None), ('publication', None), ('data', None)]
        for kind, _ in cases:
            with self.subTest(kind=kind):
                if kind == 'marker': self.interrupted.write_text('refresh\n')
                if kind == 'cursor': self.marker.write_text(pep.RESUME_SOURCE)
                if kind == 'world': self.world['env_block'] = 0
                if kind == 'publication': self.publication['revision'] = 4
                if kind == 'data': self.infos[pep.DATA[0]]['Mounts'][0]['Name'] = 'another-volume'
                with self.assertRaises(ValueError): pep.resume_reset(self.args)
                if kind == 'marker': self.interrupted.write_text('reset\n')
                if kind == 'cursor': self.assertEqual(self.marker.read_text(), pep.RESUME_SOURCE); self.marker.unlink()
                if kind == 'world': self.world['env_block'] = 1
                if kind == 'publication': self.publication['revision'] = 3
                if kind == 'data': self.infos[pep.DATA[0]]['Mounts'][0]['Name'] = pep.VOLUMES[0]
                self.assert_no_mutation()

    def test_main_admission_unknown_or_red_and_image_map_provenance_drift_reject(self):
        for guard in ('resume_sources', 'resume_image', 'map_pins', 'compose_files'):
            with self.subTest(guard=guard), patch.object(pep, guard, side_effect=ValueError('guard denied')):
                with self.assertRaises(ValueError): pep.resume_reset(self.args)
                self.assert_no_mutation()
        with patch.object(pep, 'resume_sources', side_effect=pep.AdmissionDeferred('unavailable')):
            with self.assertRaises(pep.AdmissionDeferred): pep.resume_reset(self.args)
            self.assert_no_mutation()

    def test_mid_recovery_failures_stop_only_three_consumers_and_preserve_checkpoint(self):
        for stage in ('start', 'smoke', 'web-exposure', 'tick'):
            with self.subTest(stage=stage):
                for name in (pep.PUBLIC[0], pep.ENGINE): self.infos[name]['Running'] = False
                self.infos.pop(pep.PUBLIC[1], None)
                def effect(args):
                    if stage == 'start' and args[:2] == ['docker', 'start']: raise ValueError('partial start failed')
                self.effects = effect
                with patch.object(pep, 'smoke_api', side_effect=ValueError('smoke failed') if stage == 'smoke' else None), \
                     patch.object(pep, 'check_tick', side_effect=ValueError('tick failed') if stage == 'tick' else None):
                    if stage == 'web-exposure': self.web['ports']['3001/tcp'][0]['HostPort'] = '3102'
                    with self.assertRaises(ValueError): pep.resume_reset(self.args)
                    self.web['ports']['3001/tcp'][0]['HostPort'] = '3101'
                self.assert_untouched_checkpoint()
                self.assertEqual([call.args[0][-1] for call in self.mocks[12].call_args_list[-3:]],
                                 [*pep.PUBLIC, pep.ENGINE])

    def test_finalize_unlink_failure_restores_incomplete_cursor(self):
        unlink = Path.unlink
        def fail_interrupted(path, *args, **kwargs):
            if path == self.interrupted: raise OSError('marker unavailable')
            return unlink(path, *args, **kwargs)
        with patch.object(Path, 'unlink', fail_interrupted):
            with self.assertRaises(OSError): pep.resume_reset(self.args)
        self.assert_untouched_checkpoint()

    def test_web_image_is_selected_from_projection_with_dependency_images_before_start(self):
        pep.resume_preflight(self.args)
        self.assert_no_mutation()
        self.assertFalse(any('--images' in args for args in self.commands))

    def test_wrong_or_missing_selected_web_image_rejects_before_start(self):
        for wrong in (None, pep.RESUME_IMAGES['game-api']['ref'], 'other:web'):
            with self.subTest(wrong=wrong):
                self.declaration['image'] = wrong
                with self.assertRaisesRegex(ValueError, 'approved web Compose image changed'):
                    pep.resume_preflight(self.args)
                self.assert_no_mutation()

    def test_fixed_web_declaration_rejects_new_ports_network_and_extra_alias(self):
        pep.check_resume_web_declaration(self.declaration)
        for field, value in [('name', 'other-web'), ('ports', [{'published': '3102', 'target': 3001}]),
                             ('ports', [{'published': '3101', 'target': 3001, 'host_ip': '127.0.0.1'}]),
                             ('networks', {'another-net': {'aliases': []}}),
                             ('networks', {'opensamguk-net': {'aliases': ['extra']}})]:
            with self.subTest(field=field, value=value):
                declared = {**self.declaration, field: value}
                with self.assertRaises(ValueError): pep.check_resume_web_declaration(declared)
        self.declaration['ports'][0]['published'] = '3102'
        with self.assertRaises(ValueError): pep.resume_reset(self.args)
        self.assert_no_mutation()

    def test_default_bind_notation_is_equal_but_port_or_specific_bind_drift_is_not(self):
        before = {'8081/tcp': [{'HostIp': '', 'HostPort': '31902'}]}
        after = {'8081/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '31902'},
                              {'HostIp': '::', 'HostPort': '31902'}]}
        self.assertEqual(pep.normalize_resume_ports(before), pep.normalize_resume_ports(after))
        for binding in ({'HostIp': '0.0.0.0', 'HostPort': '31903'},
                        {'HostIp': '127.0.0.1', 'HostPort': '31902'}):
            self.assertNotEqual(pep.normalize_resume_ports(before), pep.normalize_resume_ports({'8081/tcp': [binding]}))

    def test_compose_projection_emits_only_approved_public_web_fields(self):
        source = {'services': {'web-game': {'image': pep.RESUME_IMAGES['web-game']['ref'], 'container_name': pep.PUBLIC[1], 'expose': ['3001'],
                  'ports': self.declaration['ports'], 'networks': {'opensamguk-net': {}},
                  'environment': {'FAKE_PRIVATE_SETTING': 'fixture-do-not-project'}},
                  'game-api': {'image': pep.RESUME_IMAGES['game-api']['ref']},
                  'game-engine': {'image': pep.RESUME_IMAGES['game-engine']['ref']},
                  'game-postgres': {'image': 'postgres:fixture', 'environment': {'FAKE_PRIVATE_SETTING': 'other-fixture'}},
                  'game-redis': {'image': 'redis:fixture'}}}
        process = unittest.mock.MagicMock()
        process.stdout = io.BytesIO(json.dumps(source).encode())
        process.wait.return_value = 0
        process.poll.return_value = 0
        def select(args, **kwargs):
            output = io.StringIO()
            with patch.object(sys, 'stdin', kwargs['stdin']), patch.object(sys, 'stdout', output):
                exec(compile(args[3], '<public-web-projection>', 'exec'), {})
            return subprocess.CompletedProcess(args, 0, stdout=output.getvalue().encode())
        with patch.object(pep, 'compose_web_projection', wraps=PepResumeTests.original_projection), \
             patch.object(pep.subprocess, 'Popen', return_value=process) as start, \
             patch.object(pep.subprocess, 'run', side_effect=select):
            result = pep.compose_web_projection(['docker', 'compose'], {'SERVER_ID': 'pep'})
            self.assertEqual(result, self.declaration)
            self.assertNotIn('fixture-do-not-project', json.dumps(result))
            self.assertNotIn('other-fixture', json.dumps(result))
            for dependency in (pep.RESUME_IMAGES['game-api']['ref'], pep.RESUME_IMAGES['game-engine']['ref'],
                               'postgres:fixture', 'redis:fixture'):
                self.assertNotIn(dependency, json.dumps(result))
            self.assertIn('--no-env-resolution', start.call_args.args[0])

    def test_busy_shared_lock_never_reaches_preflight_or_mutation(self):
        self.mocks[10].side_effect = BlockingIOError('lock busy')
        with self.assertRaises(BlockingIOError): pep.resume_reset(self.args)
        self.mocks[1].assert_not_called()
        self.assert_no_mutation()

    def test_real_tick_contract_and_persisted_turn_failure_cannot_finalize(self):
        for field, value in [('successfulTicks', 0), ('failedTicks', 1), ('loopAlive', False),
                             ('recoveryReady', False), ('paused', True), ('persisted', None)]:
            with self.subTest(field=field):
                for name in (pep.PUBLIC[0], pep.ENGINE): self.infos[name]['Running'] = False
                self.infos.pop(pep.PUBLIC[1], None)
                ticks = {**TICK, field: value}
                self.mocks[4].side_effect = lambda name, *rest: ticks if name == pep.ENGINE else {'status': 'UP'}
                if field == 'persisted': self.world['last_turn'] = None
                with self.assertRaises(ValueError): pep.resume_reset(self.args)
                self.world['last_turn'] = WORLD['last_turn']
                self.assert_untouched_checkpoint()

    def test_stopped_container_ports_use_durable_declarations(self):
        info = self.infos[pep.PUBLIC[0]]
        info['Ports'] = {}
        expected = {'Bindings': {'8081/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '31902'}]},
                    'Exposed': {'8081/tcp': {}}}
        with patch.object(pep, 'command', return_value=json.dumps(expected).encode()):
            self.assertEqual(pep.resume_exposure(pep.PUBLIC[0], info)['ports'], expected['Bindings'])
        expected['Bindings']['8081/tcp'][0]['HostPort'] = ''
        with patch.object(pep, 'command', return_value=json.dumps(expected).encode()):
            with self.assertRaises(ValueError): pep.resume_exposure(pep.PUBLIC[0], info)

    def test_helper_and_runtime_are_separate_clean_main_checkouts(self):
        # Use the real local file locations but fake only CI/Git I/O.
        with patch.object(pep, 'resume_sources', wraps=PepResumeTests.original_sources), \
             patch.object(pep, 'source_git') as git, patch.object(pep, 'admit_snapshot') as admit, \
             patch.object(pep, 'scenario', return_value=('scenario_3190', 'scenario title')) as selected:
            selected.__code__ = PepResumeTests.original_scenario.__code__
            pep.resume_sources(self.args)
            self.assertEqual(admit.call_args_list[0].args, (self.args.helper_checkout, self.args.helper_source))
            self.assertEqual(admit.call_args_list[1].args, (self.runtime, pep.RESUME_SOURCE, pep.BASELINE))
            self.assertIn(unittest.mock.call(self.args.helper_checkout, 'merge-base', '--is-ancestor',
                                            pep.RESUME_SOURCE, self.args.helper_source), git.call_args_list)
            self.assertIn(unittest.mock.call(self.args.helper_checkout, 'diff', '--quiet', self.args.helper_source,
                                            '--', 'tools/ops/pep_loop.py', 'tools/ops/pep_scenarios.py'), git.call_args_list)
            self.args.helper_checkout = self.runtime
            with self.assertRaisesRegex(ValueError, 'reviewed helper checkout'): pep.resume_sources(self.args)

    original_sources = staticmethod(pep.resume_sources)
    original_scenario = staticmethod(pep.scenario)
    original_projection = staticmethod(pep.compose_web_projection)


class PepSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-qb', 'main')
        self.git('config', 'user.name', 'offline')
        self.git('config', 'user.email', 'offline@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.previous = self.commit('previous')
        self.source = self.commit('snapshot')
        self.head = self.commit('new main')
        self.git('remote', 'add', 'origin', str(self.repo))
        self.git('checkout', '-q', '--detach', self.source)
        self.states = {self.source: {'green': True, 'red': False},
                       self.head: {'green': False, 'red': False}}
        # Exercise real Git lineage locally, with no real token or network remote.
        git_env = patch.object(pep, 'github_git_env', return_value={'PATH': os.defpath})
        git_env.start()
        self.addCleanup(git_env.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args],
                                       stderr=subprocess.DEVNULL).decode().strip()

    def commit(self, text):
        (self.repo / 'change').write_text(text)
        self.git('add', '.')
        self.git('commit', '-qm', text)
        return self.git('rev-parse', 'HEAD')

    def admit(self):
        with patch.object(pep, 'ci_state', side_effect=lambda sha: self.states[sha]):
            return pep.admit_snapshot(self.repo, self.source, self.previous)

    def test_newer_main_preserves_fixed_snapshot_and_allows_pending(self):
        self.assertEqual(self.admit(), self.head)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.source)

    def test_backwards_applied_cursor_checkout_mismatch_and_divergent_main_are_rejected(self):
        self.previous = self.head
        with self.assertRaises(ValueError):
            self.admit()
        self.previous = self.source
        self.git('checkout', '-q', '--detach', self.head)
        with self.assertRaisesRegex(ValueError, 'checkout differs'):
            self.admit()
        self.git('checkout', '-q', '--detach', self.previous)
        self.git('checkout', '-qB', 'main', self.git('rev-parse', self.source + '^'))
        self.commit('fork')
        self.git('checkout', '-q', '--detach', self.source)
        with self.assertRaises(ValueError):
            self.admit()

    def test_source_not_green_defers_and_confirmed_red_requires_descendant_green(self):
        self.states[self.source] = {'green': False, 'red': False}
        with self.assertRaisesRegex(pep.AdmissionDeferred, 'snapshot whole CI'):
            self.admit()
        self.states[self.source] = {'green': True, 'red': True}
        with self.assertRaisesRegex(ValueError, 'confirmed main CI RED'):
            self.admit()
        self.states[self.head] = {'green': True, 'red': False}
        self.assertEqual(self.admit(), self.head)
        self.states[self.head] = {'green': True, 'red': True}
        with self.assertRaisesRegex(ValueError, 'confirmed main CI RED'):
            self.admit()

    def test_main_advance_during_observation_is_revalidated_and_new_red_blocks(self):
        self.git('checkout', '-q', 'main')
        newer = self.commit('newer')
        self.git('checkout', '-q', '--detach', self.source)
        self.states[newer] = {'green': False, 'red': True}
        with patch.object(pep, 'remote_main', side_effect=[self.head, newer, newer, newer]):
            with self.assertRaisesRegex(ValueError, 'confirmed main CI RED'):
                self.admit()
        self.states[newer] = {'green': False, 'red': False}
        with patch.object(pep, 'remote_main', side_effect=[self.head, newer, newer, newer]):
            self.assertEqual(self.admit(), newer)
        with patch.object(pep, 'remote_main', side_effect=[self.head, newer] * 3):
            with self.assertRaisesRegex(pep.AdmissionDeferred, 'kept changing'):
                self.admit()

    def test_observation_failures_defer_without_changing_snapshot(self):
        with patch.object(pep, 'github_json', side_effect=pep.AdmissionDeferred('unavailable')):
            with self.assertRaises(pep.AdmissionDeferred):
                pep.admit_snapshot(self.repo, self.source, self.previous)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.source)


class PepCiTests(unittest.TestCase):
    def run_fixture(self, conclusion='success', status='completed', attempt=1, **extra):
        return dict(id=42, head_sha=SOURCE, event='push', head_branch='main',
                    head_repository={'full_name': pep.REPOSITORY}, run_attempt=attempt,
                    status=status, conclusion=conclusion, **extra)

    def jobs(self, names=None):
        return [dict(name=name, head_sha=SOURCE, run_id=42, status='completed',
                     conclusion='success', steps=[]) for name in (names or sorted(pep.REQUIRED_CI))]

    def state(self, run=None, attempts=None, extra_runs=(), current=None):
        run = run or self.run_fixture()
        attempts = attempts or [(run, self.jobs())]
        def api(path, **query):
            if path.endswith('/runs') and 'workflows/' in path:
                self.assertEqual(query['head_sha'], SOURCE)
                self.assertEqual((query['branch'], query['event']), ('main', 'push'))
                runs = [run, *extra_runs]
                return {'total_count': len(runs), 'workflow_runs': runs}
            if path == 'actions/runs/42':
                return current or run
            attempt = int(path.split('/attempts/')[1].split('/')[0])
            observed, jobs = attempts[attempt - 1]
            return {'total_count': len(jobs), 'jobs': jobs} if path.endswith('/jobs') else observed
        with patch.object(pep, 'github_json', side_effect=api):
            return pep.ci_state(SOURCE)

    def test_whole_and_required_six_are_green_while_conditional_skip_is_accepted(self):
        jobs = self.jobs() + [{**self.jobs()[0], 'name': 'optional scenario', 'conclusion': 'skipped'}]
        self.assertEqual(self.state(attempts=[(self.run_fixture(), jobs)]), {'green': True, 'red': False})

    def test_missing_skipped_pending_failed_required_or_whole_ci_never_admits(self):
        for changed in ('missing', 'skipped', None, 'failure'):
            jobs = self.jobs()
            if changed == 'missing':
                jobs.pop()
            else:
                jobs[0]['conclusion'] = changed
            with self.subTest(changed=changed):
                self.assertFalse(self.state(attempts=[(self.run_fixture(), jobs)])['green'])
        for status, conclusion in [('in_progress', None), ('completed', 'failure'), ('completed', 'cancelled')]:
            with self.subTest(status=status, conclusion=conclusion):
                self.assertFalse(self.state(run=self.run_fixture(conclusion, status))['green'])

    def test_actual_child_failure_is_red_even_when_whole_ci_claims_green(self):
        for scope in ('job', 'step'):
            jobs = self.jobs() + [{**self.jobs()[0], 'name': 'child'}]
            if scope == 'job':
                jobs[-1]['conclusion'] = 'failure'
            else:
                jobs[-1]['steps'] = [{'conclusion': 'timed_out'}]
            with self.subTest(scope=scope):
                self.assertEqual(self.state(attempts=[(self.run_fixture(), jobs)]), {'green': False, 'red': True})

    def test_prior_attempt_red_survives_pending_cancel_and_same_sha_green_rerun(self):
        prior = self.run_fixture('failure')
        jobs = self.jobs()
        jobs[0]['conclusion'] = 'failure'
        for status, conclusion in [('in_progress', None), ('completed', 'cancelled'), ('completed', 'success')]:
            latest = self.run_fixture(conclusion, status, attempt=2)
            with self.subTest(conclusion=conclusion):
                state = self.state(run=latest, attempts=[(prior, jobs), (latest, self.jobs())])
                self.assertTrue(state['red'])
                self.assertEqual(state['green'], conclusion == 'success')

    def test_partial_rerun_uses_prior_successful_required_jobs(self):
        prior = self.run_fixture('cancelled')
        latest = self.run_fixture(attempt=2)
        self.assertEqual(self.state(run=latest, attempts=[(prior, self.jobs()), (latest, self.jobs()[:1])]),
                         {'green': True, 'red': False})

    def test_superseded_cancel_and_pending_are_not_red_but_timeout_is(self):
        for status, conclusion in [('in_progress', None), ('completed', 'cancelled')]:
            run = self.run_fixture(conclusion, status)
            jobs = [{**job, 'conclusion': 'cancelled'} for job in self.jobs()]
            with self.subTest(conclusion=conclusion):
                self.assertEqual(self.state(run=run, attempts=[(run, jobs)]), {'green': False, 'red': False})
        self.assertTrue(self.state(run=self.run_fixture('timed_out'))['red'])

    def test_non_main_fork_and_unrelated_runs_are_not_main_ci_red(self):
        for change in ({'event': 'workflow_dispatch'}, {'head_branch': 'feature'},
                       {'head_sha': 'b' * 40}, {'head_repository': {'full_name': 'fork/opensamguk'}}):
            with self.subTest(change=change):
                self.assertEqual(self.state(extra_runs=[{**self.run_fixture('failure'), **change}]),
                                 {'green': True, 'red': False})

    def test_rerun_race_job_source_mismatch_and_incomplete_history_defer(self):
        with self.assertRaises(pep.AdmissionDeferred):
            self.state(current=self.run_fixture(attempt=2))
        jobs = self.jobs()
        jobs[0]['head_sha'] = 'b' * 40
        with self.assertRaises(pep.AdmissionDeferred):
            self.state(attempts=[(self.run_fixture(), jobs)])
        with patch.object(pep, 'github_json', return_value={'total_count': 1, 'jobs': []}):
            with self.assertRaises(pep.AdmissionDeferred):
                pep.github_items('actions/runs/42/attempts/1/jobs', 'jobs')

    def test_pagination_keeps_failures_outside_first_page(self):
        with patch.object(pep, 'github_json', side_effect=[{'total_count': 2, 'jobs': [{'conclusion': 'success'}]},
                                                       {'total_count': 2, 'jobs': [{'conclusion': 'failure'}]}]):
            self.assertTrue(any(pep.failed_ci(job) for job in pep.github_items('jobs', 'jobs')))

    def test_api_unavailable_sanitizes_credentials_and_response_errors(self):
        with patch.dict(os.environ, {'GITHUB_TOKEN': 'offline-test-token'}), \
                patch.object(pep.urllib.request, 'urlopen', side_effect=OSError('offline-test-token raw response')):
            with self.assertRaisesRegex(pep.AdmissionDeferred, '^main CI observation unavailable$'):
                pep.github_json('actions/workflows/ci.yml/runs')

    def test_private_git_auth_is_exact_remote_only_in_child_env_not_args_or_disk(self):
        with patch.dict(os.environ, {'GITHUB_TOKEN': 'offline-test-token', 'PEP_SMOKE_JWT': 'offline-jwt'}), \
                patch.object(pep, 'command', side_effect=[('https://github.com/' + pep.REPOSITORY + '.git').encode(), b'']) as cmd:
            pep.source_git(Path('.'), 'fetch', '--no-tags', 'origin', SOURCE, network=True)
            arguments, options = cmd.call_args
            self.assertFalse(any('offline-test-token' in arg for arg in arguments[0]))
            self.assertNotIn('GITHUB_TOKEN', options['env'])
            self.assertNotIn('PEP_SMOKE_JWT', options['env'])
            self.assertEqual(options['env']['GIT_CONFIG_KEY_0'],
                             'http.https://github.com/' + pep.REPOSITORY + '.git.extraheader')
            # Verify Git's URL matching with a synthetic credential, without printing it.
            header = subprocess.check_output(['git', 'config', '--get-urlmatch', 'http.extraheader',
                                               'https://github.com/' + pep.REPOSITORY + '.git'], env=options['env'])
            self.assertTrue(header.startswith(b'AUTHORIZATION: basic '))
        with patch.object(pep, 'command', return_value=b'https://example.invalid/other.git'), \
                self.assertRaisesRegex(ValueError, 'exact repository'):
            pep.github_git_env(Path('.'))

    def test_privileged_fetch_uses_runner_owner_and_never_writes_root_git_objects(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(pep.os, 'geteuid', return_value=0), \
                patch.object(pep, 'github_git_env', return_value={'PATH': os.defpath}), \
                patch.object(pep, 'command', return_value=b'') as cmd:
            checkout = Path(temp)
            with patch.object(Path, 'stat') as stat:
                stat.return_value.st_uid = 1001
                stat.return_value.st_gid = 1002
                pep.source_git(checkout, 'fetch', '--no-tags', '--no-write-fetch-head', 'origin', SOURCE, network=True)
            self.assertEqual((cmd.call_args.kwargs['user'], cmd.call_args.kwargs['group']), (1001, 1002))
            self.assertEqual(cmd.call_args.kwargs['extra_groups'], [])

    def test_actual_workflow_python_invocation_cannot_create_root_import_cache(self):
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/pep-loop.yml'
        apply_run = workflow.read_text().split('sudo -n --preserve-env=', 1)[1]
        import shlex
        invocation = shlex.split(apply_run.split('python3', 1)[1].split('tools/ops/pep_loop.py', 1)[0])
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp) / 'pep_scenarios.py').write_text('approved = True\n')
            run = subprocess.run([sys.executable, *invocation, '-c',
                                  'import pep_scenarios; assert pep_scenarios.approved'], cwd=temp,
                                 env={'PATH': os.defpath}, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr.decode())
            self.assertFalse((Path(temp) / '__pycache__').exists())


class PepOperationTests(unittest.TestCase):
    """Simulated commands check destructive boundaries and failure closure end-to-end."""
    def simulate(self, mode, fail_smoke=False, public=False, current='scenario_3190', selection=None,
                 operation='auto', legacy=False, settings=None, world_scenario=None, hold=None,
                 stable_services=None, late_admission=False):
        targets = pep.PUBLIC if public else pep.PRIVATE
        calls = []
        state = {'reset': False}
        def info(name):
            service = dict(zip((*targets, pep.ENGINE, *pep.DATA),
                              ('game-api', 'web-game', 'game-engine', 'game-postgres', 'game-redis'))).get(name)
            mounts = [{'Destination': '/app/data/map/topdown', 'Source': '/existing/fullbundle',
                       'Type': 'bind', 'RW': False}]
            if name in pep.DATA:
                mounts = [{'Type': 'volume', 'Name': pep.VOLUMES[pep.DATA.index(name)]}]
            ports = {'8081/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18081'}]} if public and name == targets[0] else {}
            networks = {'opensamguk-net': {'Aliases': [name]}} if public else {}
            paths = [str(pep.ROOT / p) for p in ('docker-compose.server.yml', 'operator-web-game.compose.json')]
            if legacy:
                paths = [paths[0], '/tmp/pep-loop-a1b2c3d4/' + ('paused' if name in pep.DATA else 'live') + '.json']
            return {'Running': name != 'opensamguk-deployer', 'Ports': ports, 'Networks': networks,
                    'Labels': {'com.docker.compose.project': 'opensamguk-spep',
                               'com.docker.compose.service': service,
                               'com.docker.compose.project.working_dir': str(pep.ROOT),
                               'com.docker.compose.project.config_files': ','.join(paths)},
                    'Mounts': mounts}
        def command(args, **kwargs):
            calls.append(args)
            if args[:3] == ['docker', 'volume', 'rm']:
                state['reset'] = True
            if args[0] == 'git':
                return (SOURCE + '\trefs/heads/main\n').encode()
            if args[1:4] == ['ps', '-a', '--format']:
                return '\n'.join((*targets, pep.ENGINE, *pep.DATA)).encode()
            if args[1:3] == ['image', 'inspect']:
                return json.dumps({'Id': MANIFEST, 'Os': 'linux', 'Architecture': 'amd64',
                    'RepoDigests': [pep.REGISTRY + '@' + MANIFEST], 'Revision': SOURCE}).encode()
            if len(args) >= 5 and args[1] == 'exec' and args[-2] == 'printenv':
                values = {'TOPDOWN_BAKE_ID': 'f' * 64, 'SCENARIO_CODE': current,
                          'SCENARIO_SEED_ENABLED': 'true', 'SCENARIO_DIR': '', 'SCENARIO_LOOKUP_DIR': '',
                          'OPENSAMGUK_WORLD_ID': '1', 'SERVER_GENERATION': '0', 'RESET_TURNTERM': '60',
                          'RESET_MAXGENERAL': '50', 'RESET_FIRST_TURN': 'immediate',
                          'RESET_BLOCK_GENERAL_CREATE': '1', 'SERVER_ID': 'pep',
                          'TOPDOWN_MAP_ROOT': '/app/data/map/topdown', 'GAME_API_URL': 'http://' + targets[0] + ':8081'}
                return (settings or {}).get((args[2], args[-1]), values[args[-1]]).encode()
            if args[1] == 'inspect':
                return b'healthy'
            return b''
        with tempfile.TemporaryDirectory() as temp, patch.object(pep, 'ROOT', Path(temp)), \
                patch.dict(os.environ, {'PEP_IMAGES': json.dumps(IMAGES), 'GITHUB_TOKEN': 'offline-only'}), \
                patch.object(pep.fcntl, 'flock'), patch('builtins.open', create=True) as lock_open, \
                patch.object(pep, 'inspect_container', side_effect=info), \
                patch.object(pep, 'command', side_effect=command), \
                patch.object(pep, 'db_world') as database, \
                patch.object(pep, 'preserve_map', return_value={'release': 'province-world-20261003',
                    'tilesSha256': 'a' * 64, 'worldJsonSha256': 'b' * 64, 'roadsSha256': 'c' * 64}) as map_check, \
                patch.object(pep, 'get_json', side_effect=lambda name, port, path: TICK if name == pep.ENGINE else {'status': 'UP'}), \
                patch.object(pep, 'unapplied_mode', return_value=mode), \
                patch.object(pep, 'admit_snapshot', side_effect=[None, ValueError('confirmed main CI RED')]
                             if late_admission else None) as admission, \
                patch.object(pep, 'smoke_api', side_effect=ValueError('bad API') if fail_smoke else None, return_value=False) as smoke, \
                patch.object(pep, 'summary'), patch.object(pep.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = '\n'.join(stable_services if stable_services is not None
                                                else (*pep.ROLES, 'game-postgres', 'game-redis')).encode()
            def checked_world(code):
                value = current if world_scenario is None else world_scenario
                if state['reset']:
                    value = json.loads((Path(temp) / 'pep-loop.compose.json').read_text())['services']['game-engine']['environment']['SCENARIO_CODE']
                row = {**WORLD, 'scenario_code': value}
                pep.check_world(row, code)
                return row
            database.side_effect = checked_world
            pins = map_check.return_value
            def checked_map(api, bake, source):
                pep.require(bake == 'f' * 64, 'existing world/bake binding unavailable')
                return pins
            map_check.side_effect = checked_map
            for filename in ('docker-compose.server.yml', 'operator-web-game.compose.json'):
                (Path(temp) / filename).write_text('{}')
            checkout = Path(__file__).resolve().parents[2]
            args = argparse.Namespace(server='pep', mode=operation, source=SOURCE, checkout=checkout, scenario_code=selection)
            if hold:
                with self.assertRaisesRegex(ValueError, hold):
                    pep.apply(args)
                self.assertFalse((Path(temp) / '.pep-loop-incomplete').exists())
                self.assertFalse((Path(temp) / '.pep-loop-source').exists())
                self.assertFalse((Path(temp) / 'pep-loop.compose.json').exists())
                destructive = ('stop', 'container', 'volume') if late_admission else ('stop', 'pull', 'container', 'volume')
                self.assertFalse(any(c[1] in destructive for c in calls if c[0] == 'docker'))
                self.assertFalse(any('up' in c.args[0] or 'run' in c.args[0] for c in run.call_args_list))
                return calls
            if fail_smoke:
                with self.assertRaises(ValueError):
                    pep.apply(args)
                self.assertFalse((Path(temp) / '.pep-loop-source').exists())
                self.assertTrue((Path(temp) / '.pep-loop-incomplete').exists())
                before_retry = len(calls)
                with self.assertRaisesRegex(ValueError, 'C0 recovery required'):
                    pep.apply(args)
                self.assertEqual(len(calls), before_retry, 'queued retry must not reach Docker')
            else:
                pep.apply(args)
                self.assertEqual((Path(temp) / '.pep-loop-source').read_text().strip(), SOURCE)
                self.assertFalse((Path(temp) / '.pep-loop-incomplete').exists())
                self.assertEqual(admission.call_count, 2)
            runtime = json.loads((Path(temp) / 'pep-loop.compose.json').read_text())
            expected = (selection or ('scenario_3190' if operation == 'reset' else current)) if mode == 'reset' else current
            self.assertEqual(runtime['services']['game-engine']['environment']['SCENARIO_CODE'], expected)
            self.assertEqual(runtime['services']['web-game']['environment']['GAME_API_URL'], 'http://' + targets[0] + ':8081')
            self.assertEqual(runtime['services']['game-api']['volumes'],
                             [{'type': 'bind', 'source': '/existing/fullbundle', 'target': '/app/data/map/topdown', 'read_only': True}])
            self.assertEqual(runtime['services']['game-api']['environment']['TOPDOWN_BAKE_ID'], 'f' * 64)
            map_check.assert_called_once_with(targets[0], 'f' * 64, checkout)
            if not fail_smoke:
                smoke.assert_called_once_with(targets[0], pep.scenario(checkout, expected)[1], 'f' * 64, map_check.return_value)
            calls += [c.args[0] for c in run.call_args_list]
            self.assertTrue(all('GITHUB_TOKEN' not in c.kwargs['env'] for c in run.call_args_list if 'env' in c.kwargs))
        return calls

    def test_reset_deletes_exactly_two_pep_volumes_and_no_other_target(self):
        calls = self.simulate('reset', operation='reset')
        deletes = [c for c in calls if c[:3] == ['docker', 'volume', 'rm']]
        self.assertEqual(deletes, [['docker', 'volume', 'rm', *pep.VOLUMES]])
        self.assertFalse(any('down' in c or 'prune' in c or 'socket-proxy' in c or 'opensamguk-gateway-postgres' in c for c in calls))
        private_runs = [c for c in calls if '--name' in c]
        self.assertEqual(len(private_runs), 2)
        self.assertFalse(any('--service-ports' in c or '--use-aliases' in c for c in private_runs))

    def test_final_main_red_admission_blocks_reset_before_any_mutation(self):
        self.simulate('reset', operation='reset', hold='confirmed main CI RED', late_admission=True)

    def test_refresh_does_not_delete_data_or_start_shared_stack(self):
        calls = self.simulate('refresh')
        self.assertFalse(any(c[:3] == ['docker', 'volume', 'rm'] for c in calls))
        self.assertFalse(any('game-postgres' in c or 'game-redis' in c for c in calls))

    def test_public_reset_preserves_canonical_services_and_existing_operator_overrides(self):
        calls = self.simulate('reset', operation='reset', public=True, selection='scenario_990002')
        self.assertEqual([c for c in calls if c[:3] == ['docker', 'volume', 'rm']],
                         [['docker', 'volume', 'rm', *pep.VOLUMES]])
        compose = [c for c in calls if c[:2] == ['docker', 'compose']]
        self.assertTrue(compose)
        self.assertFalse(any('run' in c or '--name' in c or 'gateway-api' in c for c in compose))
        self.assertTrue(all(any(p.endswith('operator-web-game.compose.json') for p in c) for c in compose))
        self.assertTrue(all(c[-1] in ('game-postgres', 'game-redis', 'game-engine', 'game-api', 'web-game') for c in compose))
        self.assertFalse(any(name in c for c in calls for name in pep.PRIVATE))

    def test_refresh_preserves_current_nondefault_scenario_and_data(self):
        calls = self.simulate('refresh', public=True, current='scenario_990002')
        self.assertFalse(any(c[:3] == ['docker', 'volume', 'rm'] for c in calls))

    def test_auto_and_refresh_cannot_implicitly_reset_either_exposure(self):
        for public in (False, True):
            for operation in ('auto', 'refresh'):
                with self.subTest(public=public, operation=operation):
                    calls = self.simulate('reset', operation=operation, public=public,
                                          current='scenario_990002', hold='implicit pep reset blocked')
                    self.assertEqual(calls, [], 'implicit reset must stop before any Docker command')

    def test_manual_reset_default_and_explicit_selection_are_separate_from_auto(self):
        self.simulate('reset', operation='reset', current='scenario_990002')
        self.simulate('reset', operation='reset', selection='scenario_990002')

    def test_unknown_missing_or_database_mismatched_current_selection_holds_before_mutation(self):
        for current in ('', 'scenario_999999', 'scenario_1010'):
            with self.subTest(current=current):
                self.simulate('reset', operation='reset', current=current, hold='scenario')
        self.simulate('reset', operation='reset', current='scenario_990002', world_scenario='scenario_3190', hold='identity mismatch')
        self.simulate('reset', operation='reset', selection='scenario_999999', hold='unapproved')

    def test_legacy_private_refresh_and_reset_reconstruct_overlay_preserving_bindings(self):
        for mode in ('refresh', 'reset'):
            with self.subTest(mode=mode):
                calls = self.simulate(mode, operation=mode, legacy=True, current='scenario_990002')
                compose = [c for c in calls if c[:2] == ['docker', 'compose']]
                self.assertTrue(any(c[-2:] == ['config', '--services'] for c in compose))
                self.assertFalse(any('/tmp/pep-loop-' in item for c in compose for item in c))
                self.assertTrue(all('--project-directory' in c for c in compose))

    def test_legacy_drift_public_and_invalid_stable_compose_hold_before_mutation(self):
        self.simulate('reset', operation='reset', legacy=True, public=True, hold='legacy PRIVATE')
        for key, value in [('SCENARIO_CODE', 'scenario_3190'), ('RESET_MAXGENERAL', '49'),
                           ('RESET_BLOCK_GENERAL_CREATE', '0'), ('SERVER_GENERATION', '1'),
                           ('TOPDOWN_MAP_ROOT', '/other')]:
            with self.subTest(key=key):
                self.simulate('reset', operation='reset', legacy=True, current='scenario_990002',
                              settings={(pep.PRIVATE[0], key): value}, hold='legacy pep')
        self.simulate('reset', operation='reset', legacy=True, settings={(pep.PRIVATE[0], 'TOPDOWN_BAKE_ID'): 'e' * 64},
                      hold='world/bake binding')
        self.simulate('reset', operation='reset', legacy=True, stable_services=[*pep.ROLES, 'game-postgres'], hold='stable Compose')

    def test_smoke_failure_keeps_cursor_and_closes_only_exact_pep_consumers(self):
        calls = self.simulate('reset', operation='reset', fail_smoke=True)
        self.assertEqual(len([c for c in calls if c[:3] == ['docker', 'volume', 'rm']]), 1)
        closure = calls[-3:]
        self.assertEqual([c[-1] for c in closure], [*pep.PRIVATE, pep.ENGINE])



class PepResumeWorkflowTests(unittest.TestCase):
    """Execute the carrier's real shell offline without invoking the operating helper."""
    workflow = Path(__file__).resolve().parents[2] / '.github/workflows/pep-resume-reset.yml'

    @classmethod
    def shell(cls, step):
        lines = cls.workflow.read_text().splitlines()
        start = lines.index('      - name: ' + step)
        block = lines.index('        run: |', start) + 1
        result = []
        for line in lines[block:]:
            if line and not line.startswith('          '):
                break
            result.append(line[10:] if line else '')
        return '\n'.join(result)

    def environment(self, workspace):
        return dict(os.environ, GITHUB_WORKSPACE=str(workspace),
                    GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REPOSITORY=pep.REPOSITORY,
                    GITHUB_REF='refs/heads/main', GITHUB_RUN_ID='12345', GITHUB_RUN_ATTEMPT='1',
                    GITHUB_SHA=SOURCE, RUNTIME_SOURCE=pep.RESUME_SOURCE)

    def execute(self, step, env):
        return subprocess.run(['bash', '-c', self.shell(step)], env=env,
                              capture_output=True, text=True, timeout=15)

    def reserve(self, workspace):
        env = self.environment(workspace)
        result = self.execute('Reserve isolated recovery checkout', env)
        self.assertEqual(result.returncode, 0, result.stderr)
        return env, workspace / 'pep-resume-12345-1'

    def git(self, directory, *args):
        return subprocess.check_output(['git', '-C', str(directory), *args], text=True).strip()

    def repository(self, workspace):
        env, root = self.reserve(workspace)
        helper = root / 'helper'
        self.git(helper, 'config', 'user.name', 'Offline fixture')
        self.git(helper, 'config', 'user.email', 'fixture@example.invalid')
        (helper / 'fixture').write_text('runtime\n')
        self.git(helper, 'add', 'fixture')
        self.git(helper, '-c', 'core.hooksPath=/dev/null', 'commit', '-qm', 'runtime')
        env['RUNTIME_SOURCE'] = self.git(helper, 'rev-parse', 'HEAD')
        (helper / 'fixture').write_text('helper\n')
        self.git(helper, '-c', 'core.hooksPath=/dev/null', 'commit', '-qam', 'helper')
        env['GITHUB_SHA'] = self.git(helper, 'rev-parse', 'HEAD')
        return env, root, helper

    def test_fixed_manual_carrier_keeps_normal_credentials_and_runtime_lock(self):
        text = self.workflow.read_text()
        self.assertEqual(text.split('on:\n', 1)[1].split('\npermissions:', 1)[0].strip(),
                         'workflow_dispatch:')
        self.assertEqual(text.split('permissions:\n', 1)[1].split('\nconcurrency:', 1)[0].strip(),
                         'contents: read\n  actions: read')
        for contract in ('group: pep-private-loop', 'cancel-in-progress: false',
                         'runs-on: [self-hosted, Linux, X64, gcp-prod]',
                         'RUNTIME_SOURCE: ' + pep.RESUME_SOURCE,
                         'actions/checkout@11d5960a326750d5838078e36cf38b85af677262',
                         'ref: ${{ github.sha }}', 'fetch-depth: 0', 'persist-credentials: false',
                         'path: pep-resume-${{ github.run_id }}-${{ github.run_attempt }}/helper',
                         'GITHUB_TOKEN: ${{ github.token }}'):
            self.assertIn(contract, text)
        self.assertNotIn('secrets.', text)
        self.assertNotIn('inputs:', text)

    def test_reservation_rejects_wrong_admission_and_existing_foreign_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp).resolve()
            foreign = workspace / 'foreign-wip'
            foreign.write_text('preserve')
            for key, value in [('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REPOSITORY', 'other/repo'),
                               ('GITHUB_REF', 'refs/heads/work'), ('GITHUB_RUN_ATTEMPT', '2'),
                               ('GITHUB_RUN_ID', '../foreign-wip'), ('GITHUB_SHA', 'invalid')]:
                with self.subTest(key=key):
                    env = self.environment(workspace)
                    env[key] = value
                    self.assertNotEqual(self.execute('Reserve isolated recovery checkout', env).returncode, 0)
                    self.assertEqual(list(workspace.iterdir()), [foreign])
            root = workspace / 'pep-resume-12345-1'
            root.symlink_to(workspace / 'missing', target_is_directory=True)
            self.assertNotEqual(self.execute('Reserve isolated recovery checkout', self.environment(workspace)).returncode, 0)
            self.assertTrue(root.is_symlink())
            root.unlink()  # Test-owned fixture only.
            root.mkdir()
            (root / 'wip').write_text('existing')
            self.assertNotEqual(self.execute('Reserve isolated recovery checkout', self.environment(workspace)).returncode, 0)
            self.assertEqual((root / 'wip').read_text(), 'existing')
            self.assertEqual(foreign.read_text(), 'preserve')

    def test_reservation_initializes_own_git_root_without_touching_parent_repository(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp).resolve()
            self.git(workspace, '-c', 'init.templateDir=', 'init', '-q')
            (workspace / 'foreign-wip').write_text('preserve')
            _, root = self.reserve(workspace)
            self.assertEqual(self.git(root / 'helper', 'rev-parse', '--show-toplevel'), str(root / 'helper'))
            self.assertEqual((workspace / 'foreign-wip').read_text(), 'preserve')
            self.assertEqual((root.stat().st_mode & 0o777), 0o700)

    def test_runtime_is_separate_full_source_and_preserves_helper_and_parent_wip(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp).resolve()
            (workspace / 'foreign-wip').write_text('preserve')
            env, root, helper = self.repository(workspace)
            result = self.execute('Prepare separate full runtime source', env)
            self.assertEqual(result.returncode, 0, result.stderr)
            runtime = root / 'runtime'
            self.assertEqual(self.git(helper, 'rev-parse', 'HEAD'), env['GITHUB_SHA'])
            self.assertEqual(self.git(runtime, 'rev-parse', 'HEAD'), env['RUNTIME_SOURCE'])
            self.assertEqual(self.git(runtime, 'rev-parse', '--is-shallow-repository'), 'false')
            self.assertEqual((helper / 'fixture').read_text(), 'helper\n')
            self.assertEqual((runtime / 'fixture').read_text(), 'runtime\n')
            self.assertEqual((workspace / 'foreign-wip').read_text(), 'preserve')
            # A retry must refuse the already-owned source instead of resetting it.
            (runtime / 'wip').write_text('preserve runtime')
            self.assertNotEqual(self.execute('Prepare separate full runtime source', env).returncode, 0)
            self.assertEqual((runtime / 'wip').read_text(), 'preserve runtime')

    def test_wrong_helper_head_blocks_runtime_creation(self):
        with tempfile.TemporaryDirectory() as temp:
            env, root, _ = self.repository(Path(temp).resolve())
            env['GITHUB_SHA'] = env['RUNTIME_SOURCE']
            self.assertNotEqual(self.execute('Prepare separate full runtime source', env).returncode, 0)
            self.assertFalse((root / 'runtime').exists())

    def test_real_invocation_passes_only_exact_recovery_and_native_credential_context(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp).resolve()
            env, root = self.reserve(workspace)
            stub = workspace / 'sudo'
            stub.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                            'print(json.dumps({"argv": sys.argv[1:], "credential": bool(os.environ.get("GITHUB_TOKEN"))}))\n')
            stub.chmod(0o700)
            env.update(PATH=str(workspace) + os.pathsep + os.environ['PATH'], GITHUB_TOKEN='offline-fixture')
            result = self.execute('Resume exact seeded pep then first tick and smoke', env)
            self.assertEqual(result.returncode, 0, result.stderr)
            actual = json.loads(result.stdout)
            self.assertTrue(actual['credential'])
            self.assertEqual(actual['argv'], [
                '-n', '--preserve-env=GITHUB_TOKEN,GITHUB_REPOSITORY,GITHUB_REF,GITHUB_STEP_SUMMARY',
                'python3', '-B', str(root / 'helper/tools/ops/pep_loop.py'), 'resume-reset',
                '--server', 'pep', '--source', pep.RESUME_SOURCE,
                '--checkout', str(root / 'runtime'), '--helper-source', SOURCE,
                '--helper-checkout', str(root / 'helper'), '--web-declaration', 'docker66-public-3101'])

if __name__ == '__main__':
    unittest.main()
