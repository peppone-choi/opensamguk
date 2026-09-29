#!/usr/bin/env python3
"""Boundary tests for the isolated old API read and live Gateway identity check."""

import hashlib
import io
import json
from types import SimpleNamespace
import unittest

from game_server_recovery import RecoveryError
from pep_authenticated_read import AdminSession, GatewayAdminLogin, PepAuthenticatedReadProbe


TOKEN = 'header.payload.signature'
IMAGE = 'sha256:' + 'a' * 64


class Response:
    def __init__(self, status, payload):
        self.status = status
        self.payload = json.dumps(payload).encode()

    def read(self, limit):
        return self.payload[:limit]


class GatewayConnection:
    def __init__(self, requests, host, port, timeout):
        assert host == '172.18.0.8' and port == 8080 and timeout == 10
        self.requests = requests

    def request(self, method, path, body=None, headers=None):
        self.requests.append((method, path, body, headers))

    def getresponse(self):
        path = self.requests[-1][1]
        if path == '/auth/login':
            return Response(200, {'accessToken': TOKEN, 'user': {'id': 9, 'role': 'ADMIN'}})
        return Response(200, {'id': 9, 'role': 'ADMIN'})

    def close(self):
        pass


class GatewayRecovery:
    def __init__(self):
        self.gateway = {'Name': '/opensamguk-gateway-api', 'State': {'Running': True},
                        'NetworkSettings': {'Networks': {'opensamguk-net': {'IPAddress': '172.18.0.8'}}},
                        'Config': {'Env': ['ADMIN_USERNAME=operator', 'ADMIN_PASSWORD=private-password',
                                           'JWT_PUBLIC_KEY=public-key']}}

    def inspect(self, kind, name):
        assert (kind, name) == ('container', 'opensamguk-gateway-api')
        return self.gateway


class ReadDocker:
    def __init__(self):
        self.calls = []
        self.configs = []
        self.city_count = 2
        self.anonymous_status = 403
        self.stopped = False

    def run(self, args, *, stdin=None):
        self.calls.append(args)
        if args[:2] == ['container', 'start']:
            return b''
        if args[:2] == ['container', 'stop']:
            self.stopped = True
            return b''
        assert args[:4] == ['container', 'exec', '-i', 'clone-api']
        assert isinstance(stdin, io.BytesIO)
        config = stdin.getvalue().decode()
        self.configs.append(config)
        if '/actuator/health' in config:
            status, body = 200, {'status': 'UP'}
        elif '/api/my-page' in config and 'Authorization:' not in config:
            status, body = self.anonymous_status, {}
        elif '/api/my-page' in config:
            status, body = 404, {}
        elif '/api/my-cities' in config:
            status, body = 200, {'result': False}
        elif '/api/map/preview' in config:
            status, body = 200, {'year': 180, 'cities': [{}] * self.city_count}
        else:
            raise AssertionError('unexpected read')
        return json.dumps(body).encode() + b'\n__OS_HTTP_STATUS__:' + str(status).encode()


class ReadRecovery:
    def __init__(self):
        self.docker = ReadDocker()
        self.sleep = lambda _: None

    def inspect(self, kind, name):
        assert (kind, name) == ('container', 'clone-api')
        return {'State': {'Running': not self.docker.stopped, 'OOMKilled': False, 'ExitCode': 143}}


class TokenProvider:
    def login(self, recovery):
        return AdminSession(TOKEN, 9, hashlib.sha256(b'public-key').hexdigest())


class AuthenticatedReadTests(unittest.TestCase):
    def test_gateway_login_uses_private_address_and_does_not_return_credentials(self):
        requests = []
        login = GatewayAdminLogin(lambda *args, **kwargs: GatewayConnection(requests, *args, **kwargs))
        session = login.login(GatewayRecovery())
        self.assertEqual(session.user_id, 9)
        self.assertEqual(session.access_token, TOKEN)
        self.assertNotIn('private-password', repr(session))
        self.assertEqual(requests[0][1], '/auth/login')
        self.assertEqual(requests[1][1], '/auth/me')
        self.assertEqual(requests[1][3]['Authorization'], 'Bearer ' + TOKEN)

    def test_gateway_identity_and_network_must_match(self):
        login = GatewayAdminLogin(lambda *args, **kwargs: GatewayConnection([], *args, **kwargs))
        for mutation in [lambda gateway: gateway['State'].update(Running=False),
                         lambda gateway: gateway['NetworkSettings']['Networks'].update(
                             {'public': {'IPAddress': '203.0.113.1'}}),
                         lambda gateway: gateway['Config']['Env'].append('ADMIN_PASSWORD=duplicate')]:
            recovery = GatewayRecovery()
            mutation(recovery.gateway)
            with self.assertRaises(RecoveryError):
                login.login(recovery)

    def context(self, recovery):
        source = SimpleNamespace(
            services={'game-api': SimpleNamespace(image_id=IMAGE, container_id='source-api', memory=2048)},
            api_env={'OPENSAMGUK_WORLD_ID': '7', 'JWT_PUBLIC_KEY': 'public-key',
                     'GAME_DATABASE_URL': 'jdbc:postgresql://source:5432/sammo'},
            engine_env={'OPENSAMGUK_WORLD_ID': '7',
                        'GAME_DATABASE_URL': 'jdbc:postgresql://source:5432/sammo'},
            api_mounts=({'Destination': '/data/scenarios'},))
        created = []
        def create_container(key, image, args, *, memory):
            created.append((key, image, args, memory))
            return 'clone-api'
        return dict(recovery=recovery, source=source,
                    manifest={'containers': {'game-api': {'image_id': IMAGE, 'id': 'source-api'}}},
                    bundle=None, network='private-network', postgres='clone-pg', redis='clone-redis',
                    scenario_tree='/private/scenarios', create_container=create_container,
                    verified_storage={'counts': {'city': 2}}, manifest_sha256='e' * 64), created

    def test_isolated_authenticated_reads_and_secret_stays_out_of_docker_argv(self):
        recovery = ReadRecovery()
        context, created = self.context(recovery)
        proof = PepAuthenticatedReadProbe(TokenProvider())(**context)
        self.assertEqual(proof['world_id'], 7)
        self.assertTrue(all(proof['checks'].values()))
        self.assertTrue(recovery.docker.stopped)
        self.assertEqual(created[0][0:2], ('game-api', IMAGE))
        self.assertEqual(created[0][3], 2048)
        self.assertIn('type=bind,source=/private/scenarios,target=/data/scenarios,readonly', created[0][2])
        self.assertNotIn(TOKEN, repr(recovery.docker.calls))
        self.assertIn(TOKEN, recovery.docker.configs[-1])

    def test_map_count_mismatch_refuses_proof(self):
        recovery = ReadRecovery()
        recovery.docker.city_count = 1
        context, _ = self.context(recovery)
        with self.assertRaisesRegex(RecoveryError, 'map read differs'):
            PepAuthenticatedReadProbe(TokenProvider())(**context)

    def test_anonymous_protected_read_must_be_rejected(self):
        recovery = ReadRecovery()
        recovery.docker.anonymous_status = 200
        context, _ = self.context(recovery)
        with self.assertRaisesRegex(RecoveryError, 'did not reject missing token'):
            PepAuthenticatedReadProbe(TokenProvider())(**context)


if __name__ == '__main__':
    unittest.main()
