"""ci.yml 구조 계약.

매트릭스 잡을 잡 단위 `if` 로 건너뛰면 GitHub 는 `<job> (<matrix 값>)` 이 아니라 `<job>` 하나로 보고한다.
필수 체크는 `map-slow-tests (test_…)`·`web (game)` 처럼 매트릭스 값이 붙은 이름이라, 그 PR 은 필수 체크가
영원히 대기하고 머지가 막힌다(#899). 매트릭스 잡의 경로 판정은 단계 `if` 로 건다.
예외는 `if: always()` 로 도는 모음 잡(예: `jvm`)이 `needs` 로 받아 판정하는 매트릭스 잡(예: `city-test`)뿐이다 —
그 잡의 이름은 필수 체크가 아니고, 필수 체크는 모음 잡 이름이다.
"""
from __future__ import annotations

import unittest
import copy
import json
import os
import subprocess
import tempfile
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml"


def matrix_jobs_with_job_level_if(workflow: dict) -> list[str]:
    jobs = workflow["jobs"]
    aggregated = set()
    for job in jobs.values():
        if str(job.get("if", "")).strip() == "always()":
            needs = job.get("needs") or []
            aggregated.update([needs] if isinstance(needs, str) else needs)
    return sorted(
        name for name, job in jobs.items()
        if "matrix" in (job.get("strategy") or {}) and "if" in job and name not in aggregated
    )


def switch_evidence_violations(workflow: dict) -> list[str]:
    steps = workflow['jobs']['web']['steps']
    switch = next(step for step in steps if step.get('id') == 'switch_smoke')
    upload = next(step for step in steps if step.get('name') == 'Upload topdown switch evidence')
    violations = []
    output = switch['env'].get('E2E_PLAYWRIGHT_OUTPUT_DIR', '')
    if not output.startswith('test-results/topdown-screens/'):
        violations.append('switch overwrites normal smoke output')
    if 'always()' not in upload.get('if', ''):
        violations.append('successful or failed evidence is lost')
    if upload['with']['path'] != 'web/${{ matrix.app }}/test-results/topdown-screens':
        violations.append('switch artifact includes another phase')
    if '${{ matrix.app }}' not in upload['with']['name'] or '${{ github.run_attempt }}' not in upload['with']['name']:
        violations.append('artifact app/attempt collision')
    return violations


class CiWorkflowContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))

    def test_matrix_jobs_are_not_skipped_at_job_level(self) -> None:
        self.assertEqual([], matrix_jobs_with_job_level_if(self.workflow))

    def test_detector_flags_a_job_level_if_on_a_matrix_job(self) -> None:
        # 검사 자체가 살아 있는지 — 잡 단위 if 를 단 매트릭스 잡은 반드시 걸려야 한다.
        probe = {"jobs": {
            "gated": {"if": "needs.changes.outputs.web == 'true'", "strategy": {"matrix": {"app": ["game"]}}},
            "shard": {"if": "needs.changes.outputs.city == 'true'", "strategy": {"matrix": {"n": [0, 1]}}},
            "collect": {"if": "always()", "needs": ["shard"]},
            "matrix": {"strategy": {"matrix": {"app": ["game"]}}},
        }}
        # shard 는 always() 모음 잡이 받으므로 허용, gated 는 받는 잡이 없으므로 걸린다.
        self.assertEqual(["gated"], matrix_jobs_with_job_level_if(probe))

    def test_path_gated_matrix_jobs_still_gate_their_work(self) -> None:
        # 잡 if 를 뺀 대신 실제 작업 단계마다 경로 판정이 걸려 있어야 한다(무관한 PR 에서 무거운 일을 하지 않는다).
        for name, output in (("map-slow-tests", "map_slow"), ("web", "web")):
            steps = self.workflow["jobs"][name]["steps"]
            gate = f"needs.changes.outputs.{output} == 'true'"
            skip = f"needs.changes.outputs.{output} != 'true'"
            self.assertTrue(any(step.get("if") == skip for step in steps), f"{name}: skip notice step missing")
            ungated = [step.get("name") or step.get("uses") or step.get("run") for step in steps
                       if step.get("if") != skip and gate not in str(step.get("if", ""))]
            self.assertEqual([], ungated, f"{name}: steps without the {output} path gate")

    def test_contracts_map_steps_are_path_gated_and_ops_steps_are_not(self) -> None:
        # 지도 단계는 map 판정으로 건너뛰고, app/ 파일을 읽는 운영·CI 도구 단계는 contracts 가 돌면 늘 돈다.
        outputs = self.workflow["jobs"]["changes"]["outputs"]
        self.assertIn("map", outputs)
        steps = {step.get("name", ""): str(step.get("if", "")) for step in self.workflow["jobs"]["contracts"]["steps"]}
        gate = "needs.changes.outputs.map == 'true'"
        for name in ("Verify Han map data contract tests", "Verify Han territory disconnection ledger",
                     "Verify han-tiles coupled artifacts (batch, names every stale artifact)",
                     "Verify scenario data contract tests", "Verify frontier county materialization"):
            self.assertIn(gate, steps[name], name)
        for name in ("Verify JWT rollout contract", "Verify CI path and shard tooling",
                     "Verify game server recovery behavioral guards"):
            self.assertNotIn(gate, steps[name], name)

    def test_switch_evidence_survives_success_and_failure_without_overwriting_smoke(self) -> None:
        self.assertEqual([], switch_evidence_violations(self.workflow))
        self.assertEqual(20, self.workflow['jobs']['web']['timeout-minutes'])

    def test_switch_evidence_detector_rejects_lost_success_and_shared_output(self) -> None:
        probe = copy.deepcopy(self.workflow)
        for step in probe['jobs']['web']['steps']:
            if step.get('id') == 'switch_smoke':
                step['env']['E2E_PLAYWRIGHT_OUTPUT_DIR'] = 'test-results/playwright-output'
            if step.get('name') == 'Upload topdown switch evidence':
                step['if'] = "failure() && needs.changes.outputs.web == 'true'"
        self.assertEqual(['switch overwrites normal smoke output', 'successful or failed evidence is lost'],
                         switch_evidence_violations(probe))

    def test_switch_shell_preserves_real_exit_and_distinguishes_unexecuted_tests(self) -> None:
        # Execute the real phase shell with inert tools; no build, browser or server is launched.
        step = next(step for step in self.workflow['jobs']['web']['steps'] if step.get('id') == 'switch_smoke')
        script = step['run'].replace('${{ matrix.app }}', 'gateway')
        cases = [(False, 0, 0, 0, False, False, None, None, 'not-run'),
                 (True, 23, 0, 23, True, False, 23, None, 'failure'),
                 (True, 0, 17, 17, True, True, 0, 17, 'failure'),
                 (True, 0, 0, 0, True, True, 0, 0, 'success')]
        for spec, build_rc, test_rc, exit_rc, built, tested, build_exit, test_exit, outcome in cases:
            with self.subTest(spec=spec, build_rc=build_rc, test_rc=test_rc), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                tools = root / 'bin'; tools.mkdir()
                (root / '.next').mkdir(); (root / '.next/BUILD_ID').write_text('off-pinned')
                (root / 'e2e/topdown-screens').mkdir(parents=True)
                if spec:
                    (root / 'e2e/topdown-screens/normal.topdown-screen.spec.ts').touch()
                fake = {
                    'git': '#!/bin/sh\nprintf pinned-checkout',
                    'curl': '#!/bin/sh\nprintf 200',
                    'corepack': '''#!/bin/sh
if [ "$2" = build ]; then
  [ "$FAKE_BUILD_EXIT" = 0 ] || exit "$FAKE_BUILD_EXIT"
  mkdir -p "$NEXT_DIST_DIR"; printf on-pinned > "$NEXT_DIST_DIR/BUILD_ID"
elif [ "$3" = playwright ]; then
  exit "$FAKE_TEST_EXIT"
fi
''',
                }
                for name, source in fake.items():
                    path = tools / name; path.write_text(source); path.chmod(0o755)
                phase = root / 'test-results/topdown-screens/phase.json'
                env = {**os.environ, 'PATH': str(tools) + os.pathsep + os.environ['PATH'],
                       'K2_CI_EVIDENCE': str(phase), 'K2_PR_HEAD': 'head', 'K2_PR_BASE': 'base',
                       'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '2', 'NEXT_DIST_DIR': '.next-topdown-screens',
                       'NEXT_PUBLIC_TOPDOWN_SCREENS': '1', 'RUNNER_TEMP': str(root),
                       'FAKE_BUILD_EXIT': str(build_rc), 'FAKE_TEST_EXIT': str(test_rc)}
                result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=root, env=env,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(exit_rc, result.returncode, result.stderr)
                data = json.loads(phase.read_text())
                self.assertEqual((built, tested, build_exit, test_exit, exit_rc, outcome),
                                 (data['buildExecuted'], data['testsExecuted'], data['buildExit'],
                                  data['testExit'], data['exit'], data['outcome']))
                self.assertEqual('off-pinned', data['off']['buildId'])
                if tested:
                    self.assertEqual('on-pinned', data['on']['buildId'])


if __name__ == "__main__":
    unittest.main()
