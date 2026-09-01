"""Closed, coordinate-free Han tile build contract validation."""

from __future__ import annotations

import copy
import hashlib
import json
import unittest

from tools.map import han_tiles_contract as contract_validator


INPUT_ROLES = [
    "CHGIS_PREF_DBF", "CHGIS_COUNTY_DBF",
    "CTEXT_JUNGUOZHI_YI", "CTEXT_JUNGUOZHI_ER", "CTEXT_JUNGUOZHI_SAN",
    "CTEXT_JUNGUOZHI_SI", "CTEXT_JUNGUOZHI_WU",
    "NE_LAND_50M", "NE_LAKES_50M", "NE_RIVERS_50M", "NE_REGIONS_10M",
    "MODERN_ADMIN_ADM2",
    "ADMINISTRATIVE_UNITS", "ADMINISTRATIVE_BINDINGS", "ADMINISTRATIVE_HISTORY",
    "DUPLICATE_ADJUDICATIONS", "STABLE_ID_ADJUDICATIONS",
    "MERGE_ADJUDICATIONS", "TEMPORAL_ADJUDICATIONS", "EXTERNAL_PLACES",
    "EXTERNAL_PROVINCE_SEEDS", "EXTERNAL_ADMINISTRATIVE_SYSTEMS",
    "PROVINCE_SHAPE_EXCEPTIONS", "NON_PLAYABLE_REGIONS", "MODERN_ADMIN_RECIPE",
]
GENERATOR_ROLES = [
    "BUILD_HAN_PLACES", "BUILD_JUNGUOZHI", "BUILD_TERRAIN_GRID",
    "BUILD_READINGS", "BUILD_HAN_TILES",
]
VERIFIER_ROLES = ["HAN_TILES_CONTRACT_VALIDATOR", "HAN_TILES_ORCHESTRATOR"]
HELPER_ROLES = [
    "HAN_PLACE_STABLE_ID_LOADER", "HAN_PLACE_MERGE_ADJUDICATIONS",
    "HAN_PLACE_MERGE_RUNTIME", "HAN_TEMPORAL_PARENT_RUNTIME",
    "HAN_PARENT_RECONCILIATION_HELPER", "HAN_PROVINCE_MODEL",
    "WORLD_PROVINCE_GEOMETRY", "PROVINCE_QUALITY", "NON_PLAYABLE_REGIONS_HELPER", "EXTERNAL_PROVINCE_SYSTEMS",
    "HAN_TILES_CONTRACT_HELPER",
]
OUTPUT_ROLES = ["HAN_PLACES", "JUNGUOZHI", "TERRAIN_GRID", "READINGS", "HAN_TILES"]


def canonical_sha(value):
    blob = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def digest(label):
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def hashed_record(label, **extra):
    return {"sha256": digest(label), "bytes": 100 + len(label), **extra}


def valid_recipe():
    restricted = set(INPUT_ROLES[:12])
    inputs = {
        role: hashed_record(
            role,
            classification=("RESTRICTED_LOCAL" if role in restricted else "TRACKED_REPOSITORY"),
        )
        for role in INPUT_ROLES
    }
    return {
        "canonical": {
            "year": 220, "gridColumns": 768, "gridRows": 669,
            "frameId": "HAN_WORLD_FRAME_V1",
        },
        "inputs": inputs,
        "generators": {role: hashed_record(role) for role in GENERATOR_ROLES},
        "verifiers": {role: hashed_record(role) for role in VERIFIER_ROLES},
        "helpers": {role: hashed_record(role) for role in HELPER_ROLES},
        "dependencies": {
            "NUMPY": {
                "distributionName": "numpy",
                "lockRole": "HAN_TILES_PYTHON_LOCK",
            },
            "PILLOW": {
                "distributionName": "Pillow",
                "lockRole": "HAN_TILES_PYTHON_LOCK",
            },
            "HANJA": {
                "distributionName": "hanja",
                "lockRole": "HAN_TILES_PYTHON_LOCK",
            },
            "PYYAML": {
                "distributionName": "PyYAML",
                "lockRole": "HAN_TILES_PYTHON_LOCK",
                "requiredByRole": "HANJA",
            },
        },
        "runtime": {
            "pythonImplementation": "CPython",
            "pythonVersion": "3.14.5",
            "dependencyLock": {
                "role": "HAN_TILES_PYTHON_LOCK",
                **hashed_record("HAN_TILES_PYTHON_LOCK"),
            },
            "environment": {
                "locale": "C.UTF-8", "timezone": "UTC",
                "pythonHashSeed": "0", "threadCount": 1,
            },
        },
        "stages": [
            {
                "stageId": "HAN_PLACES", "generatorRole": "BUILD_HAN_PLACES",
                "inputRoles": [
                    "CHGIS_PREF_DBF", "CHGIS_COUNTY_DBF", "ADMINISTRATIVE_UNITS",
                    "DUPLICATE_ADJUDICATIONS", "STABLE_ID_ADJUDICATIONS",
                    "EXTERNAL_PLACES",
                ],
                "dependencyRoles": [], "outputRole": "HAN_PLACES",
                "argv": ["--year", "220", "--grid", "768"],
            },
            {
                "stageId": "JUNGUOZHI", "generatorRole": "BUILD_JUNGUOZHI",
                "inputRoles": [
                    "CHGIS_COUNTY_DBF", "CTEXT_JUNGUOZHI_YI",
                    "CTEXT_JUNGUOZHI_ER", "CTEXT_JUNGUOZHI_SAN",
                    "CTEXT_JUNGUOZHI_SI", "CTEXT_JUNGUOZHI_WU", "EXTERNAL_PLACES",
                ],
                "dependencyRoles": [], "outputRole": "JUNGUOZHI", "argv": [],
            },
            {
                "stageId": "TERRAIN_GRID", "generatorRole": "BUILD_TERRAIN_GRID",
                "inputRoles": [
                    "HAN_PLACES", "JUNGUOZHI", "NE_LAND_50M", "NE_LAKES_50M",
                    "NE_RIVERS_50M", "NE_REGIONS_10M", "MODERN_ADMIN_ADM2",
                    "ADMINISTRATIVE_UNITS",
                    "ADMINISTRATIVE_BINDINGS", "ADMINISTRATIVE_HISTORY",
                    "MERGE_ADJUDICATIONS", "TEMPORAL_ADJUDICATIONS",
                    "EXTERNAL_PROVINCE_SEEDS", "EXTERNAL_ADMINISTRATIVE_SYSTEMS",
                    "PROVINCE_SHAPE_EXCEPTIONS", "MODERN_ADMIN_RECIPE",
                ],
                "dependencyRoles": ["NUMPY", "PILLOW"],
                "outputRole": "TERRAIN_GRID", "argv": ["--grid", "768"],
            },
            {
                "stageId": "READINGS", "generatorRole": "BUILD_READINGS",
                "inputRoles": ["HAN_PLACES", "JUNGUOZHI", "TERRAIN_GRID"],
                "dependencyRoles": ["HANJA", "PYYAML"],
                "outputRole": "READINGS", "argv": [],
            },
            {
                "stageId": "HAN_TILES", "generatorRole": "BUILD_HAN_TILES",
                "inputRoles": ["HAN_PLACES", "TERRAIN_GRID", "READINGS"],
                "dependencyRoles": [], "outputRole": "HAN_TILES", "argv": [],
            },
        ],
    }


def valid_contract():
    recipe = valid_recipe()
    return {
        "schemaVersion": 1,
        "contractId": "han-tiles-build-contract-v1",
        "policy": {
            "authoritativeArtifactRole": "HAN_TILES",
            "provenanceOnly": True,
            "committedCoordinateBearingRoles": ["HAN_TILES"],
        },
        "recipe": recipe,
        "recipeSha256": canonical_sha(recipe),
        "expectedOutputs": output_records(),
    }


def summaries():
    return {
        "HAN_PLACES": {
            "year": 220, "cols": 768, "rows": 669,
            "placeCount": 1145, "nudgedCount": 97,
        },
        "JUNGUOZHI": {
            "groupCount": 105, "countyCount": 1181, "resolvedCount": 824,
            "candidateCount": 357, "checksumPassCount": 99, "noCountCount": 6,
        },
        "TERRAIN_GRID": {
            "year": 220, "cols": 768, "rows": 669,
            "terrainCellCount": 513792, "ownerCellCount": 513792,
            "seatOwnerCellCount": 513792, "hubCount": 175, "regionCount": 39,
            "countyEdgeCount": 2662, "commanderyEdgeCount": 425,
            "provinceCount": 1524, "parentRegionCount": 172,
        },
        "READINGS": {"entryCount": 2178},
        "HAN_TILES": {
            "year": 220, "cols": 768, "rows": 669,
            "cityCount": 1138, "junCount": 172, "regionCount": 38,
            "ownerRunCount": 19898, "parentOwnerRunCount": 11554,
            "countyEdgeCount": 2649, "commanderyEdgeCount": 417,
            "provinceCount": 1524, "parentRegionCount": 172,
        },
    }


def output_records():
    return {
        role: {
            "role": role, "sha256": digest(f"output:{role}"),
            "bytes": 1000 + len(role), "summary": summary,
        }
        for role, summary in summaries().items()
    }


def valid_attestation(contract):
    outputs = copy.deepcopy(contract["expectedOutputs"])
    return {
        "schemaVersion": 1,
        "attestationId": "han-tiles-build-attestation-v1",
        "contractId": contract["contractId"],
        "contractSha256": canonical_sha(contract),
        "recipeSha256": contract["recipeSha256"],
        "sourceBundleSha256": canonical_sha(contract["recipe"]["inputs"]),
        "runtimeFingerprint": {
            "pythonImplementation": "CPython", "pythonVersion": "3.14.5",
            "dependencyLockSha256": contract["recipe"]["runtime"]["dependencyLock"]["sha256"],
            "environment": copy.deepcopy(contract["recipe"]["runtime"]["environment"]),
        },
        "cleanRuns": [
            {"runOrdinal": 1, "outputs": copy.deepcopy(outputs)},
            {"runOrdinal": 2, "outputs": copy.deepcopy(outputs)},
        ],
        "materializedArtifact": copy.deepcopy(outputs["HAN_TILES"]),
    }


class ContractValidationTest(unittest.TestCase):
    def validate_mutation(self, mutate, pattern):
        document = valid_contract()
        mutate(document)
        with self.assertRaisesRegex(ValueError, pattern):
            contract_validator.validate_contract(document)

    def test_valid_closed_contract_and_canonical_hash_are_accepted(self):
        document = valid_contract()
        self.assertTrue(contract_validator.validate_contract(document))
        self.assertEqual(document["recipeSha256"], contract_validator.recipe_sha256(document["recipe"]))

    def test_recipe_hash_is_independent_of_object_key_order(self):
        recipe = valid_recipe()
        reordered = dict(reversed(list(recipe.items())))
        self.assertEqual(canonical_sha(recipe), contract_validator.recipe_sha256(reordered))

    def test_every_contract_object_layer_rejects_unknown_keys(self):
        mutations = [
            lambda d: d.update(metadata={}),
            lambda d: d["policy"].update(source="hidden"),
            lambda d: d["recipe"].update(outputs={}),
            lambda d: d["recipe"]["canonical"].update(grid=768),
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(digest="0" * 64),
            lambda d: d["recipe"]["generators"]["BUILD_HAN_PLACES"].update(path="tool.py"),
            lambda d: d["recipe"]["verifiers"]["HAN_TILES_ORCHESTRATOR"].update(version="1"),
            lambda d: d["recipe"]["helpers"]["HAN_PROVINCE_MODEL"].update(path="helper.py"),
            lambda d: d["recipe"]["dependencies"]["NUMPY"].update(version="2"),
            lambda d: d["recipe"]["runtime"].update(platform="macOS"),
            lambda d: d["recipe"]["runtime"]["dependencyLock"].update(url="https://example.test"),
            lambda d: d["recipe"]["runtime"]["environment"].update(home="/tmp"),
            lambda d: d["recipe"]["stages"][0].update(command="python"),
            lambda d: d["expectedOutputs"]["HAN_PLACES"].update(digest="0" * 64),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(layer=index):
                self.validate_mutation(mutate, "unknown|forbidden|exact keys")

    def test_fixed_role_sets_reject_missing_extra_alias_and_duplicate_roles(self):
        mutations = [
            lambda d: d["recipe"]["inputs"].pop("CHGIS_PREF_DBF"),
            lambda d: d["recipe"]["inputs"].update(OTHER=hashed_record("OTHER", classification="TRACKED_REPOSITORY")),
            lambda d: d["recipe"]["generators"].update(build_han_places=d["recipe"]["generators"].pop("BUILD_HAN_PLACES")),
            lambda d: d["recipe"]["verifiers"].pop("HAN_TILES_ORCHESTRATOR"),
            lambda d: d["recipe"]["helpers"].pop("HAN_TEMPORAL_PARENT_RUNTIME"),
            lambda d: d["recipe"]["inputs"].pop("ADMINISTRATIVE_HISTORY"),
            lambda d: d["recipe"]["dependencies"].pop("NUMPY"),
            lambda d: d["recipe"]["stages"][0]["inputRoles"].append("CHGIS_PREF_DBF"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "role|stage graph")

    def test_dependencies_are_closed_over_every_stage_reference(self):
        mutations = [
            lambda d: d["recipe"].pop("dependencies"),
            lambda d: d["recipe"]["dependencies"].pop("NUMPY"),
            lambda d: d["recipe"]["dependencies"].update(SCIPY={
                "distributionName": "scipy", "lockRole": "HAN_TILES_PYTHON_LOCK",
            }),
            lambda d: d["recipe"]["dependencies"].update(
                numpy=d["recipe"]["dependencies"].pop("NUMPY")
            ),
            lambda d: d["recipe"]["stages"][2]["dependencyRoles"].append("SCIPY"),
            lambda d: d["recipe"]["stages"][3]["dependencyRoles"].remove("HANJA"),
            lambda d: d["recipe"]["dependencies"]["PILLOW"].update(
                distributionName="pillow"
            ),
            lambda d: d["recipe"]["dependencies"]["HANJA"].update(
                lockRole="OTHER_LOCK"
            ),
            lambda d: d["recipe"]["dependencies"]["PYYAML"].update(
                requiredByRole="NUMPY"
            ),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(
                    mutate, "dependenc|role set|stage graph|exact keys"
                )

    def test_fixed_stage_order_graph_dependencies_and_arguments_reject_drift(self):
        mutations = [
            lambda d: d["recipe"]["stages"].reverse(),
            lambda d: d["recipe"]["stages"].pop(),
            lambda d: d["recipe"]["stages"][0].update(outputRole="READINGS"),
            lambda d: d["recipe"]["stages"][2]["inputRoles"].remove("JUNGUOZHI"),
            lambda d: d["recipe"]["stages"][2]["dependencyRoles"].remove("PILLOW"),
            lambda d: d["recipe"]["stages"][3]["dependencyRoles"].append("NUMPY"),
            lambda d: d["recipe"]["stages"][2]["inputRoles"].remove("ADMINISTRATIVE_HISTORY"),
            lambda d: d["recipe"]["stages"][3]["inputRoles"].remove("TERRAIN_GRID"),
            lambda d: d["recipe"]["stages"][3]["dependencyRoles"].remove("PYYAML"),
            lambda d: d["recipe"]["stages"][0]["argv"].__setitem__(3, "256"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "stage graph")

    def test_noncanonical_grid_year_rows_and_coordinate_bbox_are_rejected(self):
        mutations = [
            lambda d: d["recipe"]["canonical"].update(gridColumns=256),
            lambda d: d["recipe"]["canonical"].update(gridRows=223),
            lambda d: d["recipe"]["canonical"].update(year=221),
            lambda d: d["recipe"]["canonical"].update(bbox=[93, 133, 15, 45]),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "canonical|forbidden|exact keys")

    def test_malformed_uppercase_zero_hash_and_nonpositive_or_boolean_bytes_are_rejected(self):
        mutations = [
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(sha256="a" * 63),
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(sha256="A" * 64),
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(sha256="0" * 64),
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(bytes=0),
            lambda d: d["recipe"]["inputs"]["CHGIS_PREF_DBF"].update(bytes=True),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "sha256|bytes")

    def test_recipe_hash_drift_and_wrong_runtime_contract_are_rejected(self):
        mutations = [
            lambda d: d.update(recipeSha256=digest("wrong")),
            lambda d: d["recipe"]["runtime"].update(pythonVersion="latest"),
            lambda d: d["recipe"]["runtime"]["environment"].update(threadCount=2),
            lambda d: d["recipe"]["runtime"]["dependencyLock"].update(role="requirements.txt"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "recipeSha256|runtime")

    def test_integer_and_boolean_literals_require_exact_json_types(self):
        def replace_recipe_value(document, section, field, value):
            document["recipe"][section][field] = value
            document["recipeSha256"] = canonical_sha(document["recipe"])

        mutations = [
            lambda d: d.update(schemaVersion=True),
            lambda d: d["policy"].update(provenanceOnly=1),
            lambda d: replace_recipe_value(d, "canonical", "year", 220.0),
            lambda d: replace_recipe_value(d, "canonical", "gridColumns", 768.0),
            lambda d: replace_recipe_value(d, "runtime", "environment", {
                **d["recipe"]["runtime"]["environment"], "threadCount": 1.0,
            }),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "schemaVersion|policy|canonical|environment")

    def test_forbidden_coordinate_path_and_url_fields_are_rejected_at_any_depth(self):
        fields = {
            "path": "data/chgis-source/source.dbf", "absolute_path": "/private/source.dbf",
            "sourceRoot": "/private/source", "coordinates": [1, 2],
            "projection": {"x0": 1}, "cells": [[1, 2]], "owners": [1],
            "url": "https://example.test", "lon": 1, "lat": 2,
        }
        for field, value in fields.items():
            with self.subTest(field=field):
                self.validate_mutation(
                    lambda d, field=field, value=value: d["recipe"]["stages"][0].update({field: value}),
                    "forbidden",
                )

    def test_expected_outputs_require_exact_roles_records_and_canonical_summaries(self):
        mutations = [
            lambda d: d.pop("expectedOutputs"),
            lambda d: d["expectedOutputs"].pop("HAN_PLACES"),
            lambda d: d["expectedOutputs"].update(EXTRA=copy.deepcopy(
                d["expectedOutputs"]["HAN_PLACES"]
            )),
            lambda d: d["expectedOutputs"].update(han_places=
                d["expectedOutputs"].pop("HAN_PLACES")
            ),
            lambda d: d["expectedOutputs"]["HAN_PLACES"].update(role="READINGS"),
            lambda d: d["expectedOutputs"]["HAN_PLACES"].update(sha256="0" * 64),
            lambda d: d["expectedOutputs"]["HAN_PLACES"].update(bytes=0),
            lambda d: d["expectedOutputs"]["HAN_PLACES"]["summary"].update(
                placeCount=True
            ),
            lambda d: d["expectedOutputs"]["HAN_TILES"]["summary"].update(year=221),
            lambda d: d["expectedOutputs"]["TERRAIN_GRID"]["summary"].update(
                ownerCellCount=1
            ),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(
                    mutate,
                    "expectedOutputs|role|sha256|bytes|summary|canonical|cell|exact keys",
                )

    def test_expected_outputs_stay_outside_recipe_hash_but_inside_contract_hash(self):
        original = valid_contract()
        attestation = valid_attestation(original)
        changed = copy.deepcopy(original)
        changed_hash = digest("reviewed-new-han-places-output")
        changed["expectedOutputs"]["HAN_PLACES"]["sha256"] = changed_hash

        self.assertEqual(original["recipeSha256"], changed["recipeSha256"])
        self.assertNotEqual(canonical_sha(original), canonical_sha(changed))
        self.assertTrue(contract_validator.validate_contract(changed))

        for run in attestation["cleanRuns"]:
            run["outputs"]["HAN_PLACES"]["sha256"] = changed_hash
        with self.assertRaisesRegex(ValueError, "contractSha256"):
            contract_validator.validate_attestation(changed, attestation)


class AttestationValidationTest(unittest.TestCase):
    def validate_mutation(self, mutate, pattern):
        contract = valid_contract()
        attestation = valid_attestation(contract)
        mutate(attestation, contract)
        with self.assertRaisesRegex(ValueError, pattern):
            contract_validator.validate_attestation(contract, attestation)

    def test_valid_attestation_accepts_two_orchestrator_produced_identical_manifests(self):
        contract = valid_contract()
        self.assertTrue(contract_validator.validate_attestation(contract, valid_attestation(contract)))

    def test_identical_clean_runs_must_also_equal_contract_expected_outputs(self):
        mutations = [
            lambda a, _c: [
                run["outputs"]["HAN_PLACES"].update(
                    sha256=digest("same-but-unexpected-han-places")
                )
                for run in a["cleanRuns"]
            ],
            lambda a, _c: (
                [
                    run["outputs"]["HAN_TILES"]["summary"].update(cityCount=0)
                    for run in a["cleanRuns"]
                ],
                a["materializedArtifact"]["summary"].update(cityCount=0),
            ),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "expectedOutputs")

    def test_every_attestation_object_layer_rejects_unknown_keys(self):
        mutations = [
            lambda a, _c: a.update(hostname="builder.local"),
            lambda a, _c: a["runtimeFingerprint"].update(platform="macOS"),
            lambda a, _c: a["runtimeFingerprint"]["environment"].update(cwd="/tmp"),
            lambda a, _c: a["cleanRuns"][0].update(timestamp="now"),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"].update(path="intermediate.json"),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"]["summary"].update(placeIds=[]),
            lambda a, _c: a["materializedArtifact"].update(commit="deadbeef"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(layer=index):
                self.validate_mutation(mutate, "unknown|forbidden|exact keys")

    def test_contract_recipe_source_bundle_and_runtime_drift_are_rejected(self):
        mutations = [
            lambda a, _c: a.update(contractSha256=digest("wrong-contract")),
            lambda a, _c: a.update(recipeSha256=digest("wrong-recipe")),
            lambda a, _c: a.update(sourceBundleSha256=digest("wrong-source")),
            lambda a, _c: a["runtimeFingerprint"].update(pythonVersion="3.13.7"),
            lambda a, _c: a["runtimeFingerprint"].update(dependencyLockSha256=digest("wrong-lock")),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "contractSha256|recipeSha256|sourceBundleSha256|runtime")

    def test_attestation_requires_exactly_two_runs_in_order(self):
        mutations = [
            lambda a, _c: a["cleanRuns"].pop(),
            lambda a, _c: a["cleanRuns"].append(copy.deepcopy(a["cleanRuns"][0])),
            lambda a, _c: a["cleanRuns"].reverse(),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "two clean runs|runOrdinal")

    def test_attestation_schema_and_ordinals_require_exact_integer_types(self):
        mutations = [
            lambda a, _c: a.update(schemaVersion=True),
            lambda a, _c: a["cleanRuns"][0].update(runOrdinal=True),
            lambda a, _c: a["cleanRuns"][1].update(runOrdinal=2.0),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "schemaVersion|runOrdinal")

    def test_output_roles_reject_missing_extra_alias_and_duplicate_record_identity(self):
        mutations = [
            lambda a, _c: a["cleanRuns"][0]["outputs"].pop("HAN_PLACES"),
            lambda a, _c: a["cleanRuns"][0]["outputs"].update(EXTRA=copy.deepcopy(a["cleanRuns"][0]["outputs"]["HAN_PLACES"])),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"].update(role="han_places"),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"].update(role="READINGS"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "role set|output role")

    def test_any_stage_hash_bytes_or_summary_drift_between_runs_is_rejected(self):
        mutations = [
            lambda a, _c: a["cleanRuns"][1]["outputs"]["HAN_PLACES"].update(sha256=digest("drift")),
            lambda a, _c: a["cleanRuns"][1]["outputs"]["TERRAIN_GRID"].update(bytes=9999),
            lambda a, _c: a["cleanRuns"][1]["outputs"]["READINGS"]["summary"].update(entryCount=2179),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "clean runs differ")

    def test_malformed_hash_bytes_and_summary_scalar_types_are_rejected(self):
        mutations = [
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"].update(sha256="0" * 64),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"].update(bytes=False),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"]["summary"].update(placeCount=-1),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"]["summary"].update(placeCount=True),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["HAN_PLACES"]["summary"].update(year=221),
            lambda a, _c: a["cleanRuns"][0]["outputs"]["TERRAIN_GRID"]["summary"].update(ownerCellCount=1),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "sha256|bytes|summary|canonical|cell")

    def test_materialized_artifact_must_be_the_identical_han_tiles_output(self):
        mutations = [
            lambda a, _c: a["materializedArtifact"].update(role="TERRAIN_GRID"),
            lambda a, _c: a["materializedArtifact"].update(sha256=digest("other-final")),
            lambda a, _c: a["materializedArtifact"]["summary"].update(cityCount=1139),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.validate_mutation(mutate, "materializedArtifact")

    def test_forbidden_coordinate_path_and_url_fields_are_rejected_in_attestation(self):
        fields = {
            "path": "terrain-grid.json", "source_root": "/private/source",
            "coordinates": [1, 2], "projection": {"x0": 1},
            "cells": [[1, 2]], "url": "https://example.test",
        }
        for field, value in fields.items():
            with self.subTest(field=field):
                self.validate_mutation(
                    lambda a, _c, field=field, value=value: a["cleanRuns"][0]["outputs"]["HAN_PLACES"]["summary"].update({field: value}),
                    "forbidden",
                )


class StrictJsonApiTest(unittest.TestCase):
    def test_public_json_apis_accept_str_and_bytes_documents(self):
        contract = valid_contract()
        attestation = valid_attestation(contract)
        contract_text = json.dumps(contract, ensure_ascii=False, separators=(",", ":"))
        attestation_text = json.dumps(
            attestation, ensure_ascii=False, separators=(",", ":")
        )

        self.assertTrue(contract_validator.validate_contract_json(contract_text))
        self.assertTrue(contract_validator.validate_contract_json(contract_text.encode("utf-8")))
        self.assertTrue(contract_validator.validate_attestation_json(
            contract_text, attestation_text.encode("utf-8")
        ))

    def test_public_json_apis_route_every_document_through_strict_parsing(self):
        contract = valid_contract()
        attestation = valid_attestation(contract)
        contract_text = json.dumps(contract, separators=(",", ":"))
        attestation_text = json.dumps(attestation, separators=(",", ":"))
        duplicate_contract = contract_text.replace(
            '{"schemaVersion":1,', '{"schemaVersion":1,"schemaVersion":1,', 1
        )
        duplicate_attestation = attestation_text.replace(
            '{"schemaVersion":1,', '{"schemaVersion":1,"schemaVersion":1,', 1
        )

        with self.assertRaisesRegex(ValueError, "duplicate"):
            contract_validator.validate_contract_json(duplicate_contract)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            contract_validator.validate_attestation_json(
                contract_text, duplicate_attestation
            )

    def test_duplicate_keys_are_rejected_at_root_role_output_and_summary_depths(self):
        duplicate_documents = {
            "root-same": '{"schemaVersion":1,"schemaVersion":1}',
            "root-different": '{"schemaVersion":1,"schemaVersion":2}',
            "role-same": '{"expectedOutputs":{"HAN_PLACES":{},"HAN_PLACES":{}}}',
            "role-different": (
                '{"expectedOutputs":{"HAN_PLACES":{"role":"HAN_PLACES"},'
                '"HAN_PLACES":{"role":"READINGS"}}}'
            ),
            "output-same": '{"output":{"role":"HAN_PLACES","role":"HAN_PLACES"}}',
            "output-different": '{"output":{"role":"HAN_PLACES","role":"READINGS"}}',
            "summary-same": '{"summary":{"cityCount":1,"cityCount":1}}',
            "summary-different": '{"summary":{"cityCount":1,"cityCount":2}}',
        }
        for location, text in duplicate_documents.items():
            with self.subTest(location=location):
                with self.assertRaisesRegex(ValueError, "duplicate"):
                    contract_validator.loads_json_strict(text)

    def test_nonfinite_json_constants_are_rejected_at_any_depth(self):
        for constant in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(constant=constant):
                with self.assertRaisesRegex(ValueError, "non-finite"):
                    contract_validator.loads_json_strict(
                        '{"outer":{"summary":{"count":' + constant + "}}}"
                    )


if __name__ == "__main__":
    unittest.main()
