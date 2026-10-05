"""래칫 판정(tools/ci/ratchet.py) — 「실측 ≤ min(기준선, 병합 기준 실측)」. K10 시나리오를 그대로 시험으로 둔다(2026-10-01)."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ratchet import NEW_FILE_RULE_SINCE, added_paths, allowlist_growth, judge, limits, new_file_verdict  # noqa: E402

UI_LINT = Path(__file__).with_name("web_ui_lint.py")
COPY_LINT = Path(__file__).with_name("web_copy_lint.py")
KINDS = ("title_attr", "native_disabled", "dimmed_disabled", "adhoc_breakpoint")
ZERO = {kind: 0 for kind in KINDS}


class JudgeTest(unittest.TestCase):
    def test_slack_zero_plus_one_fails(self):
        _, failed = judge({"x": 38}, {"x": 37}, {"x": 37}, ("x",), "b.json")
        self.assertTrue(failed)

    def test_stale_baseline_slack_cannot_be_spent(self):
        # 앞 PR 이 2개 고치고 JSON 을 안 내림(base 실측 35, JSON 37) → 새 PR 이 2개를 넣어 37 이면 빨강이어야 한다.
        messages, failed = judge({"x": 37}, {"x": 37}, {"x": 35}, ("x",), "b.json")
        self.assertTrue(failed)
        self.assertIn("FAIL x: 37 > min(baseline 37, base 35)", messages[0])

    def test_lowering_is_a_note_not_a_failure(self):
        messages, failed = judge({"x": 34}, {"x": 37}, {"x": 35}, ("x",), "b.json")
        self.assertFalse(failed)
        self.assertTrue(messages[0].startswith("NOTE x: 34 < baseline 37"))

    def test_without_base_the_baseline_is_the_limit(self):
        self.assertEqual(limits({"x": 37}, None, ("x",)), {"x": 37})
        self.assertFalse(judge({"x": 36}, {"x": 37}, None, ("x",), "b.json")[1])
        self.assertTrue(judge({"x": 38}, {"x": 37}, None, ("x",), "b.json")[1])


class AllowlistGrowthTest(unittest.TestCase):
    def test_new_path_or_kind_is_noted(self):
        base = {"a.ts": {"kinds": ["hanja"]}}
        current = {"a.ts": {"kinds": ["hanja", "retired_term"]}, "b.ts": {"kinds": ["hanja"]}}
        notes = allowlist_growth(current, base, "allow.json")
        self.assertEqual(len(notes), 1)
        self.assertIn("2 new exemption(s)", notes[0])
        self.assertIn("a.ts[retired_term]", notes[0])
        self.assertIn("b.ts[hanja]", notes[0])

    def test_same_or_smaller_allowlist_is_silent(self):
        base = {"a.ts": {"kinds": ["hanja", "retired_term"]}}
        self.assertEqual(allowlist_growth({"a.ts": {"kinds": ["hanja"]}}, base, "allow.json"), [])


class BaseRefCliTest(unittest.TestCase):
    """실제 git 저장소에서 --base-ref 로 병합 기준 커밋을 다시 센다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def commit_titles(self, n):
        self.write("web/game/components/P.tsx", "".join(f'<button title="t{i}">x</button>\n' for i in range(n)))
        self.git("add", "-A")
        self.git("commit", "-q", "-m", f"{n} titles")
        return subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()

    def run_cli(self, baseline, base_ref=None):
        path = self.write("baseline.json", json.dumps(baseline))
        args = [sys.executable, str(UI_LINT), "--root", str(self.root), "--baseline", str(path)]
        if base_ref:
            args += ["--base-ref", base_ref, "--repo", str(self.root)]
        return subprocess.run(args, capture_output=True, text=True)

    def test_stale_slack_is_red_against_the_merge_base(self):
        base = self.commit_titles(1)  # main 실측 1, JSON 은 3 으로 남았다(앞 PR 이 안 내림)
        self.write("web/game/components/P.tsx", "".join(f'<button title="t{i}">x</button>\n' for i in range(3)))  # 새 PR 이 2개 추가
        no_base = self.run_cli({**ZERO, "title_attr": 3})
        self.assertEqual(no_base.returncode, 0)  # 기준선만 보면 놓친다
        with_base = self.run_cli({**ZERO, "title_attr": 3}, base_ref=base)
        self.assertEqual(with_base.returncode, 1, with_base.stdout)
        self.assertIn("FAIL title_attr: 3 > min(baseline 3, base 1)", with_base.stdout)

    def test_pr_that_fixes_passes_without_touching_the_baseline(self):
        base = self.commit_titles(2)
        self.write("web/game/components/P.tsx", '<button title="t0">x</button>\n')  # 하나 고침, JSON 은 그대로 2
        result = self.run_cli({**ZERO, "title_attr": 2}, base_ref=base)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("NOTE title_attr: 1 < baseline 2", result.stdout)

    def test_unknown_base_ref_is_a_configuration_error(self):
        # 없는 ref 를 빈 트리(병합 기준 0)로 세면 PR 이 「새 위반」으로 빨개진다 — 설정 오류(exit 2)여야 한다(K10 #1180).
        self.commit_titles(1)
        result = self.run_cli({**ZERO, "title_attr": 1}, base_ref="0" * 40)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("is not a commit", result.stdout)

    def test_base_ref_without_source_roots_is_a_configuration_error(self):
        self.write("README", "x\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "no web")
        empty = subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
        self.commit_titles(1)
        result = self.run_cli({**ZERO, "title_attr": 1}, base_ref=empty)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("has none of", result.stdout)

    def test_allowlist_growth_is_noted_against_the_merge_base(self):
        self.write("web/game/components/A.tsx", "export const a = '縣';\n")
        self.write("web/game/components/B.tsx", "export const b = '郡';\n")
        entry = lambda path: {"path": path, "kinds": ["hanja"], "reason": "r"}
        self.write("tools/ci/allow.json", json.dumps({"paths": [entry("web/game/components/A.tsx")]}))
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "one exemption")
        base = subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
        allow = self.write("tools/ci/allow.json", json.dumps({"paths": [entry("web/game/components/A.tsx"), entry("web/game/components/B.tsx")]}))
        baseline = self.write("baseline.json", json.dumps({"hanja": 1, "retired_term": 0}))
        result = subprocess.run([sys.executable, str(COPY_LINT), "--root", str(self.root), "--baseline", str(baseline),
                                 "--allowlist", str(allow), "--base-ref", base, "--repo", str(self.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)  # 안내일 뿐 실패는 아니다
        self.assertIn("NOTE allow.json: 1 new exemption(s) vs base — reviewer must check: web/game/components/B.tsx[hanja]", result.stdout)

    def test_write_baseline_records_measured_counts(self):
        self.commit_titles(2)
        path = self.write("baseline.json", json.dumps({**ZERO, "title_attr": 5}))
        subprocess.run([sys.executable, str(UI_LINT), "--root", str(self.root), "--baseline", str(path), "--write-baseline"],
                       check=True, capture_output=True)
        self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["title_attr"], 2)


class NewFileRuleTest(unittest.TestCase):
    def test_verdict_fails_new_violations_and_notes_prs_opened_before_the_rule(self):
        messages, failed = new_file_verdict(["k: web/x.ts"], None)
        self.assertEqual((["FAIL new-file k: web/x.ts"], True), (messages, failed))
        messages, failed = new_file_verdict(["k: web/x.ts"], "2000-01-01T00:00:00Z")
        self.assertFalse(failed)
        self.assertTrue(messages[0].startswith("NOTE new-file"))
        self.assertEqual(([], False), new_file_verdict([], None))
        self.assertRegex(NEW_FILE_RULE_SINCE, r"^2026-\d\d-\d\dT\d\d:\d\d:\d\dZ$")  # the ADR merge time, not a placeholder

    def test_added_paths_detects_renames_as_not_new(self):
        import subprocess
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git = lambda *a: subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t", *a],  # noqa: E731
                                            check=True, capture_output=True, text=True).stdout
            git("init", "-q")
            (repo / "a.txt").write_text("same content\n" * 20, encoding="utf-8")
            git("add", "-A")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD").strip()
            git("mv", "a.txt", "moved.txt")
            (repo / "new.txt").write_text("new\n", encoding="utf-8")
            git("add", "-A")
            git("commit", "-qm", "head")
            self.assertEqual({"new.txt"}, added_paths(repo, base))


if __name__ == "__main__":
    unittest.main()
