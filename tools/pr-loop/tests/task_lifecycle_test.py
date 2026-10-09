"""Real Git worktree safety regressions for registered task retirement."""
import importlib.machinery
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unicodedata
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader("task_lifecycle", str(ROOT / "bin/task-lifecycle"))
spec = importlib.util.spec_from_loader(loader.name, loader)
life = importlib.util.module_from_spec(spec)
loader.exec_module(life)


class TaskLifecycleTest(unittest.TestCase):
    def set_policy(self, mode="report"):
        import json
        path = self.repo / "work-units/binding.json"
        path.parent.mkdir()
        path.write_text(json.dumps({"schemaVersion": 1, "mode": mode, "repo": "example/sample",
                                    "jira": {"status": "UNBOUND"}, "explicitExemptions": [],
                                    "catalogAdapter": "opensamguk-input-catalog-v5"}))

    def issue(self, body=None, **kwargs):
        default = "<!-- work-unit-ac v1 -->\ninputs: court.reward\n- AC-1: ship\n<!-- /work-unit-ac -->"
        return dict({"number": 1, "state": "open", "body": default if body is None else body}, **kwargs)

    def test_enforce_missing_issue_rejects_actual_start_before_any_worktree_or_board(self):
        self.set_policy("enforce")
        bindir = self.meta / "bin"
        bindir.mkdir()
        shutil.copytree(ROOT / "lib", self.meta / "lib")
        for name in ("start-task", "task-lifecycle"):
            shutil.copy2(ROOT / "bin" / name, bindir / name)
        env = dict(os.environ, OPENSAMGUK_META_ROOT=str(self.meta), PR_LOOP_STATE=str(self.state))
        before = self.board.read_bytes()
        done = subprocess.run([str(bindir / "start-task"), "sample", "task-one"], env=env,
                              capture_output=True, text=True)
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("ISSUE_REQUIRED", done.stderr)
        self.assertFalse(self.tree.exists())
        self.assertFalse(self.branch_exists())
        self.assertFalse(life.registry_path(self.project, self.task).exists())
        self.assertEqual(self.board.read_bytes(), before)

    def test_v2_issue_registration_has_atomic_lease_and_ac(self):
        self.set_policy()
        with patch.object(life, "api", return_value=self.issue()):
            life.reserve(self.project, self.task, "writer-1", "example/sample#1")
        data = life.load(life.registry_path(self.project, self.task))
        self.assertEqual(data["version"], 2)
        self.assertEqual(data["criteria"], ["AC-1"])
        self.assertEqual(data["lease"]["issues"], ["example/sample#1"])
        self.assertFalse(self.tree.exists())

    def test_missing_ac_closed_dependency_and_auth_fail_before_registration(self):
        self.set_policy()
        for issue in [self.issue("old prose"), self.issue(state="closed"),
                      self.issue("<!-- work-unit-ac v1 -->\ndepends: #2\n- AC-1: ship\n<!-- /work-unit-ac -->")]:
            with self.subTest(issue=issue), patch.object(life, "api", return_value=issue):
                with self.assertRaises(ValueError):
                    life.reserve(self.project, self.task, "writer-1", "example/sample#1")
                self.assertFalse(life.registry_path(self.project, self.task).exists())
        with patch.object(life, "api", side_effect=ValueError("UNKNOWN_AUTH")):
            with self.assertRaisesRegex(ValueError, "UNKNOWN_AUTH"):
                life.reserve(self.project, self.task, "writer-1", "example/sample#1")

    def test_explicit_legacy_registration_and_probe_do_not_mutate(self):
        self.set_policy()
        with patch.object(life, "api", return_value=self.issue("legacy body")):
            life.reserve(self.project, self.task, "writer-1", "example/sample#1", legacy_ac=True, dry_run=True)
            self.assertFalse(self.state.exists())
            life.reserve(self.project, self.task, "writer-1", "example/sample#1", legacy_ac=True)
        data = life.load(life.registry_path(self.project, self.task))
        self.assertTrue(data["legacyAc"])
        self.assertEqual(data["criteria"], ["LEGACY-WHOLE"])

    def test_v2_audit_failure_prevents_real_cleanup(self):
        record = self.register()
        data = life.load(record)
        data.update(version=2, unitId=self.task, lease={}, issue="example/sample#1",
                    acFingerprint="sha256:" + "a" * 64)
        life.save(record, data)
        self.set_policy('enforce')
        self.merged()
        with patch.object(life.completion, "host_verify", side_effect=ValueError("AUDIT_FAILED")), \
             patch("work_units.cli.independent_review", return_value={}):
            self.tick()
        self.assertTrue(self.tree.exists())
        self.assertTrue(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "active")

    def test_report_host_failure_retires_real_worktree_releases_lease_without_outbox(self):
        record = self.register()
        self.set_policy('report')
        data = life.load(record)
        lease = life.claim.acquire(self.state, self.task, data['nonce'],
                                   {'issues': ['example/sample#1'], 'inputs': [], 'scopes': ['ALL_INPUTS']})
        data.update(version=2, unitId=self.task, lease=lease, issue='example/sample#1',
                    acFingerprint='sha256:' + 'a' * 64)
        life.save(record, data)
        self.merged()
        with patch.object(life.completion, 'host_verify', return_value=({'result': 'FAIL'}, {})), \
             patch('work_units.cli.independent_review', return_value={}), \
             patch.object(life.completion, 'record') as audit:
            self.tick()
            audit.assert_not_called()
        self.assertFalse(self.tree.exists())
        self.assertFalse(self.branch_exists())
        self.assertEqual(life.load(record)['phase'], 'done')
        self.assertEqual(life.claim.active_leases(self.state), [])
        self.assertEqual(life.completion.records(self.state, 'audits'), [])
        self.assertEqual(life.completion.records(self.state, 'outbox'), [])
        report = life.completion.records(self.state, 'noncompletion')
        self.assertEqual(len(report), 1)
        self.assertFalse(report[0]['automaticCompletion'])

    def test_torn_audit_and_scan_failure_do_not_stop_v1_cleanup(self):
        record = self.register()
        torn = self.state / 'work-units/audits' / ('0' * 64 + '.json')
        torn.parent.mkdir(parents=True)
        torn.write_bytes(b'')
        self.merged()
        self.tick()
        self.assertEqual(life.load(record)['phase'], 'done')
        self.assertFalse(self.tree.exists())
        self.assertFalse(torn.exists())

    def test_enforce_host_failure_retains_worktree_and_lease(self):
        record = self.register()
        self.set_policy('enforce')
        data = life.load(record)
        lease = life.claim.acquire(self.state, self.task, data['nonce'],
                                   {'issues': ['example/sample#1'], 'inputs': [], 'scopes': ['ALL_INPUTS']})
        data.update(version=2, unitId=self.task, lease=lease, issue='example/sample#1',
                    acFingerprint='sha256:' + 'a' * 64)
        life.save(record, data)
        self.merged()
        with patch.object(life.completion, 'host_verify', return_value=({'result': 'FAIL'}, {})), \
             patch('work_units.cli.independent_review', return_value={}):
            self.tick()
        self.assertTrue(self.tree.exists())
        self.assertEqual(life.load(record)['phase'], 'active')
        self.assertEqual(len(life.claim.active_leases(self.state)), 1)
        self.assertEqual(life.completion.records(self.state, 'noncompletion'), [])

    def test_independent_scan_exception_does_not_stop_v1_cleanup(self):
        record = self.register()
        self.merged()
        with patch.object(life.completion, 'scan', side_effect=OSError('scan unavailable')):
            self.tick()
        self.assertEqual(life.load(record)['phase'], 'done')
        self.assertFalse(self.tree.exists())

    def test_cleanup_keeps_durable_outbox(self):
        record = self.register()
        outbox = self.state / "work-units/outbox/example.json"
        outbox.parent.mkdir(parents=True)
        outbox.write_text('{"state":"PENDING","intentId":"external","auditId":"unrelated","target":"example/sample#1","action":"comment"}')
        self.merged()
        self.tick()
        self.assertTrue(outbox.exists())
        self.assertEqual(life.load(record)["phase"], "done")

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.meta = Path(temp.name) / unicodedata.normalize("NFD", "méta")
        self.meta.mkdir()
        self.project = "sample"
        self.task = "task-one"
        self.repo = self.meta / "projects/sample"
        self.tree = self.meta / "worktrees/sample/task-one"
        self.state = self.meta / "state"
        self.board = self.meta / "docs/coord/BOARD.md"
        self.board.parent.mkdir(parents=True)
        self.board.write_text("# BOARD\n\n## 진행\n| 다른 레인 | 유지 | PR #77 | 보존 |\n")
        self.repo.mkdir(parents=True)
        self.tree.parent.mkdir(parents=True)
        self.call("git", "init", "-q", "-b", "main", str(self.repo))
        self.call("git", "-C", str(self.repo), "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                  "commit", "-q", "--allow-empty", "-m", "initial")
        self.call("git", "-C", str(self.repo), "remote", "add", "origin", "git@github.com:example/sample.git")
        self.head = self.call("git", "-C", str(self.repo), "rev-parse", "HEAD")
        for attr, value in (("META", self.meta), ("STATE", self.state)):
            replacing = patch.object(life, attr, value)
            replacing.start()
            self.addCleanup(replacing.stop)
        self.raw = {"number": 77, "state": "open", "merged": False, "merged_at": None, "title": "Safely retire task worktrees",
                    "body": "<!-- pr-loop v2 author-session id=writer-1 -->\n교훈: 작업 종료는 실제 병합 확인 뒤 수행한다.",
                    "head": {"sha": self.head, "ref": "work/sample/task-one", "repo": {"full_name": "example/sample"}}}

    def call(self, *argv):
        return subprocess.check_output(argv, text=True).strip()

    def github(self, path):
        if "pulls?" in path:
            return [{"number": 77}]
        if path.endswith("/pulls/77"):
            return self.raw
        if path.endswith("/files?per_page=100"):
            return [{"filename": "tools/pr-loop/bin/task-lifecycle"}]
        raise AssertionError(path)

    def register(self):
        life.reserve(self.project, self.task, "writer-1")
        self.call("git", "-C", str(self.repo), "worktree", "add", "-q", "-b", "work/sample/task-one", str(self.tree), "main")
        life.activate(self.project, self.task)
        return life.registry_path(self.project, self.task)

    def tick(self):
        with patch.object(life, "api", side_effect=self.github):
            life.tick()

    def merged(self):
        self.raw.update(state="closed", merged=True, merged_at="2026-10-08T00:00:00Z")

    def branch_exists(self):
        return subprocess.run(("git", "-C", str(self.repo), "show-ref", "--verify", "--quiet",
                               "refs/heads/work/sample/task-one")).returncode == 0

    def test_waits_for_actual_merge_then_saves_lesson_before_real_remove(self):
        record = self.register()
        self.tick()
        self.assertTrue(self.tree.exists())
        self.merged()
        original = life.git

        def inspect_remove(repo, *args):
            if args[:2] == ("worktree", "remove"):
                notes = list((self.meta / "reports/sample/lessons").glob("*.md"))
                self.assertEqual(len(notes), 1)
                self.assertIn("작업 종료는 실제 병합", notes[0].read_text())
                self.assertEqual(life.load(record)["phase"], "prepared")
            return original(repo, *args)

        with patch.object(life, "git", side_effect=inspect_remove):
            self.tick()
        self.assertFalse(self.tree.exists())
        self.assertFalse(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "done")
        self.assertNotIn("task-lifecycle v1", self.board.read_text())
        self.assertIn("| 다른 레인 | 유지 | PR #77 | 보존 |", self.board.read_text())
        self.tick()  # idempotent: no second lesson or removal
        self.assertEqual(len(list((self.meta / "reports/sample/lessons").glob("*.md"))), 1)

    def test_remove_succeeds_then_process_failure_resumes_without_force(self):
        record = self.register()
        self.merged()
        original = life.git
        tripped = False

        def crash_after_remove(repo, *args):
            nonlocal tripped
            result = original(repo, *args)
            if args[:2] == ("worktree", "remove") and not tripped:
                tripped = True
                raise ValueError("simulated interruption after real worktree removal")
            return result

        with patch.object(life, "git", side_effect=crash_after_remove), patch.object(life, "api", side_effect=self.github):
            life.tick()
        self.assertFalse(self.tree.exists())
        self.assertTrue(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "prepared")
        self.tick()
        self.assertFalse(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "done")
        self.assertNotIn("task-lifecycle v1", self.board.read_text())

    def test_dirty_lock_mismatch_and_unregistered_are_retained(self):
        self.merged()
        with self.assertRaisesRegex(ValueError, "unregistered"):
            life.tick((self.project, self.task))
        self.assertFalse(self.tree.exists())
        record = self.register()
        (self.tree / "untracked.txt").write_text("WIP")
        self.tick()
        self.assertTrue(self.tree.exists())
        self.assertEqual(life.load(record)["phase"], "active")
        (self.tree / "untracked.txt").unlink()
        (self.repo / ".git/info/exclude").write_text("local-output\n")
        (self.tree / "local-output").write_text("ignored WIP")
        self.tick()
        self.assertTrue(self.tree.exists())
        (self.tree / "local-output").unlink()
        lock = self.state / "locks/example_sample-77.json"
        lock.parent.mkdir(parents=True)
        lock.write_text("{}")
        self.tick()
        self.assertTrue(self.tree.exists())
        lock.unlink()
        self.raw["body"] = "<!-- pr-loop v2 author-session id=someone-else -->"
        self.tick()
        self.assertTrue(self.tree.exists())
        self.assertTrue(self.branch_exists())

    def test_extra_local_commit_and_foreign_branch_are_retained(self):
        self.register()
        self.merged()
        self.call("git", "-C", str(self.tree), "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                  "commit", "-q", "--allow-empty", "-m", "unmerged extra")
        self.tick()
        self.assertTrue(self.tree.exists())
        self.assertTrue(self.branch_exists())

    def test_installed_start_task_registers_once_and_manual_finish_cannot_bypass_merge(self):
        shutil.copytree(ROOT / "lib", self.meta / "lib")
        bindir = self.meta / "bin"
        bindir.mkdir()
        for name in ("start-task", "finish-task", "task-lifecycle"):
            shutil.copy2(ROOT / "bin" / name, bindir / name)
        (bindir / "pull-references").write_text("#!/bin/sh\nexit 0\n")
        (bindir / "pull-references").chmod(0o755)
        graph = self.meta / "graphs/sample/graphify-out/graph.json"
        graph.parent.mkdir(parents=True)
        graph.write_text("{}")
        fakebin = self.meta / "fake-bin"
        fakebin.mkdir()
        (fakebin / "graphify").write_text("#!/bin/sh\nexit 0\n")
        (fakebin / "graphify").chmod(0o755)
        env = dict(os.environ, OPENSAMGUK_META_ROOT=str(self.meta), PR_LOOP_STATE=str(self.state),
                   CODEX_THREAD_ID="writer-1", PATH=f"{fakebin}:{os.environ['PATH']}")
        env.pop("OPENSAMGUK_TASK_OWNER_SESSION", None)
        start = subprocess.run((str(bindir / "start-task"), "sample", "task-one", "main"), env=env,
                               capture_output=True, text=True)
        self.assertEqual(start.returncode, 0, start.stderr)
        record = life.registry_path(self.project, self.task)
        self.assertEqual(life.load(record)["owner_session"], "writer-1")
        nonce = life.load(record)["nonce"]
        reused = subprocess.run((str(bindir / "start-task"), "sample", "task-one", "main"), env=env,
                                capture_output=True, text=True)
        self.assertEqual(reused.returncode, 0, reused.stderr)
        self.assertEqual(life.load(record)["nonce"], nonce)
        finish = subprocess.run((str(bindir / "finish-task"), "sample", "task-one"), env=env,
                                capture_output=True, text=True)
        self.assertNotEqual(finish.returncode, 0)
        self.assertTrue(self.tree.exists())
        self.assertTrue(self.branch_exists())

    def test_pending_creation_resumes_without_claiming_legacy_worktree(self):
        life.reserve(self.project, self.task, "writer-1")
        record = life.registry_path(self.project, self.task)
        nonce = life.load(record)["nonce"]
        life.reserve(self.project, self.task, "writer-1")
        self.assertEqual(life.load(record)["nonce"], nonce)
        self.call("git", "-C", str(self.repo), "worktree", "add", "-q", "-b", "work/sample/task-one", str(self.tree), "main")
        life.activate(self.project, self.task, allow_absent=True)
        self.assertEqual(life.load(record)["phase"], "active")
        life.bind_board_pr(record, life.load(record), 77)
        life.activate(self.project, self.task, allow_absent=True)
        self.assertIn("pr=77", self.board.read_text())
        with self.assertRaisesRegex(ValueError, "already registered"):
            life.reserve(self.project, self.task, "someone-else")

    def test_factual_fallback_lesson_when_pr_has_no_lesson(self):
        self.register()
        self.merged()
        self.raw["body"] = "<!-- pr-loop v2 author-session id=writer-1 -->"
        self.tick()
        note = next((self.meta / "reports/sample/lessons").glob("*.md")).read_text()
        self.assertIn("Safely retire task worktrees", note)
        self.assertIn("tools/pr-loop/bin/task-lifecycle", note)
        self.assertIn("실제 병합 상태", note)

    def test_board_failure_after_ref_delete_resumes_without_touching_other_rows(self):
        record = self.register()
        self.merged()
        with patch.object(life, "api", side_effect=self.github), patch.object(
                life, "remove_board_row", side_effect=ValueError("simulated BOARD write failure")):
            life.tick()
        self.assertFalse(self.tree.exists())
        self.assertFalse(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "retired")
        self.assertIn("task-lifecycle v1", self.board.read_text())
        self.tick()
        self.assertEqual(life.load(record)["phase"], "done")
        self.assertNotIn("task-lifecycle v1", self.board.read_text())
        self.assertIn("| 다른 레인 | 유지 | PR #77 | 보존 |", self.board.read_text())

    def test_ambiguous_or_missing_board_owner_row_preserves_worktree(self):
        record = self.register()
        self.merged()
        own = next(line for line in self.board.read_text().splitlines() if "task-lifecycle v1" in line)
        self.board.write_text(self.board.read_text() + own + "\n")
        self.tick()
        self.assertTrue(self.tree.exists())
        self.assertTrue(self.branch_exists())
        self.assertEqual(life.load(record)["phase"], "active")
        self.board.write_text(self.board.read_text().replace(own + "\n", ""))
        self.tick()
        self.assertTrue(self.tree.exists())
        self.assertIn("| 다른 레인 | 유지 | PR #77 | 보존 |", self.board.read_text())

    def test_native_unicode_alias_uses_inode_identity_without_linux_name_folding(self):
        self.register()
        self.merged()
        alias = Path(unicodedata.normalize("NFC", str(self.meta)))
        if not life.same_existing_path(alias, self.meta):
            # On a filesystem with distinct Unicode names, the alias must
            # remain a different path even if its characters look alike.
            self.assertFalse(life.same_task_path(str(self.tree), alias / "worktrees/sample/task-one"))
            return
        with patch.object(life, "META", alias):
            self.assertTrue(life.same_task_path(str(self.tree), alias / "worktrees/sample/task-one"))
            self.tick()
        self.assertFalse(self.tree.exists())
        self.assertFalse(self.branch_exists())
        self.assertIn("| 다른 레인 | 유지 | PR #77 | 보존 |", self.board.read_text())


if __name__ == "__main__":
    unittest.main()
