import base64
import json
from pathlib import Path
import tempfile
import unittest

from collect_yuzhou_evidence import SCREENS, collect, render_tsv, write_once


def attachment(name: str, body: bytes) -> dict:
    return {"name": name, "body": base64.b64encode(body).decode("ascii")}


def fixture() -> dict:
    attachments = []
    for screen in SCREENS:
        attachments.append(attachment(f"screen-{screen}", b"\x89PNG\r\n\x1a\nimage"))
        attachments.append(attachment(f"api-{screen}-0", b"{}"))
    attachments.extend((
        attachment("db-hwiha-slice", b"{}"),
        attachment("phase-events", json.dumps([
            {"year": 190, "month": 1, "phase": 1, "kind": "march.corps"},
            {"year": 190, "month": 1, "phase": 1, "kind": "march.corps"},
        ]).encode()),
        attachment("phase-evidence", b'{"liveEncounterCount":1,"repeatedNeutralCaptures":0}'),
    ))
    return {"stats": {"expected": 1, "skipped": 0, "unexpected": 0, "flaky": 0},
            "suites": [{"title": "yuzhou-live.spec.ts", "specs": [{"tests": [{"results": [
                {"status": "passed", "attachments": attachments}]}]}]}]}


class CollectYuzhouEvidenceTest(unittest.TestCase):
    def collect_fixture(self, data: dict):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "playwright-results.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            return collect(path)

    def test_extracts_nine_screens_and_phase_counts(self):
        decoded, rows, summary = self.collect_fixture(fixture())
        self.assertEqual(len([name for name in decoded if name.startswith("screen-")]), 9)
        self.assertEqual(summary["api_count"], 9)
        self.assertEqual(rows, [(190, 1, 1, "march.corps", 2)])
        self.assertEqual(summary["event_counts_by_kind"]["march.corps"], 2)
        self.assertEqual(summary["first_event_by_kind"]["march.corps"],
                         {"year": 190, "month": 1, "phase": 1})
        self.assertIn(b"190\t1\t1\tmarch.corps\t2\n", render_tsv(rows))

    def test_missing_screen_is_rejected(self):
        data = fixture()
        data["suites"][0]["specs"][0]["tests"][0]["results"][0]["attachments"].pop(0)
        with self.assertRaisesRegex(ValueError, "nine required screen"):
            self.collect_fixture(data)

    def test_skipped_gate_is_rejected(self):
        data = fixture()
        data["stats"]["skipped"] = 1
        with self.assertRaisesRegex(ValueError, "one clean passing"):
            self.collect_fixture(data)

    def test_existing_different_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "screen-court.png"
            path.write_bytes(b"original")
            with self.assertRaisesRegex(ValueError, "existing evidence differs"):
                write_once(path, b"different")
            self.assertEqual(path.read_bytes(), b"original")


if __name__ == "__main__":
    unittest.main()
