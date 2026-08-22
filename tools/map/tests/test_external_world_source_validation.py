from __future__ import annotations

import unittest

from tools.map.external_world_contract import (
    ContractError,
    GeneratedDocuments,
    JsonObject,
    load_context,
    require_array,
    require_object,
)
from tools.map.external_world_validation import validate_documents
from tools.map.external_world_source_validation import validate_source_binding
from tools.map.tests.test_external_world_contract_validation import (
    ROOT,
    documents,
    pack,
)


def entity_by_name(generated: GeneratedDocuments, pack_id: str, name: str) -> JsonObject:
    return next(require_object(row, "entity") for row in require_array(pack(generated, pack_id), "entities") if require_object(row, "entity").get("canonicalName") == name)


class ExternalWorldSourceValidationTest(unittest.TestCase):
    def test_rejects_claim_value_and_source_ref_swaps(self) -> None:
        generated = documents()
        claims = [require_object(row, "claim") for row in require_array(pack(generated, "western-regions"), "claims")]
        claims[0]["value"], claims[1]["value"] = claims[1]["value"], claims[0]["value"]
        with self.assertRaisesRegex(ContractError, "claim|value|binding"):
            validate_source_binding(generated, load_context(ROOT))

        generated = documents()
        claims = [require_object(row, "claim") for row in require_array(pack(generated, "western-regions"), "claims")]
        claims[0]["sourceRef"], claims[2]["sourceRef"] = claims[2]["sourceRef"], claims[0]["sourceRef"]
        with self.assertRaisesRegex(ContractError, "claim|source|binding"):
            validate_source_binding(generated, load_context(ROOT))

    def test_rejects_forged_cross_subject_and_orphan_claims(self) -> None:
        generated = documents()
        claims = [require_object(row, "claim") for row in require_array(pack(generated, "western-regions"), "claims")]
        claims[0]["value"] = "完全捏造"
        with self.assertRaisesRegex(ContractError, "claim|citation|binding"):
            validate_source_binding(generated, load_context(ROOT))

        generated = documents()
        entities = [require_object(row, "entity") for row in require_array(pack(generated, "western-regions"), "entities")]
        entities[0]["claimRefs"], entities[1]["claimRefs"] = entities[1]["claimRefs"], entities[0]["claimRefs"]
        with self.assertRaisesRegex(ContractError, "claim|subject|binding"):
            validate_source_binding(generated, load_context(ROOT))

        generated = documents()
        claims = require_array(pack(generated, "western-regions"), "claims")
        forged = dict(require_object(claims[0], "claim"))
        forged["claimId"] = "claim:western-regions:orphan"
        claims.append(forged)
        with self.assertRaisesRegex(ContractError, "orphan|claim|binding"):
            validate_source_binding(generated, load_context(ROOT))

    def test_rejects_period_sentinel_and_source_grade_mutations(self) -> None:
        generated = documents()
        claim = require_object(require_array(pack(generated, "western-regions"), "claims")[0], "claim")
        require_object(claim.get("subjectPeriod"), "subject period")["from"] = 9999
        with self.assertRaisesRegex(ContractError, "period|9999|binding"):
            validate_source_binding(generated, load_context(ROOT))

        generated = documents()
        claim = require_object(require_array(pack(generated, "western-regions"), "claims")[1], "claim")
        claim["evidenceClass"] = "SCHOLARLY_RECONSTRUCTION"
        claim["sourceProximity"] = "OFFICIAL_HISTORY"
        with self.assertRaisesRegex(ContractError, "evidence|proximity|grade|binding"):
            validate_source_binding(generated, load_context(ROOT))

    def test_source_unknown_legacy_names_have_no_claims(self) -> None:
        generated = documents()
        for name in ("悉直國", "押督國", "召文國", "于山國", "目支國"):
            with self.subTest(name=name):
                entity = entity_by_name(generated, "east-sea-wa", name)
                self.assertEqual("INACTIVE_SOURCE_UNKNOWN", entity.get("reviewState"))
                self.assertEqual([], require_array(entity, "claimRefs"))

    def test_rejects_source_path_sha_line_and_verbatim_mutations(self) -> None:
        mutations = (("path", "../escape.txt"), ("sha256", "0" * 64), ("lineStart", 1), ("verbatim", "fabricated"))
        for field, value in mutations:
            with self.subTest(field=field):
                generated = documents()
                source = require_object(require_array(pack(generated, "east-sea-wa"), "sourceRegistry")[0], "source")
                source[field] = value

                with self.assertRaisesRegex(ContractError, "source|path|SHA|line|verbatim"):
                    validate_documents(generated, load_context(ROOT))

    def test_rejects_scenario_deletion_and_early_activation(self) -> None:
        generated = documents()
        daifang = entity_by_name(generated, "northeast", "帶方郡")
        require_array(daifang, "scenarioStates").pop()
        with self.assertRaisesRegex(ContractError, "scenario|31|lifecycle"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        daifang = entity_by_name(generated, "northeast", "帶方郡")
        require_object(require_array(daifang, "scenarioStates")[0], "scenario state")["state"] = "ACTIVE"
        with self.assertRaisesRegex(ContractError, "activation|lifecycle|INACTIVE"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        funan = entity_by_name(generated, "southern-maritime", "扶南")
        lifecycle = require_object(funan.get("lifecycle"), "lifecycle")
        lifecycle["status"] = "UNKNOWN"
        lifecycle["effectiveFrom"] = None
        for raw_state in require_array(funan, "scenarioStates"):
            require_object(raw_state, "scenario state")["reason"] = "UNKNOWN_LIFECYCLE_FAIL_CLOSED"
        with self.assertRaisesRegex(ContractError, "lifecycle|245|扶南"):
            validate_documents(generated, load_context(ROOT))

    def test_rejects_point_yizhou_and_anchored_mobile_polities(self) -> None:
        generated = documents()
        entity_by_name(generated, "southern-maritime", "夷洲")["locationStatus"] = "POINT"
        with self.assertRaisesRegex(ContractError, "夷洲|location|point"):
            validate_documents(generated, load_context(ROOT))

        for pack_id, name in (("western-regions", "西羌"), ("northern-steppe", "鮮卑"), ("northern-steppe", "南匈奴")):
            with self.subTest(name=name):
                generated = documents()
                entity_by_name(generated, pack_id, name)["entityType"] = "AnchoredPlace"
                with self.assertRaisesRegex(ContractError, "mobile|PolityPresence|anchored"):
                    validate_documents(generated, load_context(ROOT))

    def test_rejects_itinerary_order_deletion_and_direct_corridor(self) -> None:
        for mutation in ("swap", "delete", "corridor"):
            with self.subTest(mutation=mutation):
                generated = documents()
                itinerary = require_object(require_array(pack(generated, "east-sea-wa"), "relativeItineraries")[0], "itinerary")
                sequence = require_array(itinerary, "sequence")
                if mutation == "swap":
                    sequence[0], sequence[1] = sequence[1], sequence[0]
                elif mutation == "delete":
                    sequence.pop()
                else:
                    require_array(itinerary, "corridorRefs").append("direct-corridor")
                    itinerary["traversable"] = True
                with self.assertRaisesRegex(ContractError, "itinerary|corridor|sequence"):
                    validate_documents(generated, load_context(ROOT))

    def test_rejects_active_later_gaya_and_liuqiu_aliases(self) -> None:
        for name in ("大伽耶", "星山伽耶", "古寧伽耶", "流求"):
            with self.subTest(name=name):
                generated = documents()
                entity = entity_by_name(generated, "east-sea-wa" if name != "流求" else "southern-maritime", name)
                entity["reviewState"] = "APPROVED"
                require_object(require_array(entity, "scenarioStates")[0], "scenario state")["state"] = "ACTIVE"
                with self.assertRaisesRegex(ContractError, "alias|inactive|Gaya|流求"):
                    validate_documents(generated, load_context(ROOT))

    def test_rejects_merged_yamatai_variant_and_xianbei_entities(self) -> None:
        generated = documents()
        claims = require_array(pack(generated, "east-sea-wa"), "claims")
        claims[:] = [claim for claim in claims if require_object(claim, "claim").get("value") != "邪馬臺國"]
        with self.assertRaisesRegex(ContractError, "variant|邪馬"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        require_array(pack(generated, "northern-steppe"), "entities").append({"canonicalName": "鮮卑東部"})
        with self.assertRaisesRegex(ContractError, "鮮卑|division|entity|schema"):
            validate_documents(generated, load_context(ROOT))


if __name__ == "__main__":
    unittest.main()
