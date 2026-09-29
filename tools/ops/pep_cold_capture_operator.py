#!/usr/bin/env python3
"""Fail-closed preflight for a future PEP cold capture window.

This command is read-only. It intentionally has no stop, restart, or reset mode.
"""

import argparse
import json
from pathlib import Path
import re

from game_server_recovery import (REDIS_CMD, VOLUMES, Recovery, RecoveryError,
                                  checked_path, require, selected_env)


SERVICES = ('web-game', 'game-api', 'game-engine', 'game-postgres', 'game-redis')
MAINTENANCE_GET = r'''
import json, os, sys, urllib.error, urllib.request
token = os.environ.get("DEPLOYER_TOKEN", "")
if not token:
    raise SystemExit(2)
request = urllib.request.Request("http://localhost:9000/maintenance",
    headers={"Authorization": "Bearer " + token}, method="GET")
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
try:
    response = urllib.request.build_opener(NoRedirect).open(request, timeout=10)
except (OSError, urllib.error.URLError):
    raise SystemExit(1)
with response:
    body = response.read(4097)
    if response.status != 200 or len(body) > 4096:
        raise SystemExit(1)
    value = json.loads(body)
    if value.get("capability") != "maintenance-v1" or value.get("state") != "drained":
        raise SystemExit(1)
    sys.stdout.write(json.dumps({"capability": "maintenance-v1", "state": "drained"}))
'''


def selected_runtime_fields(path):
    """Read only non-secret values from the private env without echoing its contents."""
    fields = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and key in {'SCENARIO_CODE', 'SCENARIO_LOOKUP_DIR', 'IMAGE_TAG', 'WEB_GAME_TAG'}:
            require(key not in fields, 'duplicate runtime field')
            fields[key] = value.strip().strip('"\'')
    require(re.fullmatch(r'scenario_[0-9]{1,8}', fields.get('SCENARIO_CODE', '')) is not None,
            'invalid source scenario code')
    require(fields.get('SCENARIO_LOOKUP_DIR', '') in {'', '/data/scenarios'},
            'unsupported scenario lookup')
    for key in ('IMAGE_TAG', 'WEB_GAME_TAG'):
        require(re.fullmatch(r'[0-9a-f]{40}', fields.get(key, '')) is not None,
                'immutable image tag required')
    return fields


class PepColdCapturePreflight:
    def __init__(self, recovery=None):
        self.recovery = recovery or Recovery()

    def inspect(self, stack):
        stack = checked_path(stack, directory=True)
        env_path = checked_path(stack / 'servers/spep.env', private=True)
        require((stack / 'servers/.deployer-maintenance').is_file(),
                'closed maintenance marker required')
        require(not (stack / 'servers/.deployer-lifecycle-journal').exists(),
                'lifecycle journal requires investigation')
        self.recovery.docker.run(['container', 'exec', 'opensamguk-deployer',
                                  'python3', '-c', MAINTENANCE_GET])
        selected = selected_env(env_path, 'pep')
        runtime = selected_runtime_fields(env_path)
        require(selected.get('COMPOSE_HOST_DIR', str(stack)) == str(stack),
                'control directory mismatch')
        scenario_tree = checked_path(stack / 'data/scenarios', directory=True)
        require(scenario_tree.stat().st_dev == stack.stat().st_dev,
                'scenario directory must be on the control filesystem')

        images = {}
        source_ids = {}
        for service in SERVICES:
            name = 'spep-' + service
            obj = self.recovery.inspect('container', name)
            state = obj['State']
            require(obj['Name'] == '/' + name and state['Running'] is True and
                    state['Status'] == 'running' and not state.get('OOMKilled'),
                    'source service is not running with expected identity')
            labels = obj['Config'].get('Labels') or {}
            require(labels.get('com.docker.compose.project') == 'opensamguk-spep' and
                    labels.get('com.docker.compose.service') == service,
                    'source service ownership mismatch')
            image_id = obj['Image']
            require(re.fullmatch(r'sha256:[0-9a-f]{64}', image_id) is not None and
                    self.recovery.inspect('image', image_id)['Id'] == image_id,
                    'source image identity mismatch')
            if service in ('game-api', 'game-engine'):
                mounts = obj['Mounts']
                require(len(mounts) == 1 and mounts[0].get('Type') == 'bind' and
                        mounts[0].get('Source') == str(scenario_tree) and
                        mounts[0].get('Destination') == '/data/scenarios' and
                        mounts[0].get('RW') is False,
                        'source scenario bind mismatch')
                config_env = dict(part.split('=', 1) for part in obj['Config'].get('Env', []) if '=' in part)
                require(config_env.get('OPENSAMGUK_WORLD_ID') == selected['OPENSAMGUK_WORLD_ID'],
                        'source world identity mismatch')
            elif service in VOLUMES:
                suffix, destination = VOLUMES[service]
                mounts = obj['Mounts']
                require(len(mounts) == 1 and mounts[0].get('Type') == 'volume' and
                        mounts[0].get('Name') == 'spep-' + suffix and
                        mounts[0].get('Destination') == destination,
                        'source storage mount mismatch')
                config_env = dict(part.split('=', 1) for part in obj['Config'].get('Env', []) if '=' in part)
                if service == 'game-postgres':
                    require(config_env.get('PGDATA', '/var/lib/postgresql/data') == '/var/lib/postgresql/data' and
                            config_env.get('POSTGRES_USER') == selected['GAME_POSTGRES_USER'] and
                            config_env.get('POSTGRES_DB') == selected['GAME_POSTGRES_DB'] and
                            obj['Config']['Cmd'] == ['postgres'],
                            'source database identity mismatch')
                else:
                    require(obj['Config']['Cmd'] == REDIS_CMD,
                            'source Redis persistence mismatch')
            else:
                require(obj['Mounts'] == [], 'unexpected web mount')
            images[service] = image_id
            source_ids[service] = obj['Id']
        for service, suffix in (('game-postgres', 'game-pgdata'),
                                ('game-redis', 'game-redisdata')):
            volume = self.recovery.inspect('volume', 'spep-' + suffix)
            require(volume['Name'] == 'spep-' + suffix and volume['Driver'] == 'local' and
                    not volume.get('Options') and
                    (volume.get('Labels') or {}).get('com.docker.compose.project') == 'opensamguk-spep' and
                    (volume.get('Labels') or {}).get('com.docker.compose.volume') == suffix,
                    'source volume ownership mismatch')
        return {
            'server': 'pep', 'maintenance': 'drained', 'source_scenario': runtime['SCENARIO_CODE'],
            'scenario_lookup': 'bundled' if runtime['SCENARIO_LOOKUP_DIR'] == '' else 'external',
            'external_scenario_file_count': sum(1 for p in scenario_tree.iterdir() if p.is_file()),
            'source_images': images, 'source_container_ids': source_ids,
            'world_identity_checked': selected['OPENSAMGUK_WORLD_ID'].isdigit(),
            'cold_capture_executed': False,
        }

    def prepare(self, stack):
        with self.recovery.locked():
            return self.inspect(Path(stack))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare'])
    parser.add_argument('--server', required=True)
    parser.add_argument('--confirm', required=True)
    parser.add_argument('--stack-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        require(args.server == 'pep' and args.confirm == 'PREPARE pep',
                'PEP-only confirmation mismatch')
        print(json.dumps(PepColdCapturePreflight().prepare(args.stack_dir), sort_keys=True))
        return 0
    except (RecoveryError, OSError, KeyError, TypeError, ValueError):
        print('PEP cold-capture preflight failed; no service was changed', flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
