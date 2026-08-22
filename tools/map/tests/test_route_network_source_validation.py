from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from tools.map.tests.test_route_corridor_candidates import (
    ROOT,
    SCRIPT,
    first_row,
    generate_documents,
    validate_documents,
)


class RouteNetworkSourceValidationTest(unittest.TestCase):
    def test_cli_rejects_missing_canonical_registry(self) -> None:
        # Given: an isolated output directory without the committed canonical registry.
        with tempfile.TemporaryDirectory() as raw_dir:
            # When: normal generation is attempted after the historical bootstrap is closed.
            result = subprocess.run([sys.executable, str(SCRIPT), "--output-dir", raw_dir], cwd=ROOT, check=False, capture_output=True, text=True)

            # Then: the CLI fails closed instead of assigning new UUIDs.
            self.assertEqual(2, result.returncode)
            self.assertRegex(result.stderr, "canonical corridor registry")

    def test_rejects_coherent_registry_uuid_rotation(self) -> None:
        # Given: every registry UUID is independently rotated and candidate references follow it.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            registry = documents["route-corridor-key-registry-v1.json"]["entries"]
            candidates = documents["route-corridor-candidates-v1.json"]["candidates"]
            assert isinstance(registry, list) and isinstance(candidates, list)
            for identity, candidate in zip(registry, candidates, strict=True):
                assert isinstance(identity, dict) and isinstance(candidate, dict)
                rotated = str(uuid4())
                identity["corridorKey"] = rotated
                candidate["corridorKey"] = rotated

            # When/Then: internal coherence cannot replace the canonical registry identity pin.
            with self.assertRaisesRegex(ValueError, "registry|SHA|identity"):
                validate_documents(documents)

    def test_rejects_coherent_registry_pair_key_swap(self) -> None:
        # Given: two registry keys and their candidate references are coherently exchanged.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            registry = documents["route-corridor-key-registry-v1.json"]["entries"]
            candidates = documents["route-corridor-candidates-v1.json"]["candidates"]
            assert isinstance(registry, list) and isinstance(candidates, list)
            first_identity, second_identity = registry[:2]
            first_candidate, second_candidate = candidates[:2]
            assert isinstance(first_identity, dict) and isinstance(second_identity, dict)
            assert isinstance(first_candidate, dict) and isinstance(second_candidate, dict)
            first_identity["corridorKey"], second_identity["corridorKey"] = second_identity["corridorKey"], first_identity["corridorKey"]
            first_candidate["corridorKey"], second_candidate["corridorKey"] = second_candidate["corridorKey"], first_candidate["corridorKey"]

            # When/Then: pair-key coherence still cannot drift from the canonical registry SHA.
            with self.assertRaisesRegex(ValueError, "registry|SHA|identity"):
                validate_documents(documents)

    def test_rejects_unknown_corridor_approval_lifecycle_and_revision_fields(self) -> None:
        # Given: a candidate gains one W1-B-style disposition, lifecycle, or revision field.
        mutations = (("disposition", "APPROVE"), ("lifecycle", {}), ("revision", 1), ("approvalDisposition", "APPROVE"))
        for field, value in mutations:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw_dir:
                documents = generate_documents(Path(raw_dir))
                first_row(documents["route-corridor-candidates-v1.json"], "candidates")[field] = value

                # When/Then: exact candidate shape rejects every unknown approval surface.
                with self.assertRaisesRegex(ValueError, "schema|key|candidate"):
                    validate_documents(documents)

    def test_rejects_external_type_adjudication_and_lifecycle_mutation(self) -> None:
        # Given: an external candidate is classified, adjudicated, and given altered active bounds.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            candidate = first_row(documents["external-world-candidates-v1.json"], "candidates")
            candidate["entityType"] = "AnchoredPlace"
            candidate["locationAdjudication"] = {"status": "APPROVED"}
            candidate["lifecycle"] = {"effectiveFrom": 180, "effectiveTo": 220}
            raw = candidate["rawLegacy"]
            assert isinstance(raw, dict)
            raw["begYr"] = 180

            # When/Then: W1-A external rows remain exact source quarantine candidates only.
            with self.assertRaisesRegex(ValueError, "external|schema|source"):
                validate_documents(documents)

    def test_rejects_endpoint_legacy_city_id_swap(self) -> None:
        # Given: endpoint route keys stay fixed while their legacy city provenance IDs are swapped.
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            candidate = first_row(documents["route-corridor-candidates-v1.json"], "candidates")
            provenance = candidate["legacyTopologyProvenance"]
            assert isinstance(provenance, dict)
            endpoints = provenance["endpoints"]
            assert isinstance(endpoints, list) and len(endpoints) == 2
            first, second = endpoints
            assert isinstance(first, dict) and isinstance(second, dict)
            first["legacyCityId"], second["legacyCityId"] = second["legacyCityId"], first["legacyCityId"]

            # When/Then: exact W0 endpoint binding rejects internally plausible ID provenance.
            with self.assertRaisesRegex(ValueError, "endpoint|source|provenance"):
                validate_documents(documents)

    def test_rejects_scenario_path_sha_and_start_year_mutations(self) -> None:
        # Given: one source-backed scenario field changes without altering the 31 scenario IDs.
        mutations = (("resourcePath", "infra/src/main/resources/scenario/9999.json"), ("sha256", "0" * 64), ("startYear", 9999))
        for field, value in mutations:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw_dir:
                documents = generate_documents(Path(raw_dir))
                network = documents["route-network-contract-v1.json"]
                scenarios = network["hanMapContractScenarios"]
                assert isinstance(scenarios, dict)
                first_row(scenarios, "resources")[field] = value

                # When/Then: full scenario resource equality rejects coherent ID-preserving drift.
                with self.assertRaisesRegex(ValueError, "scenario|source"):
                    validate_documents(documents)


if __name__ == "__main__":
    unittest.main()
