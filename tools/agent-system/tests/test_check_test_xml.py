"""test_check_test_xml.py — skipped-it-guard regression.

Fixture-driven: builds synthetic Gradle test-results XML (a normal test
fixture, not a fabricated golden — see docs/superpowers/reviews for the
incident this locks down) and asserts check_test_xml.py's exit code/output
for each case. Real end-to-end Docker on/off proof lives in the task report;
this is the permanent regression that keeps the parser's decision correct.
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "check_test_xml.py"

CLEAN_XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fake.CleanIT" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-08-24T00:00:00" hostname="fixture" time="0.1">
  <properties/>
  <testcase name="it runs()" classname="fake.CleanIT" time="0.1"/>
</testsuite>
"""

SKIPPED_XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fake.ScenarioBlankUnificationIT" tests="1" skipped="1" failures="0" errors="0" timestamp="2026-08-24T00:00:00" hostname="fixture" time="0.01">
  <properties/>
  <testcase name="han founding grants a conscriptable crew type()" classname="fake.ScenarioBlankUnificationIT" time="0.01">
    <skipped message="Docker unavailable - IT skipped"/>
  </testcase>
</testsuite>
"""


def _write_suite(root: Path, module: str, filename: str, content: str) -> None:
    d = root / module / "build" / "test-results" / "test"
    d.mkdir(parents=True, exist_ok=True)
    (d / filename).write_text(content, encoding="utf-8")


def _run(root: Path, module: str, extra_env: dict | None = None) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env.pop("OPENSAM_ALLOW_SKIPPED_IT", None)
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--repo-root", str(root), module],
        capture_output=True,
        text=True,
        env=env,
    )


class CheckTestXmlTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_xml_fails_loudly(self):
        # A module root whose test task never produced any XML (deleted test
        # class, excluded test task, etc.) must not read as green.
        (self.root / "untested-mod").mkdir()
        result = _run(self.root, "untested-mod")
        self.assertEqual(result.returncode, 1)
        self.assertIn("No Gradle test XML files found", result.stderr)

    def test_green_when_nothing_skipped(self):
        _write_suite(self.root, "clean-mod", "TEST-fake.CleanIT.xml", CLEAN_XML)
        result = _run(self.root, "clean-mod")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("0 skipped", result.stdout)

    def test_red_when_skipped_and_no_opt_out(self):
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        result = _run(self.root, "skipped-mod")
        self.assertEqual(result.returncode, 1)
        self.assertIn("SKIPPED TEST(S) DETECTED", result.stderr)
        self.assertIn("ScenarioBlankUnificationIT", result.stderr)

    def test_opt_out_passes_but_prints_skip_list(self):
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        result = _run(self.root, "skipped-mod", extra_env={"OPENSAM_ALLOW_SKIPPED_IT": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SKIPPED TEST(S) DETECTED", result.stderr)
        self.assertIn("NOT verified", result.stderr)

    def test_failures_fail_even_with_opt_out(self):
        failed_xml = CLEAN_XML.replace('failures="0"', 'failures="1"').replace(
            '<testcase name="it runs()" classname="fake.CleanIT" time="0.1"/>',
            '<testcase name="it runs()" classname="fake.CleanIT" time="0.1"><failure message="boom"/></testcase>',
        )
        _write_suite(self.root, "clean-mod", "TEST-fake.CleanIT.xml", failed_xml)
        result = _run(self.root, "clean-mod", extra_env={"OPENSAM_ALLOW_SKIPPED_IT": "1"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("RED", result.stderr)


if __name__ == "__main__":
    unittest.main()
