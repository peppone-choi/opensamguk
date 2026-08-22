from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.map.tests.test_route_corridor_candidates import (
    first_row,
    generate_documents,
    validate_documents,
)


class RouteNetworkContractValidationTest(unittest.TestCase):
    def test_semantic_validation_rejects_minimum_degree_drift(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            corridor = documents["route-corridor-candidates-v1.json"]
            summary = corridor["summary"]
            assert isinstance(summary, dict)
            summary["minimumDegree"] = 2

            with self.assertRaisesRegex(ValueError, "minimum|summary"):
                validate_documents(documents)

    def test_semantic_validation_rejects_count_preserving_han_scenario_substitution(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            network = documents["route-network-contract-v1.json"]
            scenarios = network["hanMapContractScenarios"]
            assert isinstance(scenarios, dict)
            first_row(scenarios, "resources")["scenarioId"] = "9999"

            with self.assertRaisesRegex(ValueError, "scenario"):
                validate_documents(documents)

    def test_semantic_validation_rejects_han_scenario_lifecycle_scope_drift(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            network = documents["route-network-contract-v1.json"]
            scenarios = network["hanMapContractScenarios"]
            assert isinstance(scenarios, dict)
            scenarios["lifecycleValidationScope"] = "PARTIAL"

            with self.assertRaisesRegex(ValueError, "scenario|lifecycle"):
                validate_documents(documents)

    def test_semantic_validation_rejects_created_approval_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            network = documents["route-network-contract-v1.json"]
            approval = network["approvalBoundary"]
            assert isinstance(approval, dict)
            approval["approvedCorridorCount"] = 1
            approval["approvedSnapshot"] = "han-route-network-v1"

            with self.assertRaisesRegex(ValueError, "approval"):
                validate_documents(documents)

    def test_semantic_validation_rejects_network_contract_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            documents = generate_documents(Path(raw_dir))
            network = documents["route-network-contract-v1.json"]
            network["schemaVersion"] = 2
            network["contractId"] = "han-route-network-contract-v2"

            with self.assertRaisesRegex(ValueError, "schema|contract"):
                validate_documents(documents)


if __name__ == "__main__":
    unittest.main()
