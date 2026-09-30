#!/usr/bin/env python3
"""Single-lock PEP QA cutover coordinator.

Programmatic only while the final evidence and post-reset probes are under
review. No command-line path can submit a production reset.
"""

from pathlib import Path
import re
import io
import json
import os
import subprocess

from game_server_recovery import (Recovery, RecoveryError, checked_path, digest,
                                  json_bytes, require, write_private)
from pep_cold_capture_operator import PepColdCaptureOperator, require_qa_gate
from pep_qa_provenance import GitHubEvidenceClient, verify_run_artifacts


SHA40 = re.compile(r'[0-9a-f]{40}\Z')
SHA64 = re.compile(r'[0-9a-f]{64}\Z')
IMAGE_ID = re.compile(r'sha256:[0-9a-f]{64}\Z')
IMAGE_SERVICES = {'gateway-api', 'board-api', 'game-api', 'game-engine',
                  'web-gateway', 'web-game'}
SHARED_SERVICES = {'gateway-api', 'board-api', 'web-gateway'}
SCENARIO_PATH = 'infra/src/main/resources/scenario/scenario_990002.json'
MAP_PATH = 'infra/src/main/resources/map/han-world-v3.json'
LEASED_RESET = r'''
import json, os, sys, time, urllib.error, urllib.request
payload = json.load(sys.stdin)
token = os.environ.get('DEPLOYER_TOKEN', '')
if not token:
    raise SystemExit(2)
op = payload['operationId']
lease = payload.pop('lease')
headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
           'X-Maintenance-Lease': lease}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
opener = urllib.request.build_opener(NoRedirect)
def fetch(request):
    with opener.open(request, timeout=20) as response:
        body = response.read(65537)
        if response.status != 200 or len(body) > 65536:
            raise RuntimeError('unexpected deployer response')
        return json.loads(body)
request = urllib.request.Request('http://127.0.0.1:9000/servers/reset',
    data=json.dumps(payload).encode(), headers=headers, method='POST')
accepted = fetch(request)
if accepted.get('ok') is not True or accepted.get('operationId') != op or not accepted.get('jobId'):
    raise RuntimeError('deployer did not accept the reset')
deadline = time.monotonic() + 1800
while time.monotonic() < deadline:
    status = fetch(urllib.request.Request('http://127.0.0.1:9000/operations/' + op,
        headers={'Authorization': 'Bearer ' + token}, method='GET'))
    if status.get('operationId') != op:
        raise RuntimeError('deployer operation changed identity')
    state = status.get('status')
    if state == 'succeeded':
        sys.stdout.write(json.dumps({'operation_id': op, 'status': state}))
        raise SystemExit(0)
    if state not in ('pending', 'running', 'recovery_required'):
        raise RuntimeError('deployer operation failed')
    time.sleep(5)
raise RuntimeError('deployer operation deadline exceeded')
'''


class DeployerLeaseReset:
    """Use the Docker #60 loopback endpoint exactly once under the caller lock."""

    def __init__(self, recovery):
        self.recovery = recovery

    def reset(self, *, operation_id, lease, scenario_code, image_tag, web_game_tag):
        payload = {'id': 'pep', 'confirm': 'RESET pep', 'operationId': operation_id,
                   'generation': '2', 'scenarioCode': scenario_code,
                   'scenarioSeedEnabled': True, 'imageTag': image_tag,
                   'webGameTag': web_game_tag, 'lease': lease}
        try:
            raw = self.recovery.docker.run(
                ['container', 'exec', '-i', 'opensamguk-deployer',
                 'python3', '-c', LEASED_RESET],
                stdin=io.BytesIO(json_bytes(payload)))
            receipt = json.loads(raw)
        except (OSError, UnicodeError, ValueError, TypeError) as exc:
            raise RecoveryError('deployer reset receipt unavailable') from exc
        require(isinstance(receipt, dict) and
                receipt == {'operation_id': operation_id, 'status': 'succeeded'},
                'deployer did not complete the one leased reset')
        return receipt


class DeployerBinaryVerifier:
    """Pin the separately promoted Docker #60 binary before touching PEP."""

    def __init__(self, recovery, expected_sha256):
        require(isinstance(expected_sha256, str) and SHA64.fullmatch(expected_sha256),
                'reviewed deployer binary SHA-256 required')
        self.recovery = recovery
        self.expected_sha256 = expected_sha256

    def verify(self):
        container = self.recovery.inspect('container', 'opensamguk-deployer')
        labels = container.get('Config', {}).get('Labels') or {}
        require(container.get('Name') == '/opensamguk-deployer' and
                container.get('State', {}).get('Running') is True and
                labels.get('com.docker.compose.project') == 'opensamguk-shared' and
                labels.get('com.docker.compose.service') == 'deployer',
                'reviewed deployer is not running')
        raw = self.recovery.docker.run(
            ['container', 'exec', 'opensamguk-deployer',
             'sha256sum', '/usr/local/bin/deployer']).decode().strip()
        require(raw == self.expected_sha256 + '  /usr/local/bin/deployer',
                'running deployer binary differs from reviewed build')


class GitHubFinalEvidenceVerifier:
    def __init__(self, token):
        self.client = GitHubEvidenceClient(token)

    def verify(self, run_identity, w1_artifact_ids):
        return verify_final_evidence(self.client, run_identity=run_identity,
                                     w1_artifact_ids=w1_artifact_ids)


class LocalCandidateVerifier:
    """Require already-pulled exact image IDs and actual scenario/map bytes."""

    def __init__(self, source_tree, recovery):
        self.source_tree = Path(source_tree).resolve()
        self.recovery = recovery

    def verify(self, evidence, stack, *, require_staged=False):
        try:
            sha = subprocess.check_output(
                ['git', '-C', str(self.source_tree), 'rev-parse', 'HEAD'],
                stderr=subprocess.DEVNULL, timeout=10).decode().strip()
        except (OSError, subprocess.SubprocessError) as exc:
            raise RecoveryError('candidate source commit unavailable') from exc
        require(sha == evidence.get('runtime_sha'), 'candidate source is not QA main')
        scenario_sha = digest(self.source_tree / SCENARIO_PATH)['sha256']
        map_sha = digest(self.source_tree / MAP_PATH)['sha256']
        require(scenario_sha == evidence.get('scenario_sha256') and
                map_sha == evidence.get('map_sha256'),
                'source scenario or map bytes differ from QA candidate')
        staged = Path(stack) / 'data/scenarios/scenario_990002.json'
        if staged.exists() or staged.is_symlink():
            require(digest(checked_path(staged))['sha256'] == scenario_sha,
                    'effective scenario differs from QA candidate')
        else:
            require(not require_staged, 'candidate scenario has not been staged')
        images = {}
        for service in sorted(IMAGE_SERVICES):
            reference = ('ghcr.io/peppone-choi/opensamguk:' + service + '-' + sha)
            image = self.recovery.inspect('image', reference)
            images[service] = image.get('Id')
        for service in sorted(SHARED_SERVICES):
            live = self.recovery.inspect('container', 'opensamguk-' + service)
            labels = live.get('Config', {}).get('Labels') or {}
            require(live.get('Name') == '/opensamguk-' + service and
                    live.get('State', {}).get('Running') is True and
                    labels.get('com.docker.compose.project') == 'opensamguk-shared' and
                    labels.get('com.docker.compose.service') == service and
                    live.get('Image') == images[service],
                    'shared candidate service is not live at QA image ID')
        candidate = {'runtime_sha': sha, 'image_tag': sha, 'web_game_tag': sha,
                     'scenario_code': 'scenario_990002', 'city_count': 1447,
                     'scenario_sha256': scenario_sha, 'map_sha256': map_sha,
                     'image_ids': images, 'registry_tag_verified': True,
                     'scenario_file_verified': True, 'map_file_verified': True}
        require_candidate(evidence, candidate)
        return candidate

    def stage(self, evidence, stack):
        """Add only the QA scenario after the old-world cold proof succeeds."""
        source = checked_path(self.source_tree / SCENARIO_PATH)
        expected = evidence.get('scenario_sha256')
        require(digest(source)['sha256'] == expected,
                'candidate scenario bytes changed before staging')
        directory = checked_path(Path(stack) / 'data/scenarios', directory=True)
        destination = directory / 'scenario_990002.json'
        if destination.exists() or destination.is_symlink():
            require(digest(checked_path(destination))['sha256'] == expected,
                    'existing external QA scenario differs')
            return
        fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
        try:
            with os.fdopen(fd, 'wb') as output, source.open('rb') as payload:
                for chunk in iter(lambda: payload.read(1024 * 1024), b''):
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            require(digest(checked_path(destination))['sha256'] == expected,
                    'staged QA scenario differs')
        except BaseException:
            destination.unlink(missing_ok=True)
            raise


def verify_final_evidence(client, *, run_identity, w1_artifact_ids):
    """Bind W0–W4 and three W1 attempts to current main and archive bytes."""
    # This module lands separately; absence must fail before any service stop.
    from pep_qa_final_gate import inspect_verified_run

    provenance = verify_run_artifacts(client, **run_identity)
    result = inspect_verified_run(client, w1_artifact_ids=w1_artifact_ids,
                                  **run_identity)
    require(result.get('runtime_sha') == provenance['runtime_sha'] and
            result.get('w0_w2_xml_tests', 0) > 0 and
            result.get('w2_forced_exceptions') == 0 and
            result.get('w3_verified') is True and
            result.get('w4_db_backed_battles', 0) > 0 and
            result.get('w4_phase3_winning_count', 0) > 0 and
            result.get('w1_verified') is True and result.get('w1_attempts') == 3,
            'complete same-main W0-W4 and W1 evidence required')
    require(result.get('production_stop_authorized') is False and
            provenance.get('production_stop_authorized') is False,
            'unexpected authorization from read-only evidence verifier')
    return {**provenance, 'w1_attempts': 3, 'w4_db_backed_battles':
            result['w4_db_backed_battles']}


def require_candidate(evidence, candidate):
    """A separate verifier must bind registry tags, image IDs and input bytes."""
    require(isinstance(evidence, dict) and isinstance(candidate, dict),
            'candidate attestation required')
    runtime_sha = evidence.get('runtime_sha')
    require(isinstance(runtime_sha, str) and SHA40.fullmatch(runtime_sha) and
            candidate.get('runtime_sha') == runtime_sha and
            candidate.get('image_tag') == runtime_sha and
            candidate.get('web_game_tag') == runtime_sha,
            'candidate tags must be the exact current main commit')
    require(candidate.get('scenario_code') == 'scenario_990002' and
            candidate.get('city_count') == 1447 and
            candidate.get('map_sha256') == evidence.get('map_sha256') and
            candidate.get('scenario_sha256') == evidence.get('scenario_sha256') and
            all(isinstance(evidence.get(key), str) and SHA64.fullmatch(evidence[key])
                for key in ('map_sha256', 'scenario_sha256')),
            'candidate scenario and map bytes differ from QA')
    images = candidate.get('image_ids')
    require(isinstance(images, dict) and set(images) == IMAGE_SERVICES and
            images == evidence.get('image_ids') and
            all(isinstance(value, str) and IMAGE_ID.fullmatch(value)
                for value in images.values()) and
            candidate.get('registry_tag_verified') is True and
            candidate.get('scenario_file_verified') is True and
            candidate.get('map_file_verified') is True,
            'registry image IDs or candidate file bytes are unverified')


def require_source_generation(stack):
    env = checked_path(Path(stack) / 'servers/spep.env', private=True)
    values = [line.partition('=')[2].strip().strip('"\'')
              for line in env.read_text().splitlines()
              if line.partition('=')[0] == 'SERVER_GENERATION']
    require(values == ['1'], 'PEP source generation must be exactly 1')


class BoundQAAttestor:
    def __init__(self, evidence, candidate):
        require_candidate(evidence, candidate)
        self.sha = evidence['runtime_sha']

    def verify(self, gate):
        require_qa_gate(gate)
        require(gate['head_sha'] == self.sha, 'QA gate head differs')


class PepQACutover:
    """Orchestrate one admitted attempt, without retrying reset or restarting old code."""

    def __init__(self, *, recovery=None, evidence_verifier=None,
                 candidate_verifier=None, cold_operator_factory=None,
                 deployer_verifier=None, reset_client=None, post_probe=None):
        self.recovery = recovery or Recovery()
        self.evidence_verifier = evidence_verifier
        self.candidate_verifier = candidate_verifier
        self.cold_operator_factory = cold_operator_factory or (
            lambda attestor: PepColdCaptureOperator(recovery=self.recovery,
                                                    qa_attestor=attestor))
        self.reset_client = reset_client
        self.post_probe = post_probe
        self.deployer_verifier = deployer_verifier

    def run(self, *, stack, backup_root, run_identity, w1_artifact_ids,
            operation_id, lease):
        require(isinstance(operation_id, str) and
                re.fullmatch(r'[0-9a-f]{32}', operation_id) is not None,
                'new durable operation ID required')
        require(isinstance(lease, str) and len(lease) >= 32 and
                re.fullmatch(r'[A-Za-z0-9_-]+', lease) is not None,
                'private maintenance lease required')
        require(all(dependency is not None for dependency in
                    (self.evidence_verifier, self.candidate_verifier,
                     self.deployer_verifier,
                     self.reset_client, self.post_probe)),
                'reviewed evidence, candidate, deployer, reset and post probes required')
        with self.recovery.locked():
            self.deployer_verifier.verify()
            evidence = self.evidence_verifier.verify(run_identity, w1_artifact_ids)
            candidate = self.candidate_verifier.verify(evidence, Path(stack))
            attestor = BoundQAAttestor(evidence, candidate)
            require_source_generation(stack)
            gate = {'battle': 'PASS', 'w4': 'PASS', 'head_sha': evidence['runtime_sha'],
                    'scenario_code': 'scenario_990002', 'city_count': 1447}
            cold = self.cold_operator_factory(attestor).capture_and_prove(
                stack=stack, backup_root=backup_root, qa_gate=gate)
            handoff = cold.get('handoff', {})
            require(cold.get('storage_verified') is True and
                    cold.get('old_engine_materialized') is True and
                    cold.get('authenticated_read_verified') is True and
                    cold.get('ready_for_reset') is False and
                    handoff.get('workflow_create_backup') is False and
                    handoff.get('old_engine_restart_allowed') is False and
                    handoff.get('candidate_image_pin_required') is True and
                    isinstance(handoff.get('bundle_manifest_sha256'), str) and
                    SHA64.fullmatch(handoff['bundle_manifest_sha256']),
                    'complete stopped-source cold handoff required')
            operation = Path(cold['operation'])
            status_path = operation / 'cutover-status.json'
            status = {'phase': 'cold-verified', 'success': False,
                      'operation_id': operation_id,
                      'bundle_manifest_sha256': handoff['bundle_manifest_sha256'],
                      'runtime_sha': evidence['runtime_sha']}
            write_private(status_path, json_bytes(status))
            try:
                # Recheck the changing external head and candidate pins while
                # the old services remain stopped and the same lock is held.
                current = self.evidence_verifier.verify(run_identity, w1_artifact_ids)
                require(current == evidence, 'QA evidence changed after cold capture')
                self.candidate_verifier.stage(current, Path(stack))
                require_candidate(current, self.candidate_verifier.verify(
                    current, Path(stack), require_staged=True))
                self.deployer_verifier.verify()
                status['phase'] = 'reset-submitted'
                write_private(status_path, json_bytes(status), replace=True)
                receipt = self.reset_client.reset(
                    operation_id=operation_id, lease=lease,
                    scenario_code='scenario_990002', image_tag=candidate['image_tag'],
                    web_game_tag=candidate['web_game_tag'])
                require(isinstance(receipt, dict) and
                        receipt.get('operation_id') == operation_id and
                        receipt.get('status') == 'succeeded',
                        'deployer reset did not succeed')
                status['phase'] = 'reset-succeeded'
                write_private(status_path, json_bytes(status), replace=True)
                observed = self.post_probe.verify(candidate, evidence, receipt)
                require(isinstance(observed, dict) and
                        observed.get('health') is True and
                        observed.get('authenticated_read') is True and
                        observed.get('turn_progression') is True and
                        observed.get('candidate_image_ids') == candidate['image_ids'] and
                        observed.get('scenario_sha256') == evidence['scenario_sha256'],
                        'new-world post-reset observation incomplete')
                status['phase'], status['success'] = 'observed', True
                write_private(status_path, json_bytes(status), replace=True)
                return {'operation': str(operation), 'bundle': cold['bundle'],
                        'operation_id': operation_id, 'observed': True}
            except BaseException:
                status['phase'] = 'failed-after-' + status['phase']
                write_private(status_path, json_bytes(status), replace=True)
                raise
