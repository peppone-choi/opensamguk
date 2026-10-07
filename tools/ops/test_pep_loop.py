"""Offline D143 boundaries. No Docker, network, env files or operating DB access."""
import argparse
from copy import deepcopy
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
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


class PepOperationTests(unittest.TestCase):
    """Simulated commands check destructive boundaries and failure closure end-to-end."""
    def simulate(self, mode, fail_smoke=False, public=False, current='scenario_3190', selection=None,
                 operation='auto', legacy=False, settings=None, world_scenario=None, hold=None,
                 stable_services=None):
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
                patch.dict(os.environ, {'PEP_IMAGES': json.dumps(IMAGES)}), \
                patch.object(pep.fcntl, 'flock'), patch('builtins.open', create=True) as lock_open, \
                patch.object(pep, 'inspect_container', side_effect=info), \
                patch.object(pep, 'command', side_effect=command), \
                patch.object(pep, 'db_world') as database, \
                patch.object(pep, 'preserve_map', return_value={'release': 'province-world-20261003',
                    'tilesSha256': 'a' * 64, 'worldJsonSha256': 'b' * 64, 'roadsSha256': 'c' * 64}) as map_check, \
                patch.object(pep, 'get_json', side_effect=lambda name, port, path: TICK if name == pep.ENGINE else {'status': 'UP'}), \
                patch.object(pep, 'unapplied_mode', return_value=mode), \
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
                self.assertFalse(any(c[1] in ('stop', 'pull', 'container', 'volume') for c in calls if c[0] == 'docker'))
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
        return calls

    def test_reset_deletes_exactly_two_pep_volumes_and_no_other_target(self):
        calls = self.simulate('reset')
        deletes = [c for c in calls if c[:3] == ['docker', 'volume', 'rm']]
        self.assertEqual(deletes, [['docker', 'volume', 'rm', *pep.VOLUMES]])
        self.assertFalse(any('down' in c or 'prune' in c or 'socket-proxy' in c or 'opensamguk-gateway-postgres' in c for c in calls))
        private_runs = [c for c in calls if '--name' in c]
        self.assertEqual(len(private_runs), 2)
        self.assertFalse(any('--service-ports' in c or '--use-aliases' in c for c in private_runs))

    def test_refresh_does_not_delete_data_or_start_shared_stack(self):
        calls = self.simulate('refresh')
        self.assertFalse(any(c[:3] == ['docker', 'volume', 'rm'] for c in calls))
        self.assertFalse(any('game-postgres' in c or 'game-redis' in c for c in calls))

    def test_public_reset_preserves_canonical_services_and_existing_operator_overrides(self):
        calls = self.simulate('reset', public=True, selection='scenario_990002')
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

    def test_automatic_reset_preserves_current_nondefault_selection(self):
        for public in (False, True):
            with self.subTest(public=public):
                self.simulate('reset', public=public, current='scenario_990002')
        self.simulate('reset', operation='refresh', current='scenario_990002')

    def test_manual_reset_default_and_explicit_selection_are_separate_from_auto(self):
        self.simulate('reset', operation='reset', current='scenario_990002')
        self.simulate('reset', operation='reset', selection='scenario_990002')

    def test_unknown_missing_or_database_mismatched_current_selection_holds_before_mutation(self):
        for current in ('', 'scenario_999999', 'scenario_1010'):
            with self.subTest(current=current):
                self.simulate('reset', current=current, hold='scenario')
        self.simulate('reset', current='scenario_990002', world_scenario='scenario_3190', hold='identity mismatch')
        self.simulate('reset', selection='scenario_999999', hold='unapproved')

    def test_legacy_private_refresh_and_reset_reconstruct_overlay_preserving_bindings(self):
        for mode in ('refresh', 'reset'):
            with self.subTest(mode=mode):
                calls = self.simulate(mode, legacy=True, current='scenario_990002')
                compose = [c for c in calls if c[:2] == ['docker', 'compose']]
                self.assertTrue(any(c[-2:] == ['config', '--services'] for c in compose))
                self.assertFalse(any('/tmp/pep-loop-' in item for c in compose for item in c))
                self.assertTrue(all('--project-directory' in c for c in compose))

    def test_legacy_drift_public_and_invalid_stable_compose_hold_before_mutation(self):
        self.simulate('reset', legacy=True, public=True, hold='legacy PRIVATE')
        for key, value in [('SCENARIO_CODE', 'scenario_3190'), ('RESET_MAXGENERAL', '49'),
                           ('RESET_BLOCK_GENERAL_CREATE', '0'), ('SERVER_GENERATION', '1'),
                           ('TOPDOWN_MAP_ROOT', '/other')]:
            with self.subTest(key=key):
                self.simulate('reset', legacy=True, current='scenario_990002',
                              settings={(pep.PRIVATE[0], key): value}, hold='legacy pep')
        self.simulate('reset', legacy=True, settings={(pep.PRIVATE[0], 'TOPDOWN_BAKE_ID'): 'e' * 64},
                      hold='world/bake binding')
        self.simulate('reset', legacy=True, stable_services=[*pep.ROLES, 'game-postgres'], hold='stable Compose')

    def test_smoke_failure_keeps_cursor_and_closes_only_exact_pep_consumers(self):
        calls = self.simulate('reset', fail_smoke=True)
        self.assertEqual(len([c for c in calls if c[:3] == ['docker', 'volume', 'rm']]), 1)
        closure = calls[-3:]
        self.assertEqual([c[-1] for c in closure], [*pep.PRIVATE, pep.ENGINE])


if __name__ == '__main__':
    unittest.main()
