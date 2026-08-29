"""Apply exact reviewed temporal county-parent transitions to terrain ownership."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping

import numpy as np

try:
    from tools.map.build_han_parent_reconciliation import _temporal_adjudication_context
    from tools.map.han_province_model import (
        AdministrativeHistory,
        load_administrative_history,
        resolve_relations,
        validate_history,
    )
    from tools.map.han_tiles_contract import loads_json_strict
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from build_han_parent_reconciliation import _temporal_adjudication_context
    from han_province_model import (
        AdministrativeHistory,
        load_administrative_history,
        resolve_relations,
        validate_history,
    )
    from han_tiles_contract import loads_json_strict


ROOT = Path(__file__).resolve().parents[2]
TEMPORAL_PATH = ROOT / "data/curated/han/administrative-temporal-adjudications-v1.json"
CATALOG_PATH = ROOT / "data/curated/han/administrative-units.json"
BINDINGS_PATH = ROOT / "data/curated/han/administrative-place-bindings-v1.json"
HISTORY_PATH = ROOT / "data/map/han-administrative-history.json"


@dataclass(frozen=True)
class ReviewedParentInterval:
    parent_id: str
    effective_from: int
    effective_to: int | None


@dataclass(frozen=True)
class ReviewedPhysicalWitness:
    source_id: str
    snapshot_sha256: str
    locator: str
    physical_place_id: str
    name_ch: str
    name_ft: str
    type_ch: str
    beg_yr: int
    end_yr: int
    lon: float
    lat: float
    gx: int
    gy: int
    junguozhi_child_name: str


@dataclass(frozen=True)
class ReviewedTemporalAdjudication:
    administrative_unit_id: str
    history_child_id: str
    physical_witness: ReviewedPhysicalWitness
    parent_intervals: tuple[ReviewedParentInterval, ...]
    forbidden_identities: tuple[str, ...]


@dataclass(frozen=True)
class ReviewedTemporalSeedOverride:
    place_index: int
    parent_index: int
    history_child_name: str
    junguozhi_child_name: str
    initial_source_parent_name: str


@dataclass(frozen=True)
class ReviewedTemporalParentContext:
    reference_year: int
    adjudications: Mapping[str, ReviewedTemporalAdjudication]
    history: AdministrativeHistory


def _load_object(path: Path | str, label: str) -> dict[str, Any]:
    source_path = Path(path)
    try:
        document = loads_json_strict(source_path.read_bytes())
    except OSError as error:
        raise ValueError(f"cannot read {label}: {source_path}") from error
    if not isinstance(document, dict):
        raise ValueError(f"{label} root must be an object")
    return document


def load_reviewed_temporal_parent_context(
    *,
    temporal_path: Path | str = TEMPORAL_PATH,
    catalog_path: Path | str = CATALOG_PATH,
    bindings_path: Path | str = BINDINGS_PATH,
    history_path: Path | str = HISTORY_PATH,
) -> ReviewedTemporalParentContext:
    """Load the existing exact review contract and join it to validated history IDs."""
    temporal = _load_object(temporal_path, "temporal adjudications")
    catalog = _load_object(catalog_path, "administrative catalog")
    bindings = _load_object(bindings_path, "administrative bindings")
    adjudications = _temporal_adjudication_context(temporal, catalog, bindings)
    history = load_administrative_history(history_path)
    validate_history(history)
    units = history.units_by_id
    relations = {
        (relation.child_id, relation.parent_id, relation.effective_from, relation.effective_to)
        for relation in history.relations
    }
    for physical_id, adjudication in adjudications.items():
        child_id = adjudication["historyChildId"]
        child = units.get(child_id)
        if child is None or child.kind != "COUNTY":
            raise ValueError(f"reviewed physical place {physical_id} has unknown history child")
        for interval in adjudication["parentIntervals"]:
            parent_id = interval["parentId"]
            parent = units.get(parent_id)
            if parent is None or parent.kind not in {"COMMANDERY", "KINGDOM"}:
                raise ValueError(f"reviewed physical place {physical_id} has unknown history parent")
            relation = (
                child_id, parent_id, interval["effectiveFrom"], interval["effectiveTo"]
            )
            if relation not in relations:
                raise ValueError(
                    f"reviewed physical place {physical_id} temporal interval mismatches history"
                )
    reference_year = temporal["referenceYear"]
    if reference_year not in history.supported_years:
        raise ValueError("temporal reference year is unsupported by administrative history")
    frozen_adjudications = {
        physical_id: ReviewedTemporalAdjudication(
            administrative_unit_id=adjudication["administrativeUnitId"],
            history_child_id=adjudication["historyChildId"],
            physical_witness=ReviewedPhysicalWitness(
                source_id=adjudication["physicalWitness"]["sourceId"],
                snapshot_sha256=adjudication["physicalWitness"]["snapshotSha256"],
                locator=adjudication["physicalWitness"]["locator"],
                physical_place_id=adjudication["physicalWitness"]["physicalPlaceId"],
                name_ch=adjudication["physicalWitness"]["nameCh"],
                name_ft=adjudication["physicalWitness"]["nameFt"],
                type_ch=adjudication["physicalWitness"]["typeCh"],
                beg_yr=adjudication["physicalWitness"]["begYr"],
                end_yr=adjudication["physicalWitness"]["endYr"],
                lon=adjudication["physicalWitness"]["lon"],
                lat=adjudication["physicalWitness"]["lat"],
                gx=adjudication["physicalWitness"]["gx"],
                gy=adjudication["physicalWitness"]["gy"],
                junguozhi_child_name=adjudication["physicalWitness"][
                    "junguozhiChildName"
                ],
            ),
            parent_intervals=tuple(
                ReviewedParentInterval(
                    parent_id=interval["parentId"],
                    effective_from=interval["effectiveFrom"],
                    effective_to=interval["effectiveTo"],
                )
                for interval in adjudication["parentIntervals"]
            ),
            forbidden_identities=tuple(adjudication["forbiddenIdentities"]),
        )
        for physical_id, adjudication in adjudications.items()
    }
    return ReviewedTemporalParentContext(
        reference_year=reference_year,
        adjudications=MappingProxyType(frozen_adjudications),
        history=history,
    )


def _exact_year(year: Any) -> int:
    if type(year) is not int:
        raise ValueError("scenario year must be an integer")
    return year


def _active_parent(
    adjudication: ReviewedTemporalAdjudication,
    year: int,
    history: AdministrativeHistory,
):
    matches = [
        interval for interval in adjudication.parent_intervals
        if interval.effective_from <= year
        and (interval.effective_to is None or year < interval.effective_to)
    ]
    if len(matches) != 1:
        raise ValueError(f"reviewed temporal parent is not unique in scenario year {year}")
    parent_id = matches[0].parent_id
    resolved_parent = resolve_relations(history.relations, year).get(
        adjudication.history_child_id
    )
    if resolved_parent != parent_id:
        raise ValueError("reviewed temporal parent and administrative history disagree")
    parent = history.units_by_id.get(parent_id)
    if parent is None or parent.kind not in {"COMMANDERY", "KINGDOM"}:
        raise ValueError("reviewed temporal parent has an unknown history identity")
    return parent


def apply_reviewed_temporal_parents(
    *,
    places: list[dict[str, Any]],
    jun_of: Any,
    jun_names: list[str],
    year: int,
    context: ReviewedTemporalParentContext,
    require_reference_year: bool = False,
) -> dict[str, Any]:
    """Return pre-Dijkstra membership and exact reviewed source-label overrides."""
    year = _exact_year(year)
    if not isinstance(context, ReviewedTemporalParentContext):
        raise ValueError("reviewed temporal parent context is required")
    if require_reference_year and year != context.reference_year:
        raise ValueError(
            f"terrain year {year} does not match reviewed reference year {context.reference_year}"
        )
    if not isinstance(places, list) or not places:
        raise ValueError("places must be a nonempty array")
    place_ids = []
    for index, place in enumerate(places):
        if not isinstance(place, dict) or not isinstance(place.get("id"), str) or not place["id"]:
            raise ValueError(f"places[{index}] requires a stable physical ID")
        place_ids.append(place["id"])
    if len(set(place_ids)) != len(place_ids):
        raise ValueError("stable physical place IDs must be unique")
    id_to_index = {place_id: index for index, place_id in enumerate(place_ids)}

    if not isinstance(jun_names, list) or not jun_names or any(
        not isinstance(name, str) or not name for name in jun_names
    ):
        raise ValueError("jun_names must contain nonempty exact names")
    if len(set(jun_names)) != len(jun_names):
        raise ValueError("jun_names must be unique for exact parent resolution")
    name_to_index = {name: index for index, name in enumerate(jun_names)}
    membership = np.asarray(jun_of)
    if membership.ndim != 1 or len(membership) != len(places) or membership.dtype.kind not in "iu":
        raise ValueError("jun_of must be an integer label for every physical place")
    membership = membership.astype(np.int32, copy=True)
    if np.any(membership < 0) or np.any(membership >= len(jun_names)):
        raise ValueError("jun_of contains an unknown parent index")
    units = context.history.units_by_id
    seed_overrides = []
    for physical_id, adjudication in context.adjudications.items():
        place_index = id_to_index.get(physical_id)
        if place_index is None:
            raise ValueError(f"reviewed stable physical place {physical_id} is missing")
        witness = adjudication.physical_witness
        expected_identity = {
            "id": witness.physical_place_id,
            "nameCh": witness.name_ch,
            "nameFt": witness.name_ft,
            "typeCh": witness.type_ch,
            "begYr": witness.beg_yr,
            "endYr": witness.end_yr,
            "lon": witness.lon,
            "lat": witness.lat,
            "gx": witness.gx,
            "gy": witness.gy,
        }
        place = places[place_index]
        if any(
            key not in place
            or type(place[key]) is not type(expected)
            or place[key] != expected
            for key, expected in expected_identity.items()
        ):
            raise ValueError(
                f"reviewed stable physical place {physical_id} differs from physical witness"
            )
        initial_parent = units.get(adjudication.parent_intervals[0].parent_id)
        if initial_parent is None:
            raise ValueError("reviewed initial parent has an unknown history identity")
        current_parent_index = int(membership[place_index])
        if jun_names[current_parent_index] != initial_parent.name:
            raise ValueError(
                f"reviewed stable physical place {physical_id} has an unexpected initial parent"
            )
        parent = _active_parent(adjudication, year, context.history)
        parent_index = name_to_index.get(parent.name)
        if parent_index is None:
            raise ValueError(f"reviewed history parent {parent.id} has no exact terrain jun name")
        history_child = units.get(adjudication.history_child_id)
        if history_child is None or history_child.kind != "COUNTY":
            raise ValueError("reviewed temporal child has an unknown history identity")
        membership[place_index] = parent_index
        seed_overrides.append(ReviewedTemporalSeedOverride(
            place_index=place_index,
            parent_index=parent_index,
            history_child_name=history_child.name,
            junguozhi_child_name=witness.junguozhi_child_name,
            initial_source_parent_name=initial_parent.name,
        ))
    return {"junOf": membership, "seedOverrides": tuple(seed_overrides)}
