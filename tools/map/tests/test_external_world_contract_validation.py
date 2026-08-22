from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.map.build_external_world_pack_index import ROOT, build_documents
from tools.map.external_world_contract import (
    ContractError,
    GeneratedDocuments,
    JsonObject,
    load_context,
    require_array,
    require_object,
    serialize,
)
from tools.map.external_world_validation import validate_documents

SCRIPT = ROOT / "tools/map/build_external_world_pack_index.py"


def documents() -> GeneratedDocuments:
    return build_documents(load_context(ROOT))


def pack(generated: GeneratedDocuments, pack_id: str) -> JsonObject:
    return generated[f"{pack_id}-v1.json"]


class ExternalWorldContractValidationTest(unittest.TestCase):
    def test_canonical_documents_validate(self) -> None:
        generated = documents()

        validate_documents(generated, load_context(ROOT))

    def test_rejects_unknown_pack_and_w1_c_fields(self) -> None:
        for field, value in (("mode", "SEA_ROUTE"), ("geometry", []), ("corridorId", "x"), ("access", []), ("revision", 1)):
            with self.subTest(field=field):
                generated = documents()
                first = require_object(require_array(pack(generated, "east-sea-wa"), "entities")[0], "entity")
                first[field] = value

                with self.assertRaisesRegex(ContractError, "schema|forbidden|key"):
                    validate_documents(generated, load_context(ROOT))

    def test_rejects_sixth_pack_and_duplicate_membership(self) -> None:
        generated = documents()
        index = generated["external-world-pack-index-v1.json"]
        require_array(index, "packs").append({"packId": "sixth", "path": "sixth.json", "sha256": "0" * 64, "legacyCandidateCount": 0})

        with self.assertRaisesRegex(ContractError, "pack|five|schema"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        east_key = require_array(pack(generated, "east-sea-wa"), "coverageLedger")[0]
        require_array(pack(generated, "northeast"), "coverageLedger").append(east_key)
        with self.assertRaisesRegex(ContractError, "coverage|membership|pack"):
            validate_documents(generated, load_context(ROOT))

    def test_rejects_legacy_raw_and_disposition_mutation(self) -> None:
        generated = documents()
        ledger = generated["external-world-legacy-adjudications-v1.json"]
        first = require_object(require_array(ledger, "adjudications")[0], "adjudication")
        first["rawLegacySha256"] = "0" * 64

        with self.assertRaisesRegex(ContractError, "legacy|raw|source"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        packed = require_object(require_array(generated["external-world-legacy-adjudications-v1.json"], "adjudications")[31], "adjudication")
        packed["disposition"] = "SPLIT"
        with self.assertRaisesRegex(ContractError, "disposition|result"):
            validate_documents(generated, load_context(ROOT))

    def test_rejects_legacy_partition_and_result_entity_mutation(self) -> None:
        generated = documents()
        packed = require_object(require_array(generated["external-world-legacy-adjudications-v1.json"], "adjudications")[31], "adjudication")
        packed["packId"] = "northeast"
        with self.assertRaisesRegex(ContractError, "partition|pack"):
            validate_documents(generated, load_context(ROOT))

        generated = documents()
        packed = require_object(require_array(generated["external-world-legacy-adjudications-v1.json"], "adjudications")[31], "adjudication")
        packed["resultEntityKeys"] = ["external:east-sea-wa:missing"]
        with self.assertRaisesRegex(ContractError, "result|entity"):
            validate_documents(generated, load_context(ROOT))

    def test_rejects_approved_ledger_result_when_entity_is_not_approved(self) -> None:
        generated = documents()
        east = pack(generated, "east-sea-wa")
        entity = require_object(require_array(east, "entities")[0], "entity")
        entity["reviewState"] = "INACTIVE_SOURCE_UNKNOWN"
        require_object(east.get("summary"), "summary")["approvedEntityCount"] = 14
        index_row = require_object(require_array(generated["external-world-pack-index-v1.json"], "packs")[0], "pack index row")
        index_row["sha256"] = hashlib.sha256(serialize(east).encode()).hexdigest()

        with self.assertRaisesRegex(ContractError, "APPROVE|approved|result"):
            validate_documents(generated, load_context(ROOT))

    def test_rejects_derived_summary_mutation(self) -> None:
        generated = documents()
        summary = require_object(pack(generated, "western-regions").get("summary"), "summary")
        summary["approvedEntityCount"] = 999

        with self.assertRaisesRegex(ContractError, "summary|derived"):
            validate_documents(generated, load_context(ROOT))

    def test_cli_check_and_deterministic_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as first_raw, tempfile.TemporaryDirectory() as second_raw:
            first = Path(first_raw)
            second = Path(second_raw)
            for output in (first, second):
                result = subprocess.run([sys.executable, str(SCRIPT), "--output-dir", str(output)], cwd=ROOT, check=False, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
            first_files = {path.relative_to(first): path.read_bytes() for path in first.rglob("*.json")}
            second_files = {path.relative_to(second): path.read_bytes() for path in second.rglob("*.json")}
            self.assertEqual(first_files, second_files)

        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], cwd=ROOT, check=False, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
