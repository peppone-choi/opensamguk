"""CI trust and lane-addition contracts; no live tracker credentials."""
import importlib.util
import unittest
import argparse
import json
import shutil
import subprocess
import tempfile
import sys
from pathlib import Path

PATH = Path(__file__).with_name("work_unit_gate.py")
SPEC = importlib.util.spec_from_file_location("work_unit_gate", PATH)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class WorkUnitGateTest(unittest.TestCase):
    def test_lane_union_never_removes_existing_selection(self):
        self.assertEqual(gate.union_lanes({"jvm": True, "web": False, "contracts": False},
                                         {"jvm": False, "web": True}),
                         {"jvm": True, "web": True, "contracts": False})

    def test_bootstrap_is_explicit_and_not_enforced(self):
        receipt = gate.bootstrap_receipt("a" * 40, "b" * 40, stale=False)
        self.assertEqual(receipt["status"], "BOOTSTRAP_NOT_ENFORCED")
        self.assertEqual(receipt["reservation"], "UNVERIFIED_IN_CI")

    def test_stale_base_is_reported(self):
        self.assertIn("STALE_BASE_REBASE_REQUIRED",
                      gate.bootstrap_receipt("a" * 40, "b" * 40, stale=True)["reasons"])

    def test_workflow_uses_or_and_does_not_expand_permissions(self):
        text = (PATH.parents[2] / ".github/workflows/ci.yml").read_text()
        for lane in ("jvm", "web", "contracts"):
            self.assertIn(f"steps.filter.outputs.{lane} == 'true' || steps.wu_lanes.outputs.{lane} == 'true'", text)
        self.assertNotIn("issues: write", text)
        self.assertNotIn("pull-requests: write", text)
        self.assertIn("types: [opened, synchronize, reopened, edited, ready_for_review]", text)
        self.assertIn("Validate trusted-base work-unit structure (no CI self-wait)", text)
        self.assertIn("Verify required work-unit JVM execution", text)
        self.assertIn("Verify required work-unit game browser execution", text)

    def test_existing_no_skip_and_original_browser_gates_remain(self):
        text = (PATH.parents[2] / ".github/workflows/ci.yml").read_text()
        self.assertIn("check_test_xml.py common logic", text)
        self.assertIn("check_test_xml.py app/game-engine", text)
        self.assertLess(text.index("check_web_shards.py web-shard-evidence"),
                        text.index("Record original game browser work-unit execution"))
        self.assertLess(text.index("input_evidence_gate.py --ui-shards"),
                        text.index("Verify required work-unit game browser execution"))


class TrustedBaseIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.call("init", "-q", "-b", "main")
        self.call("config", "user.name", "Fixture")
        self.call("config", "user.email", "fixture@example.invalid")
        self.root = PATH.parents[2]

    def call(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def commit(self):
        self.call("add", ".")
        self.call("commit", "-qm", "fixture")
        return self.call("rev-parse", "HEAD")

    def install(self):
        for directory in ("tools/pr-loop/lib", "work-units", "data/commands"):
            shutil.copytree(self.root / directory, self.repo / directory, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("__pycache__"))
        (self.repo / "tools/ci").mkdir(parents=True, exist_ok=True)
        shutil.copy2(PATH, self.repo / gate.GATE)

    def args(self, base, head):
        return argparse.Namespace(repo=str(self.repo), base=base, head=head, branch="feature",
                                  github_event=None, command="check", paths=[], lane="all",
                                  run_id="local", attempt=1, input=None, browser_root=None)

    def test_bootstrap_never_runs_candidate_gate(self):
        (self.repo / "README.md").write_text("initial")
        base = self.commit()
        self.install()
        (self.repo / gate.GATE).write_text("raise RuntimeError('candidate must never run')")
        head = self.commit()
        result = gate.trusted_base(self.args(base, head))
        self.assertEqual(result["status"], "BOOTSTRAP_NOT_ENFORCED")

    def test_stale_base_with_gate_on_current_main(self):
        (self.repo / "README.md").write_text("initial")
        base = self.commit()
        self.install()
        head = self.commit()
        self.call("update-ref", "refs/remotes/origin/main", head)
        self.assertIn("STALE_BASE_REBASE_REQUIRED", gate.trusted_base(self.args(base, head))["reasons"])

    def test_candidate_import_poison_does_not_run(self):
        self.install()
        base = self.commit()
        sentinel = self.repo / "poison-ran"
        (self.repo / "tools/pr-loop/lib/work_units/__init__.py").write_text(
            "from pathlib import Path\nPath(" + repr(str(sentinel)) + ").write_text('unsafe')\nraise RuntimeError('unsafe')")
        (self.repo / "docs").mkdir()
        (self.repo / "docs/example.md").write_text("docs")
        head = self.commit()
        result = gate.trusted_base(self.args(base, head))
        self.assertEqual(result["status"], "REPORT_ONLY")
        self.assertFalse(sentinel.exists())
        self.assertTrue(result["impact"]["gateChanged"])

    def test_symlink_candidate_data_is_rejected_not_followed(self):
        self.install()
        base = self.commit()
        path = self.repo / "work-units/units/escape.json"
        path.symlink_to("/etc/passwd")
        head = self.commit()
        result = gate.trusted_base(self.args(base, head))
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertIn("UNSAFE_HEAD_OBJECT", result["reasons"])

    def test_enforce_uses_base_binding_even_when_head_reports(self):
        self.install()
        policy_path = self.repo / "work-units/binding.json"
        policy = json.loads(policy_path.read_text())
        policy["mode"] = "enforce"
        policy_path.write_text(json.dumps(policy))
        base = self.commit()
        policy["mode"] = "report"
        policy_path.write_text(json.dumps(policy))
        head = self.commit()
        result = gate.trusted_base(self.args(base, head))
        self.assertEqual(result["status"], "ENFORCED")
        self.assertFalse(result["valid"])
