import copy
import json
import unittest
from pathlib import Path

from tools.map import audit_ferry_water_proximity as audit


ROOT = Path(__file__).resolve().parents[3]


class FerryWaterGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles, cls.strongholds, cls.ledger = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in (audit.TILES, audit.STRONGHOLDS, audit.LEDGER)
        ]

    def test_committed_water_gate(self):
        self.assertEqual([], audit.check(self.tiles, self.strongholds, self.ledger))

    def test_red_probe_restoring_one_missing_river_cell_fails(self):
        site = next(row for row in self.ledger["rows"] if row["siteId"] == "liaokou")
        tiles = copy.deepcopy(self.tiles)
        r, c = site["anchor"]["row"], site["anchor"]["col"]
        line = tiles["terrain"][r]
        tiles["terrain"][r] = line[:c] + site["terrainBefore"] + line[c + 1:]
        errors = audit.check(tiles, self.strongholds, self.ledger)
        self.assertTrue(any("local RIVER anchor was lost" in error for error in errors), errors)

    def test_inventory_and_nonempty_controls_reject_a_missing_ferry(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["rows"] = [row for row in ledger["rows"] if row["siteId"] != "yanjin"]
        errors = audit.check(self.tiles, self.strongholds, ledger)
        self.assertTrue(any("FERRY inventory mismatch" in error for error in errors), errors)
        self.assertTrue(any("positive control 延津" in error for error in errors), errors)

    def test_unconnected_ferries_do_not_create_boat_routes(self):
        network = json.loads((ROOT / "data/map/han-waterway-network-v1.json").read_text(encoding="utf-8"))
        self.assertEqual(network["activation"], "NON_ACTIVATING")
        active = {node["siteRef"]["id"] for node in network["nodes"] if node["siteRef"]["kind"] == "STRONGHOLD"}
        blocked = {node["siteRef"]["id"]: node for node in network["blockedNodes"]
                   if node["siteRef"]["kind"] == "STRONGHOLD"}
        for row in self.ledger["rows"]:
            if row["currentWaterDistance"] >= 2 or row["disposition"] == "LOCAL_RIVER_CELL":
                self.assertNotIn(row["siteId"], active)
                self.assertIn(row["siteId"], blocked)
        self.assertEqual(blocked["xiakou"]["reasonCode"],
                         "FERRY_ANCHOR_NOT_CONNECTED_TO_ATTESTED_REACH")


if __name__ == "__main__":
    unittest.main()
