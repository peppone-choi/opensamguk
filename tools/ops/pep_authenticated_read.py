"""Read the old PEP world through an isolated API clone using legitimate ADMIN login."""

from dataclasses import dataclass, field
import hashlib
import http.client
import io
import ipaddress
import json
import re

from game_server_recovery import RecoveryError, require


@dataclass(frozen=True, repr=False)
class AdminSession:
    access_token: str = field(repr=False)
    user_id: int
    public_key_sha256: str


def unique_env(container):
    result = {}
    for item in container['Config']['Env']:
        key, separator, value = item.partition('=')
        require(separator and key and key not in result and '\0' not in item,
                'invalid or duplicate source environment')
        result[key] = value
    return result


class GatewayAdminLogin:
    def __init__(self, connection_factory=http.client.HTTPConnection):
        self.connection_factory = connection_factory

    def _json(self, host, method, path, payload=None, token=None):
        connection = self.connection_factory(host, 8080, timeout=10)
        headers = {'Content-Type': 'application/json'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + token
        try:
            connection.request(method, path,
                               body=json.dumps(payload).encode() if payload is not None else None,
                               headers=headers)
            response = connection.getresponse()
            body = response.read(65537)
            require(response.status == 200 and len(body) <= 65536,
                    'legitimate ADMIN authentication failed')
            result = json.loads(body)
            require(isinstance(result, dict), 'invalid ADMIN response')
            return result
        except (OSError, ValueError, TypeError, http.client.HTTPException):
            raise RecoveryError('legitimate ADMIN authentication failed') from None
        finally:
            connection.close()

    def login(self, recovery):
        gateway = recovery.inspect('container', 'opensamguk-gateway-api')
        require(gateway['Name'] == '/opensamguk-gateway-api' and
                gateway['State']['Running'] is True,
                'live Gateway identity unavailable')
        networks = gateway['NetworkSettings']['Networks']
        require(set(networks) == {'opensamguk-net'}, 'unexpected Gateway network')
        host = networks['opensamguk-net']['IPAddress']
        try:
            require(ipaddress.ip_address(host).is_private, 'Gateway address is not private')
        except ValueError:
            raise RecoveryError('invalid Gateway address') from None
        env = unique_env(gateway)
        username, password = env.get('ADMIN_USERNAME'), env.get('ADMIN_PASSWORD')
        public_key = env.get('JWT_PUBLIC_KEY')
        require(bool(username) and bool(password) and bool(public_key),
                'configured ADMIN login is unavailable')
        login = self._json(host, 'POST', '/auth/login',
                           {'username': username, 'password': password})
        token = login.get('accessToken')
        user = login.get('user')
        require(isinstance(token, str) and len(token) < 8192 and
                re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token) is not None and
                isinstance(user, dict) and user.get('role') == 'ADMIN' and
                type(user.get('id')) is int and user['id'] > 0,
                'configured login did not authenticate ADMIN')
        identity = self._json(host, 'GET', '/auth/me', token=token)
        require(identity.get('id') == user['id'] and identity.get('role') == 'ADMIN',
                'ADMIN identity read mismatch')
        return AdminSession(token, user['id'], hashlib.sha256(public_key.encode()).hexdigest())


class PepAuthenticatedReadProbe:
    PATHS = frozenset({'/actuator/health', '/api/my-page', '/api/my-cities', '/api/map/preview'})

    def __init__(self, token_provider=None):
        self.token_provider = token_provider or GatewayAdminLogin()

    def _read(self, recovery, container, path, token=None):
        require(path in self.PATHS, 'unreviewed isolated API read')
        config = [
            'silent', 'show-error', 'max-time = 10', 'max-filesize = 5000000',
            'url = "http://localhost:8081' + path + '"',
            'write-out = "\\n__OS_HTTP_STATUS__:%{http_code}"',
        ]
        if token is not None:
            require(re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token) is not None,
                    'invalid ADMIN access token')
            config.append('header = "Authorization: Bearer ' + token + '"')
        result = recovery.docker.run(['container', 'exec', '-i', container, 'curl', '--config', '-'],
                                     stdin=io.BytesIO(('\n'.join(config) + '\n').encode()))
        try:
            body, status = result.rsplit(b'\n__OS_HTTP_STATUS__:', 1)
            require(len(body) <= 5000000 and re.fullmatch(rb'[0-9]{3}', status) is not None,
                    'invalid isolated API response')
            return int(status), body
        except ValueError:
            raise RecoveryError('invalid isolated API response') from None

    def __call__(self, *, recovery, source, manifest, bundle, network, postgres, redis,
                 scenario_tree, create_container, verified_storage, manifest_sha256):
        archived = manifest['containers']['game-api']
        record = source.services['game-api']
        require(record.image_id == archived['image_id'] and record.container_id == archived['id'] and
                type(record.memory) is int and record.memory > 0,
                'archived API identity or memory limit mismatch')
        api_env = dict(source.api_env)
        require(api_env.get('OPENSAMGUK_WORLD_ID') == source.engine_env.get('OPENSAMGUK_WORLD_ID') and
                bool(api_env.get('JWT_PUBLIC_KEY')),
                'source API world or JWT verifier missing')
        source_url = api_env.get('GAME_DATABASE_URL', '')
        match = re.fullmatch(r'jdbc:postgresql://[^/?#]+/([a-zA-Z_][a-zA-Z0-9_]{0,62})(\?[^#]*)?', source_url)
        require(match and match[1] == source.engine_env['GAME_DATABASE_URL'].split('/')[-1].split('?')[0],
                'source API database mismatch')
        api_env.update(GAME_DATABASE_URL=f'jdbc:postgresql://{postgres}:5432/{match[1]}{match[2] or ""}',
                       REDIS_HOST=redis, REDIS_PORT='6379', SCENARIO_SEED_ENABLED='false',
                       SENTRY_DSN='')
        environment = [part for key, value in sorted(api_env.items()) for part in ('-e', key + '=' + value)]
        from pep_preserving_topology import mount
        destination = mount({'Mounts': source.api_mounts}, '/data/scenarios')['Destination']
        extra_mounts = []
        if manifest.get('version') == 2 and manifest.get('topdown') is not None:
            from pep_topdown_catalog import companion
            topdown = companion(bundle, manifest['topdown']['catalog'])
            extra_mounts = ['--mount', f'type=bind,source={topdown},target=/app/data/map/topdown,readonly']
        api = create_container('game-api', record.image_id,
                               [*environment, '--mount',
                                f'type=bind,source={scenario_tree},target={destination},readonly', *extra_mounts],
                               memory=record.memory)
        recovery.docker.run(['container', 'start', api])
        ready = False
        for _ in range(60):
            try:
                status, body = self._read(recovery, api, '/actuator/health')
                if status == 200 and json.loads(body).get('status') == 'UP':
                    ready = True
                    break
            except (RecoveryError, ValueError, TypeError):
                pass
            recovery.sleep(1)
        require(ready, 'isolated old API did not become healthy')
        status, _ = self._read(recovery, api, '/api/my-page')
        require(status in (401, 403), 'protected isolated read did not reject missing token')
        session = self.token_provider.login(recovery)
        require(hashlib.sha256(api_env['JWT_PUBLIC_KEY'].encode()).hexdigest() == session.public_key_sha256,
                'Gateway signer and old API verifier differ')
        status, body = self._read(recovery, api, '/api/my-page', session.access_token)
        # An ADMIN account may have no character in this world. Its documented
        # response is 404; the protected list below must still return 200.
        require(status == 404 or (status == 200 and
                type(json.loads(body).get('generalId')) is int and
                json.loads(body)['generalId'] > 0),
                'isolated protected character read failed')
        status, body = self._read(recovery, api, '/api/my-cities', session.access_token)
        require(status == 200 and isinstance(json.loads(body), (dict, list)),
                'isolated protected world read failed')
        status, body = self._read(recovery, api, '/api/map/preview', session.access_token)
        map_data = json.loads(body)
        require(status == 200 and isinstance(map_data, dict) and
                type(map_data.get('year')) is int and map_data['year'] > 0 and
                isinstance(map_data.get('cities'), list) and
                len(map_data['cities']) == verified_storage['counts']['city'],
                'isolated map read differs from recovered world')
        recovery.docker.run(['container', 'stop', '--time', '120', api])
        stopped = recovery.inspect('container', api)['State']
        require(not stopped['Running'] and not stopped.get('OOMKilled') and
                stopped.get('ExitCode') in (0, 143), 'isolated API shutdown was not clean')
        return {'source': 'isolated', 'bundle_manifest_sha256': manifest_sha256,
                'world_id': int(source.engine_env['OPENSAMGUK_WORLD_ID']),
                'checks': {'login': True, 'identity': True, 'server_entry': True,
                           'world_read': True, 'map_read': True}}
