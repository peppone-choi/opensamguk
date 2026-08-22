from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import TypeAlias
from uuid import NAMESPACE_URL, UUID, uuid5

from tools.map import build_route_corridor_candidates as builder

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "tools/map/build_route_corridor_candidates.py"
CANONICAL_REGISTRY = ROOT / "data/curated/han/route-corridor-key-registry-v1.json"
JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
GeneratedDocuments: TypeAlias = dict[str, JsonObject]


def load_json(path: Path) -> JsonObject:
    return builder.load_object(path)


def generate_documents(output_dir: Path) -> GeneratedDocuments:
    _ = shutil.copyfile(CANONICAL_REGISTRY, output_dir / CANONICAL_REGISTRY.name)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--output-dir", str(output_dir)],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    return {
        name: load_json(output_dir / name)
        for name in (
            "route-network-contract-v1.json",
            "route-corridor-key-registry-v1.json",
            "route-corridor-candidates-v1.json",
            "external-world-candidates-v1.json",
        )
    }


def first_row(document: JsonObject, key: str) -> JsonObject:
    rows = document[key]
    assert isinstance(rows, list) and rows
    row = rows[0]
    assert isinstance(row, dict)
    return row


def validate_documents(documents: GeneratedDocuments) -> None:
    paths = builder.InputPaths(ROOT, builder.DEFAULT_HAN, builder.DEFAULT_SELECTION, builder.DEFAULT_EXTERNAL, builder.DEFAULT_SERVICE)
    source = builder.load_sources(paths)
    builder.validate_documents(documents, source, builder.build_topology(source))


class RouteCorridorCandidateCliTest(unittest.TestCase):
    def test_real_sources_generate_pending_candidate_contracts_and_replay_without_drift(self) -> None:
        # Given: an empty isolated output directory and the repository's reviewed W0 inputs.
        with tempfile.TemporaryDirectory() as raw_dir:
            output_dir = Path(raw_dir)
            _ = shutil.copyfile(CANONICAL_REGISTRY, output_dir / CANONICAL_REGISTRY.name)

            # When: the W1-A CLI materializes the candidate-only artifacts, then checks them.
            generated = subprocess.run(
                [sys.executable, str(SCRIPT), "--output-dir", str(output_dir)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            first_hashes = {
                path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in output_dir.glob("*.json")
            }
            checked = subprocess.run(
                [sys.executable, str(SCRIPT), "--output-dir", str(output_dir), "--check"],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )

            # Then: exact pending inventories exist and the second build is byte-stable.
            self.assertEqual(0, generated.returncode, generated.stderr)
            self.assertEqual(0, checked.returncode, checked.stderr)
            corridor = load_json(output_dir / "route-corridor-candidates-v1.json")
            registry = load_json(output_dir / "route-corridor-key-registry-v1.json")
            external = load_json(output_dir / "external-world-candidates-v1.json")
            contract = load_json(output_dir / "route-network-contract-v1.json")

            self.assertEqual(1783, corridor["summary"]["candidateCount"])
            self.assertEqual({"PENDING"}, {row["reviewState"] for row in corridor["candidates"]})
            self.assertEqual(
                "2c3ca02f316ee409e1045e1eda8d57c0aa048b9b21a3ec868595f5b5ac0032cf",
                corridor["provenance"]["numericUndirectedEdgeSha256"],
            )
            self.assertEqual(
                "0a9742971f63840e96291979ba3e900d8f07f2aecd681025ab017fb920144377",
                corridor["provenance"]["routeKeyUndirectedEdgeSha256"],
            )
            forbidden = {"mode", "modes", "geometry", "geometryRef", "sourceClaims", "supportBasis"}
            self.assertFalse(any(forbidden.intersection(row) for row in corridor["candidates"]))

            keys = [row["corridorKey"] for row in registry["entries"]]
            self.assertEqual(1783, len(keys))
            self.assertEqual(1783, len(set(keys)))
            self.assertTrue(all(UUID(value).version == 4 for value in keys))

            self.assertEqual(65, external["summary"]["candidateCount"])
            self.assertEqual({"PENDING"}, {row["reviewState"] for row in external["candidates"]})
            self.assertTrue(
                all(
                    row["legacyLifecycleStatus"] == "UNBOUNDED_DEFAULT_REQUIRES_REVIEW"
                    and row["runtimeActivation"] == "NOT_CLAIMED_BY_W1_DATA_CONTRACT"
                    for row in external["candidates"]
                )
            )

            self.assertEqual(15, contract["activeProductScenarios"]["count"])
            self.assertEqual(31, contract["hanMapContractScenarios"]["count"])
            self.assertEqual(
                [
                    "ROAD",
                    "PASS",
                    "BRIDGE",
                    "FORD",
                    "WATERWAY",
                    "SEA_ROUTE",
                    "STEPPE_CORRIDOR",
                ],
                contract["allowedCorridorModes"],
            )

            second_hashes = {
                path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in output_dir.glob("*.json")
            }
            self.assertEqual(first_hashes, second_hashes)

    def test_semantic_validation_rejects_automatic_geography_and_approval_after_rehash(self) -> None:
        # Given: a generated bundle mutated into a coherent-looking approved road candidate.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            corridor = documents["route-corridor-candidates-v1.json"]
            candidate = first_row(corridor, "candidates")
            candidate["reviewState"] = "APPROVED"
            candidate["modes"] = ["ROAD"]
            summary = corridor["summary"]
            assert isinstance(summary, dict)
            summary["reviewStateCounts"] = {"APPROVED": 1, "PENDING": 1782}
            hashlib.sha256(builder.serialize(corridor).encode()).hexdigest()

            # When/Then: semantic validation rejects the promotion independent of file hashes.
            with self.assertRaisesRegex(ValueError, "PENDING|mode|geography"):
                validate_documents(documents)

    def test_semantic_validation_rejects_candidate_runtime_activation_drift(self) -> None:
        # Given: a corridor inventory claiming live runtime activation.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            documents["route-corridor-candidates-v1.json"]["runtimeActivation"] = "ACTIVE"

            # When/Then: candidate data cannot cross the W1 runtime boundary.
            with self.assertRaisesRegex(ValueError, "runtime"):
                validate_documents(documents)

    def test_semantic_validation_rejects_replacement_risk_summary_drift(self) -> None:
        # Given: a replacement-risk summary no longer matching its candidate rows.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            corridor = documents["route-corridor-candidates-v1.json"]
            summary = corridor["summary"]
            assert isinstance(summary, dict)
            summary["replacementRiskCounts"] = {
                "NO_REPLACEMENT_ENDPOINT": 1445,
                "AT_LEAST_ONE_REPLACED_ENDPOINT": 338,
                "BOTH_ENDPOINTS_REPLACED": 53,
            }

            # When/Then: row-derived replacement exposure remains authoritative.
            with self.assertRaisesRegex(ValueError, "replacement"):
                validate_documents(documents)

    def test_semantic_validation_rejects_removed_endpoint_provenance(self) -> None:
        # Given: one corridor no longer carries endpoint-specific W0 provenance.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            candidate = first_row(documents["route-corridor-candidates-v1.json"], "candidates")
            provenance = candidate["legacyTopologyProvenance"]
            assert isinstance(provenance, dict)
            provenance.pop("endpoints", [])

            # When/Then: endpoint fingerprint and disposition provenance is mandatory.
            with self.assertRaisesRegex(ValueError, "endpoint|fingerprint|disposition"):
                validate_documents(documents)

    def test_semantic_validation_rejects_uuid5_even_when_candidate_reference_matches(self) -> None:
        # Given: a registry key derived with UUIDv5 and copied into its candidate row.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            registry_row = first_row(documents["route-corridor-key-registry-v1.json"], "entries")
            candidate_row = first_row(documents["route-corridor-candidates-v1.json"], "candidates")
            derived = str(uuid5(NAMESPACE_URL, "derived-corridor"))
            registry_row["corridorKey"] = derived
            candidate_row["corridorKey"] = derived

            # When/Then: matching references cannot legitimize a derived corridor identity.
            with self.assertRaisesRegex(ValueError, "UUIDv4|independent|registry|identity"):
                validate_documents(documents)

    def test_semantic_validation_rejects_missing_han_contract_scenario(self) -> None:
        # Given: a contract whose declared count was coherently reduced with its resource list.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            contract = documents["route-network-contract-v1.json"]
            scenarios = contract["hanMapContractScenarios"]
            assert isinstance(scenarios, dict)
            resources = scenarios["resources"]
            assert isinstance(resources, list)
            scenarios["resources"] = resources[:-1]
            scenarios["count"] = 30

            # When/Then: all 31 W0-pinned resources remain mandatory.
            with self.assertRaisesRegex(ValueError, "31"):
                validate_documents(documents)

    def test_semantic_validation_rejects_active_external_unbounded_lifecycle(self) -> None:
        # Given: an external row changed from review-only legacy bounds to active lifecycle.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            candidate = first_row(documents["external-world-candidates-v1.json"], "candidates")
            candidate["legacyLifecycleStatus"] = "ACTIVE"
            candidate["runtimeActivation"] = "ACTIVE"

            # When/Then: W1-A cannot activate or reinterpret an unreviewed external row.
            with self.assertRaisesRegex(ValueError, "external|runtime|lifecycle"):
                validate_documents(documents)

    def test_semantic_validation_rejects_endpoint_rule_drift(self) -> None:
        # Given: an administrative place was promoted to a direct corridor endpoint.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            contract = documents["route-network-contract-v1.json"]
            endpoint_rules = contract["corridorEndpointRules"]
            assert isinstance(endpoint_rules, dict)
            endpoint_rules["AdministrativePlace"] = "ALLOWED"

            # When/Then: the W1 endpoint boundary remains exact.
            with self.assertRaisesRegex(ValueError, "endpoint"):
                validate_documents(documents)

    def test_semantic_validation_rejects_infrastructure_defaults(self) -> None:
        # Given: a balance default was inserted into the type-only infrastructure schema.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            contract = documents["route-network-contract-v1.json"]
            schema = contract["infrastructureStateSchema"]
            assert isinstance(schema, dict)
            fields = schema["fields"]
            assert isinstance(fields, dict)
            grade = fields["grade"]
            assert isinstance(grade, dict)
            grade["default"] = 0

            # When/Then: W1-A defines field types without inventing live values.
            with self.assertRaisesRegex(ValueError, "default|infrastructure"):
                validate_documents(documents)

    def test_semantic_validation_rejects_product_scenario_code_substitution(self) -> None:
        # Given: the 15-count was preserved while one Gateway scenario code was substituted.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            contract = documents["route-network-contract-v1.json"]
            active = contract["activeProductScenarios"]
            assert isinstance(active, dict)
            scenario_ids = active["scenarioIds"]
            service_codes = active["serviceCodes"]
            assert isinstance(scenario_ids, list) and isinstance(service_codes, list)
            scenario_ids[0] = "9999"
            service_codes[0] = "scenario_9999"

            # When/Then: count-equivalent substitution cannot drift from ScenarioCatalogService.
            with self.assertRaisesRegex(ValueError, "product scenario"):
                validate_documents(documents)

    def test_semantic_validation_rejects_missing_external_entity_type(self) -> None:
        # Given: one of the four reviewed external entity types was removed.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            contract = documents["route-network-contract-v1.json"]
            entity_types = contract["externalEntityTypes"]
            assert isinstance(entity_types, list)
            contract["externalEntityTypes"] = entity_types[:-1]

            # When/Then: the external-world type boundary remains exhaustive.
            with self.assertRaisesRegex(ValueError, "external entity"):
                validate_documents(documents)

    def test_semantic_validation_rejects_corridor_key_pair_swaps(self) -> None:
        # Given: two valid UUIDv4 keys were swapped between otherwise valid endpoint pairs.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            corridor = documents["route-corridor-candidates-v1.json"]
            candidates = corridor["candidates"]
            assert isinstance(candidates, list) and len(candidates) >= 2
            first = candidates[0]
            second = candidates[1]
            assert isinstance(first, dict) and isinstance(second, dict)
            first["corridorKey"], second["corridorKey"] = second["corridorKey"], first["corridorKey"]

            # When/Then: key uniqueness cannot hide canonical pair-to-key drift.
            with self.assertRaisesRegex(ValueError, "registry|pair"):
                validate_documents(documents)


if __name__ == "__main__":
    unittest.main()
