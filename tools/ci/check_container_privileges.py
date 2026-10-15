#!/usr/bin/env python3
"""Static policy and isolated, disposable image runtime checks (GitHub-hosted only)."""
import argparse
from pathlib import Path
import subprocess
import time
import uuid

APPS = ('board-api', 'game-api', 'game-engine', 'gateway-api', 'web-game', 'web-gateway')
ROOT = Path(__file__).resolve().parents[2]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def static():
    import yaml
    services = yaml.safe_load((ROOT / 'docker-compose.production.yml').read_text())['services']
    for name in APPS:
        assert services[name]['security_opt'] == ['no-new-privileges:true'], name
        assert services[name]['cap_drop'] == ['ALL'], name
        if name != 'gateway-api':
            stage = (ROOT / f'docker/{name}.Dockerfile').read_text().rsplit('\nFROM ', 1)[1]
            users = [line.split(maxsplit=1)[1] for line in stage.splitlines() if line.startswith('USER ')]
            assert users == (['node'] if name.startswith('web-') else ['10001:10001']), name
    engine = (ROOT / 'docker/game-engine.Dockerfile').read_text().rsplit('\nFROM ', 1)[1]
    assert 'chown 0:0 /run/d101 && chmod 0700 /run/d101' in engine, 'D101 root-owned 0700 directory'
    assert 'chown 10001:10001 /run/d101' not in engine, 'D101 must not belong to ordinary app'
    print('PASS: D101 root-owned directory and nonroot default engine')
    print('PASS: five final-stage nonroot users; six compose app hardening policies', flush=True)


def smoke(surface, image):
    prefix = 'priv-' + uuid.uuid4().hex[:12]
    containers = []
    hardened = ('--security-opt', 'no-new-privileges:true', '--cap-drop', 'ALL')
    def container(name, img, *args):
        cname = prefix + '-' + name
        containers.append(cname)
        run('docker', 'run', '-d', '--name', cname, '--network', prefix, *args, img)
        return cname
    def exec_(name, *args):
        return run('docker', 'exec', name, *args, capture_output=True).stdout.strip()
    def wait(name, command):
        for _ in range(120):
            result = subprocess.run(['docker', 'exec', name, *command], capture_output=True, text=True)
            if result.returncode == 0:
                return result.stdout
            if exec_(name, 'sh', '-c', 'kill -0 1') != '':
                raise AssertionError('container stopped')
            time.sleep(3)
        raise AssertionError(f'{name}: readiness timeout')
    try:
        run('docker', 'network', 'create', prefix)
        if surface.startswith('web-'):
            port = '3001' if surface == 'web-game' else '3000'
            app = container('app', image, *hardened, '-e', 'HOSTNAME=0.0.0.0')
            # A real public static resource must be served, not merely a listening socket.
            probe = """const fs=require('fs');const path=require('path');
const root='/app/""" + ('game' if surface == 'web-game' else 'gateway') + """/.next/static';
function first(p){for(const n of fs.readdirSync(p)){const f=path.join(p,n);if(fs.statSync(f).isDirectory()){const r=first(f);if(r)return r;}else if(n.endsWith('.js'))return f;}}
const file=first(root);if(!file)throw Error('no static JS');
fetch('http://127.0.0.1:""" + port + """/_next/static/'+path.relative(root,file)).then(async r=>{if(!r.ok||!(await r.text()).length)process.exit(1);console.log('static JS HTTP '+r.status);}).catch(()=>process.exit(1));"""
            print(wait(app, ['node', '-e', probe]))
            sub = 'game' if surface == 'web-game' else 'gateway'
            exec_(app, 'sh', '-ec', f'touch /app/{sub}/.next/cache/privilege-probe; rm /app/{sub}/.next/cache/privilege-probe')
        else:
            pg = container('pg', 'postgres:16-alpine', '-e', 'POSTGRES_PASSWORD=ci-only', '-e', 'POSTGRES_DB=sammo')
            wait(pg, ['pg_isready', '-U', 'postgres', '-d', 'sammo'])
            redis = container('redis', 'redis:7-alpine')
            wait(redis, ['redis-cli', 'ping'])
            # Fresh isolated schema: board normally consumes gateway migrations. Enable the
            # same packaged migrations here, without bypassing JPA validation or health.
            key = run('openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', capture_output=True).stdout
            pub = run('openssl', 'pkey', '-pubout', input=key, capture_output=True).stdout
            # Product parsers require Base64 DER, not PEM envelopes.
            key = ''.join(key.splitlines()[1:-1])
            pub = ''.join(pub.splitlines()[1:-1])
            env = {'GAME_DATABASE_URL': f'jdbc:postgresql://{pg}:5432/sammo', 'GAME_DB_USER': 'postgres',
                   'GAME_DB_PASSWORD': 'ci-only', 'REDIS_HOST': redis, 'SPRING_DATA_REDIS_HOST': redis, 'OPENSAMGUK_WORLD_ID': '1',
                   'SCENARIO_SEED_ENABLED': 'false', 'JWT_PUBLIC_KEY': pub, 'JWT_PRIVATE_KEY': key,
                   'INTERNAL_SERVICE_TOKEN': 'ci-only-service', 'SERVER_ID': 'ciprivileges', 'GATEWAY_API_URL': 'http://gateway.invalid',
                   'SPRING_FLYWAY_ENABLED': 'true',
                   # Board normally uses gateway's migrated schema; mirror its V29+
                   # concurrent-index session-lock setting when bootstrapping this DB.
                   'JAVA_OPTS': '-Xmx768m -Dspring.flyway.postgresql.transactional-lock=false'}
            options = [item for k, v in env.items() for item in ('-e', k + '=' + v)]
            app = container('app', image, *hardened, *options)
            port = {'game-api': 8081, 'game-engine': 8082, 'board-api': 8083, 'gateway-api': 8080}[surface]
            print(wait(app, ['curl', '--max-time', '5', '-fsS', f'http://localhost:{port}/actuator/health']))
            if surface == 'game-api':
                exec_(app, 'sh', '-ec', 'touch /app/data/map/topdown/privilege-probe; rm /app/data/map/topdown/privilege-probe')
            if surface == 'game-engine':
                assert exec_(app, 'stat', '-c', '%a:%u:%g', '/run/d101') == '700:0:0'
                # The daemon must neither read nor write the approval transport.
                exec_(app, 'sh', '-ec', 'test ! -r /run/d101; test ! -w /run/d101; test ! -x /run/d101')
                # Disposable image only: synthetic bytes, no approval/provider/mounts,
                # no Java/D101 operation. Explicit root override preserves the file contract.
                probe = """test "$(id -u):$(id -g)" = 0:0
                    test "$(stat -c '%a:%u:%g' /run/d101)" = 700:0:0
                    trap 'rm -f /run/d101/privilege-probe' EXIT
                    umask 077
                    printf 'ci-only-not-an-approval' > /run/d101/privilege-probe
                    chmod 0400 /run/d101/privilege-probe
                    test "$(stat -c '%a:%u:%g' /run/d101/privilege-probe)" = 400:0:0
                    test "$(cat /run/d101/privilege-probe)" = ci-only-not-an-approval
                    test -w /run/d101
                    grep -Eq '^NoNewPrivs:[[:space:]]+1$' /proc/self/status
                    grep -Eq '^CapEff:[[:space:]]+0+$' /proc/self/status
                    grep -Eq '^CapBnd:[[:space:]]+0+$' /proc/self/status"""
                run('docker', 'run', '--rm', '--network', 'none', *hardened,
                    '--user', '0:0', '--entrypoint', 'sh', image, '-ec', probe, timeout=60)
                print('PASS: daemon cannot access D101; explicit hardened root reads root-owned 0400 original')
                for entry in ('d101-seed-only-entrypoint', 'd101-pre-intent-entrypoint'):
                    for user in ((), ('--user', '0:0')):
                        # Both identities fail closed without approval; not an approved D101 execution.
                        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', *hardened,
                                                 *user, '--entrypoint', '/app/' + entry, image],
                                                capture_output=True, text=True, timeout=60)
                        assert result.returncode == 78, (entry, user, result.returncode, result.stderr)
                        print(f'PASS: {entry} user={user or "default"}; missing approval fails closed (78)')
        uid = exec_(app, 'id', '-u')
        assert uid == ('0' if surface == 'gateway-api' else '1000' if surface.startswith('web-') else '10001'), uid
        status = exec_(app, 'cat', '/proc/1/status')
        fields = dict(line.split(':', 1) for line in status.splitlines() if ':' in line)
        assert fields['Uid'].split() == [uid] * 4, fields['Uid']
        assert fields['Gid'].split() == [uid] * 4, fields['Gid']
        assert fields['NoNewPrivs'].strip() == '1'
        assert int(fields['CapEff'].strip(), 16) == 0
        assert int(fields['CapBnd'].strip(), 16) == 0
        print(f'PASS: {surface} uid={uid}, PID1 NoNewPrivs=1 CapEff=0 CapBnd=0; health/static and writes preserved', flush=True)
    finally:
        for name in reversed(containers):
            subprocess.run(['docker', 'logs', name], check=False)
            subprocess.run(['docker', 'rm', '-f', '-v', name], check=False)
        subprocess.run(['docker', 'network', 'rm', prefix], check=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--surface', choices=APPS)
    parser.add_argument('--image')
    args = parser.parse_args()
    static()
    if args.surface:
        assert args.image
        smoke(args.surface, args.image)
