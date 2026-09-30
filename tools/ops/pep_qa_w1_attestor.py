#!/usr/bin/env python3
"""Verify the three W1 attempts without granting a production reset.

The caller first verifies the same #1026 run through pep_qa_provenance and
checks its W0/W2, W3 and W4 content through pep_qa_final_gate.
"""

import hashlib
import json
import re
import xml.etree.ElementTree as ET

from game_server_recovery import RecoveryError, require
from pep_qa_provenance import REPOSITORY, SHA64, _one, _zip_entries


SUITES = {
    'opensamguk.engine.boot.PassChainInvarianceIT': 1,
    'opensamguk.engine.boot.S3PassChainProbeIT': 1,
    'opensamguk.engine.invariance.YuzhouCampaignInvarianceTest': 4,
}
BASELINES = {'s3-chain-48', 'yuzhou-36-seed-00', 'yuzhou-36-seed-01'}
LINKS = {'enlist', 'dispatch', 'march', 'encounter', 'siege', 'capture',
         'income', 'salary', 'assessment', 'ranking'}


def _json(entries, name):
    try:
        value = json.loads(_one(entries, name))
    except (UnicodeError, ValueError, TypeError) as exc:
        raise RecoveryError('invalid W1 JSON') from exc
    require(isinstance(value, dict), 'invalid W1 JSON object')
    return value


def _pins(entries, runtime_sha, map_sha, scenario_sha):
    require(_one(entries, 'pin-git-sha.txt').decode().strip() == runtime_sha,
            'W1 runtime SHA differs')
    values = {}
    for line in _one(entries, 'pin-sha256.txt').decode().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})\s+([^\s]+)', line)
        require(match is not None and match.group(2) not in values,
                'invalid W1 pin row')
        values[match.group(2)] = match.group(1)
    require(values == {
        'infra/src/main/resources/map/han-world-v3.json': map_sha,
        'infra/src/main/resources/scenario/scenario_990002.json': scenario_sha,
        'tools/e2e/fixtures/yuzhou/scenario_990002.json': scenario_sha,
    }, 'W1 map or scenario pin differs')
    return values


def _baseline(entries):
    values = {}
    for line in _one(entries, 'world-state-sha256.txt').decode().splitlines():
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'(\S+) ([0-9a-f]{64})', line)
        require(match is not None and match.group(1) not in values,
                'invalid W1 baseline row')
        if match.group(1) in BASELINES:
            values[match.group(1)] = match.group(2)
    require(set(values) == BASELINES, 'missing W1 baseline hashes')
    return values


def _xml(entries, expected_hashes):
    require(isinstance(expected_hashes, dict) and
            set(expected_hashes) == {'TEST-' + name + '.xml' for name in SUITES},
            'W1 XML inventory differs')
    actual_xml = [name.rsplit('/', 1)[-1] for name in entries
                  if name.rsplit('/', 1)[-1].startswith('TEST-')]
    require(len(actual_xml) == len(SUITES) and set(actual_xml) == set(expected_hashes),
            'W1 archive XML inventory differs')
    first = None
    states = {}
    for suite_name, count in SUITES.items():
        name = 'TEST-' + suite_name + '.xml'
        raw = _one(entries, name)
        require(hashlib.sha256(raw).hexdigest() == expected_hashes[name],
                'W1 XML digest differs')
        try:
            root = ET.fromstring(raw)
            counts = {key: int(root.attrib[key]) for key in
                      ('tests', 'failures', 'errors', 'skipped')}
        except (ET.ParseError, KeyError, ValueError, TypeError) as exc:
            raise RecoveryError('invalid W1 XML') from exc
        require(root.tag == 'testsuite' and root.get('name') == suite_name and
                counts == {'tests': count, 'failures': 0, 'errors': 0, 'skipped': 0},
                'W1 XML failed, skipped or mislabeled')
        cases = root.findall('testcase')
        require(len(cases) == count and len({case.get('name') for case in cases}) == count and
                all(case.get('classname') == suite_name and
                    not any(case.find(tag) is not None for tag in
                            ('failure', 'error', 'skipped')) for case in cases),
                'W1 testcase missing or failed')
        names = ' '.join(case.get('name', '') for case in cases)
        if suite_name.endswith('S3PassChainProbeIT'):
            require('적색 짝' in names, 'W1 DB red pair missing')
        if suite_name.endswith('YuzhouCampaignInvarianceTest'):
            require(all(marker in names for marker in
                        ('36 phases', 'seed 01 replay', 'cutting npc deployment')),
                    'W1 36-phase baseline or red pair missing')
        output = '\n'.join((node.text or '') for node in root.iter('system-out'))
        first_rows = re.findall(r'^s3-first-phase (.+)$', output, re.MULTILINE)
        if suite_name.endswith('PassChainInvarianceIT') and not suite_name.endswith('S3PassChainProbeIT'):
            require(len(first_rows) == 1, 'W1 first-event trace missing')
            pairs = first_rows[0].split()
            require(len(pairs) == len(LINKS), 'W1 first-event trace incomplete')
            parsed = {}
            for pair in pairs:
                match = re.fullmatch(r'([a-z]+)=([1-9][0-9]*)', pair)
                require(match is not None and match.group(1) not in parsed,
                        'invalid W1 first-event item')
                parsed[match.group(1)] = int(match.group(2))
            require(set(parsed) == LINKS and all(1 <= value <= 48 for value in parsed.values()) and
                    parsed['enlist'] <= parsed['dispatch'] and
                    parsed['march'] <= parsed['encounter'] <= parsed['capture'] and
                    parsed['siege'] <= parsed['capture'],
                    'W1 first-event chain out of order')
            first = parsed
        else:
            require(not first_rows, 'unexpected W1 first-event trace')
        for key, value in re.findall(r'^behavior-baseline (\S+) ([0-9a-f]{64})$', output,
                                     re.MULTILINE):
            require(key in BASELINES and key not in states,
                    'unexpected or duplicate W1 state hash')
            states[key] = value
    require(first is not None and set(states) == BASELINES,
            'W1 first-event or state evidence missing')
    return first, states


def inspect_w1_content(*, provenance, attempts, comparison):
    """Inspect already digest-verified ZIP members from the same #1026 run."""
    require(isinstance(provenance, dict) and provenance.get('w4_verified') is False and
            provenance.get('production_stop_authorized') is False and
            set(attempts) == {1, 2, 3}, 'incomplete W1 provenance or attempts')
    manifest = _json(comparison, 'w1-manifest.json')
    require(manifest.get('status') == 'PASS' and manifest.get('gate') == 'W1' and
            manifest.get('run_id') == str(provenance['run_id']) and
            manifest.get('run_attempt') == str(provenance['run_attempt']) and
            manifest.get('product_sha') == provenance['runtime_sha'] and
            isinstance(manifest.get('attempts'), list) and len(manifest['attempts']) == 3,
            'W1 comparison manifest is not for this successful run')
    seen = []
    for number, reported in enumerate(manifest['attempts'], start=1):
        require(isinstance(reported, dict) and reported.get('number') == number and
                reported.get('product_sha') == provenance['runtime_sha'],
                'W1 attempt manifest identity differs')
        entries = attempts[number]
        pins = _pins(entries, provenance['runtime_sha'], provenance['map_sha256'],
                     provenance['scenario_sha256'])
        baseline = _baseline(entries)
        first, states = _xml(entries, reported.get('xml_sha256'))
        require(reported.get('pin_sha256') == pins and
                reported.get('normalized_state_sha256') == states == baseline and
                reported.get('first_event_phase') == first,
                'W1 attempt content differs from comparison')
        seen.append((pins, states, first))
    require(seen[0] == seen[1] == seen[2], 'three W1 attempts differ')
    return {'runtime_sha': provenance['runtime_sha'], 'w1_verified': True,
            'w1_attempts': 3, 'production_stop_authorized': False}


def inspect_verified_w1(client, *, provenance, artifact_ids):
    """Bind W1 content to GitHub metadata, digests and successful matrix jobs."""
    require(isinstance(artifact_ids, dict) and set(artifact_ids) == {1, 2, 3, 'compare'} and
            all(type(value) is int and value > 0 for value in artifact_ids.values()) and
            len(set(artifact_ids.values())) == 4, 'four distinct W1 artifact IDs required')
    run_id = provenance['run_id']
    attempt = provenance['run_attempt']
    jobs_response = client.get(f'/repos/{REPOSITORY}/actions/runs/{run_id}/jobs?per_page=100')
    jobs = jobs_response.get('jobs', [])
    relevant = [job for job in jobs if job.get('name', '').startswith('yuzhou-w1-attempt')]
    compare = [job for job in jobs if job.get('name') == 'yuzhou-w1-compare']
    require(type(jobs_response.get('total_count')) is int and
            jobs_response['total_count'] == len(jobs) and len(relevant) == 3 and
            len({job['name'] for job in relevant}) == 3 and len(compare) == 1 and
            all(job.get('status') == 'completed' and
                                      job.get('conclusion') == 'success'
                                      for job in relevant + compare),
            'W1 jobs did not all succeed')
    entries = {}
    for key, artifact_id in artifact_ids.items():
        prefix = 'yuzhou-w1-compare' if key == 'compare' else 'yuzhou-w1'
        expected_name = f'{prefix}-{run_id}-{attempt}'
        if key != 'compare':
            expected_name += f'-{key}'
        metadata = client.get(f'/repos/{REPOSITORY}/actions/artifacts/{artifact_id}')
        expected_digest = metadata.get('digest')
        require(metadata.get('id') == artifact_id and metadata.get('name') == expected_name and
                metadata.get('expired') is False and
                metadata.get('workflow_run', {}).get('id') == run_id and
                metadata.get('workflow_run', {}).get('head_sha') == provenance['collector_sha'] and
                isinstance(expected_digest, str) and expected_digest.startswith('sha256:') and
                SHA64.fullmatch(expected_digest[7:]) is not None,
                'W1 artifact metadata differs')
        raw = client.get(f'/repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip', archive=True)
        require(hashlib.sha256(raw).hexdigest() == expected_digest[7:],
                'W1 artifact archive digest differs')
        entries[key] = _zip_entries(raw)
    return inspect_w1_content(provenance=provenance,
                              attempts={key: entries[key] for key in (1, 2, 3)},
                              comparison=entries['compare'])
