import base64
import json
from pathlib import Path
import tempfile
import unittest

from collect_yuzhou_evidence import (
    SCREENS, collect, render_tsv, sealed_encounter_ids, summarize_battle_export, write_once,
)


BATTLE_FIXTURE = Path(__file__).parent / "testdata" / "encounter-outcomes-draft.json"


def attachment(name: str, body: bytes) -> dict:
    return {"name": name, "body": base64.b64encode(body).decode("ascii")}


def fixture() -> dict:
    attachments = []
    for screen in SCREENS:
        attachments.append(attachment(f"screen-{screen}", b"\x89PNG\r\n\x1a\nimage"))
        attachments.append(attachment(f"api-{screen}-0", b"{}"))
    attachments.extend((
        attachment("db-hwiha-slice", b'{"sieges":[{"status":"FALLEN","turns":3},'
                   b'{"status":"FALLEN","turns":18},{"status":"FALLEN","turns":25}]}'),
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
        self.assertEqual(summary["battle_result_gate"]["status"], "NOT_COLLECTED")
        self.assertEqual(summary["siege_tempo"],
                         {"fallen_turns": [3, 18, 25], "within_12_to_24": 1})
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

    def test_draft_battle_projection_counts_winnerless_round_limit(self):
        summary = summarize_battle_export(BATTLE_FIXTURE,
                                          {"qa-draft-round-limit", "qa-draft-decisive"})
        self.assertEqual(summary["status"], "CONTRACT_DRAFT")
        self.assertEqual(summary["resolved_count"], 2)
        self.assertEqual(summary["winnerless_count"], 1)
        self.assertEqual(summary["round_24_count"], 1)
        self.assertEqual(summary["barriers"]["ROUND_LIMIT"], 1)
        self.assertEqual(summary["callback_count"], 1)
        self.assertEqual(summary["evidence_source"], "MEMORY_ONLY")
        self.assertEqual(summary["commander_statuses"], {"HOLDING": 1, "RETREATED": 3})
        self.assertEqual(summary["initial_separation_steps"], [9, 45])
        self.assertEqual(summary["world_map_variants"], {"V3_1447_MAP4": 2})

    def test_battle_export_requires_db_backed_sealed_ids(self):
        with self.assertRaisesRegex(ValueError, "differ from DB-backed march events"):
            summarize_battle_export(BATTLE_FIXTURE, {"different-encounter"})

    def test_sealed_ids_require_encounter_stop_and_matching_count(self):
        events = [
            {"kind": "march.corps", "refs": {"stop": "BUDGET_EXHAUSTED"}},
            {"kind": "march.corps", "refs": {"stop": "ENCOUNTER", "encounterId": "battle-1"}},
        ]
        self.assertEqual(sealed_encounter_ids(events, {"liveEncounterCount": 1}), {"battle-1"})
        with self.assertRaisesRegex(ValueError, "count differs"):
            sealed_encounter_ids(events, {"liveEncounterCount": 2})

    def test_sealed_march_without_id_is_rejected(self):
        events = [{"kind": "march.corps", "refs": {"stop": "ENCOUNTER"}}]
        with self.assertRaisesRegex(ValueError, "missing encounterId"):
            sealed_encounter_ids(events, {"liveEncounterCount": 0})

    def test_duplicate_battle_id_is_rejected(self):
        data = json.loads(BATTLE_FIXTURE.read_text())
        data["rows"][1]["encounterId"] = data["rows"][0]["encounterId"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate encounterId"):
                summarize_battle_export(path)

    def test_missing_resolved_battle_id_is_rejected(self):
        data = json.loads(BATTLE_FIXTURE.read_text())
        data["rows"].pop()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "missing or adds resolved encounter IDs"):
                summarize_battle_export(path)


if __name__ == "__main__":
    unittest.main()
