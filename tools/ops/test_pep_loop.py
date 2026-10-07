"""Offline D143 boundaries. No Docker, network, env files or operating DB access."""
import argparse
from copy import deepcopy
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
            self.assertEqual(env['RESET_MAXGENERAL'], '50')
            self.assertEqual(env['RESET_BLOCK_GENERAL_CREATE'], '1')
            self.assertEqual(env['SERVER_GENERATION'], '0')
        self.assertEqual(spec['services']['web-game']['environment']['GAME_API_URL'],
                         'http://spep-game-api-validation:8081')
        self.assertNotIn('ports', spec['services']['game-api'])

    def test_api_probe_checks_entire_current_bundle_and_never_gateway(self):
        payload = b'current fullbundle asset'
        bake = 'f' * 64
        fixtures = {
            '/actuator/health': {'status': 'UP'},
            '/api/server-basic-info': {'game': {'maxUserCnt': 50, 'turnTerm': 60,
                'blockGeneralCreate': 1, 'scenario': '동탁의 전횡과 반동탁연합'}, 'me': None},
            '/api/map/preview': {'cities': [{}], 'nations': [{}], 'topdownBakeId': bake},
            '/api/map/topdown/' + bake + '/manifest.json': {'bakeId': bake, 'partial': False,
                'inputFingerprint': {'region': None}, 'files': [{'file': 'places.json',
                    'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}]},
        }
        calls = []
        def fetch(name, port, path, token=''):
            calls.append((name, port, path))
            if path.endswith('/places.json'):
                return payload, 'application/json'
            return json.dumps(fixtures[path]).encode(), 'application/json'
        with patch.object(pep, 'fetch', side_effect=fetch), patch.dict(os.environ, {'PEP_SMOKE_JWT': ''}):
            self.assertFalse(pep.smoke_api())
            self.assertTrue(all(name == pep.PRIVATE[0] and port == 8081 for name, port, _ in calls))
            fixtures['/api/map/topdown/' + bake + '/manifest.json']['files'][0]['sha256'] = '0' * 64
            with self.assertRaises(ValueError):
                pep.smoke_api()


class PepOperationTests(unittest.TestCase):
    """Simulated commands check destructive boundaries and failure closure end-to-end."""
    def simulate(self, mode, fail_smoke=False):
        calls = []
        def info(name):
            service = dict(zip((*pep.PRIVATE, pep.ENGINE, *pep.DATA),
                              ('game-api', 'web-game', 'game-engine', 'game-postgres', 'game-redis'))).get(name)
            mounts = [{'Destination': '/app/data/map/topdown', 'Source': '/existing/fullbundle',
                       'Type': 'bind', 'RW': False}]
            if name in pep.DATA:
                mounts = [{'Type': 'volume', 'Name': pep.VOLUMES[pep.DATA.index(name)]}]
            return {'Running': name != 'opensamguk-deployer', 'Ports': {}, 'Networks': {},
                    'Labels': {'com.docker.compose.project': 'opensamguk-spep',
                               'com.docker.compose.service': service},
                    'Mounts': mounts}
        def command(args, **kwargs):
            calls.append(args)
            if args[0] == 'git':
                return (SOURCE + '\trefs/heads/main\n').encode()
            if args[1:4] == ['ps', '-a', '--format']:
                return '\n'.join((*pep.PRIVATE, pep.ENGINE, *pep.DATA)).encode()
            if args[1:3] == ['image', 'inspect']:
                return json.dumps({'Id': MANIFEST, 'Os': 'linux', 'Architecture': 'amd64',
                    'RepoDigests': [pep.REGISTRY + '@' + MANIFEST], 'Revision': SOURCE}).encode()
            if args[-1] == 'TOPDOWN_BAKE_ID':
                return ('f' * 64).encode()
            if args[-1] == 'SERVER_GENERATION':
                return b'0'
            if args[1] == 'inspect':
                return b'healthy'
            return b''
        with tempfile.TemporaryDirectory() as temp, patch.object(pep, 'ROOT', Path(temp)), \
                patch.dict(os.environ, {'PEP_IMAGES': json.dumps(IMAGES)}), \
                patch.object(pep.fcntl, 'flock'), patch('builtins.open', create=True) as lock_open, \
                patch.object(pep, 'inspect_container', side_effect=info), \
                patch.object(pep, 'command', side_effect=command), \
                patch.object(pep, 'db_world', return_value=deepcopy(WORLD)), \
                patch.object(pep, 'get_json', side_effect=lambda name, port, path: TICK if name == pep.ENGINE else {'status': 'UP'}), \
                patch.object(pep, 'unapplied_mode', return_value=mode), \
                patch.object(pep, 'smoke_api', side_effect=ValueError('bad API') if fail_smoke else None, return_value=False), \
                patch.object(pep, 'summary'), patch.object(pep.subprocess, 'run') as run:
            run.return_value.returncode = 0
            args = argparse.Namespace(server='pep', mode='auto', source=SOURCE, checkout=Path(temp))
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

    def test_smoke_failure_keeps_cursor_and_closes_only_exact_pep_consumers(self):
        calls = self.simulate('reset', fail_smoke=True)
        self.assertEqual(len([c for c in calls if c[:3] == ['docker', 'volume', 'rm']]), 1)
        closure = calls[-3:]
        self.assertEqual([c[-1] for c in closure], [*pep.PRIVATE, pep.ENGINE])


if __name__ == '__main__':
    unittest.main()
