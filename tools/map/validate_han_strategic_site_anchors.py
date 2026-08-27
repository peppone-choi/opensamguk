#!/usr/bin/env python3
"""Validate the hand-reviewed Han strategic-site anchor ledger without rewriting it."""

import argparse
import hashlib
import json
import math
import sys
from collections import Counter
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "data/curated/han/strategic-site-anchor-review-v1.json"
INPUT_PATHS = {
    path: ROOT / path
    for path in (
        "data/map/han-strategic-sites.json",
        "data/map/han-tiles.json",
        ".ai/research/2026-08-24-namu-places-crosscheck.md",
        "tools/map/build_han_places.py",
    )
}
EXPECTED_SITE_IDS = [
    "site:dingjunshan",
    "site:jiange",
    "site:jieting",
    "site:qishan",
    "site:wuzhangyuan",
    "site:yanganguan",
    "site:yangpingguan",
]
EXPECTED_PROJECTION = {
    "cell": 0.04690971,
    "cols": 768,
    "k": 0.866025,
    "lat0": 30.0,
    "pad": 0.69282,
    "rows": 669,
    "x0": 80.540363,
    "y1": 45.0,
}
EXPECTED_ARRAY_SEMANTICS = {
    "evidenceSources": "UNORDERED_UNIQUE_BY_SOURCE_ID",
    "coordinateEvidenceSourceIds": "UNORDERED_UNIQUE",
    "placementEvidenceSourceIds": "UNORDERED_UNIQUE",
    "evidenceSourceIds": "UNORDERED_UNIQUE",
    "reviewRows": "MANIFEST_ORDERED",
    "candidates": "REVIEW_ORDERED",
    "conflicts": "REVIEW_ORDERED",
    "rejectedClaims": "REVIEW_ORDERED",
}
REVIEW_STATES = {"PROPOSED", "BLOCKED", "REJECTED", "APPROVED"}
SOURCE_KINDS = {
    "TRACKED_COORDINATE_CROSSWALK",
    "TRACKED_PROJECTION_WITNESS",
    "OFFICIAL_LOCALITY",
    "OFFICIAL_GAZETTEER",
    "OFFICIAL_PLAN",
    "ACADEMIC_DISPUTE",
}
CELL_BASES = {"COMMITTED_PROJECTION", "FULL_PRECISION_GENERATOR_WITNESS"}
CONFLICT_KINDS = {"COMPETING_LOCALITY_TRADITIONS", "PROJECTION_SSO_T_BOUNDARY"}
REJECTED_DISPOSITIONS = {"REJECTED_MALFORMED_INCONSISTENT_COORDINATE"}
FORBIDDEN_KEYS = {
    "name",
    "sitename",
    "physicalplaceid",
    "physicalplaceref",
    "administrativeunitid",
    "nearestcity",
    "nearestcityid",
    "rasterowner",
    "owner",
    "ownerid",
    "cityid",
    "cityarrayindex",
    "junarrayindex",
    "runtimeid",
    "numericcityid",
    "hancityconst",
    "hangateindex",
    "routenodekey",
}
FORBIDDEN_KEY_MARKERS = ("name", "nearest", "owner", "runtime", "index")
ALLOWED_CONTROL_KEYS = {"mandatoryruntimeseed"}
FORBIDDEN_VALUE_MARKERS = ("chgis:v6:cnty:", "chgis:v6:pref:", "pref:")

EXPECTED_SOURCES = {
    "tracked-coordinate-crosswalk": (
        "TRACKED_COORDINATE_CROSSWALK",
        "path",
        ".ai/research/2026-08-24-namu-places-crosscheck.md",
    ),
    "projection-generator-witness": (
        "TRACKED_PROJECTION_WITNESS",
        "path",
        "tools/map/build_han_places.py",
    ),
    "mian-county-locality": (
        "OFFICIAL_LOCALITY",
        "url",
        "https://www.mianxian.gov.cn/mxzf/mlmx/lsrw/202010/da415101654b4384a385f8674884a758.shtml",
    ),
    "mian-gazetteer": (
        "OFFICIAL_GAZETTEER",
        "url",
        "https://dfz.shaanxi.gov.cn/zslm/fzzlk/sxjz/hzs_16205/201706/P020240923599655489696.pdf",
    ),
    "jiange-official-plan": (
        "OFFICIAL_PLAN",
        "url",
        "https://lcj.sc.gov.cn/scslyt/c1055/2023/6/21/d1de680159b84a90987dd3d4b6409f45/files/%E5%89%91%E9%97%A8%E8%9C%80%E9%81%93%E9%A3%8E%E6%99%AF%E5%90%8D%E8%83%9C%E5%8C%BA%EF%BC%88%E5%B9%BF%E5%85%83%E6%AE%B5%EF%BC%89%E5%89%91%E9%97%A8%E5%85%B3%E6%99%AF%E5%8C%BA%E8%AF%A6%E7%BB%86%E8%A7%84%E5%88%92%EF%BC%88%E5%BE%81%E6%B1%82%E6%84%8F%E8%A7%81%E7%A8%BF%EF%BC%89%E8%AF%B4%E6%98%8E%E4%B9%A6.pdf",
    ),
    "longcheng-gazetteer": (
        "OFFICIAL_GAZETTEER",
        "url",
        "https://gsdfszw.org.cn/gsxzcz/tss/201905/P020190528326424696493.pdf",
    ),
    "hanzhong-jieting-dispute": (
        "ACADEMIC_DISPUTE",
        "url",
        "https://zx.hanzhong.gov.cn/hzzxwz/thhm/201605/t20160512_330971.shtml",
    ),
    "qishan-protection-plan": (
        "OFFICIAL_PLAN",
        "url",
        "https://www.gs.gov.cn/gsszf/c100054/202304/169834641/files/d47e74422e0f4aa99fbeb09bf1f9b698.pdf",
    ),
    "wuzhang-locality": (
        "OFFICIAL_LOCALITY",
        "url",
        "https://cjp.baoji.gov.cn/col3866/col3880/200810/t20081027_710024.html",
    ),
    "ningqiang-gazetteer": (
        "OFFICIAL_GAZETTEER",
        "url",
        "https://dfz.shaanxi.gov.cn/zslm/fzzlk/sxjz/hzs_16205/201706/P020240923600372937321.pdf",
    ),
    "hanzhong-gazetteer": (
        "OFFICIAL_GAZETTEER",
        "url",
        "https://dfz.shaanxi.gov.cn/zslm/fzzlk/xbsxsxz/xbsxz/hzs_16205/201405/P020240923623803686515.pdf",
    ),
}

LOCKED_ROWS = {
    "site:dingjunshan": {
        "state": "PROPOSED",
        "selected": "site:dingjunshan:crosswalk",
        "candidates": [
            ("site:dingjunshan:crosswalk", [106.67025, 33.11738], [267, 268], "COMMITTED_PROJECTION")
        ],
    },
    "site:jiange": {
        "state": "PROPOSED",
        "selected": "site:jiange:crosswalk",
        "candidates": [
            ("site:jiange:crosswalk", [105.56415, 32.21485], [246, 287], "COMMITTED_PROJECTION")
        ],
    },
    "site:jieting": {
        "state": "BLOCKED",
        "selected": None,
        "candidates": [
            (
                "site:jieting:longcheng-tradition",
                [105.98359, 34.99615],
                [254, 228],
                "COMMITTED_PROJECTION",
            ),
            (
                "site:jieting:maiji-theory",
                [105.99444, 34.45192],
                [254, 239],
                "COMMITTED_PROJECTION",
            ),
        ],
    },
    "site:qishan": {
        "state": "PROPOSED",
        "selected": "site:qishan:crosswalk",
        "candidates": [
            ("site:qishan:crosswalk", [105.39401, 34.23089], [243, 244], "COMMITTED_PROJECTION")
        ],
    },
    "site:wuzhangyuan": {
        "state": "BLOCKED",
        "selected": None,
        "candidates": [
            (
                "site:wuzhangyuan:committed-projection",
                [107.90836, 34.21945],
                [289, 244],
                "COMMITTED_PROJECTION",
            ),
            (
                "site:wuzhangyuan:full-precision-generator",
                [107.90836, 34.21945],
                [290, 244],
                "FULL_PRECISION_GENERATOR_WITNESS",
            ),
        ],
    },
    "site:yanganguan": {
        "state": "PROPOSED",
        "selected": "site:yanganguan:crosswalk",
        "candidates": [
            ("site:yanganguan:crosswalk", [106.0346, 32.965611], [255, 271], "COMMITTED_PROJECTION")
        ],
    },
    "site:yangpingguan": {
        "state": "PROPOSED",
        "selected": "site:yangpingguan:crosswalk",
        "candidates": [
            ("site:yangpingguan:crosswalk", [106.60975, 33.14761], [266, 267], "COMMITTED_PROJECTION")
        ],
    },
}


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _load_json(raw: bytes, path: str) -> dict:
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON: {path}") from error
    if not isinstance(document, dict):
        raise ValueError(f"input must be a JSON object: {path}")
    return document


def load_bundle(
    ledger_path: Path = LEDGER,
    input_paths: dict[str, Path] | None = None,
) -> tuple[dict, dict[str, object], dict[str, dict[str, str]]]:
    paths = INPUT_PATHS if input_paths is None else input_paths
    if set(paths) != set(INPUT_PATHS):
        raise ValueError("tracked input path set mismatch")
    ledger = _load_json(Path(ledger_path).read_bytes(), str(ledger_path))
    documents: dict[str, object] = {}
    records = {}
    for repository_path in sorted(paths):
        raw = Path(paths[repository_path]).read_bytes()
        if repository_path.endswith(".json"):
            documents[repository_path] = _load_json(raw, repository_path)
        else:
            documents[repository_path] = raw.decode("utf-8")
        records[repository_path] = {
            "path": repository_path,
            "sha256": _sha256(raw),
        }
    return ledger, documents, records


def _require_nonempty_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value


def _require_unique_strings(values: object, label: str, known: set[str]) -> list[str]:
    if not isinstance(values, list) or not values:
        raise ValueError(f"{label} must be a non-empty source array")
    if not all(isinstance(value, str) and value for value in values):
        raise ValueError(f"{label} must contain source IDs")
    if len(values) != len(set(values)):
        raise ValueError(f"duplicate source ID in {label}")
    unknown = set(values) - known
    if unknown:
        raise ValueError(f"unknown source ID in {label}: {sorted(unknown)}")
    return values


def _scan_forbidden(value: object, path: str = "ledger") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized = key.casefold()
            if normalized in FORBIDDEN_KEYS or (
                normalized not in ALLOWED_CONTROL_KEYS
                and any(marker in normalized for marker in FORBIDDEN_KEY_MARKERS)
            ):
                raise ValueError(f"forbidden approval identity field: {path}.{key}")
            _scan_forbidden(nested, f"{path}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _scan_forbidden(nested, f"{path}[{index}]")
    elif isinstance(value, str) and any(marker in value.casefold() for marker in FORBIDDEN_VALUE_MARKERS):
        raise ValueError(f"forbidden administrative physical-place value: {path}")


def _validate_tracked_inputs(ledger: dict, records: dict[str, dict[str, str]]) -> None:
    tracked = ledger.get("trackedInputs")
    if not isinstance(tracked, dict) or set(tracked) != set(INPUT_PATHS):
        raise ValueError("tracked input path set mismatch")
    if set(records) != set(INPUT_PATHS):
        raise ValueError("loaded input path set mismatch")
    for path in INPUT_PATHS:
        record = records.get(path)
        if not isinstance(record, dict) or record.get("path") != path:
            raise ValueError(f"tracked input identity mismatch: {path}")
        digest = record.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(
            character not in "0123456789abcdef" for character in digest
        ):
            raise ValueError(f"tracked input hash is invalid: {path}")
        if tracked[path] != record:
            raise ValueError(f"tracked input hash mismatch: {path}")


def _validate_projection(ledger: dict, documents: dict[str, object]) -> tuple[dict, list[str]]:
    manifest = documents["data/map/han-strategic-sites.json"]
    tiles = documents["data/map/han-tiles.json"]
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise ValueError("strategic-site manifest schema mismatch")
    sites = manifest.get("sites")
    if not isinstance(sites, list) or not all(isinstance(site, dict) for site in sites):
        raise ValueError("strategic-site manifest rows are invalid")
    manifest_ids = [site.get("id") for site in sites]
    if manifest_ids != EXPECTED_SITE_IDS or len(manifest_ids) != len(set(manifest_ids)):
        raise ValueError("strategic-site manifest identity/order mismatch")
    if not isinstance(tiles, dict):
        raise ValueError("tile input is invalid")
    meta = tiles.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("raster metadata is missing")
    if meta.get("cols") != 768 or meta.get("rows") != 669 or meta.get("year") != 220:
        raise ValueError("raster dimensions/reference year mismatch")
    if meta.get("projection") != EXPECTED_PROJECTION:
        raise ValueError("projection constants mismatch")
    reference = ledger.get("referenceRaster")
    if reference != {"cols": 768, "rows": 669, "projection": EXPECTED_PROJECTION}:
        raise ValueError("ledger projection contract mismatch")
    terrain = tiles.get("terrain")
    if not isinstance(terrain, list) or len(terrain) != 669 or any(
        not isinstance(row, str) or len(row) != 768 for row in terrain
    ):
        raise ValueError("raster terrain dimensions mismatch")
    return EXPECTED_PROJECTION, terrain


def _validate_sources(ledger: dict) -> dict[str, dict]:
    if ledger.get("arraySemantics") != EXPECTED_ARRAY_SEMANTICS:
        raise ValueError("array semantics contract mismatch")
    sources = ledger.get("evidenceSources")
    if not isinstance(sources, list) or not all(isinstance(source, dict) for source in sources):
        raise ValueError("evidenceSources must be an object array")
    source_ids = [source.get("sourceId") for source in sources]
    if len(source_ids) != len(set(source_ids)):
        raise ValueError("duplicate evidence sourceId")
    if set(source_ids) != set(EXPECTED_SOURCES):
        raise ValueError("evidence source identity set mismatch")
    by_id = {source["sourceId"]: source for source in sources}
    for source_id, (expected_kind, locator_key, expected_locator) in EXPECTED_SOURCES.items():
        source = by_id[source_id]
        if source.get("kind") not in SOURCE_KINDS or source.get("kind") != expected_kind:
            raise ValueError(f"evidence source kind mismatch: {source_id}")
        _require_nonempty_string(source.get("claim"), f"evidence source {source_id}.claim")
        if source.get(locator_key) != expected_locator:
            raise ValueError(f"evidence source locator mismatch: {source_id}")
        other_locator = "url" if locator_key == "path" else "path"
        if other_locator in source:
            raise ValueError(f"evidence source has conflicting locator: {source_id}")
        if locator_key == "url" and "sha256" in source:
            raise ValueError(f"URL claim reference must not be hash pinned: {source_id}")
    return by_id


def _validate_candidate(
    candidate: object,
    label: str,
    source_ids: set[str],
    projection: dict,
    terrain: list[str],
) -> dict:
    if not isinstance(candidate, dict):
        raise ValueError(f"{label} must be an object")
    _require_nonempty_string(candidate.get("candidateId"), f"{label}.candidateId")
    coordinate = candidate.get("coordinate")
    if not isinstance(coordinate, list) or len(coordinate) != 2 or any(
        type(value) not in (int, float) or not math.isfinite(value) for value in coordinate
    ):
        raise ValueError(f"{label}.coordinate must be two finite numbers")
    lon, lat = coordinate
    if not (-180 <= lon <= 180 and -90 <= lat <= 90):
        raise ValueError(f"{label}.coordinate is out of geographic bounds")
    cell = candidate.get("rasterCell")
    if not isinstance(cell, list) or len(cell) != 2 or any(type(value) is not int for value in cell):
        raise ValueError(f"{label}.rasterCell must be two integers")
    col, row = cell
    if not (0 <= col < 768 and 0 <= row < 669):
        raise ValueError(f"{label}.rasterCell is out of bounds")
    if terrain[row][col] == "0":
        raise ValueError(f"{label}.rasterCell must be non-SEA")
    basis = candidate.get("cellBasis")
    if basis not in CELL_BASES:
        raise ValueError(f"{label}.cellBasis closed enum mismatch")
    if basis == "COMMITTED_PROJECTION":
        recomputed = [
            math.floor((lon * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]),
            math.floor((projection["y1"] + projection["pad"] - lat) / projection["cell"]),
        ]
        if cell != recomputed:
            raise ValueError(f"{label}.rasterCell does not recompute from committed projection")
    _require_unique_strings(
        candidate.get("coordinateEvidenceSourceIds"),
        f"{label}.coordinateEvidenceSourceIds",
        source_ids,
    )
    _require_unique_strings(
        candidate.get("placementEvidenceSourceIds"),
        f"{label}.placementEvidenceSourceIds",
        source_ids,
    )
    return candidate


def _validate_conflicts(conflicts: object, label: str, source_ids: set[str]) -> list[dict]:
    if not isinstance(conflicts, list) or not all(isinstance(conflict, dict) for conflict in conflicts):
        raise ValueError(f"{label} must be an object array")
    conflict_ids = [conflict.get("conflictId") for conflict in conflicts]
    if len(conflict_ids) != len(set(conflict_ids)):
        raise ValueError(f"duplicate conflictId in {label}")
    for conflict in conflicts:
        _require_nonempty_string(conflict.get("conflictId"), f"{label}.conflictId")
        if conflict.get("kind") not in CONFLICT_KINDS:
            raise ValueError(f"{label}.kind closed enum mismatch")
        _require_nonempty_string(conflict.get("reason"), f"{label}.reason")
        _require_unique_strings(
            conflict.get("evidenceSourceIds"), f"{label}.evidenceSourceIds", source_ids
        )
    return conflicts


def _validate_rejected_claims(claims: object, label: str, source_ids: set[str]) -> list[dict]:
    if not isinstance(claims, list) or not all(isinstance(claim, dict) for claim in claims):
        raise ValueError(f"{label} must be an object array")
    claim_ids = [claim.get("claimId") for claim in claims]
    if len(claim_ids) != len(set(claim_ids)):
        raise ValueError(f"duplicate claimId in {label}")
    for claim in claims:
        _require_nonempty_string(claim.get("claimId"), f"{label}.claimId")
        if claim.get("disposition") not in REJECTED_DISPOSITIONS:
            raise ValueError(f"{label}.disposition closed enum mismatch")
        _require_nonempty_string(claim.get("rawCoordinateText"), f"{label}.rawCoordinateText")
        _require_nonempty_string(claim.get("reason"), f"{label}.reason")
        _require_unique_strings(
            claim.get("evidenceSourceIds"), f"{label}.evidenceSourceIds", source_ids
        )
        if "correctedCoordinate" in claim:
            raise ValueError(f"{label} must never auto-correct a rejected coordinate")
    return claims


def _validate_review_state(
    row: dict,
    candidates: list[dict],
    conflicts: list[dict],
    source_ids: set[str],
) -> None:
    state = row.get("reviewState")
    if state not in REVIEW_STATES:
        raise ValueError(f"reviewState closed enum mismatch: {state!r}")
    selected_id = row.get("selectedCandidateId")
    candidate_ids = {candidate["candidateId"] for candidate in candidates}
    if row.get("mandatoryRuntimeSeed") is not False:
        raise ValueError(f"{state} row cannot be a mandatory runtime seed")
    if state == "PROPOSED":
        if not isinstance(selected_id, str) or selected_id not in candidate_ids:
            raise ValueError("PROPOSED requires one selected review candidate")
    elif state == "BLOCKED":
        if selected_id is not None:
            raise ValueError("BLOCKED requires no selected candidate")
        if len(candidates) < 2 and not conflicts:
            raise ValueError("BLOCKED requires two candidates or a non-empty conflict")
    elif state == "APPROVED":
        if not isinstance(selected_id, str) or selected_id not in candidate_ids:
            raise ValueError("APPROVED requires one selected review candidate")
        reviewer_id = row.get("reviewerId")
        if (
            not isinstance(reviewer_id, str)
            or not reviewer_id.startswith("reviewer:")
            or not reviewer_id.removeprefix("reviewer:").strip()
        ):
            raise ValueError("APPROVED requires a stable reviewerId")
        reviewed_date = row.get("reviewedDate")
        try:
            date.fromisoformat(reviewed_date)
        except (TypeError, ValueError) as error:
            raise ValueError("APPROVED requires an ISO reviewedDate") from error
        if conflicts:
            raise ValueError("APPROVED requires no unresolved conflicts")
    else:
        if selected_id is not None or candidates:
            raise ValueError("REJECTED has no anchor")
        rejection = row.get("rejection")
        if not isinstance(rejection, dict):
            raise ValueError("REJECTED requires reason and evidence")
        _require_nonempty_string(rejection.get("reason"), "REJECTED.reason")
        try:
            _require_unique_strings(
                rejection.get("evidenceSourceIds"),
                "REJECTED.evidenceSourceIds",
                source_ids,
            )
        except ValueError as error:
            raise ValueError("REJECTED requires reason and evidence") from error


def _locked_candidate_tuple(candidate: dict) -> tuple[object, object, object, object]:
    return (
        candidate.get("candidateId"),
        candidate.get("coordinate"),
        candidate.get("rasterCell"),
        candidate.get("cellBasis"),
    )


def _validate_locked_decisions(rows: list[dict]) -> None:
    for row in rows:
        site_id = row["siteId"]
        expected = LOCKED_ROWS[site_id]
        if row.get("reviewState") != expected["state"]:
            raise ValueError(f"locked current reviewState changed: {site_id}")
        if row.get("selectedCandidateId") != expected["selected"]:
            raise ValueError(f"locked selected candidate changed: {site_id}")
        actual_candidates = [_locked_candidate_tuple(candidate) for candidate in row["candidates"]]
        if actual_candidates != expected["candidates"]:
            raise ValueError(f"locked review candidate/order changed: {site_id}")
    states = Counter(row["reviewState"] for row in rows)
    if states != Counter({"PROPOSED": 5, "BLOCKED": 2}):
        raise ValueError(f"locked current state counts changed: {dict(states)}")


def _validate_specific_reviews(rows_by_id: dict[str, dict]) -> None:
    jieting = rows_by_id["site:jieting"]
    if [candidate["candidateId"] for candidate in jieting["candidates"]] != [
        "site:jieting:longcheng-tradition",
        "site:jieting:maiji-theory",
    ] or [conflict.get("kind") for conflict in jieting["conflicts"]] != [
        "COMPETING_LOCALITY_TRADITIONS"
    ]:
        raise ValueError("Jieting competing candidates/review conflict changed")
    wuzhang = rows_by_id["site:wuzhangyuan"]
    if [candidate["rasterCell"] for candidate in wuzhang["candidates"]] != [
        [289, 244],
        [290, 244],
    ] or [conflict.get("kind") for conflict in wuzhang["conflicts"]] != [
        "PROJECTION_SSO_T_BOUNDARY"
    ]:
        raise ValueError("Wuzhangyuan projection SSoT conflict changed")
    jiange_claims = rows_by_id["site:jiange"]["rejectedClaims"]
    if len(jiange_claims) != 1 or jiange_claims[0].get("rawCoordinateText") != "105°3′E,31°11′N":
        raise ValueError("Jiange malformed official coordinate rejection changed")

    yangan = rows_by_id["site:yanganguan"]
    yangping = rows_by_id["site:yangpingguan"]
    if yangan.get("siteLineage") != "THREE_KINGDOMS_YANGAN_NINGQIANG":
        raise ValueError("Yang'an site lineage changed")
    if yangping.get("siteLineage") != "HAN_YANGPING_MIAXIAN":
        raise ValueError("Yangping site lineage changed")
    if yangan["siteLineage"] == yangping["siteLineage"]:
        raise ValueError("Yang'an and Yangping must not share a lineage")
    selected_yangan = next(
        candidate
        for candidate in yangan["candidates"]
        if candidate["candidateId"] == yangan["selectedCandidateId"]
    )
    selected_yangping = next(
        candidate
        for candidate in yangping["candidates"]
        if candidate["candidateId"] == yangping["selectedCandidateId"]
    )
    if (
        selected_yangan["coordinate"] == selected_yangping["coordinate"]
        or selected_yangan["rasterCell"] == selected_yangping["rasterCell"]
    ):
        raise ValueError("Yang'an and Yangping must not share a selected anchor")


def validate_ledger(
    ledger: dict,
    documents: dict[str, object],
    input_records: dict[str, dict[str, str]],
) -> None:
    if not isinstance(ledger, dict):
        raise ValueError("review ledger must be an object")
    if ledger.get("schemaVersion") != 1:
        raise ValueError("review ledger schema mismatch")
    if ledger.get("ledgerId") != "han-strategic-site-anchor-review-v1":
        raise ValueError("review ledger identity mismatch")
    if set(documents) != set(INPUT_PATHS):
        raise ValueError("loaded input path set mismatch")
    _scan_forbidden(ledger)
    _validate_tracked_inputs(ledger, input_records)
    projection, terrain = _validate_projection(ledger, documents)
    sources_by_id = _validate_sources(ledger)
    source_ids = set(sources_by_id)

    rows = ledger.get("reviewRows")
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise ValueError("reviewRows must be an object array")
    row_ids = [row.get("siteId") for row in rows]
    if row_ids != EXPECTED_SITE_IDS:
        raise ValueError("reviewRows must match strategic-site manifest order")
    if len(row_ids) != len(set(row_ids)):
        raise ValueError("duplicate strategic-site review row")

    all_candidate_ids = []
    for row_index, row in enumerate(rows):
        label = f"reviewRows[{row_index}]"
        candidates = row.get("candidates")
        if not isinstance(candidates, list):
            raise ValueError(f"{label}.candidates must be an array")
        validated_candidates = [
            _validate_candidate(
                candidate,
                f"{label}.candidates[{candidate_index}]",
                source_ids,
                projection,
                terrain,
            )
            for candidate_index, candidate in enumerate(candidates)
        ]
        candidate_ids = [candidate["candidateId"] for candidate in validated_candidates]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError(f"duplicate candidateId in {label}")
        all_candidate_ids.extend(candidate_ids)
        conflicts = _validate_conflicts(row.get("conflicts"), f"{label}.conflicts", source_ids)
        _validate_rejected_claims(
            row.get("rejectedClaims"), f"{label}.rejectedClaims", source_ids
        )
        _validate_review_state(row, validated_candidates, conflicts, source_ids)
    if len(all_candidate_ids) != len(set(all_candidate_ids)):
        raise ValueError("duplicate candidateId across review rows")

    rows_by_id = {row["siteId"]: row for row in rows}
    _validate_specific_reviews(rows_by_id)
    _validate_locked_decisions(rows)


def check_ledger(
    ledger_path: Path = LEDGER,
    input_paths: dict[str, Path] | None = None,
) -> bool:
    try:
        ledger, documents, records = load_bundle(ledger_path, input_paths)
        validate_ledger(ledger, documents, records)
    except (OSError, UnicodeError, ValueError):
        return False
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True, help="validate the committed review ledger")
    parser.parse_args(argv)
    try:
        ledger, documents, records = load_bundle()
        validate_ledger(ledger, documents, records)
    except (OSError, UnicodeError, ValueError) as error:
        print(f"Han strategic-site anchor review check failed: {error}", file=sys.stderr)
        return 1
    print(f"Han strategic-site anchor review check passed: {LEDGER.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
