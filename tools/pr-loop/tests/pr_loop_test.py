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

    def test_confirmed_main_red_only(self):
        workflow = {"id": loop.MAIN_CI_WORKFLOW, "path": ".github/workflows/ci.yml", "state": "active"}
        failed = {"id": 9, "run_number": 9, "run_attempt": 1, "workflow_id": loop.MAIN_CI_WORKFLOW,
                  "head_branch": "main", "event": "push", "head_sha": HEAD, "status": "completed", "conclusion": "failure"}
        def observe(run):
            answers = [{"commit": {"sha": HEAD}}, workflow, {"workflow_runs": [run]}, {"commit": {"sha": HEAD}}]
            with patch.object(loop, "api", side_effect=answers):
                return loop.main_ci()["state"]
        self.assertEqual(observe(failed), "RED")
        self.assertEqual(observe(dict(failed, status="in_progress", conclusion=None)), "PENDING")
        self.assertEqual(observe(dict(failed, conclusion="cancelled")), "PENDING")
        with patch.object(loop, "api", side_effect=RuntimeError("unavailable")):
            self.assertEqual(loop.main_ci()["state"], "UNKNOWN")
        verdict = [("2", f"머지 판정: 머지 가능\n독립 리뷰어: Claude\n<!-- pr-loop v1 claude-verdict sha={HEAD} verdict=MERGEABLE -->")]
        self.assertEqual(self.evaluate(["server/handler.go"], verdict, main={"state": "RED", "runId": 9})["action"], "wait-main-red")

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


if __name__ == "__main__":
    unittest.main()
