import hashlib
import io
import json
import unittest
import zipfile

from game_server_recovery import RecoveryError
from pep_qa_provenance import (ARTIFACT_PREFIXES, REPOSITORY, _zip_entries,
                               verify_run_artifacts)


RUN = 123
ATTEMPT = 2
COLLECTOR = 'a' * 40
RUNTIME = 'b' * 40
MAP = 'c' * 64
SCENARIO = 'd' * 64
IDS = dict(zip(ARTIFACT_PREFIXES, (11, 12, 13)))


def archive(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as output:
        for name, body in files.items():
            output.writestr(name, body)
    return stream.getvalue()


def pins():
    return (f'{MAP}  infra/src/main/resources/map/han-world-v3.json\n'
            f'{SCENARIO}  infra/src/main/resources/scenario/scenario_990002.json\n'
            f'{SCENARIO}  tools/e2e/fixtures/yuzhou/scenario_990002.json\n').encode()


class FakeClient:
    def __init__(self):
        common = {'pin-git-sha.txt': (RUNTIME + '\n').encode(), 'pin-sha256.txt': pins()}
        isolated = {**common,
            'runtime-world-pin.json': json.dumps({'scenarioCode': 'scenario_990002',
                'mapName': 'han-world-v3', 'worldFormat': 'GENERAL_RETAINER_CAMPAIGN',
                'cityCount': 1447}).encode(),
            'container-image-ids.tsv': ''.join(f'{service}\tsha256:{index:064x}\n'
                for index, service in enumerate(('gateway-api', 'board-api', 'game-api',
                    'game-engine', 'web-gateway', 'web-game'), 1)).encode(),
            'container-scenario-sha256.tsv': ''.join(f'{service}\t{SCENARIO}\n'
                for service in ('gateway-api', 'game-api', 'game-engine')).encode(),
        }
        self.zips = {11: archive(isolated), 12: archive(common),
                     13: archive({'pin-git-sha.txt': (RUNTIME + '\n').encode()})}
        self.run = {'id': RUN, 'run_attempt': ATTEMPT, 'status': 'completed',
                    'conclusion': 'success', 'event': 'pull_request', 'head_sha': COLLECTOR,
                    'repository': {'full_name': REPOSITORY},
                    'head_repository': {'full_name': REPOSITORY},
                    'pull_requests': [{'number': 1026}], 'path': '.github/workflows/ci.yml'}
        self.branch = {'commit': {'sha': RUNTIME}}
        self.pull = {'state': 'open', 'head': {'sha': COLLECTOR,
                     'repo': {'full_name': REPOSITORY}}}
        self.jobs = {'total_count': 4, 'jobs': [
            {'name': name, 'status': 'completed', 'conclusion': 'success'}
            for name in ('yuzhou-pin-main', 'yuzhou-isolated-evidence',
                         'yuzhou-w0-w2-evidence', 'yuzhou-w3-evidence')]}

    def get(self, path, *, archive=False):
        if path.endswith('/actions/runs/123'):
            return self.run
        if path.endswith('/branches/main'):
            return self.branch
        if path.endswith('/pulls/1026'):
            return self.pull
        if '/actions/runs/123/jobs?' in path:
            return self.jobs
        artifact_id = int(path.split('/artifacts/')[1].split('/')[0])
        if archive:
            return self.zips[artifact_id]
        prefix = next(key for key, value in IDS.items() if value == artifact_id)
        return {'id': artifact_id, 'name': f'{prefix}-{RUN}-{ATTEMPT}', 'expired': False,
                'workflow_run': {'id': RUN, 'head_sha': COLLECTOR},
                'digest': 'sha256:' + hashlib.sha256(self.zips[artifact_id]).hexdigest()}


def verify(client):
    return verify_run_artifacts(client, run_id=RUN, run_attempt=ATTEMPT,
        collector_sha=COLLECTOR, runtime_sha=RUNTIME, map_sha=MAP,
        scenario_sha=SCENARIO, artifact_ids=IDS)


class EvidenceProvenanceTest(unittest.TestCase):
    def test_exact_run_archive_and_runtime_pins_are_read_only(self):
        result = verify(FakeClient())
        self.assertEqual(result['run_id'], RUN)
        self.assertEqual(len(result['image_ids']), 6)
        self.assertFalse(result['w4_verified'])
        self.assertFalse(result['production_stop_authorized'])

    def test_failed_run_or_changed_main_is_rejected(self):
        client = FakeClient()
        client.run['conclusion'] = 'failure'
        with self.assertRaisesRegex(RecoveryError, 'successful collector'):
            verify(client)
        client = FakeClient()
        client.branch['commit']['sha'] = 'e' * 40
        with self.assertRaisesRegex(RecoveryError, 'current main'):
            verify(client)

    def test_stale_collector_or_skipped_job_is_rejected(self):
        client = FakeClient()
        client.pull['head']['sha'] = 'f' * 40
        with self.assertRaisesRegex(RecoveryError, 'current #1026'):
            verify(client)
        client = FakeClient()
        client.jobs['jobs'][2]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(RecoveryError, 'did not all succeed'):
            verify(client)

    def test_changed_zip_and_mounted_scenario_are_rejected(self):
        client = FakeClient()
        original_get = client.get
        def altered(path, *, archive=False):
            if archive and path.endswith('/artifacts/11/zip'):
                return client.zips[12]
            return original_get(path, archive=archive)
        client.get = altered
        with self.assertRaisesRegex(RecoveryError, 'digest differs'):
            verify(client)
        client = FakeClient()
        files = _zip_entries(client.zips[11])
        files['container-scenario-sha256.tsv'] = b'gateway-api\tbad\n'
        client.zips[11] = archive(files)
        with self.assertRaisesRegex(RecoveryError, 'mounted scenario'):
            verify(client)

    def test_zip_path_traversal_and_duplicate_member_are_rejected(self):
        with self.assertRaisesRegex(RecoveryError, 'unsafe'):
            _zip_entries(archive({'../secret': b'no'}))
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as output:
            output.writestr('same', 'first')
            output.writestr('same', 'second')
        with self.assertRaisesRegex(RecoveryError, 'duplicate'):
            _zip_entries(stream.getvalue())


if __name__ == '__main__':
    unittest.main()
