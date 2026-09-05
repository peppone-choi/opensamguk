import copy
import unittest

from tools.scenario.audit_han_supply_disagreements import audit_documents, audit_repository


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
        "rationale": "Reviewed geometry defect remains protected.",
        "expectedCurrentReachability": "CITY_ONLY",
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

    def test_different_scenario_identity_domain_is_rejected_before_numeric_audit(self):
        result = self.audit(
            lambda *docs: docs[3][1010].update(map={"mapName": "han-world-v3"})
        )
        self.assertEqual([
            "scenario 1010 mapName 'han-world-v3' does not match runtime map 'han'"
        ], result.errors)
        self.assertEqual([], result.rows)
        self.assertEqual({}, result.summaries)

    def test_frozen_v2_scenarios_share_the_legacy_han_identity_domain(self):
        result = self.audit(
            lambda *docs: docs[3][1010].update(map={"mapName": "han-world-v2"})
        )
        self.assertEqual([], result.errors)

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

    def test_missing_rationale_and_expected_reachability_fail_closed(self):
        def mutate(*docs):
            docs[4]["decisions"][0].pop("rationale")
            docs[4]["decisions"][0].pop("expectedCurrentReachability")
        result = self.audit(mutate)
        self.assertTrue(any("rationale" in error for error in result.errors))
        self.assertTrue(any("expectedCurrentReachability" in error for error in result.errors))

    def test_decision_must_match_source_ledger_verdict(self):
        result = self.audit(
            lambda *docs: docs[4]["decisions"][0].update(decision="UPHOLD_HISTORICAL_EXCLAVE")
        )
        self.assertTrue(any("does not match source verdict" in error for error in result.errors))

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

    def test_degree_zero_protection_must_be_active_for_the_owned_scenario(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, scenarios, ledger, source = docs
            tiles["provinceRecords"].append({"id": "PR3", "jurisdictionId": "J3", "parentRegionId": "R2"})
            runtime_map["cities"].append({"id": 3, "physicalPlaceId": "P3", "provinceId": 3, "connections": []})
            ownership["scenarios"][0]["assignments"].append({"provinceId": "PR3", "ownerNationId": 0})
            scenarios[1010]["nation"][0][8].append(3)
            source["adjudications"].append({
                "componentKey": "R2@1:1", "unitId": "R2", "memberIds": ["J3"],
                "verdict": "GEOMETRY_DEFECT",
            })
            ledger["decisions"].append({
                "runtimeCityId": 3,
                "physicalPlaceId": "P3",
                "jurisdictionId": "J3",
                "decision": "PROTECT_GEOMETRY_DEFECT",
                "sourceLedgerRow": "R2@1:1",
                "effectiveScenarioFrom": 1020,
                "effectiveScenarioTo": 1020,
            })
        result = self.audit(mutate)
        self.assertTrue(any("degree-zero city province" in error for error in result.errors))

    def test_degree_zero_uphold_is_not_protection(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, scenarios, ledger, source = docs
            tiles["provinceRecords"].append({"id": "PR3", "jurisdictionId": "J3", "parentRegionId": "R2"})
            runtime_map["cities"].append({"id": 3, "physicalPlaceId": "P3", "provinceId": 3, "connections": []})
            ownership["scenarios"][0]["assignments"].append({"provinceId": "PR3", "ownerNationId": 0})
            scenarios[1010]["nation"][0][8].append(3)
            source["adjudications"].append({
                "componentKey": "R2@1:1", "unitId": "R2", "memberIds": ["J3"],
                "verdict": "HISTORICAL_EXCLAVE",
            })
            ledger["decisions"].append({
                "runtimeCityId": 3,
                "physicalPlaceId": "P3",
                "jurisdictionId": "J3",
                "decision": "UPHOLD_HISTORICAL_EXCLAVE",
                "sourceLedgerRow": "R2@1:1",
                "effectiveScenarioFrom": 1010,
                "effectiveScenarioTo": 1010,
            })
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

    def test_v3_degree_zero_protect_is_bound_by_runtime_physical_and_route_identity(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, scenarios, ledger, source = docs
            tiles["provinceRecords"].append(
                {"id": "P3", "jurisdictionId": "J3", "parentRegionId": "R2"}
            )
            runtime_map["_meta"] = {"map": "han-world-v3"}
            runtime_map["cities"].append({
                "id": 3,
                "physicalPlaceRef": "chgis:v6:cnty:P3",
                "routeNodeKey": "route-3",
                "provinceId": 3,
                "connections": [],
            })
            ownership["scenarios"][0]["assignments"].append(
                {"provinceId": "P3", "ownerNationId": 0}
            )
            ownership["scenarios"][0]["assignments"][2]["ownerNationId"] = 1
            scenarios[1010]["nation"][0][8].append(3)
            source["adjudications"].append({
                "componentKey": "R2@1:1",
                "unitId": "R2",
                "memberIds": ["J3"],
                "verdict": "GEOMETRY_DEFECT",
            })
            ledger.clear()
            ledger.update({
                "schemaVersion": 2,
                "worldVersion": "han-world-v3",
                "decisions": [{
                    "runtimeCityId": 3,
                    "physicalPlaceRef": "chgis:v6:cnty:P3",
                    "routeNodeKey": "route-3",
                    "jurisdictionId": "J3",
                    "decision": "PROTECT_GEOMETRY_DEFECT",
                    "sourceLedgerRow": "R2@1:1",
                    "rationale": "Reviewed degree-zero geometry defect remains protected.",
                    "expectedCurrentReachability": "BOTH_UNSUPPLIED",
                    "effectiveScenarioFrom": 1010,
                    "effectiveScenarioTo": 1010,
                }],
            })

        result = self.audit(mutate)

        self.assertEqual([], result.errors)
        protected = [row for row in result.rows if row["runtimeCityId"] == 3]
        self.assertEqual(1, len(protected))
        self.assertEqual("BOTH_UNSUPPLIED_PROTECTED", protected[0]["verdict"])

    def test_v3_route_identity_drift_fails_closed(self):
        def mutate(*docs):
            _tiles, runtime_map, _ownership, _scenarios, ledger, _source = docs
            runtime_map["_meta"] = {"map": "han-world-v3"}
            runtime_map["cities"][1].update(
                physicalPlaceRef="chgis:v6:cnty:P2", routeNodeKey="route-2"
            )
            ledger.update(schemaVersion=2, worldVersion="han-world-v3")
            ledger["decisions"][0].pop("physicalPlaceId")
            ledger["decisions"][0].update(
                physicalPlaceRef="chgis:v6:cnty:P2",
                routeNodeKey="wrong-route",
            )

        result = self.audit(mutate)
        self.assertTrue(any("routeNodeKey drift" in error for error in result.errors))

    def test_v3_policy_is_stale_when_raw_reachability_changes_from_its_declared_class(self):
        def mutate(*docs):
            tiles, runtime_map, ownership, scenarios, ledger, source = docs
            tiles["provinceRecords"].append(
                {"id": "P3", "jurisdictionId": "J3", "parentRegionId": "R2"}
            )
            runtime_map["_meta"] = {"map": "han-world-v3"}
            runtime_map["cities"][0]["connections"].append(3)
            runtime_map["cities"].append({
                "id": 3,
                "physicalPlaceRef": "chgis:v6:cnty:P3",
                "routeNodeKey": "route-3",
                "provinceId": 3,
                "connections": [1],
            })
            ownership["scenarios"][0]["assignments"].append(
                {"provinceId": "P3", "ownerNationId": 0}
            )
            ownership["scenarios"][0]["assignments"][2]["ownerNationId"] = 1
            scenarios[1010]["nation"][0][8].append(3)
            source["adjudications"].append({
                "componentKey": "R2@1:1",
                "unitId": "R2",
                "memberIds": ["J3"],
                "verdict": "GEOMETRY_DEFECT",
            })
            ledger.clear()
            ledger.update({
                "schemaVersion": 2,
                "worldVersion": "han-world-v3",
                "decisions": [{
                    "runtimeCityId": 3,
                    "physicalPlaceRef": "chgis:v6:cnty:P3",
                    "routeNodeKey": "route-3",
                    "jurisdictionId": "J3",
                    "decision": "PROTECT_GEOMETRY_DEFECT",
                    "sourceLedgerRow": "R2@1:1",
                    "rationale": "Reviewed degree-zero geometry defect remains protected.",
                    "expectedCurrentReachability": "BOTH_UNSUPPLIED",
                    "effectiveScenarioFrom": 1010,
                    "effectiveScenarioTo": 1010,
                }],
            })

        result = self.audit(mutate)

        self.assertTrue(any("does not match BOTH_UNSUPPLIED" in error for error in result.errors))
        city = next(row for row in result.rows if row["runtimeCityId"] == 3)
        self.assertEqual("CITY_ONLY_PROTECTED", city["verdict"])
        self.assertEqual(None, city["decision"])

    def test_committed_v3_domain_has_only_reviewed_degree_zero_protections(self):
        result = audit_repository("han-world-v3")

        self.assertEqual([], result.errors)
        self.assertEqual(15, len(result.summaries))
        protected = {
            (row["runtimeCityId"], row.get("physicalPlaceRef"), row["verdict"])
            for row in result.rows
            if row.get("decision") == "PROTECT_GEOMETRY_DEFECT"
        }
        self.assertEqual({
            (305, "chgis:v6:cnty:43252", "BOTH_UNSUPPLIED_PROTECTED"),
            (548, "chgis:v6:cnty:40740", "BOTH_UNSUPPLIED_PROTECTED"),
        }, protected)
        self.assertFalse(any(row["runtimeCityId"] == 364 and row.get("decision") for row in result.rows))


if __name__ == "__main__":
    unittest.main()
