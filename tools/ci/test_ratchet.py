"""래칫 판정(tools/ci/ratchet.py) — 「실측 ≤ min(기준선, 병합 기준 실측)」. K10 시나리오를 그대로 시험으로 둔다(2026-10-01)."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ratchet import judge, limits  # noqa: E402

UI_LINT = Path(__file__).with_name("web_ui_lint.py")
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

    def test_write_baseline_records_measured_counts(self):
        self.commit_titles(2)
        path = self.write("baseline.json", json.dumps({**ZERO, "title_attr": 5}))
        subprocess.run([sys.executable, str(UI_LINT), "--root", str(self.root), "--baseline", str(path), "--write-baseline"],
                       check=True, capture_output=True)
        self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["title_attr"], 2)


if __name__ == "__main__":
    unittest.main()
