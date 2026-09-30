"""Red probes for verified W1 artifacts; no production operation is exposed."""

import hashlib
import io
import json
import unittest
import xml.etree.ElementTree as ET
import zipfile

from game_server_recovery import RecoveryError
from pep_qa_w1_attestor import (BASELINES, SUITES, inspect_verified_w1,
                                inspect_w1_content)


SHA = 'a' * 40
PIN = 'b' * 64
FIRST = ('enlist=1 march=1 siege=1 income=3 salary=3 assessment=3 ranking=3 '
         'encounter=4 capture=4 dispatch=12')
REPO = 'peppone-choi/opensamguk'


def fixture():
    provenance = {'run_id': 77, 'run_attempt': 1, 'collector_sha': 'c' * 40,
                  'runtime_sha': SHA, 'map_sha256': PIN, 'scenario_sha256': PIN,
                  'w4_verified': False, 'production_stop_authorized': False}
    pins = {
        'infra/src/main/resources/map/han-world-v3.json': PIN,
        'infra/src/main/resources/scenario/scenario_990002.json': PIN,
        'tools/e2e/fixtures/yuzhou/scenario_990002.json': PIN,
    }
    first = dict((part.split('=')[0], int(part.split('=')[1])) for part in FIRST.split())
    attempts = {}
    reported = []
    for number in (1, 2, 3):
        entries = {
            'pin-git-sha.txt': (SHA + '\n').encode(),
            'pin-sha256.txt': ''.join(f'{value}  {name}\n' for name, value in pins.items()).encode(),
            'world-state-sha256.txt': ''.join(f'{key} {PIN}\n' for key in sorted(BASELINES)).encode(),
        }
        xml_hashes = {}
        for suite, count in SUITES.items():
            root = ET.Element('testsuite', name=suite, tests=str(count), failures='0',
                              errors='0', skipped='0')
            names = [f'test {index}' for index in range(count)]
            if suite.endswith('S3PassChainProbeIT'):
                names = ['적색 짝 fails when corps is absent']
            if suite.endswith('YuzhouCampaignInvarianceTest'):
                names = ['36 phases', 'seed 01 replay', 'cutting npc deployment', 'campaign']
            for name in names:
                ET.SubElement(root, 'testcase', classname=suite, name=name)
            output = ET.SubElement(root, 'system-out')
            if suite.endswith('PassChainInvarianceIT') and not suite.endswith('S3PassChainProbeIT'):
                output.text = f's3-first-phase {FIRST}\nbehavior-baseline s3-chain-48 {PIN}\n'
            elif suite.endswith('YuzhouCampaignInvarianceTest'):
                output.text = ''.join(f'behavior-baseline {key} {PIN}\n' for key in
                                      ('yuzhou-36-seed-00', 'yuzhou-36-seed-01'))
            filename = f'TEST-{suite}.xml'
            entries[filename] = ET.tostring(root)
            xml_hashes[filename] = hashlib.sha256(entries[filename]).hexdigest()
        attempts[number] = entries
        reported.append({'number': number, 'product_sha': SHA, 'pin_sha256': pins,
                         'first_event_phase': first, 'normalized_state_sha256':
                         dict.fromkeys(BASELINES, PIN), 'xml_sha256': xml_hashes})
    comparison = {'w1-manifest.json': json.dumps({
        'status': 'PASS', 'gate': 'W1', 'run_id': '77', 'run_attempt': '1',
        'product_sha': SHA, 'attempts': reported,
    }).encode()}
    return provenance, attempts, comparison


def zipped(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        for name, body in entries.items():
            archive.writestr(name, body)
    return buffer.getvalue()


class W1AttestorTest(unittest.TestCase):
    def setUp(self):
        self.provenance, self.attempts, self.comparison = fixture()

    def inspect(self):
        return inspect_w1_content(provenance=self.provenance, attempts=self.attempts,
                                  comparison=self.comparison)

    def test_three_attempts_pass_but_cannot_authorize_stop(self):
        result = self.inspect()
        self.assertTrue(result['w1_verified'])
        self.assertEqual(result['w1_attempts'], 3)
        self.assertFalse(result['production_stop_authorized'])

    def test_missing_attempt_refuses(self):
        del self.attempts[3]
        with self.assertRaisesRegex(RecoveryError, 'attempts'):
            self.inspect()

    def test_wrong_runtime_pin_refuses(self):
        self.attempts[2]['pin-git-sha.txt'] = ('d' * 40).encode()
        with self.assertRaisesRegex(RecoveryError, 'runtime SHA'):
            self.inspect()

    def test_skipped_red_pair_refuses_even_if_manifest_hash_updated(self):
        name = 'TEST-opensamguk.engine.boot.S3PassChainProbeIT.xml'
        self.attempts[2][name] = self.attempts[2][name].replace(b'skipped="0"', b'skipped="1"')
        self._update_xml_hash(2, name)
        with self.assertRaisesRegex(RecoveryError, 'failed, skipped'):
            self.inspect()

    def test_state_hash_mismatch_refuses_even_if_manifest_hash_updated(self):
        name = 'TEST-opensamguk.engine.boot.PassChainInvarianceIT.xml'
        self.attempts[2][name] = self.attempts[2][name].replace(
            f's3-chain-48 {PIN}'.encode(), f"s3-chain-48 {'d' * 64}".encode())
        self._update_xml_hash(2, name)
        with self.assertRaisesRegex(RecoveryError, 'content differs'):
            self.inspect()

    def test_extra_xml_refuses(self):
        self.attempts[1]['TEST-Unrelated.xml'] = b'<testsuite/>'
        with self.assertRaisesRegex(RecoveryError, 'inventory'):
            self.inspect()

    def test_forged_manifest_run_refuses(self):
        manifest = json.loads(self.comparison['w1-manifest.json'])
        manifest['run_id'] = '78'
        self.comparison['w1-manifest.json'] = json.dumps(manifest).encode()
        with self.assertRaisesRegex(RecoveryError, 'not for this'):
            self.inspect()

    def test_github_digest_and_jobs_are_checked(self):
        evidence = {1: self.attempts[1], 2: self.attempts[2],
                    3: self.attempts[3], 'compare': self.comparison}
        ids = {1: 101, 2: 102, 3: 103, 'compare': 104}
        responses = {f'/repos/{REPO}/actions/runs/77/jobs?per_page=100': {
            'total_count': 4, 'jobs': [
                {'name': f'yuzhou-w1-attempt ({number})', 'status': 'completed',
                 'conclusion': 'success'} for number in (1, 2, 3)] + [
                {'name': 'yuzhou-w1-compare', 'status': 'completed', 'conclusion': 'success'}]}}
        for key, artifact_id in ids.items():
            raw = zipped(evidence[key])
            prefix = 'yuzhou-w1-compare' if key == 'compare' else 'yuzhou-w1'
            name = f'{prefix}-77-1' + ('' if key == 'compare' else f'-{key}')
            path = f'/repos/{REPO}/actions/artifacts/{artifact_id}'
            responses[path] = {'id': artifact_id, 'name': name, 'expired': False,
                               'workflow_run': {'id': 77, 'head_sha': 'c' * 40},
                               'digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
            responses[path + '/zip'] = raw

        class Client:
            def get(self, path, *, archive=False):
                return responses[path]

        result = inspect_verified_w1(Client(), provenance=self.provenance, artifact_ids=ids)
        self.assertTrue(result['w1_verified'])
        responses[f'/repos/{REPO}/actions/artifacts/102']['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(RecoveryError, 'archive digest'):
            inspect_verified_w1(Client(), provenance=self.provenance, artifact_ids=ids)

    def _update_xml_hash(self, number, name):
        manifest = json.loads(self.comparison['w1-manifest.json'])
        manifest['attempts'][number - 1]['xml_sha256'][name] = hashlib.sha256(
            self.attempts[number][name]).hexdigest()
        self.comparison['w1-manifest.json'] = json.dumps(manifest).encode()


if __name__ == '__main__':
    unittest.main()
