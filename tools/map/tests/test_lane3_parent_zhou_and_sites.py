"""Lane 3 ID-chain, source-section, and reviewed strategic anchor gates."""

import copy
import json
import unittest
from pathlib import Path

from tools.map import build_han_parent_reconciliation as parents
from tools.map import validate_han_strategic_site_anchors as sites


ROOT = Path(__file__).resolve().parents[3]


class ParentZhouAxisTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.documents, cls.records = parents.load_inputs()

    def test_canon_105_id_chain_ignores_catalog_order_and_display_name(self):
        expected = parents._zhou_by_administrative_unit(self.documents)
        changed = copy.deepcopy(self.documents)
        changed["data/curated/han/administrative-units.json"]["groups"].reverse()
        changed["data/curated/han/administrative-units.json"]["groups"][0]["sourceGroupName"] = "DISPLAY_ONLY_CHANGED"
        changed["data/curated/han/administrative-zhou-axis-v1.json"]["rows"].reverse()
        self.assertEqual(expected, parents._zhou_by_administrative_unit(changed))
        self.assertEqual(1180, len(expected))
        self.assertEqual(13, len({zhou for _, zhou in expected.values()}))

    def test_wrong_zhou_and_missing_group_id_fail_closed(self):
        changed = copy.deepcopy(self.documents)
        axis = changed["data/curated/han/administrative-zhou-axis-v1.json"]
        original = axis["rows"][0]["zhouId"]
        axis["rows"][0]["zhouId"] = "zhou:豫州" if original != "zhou:豫州" else "zhou:冀州"
        with self.assertRaisesRegex(ValueError, "source section"):
            parents._zhou_by_administrative_unit(changed)
        changed = copy.deepcopy(self.documents)
        changed["data/curated/han/administrative-zhou-axis-v1.json"]["rows"][0]["administrativeGroupId"] = "name:河南尹"
        with self.assertRaisesRegex(ValueError, "missing HHS group ID"):
            parents._zhou_by_administrative_unit(changed)

    def test_positive_cell_proposal_is_not_approved_by_geometry(self):
        ledger = json.loads(parents.OUTPUT.read_text(encoding="utf-8"))
        proposals = [row for row in ledger["rows"] if row["decision"] == "PROPOSED_GEOMETRIC"]
        positive = [row for row in proposals if row["cellCount"]]
        self.assertEqual(7, len(positive))
        self.assertTrue(all(row["reviewDisposition"] == "BLOCKED_SOURCE_IDENTITY_MISSING" for row in positive))
        self.assertTrue(all("approvedParentAdministrativeUnitId" not in row for row in positive))
        self.assertEqual(0, ledger["summary"]["directTerritoryReview"]["pendingCandidateJunCount"])
        self.assertEqual(4, ledger["summary"]["directTerritoryReview"]["reviewedOutsideCanonJunCount"])
        self.assertEqual(
            {"PARENT-0140", "PARENT-0145", "PARENT-0148", "PARENT-0157"},
            {row["parentRegionId"] for row in ledger["directTerritoryJunReviews"]},
        )


class StrategicAnchorApprovalTest(unittest.TestCase):
    def test_seven_sites_have_one_reviewed_terrain_anchor(self):
        ledger, documents, records = sites.load_bundle()
        sites.validate_ledger(ledger, documents, records)
        self.assertEqual(7, len(ledger["reviewRows"]))
        self.assertTrue(all(row["reviewState"] == "APPROVED" and row["siteRole"] == "TERRAIN_MARKER" for row in ledger["reviewRows"]))
        selected = {row["siteId"]: row["selectedCandidateId"] for row in ledger["reviewRows"]}
        self.assertEqual("site:jieting:longcheng-tradition", selected["site:jieting"])
        self.assertEqual("site:wuzhangyuan:committed-projection", selected["site:wuzhangyuan"])

    def test_missing_conflict_disposition_is_red(self):
        ledger, documents, records = sites.load_bundle()
        row = next(row for row in ledger["reviewRows"] if row["siteId"] == "site:jieting")
        row.pop("conflictDisposition")
        with self.assertRaisesRegex(ValueError, "APPROVED"):
            sites.validate_ledger(ledger, documents, records)


if __name__ == "__main__":
    unittest.main()
