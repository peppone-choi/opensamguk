#!/usr/bin/env python3
"""Generate the review-only Han tile-to-administrative-parent reconciliation ledger."""

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "data/curated/han/administrative-parent-reconciliation-v1.json"
INPUT_PATHS = {
    path: ROOT / path
    for path in (
        "data/map/han-tiles.json",
        "data/map/external-places.json",
        "data/curated/han/route-node-review-policy-v1.json",
        "data/curated/han/route-node-selection-v1.json",
        "data/curated/han/administrative-units.json",
        "data/curated/han/administrative-place-bindings-v1.json",
        "data/curated/han/administrative-temporal-adjudications-v1.json",
        "data/curated/han/route-node-location-adjudications-v1.json",
        "data/curated/han/route-node-selection-candidates-v1.json",
        "data/curated/han/route-node-validation-contract-v1.json",
        "data/map/han-administrative-history.json",
    )
}
REFERENCE_YEAR = 220
TEMPORAL_ROOT_KEYS = {
    "schemaVersion", "adjudicationSetId", "referenceYear", "sourceWitnesses", "adjudications"
}
TEMPORAL_SOURCE_WITNESS_KEYS = {"corpusPath", "sourceUrl", "snapshotSha256"}
TEMPORAL_ROW_KEYS = {
    "physicalPlaceRef", "administrativeUnitId", "historyChildId", "reviewState",
    "physicalWitness", "identityEvidence", "parentIntervals", "forbiddenIdentities",
}
TEMPORAL_PHYSICAL_WITNESS_KEYS = {
    "sourceId", "snapshotSha256", "locator", "physicalPlaceId",
    "nameCh", "nameFt", "typeCh", "begYr", "endYr", "lon", "lat",
    "gx", "gy", "junguozhiChildName",
}
TEMPORAL_IDENTITY_EVIDENCE_KEYS = {
    "corpusPath", "line", "sourceUrl", "snapshotSha256", "quote", "claim",
}
TEMPORAL_INTERVAL_KEYS = {
    "parentId", "effectiveFrom", "effectiveTo", "corpusPath", "line",
    "sourceUrl", "snapshotSha256", "quote",
}
PHYSICAL_PLACE_REF_RE = re.compile(r"chgis:v6:cnty:[1-9][0-9]*\Z")
ADMINISTRATIVE_UNIT_ID_RE = re.compile(r"hhs:[0-9]+:[^:]+:[0-9]{3}\Z")
HISTORY_CHILD_ID_RE = re.compile(r"county:hhs:[0-9]+:[1-9][0-9]*:[1-9][0-9]*\Z")
HISTORY_PARENT_ID_RE = re.compile(r"(?:commandery|kingdom):hhs:[0-9]+:[1-9][0-9]*\Z")
EXPECTED_TEMPORAL_SOURCE_WITNESSES = (
    (
        "data/corpus/hhs-111.txt",
        "https://zh.wikisource.org/wiki/後漢書/卷111",
        "78baef4b029d8af0d2b7255c69c97d81d5c140ad2b9db9ed4abe11dd9ed66476",
    ),
    (
        "data/corpus/sgz-01.txt",
        "https://zh.wikisource.org/wiki/三國志/卷01",
        "1001a34d5ae46667f6d9493206ed078db4d08aec2684a8d8e8f6c677dbe5fa3d",
    ),
)
EXPECTED_TEMPORAL_REVIEWS = {
    "chgis:v6:cnty:85083": {
        "physicalWitness": {
            "sourceId": "chgis-cnty-dbf",
            "snapshotSha256": "e782572a2af83fa246d608ffb13729835d535f3c010eee79ce0545f5430eb616",
            "locator": "SYS_ID=85083",
            "physicalPlaceId": "85083",
            "nameCh": "卫国",
            "nameFt": "衛國",
            "typeCh": "国",
            "begYr": 37,
            "endYr": 265,
            "lon": 115.11117,
            "lat": 35.88519,
            "gx": 422,
            "gy": 209,
            "junguozhiChildName": "衛",
        },
        "identityEvidence": {
            "corpusPath": "data/corpus/hhs-111.txt",
            "line": 81,
            "sourceUrl": "https://zh.wikisource.org/wiki/後漢書/卷111",
            "snapshotSha256": "78baef4b029d8af0d2b7255c69c97d81d5c140ad2b9db9ed4abe11dd9ed66476",
            "quote": "〖衞〗公國。本觀故國，姚姓，光武更名。",
            "claim": "衞公國 is the county-level HHS identity represented by CHGIS 85083 衛國",
        },
        "parentIntervals": [
            {
                "parentId": "commandery:hhs:111:24",
                "effectiveFrom": 184,
                "effectiveTo": 212,
                "corpusPath": "data/corpus/hhs-111.txt",
                "line": 51,
                "sourceUrl": "https://zh.wikisource.org/wiki/後漢書/卷111",
                "snapshotSha256": "78baef4b029d8af0d2b7255c69c97d81d5c140ad2b9db9ed4abe11dd9ed66476",
                "quote": "東郡",
            },
            {
                "parentId": "commandery:hhs:110:14",
                "effectiveFrom": 212,
                "effectiveTo": None,
                "corpusPath": "data/corpus/sgz-01.txt",
                "line": 166,
                "sourceUrl": "https://zh.wikisource.org/wiki/三國志/卷01",
                "snapshotSha256": "1001a34d5ae46667f6d9493206ed078db4d08aec2684a8d8e8f6c677dbe5fa3d",
                "quote": "割河內之蕩陰、朝歌、林慮，東郡之衞國、頓丘、東武陽、發干…以益魏郡",
            },
        ],
        "forbiddenIdentities": ["hhs:111:東郡:004"],
    }
}
FORBIDDEN_APPROVAL_INPUTS = (
    "junName",
    "junArrayIndex",
    "cityArrayIndex",
    "HanCityConst",
    "HanGateIndex",
    "nearestGeometry",
    "runtimeNumericId",
)
EMBEDDED_HASH_EDGES = (
    (
        "data/curated/han/route-node-review-policy-v1.json",
        ("inputs", "coordinateOverlaySha256"),
        "data/curated/han/administrative-place-bindings-v1.json",
    ),
    (
        "data/curated/han/route-node-review-policy-v1.json",
        ("inputs", "candidateManifest", "sha256"),
        "data/curated/han/route-node-selection-candidates-v1.json",
    ),
    (
        "data/curated/han/route-node-review-policy-v1.json",
        ("inputs", "locationAdjudications", "sha256"),
        "data/curated/han/route-node-location-adjudications-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-v1.json",
        ("provenance", "inputs", "administrativePlaceOverlay", "sha256"),
        "data/curated/han/administrative-place-bindings-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-v1.json",
        ("provenance", "inputs", "candidate", "sha256"),
        "data/curated/han/route-node-selection-candidates-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-v1.json",
        ("provenance", "inputs", "legacyTileMap", "sha256"),
        "data/map/han-tiles.json",
    ),
    (
        "data/curated/han/route-node-selection-v1.json",
        ("provenance", "inputs", "locationAdjudications", "sha256"),
        "data/curated/han/route-node-location-adjudications-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-v1.json",
        ("provenance", "inputs", "reviewPolicy", "sha256"),
        "data/curated/han/route-node-review-policy-v1.json",
    ),
    (
        "data/curated/han/route-node-location-adjudications-v1.json",
        ("inputOverlaySha256",),
        "data/curated/han/administrative-place-bindings-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-candidates-v1.json",
        ("provenance", "inputs", "administrativePlaceOverlay", "sha256"),
        "data/curated/han/administrative-place-bindings-v1.json",
    ),
    (
        "data/curated/han/route-node-selection-candidates-v1.json",
        ("provenance", "inputs", "legacyTileMap", "sha256"),
        "data/map/han-tiles.json",
    ),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_inputs(input_paths: dict[str, Path] | None = None) -> tuple[dict[str, dict], dict[str, dict]]:
    paths = INPUT_PATHS if input_paths is None else input_paths
    if set(paths) != set(INPUT_PATHS):
        raise ValueError("input paths must contain the complete pinned reconciliation input set")
    documents = {}
    records = {}
    for repository_path in sorted(paths):
        path = Path(paths[repository_path])
        raw = path.read_bytes()
        try:
            document = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSON input {repository_path}: {error}") from error
        if not isinstance(document, dict):
            raise ValueError(f"input {repository_path} must contain a JSON object")
        documents[repository_path] = document
        records[repository_path] = {
            "path": repository_path,
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
    return documents, records


def expand_rle(runs: object, expected_cells: int, label: str) -> list[int]:
    if not isinstance(runs, list):
        raise ValueError(f"{label} must be an RLE array")
    values = []
    for run in runs:
        if not isinstance(run, list) or len(run) != 2:
            raise ValueError(f"{label} run must contain value and count")
        value, count = run
        if type(value) is not int or type(count) is not int or count < 0:
            raise ValueError(f"{label} run values and counts must be integers")
        if count > expected_cells - len(values):
            raise ValueError(f"{label} exceeds the expected cell count")
        values.extend([value] * count)
    if len(values) != expected_cells:
        raise ValueError(f"{label} has {len(values)} cells; expected {expected_cells}")
    return values


def terminal_physical_place_id(reference: object) -> str:
    if not isinstance(reference, str) or ":" not in reference:
        raise ValueError("physicalPlaceRef must be a namespaced stable ID")
    terminal = reference.rsplit(":", 1)[-1]
    if not terminal:
        raise ValueError("physicalPlaceRef must have a non-empty terminal ID")
    return terminal


def _nested(document: object, keys: tuple[str, ...], label: str) -> object:
    current = document
    for key in keys:
        if not isinstance(current, dict) or key not in current:
            raise ValueError(f"input contract missing {label}: {'.'.join(keys)}")
        current = current[key]
    return current


def _require_equal(actual: object, expected: object, label: str) -> None:
    if actual != expected:
        raise ValueError(f"input contract mismatch for {label}: {actual!r} != {expected!r}")


def _require_exact_keys(value: object, expected: set[str], label: str) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        actual = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise ValueError(f"schema exact keys mismatch for {label}: {actual!r}")
    return value


def _require_nonempty_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} requires a nonempty string")
    return value


def _validate_temporal_evidence(
    evidence: object, expected_keys: set[str], label: str
) -> dict:
    evidence = _require_exact_keys(evidence, expected_keys, label)
    for key in {"corpusPath", "sourceUrl", "snapshotSha256", "quote"} | (
        {"claim"} if "claim" in expected_keys else set()
    ):
        _require_nonempty_string(evidence.get(key), f"{label}.{key}")
    line = evidence.get("line")
    if type(line) is not int or line <= 0:
        raise ValueError(f"{label}.line requires a positive integer")
    corpus_path = evidence["corpusPath"]
    if not re.fullmatch(r"data/corpus/[a-z0-9-]+\.txt", corpus_path):
        raise ValueError(f"{label}.corpusPath has invalid source identity")
    if not evidence["sourceUrl"].startswith("https://"):
        raise ValueError(f"{label}.sourceUrl has invalid source identity")
    digest = evidence["snapshotSha256"]
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError(f"{label}.snapshotSha256 has invalid hash")
    return evidence


def _validate_temporal_physical_witness(witness: object, label: str) -> dict:
    witness = _require_exact_keys(witness, TEMPORAL_PHYSICAL_WITNESS_KEYS, label)
    for key in {
        "sourceId", "snapshotSha256", "locator", "physicalPlaceId",
        "nameCh", "nameFt", "typeCh", "junguozhiChildName",
    }:
        _require_nonempty_string(witness.get(key), f"{label}.{key}")
    if not re.fullmatch(r"[0-9a-f]{64}", witness["snapshotSha256"]):
        raise ValueError(f"{label}.snapshotSha256 has invalid hash")
    for key in {"begYr", "endYr", "gx", "gy"}:
        if type(witness.get(key)) is not int:
            raise ValueError(f"{label}.{key} requires an integer")
    for key in {"lon", "lat"}:
        if type(witness.get(key)) is not float:
            raise ValueError(f"{label}.{key} requires a JSON number with decimal precision")
    if witness["begYr"] > witness["endYr"]:
        raise ValueError(f"{label} has an invalid source active range")
    if witness["gx"] < 0 or witness["gy"] < 0:
        raise ValueError(f"{label} has an invalid canonical grid cell")
    return witness


def _require_exact_membership(
    actual: object,
    expected: set[object],
    label: str,
) -> None:
    if not isinstance(actual, list):
        raise ValueError(f"contract membership mismatch for {label}: expected an array")
    try:
        actual_members = set(actual)
    except TypeError as error:
        raise ValueError(
            f"contract membership mismatch for {label}: members must be scalar"
        ) from error
    if len(actual) != len(actual_members) or actual_members != expected:
        raise ValueError(
            f"contract membership mismatch for {label}: {actual!r} != {expected!r}"
        )


def _require_closed_enum(
    rows: object,
    field: str,
    allowed: set[str],
    label: str,
    *,
    optional: bool = False,
) -> None:
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise ValueError(f"input contract requires object rows for {label}")
    for row in rows:
        if field not in row and optional:
            continue
        if row.get(field) not in allowed:
            raise ValueError(f"closed enum mismatch for {label}.{field}: {row.get(field)!r}")


def _validate_embedded_hash_edges(
    documents: dict[str, dict], input_records: dict[str, dict]
) -> None:
    for path in INPUT_PATHS:
        record = input_records.get(path)
        if not isinstance(record, dict) or record.get("path") != path:
            raise ValueError(f"input contract mismatch for pinned path {path}")
        digest = record.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(
            character not in "0123456789abcdef" for character in digest
        ):
            raise ValueError(f"input contract mismatch for pinned hash {path}")
    for source_path, keys, target_path in EMBEDDED_HASH_EDGES:
        embedded = _nested(documents[source_path], keys, source_path)
        expected = input_records[target_path]["sha256"]
        if embedded != expected:
            raise ValueError(
                "embedded input hash mismatch: "
                f"{source_path}:{'.'.join(keys)} -> {target_path}"
            )

    policy_inputs = documents["data/curated/han/route-node-review-policy-v1.json"]["inputs"]
    _require_equal(
        policy_inputs.get("candidateManifest", {}).get("path"),
        "data/curated/han/route-node-selection-candidates-v1.json",
        "review policy candidateManifest path",
    )
    _require_equal(
        policy_inputs.get("locationAdjudications", {}).get("path"),
        "data/curated/han/route-node-location-adjudications-v1.json",
        "review policy locationAdjudications path",
    )
    candidate_inputs = documents[
        "data/curated/han/route-node-selection-candidates-v1.json"
    ]["provenance"]["inputs"]
    _require_equal(
        candidate_inputs.get("administrativePlaceOverlay", {}).get("path"),
        "data/curated/han/administrative-place-bindings-v1.json",
        "candidate administrativePlaceOverlay path",
    )
    _require_equal(
        candidate_inputs.get("legacyTileMap", {}).get("path"),
        "data/map/han-tiles.json",
        "candidate legacyTileMap path",
    )


def _validate_review_chain(
    documents: dict[str, dict], input_records: dict[str, dict]
) -> None:
    policy = documents["data/curated/han/route-node-review-policy-v1.json"]
    selection = documents["data/curated/han/route-node-selection-v1.json"]
    bindings = documents["data/curated/han/administrative-place-bindings-v1.json"]
    temporal = documents["data/curated/han/administrative-temporal-adjudications-v1.json"]
    adjudications = documents["data/curated/han/route-node-location-adjudications-v1.json"]
    candidates = documents["data/curated/han/route-node-selection-candidates-v1.json"]
    contract = documents["data/curated/han/route-node-validation-contract-v1.json"]
    history = documents["data/map/han-administrative-history.json"]
    tiles = documents["data/map/han-tiles.json"]
    external = documents["data/map/external-places.json"]

    _require_equal(policy.get("schemaVersion"), 1, "review policy schemaVersion")
    _require_equal(policy.get("policyId"), "han-w0c-route-node-review-policy-v1", "review policy identity")
    _require_equal(selection.get("schemaVersion"), 1, "selection schemaVersion")
    _require_equal(selection.get("selectionId"), "han-route-node-selection-v1", "selection identity")
    _require_equal(selection.get("baselineYear"), REFERENCE_YEAR, "selection baseline year")
    _require_equal(bindings.get("schemaVersion"), 1, "bindings schemaVersion")
    _require_equal(bindings.get("catalogId"), "hhs-junguozhi-administrative-units-v1", "bindings identity")
    _require_equal(bindings.get("sourceYear"), REFERENCE_YEAR, "bindings source year")
    _require_equal(temporal.get("schemaVersion"), 1, "temporal adjudications schemaVersion")
    _require_equal(
        temporal.get("adjudicationSetId"),
        "han-administrative-temporal-adjudications-v1",
        "temporal adjudications identity",
    )
    _require_equal(temporal.get("referenceYear"), REFERENCE_YEAR, "temporal adjudications year")
    _require_equal(adjudications.get("schemaVersion"), 1, "adjudications schemaVersion")
    _require_equal(
        adjudications.get("adjudicationSetId"),
        "han-w0c-ambiguous-location-adjudications-v1",
        "adjudications identity",
    )
    _require_equal(candidates.get("schemaVersion"), 1, "candidates schemaVersion")
    _require_equal(
        candidates.get("selectionId"),
        "han-route-node-selection-candidates-v1",
        "candidates identity",
    )
    _require_equal(candidates.get("fixedYear"), REFERENCE_YEAR, "candidates fixed year")
    _require_equal(contract.get("schemaVersion"), 1, "validation contract schemaVersion")
    _require_equal(
        contract.get("contractId"),
        "han-w0-route-node-validation-contract-v1",
        "validation contract identity",
    )
    _require_equal(history.get("schemaVersion"), 1, "administrative history schemaVersion")
    _require_exact_membership(
        history.get("supportedYears"),
        {184, 190, 200, 208, 220, 234, 263, 280},
        "administrative history supported year contract",
    )
    _require_equal(
        history.get("catalogReference"),
        "../curated/han/administrative-units.json",
        "administrative history catalog identity",
    )
    _require_equal(history.get("requireCountySeats"), False, "administrative history seat contract")
    _require_equal(tiles.get("_meta", {}).get("year"), REFERENCE_YEAR, "han tiles reference year")
    _require_equal(
        tiles.get("_meta", {}).get("generator"),
        "tools/map/build_tile_grid.py",
        "han tiles generator identity",
    )
    _require_equal(
        external.get("_meta", {}).get("generator"),
        "tools/map/build_external_places.py",
        "external places generator identity",
    )

    rows = selection.get("routeNodes")
    expected = policy.get("expectedSelection", {}).get("routeNodeCount")
    if policy.get("status") != "APPROVED" or selection.get("reviewState") != "APPROVED":
        raise ValueError("route-node selection review chain is not approved")
    if not isinstance(rows, list) or expected != len(rows) or contract.get("expectedSelectionCount") != len(rows):
        raise ValueError("route-node selection count contract mismatch")
    if any(not isinstance(row, dict) or row.get("reviewState") != "APPROVED" for row in rows):
        raise ValueError("every route-node selection row must be approved")
    if adjudications.get("status") != "REVIEWED":
        raise ValueError("location adjudications must be reviewed")
    _require_exact_membership(
        contract.get("allowedNodeClasses"),
        {"COUNTY_NODE", "DAO_NODE", "MARQUISATE_NODE", "TOWN_NODE"},
        "validation contract allowed node classes",
    )
    _require_equal(contract.get("expectedSelectionCount"), 780, "validation contract selection count")
    scenario_catalog = candidates.get("scenarioCatalog")
    if not isinstance(scenario_catalog, list) or not scenario_catalog:
        raise ValueError("input contract mismatch for candidate scenario count contract")
    scenario_resource_count = len(scenario_catalog)
    _require_equal(
        contract.get("expectedActiveScenarioResourceCount"),
        scenario_resource_count,
        "validation contract scenario count",
    )
    _require_equal(
        selection.get("summary"),
        {"approvedCount": 780, "historicalBindingCounts": {"HHS_ADMINISTRATIVE_UNIT": 780}},
        "selection summary contract",
    )
    _require_equal(
        policy.get("expectedSelection"),
        {
            "externalHistoricalBindingCount": 0,
            "externalLocationClaimCount": 8,
            "hhsAdministrativeBindingCount": 780,
            "overlayUniqueCount": 722,
            "polityPresenceCount": 0,
            "remoteGateCount": 0,
            "reviewedAmbiguousCount": 50,
            "routeNodeCount": 780,
            "sourcePlaceholderCount": 0,
        },
        "review policy expected selection contract",
    )
    batches = policy.get("selectionBatches")
    if (
        not isinstance(batches, list)
        or len(batches) != 3
        or not all(isinstance(row, dict) for row in batches)
        or {
        (row.get("batchId"), row.get("expectedCount"), row.get("reviewState"))
        for row in batches
        }
        != {
            ("w0b-overlay-unique-220", 722, "APPROVED"),
            ("w0c-reviewed-ambiguity", 50, "APPROVED"),
            ("w0c-hhs-external-location", 8, "APPROVED"),
        }
    ):
        raise ValueError("closed enum or count mismatch for review policy selection batches")
    _require_equal(
        selection.get("reviewPolicy", {}).get("policyId"),
        policy["policyId"],
        "selection review policy identity",
    )
    _require_equal(
        candidates.get("candidatePolicy", {}).get("reviewState"),
        "PENDING",
        "candidate policy state enum",
    )
    _require_equal(
        candidates.get("candidatePolicy", {}).get("automaticSelectionCount"),
        0,
        "candidate policy automatic selection contract",
    )
    _require_equal(
        candidates.get("provenance", {}).get("scenarioResourceCount"),
        scenario_resource_count,
        "candidate scenario count contract",
    )

    _require_closed_enum(tiles.get("cities"), "kind", {"COMMANDERY", "COUNTY", "EXTERNAL_PLACE", "KINGDOM", "PROVINCE"}, "han tiles cities")
    _require_closed_enum(external.get("places"), "conf", {"DISPUTED", "IDENTIFIED"}, "external places")
    _require_closed_enum(external.get("places"), "kind", {"COMMANDERY", "EXTERNAL_PLACE", "KINGDOM"}, "external places")
    _require_closed_enum(bindings.get("administrativeUnits"), "joinStatus", {"AMBIGUOUS_POINT", "NO_COORDINATE_CANDIDATE", "RESOLVED_POINT", "SOURCE_PLACEHOLDER"}, "administrative bindings")
    _require_closed_enum(
        temporal.get("adjudications"),
        "reviewState",
        {"APPROVED_EXACT_TEMPORAL_BINDING"},
        "temporal adjudications",
    )
    _require_closed_enum(adjudications.get("adjudications"), "reviewState", {"APPROVED_FOR_SELECTION", "REJECTED_FALSE_HOMONYM"}, "location adjudications")
    _require_closed_enum(adjudications.get("adjudications"), "rationaleCode", {"FALSE_HOMONYM_OUTSIDE_CANONICAL_GROUP", "GROUP_GEOGRAPHY_AND_TEMPORAL_RECORD_REVIEW", "HISTORICAL_LOCATION_CORRECTION"}, "location adjudications")
    _require_closed_enum(candidates.get("candidates"), "origin", {"CURRENT_780", "HHS_REPLACEMENT_POOL"}, "route-node candidates")
    _require_closed_enum(candidates.get("candidates"), "reviewState", {"PENDING"}, "route-node candidates")
    _require_closed_enum(candidates.get("candidates"), "classification", {"EXTERNAL_OR_LATER_OR_MOVING", "HHS_AMBIGUOUS", "HHS_ATTRIBUTION_CONFLICT", "HHS_RESOLVED", "HHS_UNMAPPED"}, "route-node candidates", optional=True)
    _require_closed_enum(candidates.get("candidates"), "overlayJoinStatus", {"AMBIGUOUS_POINT", "NO_COORDINATE_CANDIDATE", "RESOLVED_POINT", "SOURCE_PLACEHOLDER"}, "route-node candidates", optional=True)
    _require_closed_enum(candidates.get("candidates"), "unitType", {"COUNTY", "DAO", "MARQUISATE", "TOWN"}, "route-node candidates", optional=True)
    _require_closed_enum(rows, "nodeClass", {"COUNTY_NODE", "DAO_NODE", "MARQUISATE_NODE", "TOWN_NODE"}, "approved route-node selection")
    _require_closed_enum(rows, "historicalBindingBasis", {"HHS_ADMINISTRATIVE_UNIT"}, "approved route-node selection")
    _require_closed_enum(rows, "seatRole", {"COMMANDERY_SEAT", "NON_SEAT"}, "approved route-node selection")
    _require_closed_enum(rows, "legacyDisposition", {"REPLACED", "RETAINED"}, "approved route-node selection")
    _require_closed_enum(
        [row.get("selectionRationale") for row in rows],
        "method",
        {"APPROVED_REVIEW_BATCH"},
        "approved route-node selection rationale",
    )
    _require_closed_enum(
        [row.get("selectionRationale") for row in rows],
        "batchId",
        {"w0b-overlay-unique-220", "w0c-hhs-external-location", "w0c-reviewed-ambiguity"},
        "approved route-node selection rationale",
    )
    _require_closed_enum(
        [row.get("locationAdjudication") for row in rows],
        "kind",
        {"APPROVED_LOCATION_ONLY_CLAIM", "EXPLICIT_AMBIGUITY_REVIEW", "W0B_GLOBAL_UNIQUE_220"},
        "approved route-node location adjudication",
    )
    _validate_embedded_hash_edges(documents, input_records)


def _catalog_binding_context(catalog: dict, bindings: dict) -> tuple[dict[str, dict], set[str]]:
    raw_groups = catalog.get("groups")
    binding_rows = bindings.get("administrativeUnits")
    if not isinstance(raw_groups, list) or not isinstance(binding_rows, list):
        raise ValueError("catalog/bindings require administrative identity rows")
    catalog_by_administrative_id = {}
    for group_index, group in enumerate(raw_groups, start=1):
        if not isinstance(group, dict) or not isinstance(group.get("units"), list):
            raise ValueError("catalog group identity must be an object with units")
        volume = group.get("sourceVolume")
        canonical_group = group.get("canonicalGroup")
        group_type = group.get("groupType")
        if type(volume) is not int or not isinstance(canonical_group, str) or group_type not in {
            "COMMANDERY", "KINGDOM"
        }:
            raise ValueError("catalog group identity is invalid")
        parent_prefix = group_type.lower()
        parent_id = f"{parent_prefix}:hhs:{volume}:{group_index}"
        for child_index, child in enumerate(group["units"], start=1):
            if not isinstance(child, dict):
                raise ValueError("catalog child identity must be an object")
            ordinal = child.get("ordinal")
            if ordinal != child_index:
                raise ValueError("catalog child identity ordinal mismatch")
            identity = {
                "sourceVolume": volume,
                "canonicalGroup": canonical_group,
                "ordinal": ordinal,
            }
            administrative_id = f"hhs:{volume}:{canonical_group}:{ordinal:03d}"
            if administrative_id in catalog_by_administrative_id:
                raise ValueError("duplicate catalog administrative identity")
            catalog_by_administrative_id[administrative_id] = {
                "identity": identity,
                "historyChildId": f"county:hhs:{volume}:{group_index}:{ordinal}",
                "inheritedParentId": parent_id,
                "sourceName": child.get("sourceName"),
                "sourceCitation": child.get("sourceCitation"),
            }

    binding_by_id = {}
    for row in binding_rows:
        if not isinstance(row, dict):
            raise ValueError("binding administrative identity must be an object")
        administrative_id = row.get("administrativeUnitId")
        if not isinstance(administrative_id, str) or not ADMINISTRATIVE_UNIT_ID_RE.fullmatch(
            administrative_id
        ):
            raise ValueError("binding administrative identity has invalid stable ID")
        if administrative_id in binding_by_id:
            raise ValueError("duplicate binding administrative identity")
        binding_by_id[administrative_id] = row

    return catalog_by_administrative_id, set(binding_by_id)


def _temporal_adjudication_context(
    document: dict, catalog: dict, bindings: dict
) -> dict[str, dict]:
    _require_exact_keys(document, TEMPORAL_ROOT_KEYS, "temporal adjudications root")
    raw_witnesses = document.get("sourceWitnesses")
    if not isinstance(raw_witnesses, list) or not raw_witnesses:
        raise ValueError("temporal source evidence witnesses must be a nonempty array")
    witnesses = set()
    normalized_witnesses = []
    for index, raw_witness in enumerate(raw_witnesses):
        label = f"temporal sourceWitnesses[{index}]"
        witness = _require_exact_keys(raw_witness, TEMPORAL_SOURCE_WITNESS_KEYS, label)
        for key in TEMPORAL_SOURCE_WITNESS_KEYS:
            _require_nonempty_string(witness.get(key), f"{label}.{key}")
        if not re.fullmatch(r"data/corpus/[a-z0-9-]+\.txt", witness["corpusPath"]):
            raise ValueError(f"{label}.corpusPath has invalid source identity")
        if not witness["sourceUrl"].startswith("https://"):
            raise ValueError(f"{label}.sourceUrl has invalid source identity")
        if not re.fullmatch(r"[0-9a-f]{64}", witness["snapshotSha256"]):
            raise ValueError(f"{label}.snapshotSha256 has invalid hash")
        identity = tuple(witness[key] for key in ("corpusPath", "sourceUrl", "snapshotSha256"))
        if identity in witnesses:
            raise ValueError("duplicate temporal source evidence witness")
        witnesses.add(identity)
        normalized_witnesses.append(identity)
    if tuple(normalized_witnesses) != EXPECTED_TEMPORAL_SOURCE_WITNESSES:
        raise ValueError("temporal source witnesses must match the exact reviewed source contract")
    rows = document.get("adjudications")
    if not isinstance(rows, list) or not rows:
        raise ValueError("temporal adjudications must contain reviewed rows")
    catalog_by_id, known_binding_ids = _catalog_binding_context(catalog, bindings)
    binding_by_id = {
        row["administrativeUnitId"]: row for row in bindings["administrativeUnits"]
    }
    known_parent_ids = {
        value["inheritedParentId"] for value in catalog_by_id.values()
    }
    by_city_id = {}
    for row_index, row in enumerate(rows):
        label = f"temporal adjudications[{row_index}]"
        row = _require_exact_keys(row, TEMPORAL_ROW_KEYS, label)
        physical_ref = row.get("physicalPlaceRef")
        administrative_unit_id = row.get("administrativeUnitId")
        history_child_id = row.get("historyChildId")
        physical_witness = row.get("physicalWitness")
        evidence = row.get("identityEvidence")
        intervals = row.get("parentIntervals")
        forbidden = row.get("forbiddenIdentities")
        if not isinstance(physical_ref, str) or not PHYSICAL_PLACE_REF_RE.fullmatch(physical_ref):
            raise ValueError("temporal adjudication requires a CHGIS county stable ID")
        if not isinstance(administrative_unit_id, str) or not ADMINISTRATIVE_UNIT_ID_RE.fullmatch(
            administrative_unit_id
        ):
            raise ValueError("temporal adjudication requires an HHS administrative stable ID")
        if not isinstance(history_child_id, str) or not HISTORY_CHILD_ID_RE.fullmatch(history_child_id):
            raise ValueError("temporal adjudication requires a history county stable ID")
        physical_witness = _validate_temporal_physical_witness(
            physical_witness, f"{label}.physicalWitness"
        )
        if physical_witness["physicalPlaceId"] != physical_ref.rsplit(":", 1)[-1]:
            raise ValueError("temporal physical witness disagrees with physicalPlaceRef")
        catalog_identity = catalog_by_id.get(administrative_unit_id)
        binding = binding_by_id.get(administrative_unit_id)
        if catalog_identity is None or binding is None:
            raise ValueError("temporal administrative identity is absent from catalog/bindings")
        if binding.get("identity") != catalog_identity["identity"]:
            raise ValueError("temporal administrative binding identity disagrees with catalog")
        if history_child_id != catalog_identity["historyChildId"]:
            raise ValueError("temporal history child identity disagrees with catalog binding")
        evidence = _validate_temporal_evidence(
            evidence, TEMPORAL_IDENTITY_EVIDENCE_KEYS, f"{label}.identityEvidence"
        )
        if tuple(
            evidence[key] for key in ("corpusPath", "sourceUrl", "snapshotSha256")
        ) not in witnesses:
            raise ValueError("temporal identity evidence has an unpinned source witness")
        citation = catalog_identity["sourceCitation"]
        if not isinstance(citation, dict) or any(
            evidence[key] != citation.get(key)
            for key in ("corpusPath", "line", "sourceUrl", "snapshotSha256")
        ):
            raise ValueError("temporal identity evidence disagrees with catalog source witness")
        if catalog_identity["sourceName"] not in evidence["quote"]:
            raise ValueError("temporal identity evidence quote does not name the catalog child")
        if not isinstance(intervals, list) or len(intervals) < 2:
            raise ValueError("temporal adjudication requires at least two parent intervals")
        previous_end = None
        active_parent = None
        for index, interval in enumerate(intervals):
            interval = _require_exact_keys(
                interval, TEMPORAL_INTERVAL_KEYS, f"{label}.parentIntervals[{index}]"
            )
            start = interval.get("effectiveFrom")
            end = interval.get("effectiveTo")
            if type(start) is not int or (end is not None and type(end) is not int):
                raise ValueError("temporal parent interval requires integer bounds")
            if end is not None and start >= end:
                raise ValueError("temporal parent interval requires start before end")
            if end is None and index != len(intervals) - 1:
                raise ValueError("only the final temporal parent interval may be open")
            if index and previous_end != start:
                raise ValueError("temporal parent intervals must be contiguous")
            parent_id = interval.get("parentId")
            if not isinstance(parent_id, str) or not HISTORY_PARENT_ID_RE.fullmatch(parent_id):
                raise ValueError("temporal parent interval has invalid parent identity")
            if parent_id not in known_parent_ids:
                raise ValueError("temporal parent interval has unknown catalog parent identity")
            interval = _validate_temporal_evidence(
                interval, TEMPORAL_INTERVAL_KEYS, f"{label}.parentIntervals[{index}]"
            )
            if tuple(
                interval[key] for key in ("corpusPath", "sourceUrl", "snapshotSha256")
            ) not in witnesses:
                raise ValueError("temporal parent interval evidence has an unpinned source witness")
            if start <= REFERENCE_YEAR and (end is None or REFERENCE_YEAR < end):
                active_parent = interval["parentId"]
            previous_end = end
        if intervals[0]["parentId"] != catalog_identity["inheritedParentId"]:
            raise ValueError("temporal initial parent identity disagrees with catalog binding")
        if active_parent is None:
            raise ValueError("temporal adjudication has no parent at the reference year")
        if (
            not isinstance(forbidden, list)
            or not forbidden
            or len(forbidden) != len(set(forbidden))
            or any(
                not isinstance(value, str)
                or not ADMINISTRATIVE_UNIT_ID_RE.fullmatch(value)
                or value not in known_binding_ids
                or value == administrative_unit_id
                for value in forbidden
            )
        ):
            raise ValueError("temporal adjudication requires forbidden stable identities")
        expected_review = EXPECTED_TEMPORAL_REVIEWS.get(physical_ref)
        if expected_review is None:
            raise ValueError("temporal adjudication is absent from the exact reviewed contract")
        if physical_witness != expected_review["physicalWitness"]:
            raise ValueError("temporal physical witness differs from reviewed CHGIS evidence")
        if evidence != expected_review["identityEvidence"]:
            raise ValueError("temporal identity evidence quote/claim differs from reviewed evidence")
        if intervals != expected_review["parentIntervals"]:
            raise ValueError("temporal parent interval differs from reviewed evidence")
        if forbidden != expected_review["forbiddenIdentities"]:
            raise ValueError("temporal forbidden identities differ from reviewed identity contract")
        city_id = physical_ref.rsplit(":", 1)[-1]
        if city_id in by_city_id:
            raise ValueError("duplicate temporal adjudication physical place")
        by_city_id[city_id] = {
            "administrativeUnitId": administrative_unit_id,
            "historyChildId": history_child_id,
            "physicalPlaceRef": physical_ref,
            "physicalWitness": physical_witness,
            "activeParentId": active_parent,
            "parentIntervals": intervals,
            "forbiddenIdentities": forbidden,
        }
    if set(row["physicalPlaceRef"] for row in rows) != set(EXPECTED_TEMPORAL_REVIEWS):
        raise ValueError("temporal adjudications must equal the exact reviewed physical-place set")
    return by_city_id


def _tile_context(tiles: dict) -> dict:
    meta = tiles.get("_meta")
    cities = tiles.get("cities")
    juns = tiles.get("juns")
    if not isinstance(meta, dict) or not isinstance(cities, list) or not isinstance(juns, list):
        raise ValueError("han tiles must contain _meta, cities, and juns")
    cols, rows = meta.get("cols"), meta.get("rows")
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError("han tile dimensions must be positive integers")
    cells = cols * rows
    owner = expand_rle(tiles.get("owner"), cells, "owner")
    seat_owner = expand_rle(tiles.get("seatOwner"), cells, "seatOwner")
    city_by_id = {}
    city_index_by_id = {}
    for index, city in enumerate(cities):
        if not isinstance(city, dict) or not isinstance(city.get("id"), (str, int)):
            raise ValueError("every city requires a stable string or integer id")
        city_id = str(city["id"])
        if city_id in city_by_id:
            raise ValueError(f"duplicate city id: {city_id}")
        col, row = city.get("col"), city.get("row")
        if type(col) is not int or type(row) is not int or not (0 <= col < cols and 0 <= row < rows):
            raise ValueError(f"city {city_id} has an invalid coordinate")
        city_by_id[city_id] = city
        city_index_by_id[city_id] = index
    land_counts = Counter(value for value in owner if value >= 0)
    if any(index >= len(cities) for index in land_counts):
        raise ValueError("owner references a missing city")
    if set(land_counts) != set(range(len(cities))):
        raise ValueError("every city must own at least one land cell")
    if any(value < -1 or value >= len(juns) for value in seat_owner):
        raise ValueError("seatOwner references a missing jun")
    if any((owner_value == -1) != (seat_value == -1) for owner_value, seat_value in zip(owner, seat_owner)):
        raise ValueError("owner and seatOwner coverage disagree")
    footprints = defaultdict(Counter)
    for owner_index, jun_index in zip(owner, seat_owner):
        if owner_index >= 0:
            footprints[owner_index][jun_index] += 1
    return {
        "cols": cols,
        "rows": rows,
        "cities": cities,
        "juns": juns,
        "owner": owner,
        "seatOwner": seat_owner,
        "cityById": city_by_id,
        "cityIndexById": city_index_by_id,
        "landCounts": land_counts,
        "footprints": footprints,
    }


def _selection_context(selection: dict, tiles: dict) -> dict:
    matched_by_city_id = {}
    absent = []
    seen_units = set()
    seen_route_keys = set()
    for row in selection["routeNodes"]:
        unit_id = row.get("administrativeUnitId")
        route_key = row.get("routeNodeKey")
        physical_ref = row.get("physicalPlaceRef")
        if not isinstance(unit_id, str) or not unit_id or unit_id in seen_units:
            raise ValueError("route-node administrativeUnitId values must be unique stable IDs")
        if not isinstance(route_key, str) or not route_key or route_key in seen_route_keys:
            raise ValueError("routeNodeKey values must be unique stable IDs")
        seen_units.add(unit_id)
        seen_route_keys.add(route_key)
        city_id = terminal_physical_place_id(physical_ref)
        if city_id in tiles["cityById"]:
            if city_id in matched_by_city_id:
                raise ValueError(f"multiple approved selections target city {city_id}")
            matched_by_city_id[city_id] = row
        else:
            absent.append(
                {
                    "administrativeUnitId": unit_id,
                    "physicalPlaceRef": physical_ref,
                    "terminalPhysicalPlaceId": city_id,
                }
            )
    return {
        "matchedByCityId": matched_by_city_id,
        "absent": sorted(absent, key=lambda row: (row["administrativeUnitId"], row["physicalPlaceRef"])),
    }


def _jun_diagnostics(tiles: dict, selections: dict) -> tuple[dict, dict]:
    groups_by_jun = defaultdict(set)
    anchors_by_jun = defaultdict(list)
    for city_id, selection in selections["matchedByCityId"].items():
        city = tiles["cityById"][city_id]
        jun_index = tiles["seatOwner"][city["row"] * tiles["cols"] + city["col"]]
        if jun_index < 0:
            raise ValueError(f"approved city {city_id} coordinate is not on land")
        parent_ref = selection.get("parentRef")
        if not isinstance(parent_ref, str) or not parent_ref:
            raise ValueError("approved selection requires a stable parentRef")
        groups_by_jun[jun_index].add(parent_ref)
        anchors_by_jun[jun_index].append((city_id, selection, city))
    for jun_index in anchors_by_jun:
        anchors_by_jun[jun_index].sort(key=lambda item: (item[0], item[1]["administrativeUnitId"]))
    return groups_by_jun, anchors_by_jun


def _binding_groups(bindings: dict) -> dict[str, list[str]]:
    rows = bindings.get("administrativeUnits")
    if not isinstance(rows, list):
        raise ValueError("administrative place bindings must contain administrativeUnits")
    by_group = defaultdict(list)
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("administrative binding rows must be objects")
        unit_id = row.get("administrativeUnitId")
        identity = row.get("identity")
        group = identity.get("canonicalGroup") if isinstance(identity, dict) else None
        if not isinstance(unit_id, str) or unit_id in seen or not isinstance(group, str) or not group:
            raise ValueError("administrative binding identity contract is invalid")
        seen.add(unit_id)
        by_group[group].append(unit_id)
    return {group: sorted(unit_ids) for group, unit_ids in by_group.items()}


def _external_context(external: dict, candidates: dict) -> dict:
    places = external.get("places")
    candidate_rows = candidates.get("candidates")
    if not isinstance(places, list) or not isinstance(candidate_rows, list):
        raise ValueError("external places and route-node candidates must contain row arrays")
    place_by_id = {}
    hub_by_name = {}
    for place in places:
        if not isinstance(place, dict) or not isinstance(place.get("id"), str):
            raise ValueError("external places require stable ids")
        if place["id"] in place_by_id:
            raise ValueError(f'duplicate external place id: {place["id"]}')
        place_by_id[place["id"]] = place
        if place.get("kind") == "EXTERNAL_PLACE" and place.get("hub") is True:
            name = place.get("nameFt")
            if not isinstance(name, str) or not name or name in hub_by_name:
                raise ValueError("external hub names must be unique non-empty diagnostics")
            hub_by_name[name] = place
    pending_by_city = defaultdict(list)
    pending_by_group = defaultdict(list)
    for row in candidate_rows:
        if not isinstance(row, dict):
            raise ValueError("route-node candidate rows must be objects")
        if row.get("classification") != "EXTERNAL_OR_LATER_OR_MOVING":
            continue
        if row.get("reviewState") != "PENDING":
            raise ValueError("external route-node candidates must remain PENDING")
        key = row.get("candidateKey")
        if not isinstance(key, str) or not key:
            raise ValueError("external route-node candidates require candidateKey")
        tile_id = row.get("legacyTileId")
        group = row.get("legacyOwnerGroup")
        if isinstance(tile_id, str):
            pending_by_city[tile_id].append(key)
        if isinstance(group, str):
            pending_by_group[group].append(key)
    return {
        "placeById": place_by_id,
        "hubByName": hub_by_name,
        "pendingByCity": {key: sorted(values) for key, values in pending_by_city.items()},
        "pendingByGroup": {key: sorted(values) for key, values in pending_by_group.items()},
    }


def _seat_jun_diagnostic(city: dict, tiles: dict, groups_by_jun: dict) -> tuple[int, dict]:
    jun_index = tiles["seatOwner"][city["row"] * tiles["cols"] + city["col"]]
    if jun_index < 0:
        raise ValueError(f'city {city["id"]} coordinate is not on political land')
    jun = tiles["juns"][jun_index]
    if not isinstance(jun, dict):
        raise ValueError("jun rows must be objects")
    return jun_index, {
        "diagnosticOnly": True,
        "junArrayIndex": jun_index,
        "nameCh": jun.get("nameCh"),
        "approvedAdministrativeGroupIds": sorted(groups_by_jun[jun_index]),
    }


def _footprint_diagnostic(city_index: int, coordinate_jun: int, tiles: dict) -> dict:
    distribution = tiles["footprints"][city_index]
    maximum = max(distribution.values())
    majority = sorted(index for index, count in distribution.items() if count == maximum)
    rows = []
    for jun_index, cell_count in distribution.items():
        jun = tiles["juns"][jun_index]
        rows.append(
            {
                "junArrayIndex": jun_index,
                "nameCh": jun.get("nameCh"),
                "cellCount": cell_count,
            }
        )
    rows.sort(key=lambda row: (str(row["nameCh"]), row["junArrayIndex"]))
    return {
        "diagnosticOnly": True,
        "crossJun": len(distribution) > 1,
        "coordinateMatchesFootprintMajority": coordinate_jun in majority,
        "majorityJunArrayIndices": majority,
        "junDistribution": rows,
    }


def _geometry_diagnostic(city: dict, jun_index: int, groups_by_jun: dict, anchors_by_jun: dict) -> dict:
    anchors = anchors_by_jun[jun_index]
    if not anchors:
        raise ValueError("geometry diagnostics require at least one exact approved anchor")
    distances = []
    for anchor_city_id, selection, anchor in anchors:
        squared = (anchor["col"] - city["col"]) ** 2 + (anchor["row"] - city["row"]) ** 2
        distances.append(
            {
                "anchorCityId": anchor_city_id,
                "candidateAdministrativeUnitId": selection["administrativeUnitId"],
                "candidateAdministrativeGroupId": selection["parentRef"],
                "squaredGridDistance": squared,
            }
        )
    minimum = min(row["squaredGridDistance"] for row in distances)
    nearest = [row for row in distances if row["squaredGridDistance"] == minimum]
    nearest.sort(key=lambda row: (row["anchorCityId"], row["candidateAdministrativeUnitId"]))
    return {
        "diagnosticOnly": True,
        "groupCardinality": "SINGLE_GROUP_JUN" if len(groups_by_jun[jun_index]) == 1 else "MULTI_GROUP_JUN",
        "distanceTie": len(nearest) > 1,
        "nearestCandidates": nearest,
    }


def _external_review(city_id: str, jun_name: object, external: dict) -> dict:
    place = external["placeById"].get(city_id)
    candidate = None
    if place is not None:
        candidate = {
            "externalPlaceId": place["id"],
            "kind": place.get("kind"),
            "confidence": place.get("conf"),
            "candidateReviewState": "PENDING_EXTERNAL_POLITY_REVIEW",
        }
    related = set(external["pendingByCity"].get(city_id, []))
    if isinstance(jun_name, str):
        related.update(external["pendingByGroup"].get(jun_name, []))
    return {
        "diagnosticOnly": True,
        "reviewState": "PENDING_EXTERNAL_POLITY_REVIEW",
        "cityExternalPlaceCandidate": candidate,
        "relatedPendingRouteNodeCandidateKeys": sorted(related),
    }


def _direct_territory_jun_reviews(
    tiles: dict,
    groups_by_jun: dict,
    binding_groups: dict[str, list[str]],
    external: dict,
) -> list[dict]:
    reviews = []
    for jun_index, jun in enumerate(tiles["juns"]):
        if groups_by_jun[jun_index]:
            continue
        name = jun.get("nameCh") if isinstance(jun, dict) else None
        if name in external["hubByName"]:
            continue
        sourced = binding_groups.get(name, []) if isinstance(name, str) else []
        reviews.append(
            {
                "diagnosticOnly": True,
                "junArrayIndex": jun_index,
                "nameCh": name,
                "reviewState": (
                    "REJECTED_SOURCED_COUNTIES_ALREADY_EXIST"
                    if sourced
                    else "PENDING_DIRECT_TERRITORY_REVIEW"
                ),
                "sourcedAdministrativeUnitIds": sourced,
            }
        )
    reviews.sort(key=lambda row: (str(row["nameCh"]), row["junArrayIndex"]))
    return reviews


def _summary(rows: list[dict], absent: list[dict], direct_jun_reviews: list[dict]) -> dict:
    decision_counts = Counter(row["decision"] for row in rows)
    decision_cells = Counter()
    for row in rows:
        decision_cells[row["decision"]] += row["cellCount"]
    geometry = [row for row in rows if row["decision"] == "PROPOSED_GEOMETRIC"]
    unique = [row for row in geometry if not row["geometryDiagnostic"]["distanceTie"]]
    ties = [row for row in geometry if row["geometryDiagnostic"]["distanceTie"]]
    single = [row for row in geometry if row["geometryDiagnostic"]["groupCardinality"] == "SINGLE_GROUP_JUN"]
    multi = [row for row in geometry if row["geometryDiagnostic"]["groupCardinality"] == "MULTI_GROUP_JUN"]
    rejected_juns = [
        row
        for row in direct_jun_reviews
        if row["reviewState"] == "REJECTED_SOURCED_COUNTIES_ALREADY_EXIST"
    ]
    pending_juns = [
        row
        for row in direct_jun_reviews
        if row["reviewState"] == "PENDING_DIRECT_TERRITORY_REVIEW"
    ]
    return {
        "landOwnerRowCount": len(rows),
        "landCellCount": sum(row["cellCount"] for row in rows),
        "decisionRowCounts": dict(sorted(decision_counts.items())),
        "decisionCellCounts": dict(sorted(decision_cells.items())),
        "exactApprovedRowCount": decision_counts["EXACT_APPROVED"],
        "exactApprovedCellCount": decision_cells["EXACT_APPROVED"],
        "approvedPhysicalPlaceIdAbsentCount": len(absent),
        "unresolvedRowCount": len(rows) - decision_counts["EXACT_APPROVED"],
        "unresolvedCellCount": sum(row["cellCount"] for row in rows if row["decision"] != "EXACT_APPROVED"),
        "geometryDiagnostics": {
            "singleGroupJun": {"rowCount": len(single), "cellCount": sum(row["cellCount"] for row in single)},
            "multiGroupJun": {"rowCount": len(multi), "cellCount": sum(row["cellCount"] for row in multi)},
            "uniqueNearest": {"rowCount": len(unique), "cellCount": sum(row["cellCount"] for row in unique)},
            "distanceTies": {"rowCount": len(ties), "cellCount": sum(row["cellCount"] for row in ties)},
        },
        "directTerritoryReview": {
            "rejectedSourcedGroupJunCount": len(rejected_juns),
            "pendingCandidateJunCount": len(pending_juns),
        },
        "crossSeatOwnerJunFootprintCount": sum(row["footprintDiagnostic"]["crossJun"] for row in rows),
        "coordinateFootprintMajorityMismatchCount": sum(
            not row["footprintDiagnostic"]["coordinateMatchesFootprintMajority"] for row in rows
        ),
    }


def _assert_locked_contract(summary: dict, absent: list[dict]) -> None:
    expected = {
        "landOwnerRowCount": 1_138,
        "landCellCount": 332_914,
        "exactApprovedRowCount": 779,
        "exactApprovedCellCount": 199_874,
        "approvedPhysicalPlaceIdAbsentCount": 2,
        "unresolvedRowCount": 359,
        "unresolvedCellCount": 133_040,
        "crossSeatOwnerJunFootprintCount": 99,
        "coordinateFootprintMajorityMismatchCount": 7,
    }
    for key, value in expected.items():
        if summary.get(key) != value:
            raise ValueError(f"locked reconciliation count changed: {key}={summary.get(key)} expected {value}")
    geometry = summary["geometryDiagnostics"]
    if geometry != {
        "singleGroupJun": {"rowCount": 162, "cellCount": 45_828},
        "multiGroupJun": {"rowCount": 116, "cellCount": 20_155},
        "uniqueNearest": {"rowCount": 273, "cellCount": 65_634},
        "distanceTies": {"rowCount": 5, "cellCount": 349},
    }:
        raise ValueError("locked geometry reconciliation counts changed")
    if summary["decisionRowCounts"].get("BLOCKED_DIRECT_TERRITORY_REVIEW") != 41:
        raise ValueError("locked direct-territory blocker row count changed")
    if summary["decisionCellCounts"].get("BLOCKED_DIRECT_TERRITORY_REVIEW") != 20_987:
        raise ValueError("locked direct-territory blocker cell count changed")
    if summary["decisionRowCounts"].get("BLOCKED_EXTERNAL_POLITY_REVIEW") != 40:
        raise ValueError("locked external-polity blocker row count changed")
    if summary["decisionCellCounts"].get("BLOCKED_EXTERNAL_POLITY_REVIEW") != 46_070:
        raise ValueError("locked external-polity blocker cell count changed")
    if summary["directTerritoryReview"] != {
        "rejectedSourcedGroupJunCount": 6,
        "pendingCandidateJunCount": 26,
    }:
        raise ValueError("locked direct-territory review split changed")
    expected_absent = [
        ("hhs:110:清河國:003", "85168"),
        ("hhs:111:東海郡:009", "42901"),
    ]
    if [(row["administrativeUnitId"], row["terminalPhysicalPlaceId"]) for row in absent] != expected_absent:
        raise ValueError("approved physical-place IDs absent from tiles changed")


def build_ledger(documents: dict[str, dict], input_records: dict[str, dict]) -> dict:
    if set(documents) != set(INPUT_PATHS) or set(input_records) != set(INPUT_PATHS):
        raise ValueError("ledger build requires every pinned input")
    _validate_review_chain(documents, input_records)
    tiles = _tile_context(documents["data/map/han-tiles.json"])
    selections = _selection_context(documents["data/curated/han/route-node-selection-v1.json"], tiles)
    temporal_adjudications = _temporal_adjudication_context(
        documents["data/curated/han/administrative-temporal-adjudications-v1.json"],
        documents["data/curated/han/administrative-units.json"],
        documents["data/curated/han/administrative-place-bindings-v1.json"],
    )
    history_relations = documents["data/map/han-administrative-history.json"].get("relations")
    if not isinstance(history_relations, list):
        raise ValueError("administrative history relations must be an array")
    for adjudication in temporal_adjudications.values():
        for interval in adjudication["parentIntervals"][1:]:
            expected_relation = {
                "childId": adjudication["historyChildId"],
                "parentId": interval["parentId"],
                "effectiveFrom": interval["effectiveFrom"],
                "effectiveTo": interval["effectiveTo"],
            }
            if expected_relation not in history_relations:
                raise ValueError("temporal adjudication and administrative history relation disagree")
    groups_by_jun, anchors_by_jun = _jun_diagnostics(tiles, selections)
    binding_groups = _binding_groups(documents["data/curated/han/administrative-place-bindings-v1.json"])
    external = _external_context(
        documents["data/map/external-places.json"],
        documents["data/curated/han/route-node-selection-candidates-v1.json"],
    )
    direct_jun_reviews = _direct_territory_jun_reviews(
        tiles, groups_by_jun, binding_groups, external
    )
    rows = []
    for city_id in sorted(tiles["cityById"]):
        city = tiles["cityById"][city_id]
        city_index = tiles["cityIndexById"][city_id]
        jun_index, seat_jun = _seat_jun_diagnostic(city, tiles, groups_by_jun)
        footprint = _footprint_diagnostic(city_index, jun_index, tiles)
        row = {
            "cityId": city_id,
            "cellCount": tiles["landCounts"][city_index],
            "seatJunDiagnostic": seat_jun,
            "footprintDiagnostic": footprint,
        }
        approved = selections["matchedByCityId"].get(city_id)
        temporal_approved = temporal_adjudications.get(city_id)
        if approved is not None and temporal_approved is not None:
            raise ValueError("city has competing exact approval sources")
        if approved is not None:
            row.update(
                {
                    "decision": "EXACT_APPROVED",
                    "reviewState": "APPROVED_EXACT_SELECTION_JOIN",
                    "approvedParentAdministrativeUnitId": approved["administrativeUnitId"],
                    "approvalEvidence": {
                        "method": "EXACT_APPROVED_SELECTION_PHYSICAL_ID",
                        "inputs": [
                            "routeNodeSelection.reviewState",
                            "routeNodeSelection.physicalPlaceRefTerminal",
                            "hanTiles.cities.id",
                        ],
                        "physicalPlaceRef": approved["physicalPlaceRef"],
                        "routeNodeKey": approved["routeNodeKey"],
                    },
                }
            )
        elif temporal_approved is not None:
            row.update(
                {
                    "decision": "EXACT_APPROVED",
                    "reviewState": "APPROVED_EXACT_TEMPORAL_BINDING",
                    "approvedParentAdministrativeUnitId": temporal_approved[
                        "administrativeUnitId"
                    ],
                    "approvalEvidence": {
                        "method": "REVIEWED_TEMPORAL_ADMINISTRATIVE_ADJUDICATION",
                        "inputs": [
                            "administrativeTemporalAdjudication.reviewState",
                            "administrativeTemporalAdjudication.physicalPlaceRefTerminal",
                            "hanTiles.cities.id",
                        ],
                        "physicalPlaceRef": temporal_approved["physicalPlaceRef"],
                        "activeParentId": temporal_approved["activeParentId"],
                        "forbiddenIdentities": temporal_approved["forbiddenIdentities"],
                    },
                }
            )
        elif groups_by_jun[jun_index]:
            row.update(
                {
                    "decision": "PROPOSED_GEOMETRIC",
                    "reviewState": "REVIEW_REQUIRED_DIAGNOSTIC_ONLY",
                    "geometryDiagnostic": _geometry_diagnostic(
                        city, jun_index, groups_by_jun, anchors_by_jun
                    ),
                }
            )
        else:
            jun_name = seat_jun["nameCh"]
            if jun_name in external["hubByName"]:
                row.update(
                    {
                        "decision": "BLOCKED_EXTERNAL_POLITY_REVIEW",
                        "reviewState": "BLOCKED",
                        "externalReview": _external_review(city_id, jun_name, external),
                    }
                )
            else:
                sourced = binding_groups.get(jun_name, []) if isinstance(jun_name, str) else []
                state = (
                    "REJECTED_SOURCED_COUNTIES_ALREADY_EXIST"
                    if sourced
                    else "PENDING_DIRECT_TERRITORY_REVIEW"
                )
                row.update(
                    {
                        "decision": "BLOCKED_DIRECT_TERRITORY_REVIEW",
                        "reviewState": "BLOCKED",
                        "directTerritoryReview": {
                            "diagnosticOnly": True,
                            "reviewState": state,
                            "sourcedAdministrativeUnitIds": sourced,
                        },
                    }
                )
        rows.append(row)
    summary = _summary(rows, selections["absent"], direct_jun_reviews)
    _assert_locked_contract(summary, selections["absent"])
    return {
        "schemaVersion": 1,
        "ledgerId": "han-administrative-parent-reconciliation-v1",
        "referenceYear": REFERENCE_YEAR,
        "policy": {
            "approvalRule": "reviewed exact physicalPlaceRef terminal exactly equals cities[].id",
            "approvalIdentityKey": "cityId",
            "forbiddenApprovalInputs": list(FORBIDDEN_APPROVAL_INPUTS),
            "nonExactRowsNeverApproveParent": True,
            "diagnosticCategoriesAreNotHistoricalAdjudications": True,
        },
        "inputs": {path: dict(input_records[path]) for path in sorted(input_records)},
        "summary": summary,
        "approvedPhysicalPlaceIdsAbsentFromTiles": selections["absent"],
        "directTerritoryJunReviews": direct_jun_reviews,
        "rows": rows,
    }


def render_ledger(input_paths: dict[str, Path] | None = None) -> bytes:
    documents, records = load_inputs(input_paths)
    ledger = build_ledger(documents, records)
    return (json.dumps(ledger, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def write_ledger(
    output_path: Path = OUTPUT,
    input_paths: dict[str, Path] | None = None,
) -> bytes:
    rendered = render_ledger(input_paths)
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(rendered)
    return rendered


def check_ledger(
    output_path: Path = OUTPUT,
    input_paths: dict[str, Path] | None = None,
) -> bool:
    output_path = Path(output_path)
    return output_path.is_file() and output_path.read_bytes() == render_ledger(input_paths)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write the deterministic ledger")
    mode.add_argument("--check", action="store_true", help="require byte-identical committed output")
    args = parser.parse_args(argv)
    if args.check:
        valid = check_ledger()
        print(f"Han parent reconciliation check {'passed' if valid else 'failed'}: {OUTPUT.relative_to(ROOT)}")
        return 0 if valid else 1
    rendered = write_ledger()
    print(f"generated {OUTPUT.relative_to(ROOT)} ({len(rendered)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
