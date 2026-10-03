#!/usr/bin/env python3
"""deps_audit_summary: 기본은 「돌았다 · 종료 코드」만, --counts 일 때만 심각도별 개수. 결과를 못 읽어도 실패하지 않는다."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from deps_audit_summary import summarize  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "deps_audit_summary.py"

SAMPLE = {
    "actions": [],
    "advisories": {
        "1": {"module_name": "pkg-a", "severity": "high"},
        "2": {"module_name": "pkg-b", "severity": "moderate"},
        "3": {"module_name": "pkg-c", "severity": "critical"},
    },
    "muted": [],
    "metadata": {
        "vulnerabilities": {"info": 0, "low": 0, "moderate": 1, "high": 1, "critical": 1},
        "dependencies": 42,
    },
}
NAMES = ("pkg-a", "pkg-b", "pkg-c")


class SummarizeTest(unittest.TestCase):
    def test_default_reports_only_that_it_ran_and_the_exit_code(self) -> None:
        out = summarize(json.dumps(SAMPLE), "1")
        self.assertIn("감사가 돌았고 결과를 읽었다", out)
        self.assertIn("종료 코드 1", out)
        for hidden in (*NAMES, "critical |", "| 1 | 1 | 1", "high 이상"):
            self.assertNotIn(hidden, out)

    def test_counts_flag_adds_severity_counts_without_package_names(self) -> None:
        out = summarize(json.dumps(SAMPLE), "1", counts=True)
        self.assertIn("| critical | high | moderate | low | info | 권고 | 의존성 |", out)
        self.assertIn("| 1 | 1 | 1 | 0 | 0 | 3 | 42 |", out)
        self.assertIn("high 이상 2건", out)
        for name in NAMES:
            self.assertNotIn(name, out)

    def test_unreadable_or_error_results_are_reported_not_raised(self) -> None:
        self.assertIn("읽지 못했다", summarize("", "1"))
        self.assertIn("읽지 못했다", summarize("ERR_PNPM_AUDIT_BAD_RESPONSE", "1", counts=True))
        out = summarize(json.dumps({"error": {"code": "ERR", "message": "registry down"}}), "1")
        self.assertIn("metadata 가 없다(registry down)", out)

    def test_cli_always_exits_zero_and_hides_counts_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good.json"
            good.write_text(json.dumps(SAMPLE), encoding="utf-8")
            bad = Path(tmp) / "audit.json"
            bad.write_text("not json", encoding="utf-8")
            plain = subprocess.run([sys.executable, str(SCRIPT), str(good), "1"], capture_output=True, text=True)
            self.assertEqual(plain.returncode, 0, plain.stderr)
            self.assertNotIn("| 1 | 1 | 1", plain.stdout)
            counted = subprocess.run([sys.executable, str(SCRIPT), str(good), "1", "--counts"], capture_output=True, text=True)
            self.assertIn("| 1 | 1 | 1 | 0 | 0 | 3 | 42 |", counted.stdout)
            for args in ([str(bad), "1"], [str(Path(tmp) / "missing.json")], []):
                run = subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)
                self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
