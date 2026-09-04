import copy
import unittest

from tools.scenario.audit_han_supply_disagreements import audit_documents


def fixture():
    tiles = {
        "provinceRecords": [
            {"id": "PR0", "jurisdictionId": "J0", "parentRegionId": "R0"},
            {"id": "PR1", "jurisdictionId": "J1", "parentRegionId": "R0"},
            {"id": "PR2", "jurisdictionId": "J2", "parentRegionId": "R1"},
        ],
        "adjacency": {"county": [{"a": 0, "b": 2}, {"a": 1, "b": 2}]},
    }
    runtime_map = {"cities": [
        {"id": 2, "physicalPlaceId": "P2", "provinceId": 1, "connections": [1]},
        {"id": 1, "physicalPlaceId": "P1", "provinceId": 0, "connections": [2]},
    ]}
    ownership = {"scenarios": [{"scenarioCode": 1010, "assignments": [
        {"provinceId": "PR0", "ownerNationId": 1},
        {"provinceId": "PR1", "ownerNationId": 1},
        {"provinceId": "PR2", "ownerNationId": 2},
    ]}]}
    scenarios = {1010: {"nation": [["N1", "#000", 0, 0, "", 0, "", 1, [1, 2]]]}}
    source = {"adjudications": [{
        "componentKey": "R0@10:10", "unitId": "R0", "memberIds": ["J1"],
        "verdict": "GEOMETRY_DEFECT",
    }]}
    ledger = {"schemaVersion": 1, "decisions": [{
        "runtimeCityId": 2,
        "physicalPlaceId": "P2",
        "jurisdictionId": "J1",
        "decision": "PROTECT_GEOMETRY_DEFECT",
        "sourceLedgerRow": "R0@10:10",
        "effectiveScenarioFrom": 1010,
        "effectiveScenarioTo": 1010,
    }]}
    return tiles, runtime_map, ownership, scenarios, ledger, source


class HanSupplyDisagreementAuditTest(unittest.TestCase):
    def audit(self, mutate=None):
        docs = list(fixture())
        if mutate:
            mutate(*docs)
        return audit_documents(*docs)

    def test_valid_inventory_is_sorted_and_counted(self):
        result = self.audit()
        self.assertEqual([], result.errors)
        self.assertEqual([(1010, 2)], [(row["scenarioCode"], row["runtimeCityId"]) for row in result.rows])
        self.assertEqual(1, result.summaries[1010]["CITY_ONLY_PROTECTED"])
        self.assertEqual(1, result.summaries[1010]["BOTH_SUPPLIED"])

    def test_unknown_decision_fails_closed(self):
        result = self.audit(lambda *docs: docs[4]["decisions"][0].update(decision="GUESS"))
        self.assertTrue(any("unknown decision" in error for error in result.errors))

    def test_overlapping_effective_ranges_fail_closed(self):
        def mutate(*docs):
            docs[4]["decisions"].append(copy.deepcopy(docs[4]["decisions"][0]))
        result = self.audit(mutate)
        self.assertTrue(any("overlapping active decision" in error for error in result.errors))

    def test_identity_drift_fails_closed(self):
        result = self.audit(lambda *docs: docs[4]["decisions"][0].update(physicalPlaceId="OLD"))
        self.assertTrue(any("physicalPlaceId drift" in error for error in result.errors))

    def test_missing_source_ledger_row_fails_closed(self):
        result = self.audit(lambda *docs: docs[4]["decisions"][0].pop("sourceLedgerRow"))
        self.assertTrue(any("sourceLedgerRow" in error for error in result.errors))

    def test_unclassified_city_only_mismatch_fails_closed(self):
        result = self.audit(lambda *docs: docs[4].update(decisions=[]))
        self.assertTrue(any("unclassified city-only" in error for error in result.errors))

    def test_stale_decision_fails_closed_when_expected_mismatch_disappears(self):
        def mutate(*docs):
            docs[2]["scenarios"][0]["assignments"][2]["ownerNationId"] = 1
        result = self.audit(mutate)
        self.assertTrue(any("stale decision" in error for error in result.errors))

    def test_city_bearing_degree_zero_province_requires_protection(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, scenarios, ledger, _source = docs
            tiles["provinceRecords"].append({"id": "PR3", "jurisdictionId": "J3", "parentRegionId": "R2"})
            runtime_map["cities"].append({"id": 3, "physicalPlaceId": "P3", "provinceId": 3, "connections": []})
            ownership["scenarios"][0]["assignments"].append({"provinceId": "PR3", "ownerNationId": 0})
            scenarios[1010]["nation"][0][8].append(3)
        result = self.audit(mutate)
        self.assertTrue(any("degree-zero city province" in error for error in result.errors))

    def test_neutral_external_degree_zero_place_is_not_a_destructive_supply_risk(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, _scenarios, _ledger, _source = docs
            tiles["provinceRecords"].append({"id": "PR3", "jurisdictionId": "J3", "parentRegionId": "R2"})
            runtime_map["cities"].append({"id": 3, "physicalPlaceId": "P3", "provinceId": 3, "connections": []})
            ownership["scenarios"][0]["assignments"].append({"provinceId": "PR3", "ownerNationId": 0})
        result = self.audit(mutate)
        self.assertFalse(any("degree-zero city province: city 3" in error for error in result.errors))


if __name__ == "__main__":
    unittest.main()
