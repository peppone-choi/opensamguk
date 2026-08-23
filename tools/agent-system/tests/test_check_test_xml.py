"""test_check_test_xml.py — skipped-it-guard regression.

Fixture-driven: builds synthetic Gradle test-results XML (a normal test
fixture, not a fabricated golden — see docs/superpowers/reviews for the
incident this locks down) and asserts check_test_xml.py's exit code/output
for each case. Real end-to-end Docker on/off proof lives in the task report;
this is the permanent regression that keeps the parser's decision correct.
"""
import json
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

OTHER_SKIPPED_XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fake.LongSimReplayGateTest" tests="1" skipped="1" failures="0" errors="0" timestamp="2026-08-24T00:00:00" hostname="fixture" time="0.01">
  <properties/>
  <testcase name="12 month structural replay matches PHP golden()" classname="fake.LongSimReplayGateTest" time="0.01">
    <skipped message="LONGSIM_SCHEMA4_CANDIDATE_DIR not set - IT skipped"/>
  </testcase>
</testsuite>
"""


def _write_suite(root: Path, module: str, filename: str, content: str) -> None:
    d = root / module / "build" / "test-results" / "test"
    d.mkdir(parents=True, exist_ok=True)
    (d / filename).write_text(content, encoding="utf-8")


def _run(
    root: Path,
    module: str,
    extra_env: dict | None = None,
    extra_args: list | None = None,
) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env.pop("OPENSAM_ALLOW_SKIPPED_IT", None)
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--repo-root", str(root), *(extra_args or []), module],
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

    def test_quarantined_skip_passes_without_opt_out(self):
        # A skip whose test key is registered in the quarantine file (with a
        # ticket) must pass green with no OPENSAM_ALLOW_SKIPPED_IT needed —
        # but must still print QUARANTINED loudly, not silently.
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        quarantine_path = self.root / "quarantine.json"
        quarantine_path.write_text(
            json.dumps({
                "fake.ScenarioBlankUnificationIT#han founding grants a conscriptable crew type()": {
                    "ticket": "https://example.invalid/issues/1",
                    "reason": "test fixture",
                },
            }),
            encoding="utf-8",
        )
        result = _run(self.root, "skipped-mod", extra_args=["--quarantine", str(quarantine_path)])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("QUARANTINED", result.stderr)
        self.assertIn("https://example.invalid/issues/1", result.stderr)
        self.assertIn("NOT verified", result.stderr)

    def test_unquarantined_skip_alongside_quarantined_still_fails(self):
        # Quarantine only covers the exact key it lists — an unrelated skip
        # in the same run must still fail, opt-out notwithstanding absent.
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        quarantine_path = self.root / "quarantine.json"
        quarantine_path.write_text(
            json.dumps({"fake.SomeOtherIT#unrelated()": {"ticket": "t", "reason": "r"}}),
            encoding="utf-8",
        )
        result = _run(self.root, "skipped-mod", extra_args=["--quarantine", str(quarantine_path)])
        self.assertEqual(result.returncode, 1)
        self.assertIn("SKIPPED: fake.ScenarioBlankUnificationIT", result.stderr)

    def test_quarantined_and_unquarantined_skip_coexist_in_one_run(self):
        # Two different skipped testcases in the SAME run: one registered in
        # quarantine, one not. Quarantine must not blanket-silence the run —
        # only the exact registered key passes; the other still fails it.
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        _write_suite(self.root, "skipped-mod", "TEST-fake.LongSimReplayGateTest.xml", OTHER_SKIPPED_XML)
        quarantine_path = self.root / "quarantine.json"
        quarantine_path.write_text(
            json.dumps({
                "fake.LongSimReplayGateTest#12 month structural replay matches PHP golden()": {
                    "ticket": "https://example.invalid/issues/521",
                    "reason": "test fixture",
                },
            }),
            encoding="utf-8",
        )
        result = _run(self.root, "skipped-mod", extra_args=["--quarantine", str(quarantine_path)])
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("QUARANTINED: fake.LongSimReplayGateTest", result.stderr)
        self.assertIn("SKIPPED: fake.ScenarioBlankUnificationIT", result.stderr)

    def test_quarantine_entry_without_ticket_is_rejected(self):
        # A quarantine registration with no ticket is worse than no guard —
        # the loader must refuse the whole file rather than let it through.
        _write_suite(self.root, "skipped-mod", "TEST-fake.ScenarioBlankUnificationIT.xml", SKIPPED_XML)
        quarantine_path = self.root / "quarantine.json"
        quarantine_path.write_text(
            json.dumps({
                "fake.ScenarioBlankUnificationIT#han founding grants a conscriptable crew type()": {
                    "ticket": "",
                    "reason": "no ticket",
                },
            }),
            encoding="utf-8",
        )
        result = _run(self.root, "skipped-mod", extra_args=["--quarantine", str(quarantine_path)])
        self.assertEqual(result.returncode, 1)
        self.assertIn("no non-empty 'ticket'", result.stderr)

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
