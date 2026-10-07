#!/usr/bin/env python3
"""D143 exact-pep PRIVATE operation. No shared-stack operation or secret output."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path('/home/peppone_choi/opensamguk-docker')
BASELINE = 'b7dc6b49516d9b6996c3a1bf7fb6858f0309adc1'
REGISTRY = 'ghcr.io/peppone-choi/opensamguk'
ROLES = ('game-api', 'game-engine', 'web-game')
PRIVATE = ('spep-game-api-validation', 'spep-web-game-validation')
ENGINE = 'spep-game-engine'
DATA = ('spep-game-postgres', 'spep-game-redis')
VOLUMES = ('spep-game-pgdata', 'spep-game-redisdata')
SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def command(args, *, stdin=None, timeout=90):
    # Never echo args, child output, Compose config, container Env or database rows.
    result = subprocess.run(args, input=stdin, capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'operation failed: ' + args[0])
    return result.stdout


def select_mode(paths):
    """Union of changed paths over all un-applied commits, including reverted changes."""
    for path in paths:
        lower = path.lower()
        if (lower.startswith(('data/curated/', 'infra/src/main/resources/scenario/',
                              'infra/src/main/resources/db/migration/',
                              'app/game-engine/src/main/kotlin/opensamguk/engine/boot/',
                              'app/game-engine/src/main/kotlin/opensamguk/engine/config/',
                              'app/game-engine/src/main/resources/', 'app/game-engine/build.gradle'))
                or lower in ('docker/game-engine.dockerfile',
                             'app/game-engine/src/main/kotlin/opensamguk/engine/gameengineapplication.kt')
                or (lower.startswith('app/game-engine/') and lower.endswith('entrypoint.sh'))
                or '/seed/' in lower or '/migration/' in lower or '/scenario/' in lower
                or lower.startswith(('tools/rtk14/', 'tools/scenario/'))):
            return 'reset'
    return 'refresh'


def unapplied_mode(source, previous, checkout):
    require(bool(SHA.fullmatch(source)) and bool(SHA.fullmatch(previous)), 'invalid main revision')
    command(['git', '-c', 'safe.directory=' + str(checkout), '-C', str(checkout), 'merge-base', '--is-ancestor', previous, source])
    changed = command(['git', '-c', 'safe.directory=' + str(checkout), '-C', str(checkout), 'log', '--format=', '--name-only',
                       previous + '..' + source]).decode().splitlines()
    return select_mode(changed)


def image_contract(ref, config):
    require(ref.startswith(REGISTRY + '@') and DIGEST.fullmatch(ref.split('@')[-1]),
            'image must be immutable repository digest')
    require(bool(DIGEST.fullmatch(config)), 'invalid image config digest')
    return {'ref': ref, 'manifest': ref.split('@')[1], 'config': config}


def check_image(actual, expected, source):
    # Classic Docker uses config Id; containerd-backed hosts can use the platform manifest Id.
    image_contract(expected['ref'], expected['config'])
    require(expected['manifest'] == expected['ref'].split('@')[1], 'manifest contract mismatch')
    require(actual['Id'] in (expected['config'], expected['manifest']), 'pulled image identity mismatch')
    require(actual['Os'] == 'linux' and actual['Architecture'] == 'amd64', 'image platform mismatch')
    require(expected['ref'] in actual['RepoDigests'], 'image repository digest mismatch')
    require(actual['Revision'] == source, 'image source differs from built main')


def check_private(info):
    require(info['Running'] is True, 'PRIVATE container not running')
    require(not any((info['Ports'] or {}).values()), 'PRIVATE container has published port')
    require(not any(n.get('Aliases') for n in info['Networks'].values()), 'PRIVATE container has alias')
    require(info['Labels'].get('com.docker.compose.project') == 'opensamguk-spep', 'wrong PRIVATE project')


def check_tick(status, *, first=True):
    require(status.get('loopAlive') is True and status.get('paused') is False
            and status.get('recoveryReady') is True, 'daemon not ready/alive')
    require(type(status.get('failedTicks')) is int and status['failedTicks'] == 0, 'daemon tick failure')
    if first:
        require(type(status.get('successfulTicks')) is int and status['successfulTicks'] >= 1,
                'first successful tick not observed')
    clock = status.get('clock') or {}
    require(clock.get('tickSeconds') == 3600, 'daemon tick interval mismatch')
    require(isinstance(clock.get('lastTurnTime'), str)
            and clock['lastTurnTime'] not in ('', 'unavailable'), 'daemon clock unavailable')
    # The first immediate tick can retain 190/1/상. Calendar advancement is not a success gate.


WORLD_SQL = '''BEGIN READ ONLY;
SELECT row_to_json(t) FROM (
 SELECT (SELECT count(*) FROM world_state) AS worlds, id, scenario_code, tick_seconds,
        config->'maxgeneral' AS maxgeneral, jsonb_typeof(config->'maxgeneral') AS max_type,
        config->'block_general_create' AS block,
        config->>'firstTurnPolicy' AS first_policy, meta->>'lastTurnTime' AS last_turn,
        (SELECT value FROM game_kv WHERE world_id=1 AND "table"='game_env'
          AND namespace='game_env' AND key='maxgeneral') AS env_max,
        (SELECT jsonb_typeof(value) FROM game_kv WHERE world_id=1 AND "table"='game_env'
          AND namespace='game_env' AND key='maxgeneral') AS env_max_type,
        (SELECT value FROM game_kv WHERE world_id=1 AND "table"='game_env'
          AND namespace='game_env' AND key='block_general_create') AS env_block
 FROM world_state WHERE id=1) t;
ROLLBACK;'''


def check_world(row):
    require(row.get('worlds') == 1 and row.get('id') == 1
            and row.get('scenario_code') == 'scenario_3190', 'pep world identity mismatch')
    require(row.get('tick_seconds') == 3600, 'pep tick interval mismatch')
    require(type(row.get('maxgeneral')) is int and row['maxgeneral'] == 50
            and row.get('max_type') == 'number', 'config maxgeneral must be number50')
    require(type(row.get('env_max')) is int and row['env_max'] == 50
            and row.get('env_max_type') == 'number', 'game_env maxgeneral must be number50')
    require(row.get('block') == 1 and row.get('env_block') == 1, 'general creation block lost')
    require(row.get('first_policy') == 'immediate', 'reset first-turn policy mismatch')


def inspect_container(name):
    template = ('{"Running":{{json .State.Running}},"Ports":{{json .NetworkSettings.Ports}},'
                '"Networks":{{json .NetworkSettings.Networks}},"Labels":{{json .Config.Labels}},'
                '"Mounts":{{json .Mounts}}}')
    return json.loads(command(['docker', 'inspect', '--format', template, name]))


def fetch(name, port, path, token=''):
    require(path.startswith('/') and '\n' not in path and '"' not in path, 'invalid internal API path')
    config = f'url = "http://localhost:{port}{path}"\n'
    if token:
        require(not any(c in token for c in '\r\n"\\'), 'invalid supplied JWT')
        config += f'header = "Authorization: Bearer {token}"\n'
    raw = command(['docker', 'exec', '-i', name, 'curl', '--silent', '--fail',
                   '--max-redirs', '0', '--connect-timeout', '5', '--max-time', '30',
                   '--max-filesize', '67108864', '--write-out', '\n%{http_code}\n%{content_type}',
                   '--config', '-'], stdin=config.encode(), timeout=40)
    body, status, content = raw.rsplit(b'\n', 2)
    require(status == b'200', 'internal API did not return 200')
    return body, content.decode()


def get_json(name, port, path, token=''):
    body, content = fetch(name, port, path, token)
    require('application/json' in content, 'internal API did not return JSON')
    return json.loads(body)


def smoke_api():
    """A API probe adapted from K10 postreset probe-api.py; internal PRIVATE routes only."""
    api = PRIVATE[0]
    require(get_json(api, 8081, '/actuator/health').get('status') == 'UP', 'PRIVATE API unhealthy')
    basic = get_json(api, 8081, '/api/server-basic-info')
    game = basic.get('game') or {}
    require(game.get('maxUserCnt') == 50 and game.get('turnTerm') == 60
            and game.get('blockGeneralCreate') == 1, 'basic-info reset contract mismatch')
    require(game.get('scenario') == '동탁의 전횡과 반동탁연합' and basic.get('me') is None,
            'basic-info scenario/anonymous contract mismatch')
    preview = get_json(api, 8081, '/api/map/preview')
    require(bool(preview.get('cities')) and bool(preview.get('nations')), 'map preview empty')
    bake = preview.get('topdownBakeId')
    require(isinstance(bake, str) and re.fullmatch('[0-9a-f]{64}', bake), 'current fullbundle binding absent')
    manifest = get_json(api, 8081, '/api/map/topdown/' + bake + '/manifest.json')
    require(manifest.get('bakeId') == bake and manifest.get('partial') is False
            and manifest.get('inputFingerprint', {}).get('region') is None, 'fullbundle identity mismatch')
    files = manifest.get('files') or []
    require(bool(files), 'fullbundle has no files')
    for entry in files:
        name = entry.get('file', '')
        require(bool(re.fullmatch(r'[a-zA-Z0-9_.-]+', name)) and name not in ('.', '..'), 'unsafe bundle asset path')
        body, _ = fetch(api, 8081, '/api/map/topdown/' + bake + '/' + name)
        require(len(body) == entry.get('bytes')
                and hashlib.sha256(body).hexdigest() == entry.get('sha256'), 'fullbundle asset mismatch')
    # No token is generated and no account/provider provisioning is attempted here.
    token = os.environ.get('PEP_SMOKE_JWT', '')
    if token:
        get_json(api, 8081, '/api/my-page', token)
    return bool(token)


def poll(check, seconds):
    end = time.monotonic() + seconds
    while True:
        try:
            return check()
        except (ValueError, json.JSONDecodeError):
            if time.monotonic() >= end:
                raise
            time.sleep(3)


def db_world():
    raw = command(['docker', 'exec', '-i', DATA[0], 'sh', '-c',
                   'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                  stdin=WORLD_SQL.encode())
    row = json.loads(raw)
    check_world(row)
    return row


def override(images, mounts, bake, daemon):
    seed = {'SCENARIO_CODE': 'scenario_3190', 'SCENARIO_SEED_ENABLED': 'true',
            'SCENARIO_DIR': '', 'SCENARIO_LOOKUP_DIR': '', 'OPENSAMGUK_WORLD_ID': '1',
            'SERVER_GENERATION': '0', 'RESET_TURNTERM': '60', 'RESET_MAXGENERAL': '50',
            'RESET_FIRST_TURN': 'immediate', 'RESET_BLOCK_GENERAL_CREATE': '1'}
    services = {role: {'image': images[role]['ref']} for role in ROLES}
    services['game-engine']['environment'] = {**seed, 'JAVA_OPTS':
        '-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -Dopensamguk.daemon.enabled=' + str(daemon).lower()}
    services['game-api']['environment'] = {**seed, 'SERVER_ID': 'pep',
        'TOPDOWN_MAP_ROOT': '/app/data/map/topdown', 'TOPDOWN_BAKE_ID': bake}
    services['game-api']['volumes'] = mounts
    services['web-game']['environment'] = {'SERVER_ID': 'pep', 'GAME_API_URL': 'http://' + PRIVATE[0] + ':8081'}
    return {'services': services}


def summary(message):
    target = os.environ.get('GITHUB_STEP_SUMMARY')
    if target:
        with open(target, 'a') as stream:
            stream.write(message + '\n')
    print(message)


def apply(args):
    require(args.server == 'pep', 'only exact pep is authorized')
    require(args.mode in ('auto', 'refresh', 'reset'), 'invalid operation')
    require(bool(SHA.fullmatch(args.source)), 'invalid built main revision')
    images = json.loads(os.environ['PEP_IMAGES'])
    require(set(images) == set(ROLES), 'API/web/engine images all required')
    for expected in images.values():
        image_contract(expected['ref'], expected['config'])
    # Shared lock is interoperable with the current general operation helpers; no stale lock removal.
    with open('/tmp/opensamguk-production.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        marker = ROOT / '.pep-loop-source'
        interrupted = ROOT / '.pep-loop-incomplete'
        require(not interrupted.exists(), 'previous pep mutation incomplete; C0 recovery required')
        previous = marker.read_text().strip() if marker.exists() else BASELINE
        automatic = unapplied_mode(args.source, previous, args.checkout)
        mode = 'reset' if args.mode == 'reset' or automatic == 'reset' else 'refresh'
        names = command(['docker', 'ps', '-a', '--format', '{{.Names}}']).decode().splitlines()
        require('spep-game-api' not in names and 'spep-web-game' not in names, 'canonical public pep container exists')
        require(not inspect_container('opensamguk-deployer')['Running'], 'Root deployer must remain stopped')
        for name in PRIVATE:
            check_private(inspect_container(name))
        for name, role in zip(PRIVATE, ('game-api', 'web-game')):
            require(inspect_container(name)['Labels'].get('com.docker.compose.service') == role, 'PRIVATE service mismatch')
        for name, role in zip((ENGINE, *DATA), ('game-engine', 'game-postgres', 'game-redis')):
            info = inspect_container(name)
            require(info['Labels'].get('com.docker.compose.project') == 'opensamguk-spep'
                    and info['Labels'].get('com.docker.compose.service') == role, 'pep service ownership mismatch')
        for name, volume in zip(DATA, VOLUMES):
            require(any(m.get('Type') == 'volume' and m.get('Name') == volume
                        for m in inspect_container(name)['Mounts']), 'pep data volume identity mismatch')
        api = inspect_container(PRIVATE[0])
        mounts = [{'type': 'bind', 'source': m['Source'], 'target': m['Destination'], 'read_only': True}
                  for m in api['Mounts'] if m['Destination'] == '/app/data/map/topdown'
                  and m['Type'] == 'bind' and m['RW'] is False]
        require(len(mounts) == 1, 'current fullbundle readonly mount missing')
        bake = command(['docker', 'exec', PRIVATE[0], 'printenv', 'TOPDOWN_BAKE_ID']).decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', bake)), 'current fullbundle bake binding missing')
        # Check the existing DB before either operation. A drift is reported, never silently repaired.
        db_world()
        for role in ROLES:
            expected = images[role]
            command(['docker', 'pull', '--platform', 'linux/amd64', expected['ref']], timeout=300)
            template = ('{"Id":{{json .Id}},"Os":{{json .Os}},"Architecture":{{json .Architecture}},'
                        '"RepoDigests":{{json .RepoDigests}},"Revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}')
            actual = json.loads(command(['docker', 'image', 'inspect', '--format', template, expected['ref']]))
            check_image(actual, expected, args.source)
        current = command(['git', '-c', 'safe.directory=' + str(args.checkout), '-C', str(args.checkout), 'ls-remote', 'origin', 'refs/heads/main']).decode().split()[0]
        if current != args.source:
            summary('pep loop: newer main queued; stale build skipped before mutation.')
            return
        with tempfile.TemporaryDirectory(prefix='pep-loop-') as stage:
            paused, live = Path(stage) / 'paused.json', Path(stage) / 'live.json'
            paused.write_text(json.dumps(override(images, mounts, bake, False)))
            live.write_text(json.dumps(override(images, mounts, bake, True)))
            common = ['docker', 'compose', '-p', 'opensamguk-spep', '-f', str(ROOT / 'docker-compose.server.yml')]
            env = os.environ.copy()
            # Only this exact env file is consumed by existing Compose. Never source/read/print it.
            env.update(SERVER_ID='pep', COMPOSE_HOST_DIR=str(ROOT))
            def compose(file, *rest):
                with_env = common + ['-f', str(file), '--env-file', str(ROOT / 'servers/spep.env'), *rest]
                result = subprocess.run(with_env, env=env, capture_output=True, timeout=180)
                require(result.returncode == 0, 'pep Compose operation failed')
            stopped = False
            try:
                # A killed/timed-out run must not let a queued run delete freshly seeded data again.
                interrupted.write_text(mode + '\n')
                stopped = True
                command(['docker', 'stop', '--time', '60', *PRIVATE, ENGINE], timeout=120)
                command(['docker', 'container', 'rm', *PRIVATE, ENGINE])
                if mode == 'reset':
                    # No compose down -v, prune, wildcard, backup, shared-stack or gateway operation.
                    command(['docker', 'stop', '--time', '60', *DATA], timeout=120)
                    command(['docker', 'container', 'rm', *DATA])
                    for volume in VOLUMES:
                        require(not command(['docker', 'ps', '-aq', '--filter', 'volume=' + volume]).strip(),
                                'pep volume still has consumer')
                    command(['docker', 'volume', 'rm', *VOLUMES])
                    compose(paused, 'up', '-d', 'game-postgres', 'game-redis')
                    for name in DATA:
                        poll(lambda n=name: require(command(['docker', 'inspect', '--format',
                             '{{.State.Health.Status}}', n]).strip() == b'healthy', 'pep data service unhealthy'), 120)
                    compose(paused, 'up', '-d', '--no-deps', 'game-engine')
                    poll(db_world, 240)
                compose(live, 'up', '-d', '--no-deps', 'game-engine')
                compose(live, 'run', '-d', '--no-deps', '--name', PRIVATE[0], 'game-api')
                poll(lambda: require(get_json(PRIVATE[0], 8081, '/actuator/health').get('status') == 'UP',
                                     'PRIVATE API not ready'), 240)
                # Next.js image has node, not curl; no gateway or host route is consulted.
                compose(live, 'run', '-d', '--no-deps', '--name', PRIVATE[1], 'web-game')
                for name in PRIVATE:
                    check_private(inspect_container(name))
                for name in (PRIVATE[0], ENGINE):
                    require(command(['docker', 'exec', name, 'printenv', 'SERVER_GENERATION']).strip() == b'0',
                            'pep generation must remain zero')
                poll(lambda: require(command(['docker', 'exec', PRIVATE[1], 'node', '-e',
                    "fetch('http://localhost:3001/',{redirect:'manual'}).then(r=>process.exit([200,307].includes(r.status)?0:1)).catch(()=>process.exit(1))"])
                    == b'', 'PRIVATE web not ready'), 120)
                def tick():
                    status = get_json(ENGINE, 8082, '/admin/turn-daemon/status')
                    check_tick(status)
                    return status
                poll(tick, 240 if mode == 'reset' else 3900)
                require(bool(db_world().get('last_turn')), 'successful tick not persisted')
                authenticated = smoke_api()
                # Store only the applied source cursor, after ALL required checks pass. No receipt/evidence.
                (ROOT / 'pep-loop.compose.json').write_text(live.read_text())
                temporary = marker.with_suffix('.new')
                temporary.write_text(args.source + '\n')
                os.replace(temporary, marker)
                interrupted.unlink()
                summary(f'pep-{mode}: PASS · PRIVATE · 3190/world1/gen0/tick3600/max50/block1 · first tick + API/fullbundle PASS')
                if not authenticated:
                    summary('Authenticated API: supply pending (BOARD → CEO: pep test account/JWT).')
            except Exception:
                if stopped:
                    # Close only this operation's exact pep consumers. Do not retry reset/delete or open PUBLIC.
                    for name in (*PRIVATE, ENGINE):
                        subprocess.run(['docker', 'stop', '--time', '30', name], capture_output=True, timeout=45)
                raise


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='action', required=True)
    images = sub.add_parser('images')
    images.add_argument('--metadata-dir', required=True)
    run = sub.add_parser('apply')
    run.add_argument('--server', required=True)
    run.add_argument('--mode', choices=('auto', 'refresh', 'reset'), required=True)
    run.add_argument('--source', required=True)
    run.add_argument('--checkout', type=Path, required=True)
    args = parser.parse_args()
    if args.action == 'images':
        result = {}
        for role in ROLES:
            metadata = json.loads((Path(args.metadata_dir) / (role + '.json')).read_text())
            ref = REGISTRY + '@' + metadata['containerimage.digest']
            manifest = json.loads(command(['docker', 'buildx', 'imagetools', 'inspect', '--raw', ref]))
            require('manifests' not in manifest, 'build must publish single linux/amd64 platform manifest')
            result[role] = image_contract(ref, manifest['config']['digest'])
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write('images=' + json.dumps(result, separators=(',', ':')) + '\n')
    else:
        require(os.environ.get('GITHUB_REPOSITORY') == 'peppone-choi/opensamguk'
                and os.environ.get('GITHUB_REF') == 'refs/heads/main', 'operation requires repository main workflow')
        apply(args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        reason = str(error) if type(error) is ValueError else 'runtime command/input unavailable'
        summary('pep loop FAILED: ' + reason + '; no automatic reset retry or PUBLIC opening.')
        raise SystemExit(1) from None
