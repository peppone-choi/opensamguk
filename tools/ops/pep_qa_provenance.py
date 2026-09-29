#!/usr/bin/env python3
"""Read-only GitHub Actions provenance check for the PEP QA evidence run.

This deliberately does not make a W4 or production-stop decision.  The current
collector labels its battle result CONTRACT_DRAFT/NOT_COLLECTED.
"""

import hashlib
import io
import json
import re
import urllib.parse
import urllib.request
import urllib.error
import zipfile

from game_server_recovery import RecoveryError, require


REPOSITORY = 'peppone-choi/opensamguk'
RUN_PATH = '.github/workflows/ci.yml'
ARTIFACT_PREFIXES = ('yuzhou-isolated-evidence', 'yuzhou-w0-w2', 'yuzhou-w3')
SHA40 = re.compile(r'[0-9a-f]{40}\Z')
SHA64 = re.compile(r'[0-9a-f]{64}\Z')
IMAGE = re.compile(r'sha256:[0-9a-f]{64}\Z')
MAX_ARCHIVE = 200 * 1024 * 1024
MAX_ENTRY = 100 * 1024 * 1024


class GitHubEvidenceClient:
    """Small token-authenticated REST reader; never prints the token or bodies."""

    def __init__(self, token):
        require(isinstance(token, str) and bool(token), 'GitHub token required')
        self.token = token

    def get(self, path, *, archive=False):
        require(path.startswith('/repos/' + REPOSITORY + '/') and '..' not in path,
                'invalid GitHub API path')
        request = urllib.request.Request('https://api.github.com' + path, headers={
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json' if not archive else 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'opensamguk-pep-qa-provenance',
        })
        try:
            if archive:
                class NoRedirect(urllib.request.HTTPRedirectHandler):
                    def redirect_request(self, req, fp, code, msg, headers, newurl):
                        return None

                opener = urllib.request.build_opener(NoRedirect)
                try:
                    opener.open(request, timeout=30)
                except urllib.error.HTTPError as response:
                    require(response.code in (302, 303, 307), 'artifact download did not redirect')
                    location = response.headers.get('Location', '')
                    require(location.startswith('https://') and
                            urllib.parse.urlparse(location).hostname != 'api.github.com',
                            'insecure artifact location')
                else:
                    raise RecoveryError('artifact download did not redirect')
                # GitHub's short-lived blob URL carries its own authorization.
                # Forwarding the API bearer token causes 401 and leaks credentials.
                with urllib.request.urlopen(urllib.request.Request(location), timeout=30) as response:
                    require(response.url.startswith('https://'), 'insecure artifact redirect')
                    payload = response.read(MAX_ARCHIVE + 1)
            else:
                with urllib.request.urlopen(request, timeout=30) as response:
                    payload = response.read(4 * 1024 * 1024 + 1)
        except (OSError, ValueError) as exc:
            raise RecoveryError('GitHub evidence fetch failed') from exc
        require(len(payload) <= MAX_ARCHIVE if archive else len(payload) <= 4 * 1024 * 1024,
                'GitHub response exceeds size limit')
        if archive:
            return payload
        try:
            return json.loads(payload)
        except (UnicodeError, ValueError) as exc:
            raise RecoveryError('invalid GitHub JSON') from exc


def _zip_entries(raw):
    require(isinstance(raw, bytes) and len(raw) <= MAX_ARCHIVE, 'invalid artifact archive')
    result = {}
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            require(sum(info.file_size for info in infos) <= MAX_ARCHIVE,
                    'artifact uncompressed size exceeds limit')
            for info in infos:
                name = info.filename
                require(name and not name.startswith('/') and '\\' not in name and
                        '..' not in name.split('/') and name not in result and
                        info.file_size <= MAX_ENTRY and
                        ((info.external_attr >> 16) & 0o170000) != 0o120000,
                        'unsafe or duplicate artifact member')
                if not info.is_dir():
                    result[name] = archive.read(info)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        raise RecoveryError('invalid artifact ZIP') from exc
    return result


def _one(entries, suffix):
    matches = [body for name, body in entries.items()
               if name == suffix or name.endswith('/' + suffix)]
    require(len(matches) == 1, 'missing or duplicate artifact entry: ' + suffix)
    return matches[0]


def _pin_hashes(entries, runtime_sha, map_sha, scenario_sha):
    require(_one(entries, 'pin-git-sha.txt').decode().strip() == runtime_sha,
            'artifact runtime SHA differs')
    pins = {}
    for line in _one(entries, 'pin-sha256.txt').decode().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})\s+([^\s]+)', line)
        require(match is not None and match.group(2) not in pins, 'invalid input pin row')
        pins[match.group(2)] = match.group(1)
    require(pins.get('infra/src/main/resources/map/han-world-v3.json') == map_sha and
            pins.get('infra/src/main/resources/scenario/scenario_990002.json') == scenario_sha and
            pins.get('tools/e2e/fixtures/yuzhou/scenario_990002.json') == scenario_sha,
            'map or scenario input pin differs')


def verify_run_artifacts(client, *, run_id, run_attempt, collector_sha,
                         runtime_sha, map_sha, scenario_sha, artifact_ids):
    """Bind the three current lane artifacts to one successful PR run and main."""
    require(type(run_id) is int and run_id > 0 and type(run_attempt) is int and run_attempt > 0,
            'invalid run identity')
    require(all(isinstance(value, str) and SHA40.fullmatch(value) for value in
                (collector_sha, runtime_sha)), 'invalid Git SHA')
    require(all(isinstance(value, str) and SHA64.fullmatch(value) for value in
                (map_sha, scenario_sha)), 'invalid content SHA')
    require(isinstance(artifact_ids, dict) and set(artifact_ids) == set(ARTIFACT_PREFIXES) and
            all(type(value) is int and value > 0 for value in artifact_ids.values()) and
            len(set(artifact_ids.values())) == len(artifact_ids), 'exact artifact IDs required')
    run = client.get(f'/repos/{REPOSITORY}/actions/runs/{run_id}')
    require(run.get('id') == run_id and run.get('run_attempt') == run_attempt and
            run.get('status') == 'completed' and run.get('conclusion') == 'success' and
            run.get('event') == 'pull_request' and run.get('head_sha') == collector_sha and
            run.get('repository', {}).get('full_name') == REPOSITORY and
            run.get('head_repository', {}).get('full_name') == REPOSITORY and
            [item.get('number') for item in run.get('pull_requests', [])] == [1026] and
            run.get('path') == RUN_PATH,
            'Actions run is not the expected successful collector run')
    branch = client.get(f'/repos/{REPOSITORY}/branches/main')
    require(branch.get('commit', {}).get('sha') == runtime_sha,
            'runtime candidate is no longer current main')
    pull = client.get(f'/repos/{REPOSITORY}/pulls/1026')
    require(pull.get('state') == 'open' and
            pull.get('head', {}).get('sha') == collector_sha and
            pull.get('head', {}).get('repo', {}).get('full_name') == REPOSITORY,
            'collector run is not the current #1026 head')
    jobs_response = client.get(f'/repos/{REPOSITORY}/actions/runs/{run_id}/jobs?per_page=100')
    jobs = jobs_response.get('jobs', [])
    require(type(jobs_response.get('total_count')) is int and
            jobs_response['total_count'] == len(jobs), 'incomplete run job page')
    required_jobs = {'yuzhou-pin-main', 'yuzhou-isolated-evidence',
                     'yuzhou-w0-w2-evidence', 'yuzhou-w3-evidence'}
    selected = [job for job in jobs if job.get('name') in required_jobs]
    require(len(selected) == 4 and {job['name'] for job in selected} == required_jobs and
            all(job.get('status') == 'completed' and job.get('conclusion') == 'success'
                for job in selected), 'required QA jobs did not all succeed')

    evidence = {}
    for prefix in ARTIFACT_PREFIXES:
        artifact_id = artifact_ids[prefix]
        metadata = client.get(f'/repos/{REPOSITORY}/actions/artifacts/{artifact_id}')
        expected_name = f'{prefix}-{run_id}-{run_attempt}'
        digest = metadata.get('digest')
        require(metadata.get('id') == artifact_id and metadata.get('name') == expected_name and
                metadata.get('expired') is False and
                metadata.get('workflow_run', {}).get('id') == run_id and
                metadata.get('workflow_run', {}).get('head_sha') == collector_sha and
                isinstance(digest, str) and digest.startswith('sha256:') and
                SHA64.fullmatch(digest[7:]) is not None,
                'artifact metadata does not match the run')
        raw = client.get(f'/repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip', archive=True)
        require(hashlib.sha256(raw).hexdigest() == digest[7:],
                'artifact archive digest differs from GitHub metadata')
        evidence[prefix] = _zip_entries(raw)

    _pin_hashes(evidence['yuzhou-isolated-evidence'], runtime_sha, map_sha, scenario_sha)
    _pin_hashes(evidence['yuzhou-w0-w2'], runtime_sha, map_sha, scenario_sha)
    require(_one(evidence['yuzhou-w3'], 'pin-git-sha.txt').decode().strip() == runtime_sha,
            'W3 runtime SHA differs')
    runtime = json.loads(_one(evidence['yuzhou-isolated-evidence'], 'runtime-world-pin.json'))
    require(runtime == {'scenarioCode': 'scenario_990002', 'mapName': 'han-world-v3',
                        'worldFormat': 'GENERAL_RETAINER_CAMPAIGN', 'cityCount': 1447},
            'runtime world pin differs')
    image_rows = _one(evidence['yuzhou-isolated-evidence'], 'container-image-ids.tsv').decode().splitlines()
    images = dict(row.split('\t') for row in image_rows)
    require(len(image_rows) == 6 and set(images) == {'gateway-api', 'board-api', 'game-api',
            'game-engine', 'web-gateway', 'web-game'} and
            all(IMAGE.fullmatch(value) for value in images.values()),
            'six runtime image IDs required')
    mounted_rows = _one(evidence['yuzhou-isolated-evidence'], 'container-scenario-sha256.tsv').decode().splitlines()
    mounted = dict(row.split('\t') for row in mounted_rows)
    require(len(mounted_rows) == 3 and set(mounted) == {'gateway-api', 'game-api', 'game-engine'} and
            all(value == scenario_sha for value in mounted.values()),
            'mounted scenario bytes differ')
    return {'run_id': run_id, 'run_attempt': run_attempt, 'collector_sha': collector_sha,
            'runtime_sha': runtime_sha, 'map_sha256': map_sha,
            'scenario_sha256': scenario_sha,
            'artifact_ids': dict(artifact_ids), 'image_ids': images,
            'w4_verified': False, 'production_stop_authorized': False}
