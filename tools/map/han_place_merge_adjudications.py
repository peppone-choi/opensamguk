"""Pure validation for stable-ID Han place merge adjudications.

The ledger identifies records only by source identity fields and their canonical
hash. It deliberately contains no coordinates, array positions, runtime IDs, or
source paths. Loading and applying decisions belong to runtime integration.
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

try:
    from tools.map.han_tiles_contract import loads_json_strict
except ModuleNotFoundError:  # pragma: no cover - direct import compatibility
    from han_tiles_contract import loads_json_strict


_ROOT_KEYS = {
    "schemaVersion", "ledgerId", "baselineYear",
    "sourceFingerprintContract", "adjudications",
}
_ROW_KEYS = {
    "adjudicationId", "decision", "operation", "sourcePlace", "targetPlace",
    "expectedInitialSourceJunNameFt", "expectedInitialTargetJunNameFt",
    "resultJunNameFt", "historicalProvenance",
}
_IDENTITY_KEYS = {
    "physicalPlaceId", "sourceNameCh", "sourceNameFt", "typeCh", "begYr",
    "endYr", "kind", "level",
}
_PLACE_KEYS = _IDENTITY_KEYS | {"sourceRecordSha256"}
_PROVENANCE_KEYS = {"role", "commit"}
_OPERATIONS = {"RESEAT_WITHIN_JUN", "MERGE_JUN"}
_PROVENANCE_ROLES = {"DECISION", "INDEX_CORRECTION"}
_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_LOWER_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
_FORBIDDEN_KEYS = {
    "x", "y", "gx", "gy", "lat", "lon", "latitude", "longitude",
    "coordinate", "coordinates", "cell", "cells", "cellid", "gridcell",
    "path", "absolutepath", "sourcepath", "sourceroot", "filepath",
    "index", "arrayindex", "recordindex", "runtimeindex", "nearestindex",
    "runtimeid", "physicalindex",
}

_DECISION_FIRST_FIVE = "f070cf364a896b51effe26ca6c73a849fcf09f4a"
_DECISION_XILING = "cfcf94a02c0529bf81cb0a9e4992a9efce1c0140"
_INDEX_CORRECTION = "808965058163c9ff4858f6a82698a2ec5271e19b"

# physicalPlaceId -> sourceNameCh, sourceNameFt, typeCh, begYr, endYr, kind,
# level, sourceRecordSha256. These values are reviewed evidence, not values
# discovered from a generator at validation time.
_EXPECTED_IDENTITIES = {
    "34539": (
        "宜都郡", "宜都郡", "郡", 210, 279, "COMMANDERY", 6,
        "1dc241265b752bdcf39e2bafb76a11cbac6985e7b9c71d8e9f96085684474edd",
    ),
    "45796": (
        "夷陵县", "夷陵縣", "县", 131, 221, "COUNTY", 5,
        "f40f29143be6a06ad19cbd8ba5aabc2503b699e5360afed263a53e47c7ab48d4",
    ),
    "34546": (
        "新城郡", "新城郡", "郡", 220, 489, "COMMANDERY", 6,
        "902898d0903b32e94fcca257e9264f02db5833bbaf6c78712063367399805a38",
    ),
    "45921": (
        "房陵县", "房陵縣", "县", -312, 562, "COUNTY", 5,
        "c2c0dabd8825e62ec357acf01883cfb9352b8f1ed2b6693722e35be0b31c5d7c",
    ),
    "211278": (
        "巴西郡", "巴西郡", "郡", 201, 430, "COMMANDERY", 6,
        "606ddc1a8b0e4e834bfdfeaec8a16fdad8f12f7c268635708ddea2a00d7e256a",
    ),
    "44558": (
        "阆中县", "閬中縣", "县", -314, 582, "COUNTY", 5,
        "2c5587a5da79c61dfd12d13a36520d75ebaaabb5ce654ca9a2b0a39d7d263ebf",
    ),
    "87633": (
        "北平郡", "北平郡", "郡", 220, 264, "COMMANDERY", 6,
        "fdf96e03ea2d1b5c237ccac702eef5ddee1864ce004ae08a8815444f3728bd43",
    ),
    "87458": (
        "土垠县", "土垠縣", "县", -201, 316, "COUNTY", 5,
        "c09d9b44c2cafca2e7fadc5ffb949d0a27a4ff7198dba9b67557dac6bfdb606b",
    ),
    "211473": (
        "冯翊郡", "馮翊郡", "郡", 220, 490, "COMMANDERY", 6,
        "a7cc0a6adfe3a35e5ffbed3bc01ff3fc292983eed702d5f107864ec50994b58e",
    ),
    "70741": (
        "高陆县", "高陸縣", "县", 220, 515, "COUNTY", 5,
        "0861f09976ba610bdf57b2dea694168f20850fc6807cad9b283d68c1217540ad",
    ),
    "34526": (
        "西陵郡", "西陵郡", "郡", 214, 220, "COMMANDERY", 6,
        "ca50d20cf211c7166357d2caf450c1e26e34fb55bc491de4d4d0bc619a7ac4f8",
    ),
    "43503": (
        "西陵县", "西陵縣", "县", 23, 501, "COUNTY", 5,
        "793afc3a5f221570c26f4441031d10efac942fe5dd00c9675b603a00a971a110",
    ),
}

# adjudicationId, operation, source ID, target ID, initial source jun,
# initial target jun, result jun, exact ordered provenance.
_EXPECTED_ROWS = (
    (
        "han-place-merge:34539-45796", "RESEAT_WITHIN_JUN", "34539", "45796",
        "宜都郡", "南郡", "宜都郡",
        (("DECISION", _DECISION_FIRST_FIVE),),
    ),
    (
        "han-place-merge:34546-45921", "RESEAT_WITHIN_JUN", "34546", "45921",
        "新城郡", "漢中郡", "新城郡",
        (("DECISION", _DECISION_FIRST_FIVE),),
    ),
    (
        "han-place-merge:211278-44558", "RESEAT_WITHIN_JUN", "211278", "44558",
        "巴西郡", "巴郡", "巴西郡",
        (("DECISION", _DECISION_FIRST_FIVE),),
    ),
    (
        "han-place-merge:87633-87458", "MERGE_JUN", "87633", "87458",
        "北平郡", "右北平郡", "右北平郡",
        (("DECISION", _DECISION_FIRST_FIVE),
         ("INDEX_CORRECTION", _INDEX_CORRECTION)),
    ),
    (
        "han-place-merge:211473-70741", "MERGE_JUN", "211473", "70741",
        "馮翊郡", "左馮翊", "左馮翊",
        (("DECISION", _DECISION_FIRST_FIVE),
         ("INDEX_CORRECTION", _INDEX_CORRECTION)),
    ),
    (
        "han-place-merge:34526-43503", "MERGE_JUN", "34526", "43503",
        "西陵郡", "江夏郡", "江夏郡",
        (("DECISION", _DECISION_XILING),
         ("INDEX_CORRECTION", _INDEX_CORRECTION)),
    ),
)


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    if set(value) != expected:
        missing = sorted(expected - set(value))
        extra = sorted(set(value) - expected)
        raise ValueError(
            f"{label} keys are not closed; missing={missing}, extra={extra}"
        )
    return value


def _require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _require_int(value: Any, label: str) -> int:
    if type(value) is not int:
        raise ValueError(f"{label} must be an integer")
    return value


def _reject_forbidden_keys(value: Any, label: str = "ledger") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            if not isinstance(key, str):
                raise ValueError(f"{label} contains a non-string key")
            normalized = re.sub(r"[-_]", "", key).lower()
            if normalized in _FORBIDDEN_KEYS:
                raise ValueError(f"{label} contains forbidden field {key!r}")
            _reject_forbidden_keys(nested, f"{label}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _reject_forbidden_keys(nested, f"{label}[{index}]")


def _canonical_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ValueError("identity is not canonical JSON data") from error


def _validate_identity_payload(value: Any) -> dict[str, Any]:
    identity = _exact_keys(value, _IDENTITY_KEYS, "identity")
    place_id = _require_string(identity["physicalPlaceId"], "physicalPlaceId")
    if not place_id.isascii() or not place_id.isdigit():
        raise ValueError("physicalPlaceId must contain only ASCII decimal digits")
    for key in ("sourceNameCh", "sourceNameFt", "typeCh", "kind"):
        _require_string(identity[key], key)
    beg_year = _require_int(identity["begYr"], "begYr")
    end_year = _require_int(identity["endYr"], "endYr")
    _require_int(identity["level"], "level")
    if beg_year > end_year:
        raise ValueError("begYr must not be after endYr")
    return identity


def identity_sha256(record_without_hash: dict[str, Any]) -> str:
    """Hash the exact eight-field identity using canonical UTF-8 JSON."""
    identity = _validate_identity_payload(record_without_hash)
    return hashlib.sha256(_canonical_bytes(identity)).hexdigest()


def _identity_signature(place: dict[str, Any]) -> tuple[Any, ...]:
    return (
        place["sourceNameCh"], place["sourceNameFt"], place["typeCh"],
        place["begYr"], place["endYr"], place["kind"], place["level"],
        place["sourceRecordSha256"],
    )


def _validate_place(
    value: Any,
    label: str,
    expected_kind: str,
    expected_level: int,
) -> dict[str, Any]:
    place = _exact_keys(value, _PLACE_KEYS, label)
    payload = {key: place[key] for key in _IDENTITY_KEYS}
    _validate_identity_payload(payload)
    digest = place["sourceRecordSha256"]
    if (
        not isinstance(digest, str)
        or not _LOWER_SHA256.fullmatch(digest)
        or digest == "0" * 64
    ):
        raise ValueError(
            f"{label}.sourceRecordSha256 must be a nonzero lowercase sha256"
        )
    if identity_sha256(payload) != digest:
        raise ValueError(f"{label}.sourceRecordSha256 does not match its identity")
    if place["kind"] != expected_kind or place["level"] != expected_level:
        raise ValueError(f"{label} must be {expected_kind} level {expected_level}")
    return place


def _validate_provenance(value: Any, label: str) -> tuple[tuple[str, str], ...]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{label} must be a nonempty array")
    result = []
    for index, raw_item in enumerate(value):
        item = _exact_keys(raw_item, _PROVENANCE_KEYS, f"{label}[{index}]")
        role = _require_string(item["role"], f"{label}[{index}].role")
        commit = _require_string(item["commit"], f"{label}[{index}].commit")
        if role not in _PROVENANCE_ROLES:
            raise ValueError(f"{label}[{index}].role is not allowed")
        if not _LOWER_COMMIT.fullmatch(commit) or commit == "0" * 40:
            raise ValueError(
                f"{label}[{index}].commit must be nonzero lowercase hex"
            )
        result.append((role, commit))
    if len(set(result)) != len(result):
        raise ValueError(f"{label} contains duplicate witnesses")
    return tuple(result)


def _has_cycle(edges: dict[str, str]) -> bool:
    for start in edges:
        seen: set[str] = set()
        node = start
        while node in edges:
            if node in seen:
                return True
            seen.add(node)
            node = edges[node]
    return False


def validate_ledger(document: dict[str, Any]) -> bool:
    """Validate one already-loaded, closed Han merge adjudication ledger."""
    _reject_forbidden_keys(document)
    ledger = _exact_keys(document, _ROOT_KEYS, "ledger")
    if _require_int(ledger["schemaVersion"], "schemaVersion") != 1:
        raise ValueError("schemaVersion must be 1")
    if ledger["ledgerId"] != "han-place-merge-adjudications-v1":
        raise ValueError("ledgerId is not the supported ledger")
    if _require_int(ledger["baselineYear"], "baselineYear") != 220:
        raise ValueError("baselineYear must be 220")
    if (
        ledger["sourceFingerprintContract"]
        != "HAN_PLACE_IDENTITY_CANONICAL_JSON_SHA256_V1"
    ):
        raise ValueError("sourceFingerprintContract is unsupported")
    rows = ledger["adjudications"]
    if not isinstance(rows, list) or len(rows) != len(_EXPECTED_ROWS):
        raise ValueError("adjudications must contain exactly six rows")

    actual_rows = []
    adjudication_ids: list[str] = []
    source_ids: list[str] = []
    target_ids: list[str] = []
    edges: dict[str, str] = {}
    for index, raw_row in enumerate(rows):
        label = f"adjudications[{index}]"
        row = _exact_keys(raw_row, _ROW_KEYS, label)
        adjudication_id = _require_string(
            row["adjudicationId"], f"{label}.adjudicationId"
        )
        if row["decision"] != "APPROVED":
            raise ValueError(f"{label}.decision must be APPROVED")
        operation = _require_string(row["operation"], f"{label}.operation")
        if operation not in _OPERATIONS:
            raise ValueError(f"{label}.operation is not allowed")
        for key in (
            "expectedInitialSourceJunNameFt",
            "expectedInitialTargetJunNameFt",
            "resultJunNameFt",
        ):
            _require_string(row[key], f"{label}.{key}")

        source = _validate_place(
            row["sourcePlace"], f"{label}.sourcePlace", "COMMANDERY", 6
        )
        target = _validate_place(
            row["targetPlace"], f"{label}.targetPlace", "COUNTY", 5
        )
        source_id = source["physicalPlaceId"]
        target_id = target["physicalPlaceId"]
        if source_id == target_id:
            raise ValueError(f"{label} cannot target its own source")
        provenance = _validate_provenance(
            row["historicalProvenance"], f"{label}.historicalProvenance"
        )

        adjudication_ids.append(adjudication_id)
        source_ids.append(source_id)
        target_ids.append(target_id)
        edges[source_id] = target_id
        actual_rows.append((
            adjudication_id,
            operation,
            source_id,
            target_id,
            row["expectedInitialSourceJunNameFt"],
            row["expectedInitialTargetJunNameFt"],
            row["resultJunNameFt"],
            provenance,
        ))

        expected_source = _EXPECTED_IDENTITIES.get(source_id)
        expected_target = _EXPECTED_IDENTITIES.get(target_id)
        if expected_source is None or _identity_signature(source) != expected_source:
            raise ValueError(f"{label}.sourcePlace is not the reviewed identity")
        if expected_target is None or _identity_signature(target) != expected_target:
            raise ValueError(f"{label}.targetPlace is not the reviewed identity")

    if len(set(adjudication_ids)) != len(adjudication_ids):
        raise ValueError("adjudicationId values must be unique")
    if len(set(source_ids)) != len(source_ids):
        raise ValueError("sourcePlace values must be unique")
    if len(set(target_ids)) != len(target_ids):
        raise ValueError("targetPlace values must be unique")
    if set(source_ids) & set(target_ids):
        raise ValueError("source and target physicalPlaceId sets must be disjoint")
    if _has_cycle(edges):
        raise ValueError("adjudication graph must not contain cycles")
    if tuple(actual_rows) != _EXPECTED_ROWS:
        raise ValueError("adjudications differ from exact reviewed rows or order")
    return True


def validate_ledger_json(document: str | bytes) -> bool:
    """Strictly parse and validate a serialized adjudication ledger."""
    return validate_ledger(loads_json_strict(document))
