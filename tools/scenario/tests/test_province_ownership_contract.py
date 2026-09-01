from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from tools.scenario.province_ownership_contract import (
    OwnershipContractError,
    parse_ownership_document,
)


FIXTURE = Path(__file__).parent / "fixtures/province_ownership/minimal_claims.json"


def fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def catalog() -> dict:
    return {
        "provinceIds": ["P-1", "P-2"],
        "parentRegionIds": ["R-1"],
        "provinceIdsByParent": {"R-1": ["P-1", "P-2"]},
    }


def scenarios(*, duplicate_han: bool = False) -> dict:
    nations = [{"id": 1, "name": "후한"}]
    if duplicate_han:
        nations.append({"id": 2, "name": "후한"})
    return {1010: {"nations": nations}}


class ProvinceOwnershipContractTest(unittest.TestCase):
    def test_parses_valid_document_and_resolves_stable_nation_key(self):
        parsed = parse_ownership_document(fixture(), catalog(), scenarios())

        self.assertEqual((1010,), parsed.active_scenario_codes)
        self.assertEqual(1, parsed.scenarios[1010].nation_ids["S1010-HAN"])
        self.assertEqual("S1010-BASELINE", parsed.scenarios[1010].claims[0].claim_id)

    def test_requires_one_unowned_baseline_per_active_scenario(self):
        raw = fixture()
        raw["scenarios"][0]["claims"] = raw["scenarios"][0]["claims"][1:]

        with self.assertRaisesRegex(OwnershipContractError, "MISSING_UNOWNED_BASELINE"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_rejects_duplicate_unowned_baseline(self):
        raw = fixture()
        duplicate = copy.deepcopy(raw["scenarios"][0]["claims"][0])
        duplicate["claimId"] = "S1010-BASELINE-2"
        raw["scenarios"][0]["claims"].append(duplicate)

        with self.assertRaisesRegex(OwnershipContractError, "DUPLICATE_UNOWNED_BASELINE"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_rejects_unknown_province_before_materialization(self):
        raw = fixture()
        raw["scenarios"][0]["claims"][1]["target"]["provinceIds"] = ["P-MISSING"]

        with self.assertRaisesRegex(OwnershipContractError, "UNKNOWN_PROVINCE"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_rejects_unknown_parent_region_before_materialization(self):
        raw = fixture()
        claim = raw["scenarios"][0]["claims"][1]
        claim["claimKind"] = "ADMIN_REGION_CONTROL"
        claim["target"] = {"parentRegionIds": ["R-MISSING"]}

        with self.assertRaisesRegex(OwnershipContractError, "UNKNOWN_PARENT_REGION"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_nation_name_join_must_resolve_exactly_once(self):
        with self.assertRaisesRegex(OwnershipContractError, "AMBIGUOUS_SCENARIO_NATION"):
            parse_ownership_document(fixture(), catalog(), scenarios(duplicate_han=True))

    def test_rejects_unknown_evidence_reference(self):
        raw = fixture()
        raw["scenarios"][0]["claims"][1]["evidenceIds"] = ["MISSING"]

        with self.assertRaisesRegex(OwnershipContractError, "UNKNOWN_EVIDENCE"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_rejects_if_claim_in_historical_scenario(self):
        raw = fixture()
        raw["scenarios"][0]["claims"][1]["claimKind"] = "IF_SCENARIO"

        with self.assertRaisesRegex(OwnershipContractError, "IF_CLAIM_IN_HISTORICAL_SCENARIO"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_temporal_carry_requires_a_known_inherited_claim_and_bounded_interval(self):
        raw = fixture()
        carry = copy.deepcopy(raw["scenarios"][0]["claims"][1])
        carry.update({
            "claimId": "S1010-P1-CARRY",
            "claimKind": "TEMPORAL_CARRY",
            "inheritsClaimId": "S1010-P1",
            "effectiveFrom": 184,
            "effectiveTo": 185,
        })
        raw["scenarios"][0]["claims"].append(carry)

        parsed = parse_ownership_document(raw, catalog(), scenarios())
        parsed_carry = parsed.scenarios[1010].claims[-1]
        self.assertEqual("S1010-P1", parsed_carry.inherits_claim_id)
        self.assertEqual((184, 185), (parsed_carry.effective_from, parsed_carry.effective_to))

        for field, value, error in (
            ("inheritsClaimId", "S1010-MISSING", "INVALID_TEMPORAL_CARRY"),
            ("effectiveFrom", 186, "INVALID_EFFECTIVE_INTERVAL"),
        ):
            with self.subTest(field=field):
                invalid = copy.deepcopy(raw)
                invalid["scenarios"][0]["claims"][-1][field] = value
                with self.assertRaisesRegex(OwnershipContractError, error):
                    parse_ownership_document(invalid, catalog(), scenarios())

    def test_temporal_carry_preserves_provenance_and_covers_the_scenario_year(self):
        raw = fixture()
        carry = copy.deepcopy(raw["scenarios"][0]["claims"][1])
        carry.update({
            "claimId": "S1010-P1-CARRY",
            "claimKind": "TEMPORAL_CARRY",
            "inheritsClaimId": "S1010-P1",
            "effectiveFrom": 184,
            "effectiveTo": 185,
        })
        raw["scenarios"][0]["nationRefs"].append({
            "nationKey": "S1010-OTHER",
            "scenarioNationName": "황건적",
        })
        raw["scenarios"][0]["claims"].append(carry)

        mutations = (
            ("year", lambda doc: doc["scenarios"][0].update(effectiveYear=183)),
            ("owner", lambda doc: doc["scenarios"][0]["claims"][-1].update(ownerNationKey="S1010-OTHER")),
            ("target", lambda doc: doc["scenarios"][0]["claims"][-1].update(target={"provinceIds": ["P-2"]})),
            ("evidence", lambda doc: doc["scenarios"][0]["claims"][-1].update(evidenceIds=["POLICY-UNOWNED"])),
        )
        for label, mutate in mutations:
            with self.subTest(label=label):
                invalid = copy.deepcopy(raw)
                mutate(invalid)
                with self.assertRaisesRegex(OwnershipContractError, "INVALID_TEMPORAL_CARRY"):
                    parse_ownership_document(invalid, catalog(), {1010: {"nations": [
                        {"id": 1, "name": "후한"},
                        {"id": 2, "name": "황건적"},
                    ]}})

    def test_temporal_carry_chain_must_be_acyclic(self):
        raw = fixture()
        first = copy.deepcopy(raw["scenarios"][0]["claims"][1])
        first.update({
            "claimId": "S1010-CARRY-A",
            "claimKind": "TEMPORAL_CARRY",
            "inheritsClaimId": "S1010-CARRY-B",
            "effectiveFrom": 184,
            "effectiveTo": 185,
        })
        second = copy.deepcopy(first)
        second.update({
            "claimId": "S1010-CARRY-B",
            "inheritsClaimId": "S1010-CARRY-A",
        })
        raw["scenarios"][0]["claims"].extend((first, second))

        with self.assertRaisesRegex(OwnershipContractError, "TEMPORAL_CARRY_CYCLE"):
            parse_ownership_document(raw, catalog(), scenarios())

    def test_allowlist_cannot_use_wildcard_province_ids(self):
        raw = fixture()
        raw["scenarios"][0]["auditAllowlist"] = [{
            "scenarioCode": 1010,
            "auditKind": "UNEXPLAINED_ENCLAVE",
            "provinceIds": ["*"],
            "evidenceIds": ["HIST-P1"],
            "rationale": "Wildcards would hide unrelated findings.",
            "reviewState": "APPROVED",
        }]

        with self.assertRaisesRegex(OwnershipContractError, "ALLOWLIST_WILDCARD_FORBIDDEN"):
            parse_ownership_document(raw, catalog(), scenarios())


if __name__ == "__main__":
    unittest.main()
