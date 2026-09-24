"""ci.yml 구조 계약.

매트릭스 잡을 잡 단위 `if` 로 건너뛰면 GitHub 는 `<job> (<matrix 값>)` 이 아니라 `<job>` 하나로 보고한다.
필수 체크는 `map-slow-tests (test_…)`·`web (game)` 처럼 매트릭스 값이 붙은 이름이라, 그 PR 은 필수 체크가
영원히 대기하고 머지가 막힌다(#899). 매트릭스 잡의 경로 판정은 단계 `if` 로 건다.
예외는 `if: always()` 로 도는 모음 잡(예: `jvm`)이 `needs` 로 받아 판정하는 매트릭스 잡(예: `city-test`)뿐이다 —
그 잡의 이름은 필수 체크가 아니고, 필수 체크는 모음 잡 이름이다.
"""
from __future__ import annotations

import unittest
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


if __name__ == "__main__":
    unittest.main()
