"""Red probes for the read-only PEP evidence content gate."""

import hashlib
import json
import unittest
from unittest.mock import patch

from game_server_recovery import RecoveryError
from pep_qa_final_gate import BOUNDARY_SUITES, inspect_current_artifacts, inspect_verified_run
from pep_qa_provenance import _zip_entries
from test_pep_qa_provenance import (ATTEMPT, COLLECTOR, IDS, MAP, RUN, RUNTIME,
                                     SCENARIO, FakeClient, archive)


SHA = RUNTIME


def fixture():
    pin = {'pin-git-sha.txt': (SHA + '\n').encode()}
    w0 = dict(pin)
    for suite in BOUNDARY_SUITES:
        w0['TEST-' + suite + '.xml'] = (
            f'<testsuite name="{suite}" tests="1" failures="0" errors="0" skipped="0" />'
        ).encode()
    w0['forced-boundary-exceptions.txt'] = b''
    w3 = {**pin, 'vitest.log': b'passed\n', 'typecheck.log': b'passed\n'}
    playwright = json.dumps({
        'stats': {'expected': 1, 'skipped': 0, 'unexpected': 0, 'flaky': 0},
        'suites': [{'file': 'web/game/e2e/yuzhou-live.spec.ts',
                    'specs': [{'tests': [{'results': [{'status': 'passed'}]}]}]}],
    }).encode()
    battle_file = b'{"schemaVersion":"qa-committed-battle-v1"}'
    name = 'battle-990002-' + 'b' * 64 + '.json'
    manifest = {
        'source_sha256': hashlib.sha256(playwright).hexdigest(),
        'screens': ['screen-' + name + '.png' for name in
                    ('court', 'hand', 'orders', 'posts', 'retinue', 'siege',
                     'supply', 'war-room', 'yuedan')],
        'api_count': 13, 'event_count': 1,
        'battle_result_gate': {
            'status': 'DB_BACKED_COVERAGE', 'source': 'POST_FLUSH_FILE_SINK',
            'resolved_count': 1, 'sealed_count': 1, 'phase3_winning_count': 1,
            'callback_count': 1, 'db_last_battle_rows': 1,
            'files_sha256': {name: hashlib.sha256(battle_file).hexdigest()},
        },
        'phase_evidence': {'npcBattles': 1, 'liveEncounterCount': 1,
                           'repeatedNeutralCaptures': 0, 'abandonedWithGarrison': 0,
                           'yuedan': {'status': 'READY'}},
    }
    isolated = {**pin, 'playwright-results.json': playwright,
                'yuzhou-evidence-manifest.json': json.dumps(manifest).encode(),
                name: battle_file}
    proof = {'runtime_sha': SHA, 'w4_verified': False, 'production_stop_authorized': False}
    return proof, isolated, w0, w3


class FinalGateTest(unittest.TestCase):
    def setUp(self):
        self.proof, self.isolated, self.w0, self.w3 = fixture()

    def inspect(self):
        return inspect_current_artifacts(provenance=self.proof, isolated=self.isolated,
                                         w0_w2=self.w0, w3=self.w3)

    def manifest(self):
        return json.loads(self.isolated['yuzhou-evidence-manifest.json'])

    def replace_manifest(self, value):
        self.isolated['yuzhou-evidence-manifest.json'] = json.dumps(value).encode()

    def test_clean_evidence_still_cannot_stop_production_without_w1(self):
        result = self.inspect()
        self.assertEqual(result['w0_w2_xml_tests'], 9)
        self.assertEqual(result['w4_phase3_winning_count'], 1)
        self.assertFalse(result['w1_verified'])
        self.assertFalse(result['production_stop_authorized'])

    def test_forced_boundary_exception_refuses(self):
        self.w0['forced-boundary-exceptions.txt'] = b'DomesticBoundary:42\n'
        with self.assertRaisesRegex(RecoveryError, 'forced phase-boundary'):
            self.inspect()

    def test_skipped_w2_xml_refuses(self):
        self.w0['TEST-DomesticEngineTest.xml'] = (
            b'<testsuite tests="1" failures="0" errors="0" skipped="1" />'
        )
        with self.assertRaisesRegex(RecoveryError, 'failed or skipped'):
            self.inspect()

    def test_packaged_junit_filenames_are_accepted(self):
        self.w0['TEST-opensamguk.engine.campaign.DomesticEngineTest.xml'] = (
            self.w0.pop('TEST-DomesticEngineTest.xml')
        )
        self.assertEqual(self.inspect()['w0_w2_xml_tests'], 9)

    def test_browser_case_must_pass(self):
        browser = json.loads(self.isolated['playwright-results.json'])
        browser['suites'][0]['specs'][0]['tests'][0]['results'][0]['status'] = 'failed'
        body = json.dumps(browser).encode()
        self.isolated['playwright-results.json'] = body
        manifest = self.manifest()
        manifest['source_sha256'] = hashlib.sha256(body).hexdigest()
        self.replace_manifest(manifest)
        with self.assertRaisesRegex(RecoveryError, 'Playwright case'):
            self.inspect()

    def test_null_browser_file_fails_cleanly(self):
        browser = json.loads(self.isolated['playwright-results.json'])
        browser['suites'][0]['file'] = None
        browser['suites'][0]['title'] = None
        body = json.dumps(browser).encode()
        self.isolated['playwright-results.json'] = body
        manifest = self.manifest()
        manifest['source_sha256'] = hashlib.sha256(body).hexdigest()
        self.replace_manifest(manifest)
        with self.assertRaisesRegex(RecoveryError, 'Playwright case'):
            self.inspect()

    def test_manifest_must_bind_playwright_bytes(self):
        manifest = self.manifest()
        manifest['source_sha256'] = '0' * 64
        self.replace_manifest(manifest)
        with self.assertRaisesRegex(RecoveryError, 'not bound'):
            self.inspect()

    def test_missing_phase3_winner_refuses(self):
        manifest = self.manifest()
        manifest['battle_result_gate']['phase3_winning_count'] = 0
        self.replace_manifest(manifest)
        with self.assertRaisesRegex(RecoveryError, 'phase-3 winner'):
            self.inspect()

    def test_live_encounters_must_equal_sealed_count(self):
        manifest = self.manifest()
        manifest['phase_evidence']['liveEncounterCount'] = 2
        self.replace_manifest(manifest)
        with self.assertRaisesRegex(RecoveryError, 'world progression'):
            self.inspect()

    def test_battle_file_hash_mismatch_refuses(self):
        manifest = self.manifest()
        name = next(iter(manifest['battle_result_gate']['files_sha256']))
        self.isolated[name] = b'changed'
        with self.assertRaisesRegex(RecoveryError, 'differs from manifest'):
            self.inspect()

    def test_artifact_pin_mismatch_refuses(self):
        self.w3['pin-git-sha.txt'] = ('c' * 40).encode()
        with self.assertRaisesRegex(RecoveryError, 'SHA/provenance mismatch'):
            self.inspect()

    def test_verified_archive_bytes_are_inspected_without_second_download(self):
        client = FakeClient()
        for artifact_id, additions in ((11, self.isolated), (12, self.w0), (13, self.w3)):
            entries = _zip_entries(client.zips[artifact_id])
            entries.update(additions)
            client.zips[artifact_id] = archive(entries)
        original_get = client.get
        downloads = []

        def counted(path, *, archive=False):
            if archive:
                downloads.append(path)
            return original_get(path, archive=archive)

        client.get = counted
        result = inspect_verified_run(client, run_id=RUN, run_attempt=ATTEMPT,
            collector_sha=COLLECTOR, runtime_sha=RUNTIME, map_sha=MAP,
            scenario_sha=SCENARIO, artifact_ids=IDS)
        self.assertEqual(result['w4_phase3_winning_count'], 1)
        self.assertFalse(result['production_stop_authorized'])
        self.assertEqual(len(downloads), 3)
        self.assertEqual(len(set(downloads)), 3)

    def test_missing_verified_archive_capture_refuses(self):
        with patch('pep_qa_final_gate.verify_run_artifacts', return_value={
            'artifact_ids': IDS, 'runtime_sha': RUNTIME}):
            with self.assertRaisesRegex(RecoveryError, 'digest-verified'):
                inspect_verified_run(FakeClient())


if __name__ == '__main__':
    unittest.main()
