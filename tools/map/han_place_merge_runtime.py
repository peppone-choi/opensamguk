"""Apply reviewed Han place merge adjudications during generation.

The raw place seeds must finish Voronoi ownership and historical parent folding
before this module runs.  This module then validates the reviewed identities,
applies stable-ID transforms, and compacts every index-bearing structure.  It
does not build or copy roads or adjacency: callers must derive those graphs from
the returned compact state.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np

try:
    from tools.map.han_place_merge_adjudications import (
        identity_sha256,
        validate_ledger,
        validate_ledger_json,
    )
    from tools.map.han_tiles_contract import loads_json_strict
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from han_place_merge_adjudications import (
        identity_sha256,
        validate_ledger,
        validate_ledger_json,
    )
    from han_tiles_contract import loads_json_strict


ROOT = Path(__file__).resolve().parents[2]
LEDGER_PATH = ROOT / "data/curated/han/han-place-merge-adjudications-v1.json"
SOURCE_PLACE_IDS = (
    "34539", "34546", "211278", "87633", "211473", "34526",
)
REVIEWED_CATALOG_PLACE_IDS = ("33425", "42777")
REVIEWED_CATALOG_IDENTITIES = {
    "33425": {
        "id": "33425", "nameCh": "彭城国", "nameFt": "彭城國",
        "typeCh": "侯国", "begYr": 88, "endYr": 323,
        "kind": "KINGDOM", "level": 6,
    },
    "42777": {
        "id": "42777", "nameCh": "彭城县", "nameFt": "彭城縣",
        "typeCh": "县", "begYr": -223, "endYr": 1264,
        "kind": "COUNTY", "level": 5,
    },
}

_RAW_TO_LEDGER_FIELDS = {
    "physicalPlaceId": "id",
    "sourceNameCh": "nameCh",
    "sourceNameFt": "nameFt",
    "typeCh": "typeCh",
    "begYr": "begYr",
    "endYr": "endYr",
    "kind": "kind",
    "level": "level",
}


def load_reviewed_ledger(path: Path | str | None = None) -> dict[str, Any]:
    """Strictly load the committed ledger; no permissive JSON fallback exists."""
    ledger_path = Path(path) if path is not None else LEDGER_PATH
    document = ledger_path.read_bytes()
    validate_ledger_json(document)
    return loads_json_strict(document)


def _exact_int(value: Any, label: str) -> int:
    if type(value) is not int:
        raise ValueError(f"{label} must be an integer")
    return value


def _place_id(place: Any, label: str) -> str:
    if not isinstance(place, dict):
        raise ValueError(f"{label} must be an object")
    value = place.get("id")
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label}.id must be a nonempty string")
    return value


def _validate_label_array(
    value: Any,
    label: str,
    upper_bound: int,
    *,
    shape: tuple[int, int] | None = None,
) -> np.ndarray:
    array = np.asarray(value)
    if array.ndim != 2 or (shape is not None and array.shape != shape):
        raise ValueError(f"{label} must be a two-dimensional grid with the expected shape")
    if array.dtype.kind not in "iu":
        raise ValueError(f"{label} labels must be integers")
    if np.any(array < -1) or np.any(array >= upper_bound):
        raise ValueError(f"{label} contains an out-of-range label")
    return array.astype(np.int32, copy=True)


def _raw_identity(place: dict[str, Any]) -> dict[str, Any]:
    try:
        return {
            ledger_key: place[raw_key]
            for ledger_key, raw_key in _RAW_TO_LEDGER_FIELDS.items()
        }
    except KeyError as error:
        raise ValueError(f"raw place is missing identity field {error.args[0]!r}") from error


def _validate_identity(place: dict[str, Any], expected: dict[str, Any], label: str) -> None:
    identity = _raw_identity(place)
    expected_identity = {
        key: expected[key] for key in _RAW_TO_LEDGER_FIELDS
    }
    if identity != expected_identity:
        raise ValueError(f"{label} raw identity differs from the reviewed ledger")
    if identity_sha256(identity) != expected["sourceRecordSha256"]:
        raise ValueError(f"{label} raw identity fingerprint differs from the ledger")


def _validate_initial_state(
    places: Any,
    owner: Any,
    seat_owner: Any,
    jun_of: Any,
    hubs: Any,
    jun_names: Any,
    zhi_places: Any,
) -> tuple[
    list[dict[str, Any]], np.ndarray, np.ndarray, np.ndarray,
    list[int], list[str], list[int], dict[str, int],
]:
    if not isinstance(places, list) or not places:
        raise ValueError("places must be a nonempty array")
    raw_places = list(places)
    ids = [_place_id(place, f"places[{index}]") for index, place in enumerate(raw_places)]
    if len(set(ids)) != len(ids):
        raise ValueError("raw physical place IDs must be unique")
    id_to_index = {place_id: index for index, place_id in enumerate(ids)}

    if not isinstance(jun_names, list) or not jun_names:
        raise ValueError("jun_names must be a nonempty array")
    names = []
    for index, name in enumerate(jun_names):
        if not isinstance(name, str) or not name:
            raise ValueError(f"jun_names[{index}] must be a nonempty string")
        names.append(name)
    if len(set(names)) != len(names):
        raise ValueError("initial jun names must be unique")
    jun_count = len(names)

    membership = np.asarray(jun_of)
    if membership.ndim != 1 or len(membership) != len(raw_places):
        raise ValueError("jun_of length must equal the raw place count")
    if membership.dtype.kind not in "iu":
        raise ValueError("jun_of labels must be integers")
    membership = membership.astype(np.int32, copy=True)
    if np.any(membership < 0) or np.any(membership >= jun_count):
        raise ValueError("jun_of contains an out-of-range label")

    if not isinstance(hubs, list) or len(hubs) != jun_count:
        raise ValueError("hubs length must equal jun_names length")
    hub_indices = []
    hub_cells = []
    for jun_index, raw_hub in enumerate(hubs):
        hub = _exact_int(raw_hub, f"hubs[{jun_index}]")
        if not 0 <= hub < len(raw_places):
            raise ValueError(f"hubs[{jun_index}] is out of range")
        if int(membership[hub]) != jun_index:
            raise ValueError(f"hubs[{jun_index}] is not a member of its jun")
        place = raw_places[hub]
        gx = _exact_int(place.get("gx"), f"places[{hub}].gx")
        gy = _exact_int(place.get("gy"), f"places[{hub}].gy")
        hub_indices.append(hub)
        hub_cells.append((gx, gy))
    if len(set(hub_indices)) != len(hub_indices):
        raise ValueError("initial hubs must be unique")
    if len(set(hub_cells)) != len(hub_cells):
        raise ValueError("initial hub cells must be unique")

    county_owner = _validate_label_array(owner, "owner", len(raw_places))
    commandery_owner = _validate_label_array(
        seat_owner, "seat_owner", jun_count, shape=county_owner.shape
    )
    rows, cols = county_owner.shape
    for index, (gx, gy) in enumerate(hub_cells):
        if not (0 <= gx < cols and 0 <= gy < rows):
            raise ValueError(f"hubs[{index}] cell is outside the owner grid")

    if not isinstance(zhi_places, list):
        raise ValueError("zhi_places must be an array")
    zhi = []
    for index, raw_value in enumerate(zhi_places):
        value = _exact_int(raw_value, f"zhi_places[{index}]")
        if not 0 <= value < len(raw_places):
            raise ValueError(f"zhi_places[{index}] is out of range")
        zhi.append(value)
    if len(set(zhi)) != len(zhi):
        raise ValueError("zhi_places must be unique")

    return (
        raw_places, county_owner, commandery_owner, membership,
        hub_indices, names, zhi, id_to_index,
    )


def apply_reviewed_merges(
    *,
    places: list[dict[str, Any]],
    owner: Any,
    seat_owner: Any,
    jun_of: Any,
    hubs: list[int],
    jun_names: list[str],
    zhi_places: list[int],
    ledger: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Apply all six reviewed transforms and return a compact generation state."""
    reviewed = load_reviewed_ledger() if ledger is None else ledger
    validate_ledger(reviewed)
    (
        raw_places, county_owner, commandery_owner, membership,
        raw_hubs, names, zhi, id_to_index,
    ) = _validate_initial_state(
        places, owner, seat_owner, jun_of, hubs, jun_names, zhi_places
    )

    resolved = []
    source_indices: set[int] = set()
    merge_juns: set[int] = set()
    for row_index, row in enumerate(reviewed["adjudications"]):
        source_id = row["sourcePlace"]["physicalPlaceId"]
        target_id = row["targetPlace"]["physicalPlaceId"]
        if source_id not in id_to_index or target_id not in id_to_index:
            raise ValueError(f"adjudication {row_index} source or target is missing")
        source = id_to_index[source_id]
        target = id_to_index[target_id]
        _validate_identity(raw_places[source], row["sourcePlace"], f"source {source_id}")
        _validate_identity(raw_places[target], row["targetPlace"], f"target {target_id}")
        source_jun = int(membership[source])
        target_jun = int(membership[target])
        if names[source_jun] != row["expectedInitialSourceJunNameFt"]:
            raise ValueError(f"source {source_id} initial jun differs from the ledger")
        if names[target_jun] != row["expectedInitialTargetJunNameFt"]:
            raise ValueError(f"target {target_id} initial jun differs from the ledger")
        if raw_hubs[source_jun] != source:
            raise ValueError(f"source {source_id} is not the initial hub of its jun")
        if row["operation"] == "RESEAT_WITHIN_JUN":
            if names[source_jun] != row["resultJunNameFt"]:
                raise ValueError(f"reseat {source_id} result jun is not the source jun")
        elif row["operation"] == "MERGE_JUN":
            if raw_hubs[target_jun] != target:
                raise ValueError(f"merge target {target_id} is not its initial jun hub")
            if names[target_jun] != row["resultJunNameFt"]:
                raise ValueError(f"merge {source_id} result jun is not the target jun")
            merge_juns.add(source_jun)
        else:  # Task 3A validation already closes this, kept fail-closed here.
            raise ValueError(f"adjudication {row_index} operation is unsupported")
        source_indices.add(source)
        resolved.append((row["operation"], source, target, source_jun, target_jun))

    if len(source_indices) != len(SOURCE_PLACE_IDS):
        raise ValueError("reviewed source indices are not all unique")
    if len(merge_juns) != 3:
        raise ValueError("reviewed merge jun indices are not all unique")

    for operation, source, target, source_jun, target_jun in resolved:
        county_owner[county_owner == source] = target
        if operation == "RESEAT_WITHIN_JUN":
            raw_hubs[source_jun] = target
            membership[target] = source_jun
            commandery_owner[county_owner == target] = source_jun
        else:
            membership[membership == source_jun] = target_jun
            commandery_owner[commandery_owner == source_jun] = target_jun

    survivors = [index for index in range(len(raw_places)) if index not in source_indices]
    raw_to_compact = np.full(len(raw_places), -1, dtype=np.int32)
    for compact, raw in enumerate(survivors):
        raw_to_compact[raw] = compact
    if np.any((county_owner >= 0) & (raw_to_compact[county_owner] < 0)):
        raise ValueError("owner still references a removed source place")
    compact_owner = np.where(
        county_owner >= 0, raw_to_compact[county_owner], -1
    ).astype(np.int32)

    live_juns = [index for index in range(len(names)) if index not in merge_juns]
    jun_remap = np.full(len(names), -1, dtype=np.int32)
    for compact, raw in enumerate(live_juns):
        jun_remap[raw] = compact
    if np.any(jun_remap[membership[survivors]] < 0):
        raise ValueError("jun_of still references a removed source jun")
    if np.any((commandery_owner >= 0) & (jun_remap[commandery_owner] < 0)):
        raise ValueError("seat_owner still references a removed source jun")

    compact_places = [raw_places[index] for index in survivors]
    compact_hubs = [int(raw_to_compact[raw_hubs[index]]) for index in live_juns]
    if any(hub < 0 for hub in compact_hubs):
        raise ValueError("a live jun hub references a removed source place")
    compact_jun_of = jun_remap[membership[survivors]].astype(np.int32)
    compact_seat_owner = np.where(
        commandery_owner >= 0, jun_remap[commandery_owner], -1
    ).astype(np.int32)
    compact_zhi = sorted(
        int(raw_to_compact[index]) for index in zhi if raw_to_compact[index] >= 0
    )

    rows, cols = compact_owner.shape
    for jun_index, hub in enumerate(compact_hubs):
        place = compact_places[hub]
        gx = _exact_int(place.get("gx"), f"compact hub {hub}.gx")
        gy = _exact_int(place.get("gy"), f"compact hub {hub}.gy")
        if not (0 <= gx < cols and 0 <= gy < rows):
            raise ValueError(f"compact hub {hub} is outside the grid")
        compact_seat_owner[gy, gx] = jun_index

    result = {
        "places": compact_places,
        "placeIds": [place["id"] for place in compact_places],
        "owner": compact_owner,
        "seatOwner": compact_seat_owner,
        "hubs": compact_hubs,
        "junOf": compact_jun_of,
        "junNames": [names[index] for index in live_juns],
        "zhiPlaces": compact_zhi,
        "rawToCompact": raw_to_compact,
    }
    validate_compacted_state(result)
    return result


def validate_reviewed_catalog_policy(state: dict[str, Any]) -> bool:
    """Preserve the reviewed Pengcheng inclusion and non-seat decision."""
    ids = state["placeIds"]
    places = state["places"]
    hubs = state["hubs"]
    jun_names = state["junNames"]
    jun_of = state["junOf"]
    zhi_places = state["zhiPlaces"]
    by_id = {place_id: index for index, place_id in enumerate(ids)}
    missing = set(REVIEWED_CATALOG_PLACE_IDS) - set(ids)
    if missing:
        raise ValueError(f"reviewed Pengcheng place IDs are missing: {sorted(missing)}")
    for place_id, expected in REVIEWED_CATALOG_IDENTITIES.items():
        place = places[by_id[place_id]]
        actual = {key: place.get(key) for key in expected}
        if actual != expected:
            raise ValueError(
                f"reviewed Pengcheng identity/classification differs for {place_id}"
            )
    pengcheng_city = by_id["33425"]
    if pengcheng_city in hubs:
        raise ValueError("33425 彭城国 must remain a non-seat city")
    if pengcheng_city in zhi_places:
        raise ValueError("33425 彭城国 must remain outside the zhi candidate set")
    if "彭城國" not in jun_names:
        raise ValueError("彭城國 jun is missing")
    pengcheng = jun_names.index("彭城國")
    if type(jun_of[pengcheng_city]) is not int and not isinstance(
            jun_of[pengcheng_city], np.integer):
        raise ValueError("33425 彭城国 jun membership must be an integer")
    if int(jun_of[pengcheng_city]) != pengcheng:
        raise ValueError("33425 彭城国 must belong to 彭城國")
    if ids[hubs[pengcheng]] != "42777":
        raise ValueError("彭城國 must retain physical place 42777 as its hub")
    return True


def validate_compacted_state(state: dict[str, Any]) -> bool:
    """Validate compact indices before any downstream graph is derived."""
    required = {
        "places", "placeIds", "owner", "seatOwner", "hubs", "junOf",
        "junNames", "zhiPlaces",
    }
    if not isinstance(state, dict) or not required <= set(state):
        raise ValueError("compacted state is missing required fields")
    places = state["places"]
    ids = state["placeIds"]
    if not isinstance(places, list) or not isinstance(ids, list) or len(places) != len(ids):
        raise ValueError("places and placeIds must have exactly matching lengths")
    if ids != [_place_id(place, f"places[{index}]") for index, place in enumerate(places)]:
        raise ValueError("placeIds must exactly match compact place order")
    if len(set(ids)) != len(ids):
        raise ValueError("compact placeIds must be unique")
    if set(ids) & set(SOURCE_PLACE_IDS):
        raise ValueError("compact placeIds still contain reviewed source IDs")

    jun_names = state["junNames"]
    hubs = state["hubs"]
    if not isinstance(jun_names, list) or not jun_names or len(hubs) != len(jun_names):
        raise ValueError("hubs and junNames must have equal nonzero lengths")
    if len(set(jun_names)) != len(jun_names):
        raise ValueError("compact junNames must be unique")
    jun_count = len(jun_names)

    membership = np.asarray(state["junOf"])
    if membership.ndim != 1 or len(membership) != len(places) or membership.dtype.kind not in "iu":
        raise ValueError("junOf must be an integer array matching placeIds")
    if np.any(membership < 0) or np.any(membership >= jun_count):
        raise ValueError("junOf contains an out-of-range label")

    owner = _validate_label_array(state["owner"], "owner", len(places))
    seat_owner = _validate_label_array(
        state["seatOwner"], "seatOwner", jun_count, shape=owner.shape
    )
    rows, cols = owner.shape
    checked_hubs = []
    hub_cells = []
    for jun_index, raw_hub in enumerate(hubs):
        hub = _exact_int(raw_hub, f"hubs[{jun_index}]")
        if not 0 <= hub < len(places):
            raise ValueError(f"hubs[{jun_index}] is out of range")
        if int(membership[hub]) != jun_index:
            raise ValueError(f"hubs[{jun_index}] is not a member of its jun")
        gx = _exact_int(places[hub].get("gx"), f"places[{hub}].gx")
        gy = _exact_int(places[hub].get("gy"), f"places[{hub}].gy")
        if not (0 <= gx < cols and 0 <= gy < rows):
            raise ValueError(f"hubs[{jun_index}] cell is outside the grid")
        if int(seat_owner[gy, gx]) != jun_index:
            raise ValueError(f"hubs[{jun_index}] seat cell does not belong to its jun")
        checked_hubs.append(hub)
        hub_cells.append((gx, gy))
    if len(set(checked_hubs)) != len(checked_hubs):
        raise ValueError("compact hubs must be unique")
    if len(set(hub_cells)) != len(hub_cells):
        raise ValueError("compact hub cells must be unique")

    zhi = state["zhiPlaces"]
    if not isinstance(zhi, list):
        raise ValueError("zhiPlaces must be an array")
    if any(type(value) is not int or not 0 <= value < len(places) for value in zhi):
        raise ValueError("zhiPlaces contains an invalid index")
    if len(set(zhi)) != len(zhi):
        raise ValueError("zhiPlaces must be unique")

    place_areas = np.bincount(owner[owner >= 0], minlength=len(places))
    if len(place_areas) != len(places) or np.any(place_areas == 0):
        raise ValueError("every compact place must own at least one owner cell")
    areas = np.bincount(seat_owner[seat_owner >= 0], minlength=jun_count)
    if len(areas) != jun_count or np.any(areas == 0):
        raise ValueError("every jun must own at least one seatOwner cell")
    validate_reviewed_catalog_policy(state)
    return True
