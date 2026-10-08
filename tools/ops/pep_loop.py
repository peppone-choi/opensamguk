#!/usr/bin/env python3
"""D143 exact-pep operation preserving its existing exposure and selected map. No shared-stack operation or secret output."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

from pep_scenarios import scenario

ROOT = Path('/home/peppone_choi/opensamguk-docker')
BASELINE = 'b7dc6b49516d9b6996c3a1bf7fb6858f0309adc1'
REGISTRY = 'ghcr.io/peppone-choi/opensamguk'
ROLES = ('game-api', 'game-engine', 'web-game')
PRIVATE = ('spep-game-api-validation', 'spep-web-game-validation')
PUBLIC = ('spep-game-api', 'spep-web-game')
ENGINE = 'spep-game-engine'
DATA = ('spep-game-postgres', 'spep-game-redis')
VOLUMES = ('spep-game-pgdata', 'spep-game-redisdata')
SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
REPOSITORY = 'peppone-choi/opensamguk'
REQUIRED_CI = frozenset(('contracts', 'jvm', 'web (game)', 'web (gateway)', 'web-shared', 'naming-lint'))
# One approved, already-seeded reset recovery; these contracts cannot be overridden by CLI/env.
RESUME_SOURCE = 'c2146634449889cd8ad87bb36ef071a174178c26'
RESUME_BAKE = 'dc7b0ad33600721cd9c10db2fa31b08d3d13d24a120c4fc25d8cb162bd2d36e1'
RESUME_PINS = {'release': 'province-world-20261003',
               'tilesSha256': '9ba39f9bbdc3d1b636351f0265dc90b00496b427a8ef3e667efad070b073b05d',
               'worldJsonSha256': 'db0913cee9296d1c4326d118c42bad0bb71f75c7f6215141bd3e3d7fffee88e3',
               'roadsSha256': '3759bf7492591c346f5646908a7813caecc647b2bfa8dde2450b4ca58d218356'}
RESUME_IMAGES = {role: {'ref': REGISTRY + '@sha256:' + manifest,
                       'manifest': 'sha256:' + manifest, 'config': 'sha256:' + config}
                 for role, manifest, config in (
                     ('game-api', '78bee668f3212ab835431dca0dd2909fe7ebd7ddc2e825d88cc21e5308c6d08e',
                      '7482e6ac3e5db78c627796619959af49d0164f13ed12c30170702c0a82964207'),
                     ('game-engine', '6370a0534a022854f9de930155cc68db40abba2915b8e52ea4e7c08e29ca4ad1',
                      '215ea8334d7da429ea2d0afb44bd1e05dd5dc44a8efbd9d14e091b8c70f64e87'),
                     ('web-game', 'ce091917cfeb39806f5e2ff50eb0849c6684a662fdb750c08a06e19be20d8c3d',
                      'a647d6fac2fa6715abd524c74b54b483f70f1d9c3174f916d38f2f3faf9c6aed'))}


class AdmissionDeferred(RuntimeError):
    """Unavailable/racing observations defer this operation without inventing main RED."""


def github_json(path, **query):
    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        raise AdmissionDeferred('main CI observation unavailable')
    url = 'https://api.github.com/repos/' + REPOSITORY + '/' + path
    if query:
        url += '?' + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + token,
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except (OSError, ValueError, urllib.error.URLError):
        # Do not expose request headers, response bodies or exception text.
        raise AdmissionDeferred('main CI observation unavailable') from None


def github_items(path, key, **query):
    items = []
    for page in range(1, 11):
        data = github_json(path, per_page=100, page=page, **query)
        if (not isinstance(data, dict) or not isinstance(data.get(key), list)
                or type(data.get('total_count')) is not int):
            raise AdmissionDeferred('main CI response incomplete')
        items.extend(data[key])
        if len(items) >= data['total_count']:
            return items
        if not data[key]:
            break
    raise AdmissionDeferred('main CI history incomplete')


def failed_ci(item):
    return item.get('conclusion') in ('failure', 'timed_out')


def ci_state(source):
    """Exact main-push CI only; all attempts retain confirmed failures until descendant GREEN."""
    runs = github_items('actions/workflows/ci.yml/runs', 'workflow_runs',
                        branch='main', event='push', head_sha=source)
    if any(not isinstance(run, dict) for run in runs):
        raise AdmissionDeferred('main CI response incomplete')
    matching = [r for r in runs if r.get('head_sha') == source and r.get('event') == 'push'
                and r.get('head_branch') == 'main'
                and (r.get('head_repository') or {}).get('full_name') == REPOSITORY]
    if not matching:
        return {'green': False, 'red': False}
    latest = max(matching, key=lambda r: r['id'])
    green, red = False, False
    for run in matching:
        attempts = run.get('run_attempt')
        if type(attempts) is not int or not 1 <= attempts <= 100:
            raise AdmissionDeferred('main CI attempt history incomplete')
        latest_jobs = {}
        for attempt in range(1, attempts + 1):
            observed = github_json(f"actions/runs/{run['id']}/attempts/{attempt}")
            if (not isinstance(observed, dict) or observed.get('head_sha') != source
                    or observed.get('id') != run['id'] or observed.get('run_attempt') != attempt):
                raise AdmissionDeferred('main CI attempt source mismatch')
            red |= failed_ci(observed)
            jobs = github_items(f"actions/runs/{run['id']}/attempts/{attempt}/jobs", 'jobs')
            for job in jobs:
                if job.get('head_sha') != source or job.get('run_id') != run['id']:
                    raise AdmissionDeferred('main CI job source mismatch')
                # Partial reruns retain successful upstream jobs from previous attempts.
                latest_jobs[job['name']] = job
                red |= failed_ci(job) or any(failed_ci(step) for step in job.get('steps', []))
        current = github_json(f"actions/runs/{run['id']}")
        if (not isinstance(current, dict) or current.get('id') != run['id']
                or current.get('head_sha') != source or current.get('run_attempt') != attempts):
            raise AdmissionDeferred('main CI changed while observing attempts')
        red |= failed_ci(run) or failed_ci(current)
        if run['id'] == latest['id']:
            green = (current.get('status') == 'completed' and current.get('conclusion') == 'success'
                     and REQUIRED_CI <= latest_jobs.keys()
                     and all(latest_jobs[name].get('status') == 'completed'
                             and latest_jobs[name].get('conclusion') == 'success' for name in REQUIRED_CI)
                     and all(job.get('status') == 'completed'
                             and job.get('conclusion') in ('success', 'skipped')
                             and not any(failed_ci(step) for step in job.get('steps', []))
                             for job in latest_jobs.values()))
    return {'green': green, 'red': bool(red)}


def github_git_env(checkout):
    origin = source_git(checkout, 'remote', 'get-url', 'origin').decode().strip()
    require(origin in ('https://github.com/' + REPOSITORY, 'https://github.com/' + REPOSITORY + '.git'),
            'snapshot origin must be the exact repository')
    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        raise AdmissionDeferred('main ancestry credential unavailable')
    env = os.environ.copy()
    env.pop('GITHUB_TOKEN', None)
    env.pop('PEP_SMOKE_JWT', None)
    # checkout persist-credentials=false: authorize only this exact private Git remote,
    # through child environment config (never argv, disk config, output or Compose).
    env.update(GIT_TERMINAL_PROMPT='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_COUNT='1',
               GIT_CONFIG_KEY_0='http.' + origin + '.extraheader',
               GIT_CONFIG_VALUE_0='AUTHORIZATION: basic ' + base64.b64encode(
                   ('x-access-token:' + token).encode()).decode())
    return env


def source_git(checkout, *args, network=False):
    env = github_git_env(checkout) if network else None
    identity = {}
    if args[0] == 'fetch' and os.geteuid() == 0:
        # The privileged apply must not create root-owned Git objects that break the next checkout.
        owner = checkout.stat()
        require(owner.st_uid != 0, 'runner checkout must have an unprivileged owner')
        identity = {'user': owner.st_uid, 'group': owner.st_gid, 'extra_groups': []}
    return command(['git', '-c', 'safe.directory=' + str(checkout), '-C', str(checkout), *args],
                   env=env, **identity)


def remote_main(checkout):
    try:
        value = source_git(checkout, 'ls-remote', 'origin', 'refs/heads/main', network=True).decode().split()
        if len(value) == 2 and SHA.fullmatch(value[0]) and value[1] == 'refs/heads/main':
            return value[0]
    except (ValueError, OSError, subprocess.TimeoutExpired):
        pass
    raise AdmissionDeferred('main head observation unavailable')


def admit_snapshot(checkout, source, previous=None):
    """Pin S, allow P <= S <= H, and re-observe H immediately before returning to mutation."""
    require(bool(SHA.fullmatch(source)), 'invalid built main revision')
    require(source_git(checkout, 'rev-parse', 'HEAD').decode().strip() == source,
            'checkout differs from built main snapshot')
    if previous is not None:
        require(bool(SHA.fullmatch(previous)), 'invalid applied main revision')
        source_git(checkout, 'merge-base', '--is-ancestor', previous, source)
    for _ in range(3):
        head = remote_main(checkout)
        # Fetch objects only, never move the snapshot checkout or an operator branch.
        try:
            source_git(checkout, 'fetch', '--no-tags', '--no-write-fetch-head', 'origin', head, network=True)
        except (ValueError, OSError, subprocess.TimeoutExpired):
            raise AdmissionDeferred('main ancestry observation unavailable') from None
        source_git(checkout, 'merge-base', '--is-ancestor', source, head)
        revisions = source_git(checkout, 'rev-list', '--ancestry-path', source + '..' + head).decode().splitlines()
        states = {revision: ci_state(revision) for revision in (source, *revisions)}
        if remote_main(checkout) != head:
            continue
        # Unknown/pending at S never admits images, even though it is not global main RED.
        if not states[source]['green']:
            raise AdmissionDeferred('snapshot whole CI and required six are not GREEN')
        for revision, state in states.items():
            if state['red']:
                recovered = any(candidate != revision and candidate_state['green']
                                and subprocess.run(['git', '-c', 'safe.directory=' + str(checkout),
                                                    '-C', str(checkout), 'merge-base', '--is-ancestor',
                                                    revision, candidate], capture_output=True).returncode == 0
                                for candidate, candidate_state in states.items())
                require(recovered, 'confirmed main CI RED; verified descendant GREEN required')
        return head
    raise AdmissionDeferred('main head kept changing before mutation')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def command(args, *, stdin=None, timeout=90, env=None, **identity):
    # Never echo args, child output, Compose config, container Env or database rows.
    result = subprocess.run(args, input=stdin, capture_output=True, timeout=timeout, env=env, **identity)
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


def check_world(row, code='scenario_3190'):
    require(row.get('worlds') == 1 and row.get('id') == 1
            and row.get('scenario_code') == code, 'pep world identity mismatch')
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
    media = content.split(';', 1)[0].strip().lower()
    require(re.fullmatch(r"application/(?:json|[a-z0-9!#$%&'*+.^_`|~-]+\+json)", media) is not None,
            'internal API did not return JSON')
    return json.loads(body)


def smoke_api(api=PRIVATE[0], title='동탁의 전횡과 반동탁연합', expected_bake=None, expected_pins=None):
    """A API probe adapted from K10 postreset probe-api.py; internal routes only; no gateway mutation."""
    require(get_json(api, 8081, '/actuator/health').get('status') == 'UP', 'pep API unhealthy')
    basic = get_json(api, 8081, '/api/server-basic-info')
    game = basic.get('game') or {}
    require(game.get('maxUserCnt') == 50 and game.get('turnTerm') == 60
            and game.get('blockGeneralCreate') == 1, 'basic-info reset contract mismatch')
    require(game.get('scenario') == title and basic.get('me') is None,
            'basic-info scenario/anonymous contract mismatch')
    preview = get_json(api, 8081, '/api/map/preview')
    require(bool(preview.get('cities')) and bool(preview.get('nations')), 'map preview empty')
    bake = preview.get('topdownBakeId')
    require(isinstance(bake, str) and re.fullmatch('[0-9a-f]{64}', bake), 'current fullbundle binding absent')
    require(expected_bake is None or bake == expected_bake, 'selected bake pin changed')
    manifest = get_json(api, 8081, '/api/map/topdown/' + bake + '/manifest.json')
    if expected_pins is not None:
        require(map_pins(manifest) == expected_pins, 'selected release/three map pins changed')
    require(manifest.get('bakeId') == bake and manifest.get('partial') is False
            and manifest.get('inputFingerprint', {}).get('region') is None, 'fullbundle identity mismatch')
    files = manifest.get('files') or []
    require(bool(files), 'fullbundle has no files')
    for entry in files:
        name = entry.get('file', '')
        # Keep the exact publication allowlist from TopdownMapArtifacts, including grid paths.
        require(isinstance(name, str) and bool(re.fullmatch(
            r'(?:grid/L0/[0-9]+_[0-9]+\.bin\.gz|grid/L2\.bin\.gz|places\.json\.gz|defects\.json)', name)),
            'unsafe bundle asset path')
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


def db_world(code='scenario_3190'):
    raw = command(['docker', 'exec', '-i', DATA[0], 'sh', '-c',
                   'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                  stdin=WORLD_SQL.encode())
    row = json.loads(raw)
    check_world(row, code)
    return row


def override(images, mounts, bake, daemon, code='scenario_3190', api=PRIVATE[0]):
    seed = {'SCENARIO_CODE': code, 'SCENARIO_SEED_ENABLED': 'true',
            'SCENARIO_DIR': '', 'SCENARIO_LOOKUP_DIR': '', 'OPENSAMGUK_WORLD_ID': '1',
            'SERVER_GENERATION': '0', 'RESET_TURNTERM': '60', 'RESET_MAXGENERAL': '50',
            'RESET_FIRST_TURN': 'immediate', 'RESET_BLOCK_GENERAL_CREATE': '1'}
    services = {role: {'image': images[role]['ref']} for role in ROLES}
    services['game-engine']['environment'] = {**seed, 'JAVA_OPTS':
        '-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -Dopensamguk.daemon.enabled=' + str(daemon).lower()}
    services['game-api']['environment'] = {**seed, 'SERVER_ID': 'pep',
        'TOPDOWN_MAP_ROOT': '/app/data/map/topdown', 'TOPDOWN_BAKE_ID': bake}
    services['game-api']['volumes'] = mounts
    services['web-game']['environment'] = {'SERVER_ID': 'pep', 'GAME_API_URL': 'http://' + api + ':8081'}
    return {'services': services}


def exposure(info):
    return {'ports': info.get('Ports') or {}, 'aliases': {
        key: sorted(value.get('Aliases') or []) for key, value in info['Networks'].items()}}


def consumers(names):
    public = [name in names and inspect_container(name)['Running'] for name in PUBLIC]
    private = [name in names and inspect_container(name)['Running'] for name in PRIVATE]
    require((all(public) and not any(private)) or (all(private) and not any(public)),
            'pep consumers must have one complete existing exposure mode')
    return PUBLIC if all(public) else PRIVATE


def legacy_overlay(info):
    paths = info['Labels'].get('com.docker.compose.project.config_files', '').split(',')
    if len(paths) != 2 or paths[0] != str(ROOT / 'docker-compose.server.yml'):
        return False
    role = info['Labels'].get('com.docker.compose.service')
    stage = 'paused' if role in ('game-postgres', 'game-redis') else 'live'
    return bool(re.fullmatch(r'/tmp/pep-loop-[a-z0-9_]{8}/' + stage + r'\.json', paths[1]))


def compose_files(info, *, private=False):
    raw = info['Labels'].get('com.docker.compose.project.config_files', '')
    paths = [Path(p) for p in raw.split(',') if p]
    require(bool(paths), 'existing pep Compose provenance unavailable')
    if legacy_overlay(info):
        require(private and info['Running'] is True
                and info['Labels'].get('com.docker.compose.project') == 'opensamguk-spep'
                and info['Labels'].get('com.docker.compose.service') in (*ROLES, 'game-postgres', 'game-redis')
                and info['Labels'].get('com.docker.compose.project.working_dir') == str(ROOT)
                and not paths[0].is_symlink()
                and not paths[1].parent.exists() and not paths[1].parent.is_symlink()
                and not paths[1].is_symlink(), 'unverified legacy PRIVATE Compose provenance')
        # Only the known, deleted helper-generated overlay is reconstructed from checked runtime state.
        # The stable base remains mandatory; no missing operator file or arbitrary /tmp path is admitted.
        paths = paths[:1]
    for path in paths:
        require(path.is_absolute() and path.is_file() and path.resolve().is_relative_to(ROOT.resolve())
                and path.suffix in ('.yml', '.yaml', '.json'), 'existing pep Compose path unavailable')
    require(paths[0] == ROOT / 'docker-compose.server.yml', 'unexpected pep Compose base')
    # Only existing Compose consumes these files. Python never reads env/override contents.
    return [p for p in paths if p != ROOT / 'pep-loop.compose.json']


def check_legacy_runtime(before, targets, code, bake):
    require(targets == PRIVATE, 'legacy recovery requires existing PRIVATE consumers')
    for name in targets:
        check_private(before[name])
    expected = {'SCENARIO_CODE': code, 'SCENARIO_SEED_ENABLED': 'true',
                'SCENARIO_DIR': '', 'SCENARIO_LOOKUP_DIR': '', 'OPENSAMGUK_WORLD_ID': '1',
                'SERVER_GENERATION': '0', 'RESET_TURNTERM': '60', 'RESET_MAXGENERAL': '50',
                'RESET_FIRST_TURN': 'immediate', 'RESET_BLOCK_GENERAL_CREATE': '1'}
    for name in (PRIVATE[0], ENGINE):
        for key, value in expected.items():
            require(command(['docker', 'exec', name, 'printenv', key]).decode().strip() == value,
                    'legacy pep settings drift; C0 recovery required')
    for name, key, value in ((PRIVATE[0], 'SERVER_ID', 'pep'),
                             (PRIVATE[0], 'TOPDOWN_MAP_ROOT', '/app/data/map/topdown'),
                             (PRIVATE[0], 'TOPDOWN_BAKE_ID', bake),
                             (PRIVATE[1], 'SERVER_ID', 'pep'),
                             (PRIVATE[1], 'GAME_API_URL', 'http://' + PRIVATE[0] + ':8081')):
        require(command(['docker', 'exec', name, 'printenv', key]).decode().strip() == value,
                'legacy pep binding drift; C0 recovery required')
    # Compose alone consumes the exact env file. Only service names are returned, never rendered config/Env.
    env = os.environ.copy()
    env.pop('GITHUB_TOKEN', None)
    env.update(SERVER_ID='pep', COMPOSE_HOST_DIR=str(ROOT))
    result = subprocess.run(['docker', 'compose', '--project-directory', str(ROOT), '-p', 'opensamguk-spep',
                             '-f', str(ROOT / 'docker-compose.server.yml'), '--env-file',
                             str(ROOT / 'servers/spep.env'), 'config', '--services'],
                            cwd=ROOT, env=env, capture_output=True, timeout=90)
    require(result.returncode == 0 and set(result.stdout.decode().splitlines())
            == {*ROLES, 'game-postgres', 'game-redis'}, 'legacy stable Compose services unavailable')


def map_pins(manifest):
    fingerprint = manifest.get('inputFingerprint') or {}
    result = {key: fingerprint.get(key) for key in ('tilesSha256', 'worldJsonSha256', 'roadsSha256')}
    require(all(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) for value in result.values()),
            'selected bundle three pins unavailable')
    release = manifest.get('mapRelease')
    require(isinstance(release, str) and bool(release), 'selected map release unavailable')
    return {'release': release, **result}


def preserve_map(api, bake, checkout):
    preview = get_json(api, 8081, '/api/map/preview')
    require(preview.get('topdownBakeId') == bake, 'existing world/bake binding unavailable')
    manifest = get_json(api, 8081, '/api/map/topdown/' + bake + '/manifest.json')
    require(manifest.get('bakeId') == bake and manifest.get('partial') is False
            and (manifest.get('inputFingerprint') or {}).get('region') is None,
            'existing bundle must be full and bound')
    pins = map_pins(manifest)
    require(pins['release'] == 'province-world-20261003', 'unsupported existing map release')
    paths = {'tilesSha256': 'data/map/province-tiles.json',
             'worldJsonSha256': 'infra/src/main/resources/map/han-world-v3.json',
             'roadsSha256': 'data/map/han-land-roads-v1.json'}
    for key, relative in paths.items():
        require(hashlib.sha256((checkout / relative).read_bytes()).hexdigest() == pins[key],
                'candidate main differs from existing selected map; C0 bundle coordination required')
    return pins


def summary(message):
    target = os.environ.get('GITHUB_STEP_SUMMARY')
    if target:
        with open(target, 'a') as stream:
            stream.write(message + '\n')
    print(message)


def resume_publication():
    # Read-only registry observation; this recovery never changes the gateway.
    sql = '''BEGIN READ ONLY;
SELECT row_to_json(t) FROM (SELECT p.state,p.publicly_visible,p.revision,s.generation,s.scenario_code
FROM game_server_publication p JOIN game_server s USING(server_id) WHERE p.server_id='pep') t;
ROLLBACK;'''
    row = json.loads(command(['docker', 'exec', '-i', 'opensamguk-gateway-postgres', 'sh', '-c',
                             'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                            stdin=sql.encode()))
    require(row == {'state': 'PUBLIC', 'publicly_visible': True, 'revision': 3,
                    'generation': 0, 'scenario_code': 'scenario_3190'}, 'approved pep publication changed')
    return row


def resume_settings(name):
    # Docker emits ONLY these nonsecret operating settings, including for stopped containers.
    keys = ('SCENARIO_CODE', 'SCENARIO_SEED_ENABLED', 'SCENARIO_DIR', 'SCENARIO_LOOKUP_DIR',
            'OPENSAMGUK_WORLD_ID', 'SERVER_GENERATION', 'RESET_TURNTERM', 'RESET_MAXGENERAL',
            'RESET_FIRST_TURN', 'RESET_BLOCK_GENERAL_CREATE', 'SCENARIO_QA_TURNTERM',
            'SERVER_ID', 'TOPDOWN_MAP_ROOT', 'TOPDOWN_BAKE_ID', 'GAME_API_URL', 'JAVA_OPTS')
    select = ' '.join('(eq (index (split . "=") 0) "' + key + '")' for key in keys)
    raw = command(['docker', 'inspect', '--format',
                   '{{range .Config.Env}}{{if or ' + select + '}}{{println .}}{{end}}{{end}}', name])
    result = {}
    for line in raw.decode().splitlines():
        if not line:
            continue  # Template println plus docker inspect's final LF can emit empty lines.
        key, separator, value = line.partition('=')
        require(separator and key in keys and key not in result, 'invalid selected pep settings')
        result[key] = value
    if name in (PUBLIC[0], ENGINE):
        expected = {'SCENARIO_CODE': 'scenario_3190', 'SCENARIO_SEED_ENABLED': 'true',
                    'SCENARIO_DIR': '', 'SCENARIO_LOOKUP_DIR': '', 'OPENSAMGUK_WORLD_ID': '1',
                    'SERVER_GENERATION': '0', 'RESET_TURNTERM': '60', 'RESET_MAXGENERAL': '50',
                    'RESET_FIRST_TURN': 'immediate', 'RESET_BLOCK_GENERAL_CREATE': '1'}
        require(all(result.get(key) == value for key, value in expected.items())
                and not result.get('SCENARIO_QA_TURNTERM'), 'approved seeded pep settings changed')
    if name == PUBLIC[0]:
        require(result.get('SERVER_ID') == 'pep' and result.get('TOPDOWN_MAP_ROOT') == '/app/data/map/topdown'
                and result.get('TOPDOWN_BAKE_ID') == RESUME_BAKE, 'approved pep map binding changed')
    elif name == ENGINE:
        require('-Dopensamguk.daemon.enabled=true' in result.get('JAVA_OPTS', '').split(),
                'approved pep daemon disabled')
    elif name == PUBLIC[1]:
        require(result.get('SERVER_ID') == 'pep' and result.get('GAME_API_URL') == 'http://' + PUBLIC[0] + ':8081',
                'approved pep web binding changed')
    return result


def resume_image(role, name=None):
    expected = RESUME_IMAGES[role]
    template = ('{"Id":{{json .Id}},"Os":{{json .Os}},"Architecture":{{json .Architecture}},'
                '"RepoDigests":{{json .RepoDigests}},"Revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}')
    actual = json.loads(command(['docker', 'image', 'inspect', '--format', template, expected['ref']]))
    check_image(actual, expected, RESUME_SOURCE)  # Inspect only; no pull/build or new image.
    if name is not None:
        bound = json.loads(command(['docker', 'inspect', '--format',
                                   '{"Id":{{json .Image}},"Ref":{{json .Config.Image}}}', name]))
        require(bound['Id'] in (expected['config'], expected['manifest'])
                and bound['Ref'] == expected['ref'], 'pep container image changed')


def resume_exposure(name, info):
    # NetworkSettings.Ports can be empty while stopped; preserve the durable port declarations.
    spec = json.loads(command(['docker', 'inspect', '--format',
                              '{"Bindings":{{json .HostConfig.PortBindings}},"Exposed":{{json .Config.ExposedPorts}}}', name]))
    bindings, exposed = spec.get('Bindings') or {}, spec.get('Exposed') or {}
    require(isinstance(bindings, dict) and isinstance(exposed, dict), 'pep port declarations unavailable')
    ports = {key: bindings.get(key) or None for key in set(bindings) | set(exposed)}
    require(all(value is None or isinstance(value, list) and all(isinstance(binding, dict)
                and isinstance(binding.get('HostPort'), str) and binding['HostPort'] for binding in value)
                for value in ports.values()), 'dynamic pep port cannot be preserved')
    return {'ports': ports, 'aliases': exposure(info)['aliases']}


def resume_sources(args):
    require(args.source == RESUME_SOURCE, 'only approved seeded snapshot can resume')
    require(bool(SHA.fullmatch(args.helper_source)) and args.helper_source != args.source,
            'reviewed helper main source required')
    helper_files = ('tools/ops/pep_loop.py', 'tools/ops/pep_scenarios.py')
    require(Path(__file__).resolve() == (args.helper_checkout / helper_files[0]).resolve()
            and Path(scenario.__code__.co_filename).resolve() == (args.helper_checkout / helper_files[1]).resolve(),
            'recovery must execute the reviewed helper checkout')
    # F is a separate clean main checkout; S remains the exact runtime/image checkout.
    source_git(args.helper_checkout, 'diff', '--quiet', args.helper_source, '--', *helper_files)
    admit_snapshot(args.helper_checkout, args.helper_source)
    source_git(args.helper_checkout, 'merge-base', '--is-ancestor', args.source, args.helper_source)
    admit_snapshot(args.checkout, args.source, BASELINE)
    source_git(args.checkout, 'diff', '--quiet', args.source, '--',
               'app/gateway-api/src/main/resources/scenario-reset-catalog.json',
               'infra/src/main/resources/scenario/scenario_3190.json')
    scenario(args.checkout, 'scenario_3190')


def normalize_resume_ports(ports):
    result = {}
    for port, bindings in ports.items():
        if not bindings:
            result[port] = None
        else:
            result[port] = sorted({('*' if binding.get('HostIp', '') in ('', '0.0.0.0', '::')
                                     else binding['HostIp'], binding['HostPort']) for binding in bindings})
    return result


def compose_web_projection(compose, env):
    # Compose alone consumes env/overrides. The child emits ONLY public web declaration fields;
    # the parent never captures or prints rendered configuration or any service environment.
    projection = """import json,sys
web=json.load(sys.stdin)['services']['web-game']
networks=web.get('networks') or {}
print(json.dumps({'image':web.get('image'),'name':web.get('container_name'),'ports':web.get('ports'),
 'expose':web.get('expose'),'networks':{name:{'aliases':(spec or {}).get('aliases',[])}
 for name,spec in networks.items()}}))"""
    process = subprocess.Popen([*compose, 'config', '--no-env-resolution', '--format', 'json'],
                               cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        selected = subprocess.run(['python3', '-B', '-c', projection], stdin=process.stdout,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, timeout=90)
        process.stdout.close()
        require(process.wait(timeout=90) == 0 and selected.returncode == 0, 'web declaration observation unavailable')
        return json.loads(selected.stdout)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)


def check_resume_web_declaration(spec):
    require(isinstance(spec, dict) and spec.get('name') == PUBLIC[1]
            and spec.get('expose') in (['3001'], ['3001/tcp'], [3001])
            and spec.get('networks') == {'opensamguk-net': {'aliases': []}},
            'approved PUBLIC web name/network/declaration changed')
    ports = spec.get('ports')
    require(isinstance(ports, list) and len(ports) == 1 and isinstance(ports[0], dict),
            'approved PUBLIC web single port required')
    port = ports[0]
    require(str(port.get('published')) == '3101' and str(port.get('target')) == '3001'
            and port.get('protocol', 'tcp') == 'tcp' and port.get('host_ip') in (None, '')
            and port.get('mode', 'ingress') == 'ingress', 'approved PUBLIC web port changed')


def check_resumed_web(info):
    require(normalize_resume_ports(info.get('Ports') or {}) == {'3001/tcp': [('*', '3101')]}
            and set(info['Networks']) == {'opensamguk-net'}, 'resumed PUBLIC web exposure changed')
    network = info['Networks']['opensamguk-net']
    aliases = set(network.get('Aliases') or [])
    dns = aliases | set(network.get('DNSNames') or [])
    identifier = command(['docker', 'inspect', '--format', '{{.Id}}', PUBLIC[1]]).decode().strip()
    require(bool(re.fullmatch('[0-9a-f]{64}', identifier)), 'resumed web identity unavailable')
    required = {PUBLIC[1], 'web-game'}
    require(required <= dns and aliases <= required | {identifier, identifier[:12]}
            and dns <= required | {identifier, identifier[:12]}, 'resumed web DNS/extra alias changed')


def resume_preflight(args):
    interrupted, marker = ROOT / '.pep-loop-incomplete', ROOT / '.pep-loop-source'
    require(interrupted.is_file() and not interrupted.is_symlink()
            and interrupted.read_text().strip() == 'reset', 'approved incomplete reset required')
    require(not marker.exists() and not marker.is_symlink(), 'approved reset cursor must be incomplete')
    require(args.web_declaration == 'docker66-public-3101', 'approved PUBLIC web declaration required')
    resume_sources(args)
    names = command(['docker', 'ps', '-a', '--format', '{{.Names}}']).decode().splitlines()
    require(all(name in names for name in (PUBLIC[0], ENGINE, *DATA))
            and not any(name in names for name in PRIVATE), 'approved pep recovery consumers unavailable')
    before = {name: inspect_container(name) for name in (PUBLIC[0], ENGINE, *DATA)}
    if PUBLIC[1] in names:
        before[PUBLIC[1]] = inspect_container(PUBLIC[1])
    require(not inspect_container('opensamguk-deployer')['Running'], 'Root deployer must remain stopped')
    for name, info in before.items():
        role = {PUBLIC[0]: 'game-api', PUBLIC[1]: 'web-game', ENGINE: 'game-engine',
                DATA[0]: 'game-postgres', DATA[1]: 'game-redis'}[name]
        require(info['Labels'].get('com.docker.compose.project') == 'opensamguk-spep'
                and info['Labels'].get('com.docker.compose.service') == role, 'pep recovery ownership mismatch')
        compose_files(info)
        require(info['Running'] is (name in DATA), 'approved stopped consumers/healthy data required')
        if name not in DATA:
            info['ResumeExposure'] = resume_exposure(name, info)
    for name, volume in zip(DATA, VOLUMES):
        require(any(m.get('Type') == 'volume' and m.get('Name') == volume for m in before[name]['Mounts'])
                and command(['docker', 'inspect', '--format', '{{.State.Health.Status}}', name]).strip() == b'healthy',
                'approved pep data volume/health changed')
    db_world('scenario_3190')  # Existing world prevents the engine boot path from reseeding.
    publication = resume_publication()
    settings = {name: resume_settings(name) for name in (PUBLIC[0], ENGINE)}
    for role in ROLES:
        name = {'game-api': PUBLIC[0], 'game-engine': ENGINE, 'web-game': PUBLIC[1]}[role]
        resume_image(role, name if name in before else None)
    mounts = [m for m in before[PUBLIC[0]]['Mounts'] if m.get('Destination') == '/app/data/map/topdown']
    map_root = ROOT / 'data/topdown/pep'
    require(len(mounts) == 1 and mounts[0].get('Type') == 'bind' and mounts[0].get('RW') is False
            and mounts[0].get('Source') == str(map_root), 'approved readonly fullmap mount changed')
    paths = (map_root / RESUME_BAKE / 'manifest.json', map_root / 'bakes' / RESUME_BAKE / 'manifest.json')
    manifests = [path for path in paths if path.is_file()]
    require(bool(manifests), 'approved fullmap manifest unavailable')
    for path in manifests:
        require(not path.is_symlink() and path.resolve().is_relative_to(map_root.resolve()), 'unsafe fullmap manifest')
        manifest = json.loads(path.read_bytes())
        require(manifest.get('bakeId') == RESUME_BAKE and manifest.get('partial') is False
                and (manifest.get('inputFingerprint') or {}).get('region') is None
                and map_pins(manifest) == RESUME_PINS, 'approved fullmap identity changed')
    for key, path in {'tilesSha256': 'data/map/province-tiles.json',
                      'worldJsonSha256': 'infra/src/main/resources/map/han-world-v3.json',
                      'roadsSha256': 'data/map/han-land-roads-v1.json'}.items():
        require(hashlib.sha256((args.checkout / path).read_bytes()).hexdigest() == RESUME_PINS[key],
                'runtime snapshot fullmap inputs changed')
    files = [ROOT / name for name in ('docker-compose.server.yml', 'operator-web-game.compose.json', 'pep-loop.compose.json')]
    require(all(p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(ROOT.resolve()) for p in files),
            'approved web Compose provenance unavailable')
    env = os.environ.copy()
    env.pop('GITHUB_TOKEN', None)
    env.update(SERVER_ID='pep', COMPOSE_HOST_DIR=str(ROOT))
    compose = ['docker', 'compose', '--project-directory', str(ROOT), '-p', 'opensamguk-spep',
               *[arg for path in files for arg in ('-f', str(path))],
               '--env-file', str(ROOT / 'servers/spep.env')]
    web = compose_web_projection(compose, env)
    require(web.get('image') == RESUME_IMAGES['web-game']['ref'], 'approved web Compose image changed')
    check_resume_web_declaration(web)
    if PUBLIC[1] in before:
        declared = {**before[PUBLIC[1]], 'Ports': before[PUBLIC[1]]['ResumeExposure']['ports']}
        check_resumed_web(declared)
        resume_settings(PUBLIC[1])
    return before, settings, publication, compose, env


def finalize_resume():
    marker, interrupted = ROOT / '.pep-loop-source', ROOT / '.pep-loop-incomplete'
    require(not marker.exists() and not marker.is_symlink()
            and interrupted.read_text().strip() == 'reset', 'recovery checkpoint changed')
    with tempfile.NamedTemporaryFile(dir=ROOT, prefix='.pep-loop-source.', delete=False) as stream:
        temporary = Path(stream.name)
        stream.write((RESUME_SOURCE + '\n').encode())
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, marker)
        try:
            directory = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)  # The source cursor is durable before the incomplete marker is removed.
            finally:
                os.close(directory)
            interrupted.unlink()  # Only after every required recovery check and atomic S cursor commit.
        except Exception:
            marker.unlink()  # Roll back only the cursor created by this locked transaction.
            raise
    finally:
        temporary.unlink(missing_ok=True)


def resume_reset(args):
    require(args.server == 'pep' and args.source == RESUME_SOURCE, 'only exact approved pep reset can resume')
    with open('/tmp/opensamguk-production.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before, settings, publication, compose, env = resume_preflight(args)
        # Re-observe both main sources immediately before the first mutation, without moving S or F.
        resume_sources(args)
        db_world('scenario_3190')
        require(resume_publication() == publication and not inspect_container('opensamguk-deployer')['Running']
                and all(inspect_container(name) == before[name] for name in DATA),
                'pep recovery preconditions changed before start')
        require(not (ROOT / '.pep-loop-source').exists() and not (ROOT / '.pep-loop-source').is_symlink()
                and (ROOT / '.pep-loop-incomplete').read_text().strip() == 'reset', 'recovery checkpoint changed')
        try:
            command(['docker', 'start', ENGINE, PUBLIC[0]])
            poll(lambda: require(get_json(PUBLIC[0], 8081, '/actuator/health').get('status') == 'UP',
                                 'pep API not ready'), 240)
            if PUBLIC[1] in before:
                command(['docker', 'start', PUBLIC[1]])
            else:
                command([*compose, 'up', '-d', '--no-deps', '--no-build', '--no-recreate', '--pull', 'never', 'web-game'],
                        env=env, timeout=180)
            for name in (*PUBLIC, ENGINE):
                after = inspect_container(name)
                role = {PUBLIC[0]: 'game-api', PUBLIC[1]: 'web-game', ENGINE: 'game-engine'}[name]
                require(after['Running'] is True
                        and after['Labels'].get('com.docker.compose.project') == 'opensamguk-spep'
                        and after['Labels'].get('com.docker.compose.service') == role, 'resumed pep consumer ownership changed')
                if name in before:
                    require(normalize_resume_ports(exposure(after)['ports'])
                            == normalize_resume_ports(before[name]['ResumeExposure']['ports'])
                            and exposure(after)['aliases'] == before[name]['ResumeExposure']['aliases']
                            and resume_exposure(name, after) == before[name]['ResumeExposure']
                            and after['Mounts'] == before[name]['Mounts'],
                            'resumed pep exposure/mount changed')
                if name == PUBLIC[1]:
                    check_resumed_web(after)
                current = resume_settings(name)
                require(name not in settings or current == settings[name], 'resumed pep settings changed')
                resume_image({PUBLIC[0]: 'game-api', PUBLIC[1]: 'web-game', ENGINE: 'game-engine'}[name], name)
            for name in DATA:
                require(inspect_container(name) == before[name], 'existing pep data service changed')
            require(resume_publication() == publication, 'pep registry changed during recovery')
            require(preserve_map(PUBLIC[0], RESUME_BAKE, args.checkout) == RESUME_PINS, 'resumed fullmap changed')
            poll(lambda: command(['docker', 'exec', PUBLIC[1], 'node', '-e',
                 "fetch('http://localhost:3001/',{redirect:'manual'}).then(r=>process.exit([200,307].includes(r.status)?0:1)).catch(()=>process.exit(1))"]), 120)
            poll(lambda: check_tick(get_json(ENGINE, 8082, '/admin/turn-daemon/status')), 3900)
            require(bool(db_world('scenario_3190').get('last_turn')), 'successful tick not persisted')
            authenticated = smoke_api(PUBLIC[0], scenario(args.checkout, 'scenario_3190')[1], RESUME_BAKE, RESUME_PINS)
            # Recheck state after the full smoke and only then finalize the original reset.
            require(resume_publication() == publication, 'pep registry changed during smoke')
            db_world('scenario_3190')
            require(not inspect_container('opensamguk-deployer')['Running'], 'Root deployer restarted during recovery')
            finalize_resume()
        except Exception:
            for name in (*PUBLIC, ENGINE):
                try:
                    subprocess.run(['docker', 'stop', '--time', '30', name], capture_output=True, timeout=45)
                except (OSError, subprocess.TimeoutExpired):
                    pass  # Attempt the remaining exact consumers even if one stop is unavailable.
            raise
        summary('pep-resume-reset: PASS · same c214 images/seeded DB preserved · first tick + API/fullbundle PASS')
        if not authenticated:
            summary('Authenticated API: supply pending (BOARD → CEO: pep test account/JWT).')


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
        admit_snapshot(args.checkout, args.source, previous)
        automatic = unapplied_mode(args.source, previous, args.checkout)
        mode = 'reset' if args.mode == 'reset' or automatic == 'reset' else 'refresh'
        names = command(['docker', 'ps', '-a', '--format', '{{.Names}}']).decode().splitlines()
        targets = consumers(names)
        api_name, web_name = targets
        before = {name: inspect_container(name) for name in (*targets, ENGINE, *DATA)}
        files = {name: compose_files(info, private=targets == PRIVATE) for name, info in before.items()}
        publication = {name: exposure(before[name]) for name in targets}
        require(not inspect_container('opensamguk-deployer')['Running'], 'Root deployer must remain stopped')
        if targets == PRIVATE:
            for name in targets:
                check_private(before[name])
        for name, role in zip(targets, ('game-api', 'web-game')):
            require(before[name]['Labels'].get('com.docker.compose.service') == role
                    and before[name]['Labels'].get('com.docker.compose.project') == 'opensamguk-spep', 'pep service mismatch')
        for name, role in zip((ENGINE, *DATA), ('game-engine', 'game-postgres', 'game-redis')):
            info = inspect_container(name)
            require(info['Labels'].get('com.docker.compose.project') == 'opensamguk-spep'
                    and info['Labels'].get('com.docker.compose.service') == role, 'pep service ownership mismatch')
        for name, volume in zip(DATA, VOLUMES):
            require(any(m.get('Type') == 'volume' and m.get('Name') == volume
                        for m in inspect_container(name)['Mounts']), 'pep data volume identity mismatch')
        api = before[api_name]
        mounts = [{'type': 'bind', 'source': m['Source'], 'target': m['Destination'], 'read_only': True}
                  for m in api['Mounts'] if m['Destination'] == '/app/data/map/topdown'
                  and m['Type'] == 'bind' and m['RW'] is False]
        require(len(mounts) == 1, 'current fullbundle readonly mount missing')
        bake = command(['docker', 'exec', api_name, 'printenv', 'TOPDOWN_BAKE_ID']).decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', bake)), 'current fullbundle bake binding missing')
        # Check the existing DB before either operation. A drift is reported, never silently repaired.
        # Unselected automatic resets preserve the verified running selection. Only manual reset defaults.
        current_code = command(['docker', 'exec', ENGINE, 'printenv', 'SCENARIO_CODE']).decode().strip()
        scenario(args.checkout, current_code)  # Unknown/missing/unprepared current state cannot reach deletion.
        db_world(current_code)
        requested = getattr(args, 'scenario_code', None)
        selected = requested if mode == 'reset' else current_code
        if mode == 'reset' and requested is None and args.mode != 'reset':
            selected = current_code
        code, title = scenario(args.checkout, selected)
        if mode == 'refresh' and requested is not None:
            require(requested == current_code, 'refresh cannot change scenario selection')
        pins = preserve_map(api_name, bake, args.checkout)
        if any(legacy_overlay(info) for info in before.values()):
            check_legacy_runtime(before, targets, current_code, bake)
        for role in ROLES:
            expected = images[role]
            command(['docker', 'pull', '--platform', 'linux/amd64', expected['ref']], timeout=300)
            template = ('{"Id":{{json .Id}},"Os":{{json .Os}},"Architecture":{{json .Architecture}},'
                        '"RepoDigests":{{json .RepoDigests}},"Revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}')
            actual = json.loads(command(['docker', 'image', 'inspect', '--format', template, expected['ref']]))
            check_image(actual, expected, args.source)
        with tempfile.TemporaryDirectory(prefix='pep-loop-') as stage:
            paused, live = Path(stage) / 'paused.json', Path(stage) / 'live.json'
            paused.write_text(json.dumps(override(images, mounts, bake, False, code, api_name)))
            live.write_text(json.dumps(override(images, mounts, bake, True, code, api_name)))
            common = ['docker', 'compose', '--project-directory', str(ROOT), '-p', 'opensamguk-spep']
            persistent = ROOT / 'pep-loop.compose.json'
            env = os.environ.copy()
            env.pop('GITHUB_TOKEN', None)  # CI observation credential does not enter Compose.
            # Only this exact env file is consumed by existing Compose. Never source/read/print it.
            env.update(SERVER_ID='pep', COMPOSE_HOST_DIR=str(ROOT))
            def compose(file, name, *rest):
                # Persistent path is recorded by Compose so later loops retain operator provenance.
                temporary = persistent.with_suffix('.new')
                temporary.write_text(file.read_text())
                os.replace(temporary, persistent)
                inherited = [arg for path in files[name] for arg in ('-f', str(path))]
                with_env = common + inherited + ['-f', str(persistent), '--env-file', str(ROOT / 'servers/spep.env'), *rest]
                result = subprocess.run(with_env, cwd=ROOT, env=env, capture_output=True, timeout=180)
                require(result.returncode == 0, 'pep Compose operation failed')
            stopped = False
            # Last admission is after pulls/staging and immediately before the first mutation.
            # Once admitted, finish the same S through smoke/cursor even if newer main queues.
            admit_snapshot(args.checkout, args.source, previous)
            try:
                # A killed/timed-out run must not let a queued run delete freshly seeded data again.
                interrupted.write_text(mode + '\n')
                stopped = True
                command(['docker', 'stop', '--time', '60', *targets, ENGINE], timeout=120)
                command(['docker', 'container', 'rm', *targets, ENGINE])
                if mode == 'reset':
                    # No compose down -v, prune, wildcard, backup, shared-stack or gateway operation.
                    command(['docker', 'stop', '--time', '60', *DATA], timeout=120)
                    command(['docker', 'container', 'rm', *DATA])
                    for volume in VOLUMES:
                        require(not command(['docker', 'ps', '-aq', '--filter', 'volume=' + volume]).strip(),
                                'pep volume still has consumer')
                    command(['docker', 'volume', 'rm', *VOLUMES])
                    for name, role in zip(DATA, ('game-postgres', 'game-redis')):
                        compose(paused, name, 'up', '-d', '--no-deps', role)
                    for name in DATA:
                        poll(lambda n=name: require(command(['docker', 'inspect', '--format',
                             '{{.State.Health.Status}}', n]).strip() == b'healthy', 'pep data service unhealthy'), 120)
                    compose(paused, ENGINE, 'up', '-d', '--no-deps', 'game-engine')
                    poll(lambda: db_world(code), 240)
                compose(live, ENGINE, 'up', '-d', '--no-deps', 'game-engine')
                if targets == PRIVATE:
                    compose(live, api_name, 'run', '-d', '--no-deps', '--name', api_name, 'game-api')
                else:
                    compose(live, api_name, 'up', '-d', '--no-deps', 'game-api')
                poll(lambda: require(get_json(api_name, 8081, '/actuator/health').get('status') == 'UP',
                                     'pep API not ready'), 240)
                # Next.js image has node, not curl; no gateway or host route is consulted.
                if targets == PRIVATE:
                    compose(live, web_name, 'run', '-d', '--no-deps', '--name', web_name, 'web-game')
                else:
                    compose(live, web_name, 'up', '-d', '--no-deps', 'web-game')
                for name in targets:
                    after = inspect_container(name)
                    require(after['Running'] is True and exposure(after) == publication[name], 'pep exposure changed')
                    if targets == PRIVATE:
                        check_private(after)
                for name in (api_name, ENGINE):
                    require(command(['docker', 'exec', name, 'printenv', 'SERVER_GENERATION']).strip() == b'0',
                            'pep generation must remain zero')
                poll(lambda: require(command(['docker', 'exec', web_name, 'node', '-e',
                    "fetch('http://localhost:3001/',{redirect:'manual'}).then(r=>process.exit([200,307].includes(r.status)?0:1)).catch(()=>process.exit(1))"])
                    == b'', 'pep web not ready'), 120)
                def tick():
                    status = get_json(ENGINE, 8082, '/admin/turn-daemon/status')
                    check_tick(status)
                    return status
                poll(tick, 240 if mode == 'reset' else 3900)
                require(bool(db_world(code).get('last_turn')), 'successful tick not persisted')
                authenticated = smoke_api(api_name, title, bake, pins)
                # Store only the applied source cursor, after ALL required checks pass. No receipt/evidence.
                (ROOT / 'pep-loop.compose.json').write_text(live.read_text())
                temporary = marker.with_suffix('.new')
                temporary.write_text(args.source + '\n')
                os.replace(temporary, marker)
                interrupted.unlink()
                summary(f'pep-{mode}: PASS · existing exposure preserved · {code}/world1/gen0/tick3600/max50/block1 · first tick + API/fullbundle PASS')
                if not authenticated:
                    summary('Authenticated API: supply pending (BOARD → CEO: pep test account/JWT).')
            except Exception:
                if stopped:
                    # Close only this operation's exact pep consumers. Do not retry reset/delete or open PUBLIC.
                    for name in (*targets, ENGINE):
                        subprocess.run(['docker', 'stop', '--time', '30', name], capture_output=True, timeout=45)
                raise


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='action', required=True)
    images = sub.add_parser('images')
    images.add_argument('--metadata-dir', required=True)
    admission = sub.add_parser('admit-ci')
    admission.add_argument('--source', required=True)
    admission.add_argument('--checkout', type=Path, required=True)
    run = sub.add_parser('apply')
    run.add_argument('--server', required=True)
    run.add_argument('--mode', choices=('auto', 'refresh', 'reset'), required=True)
    run.add_argument('--source', required=True)
    run.add_argument('--checkout', type=Path, required=True)
    run.add_argument('--scenario-code', help='explicit reset selection; omission defaults to the approved catalog')
    resume = sub.add_parser('resume-reset', help='C0-approved nondelete recovery of the seeded c214 reset only')
    resume.add_argument('--server', required=True)
    resume.add_argument('--source', required=True)
    resume.add_argument('--checkout', type=Path, required=True, help='unchanged c214 runtime checkout S')
    resume.add_argument('--helper-source', required=True, help='reviewed, normally merged main helper source F')
    resume.add_argument('--helper-checkout', type=Path, required=True)
    resume.add_argument('--web-declaration', required=True, choices=('docker66-public-3101',),
                        help='C0-approved existing Compose PUBLIC declaration; no historical snapshot claim')
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
        if args.action == 'admit-ci':
            admit_snapshot(args.checkout, args.source)
            summary('pep snapshot admission: whole CI + required six GREEN; main lineage admitted.')
        elif args.action == 'resume-reset':
            resume_reset(args)
        else:
            apply(args)


if __name__ == '__main__':
    try:
        main()
    except AdmissionDeferred as error:
        summary('pep loop DEFERRED: ' + str(error) + '; operation requires a new observation before mutation.')
        raise SystemExit(75) from None
    except (ValueError, OSError, KeyError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        reason = str(error) if type(error) is ValueError else 'runtime command/input unavailable'
        summary('pep loop FAILED: ' + reason + '; no automatic reset retry or PUBLIC opening.')
        raise SystemExit(1) from None
