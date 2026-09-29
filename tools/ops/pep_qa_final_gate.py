#!/usr/bin/env python3
"""Read-only checks on pinned PEP QA artifacts.

W1 is checked only when all four W1 artifact IDs are supplied. Even a
successful inspection cannot authorize a production stop or reset.
"""

import argparse
import hashlib
import json
import os
import sys
import xml.etree.ElementTree as ET

from game_server_recovery import RecoveryError, require
from pep_qa_provenance import (ARTIFACT_PREFIXES, REPOSITORY, GitHubEvidenceClient,
                               _one, _zip_entries, verify_run_artifacts)
from pep_qa_w1_attestor import inspect_verified_w1


BOUNDARY_SUITES = (
    'YuzhouSliceScenarioTest', 'SiegeServiceTest', 'CapitalAfterCaptureTest',
    'EncounterResolverTest', 'DomesticEngineTest', 'RoadFortSiegeServiceTest',
    'StrategicSupplyProviderTest', 'SpatialSupplyProviderTest', 'MonthBoundaryLoopIT',
)


def inspect_verified_run(client, *, w1_artifact_ids=None, **run_identity):
    """Inspect the exact archive bytes whose IDs and digests GitHub verified."""
    archives = {}

    class CapturingClient:
        def get(self, path, *, archive=False):
            value = client.get(path, archive=archive)
            if archive:
                archives[path] = value
            return value

    provenance = verify_run_artifacts(CapturingClient(), **run_identity)
    artifact_ids = provenance['artifact_ids']
    entries = {}
    for prefix in ARTIFACT_PREFIXES:
        path = f'/repos/{REPOSITORY}/actions/artifacts/{artifact_ids[prefix]}/zip'
        require(path in archives, 'digest-verified artifact bytes unavailable')
        entries[prefix] = _zip_entries(archives[path])
    result = inspect_current_artifacts(
        provenance=provenance,
        isolated=entries['yuzhou-isolated-evidence'],
        w0_w2=entries['yuzhou-w0-w2'],
        w3=entries['yuzhou-w3'],
    )
    if w1_artifact_ids is not None:
        w1 = inspect_verified_w1(client, provenance=provenance,
                                 artifact_ids=w1_artifact_ids)
        result['w1_verified'] = w1['w1_verified']
        result['w1_attempts'] = w1['w1_attempts']
    return result


def _json_entry(entries, name):
    try:
        value = json.loads(_one(entries, name))
    except (UnicodeError, ValueError, TypeError) as exc:
        raise ValueError('invalid QA JSON: ' + name) from exc
    require(isinstance(value, dict), 'QA JSON must be an object: ' + name)
    return value


def _clean_xml(entries, suite):
    matches = [body for name, body in entries.items()
               if name.rsplit('/', 1)[-1].startswith('TEST-') and
               (name.endswith('.' + suite + '.xml') or
                name.endswith('TEST-' + suite + '.xml'))]
    require(len(matches) == 1, 'missing or duplicate W0/W2 XML: ' + suite)
    try:
        root = ET.fromstring(matches[0])
        require(root.tag == 'testsuite', 'unexpected XML root')
        counts = {key: int(root.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
    except (ET.ParseError, KeyError, ValueError, TypeError) as exc:
        raise ValueError('invalid W0/W2 XML: ' + suite) from exc
    require(counts['tests'] > 0 and all(counts[key] == 0 for key in ('failures', 'errors', 'skipped')),
            'W0/W2 XML failed or skipped: ' + suite)
    return counts['tests']


def inspect_current_artifacts(*, provenance, isolated, w0_w2, w3):
    """Inspect artifact content already bound to one run and SHA by provenance.

    ``provenance`` must be the result of ``verify_run_artifacts``.  This
    function checks the evidence content; it never promotes its result to a
    production authorization.  Callers must keep these entries tied to the
    digest-verified artifact IDs supplied to that verifier.
    """
    require(isinstance(provenance, dict) and provenance.get('w4_verified') is False and
            provenance.get('production_stop_authorized') is False and
            provenance.get('runtime_sha') == _one(isolated, 'pin-git-sha.txt').decode().strip() ==
            _one(w0_w2, 'pin-git-sha.txt').decode().strip() ==
            _one(w3, 'pin-git-sha.txt').decode().strip(),
            'artifact SHA/provenance mismatch')
    test_count = sum(_clean_xml(w0_w2, suite) for suite in BOUNDARY_SUITES)
    require(_one(w0_w2, 'forced-boundary-exceptions.txt').strip() == b'',
            'forced phase-boundary exception remains')
    require(_one(w3, 'vitest.log').strip() and _one(w3, 'typecheck.log').strip(),
            'missing W3 web evidence')

    playwright = _one(isolated, 'playwright-results.json')
    manifest = _json_entry(isolated, 'yuzhou-evidence-manifest.json')
    require(manifest.get('source_sha256') == hashlib.sha256(playwright).hexdigest(),
            'W4 summary is not bound to the captured Playwright run')
    browser = _json_entry(isolated, 'playwright-results.json')
    stats = browser.get('stats')
    require(isinstance(stats, dict) and stats.get('expected') == 1 and
            all(stats.get(field) == 0 for field in ('skipped', 'unexpected', 'flaky')),
            'W4 browser run did not pass cleanly')
    suites = browser.get('suites')
    matches = [spec for suite in suites if isinstance(suite, dict) and
               (suite.get('file', '').endswith('yuzhou-live.spec.ts') or
                suite.get('title', '').endswith('yuzhou-live.spec.ts'))
               for spec in suite.get('specs', [])] if isinstance(suites, list) else []
    require(len(matches) == 1 and len(matches[0].get('tests', [])) == 1 and
            len(matches[0]['tests'][0].get('results', [])) == 1 and
            matches[0]['tests'][0]['results'][0].get('status') == 'passed',
            'W4 expected Playwright case did not pass')
    expected_screens = {'screen-' + name + '.png' for name in
                        ('court', 'hand', 'orders', 'posts', 'retinue', 'siege',
                         'supply', 'war-room', 'yuedan')}
    require(set(manifest.get('screens', [])) == expected_screens and
            manifest.get('api_count') == 13 and
            type(manifest.get('event_count')) is int and manifest['event_count'] > 0,
            'W4 screen/API/event evidence incomplete')
    battle = manifest.get('battle_result_gate')
    require(isinstance(battle, dict) and battle.get('status') == 'DB_BACKED_COVERAGE' and
            battle.get('source') == 'POST_FLUSH_FILE_SINK' and
            type(battle.get('resolved_count')) is int and battle['resolved_count'] > 0 and
            type(battle.get('phase3_winning_count')) is int and battle['phase3_winning_count'] > 0 and
            type(battle.get('callback_count')) is int and battle['callback_count'] > 0 and
            type(battle.get('db_last_battle_rows')) is int and battle['db_last_battle_rows'] > 0,
            'W4 committed battle, phase-3 winner, callback or DB comparison missing')
    hashes = battle.get('files_sha256')
    require(isinstance(hashes, dict) and len(hashes) == battle['resolved_count'],
            'W4 battle file inventory incomplete')
    for filename, expected_hash in hashes.items():
        require(isinstance(filename, str) and filename.startswith('battle-990002-') and
                filename.endswith('.json') and isinstance(expected_hash, str) and
                hashlib.sha256(_one(isolated, filename)).hexdigest() == expected_hash,
                'W4 battle file differs from manifest')
    phase = manifest.get('phase_evidence')
    require(isinstance(phase, dict) and type(phase.get('npcBattles')) is int and
            phase['npcBattles'] > 0 and type(phase.get('liveEncounterCount')) is int and
            phase['liveEncounterCount'] >= battle['sealed_count'] and
            phase.get('repeatedNeutralCaptures') == 0 and
            phase.get('abandonedWithGarrison') == 0 and
            isinstance(phase.get('yuedan'), dict) and phase['yuedan'].get('status') == 'READY',
            'W4 world progression or monthly evidence incomplete')
    return {
        'runtime_sha': provenance['runtime_sha'],
        'w0_w2_xml_tests': test_count,
        'w2_forced_exceptions': 0,
        'w3_verified': True,
        'w4_db_backed_battles': battle['resolved_count'],
        'w4_phase3_winning_count': battle['phase3_winning_count'],
        'w1_verified': False,
        'production_stop_authorized': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--run-attempt', type=int, required=True)
    parser.add_argument('--collector-sha', required=True)
    parser.add_argument('--runtime-sha', required=True)
    parser.add_argument('--map-sha256', required=True)
    parser.add_argument('--scenario-sha256', required=True)
    parser.add_argument('--isolated-artifact-id', type=int, required=True)
    parser.add_argument('--w0-w2-artifact-id', type=int, required=True)
    parser.add_argument('--w3-artifact-id', type=int, required=True)
    parser.add_argument('--w1-attempt-1-artifact-id', type=int)
    parser.add_argument('--w1-attempt-2-artifact-id', type=int)
    parser.add_argument('--w1-attempt-3-artifact-id', type=int)
    parser.add_argument('--w1-compare-artifact-id', type=int)
    args = parser.parse_args()
    try:
        w1_ids = (args.w1_attempt_1_artifact_id, args.w1_attempt_2_artifact_id,
                  args.w1_attempt_3_artifact_id, args.w1_compare_artifact_id)
        require(all(value is None for value in w1_ids) or
                all(value is not None for value in w1_ids),
                'all four W1 artifact IDs must be provided together')
        result = inspect_verified_run(
            GitHubEvidenceClient(os.environ.get('GITHUB_TOKEN', '')),
            w1_artifact_ids=None if w1_ids[0] is None else {
                1: w1_ids[0], 2: w1_ids[1], 3: w1_ids[2], 'compare': w1_ids[3]},
            run_id=args.run_id, run_attempt=args.run_attempt,
            collector_sha=args.collector_sha, runtime_sha=args.runtime_sha,
            map_sha=args.map_sha256, scenario_sha=args.scenario_sha256,
            artifact_ids={
                'yuzhou-isolated-evidence': args.isolated_artifact_id,
                'yuzhou-w0-w2': args.w0_w2_artifact_id,
                'yuzhou-w3': args.w3_artifact_id,
            },
        )
    except (RecoveryError, OSError, ValueError, KeyError, TypeError):
        print('PEP QA evidence incomplete; production stop not authorized', file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
