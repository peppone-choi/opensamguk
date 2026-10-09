"""Core fast-loop gates: exact-head review, required CI and confirmed main RED."""
import importlib.machinery
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader("fast_pr_loop", str(ROOT / "bin/pr-loop"))
spec = importlib.util.spec_from_loader(loader.name, loader)
loop = importlib.util.module_from_spec(spec)
loader.exec_module(loop)
HEAD = "a" * 40
OLD = "b" * 40
RAW = {"number": 7, "state": "open", "draft": False, "head": {"sha": HEAD, "ref": "feature", "repo": {"full_name": loop.APP}},
       "base": {"ref": "main"}, "user": {"login": "owner"}, "mergeable_state": "clean", "labels": [], "body": ""}


class FastLoopTest(unittest.TestCase):
    def test_gate_surface_has_no_docs_or_generated_review_exemption(self):
        row = self.evaluate(["tools/pr-loop/lib/work_units/schema.py"])
        self.assertFalse(row["review_exempt"])
        self.assertEqual(row["action"], "independent-review")

    def test_enforced_host_failure_never_yields_merge_action(self):
        with tempfile.TemporaryDirectory() as temp:
            meta = Path(temp)
            policy = meta / "projects/opensamguk/work-units/binding.json"
            policy.parent.mkdir(parents=True)
            policy.write_text('{"mode":"enforce"}')
            with patch.object(loop, "META", meta), patch.object(loop, "STATE", meta / "state"), \
                 patch.object(loop, "host_verify", return_value=({"result": "FAIL", "mode": "enforce",
                     "reasons": ["REQUIRED_LANE_SKIPPED:jvm"], "libHash": "test"}, {})):
                row = self.evaluate(["server/handler.go"])
            self.assertEqual(row["action"], "wait-work-unit")

    def setUp(self):
        self.state_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.state_dir.cleanup)
        state = patch.object(loop, "STATE", Path(self.state_dir.name))
        state.start()
        self.addCleanup(state.stop)

    def run_record(self, **changes):
        run = {"id": 9, "run_number": 9, "run_attempt": 1, "workflow_id": loop.MAIN_CI_WORKFLOW,
               "head_branch": "main", "event": "push", "head_sha": HEAD,
               "status": "completed", "conclusion": "failure"}
        return dict(run, **changes)

    def observe(self, runs, head=HEAD, attempts=None, relations=None, fail_at=None, final_head=None):
        def response(path):
            if fail_at and fail_at in path:
                raise RuntimeError("GitHub unavailable")
            if path.endswith("branches/main"):
                nonlocal branch_reads
                branch_reads += 1
                return {"commit": {"sha": final_head if final_head and branch_reads == 2 else head}}
            if path.endswith(f"workflows/{loop.MAIN_CI_WORKFLOW}"):
                return {"id": loop.MAIN_CI_WORKFLOW, "path": ".github/workflows/ci.yml", "state": "active"}
            if "/attempts/" in path:
                run_id, attempt = path.split("/runs/")[1].split("/attempts/")
                return attempts[(int(run_id), int(attempt))]
            if "/compare/" in path:
                pair = tuple(path.split("/compare/")[1].split("..."))
                return {"status": (relations or {}).get(pair, "diverged")}
            return {"workflow_runs": runs}
        branch_reads = 0
        with patch.object(loop, "api", side_effect=response):
            return loop.main_ci()

    def evaluate(self, paths, bodies=(), ci="green", main=None):
        with patch.object(loop, "required_checks", return_value=["unit"]), \
             patch.object(loop, "checks_state", return_value=ci), \
             patch.object(loop, "changed_paths", return_value=paths), \
             patch.object(loop, "review_bodies", return_value=bodies):
            return loop.evaluate(loop.APP, 7, raw=RAW, login="owner", main=main)

    def test_current_head_review_once_and_old_head_ignored(self):
        marker = lambda sha: f"머지 판정: 머지 가능\n독립 리뷰어: Claude\n<!-- pr-loop v1 claude-verdict sha={sha} verdict=MERGEABLE -->"
        self.assertEqual(self.evaluate(["server/handler.go"])["action"], "independent-review")
        self.assertEqual(self.evaluate(["server/handler.go"], [("1", marker(OLD))])["action"], "independent-review")
        self.assertEqual(self.evaluate(["server/handler.go"], [("2", marker(HEAD))])["action"], "codex-merge")
        self.assertEqual(self.evaluate(["server/handler.go"], [("2", marker(HEAD))], ci="pending")["action"], "wait-ci")
        with patch.dict(RAW, mergeable_state="blocked"):
            self.assertEqual(self.evaluate(["server/handler.go"], [("2", marker(HEAD))])["action"], "codex-merge")

    def test_docs_test_generated_only_bypass_review(self):
        self.assertEqual(self.evaluate(["docs/guide.md", "tests/feature_test.py"])["action"], "codex-merge")
        self.assertEqual(self.evaluate(["docs/guide.md", "server/handler.go"])["action"], "independent-review")

    def test_both_agents_require_registered_separate_sessions(self):
        for agent in ("Claude", "Codex"):
            body = (f"머지 판정: 머지 가능\n독립 리뷰어: {agent}\n"
                    f"<!-- pr-loop v2 {agent.lower()}-verdict sha={HEAD} verdict=MERGEABLE "
                    "author-session=writer reviewer-session=reviewer -->")
            with patch.dict(RAW, body="<!-- pr-loop v2 author-session id=writer -->"):
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body)])["action"], "codex-merge")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body)], ci="pending")["action"], "wait-ci")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body)], ci="failed")["action"], "codex-fix")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body.replace(HEAD, OLD))])["action"], "independent-review")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body.replace("reviewer-session=reviewer", "reviewer-session=writer"))])["action"], "independent-review")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body.replace("author-session=writer", "author-session=unknown"))])["action"], "independent-review")
                blocked = body.replace("머지 가능", "불가").replace("MERGEABLE", "BLOCKED")
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", blocked)])["action"], "codex-fix")
            with patch.dict(RAW, body="<!-- pr-loop v2 author-session id=writer -->\n<!-- pr-loop v2 author-session id=reviewer -->"):
                self.assertEqual(self.evaluate(["server/handler.go"], [("2", body)])["action"], "independent-review")
            self.assertIsNone(loop.verdict_for_head([("2", body)], HEAD))

    def test_tool_identity_and_legacy_marker_cannot_bypass_session_gate(self):
        legacy = f"머지 판정: 머지 가능\n독립 리뷰어: Claude\n<!-- pr-loop v1 claude-verdict sha={HEAD} verdict=MERGEABLE -->"
        self.assertEqual(loop.verdict_for_head([("2", legacy)], HEAD), "MERGEABLE")
        self.assertIsNone(loop.verdict_for_head([("2", legacy)], HEAD, ["writer"]))
        self.assertIsNone(loop.verdict_for_head([("2", legacy.replace("독립 리뷰어: Claude", "독립 리뷰어: Codex"))], HEAD))
        body = (f"머지 판정: 머지 가능\n독립 리뷰어: Claude\n"
                f"<!-- pr-loop v2 codex-verdict sha={HEAD} verdict=MERGEABLE author-session=writer reviewer-session=reviewer -->")
        self.assertIsNone(loop.verdict_for_head([("2", body)], HEAD, ["writer"]))

    def test_marker_refuses_self_review_and_records_actual_agent(self):
        argv = ["marker", "verdict", "--sha", HEAD, "--verdict", "MERGEABLE", "--agent", "codex", "--author-session", "writer", "--reviewer-session"]
        self.assertEqual(loop.main(argv + ["writer"]), 2)
        self.assertEqual(loop.main(["marker", "verdict", "--sha", HEAD, "--verdict", "MERGEABLE", "--agent", "codex"]), 2)
        from io import StringIO
        stream = StringIO()
        with patch("sys.stdout", stream):
            self.assertEqual(loop.main(argv + ["reviewer"]), 0)
        self.assertEqual(loop.verdict_for_head([("1", "머지 판정: 머지 가능\n독립 리뷰어: Codex\n" + stream.getvalue())], HEAD, ["writer"]), "MERGEABLE")

    def test_context_requires_separate_review_and_immediate_pr(self):
        from io import StringIO
        stream = StringIO()
        with patch("sys.stdin", StringIO('{"cwd":"/opensamguk"}')), patch("sys.stdout", stream):
            self.assertEqual(loop.main(["context", "--agent", "codex"]), 0)
        for phrase in ("독립 리뷰", "Claude 또는 Codex", "자기 작성 PR의 자기 판정은 금지", "바로 PR"):
            self.assertIn(phrase, stream.getvalue())

    def test_required_check_fails_closed_without_latest_green(self):
        with patch.object(loop, "api", side_effect=[[{"check_runs": [{"id": 1, "name": "unit", "status": "completed", "conclusion": "failure"}]}],
                                                     {"statuses": []}]):
            self.assertEqual(loop.checks_state(loop.APP, HEAD, ["unit"]), "failed")
        with patch.object(loop, "api", side_effect=[[{"check_runs": [{"id": 1, "name": "unit", "status": "completed", "conclusion": "cancelled"},
                                                                    {"id": 2, "name": "unit", "status": "completed", "conclusion": "success"}]}],
                                                     {"statuses": []}]):
            self.assertEqual(loop.checks_state(loop.APP, HEAD, ["unit"]), "green")

    def test_unprotected_docker_still_requires_green_ci(self):
        docker = "peppone-choi/opensamguk-docker"
        self.assertIn(docker, loop.REPOS)
        with patch.object(loop, "api", return_value={"protected": False}):
            self.assertEqual(loop.required_checks(docker, "main"), [])
        with patch.object(loop, "api", side_effect=[[{"check_runs": []}], {"statuses": []}]):
            self.assertEqual(loop.checks_state(docker, HEAD, []), "pending")
        with patch.object(loop, "api", side_effect=[[{"check_runs": [{"id": 1, "name": "docker-ci", "conclusion": "failure"}]}],
                                                     {"statuses": []}]):
            self.assertEqual(loop.checks_state(docker, HEAD, []), "failed")
        with patch.object(loop, "api", side_effect=[[{"check_runs": [{"id": 2, "name": "docker-ci", "conclusion": "success"}]}],
                                                     {"statuses": []}]):
            self.assertEqual(loop.checks_state(docker, HEAD, []), "green")

    def test_unobserved_pending_cancelled_and_errors_are_not_red(self):
        failed = self.run_record()
        self.assertEqual(self.observe([dict(failed, status="in_progress", conclusion=None)])["state"], "PENDING")
        self.assertEqual(self.observe([dict(failed, conclusion="cancelled")])["state"], "PENDING")
        self.assertEqual(self.observe([], fail_at="branches/main")["state"], "UNKNOWN")

    def test_confirmed_failure_survives_rerun_old_green_errors_and_pending_descendant(self):
        old_green = self.run_record(id=8, run_number=8, head_sha=OLD, conclusion="success")
        failed = self.run_record()
        relation = {(OLD, HEAD): "ahead"}
        self.assertEqual(self.observe([old_green], head=OLD)["state"], "GREEN")
        self.assertEqual(self.observe([failed, old_green], relations=relation)["state"], "RED")
        # A new process cannot lose the failure just because attempt 2 is queued.
        queued = dict(failed, run_attempt=2, status="queued", conclusion=None)
        for status in ("queued", "in_progress", "completed"):
            run = dict(queued, status=status, conclusion="cancelled" if status == "completed" else None)
            self.assertEqual(self.observe([run, old_green], attempts={(9, 1): failed}, relations=relation)["state"], "RED")
        for endpoint in ("branches/main", "workflows/", "compare/"):
            self.assertEqual(self.observe([old_green], fail_at=endpoint)["state"], "RED")
        child = "c" * 40
        pending = self.run_record(id=10, run_number=10, head_sha=child, status="queued", conclusion=None)
        relation[(HEAD, child)] = "ahead"
        relation[(OLD, child)] = "ahead"
        self.assertEqual(self.observe([pending, queued, old_green], head=child,
                         attempts={(9, 1): failed}, relations=relation)["state"], "RED")
        self.assertEqual(self.observe([old_green], head=child, relations=relation)["state"], "RED")
        self.assertEqual(self.observe([], head=child)["state"], "RED")
        verdict = [("2", f"머지 판정: 머지 가능\n독립 리뷰어: Claude\n<!-- pr-loop v1 claude-verdict sha={HEAD} verdict=MERGEABLE -->")]
        self.assertEqual(self.evaluate(["server/handler.go"], verdict,
                         main={"state": "RED", "runId": 9})["action"], "wait-main-red")
        with patch.dict(RAW, labels=[{"name": "main-red-recovery"}], body="Main RED 복구: 9"):
            self.assertEqual(self.evaluate(["server/handler.go"], verdict,
                             main={"state": "RED", "runId": 9})["action"], "codex-merge")

    def test_rerun_history_bootstraps_red_without_previous_cache(self):
        failed = self.run_record()
        queued = dict(failed, run_attempt=3, status="queued", conclusion=None)
        cancelled = dict(failed, run_attempt=2, conclusion="cancelled")
        self.assertEqual(self.observe([queued], attempts={(9, 2): cancelled, (9, 1): failed})["state"], "RED")
        self.assertEqual(self.observe([], fail_at="branches/main")["runId"], 9)

    def test_only_verified_same_or_descendant_success_clears_confirmed_red(self):
        failed = self.run_record()
        self.observe([failed])
        unrelated = self.run_record(id=10, run_number=10, head_sha=OLD, conclusion="success")
        self.assertEqual(self.observe([unrelated], head=OLD)["state"], "RED")
        success = dict(failed, run_attempt=2, conclusion="success")
        self.assertEqual(self.observe([success])["state"], "GREEN")
        self.assertFalse((loop.STATE / "main-ci-red.json").exists())
        self.observe([failed])
        child = "c" * 40
        descendant = self.run_record(id=10, run_number=10, head_sha=child, conclusion="success")
        self.assertEqual(self.observe([descendant], head=child,
                         relations={(HEAD, child): "ahead"})["state"], "GREEN")
        self.assertEqual(self.observe([], fail_at="branches/main")["state"], "UNKNOWN")

    def test_failed_attempt_and_head_race_never_erase_known_red(self):
        failed = self.run_record()
        self.assertEqual(self.observe([failed], final_head=OLD)["state"], "RED")
        new_fail = dict(failed, run_attempt=2, conclusion="timed_out")
        self.assertEqual(self.observe([new_fail])["runAttempt"], 2)
        success = dict(failed, run_attempt=3, conclusion="success")
        self.assertEqual(self.observe([success], final_head=OLD)["state"], "RED")
        self.assertEqual(self.observe([success])["state"], "GREEN")

    def test_attempt_identity_mismatch_is_not_success_or_new_red(self):
        queued = self.run_record(run_attempt=2, status="queued", conclusion=None)
        foreign = self.run_record(workflow_id=999, conclusion="failure")
        self.assertEqual(self.observe([queued], attempts={(9, 1): foreign})["state"], "UNKNOWN")
        self.observe([self.run_record()])
        self.assertEqual(self.observe([queued], attempts={(9, 1): foreign})["state"], "RED")

    def test_watch_requests_squash_auto_once_and_rechecks_head(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            (base / "bin").mkdir()
            (base / "docs").mkdir()
            (base / "docs/pr-review-loop-claude-prompt.md").write_text(
                (ROOT / "docs/pr-review-loop-claude-prompt.md").read_text())
            fake = base / "bin/pr-loop"
            fake.write_text("""#!/bin/sh
case " $* " in
  *' --pr '*) cat "$FAKE_FRESH" ;;
  *) cat "$FAKE_INITIAL" ;;
esac
""")
            fake.chmod(0o755)
            gh_dir = base / "fake-bin"
            gh_dir.mkdir()
            gh = gh_dir / "gh"
            gh.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >>\"$GH_RECORD\"\n")
            gh.chmod(0o755)
            row = {"repo": "example/repo", "pr": 7, "head": HEAD, "action": "codex-merge"}
            initial, fresh, record = (base / name for name in ("initial.json", "fresh.json", "gh.calls"))
            initial.write_text(json.dumps([row]))
            fresh.write_text(json.dumps([row]))
            env = dict(os.environ, OPENSAMGUK_META_ROOT=str(base), PR_LOOP_STATE=str(base / "state"),
                       PATH=str(gh_dir) + ":" + os.environ["PATH"], FAKE_INITIAL=str(initial),
                       FAKE_FRESH=str(fresh), GH_RECORD=str(record), CLAUDE_BIN=str(base / "none"),
                       PR_LOOP_GH_BIN=str(gh))
            env.update(CODEX_BIN=str(base / "none"), PR_LOOP_REVIEWER="auto", PR_LOOP_CLAUDE_AVAILABLE="1", PR_LOOP_CODEX_AVAILABLE="1")
            watch = str(ROOT / "bin/pr-loop-watch")
            for _ in range(2):
                done = subprocess.run([watch], env=env, capture_output=True, text=True)
                self.assertEqual(done.returncode, 0, done.stderr)
            self.assertEqual(record.read_text().splitlines(),
                             [f"pr merge 7 --repo example/repo --squash --auto --match-head-commit {HEAD}"])
            # A stale queue row never authorizes a different fresh head.
            fresh.write_text(json.dumps([dict(row, head=OLD)]))
            (base / "state/auto-merge" / f"example_repo-7-{HEAD}").unlink()
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertEqual(len(record.read_text().splitlines()), 1)

            reviewer = base / "fake-claude"
            review_record = base / "reviews.calls"
            review_args = base / "review.args"
            reviewer.write_text("#!/bin/sh\nprintf 'review\\n' >>\"$CLAUDE_RECORD\"\nprintf '%s\\n' \"$@\" >\"$CLAUDE_ARGS\"\n")
            reviewer.chmod(0o755)
            initial.write_text(json.dumps([dict(row, action="independent-review")]))
            fresh.write_text(initial.read_text())
            env.update(CLAUDE_BIN=str(reviewer), CLAUDE_RECORD=str(review_record), CLAUDE_ARGS=str(review_args))
            for _ in range(2):
                done = subprocess.run([watch], env=env, capture_output=True, text=True)
                self.assertEqual(done.returncode, 0, done.stderr)
            self.assertEqual(review_record.read_text().splitlines(), ["review"])
            arguments = review_args.read_text().splitlines()
            self.assertIn(f"Bash({base}/bin/pr-loop marker verdict *)", arguments)
            self.assertTrue(any("`.env`와 비밀 파일" in arg for arg in arguments))
            self.assertFalse(any("Bash(gh pr merge" in arg or "Bash(git push" in arg for arg in arguments))

            # When Claude has no tokens, a fresh Codex process receives session IDs.
            (base / "docs/pr-review-loop-codex-reviewer-prompt.md").write_text(
                (ROOT / "docs/pr-review-loop-codex-reviewer-prompt.md").read_text())
            codex = base / "fake-codex"
            codex.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" >\"$CODEX_ARGS\"\n")
            codex.chmod(0o755)
            new_row = dict(row, action="independent-review", head=OLD, author_sessions=["writer"])
            initial.write_text(json.dumps([new_row]))
            fresh.write_text(initial.read_text())
            codex_args = base / "codex.args"
            env.update(CODEX_BIN=str(codex), CODEX_ARGS=str(codex_args), PR_LOOP_CLAUDE_AVAILABLE="0")
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            arguments = codex_args.read_text().splitlines()
            self.assertEqual(arguments[0], "exec")
            self.assertNotIn("resume", arguments)
            self.assertTrue(any("--author-session writer --reviewer-session watch-codex-" in arg for arg in arguments))
            self.assertEqual(review_record.read_text().splitlines(), ["review"])

            # Neither side available: no launch; stale head: no launch.
            codex_args.unlink()
            initial.write_text(json.dumps([dict(new_row, head="c" * 40)]))
            fresh.write_text(initial.read_text())
            env["PR_LOOP_CODEX_AVAILABLE"] = "0"
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertFalse(codex_args.exists())

            env["PR_LOOP_CODEX_AVAILABLE"] = "1"
            fresh.write_text(json.dumps([dict(new_row, head="d" * 40)]))
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertFalse(codex_args.exists())

            # A failed/unfinished first review cannot starve fresh PRs behind it.
            following = dict(new_row, pr=8, head="f" * 40)
            initial.write_text(json.dumps([dict(row, action="independent-review"), following]))
            fresh.write_text(json.dumps([following]))
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertIn("--sha " + following["head"], codex_args.read_text())

            # A legacy first PR without writer IDs cannot starve Codex-ready PRs.
            codex_args.unlink()
            following = dict(new_row, pr=9, head="g" * 40)
            legacy = dict(row, action="independent-review", head="e" * 40)
            initial.write_text(json.dumps([legacy, following]))
            fresh.write_text(json.dumps([following]))
            done = subprocess.run([watch], env=env, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertIn("--sha " + following["head"], codex_args.read_text())


if __name__ == "__main__":
    unittest.main()
