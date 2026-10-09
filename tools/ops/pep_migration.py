#!/usr/bin/env python3
"""Explicit preserving cold rehearsal. No reset, volume deletion or candidate live apply."""
import argparse
import io
import json
from pathlib import Path
import re
import uuid

import pep_loop
from game_server_recovery import (SERVICES, Recovery, RecoveryError, checked_path, digest,
                                 json_bytes, read_json, require, selected_env, write_private)
from pep_application_drill import PepApplicationDrill, SourceEngineInputs
from pep_cold_capture_operator import PepColdCapturePreflight, preserve_scenario_tree, scenario_inventory
from pep_migration_clone import StorageClone, redis_fingerprint
import pep_preserving_topology as topology_contract
import pep_topdown_catalog as topdown_contract


class CandidateAdmission:
    """No supplied PASS booleans: observe exact main CI and already-present immutable images."""
    def verify(self, recovery, checkout, source, previous, images):
        require(isinstance(images, dict) and set(images) == set(pep_loop.ROLES),
                'exact three-role immutable image contract required')
        pep_loop.admit_snapshot(checkout, source, previous)
        result = {}
        for role in pep_loop.ROLES:
            expected = images[role]
            require(isinstance(expected, dict) and set(expected) == {'ref', 'manifest', 'config'},
                    'image contract fields mismatch')
            require(all(isinstance(value, str) for value in expected.values()), 'image contract scalar types mismatch')
            pep_loop.image_contract(expected['ref'], expected['config'])
            obj = recovery.inspect('image', expected['ref'])
            pep_loop.check_image(dict(Id=obj['Id'], Os=obj['Os'], Architecture=obj['Architecture'],
                RepoDigests=obj.get('RepoDigests') or [],
                Revision=(obj['Config'].get('Labels') or {}).get('org.opencontainers.image.revision')),
                expected, source)
            result[role] = obj['Id']
        return result


def stable_identity(obj):
    """In-memory source identity; not serialized because Config contains private settings."""
    return {key: obj[key] for key in ('Id', 'Name', 'Image', 'Config', 'HostConfig', 'Mounts')}


def source_exposure(obj):
    return topology_contract.exposure(obj)


class PreservingMigration:
    def __init__(self, recovery=None, admission=None, clone_factory=StorageClone):
        self.recovery = recovery or Recovery()
        self.admission = admission or CandidateAdmission()
        self.clone_factory = clone_factory

    def preflight(self, stack):
        topology, before = topology_contract.observe(self.recovery)
        fullbundle = any(m.get('Destination') == '/app/data/map/topdown' for m in before['game-api']['Mounts'])
        for service in ('game-api', 'game-engine', 'web-game'):
            topology_contract.mounts(before[service], service, stack, fullbundle=fullbundle)
        topdown = None
        if fullbundle:
            values = topology_contract.environment(before['game-api'])
            inventory = topdown_contract.catalog(stack / 'data/topdown/pep')
            selected = values.get('TOPDOWN_BAKE_ID')
            require(values.get('SERVER_ID') == 'pep' and values.get('TOPDOWN_MAP_ROOT') == '/app/data/map/topdown' and
                    selected in inventory['bakes'], 'effective topdown server/root/bake mismatch')
            topdown = {'selected_bake': selected, 'catalog': inventory}
            topdown_contract.probe(self.recovery, topology['names']['game-api'], selected, inventory)
        preserving = {'topology': topology, 'topdown': topdown}
        observation = PepColdCapturePreflight(self.recovery).inspect(stack, preserving=preserving)
        observation['preserving'] = preserving
        require(not (stack / '.pep-loop-incomplete').exists() and
                not (stack / '.pep-migration-incomplete').exists(), 'incomplete operation requires recovery')
        marker = checked_path(stack / '.pep-loop-source')
        previous = marker.read_text().strip()
        require(re.fullmatch('[0-9a-f]{40}', previous), 'exact applied source cursor required')
        require(all(source_exposure(before[s])['networks'] for s in SERVICES),
                'source network identity required')
        for service in ('game-api', 'game-engine', 'web-game'):
            image = self.recovery.inspect('image', before[service]['Image'])
            require((image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == previous,
                    'source runtime images differ from applied cursor')
        for service in ('game-api', 'game-engine'):
            values = dict(item.split('=', 1) for item in before[service]['Config']['Env'] if '=' in item)
            require(values.get('GAME_API_PORT', '8081') == '8081' if service == 'game-api'
                    else values.get('GAME_ENGINE_PORT', '8082') == '8082', 'noncanonical application port unsupported')
            require(type(before[service]['HostConfig'].get('Memory')) is int and
                    before[service]['HostConfig']['Memory'] > 0, 'finite source application memory required')
            require(values.get('SCENARIO_DIR') == '/data/scenarios',
                    'exact external effective scenario required; bundled-source capture unsupported')
        for service, suffix in (('game-postgres', 'game-pgdata'), ('game-redis', 'game-redisdata')):
            consumers = self.recovery.docker.run(['container', 'ls', '--all', '--quiet', '--no-trunc',
                '--filter', 'volume=spep-' + suffix]).decode().splitlines()
            require(consumers == [before[service]['Id']], 'extra source volume consumer blocks cold rehearsal')
        status = json.loads(self.recovery.docker.run(['container', 'exec', 'spep-game-engine',
            'wget', '-T', '2', '-t', '1', '-qO-', 'http://localhost:8082/admin/turn-daemon/status']))
        require(status.get('recoveryMode') == 'READY' and status.get('recoveryReady') is True and
                status.get('serviceMaterialized') is True and status.get('clockError') is None,
                'source engine is not materialized and recovery-ready')
        return observation, previous, before

    def prepare(self, stack):
        with self.recovery.locked():
            observation, _, _ = self.preflight(checked_path(stack, directory=True))
            return {'server': 'pep', 'supported_shape': observation['preserving']['topology']['mode'].lower() + ('-fullbundle' if observation['preserving']['topdown'] else '-scenario-only'),
                    'maintenance': observation['maintenance'], 'services_changed': False,
                    'ready_for_deployment': False}

    def stop(self, service, expected):
        current = self.recovery.inspect('container', expected['Name'].removeprefix('/'))
        require(stable_identity(current) == stable_identity(expected), 'source identity changed before shutdown')
        self.recovery.docker.run(['container', 'stop', '--time', '120', expected['Id']])
        stopped = self.recovery.inspect('container', expected['Name'].removeprefix('/'))
        require(stable_identity(stopped) == stable_identity(expected) and
                not stopped['State']['Running'] and stopped['State']['Status'] == 'exited' and
                not stopped['State'].get('OOMKilled') and stopped['State'].get('ExitCode') in
                ((0,) if service in ('game-postgres', 'game-redis') else (0, 143)),
                'source did not stop cleanly with the same identity')

    def inbox_empty(self, env):
        query = 'SELECT count(*) FROM command_inbox WHERE world_id=' + env['OPENSAMGUK_WORLD_ID'] + " AND status IN ('ACCEPTED','CLAIMED');"
        result = self.recovery.docker.run(['container', 'exec', '-e',
            'PGOPTIONS=-c default_transaction_read_only=on', '-i', 'spep-game-postgres', 'psql',
            '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
            '-U', env['GAME_POSTGRES_USER'], '-d', env['GAME_POSTGRES_DB']], stdin=io.BytesIO(query.encode()))
        require(result.strip() == b'0', 'nonterminal inbox prevents a cold rehearsal')

    def clone_stage(self, bundle, manifest, env, source, tree, storage, api, engine, *, candidate=False):
        with self.clone_factory(self.recovery, bundle, manifest, env).restored() as clone:
            require(self.recovery.postgres_check(clone.pg, env) == storage['postgres'] and
                    self.recovery.redis_check(clone.redis, socket=None) == storage['redis'],
                    'cold clone does not match source storage proof')
            redis = clone.redis_fingerprint()
            require(redis == storage['redis_fingerprint'], 'cold clone Redis values/expiries differ from source')
            migrated = clone.migrate_api(source, api, tree)
            if not candidate:
                require(migrated == storage['postgres'], 'old API altered original backup schema/data')
            proof = clone.boot_engine(source, engine, tree)
            require(clone.redis_fingerprint() == redis, 'rehearsal changed restored Redis')
            return {'api_image_id': api, 'engine': proof, 'versions': migrated['versions'],
                    'cold_storage_verified': True, 'redis_fingerprint': redis}

    def validate_scenario(self, bundle, tree, scenario, original):
        PepApplicationDrill()._scenario(bundle, tree, scenario, digest(bundle / 'manifest.json')['sha256'])
        require(scenario_inventory(original) == scenario_inventory(tree),
                'original scenario bytes changed during rehearsal')

    def validate_topdown(self, bundle, stack, preserving, before=None):
        topology_contract.assert_current(self.recovery, preserving['topology'])
        if before is not None:
            require(all(stable_identity(self.recovery.inspect('container', before[s]['Name'].removeprefix('/'))) ==
                        stable_identity(before[s]) for s in SERVICES), 'original identity drift during rehearsal')
        if preserving['topdown'] is not None:
            require(topdown_contract.catalog(stack / 'data/topdown/pep') == preserving['topdown']['catalog'], 'original topdown drift')
            topdown_contract.companion(bundle, preserving['topdown']['catalog'])

    def resume(self, before, env, source_pg, source_redis, source_redis_fingerprint, *, topdown=None):
        # Exact existing objects only: no Compose reconstruction and no candidate image anywhere live.
        for service in SERVICES:
            require(stable_identity(self.recovery.inspect('container', before[service]['Name'].removeprefix('/'))) == stable_identity(before[service]),
                    'source changed while rehearsing; resume refused')
        for service in ('game-postgres', 'game-redis'):
            self.recovery.docker.run(['container', 'start', before[service]['Id']])
        require(self.recovery.postgres_check('spep-game-postgres', env, socket='/var/run/postgresql', read_only=True) == source_pg
                and self.recovery.redis_check('spep-game-redis', socket=None) == source_redis,
                'original storage changed before source resume')
        require(redis_fingerprint(self.recovery, 'spep-game-redis') == source_redis_fingerprint,
                'original Redis values/expiries changed before source resume')
        self.recovery.docker.run(['container', 'start', before['game-engine']['Id'], before['game-api']['Id']])
        healthy = False
        for _ in range(120):
            try:
                health = json.loads(self.recovery.docker.run(['container', 'exec', before['game-api']['Name'].removeprefix('/'),
                    'curl', '-fsS', '--max-time', '2', 'http://localhost:8081/actuator/health']))
                status = json.loads(self.recovery.docker.run(['container', 'exec', 'spep-game-engine',
                    'wget', '-T', '2', '-t', '1', '-qO-', 'http://localhost:8082/admin/turn-daemon/status']))
                if (health.get('status') == 'UP' and status.get('recoveryMode') == 'READY' and
                        status.get('recoveryReady') is True and status.get('serviceMaterialized') is True):
                    healthy = True
                    break
            except (RecoveryError, ValueError, TypeError):
                pass
            self.recovery.sleep(1)
        require(healthy, 'original API/engine did not recover after cold rehearsal')
        if topdown is not None:
            root = Path(topology_contract.mount(before['game-api'], '/app/data/map/topdown')['Source'])
            require(topdown_contract.catalog(root) == topdown['catalog'], 'original catalog changed on resume')
            topdown_contract.probe(self.recovery, before['game-api']['Name'].removeprefix('/'), topdown['selected_bake'], topdown['catalog'])
        self.recovery.docker.run(['container', 'start', before['web-game']['Id']])
        web_ready = False
        for _ in range(120):
            try:
                self.recovery.docker.run(['container', 'exec', before['web-game']['Name'].removeprefix('/'), 'node', '-e',
                    "fetch('http://localhost:3001/',{redirect:'manual'}).then(r=>process.exit([200,307].includes(r.status)?0:1)).catch(()=>process.exit(1))"])
                web_ready = True
                break
            except RecoveryError:
                self.recovery.sleep(1)
        require(web_ready, 'original web did not become healthy after cold rehearsal')
        for service in SERVICES:
            current = self.recovery.inspect('container', before[service]['Name'].removeprefix('/'))
            require(current['State']['Running'] and stable_identity(current) == stable_identity(before[service]) and
                    source_exposure(current) == source_exposure(before[service]),
                    'source identity or exposure changed on resume')

    def rehearse(self, *, stack, backup_root, checkout, source_sha, images, confirm):
        require(confirm == 'REHEARSE AND RESUME pep', 'explicit rehearsal/resume confirmation required')
        stack = checked_path(stack, directory=True)
        root = checked_path(backup_root, directory=True, private=True)
        checkout = checked_path(checkout, directory=True)
        with self.recovery.locked():
            observation, previous, before = self.preflight(stack)
            preserving = observation['preserving']
            # Actual app reads require Gateway publication even with valid map bytes.
            # No isolated publication source contract is implemented for clones yet.
            require(preserving['topdown'] is None,
                    'fullbundle rehearsal deferred: isolated Gateway publication source required before shutdown')
            candidates = self.admission.verify(self.recovery, checkout, source_sha, previous, images)
            env = selected_env(stack / 'servers/spep.env', 'pep')
            inputs = SourceEngineInputs.from_inspections('pep', before)
            # Repeat the complete shape after potentially slow CI observation, before the first stop.
            current_observation, current_source, current = self.preflight(stack)
            require(current_observation['preserving'] == preserving, 'topology/catalog drift during admission')
            require(current_source == previous and all(
                stable_identity(current[s]) == stable_identity(before[s]) and
                source_exposure(current[s]) == source_exposure(before[s]) for s in SERVICES),
                'source changed during admission')
            # Reject existing unsupported delivery state before creating a journal or stopping services.
            # Measure again after shutdown: this live observation cannot fence later deliveries.
            redis_fingerprint(self.recovery, 'spep-game-redis')
            operation = root / ('pep-migration-' + uuid.uuid4().hex)
            operation.mkdir(mode=0o700)
            status = {'version': 1, 'source_sha': source_sha, 'phase': 'admitted', 'success': False,
                      'ready_for_deployment': False, 'candidate_applied_live': False}
            journal = stack / '.pep-migration-incomplete'
            write_private(journal, json_bytes({'operation': str(operation)}))
            def phase(value):
                status['phase'] = value
                write_private(operation / 'status.json', json_bytes(status), replace=True)
            phase('admitted')
            try:
                for service in ('web-game', 'game-api', 'game-engine'):
                    phase('stopping-' + service)
                    self.stop(service, before[service])
                self.inbox_empty(env)
                source_pg = self.recovery.postgres_check('spep-game-postgres', env, socket='/var/run/postgresql', read_only=True)
                source_redis = self.recovery.redis_check('spep-game-redis', socket=None)
                source_redis_fingerprint = redis_fingerprint(self.recovery, 'spep-game-redis')
                for service in ('game-redis', 'game-postgres'):
                    phase('stopping-' + service)
                    self.stop(service, before[service])
                require(all(stable_identity(self.recovery.inspect('container', before[s]['Name'].removeprefix('/'))) == stable_identity(before[s]) for s in SERVICES), 'source identity changed before capture')
                phase('capturing')
                bundle = self.recovery.capture(server='pep', confirm='BACKUP pep', stack_dir=stack, backup_root=root, preserving=preserving)
                status['bundle_manifest_sha256'] = digest(bundle / 'manifest.json')['sha256']
                if preserving['topdown'] is not None:
                    topdown_contract.preserve(stack / 'data/topdown/pep', bundle, preserving['topdown']['catalog'])
                phase('verifying-storage')
                storage = self.recovery.verify(server='pep', confirm='VERIFY pep', bundle=bundle)
                require(storage['postgres'] == source_pg and storage['redis'] == source_redis,
                        'backup restored storage differs from committed source')
                storage['redis_fingerprint'] = source_redis_fingerprint
                manifest, captured_env = self.recovery.validate_bundle('pep', bundle)
                require(captured_env == env, 'backup environment drift')
                tree, scenario = preserve_scenario_tree(stack / 'data/scenarios', bundle)
                status['scenario_tree_sha256'] = scenario.tree_sha256
                stages = [('old-application', before['game-api']['Image'], before['game-engine']['Image'], False),
                          ('candidate-migration', candidates['game-api'], candidates['game-engine'], True),
                          ('rollback-from-original-backup', before['game-api']['Image'], before['game-engine']['Image'], False)]
                proofs = {}
                for label, api, engine, candidate in stages:
                    phase(label)
                    self.validate_scenario(bundle, tree, scenario, stack / 'data/scenarios')
                    self.validate_topdown(bundle, stack, preserving, before)
                    proofs[label] = self.clone_stage(bundle, manifest, env, inputs, tree, storage, api, engine, candidate=candidate)
                    self.validate_scenario(bundle, tree, scenario, stack / 'data/scenarios')
                    self.validate_topdown(bundle, stack, preserving, before)
                require(proofs['old-application'] == proofs['rollback-from-original-backup'],
                        'rollback did not reproduce old storage/application proof')
                write_private(operation / 'rehearsal.json', json_bytes({'bundle_manifest_sha256': status['bundle_manifest_sha256'],
                    'source_sha': source_sha, 'scenario_tree_sha256': scenario.tree_sha256, 'images': candidates,
                    'proofs': proofs, 'authenticated_reads_verified': False, 'ready_for_deployment': False}))
                # Immutable snapshot must still be admitted before resuming the exact old service objects.
                self.admission.verify(self.recovery, checkout, source_sha, previous, images)
                require(self.recovery.source('pep', stack, env, preserving=preserving)[0] == manifest['containers'], 'source changed after backup')
                phase('resuming-original')
                self.validate_scenario(bundle, tree, scenario, stack / 'data/scenarios')
                self.validate_topdown(bundle, stack, preserving, before)
                extra = {'topdown': preserving['topdown']} if preserving['topdown'] is not None else {}
                self.resume(before, env, source_pg, source_redis, source_redis_fingerprint, **extra)
                self.validate_topdown(bundle, stack, preserving, before)
                phase('original-resumed')
                status['success'] = True
                phase('original-resumed')
                journal.unlink()
                return {'server': 'pep', 'cold_rehearsal_verified': True, 'original_resumed': True,
                        'candidate_applied_live': False, 'ready_for_deployment': False, 'authenticated_reads_verified': False}
            except BaseException:
                # Preserve the journal and backup. Fail closed: no blind restart, restore or delete.
                phase('failed-after-' + status['phase'])
                for service in ('web-game', 'game-api', 'game-engine'):
                    try:
                        self.stop(service, before[service])
                    except (RecoveryError, KeyError, TypeError, OSError):
                        pass
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'rehearse'])
    parser.add_argument('--server', required=True)
    parser.add_argument('--confirm', required=True)
    parser.add_argument('--stack-dir', type=Path, required=True)
    parser.add_argument('--backup-root', type=Path)
    parser.add_argument('--checkout', type=Path)
    parser.add_argument('--source')
    parser.add_argument('--images', type=Path)
    args = parser.parse_args()
    try:
        require(args.server == 'pep' and args.stack_dir == pep_loop.ROOT, 'exact canonical PEP control path required')
        operator = PreservingMigration()
        if args.action == 'prepare':
            require(args.confirm == 'PREPARE pep', 'prepare confirmation mismatch')
            result = operator.prepare(args.stack_dir)
        else:
            require(all((args.backup_root, args.checkout, args.source, args.images)), 'rehearsal inputs missing')
            result = operator.rehearse(stack=args.stack_dir, backup_root=args.backup_root, checkout=args.checkout,
                source_sha=args.source, images=read_json(checked_path(args.images)), confirm=args.confirm)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (RecoveryError, pep_loop.AdmissionDeferred, OSError, KeyError, TypeError, ValueError):
        print('Preserving rehearsal blocked or incomplete. Inspect private operation status; no candidate was applied live.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
