import contextlib
import json
import tempfile
import unittest
from pathlib import Path

from game_server_recovery import RecoveryError
from pep_qa_cutover import DeployerLeaseReset, PepQACutover


SHA = 'a' * 40
HASH = 'b' * 64
IMAGES = {name: 'sha256:' + 'c' * 64 for name in
          ('gateway-api', 'board-api', 'game-api', 'game-engine',
           'web-gateway', 'web-game')}
EVIDENCE = {'runtime_sha': SHA, 'map_sha256': HASH,
            'scenario_sha256': HASH, 'image_ids': IMAGES}
CANDIDATE = {'runtime_sha': SHA, 'image_tag': SHA, 'web_game_tag': SHA,
             'scenario_code': 'scenario_990002', 'city_count': 1447,
             'map_sha256': HASH, 'scenario_sha256': HASH,
             'image_ids': IMAGES, 'registry_tag_verified': True,
             'scenario_file_verified': True, 'map_file_verified': True}


class Recovery:
    def __init__(self):
        self.events = []
        self.held = False

    @contextlib.contextmanager
    def locked(self):
        self.events.append('lock')
        self.held = True
        try:
            yield
        finally:
            self.held = False
            self.events.append('unlock')


class Evidence:
    def __init__(self, recovery):
        self.recovery = recovery
        self.calls = 0
        self.fail_on = None

    def verify(self, run_identity, w1_artifact_ids):
        assert self.recovery.held
        self.calls += 1
        self.recovery.events.append('evidence')
        if self.calls == self.fail_on:
            raise RecoveryError('evidence changed')
        return dict(EVIDENCE)


class Candidate:
    def __init__(self, recovery):
        self.recovery = recovery
        self.value = dict(CANDIDATE)

    def verify(self, evidence, stack):
        assert self.recovery.held
        self.recovery.events.append('candidate')
        return self.value


class Cold:
    def __init__(self, recovery, attestor, directory):
        self.recovery, self.attestor, self.directory = recovery, attestor, directory

    def capture_and_prove(self, **kwargs):
        assert self.recovery.held
        self.attestor.verify(kwargs['qa_gate'])
        self.recovery.events.append('cold')
        operation = self.directory / 'operation'
        operation.mkdir()
        return {'operation': str(operation), 'bundle': str(self.directory / 'bundle'),
                'storage_verified': True, 'old_engine_materialized': True,
                'authenticated_read_verified': True, 'ready_for_reset': False,
                'handoff': {'workflow_create_backup': False,
                            'old_engine_restart_allowed': False,
                            'candidate_image_pin_required': True,
                            'bundle_manifest_sha256': HASH}}


class Reset:
    def __init__(self, recovery):
        self.recovery = recovery
        self.fail = False

    def reset(self, **kwargs):
        assert self.recovery.held
        self.recovery.events.append('reset')
        if self.fail:
            raise RecoveryError('reset failed')
        return {'operation_id': kwargs['operation_id'], 'status': 'succeeded'}


class Post:
    def __init__(self, recovery):
        self.recovery = recovery
        self.turn = True

    def verify(self, candidate, evidence, receipt):
        assert self.recovery.held
        self.recovery.events.append('post')
        return {'health': True, 'authenticated_read': True,
                'turn_progression': self.turn,
                'candidate_image_ids': IMAGES, 'scenario_sha256': HASH}


class CutoverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'servers').mkdir()
        (self.root / 'servers/spep.env').write_text('SERVER_GENERATION=1\n')
        (self.root / 'servers/spep.env').chmod(0o600)
        self.recovery = Recovery()
        self.evidence = Evidence(self.recovery)
        self.candidate = Candidate(self.recovery)
        self.reset = Reset(self.recovery)
        self.post = Post(self.recovery)
        self.subject = PepQACutover(
            recovery=self.recovery, evidence_verifier=self.evidence,
            candidate_verifier=self.candidate,
            cold_operator_factory=lambda attestor: Cold(self.recovery, attestor, self.root),
            reset_client=self.reset, post_probe=self.post)

    def run_cutover(self):
        return self.subject.run(stack=self.root, backup_root=self.root,
                                run_identity={}, w1_artifact_ids={},
                                operation_id='d' * 32, lease='e' * 32)

    def test_one_lock_covers_proofs_cold_reset_and_observation(self):
        self.assertTrue(self.run_cutover()['observed'])
        self.assertEqual(self.recovery.events,
                         ['lock', 'evidence', 'candidate', 'cold', 'evidence',
                          'candidate', 'reset', 'post', 'unlock'])
        status = json.loads((self.root / 'operation/cutover-status.json').read_text())
        self.assertTrue(status['success'])

    def test_image_or_scenario_drift_blocks_before_cold_stop(self):
        self.candidate.value = {**CANDIDATE, 'scenario_sha256': 'f' * 64}
        with self.assertRaises(RecoveryError):
            self.run_cutover()
        self.assertNotIn('cold', self.recovery.events)
        self.assertNotIn('reset', self.recovery.events)

    def test_missing_evidence_blocks_before_cold_stop(self):
        self.evidence.fail_on = 1
        with self.assertRaises(RecoveryError):
            self.run_cutover()
        self.assertEqual(self.recovery.events, ['lock', 'evidence', 'unlock'])

    def test_changed_evidence_leaves_old_stack_stopped_without_reset(self):
        self.evidence.fail_on = 2
        with self.assertRaises(RecoveryError):
            self.run_cutover()
        self.assertIn('cold', self.recovery.events)
        self.assertNotIn('reset', self.recovery.events)
        self.assertIn('failed-after-cold-verified',
                      (self.root / 'operation/cutover-status.json').read_text())

    def test_reset_failure_does_not_attempt_old_restart_or_retry(self):
        self.reset.fail = True
        with self.assertRaises(RecoveryError):
            self.run_cutover()
        self.assertEqual(self.recovery.events.count('reset'), 1)
        self.assertNotIn('post', self.recovery.events)
        self.assertIn('failed-after-reset-submitted',
                      (self.root / 'operation/cutover-status.json').read_text())

    def test_missing_turn_proof_fails_after_reset(self):
        self.post.turn = False
        with self.assertRaises(RecoveryError):
            self.run_cutover()
        self.assertIn('failed-after-reset-succeeded',
                      (self.root / 'operation/cutover-status.json').read_text())

    def test_missing_runtime_dependencies_fail_before_lock(self):
        subject = PepQACutover(recovery=self.recovery)
        with self.assertRaises(RecoveryError):
            subject.run(stack=self.root, backup_root=self.root,
                        run_identity={}, w1_artifact_ids={},
                        operation_id='d' * 32, lease='e' * 32)
        self.assertEqual(self.recovery.events, [])

    def test_reset_adapter_sends_private_lease_once_on_stdin(self):
        class Docker:
            calls = []

            def run(self, args, *, stdin):
                self.calls.append((args, json.loads(stdin.getvalue())))
                return b'{"operation_id":"' + b'd' * 32 + b'","status":"succeeded"}'

        docker = Docker()
        adapter = DeployerLeaseReset(type('R', (), {'docker': docker})())
        receipt = adapter.reset(operation_id='d' * 32, lease='e' * 32,
                                scenario_code='scenario_990002', image_tag=SHA,
                                web_game_tag=SHA)
        self.assertEqual(receipt['status'], 'succeeded')
        self.assertEqual(len(docker.calls), 1)
        args, payload = docker.calls[0]
        self.assertEqual(payload['lease'], 'e' * 32)
        self.assertEqual(payload['scenarioCode'], 'scenario_990002')
        self.assertEqual(payload['imageTag'], SHA)
        self.assertFalse(any('e' * 32 in arg for arg in args))


if __name__ == '__main__':
    unittest.main()
