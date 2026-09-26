"""Web copy ratchet tests, including red probes for each rule."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from web_copy_lint import KINDS, check, load_allowlist, scan, strip_comments

SCRIPT = Path(__file__).with_name("web_copy_lint.py")


class WebCopyLintTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def run_cli(self, baseline, allowlist=None):
        base = self.write("baseline.json", json.dumps(baseline))
        allow = self.write("allow.json", json.dumps(allowlist or {"paths": []}))
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), "--baseline", str(base),
                               "--allowlist", str(allow)], capture_output=True, text=True)

    def test_hanja_in_jsx_text_and_strings_is_counted(self):
        self.write("web/game/components/Probe.tsx",
                   "export const a = '長安縣';\nexport function P() { return <p>城 방어</p>; }\n")
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["hanja"], 2)
        self.assertEqual(findings["hanja"][0], "web/game/components/Probe.tsx:1:長安縣")

    def test_retired_terms_are_counted_once_each(self):
        self.write("web/shared/src/Probe.ts", "const t = `자금 ${x} 군량매매 휘하 편성`;\n")
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["retired_term"], 3)
        self.assertEqual([f.rsplit(":", 1)[1] for f in findings["retired_term"]], ["자금", "군량매매", "휘하"])

    def test_comments_are_not_player_copy(self):
        self.write("web/gateway/app/Probe.tsx",
                   "// 縣 휘하 주석\n/* 郡\n 군량 */\nconst url = 'http://x//縣';\n{/* 城 */}\n")
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["retired_term"], 0)
        self.assertEqual(findings["hanja"], ["web/gateway/app/Probe.tsx:4:縣"])

    def test_template_nesting_keeps_comment_detection(self):
        text = "const a = `${b ? `縣` : '//'}`; // 郡\nconst c = 1;\n"
        stripped = strip_comments(text)
        self.assertIn("縣", stripped)
        self.assertNotIn("郡", stripped)
        self.assertEqual(stripped.count("\n"), text.count("\n"))

    def test_tests_generated_types_and_other_roots_are_skipped(self):
        self.write("web/game/__tests__/a.tsx", "'縣'\n")
        self.write("web/game/components/a.test.tsx", "'縣'\n")
        self.write("web/game/e2e/a.spec.ts", "'縣'\n")
        self.write("web/game/next-env.d.ts", "'縣'\n")
        self.write("app/game-api/Probe.ts", "'縣'\n")
        counts, _ = scan(self.root, {})
        self.assertEqual(counts["hanja"], 0)

    def test_allowlist_exempts_only_named_kind(self):
        self.write("web/shared/src/table.ts", "const m = { '縣': '현' }; // 휘하\nconst w = '휘하';\n")
        allowed = load_allowlist(self.write("allow.json", json.dumps({"paths": [
            {"path": "web/shared/src/table.ts", "kinds": ["hanja"], "reason": "normalisation table"}]})), self.root)
        counts, _ = scan(self.root, allowed)
        self.assertEqual(counts["hanja"], 0)
        self.assertEqual(counts["retired_term"], 1)

    def test_allowlist_rejects_missing_reason_or_unknown_kind(self):
        self.write("web/shared/src/table.ts", "x\n")
        for entry in ({"path": "web/shared/src/table.ts", "kinds": ["hanja"]},
                      {"path": "web/shared/src/table.ts", "kinds": ["nope"], "reason": "r"},
                      {"path": "web/shared/src/missing.ts", "kinds": ["hanja"], "reason": "r"}):
            with self.assertRaises(ValueError):
                load_allowlist(self.write("allow.json", json.dumps({"paths": [entry]})), self.root)

    def test_red_probe_new_violation_fails_cli(self):
        self.write("web/game/components/Probe.tsx", "export const a = '금';\n")
        self.assertEqual(self.run_cli({"hanja": 0, "retired_term": 0}).returncode, 0)
        self.write("web/game/components/Probe.tsx", "export const a = '국고 縣';\n")
        result = self.run_cli({"hanja": 0, "retired_term": 0})
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL hanja: 1 > baseline 0", result.stdout)
        self.assertIn("FAIL retired_term: 1 > baseline 0", result.stdout)

    def test_lower_count_requires_baseline_update(self):
        self.write("web/game/components/Probe.tsx", "export const a = '금';\n")
        result = self.run_cli({"hanja": 1, "retired_term": 0})
        self.assertEqual(result.returncode, 1)
        self.assertIn("LOWER hanja: 0 < baseline 1", result.stdout)

    def test_baseline_needs_every_kind(self):
        with self.assertRaises(ValueError):
            check({kind: 0 for kind in KINDS}, {"hanja": 0})


if __name__ == "__main__":
    unittest.main()
