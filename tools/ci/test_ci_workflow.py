"""ci.yml 구조 계약.

매트릭스 잡을 잡 단위 `if` 로 건너뛰면 GitHub 는 `<job> (<matrix 값>)` 이 아니라 `<job>` 하나로 보고한다.
필수 체크는 `map-slow-tests (test_…)`·`web (game)` 처럼 매트릭스 값이 붙은 이름이라, 그 PR 은 필수 체크가
영원히 대기하고 머지가 막힌다(#899). 매트릭스 잡의 경로 판정은 단계 `if` 로 건다.
예외는 `if: always()` 로 도는 모음 잡(예: `jvm`)이 `needs` 로 받아 판정하는 매트릭스 잡(예: `city-test`)뿐이다 —
그 잡의 이름은 필수 체크가 아니고, 필수 체크는 모음 잡 이름이다.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
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


class WebE2eArtifactContractTest(unittest.TestCase):
    """Execute the workflow's real shell with local command fakes; no browser/server."""

    def setUp(self) -> None:
        self.workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        self.steps = self.workflow["jobs"]["web"]["steps"]
        self.by_name = {s.get("name"): s for s in self.steps}
        self.smoke = next(s for s in self.steps if s.get("id") == "web_smoke")
        self.topdown = next(s for s in self.steps if s.get("id") == "web_topdown")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.runner = self.root / "runner"
        self.runner.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, RUNNER_TEMP=str(self.runner),
                        GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]
        self.install_fake_commands()
        prepared = self.shell(self.by_name["Prepare web e2e phase recorder"], "game")
        self.assertEqual(0, prepared.returncode, prepared.stdout)

    def render(self, value: str, app: str) -> str:
        replacements = {
            "${{ runner.temp }}": str(self.runner), "${{ matrix.app }}": app,
            "${{ github.event.pull_request.head.sha || github.sha }}": "a" * 40,
            "${{ github.workflow_sha }}": "b" * 40,
        }
        for before, after in replacements.items():
            value = value.replace(before, after)
        return value

    def shell(self, step: dict, app: str, **extra: str) -> subprocess.CompletedProcess:
        env = dict(self.env, **{k: self.render(str(v), app) for k, v in step.get("env", {}).items()})
        env.update(extra)
        cwd = self.root / app
        cwd.mkdir(exist_ok=True)
        return subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
                               self.render(step["run"], app)], cwd=cwd, env=env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)

    def install_fake_commands(self) -> None:
        # Each fake Playwright invocation really cleans outputDir and replaces JSON.
        # If the workflow reuses paths, these behavioral tests lose the first trace.
        corepack = self.bin / "corepack"
        corepack.write_text("""#!/usr/bin/env python3
import json, os, shutil, signal, sys
from pathlib import Path
args = sys.argv[1:]
if args[:4] == ['pnpm', 'exec', 'playwright', 'test']:
    output = Path(os.environ['E2E_PLAYWRIGHT_OUTPUT_DIR'])
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    phase = os.environ['E2E_PHASE']
    code = int(os.environ.get('TEST_PLAYWRIGHT_EXIT', '0'))
    if code:
        (output / 'trace.zip').write_bytes(('failure:' + phase).encode())
        (output / 'screenshot.png').write_bytes(b'fake failure screenshot')
    if os.environ['E2E_APP'] == 'game':
        path = Path(os.environ['E2E_PLAYWRIGHT_JSON'])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({'phase': phase, 'unexpected': int(code != 0), 'args': args[4:]}))
    print('playwright:' + phase + ':exit=' + str(code), flush=True)
    if os.environ.get('TEST_TERMINATE') == '1':
        os.kill(os.getppid(), signal.SIGTERM)
    sys.exit(code)
if args[:3] == ['pnpm', 'exec', 'tsc']:
    sys.exit(int(os.environ.get('TEST_TYPECHECK_EXIT', '0')))
if args == ['pnpm', 'build']:
    sys.exit(int(os.environ.get('TEST_BUILD_EXIT', '0')))
if args[:4] == ['pnpm', 'exec', 'playwright', 'install']:
    sys.exit(int(os.environ.get('TEST_INSTALL_EXIT', '0')))
sys.exit(0)
""", encoding="utf-8")
        corepack.chmod(0o755)
        curl = self.bin / "curl"
        curl.write_text("#!/bin/sh\nprintf 200\n", encoding="utf-8")
        curl.chmod(0o755)

    def phase_dir(self, step: dict, app: str) -> Path:
        return Path(self.render(step["env"]["E2E_PHASE_DIR"], app))

    def finalize(self, phase: str, app: str, outcome: str) -> dict:
        name = "Finalize smoke e2e metadata" if phase == "smoke" else "Finalize topdown screens e2e metadata"
        result = self.shell(self.by_name[name], app, E2E_STEP_OUTCOME=outcome)
        self.assertEqual(0, result.returncode, result.stdout)
        step = self.smoke if phase == "smoke" else self.topdown
        return json.loads((self.phase_dir(step, app) / "phase.json").read_text())

    def topdown_specs(self) -> None:
        directory = self.root / "game/e2e/topdown-screens"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "screen.topdown-screen.spec.ts").write_text("// synthetic spec\n")

    def test_phase_paths_are_disjoint_and_metadata_survives_output_cleanup(self) -> None:
        for app in ("game", "gateway"):
            for step in (self.smoke, self.topdown):
                env = {k: self.render(str(v), app) for k, v in step["env"].items()}
                directory = Path(env["E2E_PHASE_DIR"])
                self.assertEqual(directory, Path(env["E2E_PLAYWRIGHT_OUTPUT_DIR"]).parent)
                self.assertEqual(directory, Path(env["E2E_PLAYWRIGHT_JSON"]).parent)
                self.assertNotEqual(directory, Path(env["E2E_PLAYWRIGHT_OUTPUT_DIR"]))
        self.assertNotEqual(self.phase_dir(self.smoke, "game"), self.phase_dir(self.topdown, "game"))
        self.assertNotEqual(self.phase_dir(self.smoke, "game"), self.phase_dir(self.smoke, "gateway"))

    def test_smoke_is_uploaded_before_topdown_and_both_uploads_are_attempt_scoped(self) -> None:
        smoke_upload = self.by_name["Upload smoke e2e results"]
        topdown_upload = self.by_name["Upload topdown screens e2e results"]
        self.assertLess(self.steps.index(self.smoke), self.steps.index(smoke_upload))
        self.assertLess(self.steps.index(smoke_upload), self.steps.index(self.topdown))
        self.assertLess(self.steps.index(self.topdown), self.steps.index(topdown_upload))
        for upload, phase, step_id, run in ((smoke_upload, "smoke", "web_smoke", self.smoke),
                                          (topdown_upload, "topdown-screens", "web_topdown", self.topdown)):
            with self.subTest(phase=phase):
                self.assertEqual("actions/upload-artifact@v4", upload["uses"])
                condition = upload["if"]
                for term in ("always()", "needs.changes.outputs.web == 'true'",
                             f"steps.{step_id}.outcome != ''", f"steps.{step_id}.outcome != 'skipped'"):
                    self.assertIn(term, condition)
                config = upload["with"]
                self.assertIn("${{ github.run_attempt }}", config["name"])
                self.assertIn(phase, config["name"])
                self.assertEqual(run["env"]["E2E_PHASE_DIR"], config["path"])
                self.assertIs(False, config["overwrite"])
                self.assertEqual(7, config["retention-days"])
                self.assertEqual("error", config["if-no-files-found"])
        self.assertIn("${{ matrix.app }}", smoke_upload["with"]["name"])
        self.assertIn("matrix.app == 'game'", topdown_upload["if"])

    def test_execution_gates_and_required_matrix_are_preserved(self) -> None:
        job = self.workflow["jobs"]["web"]
        self.assertNotIn("if", job)
        self.assertEqual(["gateway", "game"], job["strategy"]["matrix"]["app"])
        self.assertEqual(20, job["timeout-minutes"])
        self.assertNotIn("continue-on-error", self.smoke)
        self.assertNotIn("continue-on-error", self.topdown)
        self.assertEqual("!cancelled() && needs.changes.outputs.web == 'true'", self.smoke["if"])
        self.assertEqual("!cancelled() && matrix.app == 'game' && needs.changes.outputs.web == 'true'", self.topdown["if"])
        for phase in (self.smoke, self.topdown):
            self.assertIn('exit 1;', phase["run"])
        discover = self.workflow["jobs"]["contracts"]["steps"]
        self.assertTrue(any("unittest discover -s tools/ci -p 'test_*.py'" in s.get("run", "") for s in discover))

    def test_smoke_failure_then_topdown_success_preserves_first_trace_json_and_exit(self) -> None:
        self.topdown_specs()
        smoke = self.shell(self.smoke, "game", TEST_PLAYWRIGHT_EXIT="7")
        self.assertEqual(1, smoke.returncode, smoke.stdout)  # Original shell maps Playwright failures to 1.
        first = self.finalize("smoke", "game", "failure")
        directory = self.phase_dir(self.smoke, "game")
        before = {p.relative_to(directory): p.read_bytes() for p in directory.rglob("*") if p.is_file()}
        topdown = self.shell(self.topdown, "game")
        self.assertEqual(0, topdown.returncode, topdown.stdout)
        last = self.finalize("topdown-screens", "game", "success")
        after = {p.relative_to(directory): p.read_bytes() for p in directory.rglob("*") if p.is_file()}
        self.assertEqual(before, after, "topdown destroyed the first phase evidence")
        self.assertEqual(b"failure:smoke", before[Path("playwright-output/trace.zip")])
        self.assertEqual(7, first["playwrightExitCode"])
        self.assertEqual(1, first["exitCode"])
        self.assertEqual("failure", first["workflowStepOutcome"])
        self.assertEqual("success", last["workflowStepOutcome"])
        self.assertEqual(0, last["exitCode"])
        self.assertEqual(["e2e/smoke"], json.loads(before[Path("results.json")])["args"])
        self.assertEqual(["e2e/topdown-screens/screen.topdown-screen.spec.ts"],
                         json.loads((self.phase_dir(self.topdown, "game") / "results.json").read_text())["args"])
        self.assertEqual("a" * 40, first["headSha"])
        self.assertEqual("b" * 40, first["workflowSha"])
        self.assertEqual("123", first["runId"])
        self.assertEqual("1", first["runAttempt"])
        self.assertIsNotNone(first["startedAt"])
        self.assertIsNotNone(first["finishedAt"])

    def test_topdown_failure_keeps_successful_smoke_results(self) -> None:
        self.topdown_specs()
        self.assertEqual(0, self.shell(self.smoke, "game").returncode)
        first = self.finalize("smoke", "game", "success")
        self.assertEqual(1, self.shell(self.topdown, "game", TEST_PLAYWRIGHT_EXIT="9").returncode)
        last = self.finalize("topdown-screens", "game", "failure")
        self.assertEqual(0, first["playwrightExitCode"])
        self.assertEqual(9, last["playwrightExitCode"])
        self.assertEqual(1, last["exitCode"])
        self.assertTrue((self.phase_dir(self.smoke, "game") / "results.json").exists())
        self.assertTrue((self.phase_dir(self.topdown, "game") / "playwright-output/trace.zip").exists())

    def test_no_topdown_specs_is_explicitly_unexecuted(self) -> None:
        result = self.shell(self.topdown, "game")
        self.assertEqual(0, result.returncode, result.stdout)
        record = self.finalize("topdown-screens", "game", "success")
        self.assertEqual("NO_TOPDOWN_SPECS", record["testState"])
        self.assertIs(False, record["playwrightInvoked"])
        self.assertIsNone(record["playwrightExitCode"])
        self.assertFalse((self.phase_dir(self.topdown, "game") / "results.json").exists())

    def test_pre_playwright_build_and_typecheck_failures_keep_original_exit(self) -> None:
        self.topdown_specs()
        for step, app, failure in ((self.topdown, "game", {"TEST_BUILD_EXIT": "37"}),
                                   (self.smoke, "gateway", {"TEST_TYPECHECK_EXIT": "42"}),
                                   (self.smoke, "game", {"TEST_INSTALL_EXIT": "43"})):
            with self.subTest(app=app, failure=failure):
                result = self.shell(step, app, **failure)
                self.assertEqual(int(next(iter(failure.values()))), result.returncode, result.stdout)
                record = self.finalize(step["env"]["E2E_PHASE"], app, "failure")
                self.assertEqual(result.returncode, record["exitCode"])
                self.assertEqual("PLAYWRIGHT_NOT_STARTED", record["testState"])
                self.assertIs(False, record["playwrightInvoked"])
                self.assertIsNone(record["playwrightExitCode"])

    def test_gateway_preserves_native_list_output_without_inventing_json(self) -> None:
        result = self.shell(self.smoke, "gateway")
        self.assertEqual(0, result.returncode, result.stdout)
        record = self.finalize("smoke", "gateway", "success")
        self.assertEqual("gateway", record["app"])
        self.assertIs(True, record["playwrightInvoked"])
        directory = self.phase_dir(self.smoke, "gateway")
        self.assertIn("playwright:smoke:exit=0", (directory / "playwright.log").read_text())
        self.assertFalse((directory / "results.json").exists())

    def test_cancellation_is_recorded_without_becoming_success(self) -> None:
        result = self.shell(self.smoke, "game", TEST_TERMINATE="1")
        self.assertEqual(143, result.returncode, result.stdout)
        record = self.finalize("smoke", "game", "cancelled")
        self.assertEqual(143, record["exitCode"])
        self.assertEqual("cancelled", record["workflowStepOutcome"])

    def test_missing_initial_metadata_is_an_evidence_failure(self) -> None:
        result = self.shell(self.by_name["Finalize smoke e2e metadata"], "game", E2E_STEP_OUTCOME="cancelled")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.phase_dir(self.smoke, "game") / "phase.json").exists())


if __name__ == "__main__":
    unittest.main()
