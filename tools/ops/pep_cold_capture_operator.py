#!/usr/bin/env python3
"""Fail-closed preflight for a future PEP cold capture window.

This command is read-only. It intentionally has no stop, restart, or reset mode.
"""

import argparse
import io
import json
from pathlib import Path
import re
import shutil
import stat
import uuid

from game_server_recovery import (REDIS_CMD, VOLUMES, Recovery, RecoveryError,
                                  checked_path, digest, json_bytes, require, selected_env,
                                  write_private)
from pep_application_drill import PepApplicationDrill, ScenarioTreeDigest, SourceEngineInputs


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


def scenario_inventory(tree):
    """Capture relative names and bytes without following scenario links."""
    tree = checked_path(tree, directory=True)
    inventory = {}
    for path in sorted(tree.rglob('*')):
        relative = path.relative_to(tree).as_posix()
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            inventory[relative] = {'type': 'directory'}
        else:
            require(stat.S_ISREG(mode) and path.stat().st_nlink == 1,
                    'scenario tree contains a link or special file')
            inventory[relative] = {'type': 'file', **digest(path)}
    return inventory


def preserve_scenario_tree(source, bundle):
    """Make a private exact companion for a completed cold bundle."""
    bundle = checked_path(bundle, directory=True, private=True)
    checked_path(bundle / 'manifest.json', private=True)
    source = checked_path(source, directory=True)
    before = scenario_inventory(source)
    companion = bundle.with_name(bundle.name + '.scenario')
    require(not companion.exists() and not companion.is_symlink(),
            'existing scenario companion refused')
    companion.mkdir(mode=0o700)
    write_private(companion / 'INCOMPLETE', b'Scenario copy has not been verified.\n')
    tree = companion / 'tree'
    shutil.copytree(source, tree, symlinks=True)
    for path in sorted(tree.rglob('*')):
        if path.is_dir() and not path.is_symlink():
            path.chmod(0o700)
        elif path.is_file() and not path.is_symlink():
            path.chmod(0o600)
    tree.chmod(0o700)
    require(scenario_inventory(source) == before == scenario_inventory(tree),
            'effective scenario tree changed during capture')
    manifest_sha = digest(bundle / 'manifest.json')['sha256']
    scenario = ScenarioTreeDigest.capture(tree, manifest_sha)
    write_private(companion / 'manifest.json', json_bytes(scenario.manifest()))
    (companion / 'INCOMPLETE').unlink()
    return tree, scenario


def require_complete_old_application_proof(storage, application, authenticated_read):
    """Refuse a recovery success claim without isolated login and API reads."""
    require(isinstance(storage, dict) and storage.get('success') is True and
            isinstance(storage.get('manifest_sha256'), str),
            'successful storage verification required')
    require(application is not None and
            application.bundle_manifest_sha256 == storage['manifest_sha256'] and
            application.status.get('serviceMaterialized') is True and
            application.status.get('recoveryReady') is True and
            application.cleanup == {'success': True, 'remaining_resources': []},
            'matching isolated engine materialization required')
    require(isinstance(authenticated_read, dict) and
            authenticated_read.get('source') == 'isolated' and
            authenticated_read.get('bundle_manifest_sha256') == storage['manifest_sha256'] and
            authenticated_read.get('world_id') == application.world_id and
            isinstance(authenticated_read.get('checks'), dict) and
            set(authenticated_read['checks']) == {'login', 'identity', 'server_entry',
                                                  'world_read', 'map_read'} and
            all(value is True for value in authenticated_read['checks'].values()),
            'isolated authenticated read proof required')


def require_qa_gate(gate):
    require(isinstance(gate, dict) and set(gate) ==
            {'battle', 'w4', 'head_sha', 'scenario_code', 'city_count'} and
            gate['battle'] == 'PASS' and gate['w4'] == 'PASS' and
            isinstance(gate['head_sha'], str) and
            re.fullmatch(r'[0-9a-f]{40}', gate['head_sha']) is not None and
            gate['scenario_code'] == 'scenario_990002' and
            type(gate['city_count']) is int and gate['city_count'] == 1447,
            'battle and W4 PASS for exact 1447-city QA candidate required')


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
            'world_id_syntax_checked': True,
            'cold_capture_executed': False,
        }

    def prepare(self, stack):
        with self.recovery.locked():
            return self.inspect(Path(stack))


class PepColdCaptureOperator(PepColdCapturePreflight):
    """Programmatic cold capture stage; no CLI entry exists while auth drill is missing."""

    def __init__(self, recovery=None, drill=None):
        super().__init__(recovery)
        self.drill = drill or PepApplicationDrill()

    def _stop(self, service, source_id, *, storage=False):
        name = 'spep-' + service
        self.recovery.docker.run(['container', 'stop', '--time', '120', source_id])
        state = self.recovery.inspect('container', name)
        require(state['Id'] == source_id and state['State']['Running'] is False and
                state['State']['Status'] == 'exited' and
                not state['State'].get('OOMKilled') and
                state['State'].get('ExitCode') in ((0,) if storage else (0, 143)),
                'source shutdown did not complete cleanly')

    def _nonterminal_inbox(self, env):
        query = ("SELECT count(*) FROM command_inbox WHERE world_id = " +
                 env['OPENSAMGUK_WORLD_ID'] + " AND status IN ('ACCEPTED','CLAIMED');")
        command = ['container', 'exec', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                   '-i', 'spep-game-postgres', 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
                   '-h', '/var/run/postgresql', '-U', env['GAME_POSTGRES_USER'],
                   '-d', env['GAME_POSTGRES_DB']]
        result = self.recovery.docker.run(command, stdin=io.BytesIO(query.encode())).strip()
        require(result == b'0', 'nonterminal command inbox must be empty')

    def capture_and_prove(self, *, stack, backup_root, qa_gate):
        """Leave the old stack stopped; a separate reviewed cutover owns final recovery."""
        require_qa_gate(qa_gate)
        stack = checked_path(stack, directory=True)
        backup_root = checked_path(backup_root, directory=True, private=True)
        with self.recovery.locked():
            before = self.inspect(stack)
            env = selected_env(stack / 'servers/spep.env', 'pep')
            inspections = {service: self.recovery.inspect('container', 'spep-' + service)
                           for service in (*SERVICES,)}
            source_inputs = SourceEngineInputs.from_inspections('pep', inspections)
            token = uuid.uuid4().hex
            operation = backup_root / ('pep-cold-operation-' + token)
            operation.mkdir(mode=0o700)
            status = {'phase': 'preflight', 'success': False, 'old_stack_stopped': False,
                      'authenticated_read_verified': False, 'bundle': None}
            write_private(operation / 'status.json', json_bytes(status))
            try:
                for service in ('web-game', 'game-api', 'game-engine'):
                    status['phase'] = 'stopping-' + service
                    write_private(operation / 'status.json', json_bytes(status), replace=True)
                    self._stop(service, before['source_container_ids'][service])
                    status['phase'] = 'stopped-' + service
                    write_private(operation / 'status.json', json_bytes(status), replace=True)
                source_pg = self.recovery.postgres_check('spep-game-postgres', env,
                    socket='/var/run/postgresql', read_only=True)
                self._nonterminal_inbox(env)
                source_redis = self.recovery.redis_check('spep-game-redis', socket=None)
                for service in ('game-redis', 'game-postgres'):
                    status['phase'] = 'stopping-' + service
                    write_private(operation / 'status.json', json_bytes(status), replace=True)
                    self._stop(service, before['source_container_ids'][service], storage=True)
                    status['phase'] = 'stopped-' + service
                    write_private(operation / 'status.json', json_bytes(status), replace=True)
                status['old_stack_stopped'] = True
                bundle = self.recovery.capture(server='pep', confirm='BACKUP pep',
                    stack_dir=stack, backup_root=backup_root)
                status['phase'], status['bundle'] = 'captured', str(bundle)
                write_private(operation / 'status.json', json_bytes(status), replace=True)
                verified = self.recovery.verify(server='pep', confirm='VERIFY pep', bundle=bundle)
                require(verified['success'] is True and verified['postgres'] == source_pg and
                        verified['redis'] == source_redis,
                        'isolated storage does not match committed source')
                status['phase'] = 'storage-verified'
                write_private(operation / 'status.json', json_bytes(status), replace=True)
                tree, scenario = preserve_scenario_tree(stack / 'data/scenarios', bundle)
                application = self.drill.prove(self.recovery, bundle, source_inputs, tree, scenario)
                require(application.bundle_manifest_sha256 == verified['manifest_sha256'] and
                        application.status.get('serviceMaterialized') is True and
                        application.status.get('recoveryReady') is True and
                        application.cleanup == {'success': True, 'remaining_resources': []},
                        'isolated old engine did not rehydrate cleanly')
                status['phase'] = 'old-engine-materialized'
                write_private(operation / 'status.json', json_bytes(status), replace=True)
                return {'operation': str(operation), 'bundle': str(bundle),
                        'storage_verified': True, 'old_engine_materialized': True,
                        'authenticated_read_verified': False, 'ready_for_reset': False}
            except BaseException:
                status['phase'] = 'failed-after-' + status['phase']
                write_private(operation / 'status.json', json_bytes(status), replace=True)
                raise


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
