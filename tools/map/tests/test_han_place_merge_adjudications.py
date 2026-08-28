"""Closed stable-ID contract for reviewed Han place merge decisions."""

import copy
import json
from pathlib import Path
import unittest

from tools.map import han_place_merge_adjudications as validator


ROOT = Path(__file__).resolve().parents[3]
LEDGER_PATH = ROOT / "data/curated/han/han-place-merge-adjudications-v1.json"
MODULE_PATH = ROOT / "tools/map/han_place_merge_adjudications.py"
DECISION_FIRST_FIVE = "f070cf364a896b51effe26ca6c73a849fcf09f4a"
DECISION_XILING = "cfcf94a02c0529bf81cb0a9e4992a9efce1c0140"
INDEX_CORRECTION = "808965058163c9ff4858f6a82698a2ec5271e19b"

EXPECTED_IDENTITIES = {
    "34539": {
        "physicalPlaceId": "34539", "sourceNameCh": "宜都郡",
        "sourceNameFt": "宜都郡", "typeCh": "郡", "begYr": 210,
        "endYr": 279, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "1dc241265b752bdcf39e2bafb76a11cbac6985e7b9c71d8e9f96085684474edd",
    },
    "45796": {
        "physicalPlaceId": "45796", "sourceNameCh": "夷陵县",
        "sourceNameFt": "夷陵縣", "typeCh": "县", "begYr": 131,
        "endYr": 221, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "f40f29143be6a06ad19cbd8ba5aabc2503b699e5360afed263a53e47c7ab48d4",
    },
    "34546": {
        "physicalPlaceId": "34546", "sourceNameCh": "新城郡",
        "sourceNameFt": "新城郡", "typeCh": "郡", "begYr": 220,
        "endYr": 489, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "902898d0903b32e94fcca257e9264f02db5833bbaf6c78712063367399805a38",
    },
    "45921": {
        "physicalPlaceId": "45921", "sourceNameCh": "房陵县",
        "sourceNameFt": "房陵縣", "typeCh": "县", "begYr": -312,
        "endYr": 562, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "c2c0dabd8825e62ec357acf01883cfb9352b8f1ed2b6693722e35be0b31c5d7c",
    },
    "211278": {
        "physicalPlaceId": "211278", "sourceNameCh": "巴西郡",
        "sourceNameFt": "巴西郡", "typeCh": "郡", "begYr": 201,
        "endYr": 430, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "606ddc1a8b0e4e834bfdfeaec8a16fdad8f12f7c268635708ddea2a00d7e256a",
    },
    "44558": {
        "physicalPlaceId": "44558", "sourceNameCh": "阆中县",
        "sourceNameFt": "閬中縣", "typeCh": "县", "begYr": -314,
        "endYr": 582, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "2c5587a5da79c61dfd12d13a36520d75ebaaabb5ce654ca9a2b0a39d7d263ebf",
    },
    "87633": {
        "physicalPlaceId": "87633", "sourceNameCh": "北平郡",
        "sourceNameFt": "北平郡", "typeCh": "郡", "begYr": 220,
        "endYr": 264, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "fdf96e03ea2d1b5c237ccac702eef5ddee1864ce004ae08a8815444f3728bd43",
    },
    "87458": {
        "physicalPlaceId": "87458", "sourceNameCh": "土垠县",
        "sourceNameFt": "土垠縣", "typeCh": "县", "begYr": -201,
        "endYr": 316, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "c09d9b44c2cafca2e7fadc5ffb949d0a27a4ff7198dba9b67557dac6bfdb606b",
    },
    "211473": {
        "physicalPlaceId": "211473", "sourceNameCh": "冯翊郡",
        "sourceNameFt": "馮翊郡", "typeCh": "郡", "begYr": 220,
        "endYr": 490, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "a7cc0a6adfe3a35e5ffbed3bc01ff3fc292983eed702d5f107864ec50994b58e",
    },
    "70741": {
        "physicalPlaceId": "70741", "sourceNameCh": "高陆县",
        "sourceNameFt": "高陸縣", "typeCh": "县", "begYr": 220,
        "endYr": 515, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "0861f09976ba610bdf57b2dea694168f20850fc6807cad9b283d68c1217540ad",
    },
    "34526": {
        "physicalPlaceId": "34526", "sourceNameCh": "西陵郡",
        "sourceNameFt": "西陵郡", "typeCh": "郡", "begYr": 214,
        "endYr": 220, "kind": "COMMANDERY", "level": 6,
        "sourceRecordSha256": "ca50d20cf211c7166357d2caf450c1e26e34fb55bc491de4d4d0bc619a7ac4f8",
    },
    "43503": {
        "physicalPlaceId": "43503", "sourceNameCh": "西陵县",
        "sourceNameFt": "西陵縣", "typeCh": "县", "begYr": 23,
        "endYr": 501, "kind": "COUNTY", "level": 5,
        "sourceRecordSha256": "793afc3a5f221570c26f4441031d10efac942fe5dd00c9675b603a00a971a110",
    },
}

EXPECTED_ROWS = [
    (
        "han-place-merge:34539-45796", "RESEAT_WITHIN_JUN", "34539", "45796",
        "宜都郡", "南郡", "宜都郡",
        [{"role": "DECISION", "commit": DECISION_FIRST_FIVE}],
    ),
    (
        "han-place-merge:34546-45921", "RESEAT_WITHIN_JUN", "34546", "45921",
        "新城郡", "漢中郡", "新城郡",
        [{"role": "DECISION", "commit": DECISION_FIRST_FIVE}],
    ),
    (
        "han-place-merge:211278-44558", "RESEAT_WITHIN_JUN", "211278", "44558",
        "巴西郡", "巴郡", "巴西郡",
        [{"role": "DECISION", "commit": DECISION_FIRST_FIVE}],
    ),
    (
        "han-place-merge:87633-87458", "MERGE_JUN", "87633", "87458",
        "北平郡", "右北平郡", "右北平郡",
        [
            {"role": "DECISION", "commit": DECISION_FIRST_FIVE},
            {"role": "INDEX_CORRECTION", "commit": INDEX_CORRECTION},
        ],
    ),
    (
        "han-place-merge:211473-70741", "MERGE_JUN", "211473", "70741",
        "馮翊郡", "左馮翊", "左馮翊",
        [
            {"role": "DECISION", "commit": DECISION_FIRST_FIVE},
            {"role": "INDEX_CORRECTION", "commit": INDEX_CORRECTION},
        ],
    ),
    (
        "han-place-merge:34526-43503", "MERGE_JUN", "34526", "43503",
        "西陵郡", "江夏郡", "江夏郡",
        [
            {"role": "DECISION", "commit": DECISION_XILING},
            {"role": "INDEX_CORRECTION", "commit": INDEX_CORRECTION},
        ],
    ),
]


class PresenceRedTest(unittest.TestCase):
    def test_ledger_and_pure_validator_exist(self):
        self.assertTrue(LEDGER_PATH.is_file(), f"missing ledger: {LEDGER_PATH}")
        self.assertTrue(MODULE_PATH.is_file(), f"missing validator: {MODULE_PATH}")

    def test_public_pure_validation_api_exists(self):
        self.assertTrue(hasattr(validator, "identity_sha256"))
        self.assertTrue(hasattr(validator, "validate_ledger"))
        self.assertTrue(hasattr(validator, "validate_ledger_json"))


class CommittedLedgerTest(unittest.TestCase):
    def test_exact_six_stable_identity_decisions_are_committed_in_order(self):
        ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            {
                "schemaVersion", "ledgerId", "baselineYear",
                "sourceFingerprintContract", "adjudications",
            },
            set(ledger),
        )
        self.assertEqual(1, ledger["schemaVersion"])
        self.assertEqual("han-place-merge-adjudications-v1", ledger["ledgerId"])
        self.assertEqual(220, ledger["baselineYear"])
        self.assertEqual(
            "HAN_PLACE_IDENTITY_CANONICAL_JSON_SHA256_V1",
            ledger["sourceFingerprintContract"],
        )
        self.assertEqual(6, len(ledger["adjudications"]))

        actual = []
        for row in ledger["adjudications"]:
            source_id = row["sourcePlace"]["physicalPlaceId"]
            target_id = row["targetPlace"]["physicalPlaceId"]
            self.assertEqual(EXPECTED_IDENTITIES[source_id], row["sourcePlace"])
            self.assertEqual(EXPECTED_IDENTITIES[target_id], row["targetPlace"])
            actual.append((
                row["adjudicationId"], row["operation"], source_id, target_id,
                row["expectedInitialSourceJunNameFt"],
                row["expectedInitialTargetJunNameFt"], row["resultJunNameFt"],
                row["historicalProvenance"],
            ))
        self.assertEqual(EXPECTED_ROWS, actual)


class LedgerValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger_text = LEDGER_PATH.read_text(encoding="utf-8")
        cls.ledger = json.loads(cls.ledger_text)

    def mutated(self):
        return copy.deepcopy(self.ledger)

    def assert_invalid(self, document):
        with self.assertRaises((TypeError, ValueError)):
            validator.validate_ledger(document)

    def test_committed_ledger_and_identity_hash_are_valid(self):
        self.assertTrue(validator.validate_ledger(self.mutated()))
        self.assertTrue(validator.validate_ledger_json(self.ledger_text))
        self.assertTrue(validator.validate_ledger_json(self.ledger_text.encode()))
        identity = copy.deepcopy(EXPECTED_IDENTITIES["34539"])
        expected_hash = identity.pop("sourceRecordSha256")
        self.assertEqual(expected_hash, validator.identity_sha256(identity))

    def test_exact_closed_keys_at_every_object_layer(self):
        mutations = []
        for path in (
            (),
            ("adjudications", 0),
            ("adjudications", 0, "sourcePlace"),
            ("adjudications", 0, "targetPlace"),
            ("adjudications", 0, "historicalProvenance", 0),
        ):
            document = self.mutated()
            target = document
            for part in path:
                target = target[part]
            target["unexpected"] = "fail-closed"
            mutations.append(document)
        for alias in ("sourceId", "targetId", "recordId", "name"):
            document = self.mutated()
            document["adjudications"][0][alias] = "34539"
            mutations.append(document)
        for document in mutations:
            with self.subTest(document=document):
                self.assert_invalid(document)

    def test_forbidden_coordinate_path_index_and_runtime_identity_aliases(self):
        forbidden = (
            "x", "y", "gx", "gy", "lat", "lon", "coordinates", "cell",
            "path", "sourcePath", "arrayIndex", "recordIndex", "runtimeId",
            "nearestIndex",
        )
        for key in forbidden:
            document = self.mutated()
            document["adjudications"][0]["sourcePlace"][key] = 0
            with self.subTest(key=key):
                self.assert_invalid(document)

    def test_rows_are_exactly_complete_unique_and_ordered(self):
        mutations = []
        missing = self.mutated()
        missing["adjudications"].pop()
        mutations.append(missing)
        extra = self.mutated()
        extra["adjudications"].append(copy.deepcopy(extra["adjudications"][0]))
        mutations.append(extra)
        reordered = self.mutated()
        reordered["adjudications"][0], reordered["adjudications"][1] = (
            reordered["adjudications"][1], reordered["adjudications"][0]
        )
        mutations.append(reordered)
        duplicate = self.mutated()
        duplicate["adjudications"][1] = copy.deepcopy(duplicate["adjudications"][0])
        mutations.append(duplicate)
        duplicate_id = self.mutated()
        duplicate_id["adjudications"][1]["adjudicationId"] = duplicate_id["adjudications"][0]["adjudicationId"]
        mutations.append(duplicate_id)
        for document in mutations:
            self.assert_invalid(document)

    def test_identity_fields_types_and_hash_are_locked(self):
        mutations = {
            "physicalPlaceId": "999",
            "sourceNameCh": "錯",
            "sourceNameFt": "錯",
            "typeCh": "縣",
            "begYr": 211,
            "endYr": 280,
            "kind": "COUNTY",
            "level": 5,
            "sourceRecordSha256": "1" * 64,
        }
        for key, value in mutations.items():
            document = self.mutated()
            document["adjudications"][0]["sourcePlace"][key] = value
            with self.subTest(key=key):
                self.assert_invalid(document)

        document = self.mutated()
        place = document["adjudications"][0]["sourcePlace"]
        place["sourceNameCh"] = "偽名"
        unhashed = {key: value for key, value in place.items() if key != "sourceRecordSha256"}
        place["sourceRecordSha256"] = validator.identity_sha256(unhashed)
        self.assert_invalid(document)

        type_mutations = (
            ("physicalPlaceId", 34539), ("physicalPlaceId", ""),
            ("physicalPlaceId", "34A"), ("begYr", True), ("begYr", 210.0),
            ("endYr", False), ("level", True), ("level", 6.0),
            ("sourceRecordSha256", "A" * 64),
            ("sourceRecordSha256", "0" * 64),
            ("sourceRecordSha256", "a" * 63),
        )
        for key, value in type_mutations:
            document = self.mutated()
            document["adjudications"][0]["sourcePlace"][key] = value
            with self.subTest(key=key, value=value):
                self.assert_invalid(document)

    def test_root_and_row_scalars_are_exact_and_bool_is_not_int(self):
        for key, value in (
            ("schemaVersion", True), ("schemaVersion", 1.0),
            ("baselineYear", True), ("baselineYear", 220.0),
            ("ledgerId", "wrong"), ("sourceFingerprintContract", "SHA256"),
        ):
            document = self.mutated()
            document[key] = value
            with self.subTest(key=key, value=value):
                self.assert_invalid(document)
        for key, value in (
            ("adjudicationId", "wrong"), ("decision", "PROPOSED"),
            ("operation", "RESEAT"),
            ("expectedInitialSourceJunNameFt", "錯"),
            ("expectedInitialTargetJunNameFt", "錯"),
            ("resultJunNameFt", "錯"),
        ):
            document = self.mutated()
            document["adjudications"][0][key] = value
            with self.subTest(key=key):
                self.assert_invalid(document)

    def test_topology_rejects_self_edges_cycles_and_endpoint_overlap(self):
        self_edge = self.mutated()
        self_edge["adjudications"][0]["targetPlace"] = copy.deepcopy(
            self_edge["adjudications"][0]["sourcePlace"]
        )
        self.assert_invalid(self_edge)

        cycle = self.mutated()
        cycle["adjudications"][0]["targetPlace"] = copy.deepcopy(
            cycle["adjudications"][1]["sourcePlace"]
        )
        cycle["adjudications"][1]["targetPlace"] = copy.deepcopy(
            cycle["adjudications"][0]["sourcePlace"]
        )
        self.assert_invalid(cycle)

        duplicate_source = self.mutated()
        duplicate_source["adjudications"][1]["sourcePlace"] = copy.deepcopy(
            duplicate_source["adjudications"][0]["sourcePlace"]
        )
        self.assert_invalid(duplicate_source)

        duplicate_target = self.mutated()
        duplicate_target["adjudications"][1]["targetPlace"] = copy.deepcopy(
            duplicate_target["adjudications"][0]["targetPlace"]
        )
        self.assert_invalid(duplicate_target)

    def test_provenance_is_exact_nonempty_ordered_and_cryptographic(self):
        mutations = []
        missing = self.mutated()
        missing["adjudications"][3]["historicalProvenance"].pop()
        mutations.append(missing)
        empty = self.mutated()
        empty["adjudications"][0]["historicalProvenance"] = []
        mutations.append(empty)
        extra = self.mutated()
        extra["adjudications"][0]["historicalProvenance"].append(
            {"role": "DECISION", "commit": DECISION_FIRST_FIVE}
        )
        mutations.append(extra)
        duplicate = self.mutated()
        duplicate["adjudications"][3]["historicalProvenance"][1] = copy.deepcopy(
            duplicate["adjudications"][3]["historicalProvenance"][0]
        )
        mutations.append(duplicate)
        reordered = self.mutated()
        reordered["adjudications"][3]["historicalProvenance"].reverse()
        mutations.append(reordered)
        for key, value in (
            ("role", "decision"), ("role", "SOURCE"),
            ("commit", "1" * 40),
            ("commit", "F" * 40), ("commit", "0" * 40),
            ("commit", "f" * 39),
        ):
            document = self.mutated()
            document["adjudications"][0]["historicalProvenance"][0][key] = value
            mutations.append(document)
        for document in mutations:
            self.assert_invalid(document)

    def test_json_entrypoint_rejects_duplicate_keys_at_every_depth(self):
        documents = (
            '{"schemaVersion":1,"schemaVersion":1}',
            '{"adjudications":[{"decision":"APPROVED","decision":"BLOCKED"}]}',
            '{"adjudications":[{"sourcePlace":{"level":6,"level":5}}]}',
            '{"adjudications":[{"historicalProvenance":[{"role":"DECISION","role":"DECISION"}]}]}',
        )
        for document in documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    validator.validate_ledger_json(document)

    def test_json_entrypoint_rejects_nonfinite_numbers(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(token=token):
                with self.assertRaises(ValueError):
                    validator.validate_ledger_json('{"baselineYear":' + token + "}")


if __name__ == "__main__":
    unittest.main()
