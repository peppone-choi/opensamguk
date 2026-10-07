import copy
import json
import tempfile
import unittest
from pathlib import Path

from check_web_shards import check


class WebShardsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.args = dict(app="game", count=4, run_id="123", attempt="2", head="a" * 40,
                         workflow_sha="b" * 40, output=self.root / "out")
        self.paths = []
        for phase in ("smoke", "topdown-screens"):
            inventory = self.report(range(1, 5), executed=False)
            for index in range(1, 5):
                path = self.root / "in" / f"web-game-{phase}-e2e-shard-{index}-of-4-attempt-2"
                path.mkdir(parents=True)
                record = {"schema": "web-e2e-phase-v1", "app": "game", "phase": phase,
                          "shardIndex": index, "shardCount": 4, "runId": "123",
                          "runAttempt": "2", "headSha": "a" * 40, "workflowSha": "b" * 40,
                          "recordState": "FINISHED", "exitCode": 0,
                          "workflowStepOutcome": "success", "testState": "PLAYWRIGHT_FINISHED",
                          "playwrightInvoked": True, "playwrightExitCode": 0,
                          "startedAt": "2026-10-03T07:00:00Z", "finishedAt": "2026-10-03T07:01:00Z"}
                self.write(path / "phase.json", record)
                self.write(path / "expected.json", inventory)
                self.write(path / "results.json", self.report([index]))
                self.paths.append(path)

    def report(self, indices, executed=True):
        specs = []
        for index in indices:
            specs.append({"id": f"test{index}", "title": f"test {index}", "file": "smoke.spec.ts",
                          "line": index, "column": 1, "tests": [
                              {"projectName": project, "status": "expected",
                               "results": [{"status": "passed", "retry": 0}] if executed else []}
                              for project in ("desktop", "mobile")]})
        return {"suites": [{"specs": specs, "suites": []}], "errors": []}

    def write(self, path, value):
        path.write_text(json.dumps(value))

    def alter(self, name, update):
        path = self.paths[0] / name
        value = json.loads(path.read_text())
        update(value)
        self.write(path, value)

    def run_check(self):
        return check(self.root / "in", **self.args)

    def move_shard_attempt(self, index, attempt):
        for path in list(self.paths):
            if f"-shard-{index}-of-4-" not in path.name:
                continue
            destination = path.with_name(path.name.rsplit("-attempt-", 1)[0] + f"-attempt-{attempt}")
            path.rename(destination)
            record = json.loads((destination / "phase.json").read_text())
            record["runAttempt"] = str(attempt)
            self.write(destination / "phase.json", record)
            self.paths[self.paths.index(path)] = destination

    def test_complete_desktop_mobile_inventory_and_original_receipts_preserved(self):
        before = [p.joinpath("phase.json").read_bytes() for p in self.paths]
        summary = self.run_check()
        self.assertEqual(8, summary["phases"]["smoke"]["testCount"])
        receipt = json.loads((self.args["output"] / "smoke/phase.json").read_text())
        self.assertEqual(4, len(receipt["shardReceipts"]))
        self.assertEqual("web-e2e-shard-aggregate-v1", receipt["schema"])
        self.assertEqual(before, [p.joinpath("phase.json").read_bytes() for p in self.paths])

    def test_missing_shard_and_missing_phase_are_rejected(self):
        self.paths[0].joinpath("phase.json").unlink()
        with self.assertRaisesRegex(ValueError, "missing evidence"):
            self.run_check()

    def test_project_only_shard_may_have_different_native_spec_id(self):
        # Playwright v1.52 JSON reporter's spec.id comes from the first project
        # present in that shard. It is not a cross-project source identity.
        self.alter("results.json", lambda d: d["suites"][0]["specs"][0].update(id="mobile-only-native-id"))
        self.assertEqual(8, self.run_check()["phases"]["smoke"]["testCount"])

    def test_original_suite_hierarchy_survives_aggregation(self):
        for path in self.paths:
            for name in ("expected.json", "results.json"):
                value = json.loads(path.joinpath(name).read_text())
                value["suites"] = [{"title": "file", "suites": [
                    {"title": "input flow", **value["suites"][0]}]}]
                self.write(path / name, value)
        self.run_check()
        merged = json.loads((self.args["output"] / "smoke/results.json").read_text())
        self.assertEqual("input flow", merged["suites"][0]["suites"][0]["title"])
        self.assertEqual(2, len(merged["suites"][0]["suites"][0]["specs"][0]["tests"]))

    def test_same_source_in_distinct_describe_suites_keeps_every_test(self):
        # Parameterized policy suites share source locations/titles. Full describe
        # paths distinguish them; keep both projects and every invocation.
        for path in self.paths:
            for name in ("expected.json", "results.json"):
                value = json.loads(path.joinpath(name).read_text())
                original = value["suites"][0]
                value["suites"] = [{"title": "policy.spec.ts", "suites": [
                    {"title": policy, **copy.deepcopy(original)}
                    for policy in ("privacy", "terms")]}]
                self.write(path / name, value)
        self.assertEqual(16, self.run_check()["phases"]["smoke"]["testCount"])
        report = json.loads((self.args["output"] / "smoke/results.json").read_text())
        self.assertEqual(["privacy", "terms"], [s["title"] for s in report["suites"][0]["suites"]])

    def test_renamed_describe_suite_is_not_the_collected_test(self):
        for path in self.paths:
            for name in ("expected.json", "results.json"):
                value = json.loads(path.joinpath(name).read_text())
                value["suites"] = [{"title": "policy.spec.ts", "suites": [
                    {"title": "privacy", **value["suites"][0]}]}]
                self.write(path / name, value)
        self.alter("results.json", lambda d: d["suites"][0]["suites"][0].update(title="terms"))
        with self.assertRaisesRegex(ValueError, "source identity"):
            self.run_check()

    def test_duplicate_shard_receipt_is_rejected(self):
        extra = self.root / "in/duplicate"
        extra.mkdir()
        extra.joinpath("phase.json").write_bytes(self.paths[0].joinpath("phase.json").read_bytes())
        with self.assertRaisesRegex(ValueError, "unexpected browser shard artifact"):
            self.run_check()

    def test_failed_only_rerun_keeps_older_successful_shard_pair(self):
        self.move_shard_attempt(3, 1)
        summary = self.run_check()
        self.assertEqual("2", summary["runAttempt"])
        for phase in ("smoke", "topdown-screens"):
            aggregate = json.loads((self.args["output"] / phase / "phase.json").read_text())
            self.assertEqual(["2", "2", "1", "2"],
                             [item["runAttempt"] for item in aggregate["shardReceipts"]])

    def test_newer_failed_skipped_or_incomplete_pair_never_uses_old_success(self):
        self.move_shard_attempt(3, 1)
        old = self.root / "in/web-game-smoke-e2e-shard-3-of-4-attempt-1"
        latest = old.with_name(old.name.replace("attempt-1", "attempt-2"))
        latest.mkdir()
        with self.assertRaisesRegex(ValueError, "missing phase"):
            self.run_check()
        topdown = self.root / "in/web-game-topdown-screens-e2e-shard-3-of-4-attempt-2"
        topdown.mkdir()
        with self.assertRaisesRegex(ValueError, "missing evidence"):
            self.run_check()
        for phase, directory in (("smoke", latest), ("topdown-screens", topdown)):
            source = directory.with_name(directory.name.replace("attempt-2", "attempt-1"))
            for name in ("phase.json", "expected.json", "results.json"):
                directory.joinpath(name).write_bytes(source.joinpath(name).read_bytes())
            record = json.loads((directory / "phase.json").read_text())
            record["runAttempt"] = "2"
            self.write(directory / "phase.json", record)
        for outcome in ("failure", "skipped"):
            record = json.loads((latest / "phase.json").read_text())
            record["workflowStepOutcome"] = outcome
            self.write(latest / "phase.json", record)
            with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, "outcome differs"):
                self.run_check()

    def test_latest_foreign_run_head_or_future_attempt_is_rejected(self):
        self.move_shard_attempt(3, 1)
        for key, wrong in (("runId", "other"), ("headSha", "c" * 40)):
            path = self.root / "in/web-game-smoke-e2e-shard-3-of-4-attempt-1/phase.json"
            original = path.read_bytes()
            changed = json.loads(original)
            changed[key] = wrong
            self.write(path, changed)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "identity or outcome differs"):
                self.run_check()
            path.write_bytes(original)
        self.move_shard_attempt(3, 3)
        with self.assertRaisesRegex(ValueError, "future attempt"):
            self.run_check()

    def test_gateway_single_shard_preserves_its_complete_pair(self):
        self.move_shard_attempt(1, 1)
        for path in list(self.paths):
            if "-shard-1-of-4-" not in path.name:
                for child in path.iterdir():
                    child.unlink()
                path.rmdir()
                continue
            destination = path.with_name(path.name.replace("web-game-", "web-gateway-").replace("-of-4-", "-of-1-"))
            path.rename(destination)
            record = json.loads((destination / "phase.json").read_text())
            record.update(app="gateway", shardCount=1)
            self.write(destination / "phase.json", record)
            self.write(destination / "expected.json", self.report([1], executed=False))
        self.args.update(app="gateway", count=1)
        summary = self.run_check()
        self.assertEqual(2, summary["phases"]["smoke"]["testCount"])
        self.assertEqual("1", json.loads((self.args["output"] / "smoke/phase.json").read_text())
                         ["shardReceipts"][0]["runAttempt"])

    def test_wrong_execution_identity_and_unfinished_receipts_are_rejected(self):
        path = self.paths[0] / "phase.json"
        original = json.loads(path.read_text())
        for key, wrong in {"app": "gateway", "runId": "other", "runAttempt": "1",
                           "headSha": "c" * 40, "workflowSha": "d" * 40, "shardCount": 3,
                           "shardIndex": True, "recordState": "RUNNING", "exitCode": False,
                           "workflowStepOutcome": "skipped", "testState": "PLAYWRIGHT_NOT_STARTED",
                           "playwrightInvoked": False, "playwrightExitCode": None}.items():
            with self.subTest(key=key):
                value = dict(original, **{key: wrong})
                self.write(path, value)
                with self.assertRaises(ValueError):
                    self.run_check()
        self.write(path, original)

    def test_different_full_inventory_is_rejected(self):
        self.alter("expected.json", lambda d: d["suites"][0]["specs"].pop())
        with self.assertRaisesRegex(ValueError, "inventory"):
            self.run_check()

    def test_missing_and_duplicate_executed_test_are_rejected(self):
        path = self.paths[0] / "results.json"
        self.write(path, self.report([]))
        with self.assertRaisesRegex(ValueError, "missing tests"):
            self.run_check()
        self.write(path, self.report([1, 2]))
        with self.assertRaisesRegex(ValueError, "duplicate or unexpected"):
            self.run_check()

    def test_mobile_omission_is_rejected(self):
        self.alter("results.json", lambda d: d["suites"][0]["specs"][0]["tests"].pop())
        with self.assertRaisesRegex(ValueError, "missing tests"):
            self.run_check()

    def test_fail_skip_unexecuted_and_report_errors_are_rejected(self):
        path = self.paths[0] / "results.json"
        original = json.loads(path.read_text())
        for failure in ("failed", "skipped", "timedOut", "interrupted"):
            with self.subTest(failure=failure):
                value = copy.deepcopy(original)
                value["suites"][0]["specs"][0]["tests"][0]["results"][0]["status"] = failure
                self.write(path, value)
                with self.assertRaises(ValueError):
                    self.run_check()
        self.write(path, dict(original, errors=[{"message": "collection error"}]))
        with self.assertRaisesRegex(ValueError, "errors"):
            self.run_check()

    def test_zero_smoke_tests_are_rejected(self):
        for path in self.paths[:4]:
            self.write(path / "expected.json", self.report([]))
            self.write(path / "results.json", self.report([]))
        with self.assertRaisesRegex(ValueError, "empty browser smoke"):
            self.run_check()

    def test_empty_individual_shard_does_not_drop_inventory(self):
        self.write(self.paths[0] / "results.json", self.report([]))
        self.write(self.paths[1] / "results.json", self.report([1, 2]))
        self.assertEqual(8, self.run_check()["phases"]["smoke"]["testCount"])

    def test_changed_source_location_is_rejected(self):
        self.alter("results.json", lambda d: d["suites"][0]["specs"][0].update(line=999))
        with self.assertRaisesRegex(ValueError, "source identity"):
            self.run_check()

    def test_cancelled_receipt_cannot_become_pass(self):
        self.alter("phase.json", lambda d: d.update(workflowStepOutcome="cancelled", exitCode=143))
        with self.assertRaises(ValueError):
            self.run_check()


if __name__ == "__main__":
    unittest.main()
