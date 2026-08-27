#!/usr/bin/env python3
"""Immutable loaders and scenario-year resolver for Han administrative manifests."""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping


ADMINISTRATIVE_UNIT_KINDS = frozenset(
    {"COUNTY", "DIRECT_TERRITORY", "COMMANDERY", "KINGDOM", "EXTERNAL_POLITY"}
)
EVIDENCE_GRADES = frozenset(
    {
        "STANDARD_HISTORY",
        "CHRONICLE",
        "CONTEMPORARY_GEOGRAPHY",
        "LATER_GEOGRAPHY",
        "COMMENTARY",
        "NOVEL",
    }
)
LOCATION_CONFIDENCES = frozenset({"HIGH", "MEDIUM", "LOW", "UNKNOWN"})
PROVENANCE_STATUSES = frozenset({"SOURCED", "PROVISIONAL"})
SITE_KINDS = frozenset(
    {
        "MOUNTAIN_BATTLEFIELD",
        "PASS",
        "FORTRESS",
        "FORD",
        "PORT",
        "BATTLEFIELD",
        "PALACE",
        "TOMB",
        "SHRINE",
        "MONUMENT",
    }
)
SETTLEMENT_ROLES = frozenset(
    {
        "COUNTY_SEAT",
        "COMMANDERY_SEAT",
        "PROVINCIAL_SEAT",
        "POLITY_CAPITAL",
        "IMPERIAL_CAPITAL",
    }
)


def _require_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


def _require_int(value: object, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"{field} must be an integer")
    return value


def _read_json(path: Path) -> Mapping[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise ValueError(f"cannot read manifest {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON in {path}: {error}") from error
    if not isinstance(payload, dict):
        raise ValueError("manifest root must be an object")
    return payload


def _range_from(payload: Mapping[str, Any], label: str) -> tuple[int, int | None]:
    effective_from = _require_int(payload.get("effectiveFrom"), f"{label}.effectiveFrom")
    effective_to_value = payload.get("effectiveTo")
    effective_to = (
        None
        if effective_to_value is None
        else _require_int(effective_to_value, f"{label}.effectiveTo")
    )
    if effective_to is not None and effective_to <= effective_from:
        raise ValueError(f"{label} has invalid effective range")
    return effective_from, effective_to


@dataclass(frozen=True)
class Evidence:
    book: str
    volume: str
    section: str
    quote: str
    grade: str
    claim: str
    location_confidence: str

    @classmethod
    def from_json(cls, payload: object, label: str) -> "Evidence":
        if not isinstance(payload, dict):
            raise ValueError(f"{label} must be an object")
        evidence = cls(
            book=_require_string(payload.get("book"), f"{label}.book"),
            volume=_require_string(payload.get("volume"), f"{label}.volume"),
            section=_require_string(payload.get("section"), f"{label}.section"),
            quote=_require_string(payload.get("quote"), f"{label}.quote"),
            grade=_require_string(payload.get("grade"), f"{label}.grade"),
            claim=_require_string(payload.get("claim"), f"{label}.claim"),
            location_confidence=_require_string(
                payload.get("locationConfidence"), f"{label}.locationConfidence"
            ),
        )
        if evidence.grade not in EVIDENCE_GRADES:
            raise ValueError(f"{label}.grade is unsupported: {evidence.grade}")
        if evidence.location_confidence not in LOCATION_CONFIDENCES:
            raise ValueError(
                f"{label}.locationConfidence is unsupported: {evidence.location_confidence}"
            )
        return evidence


@dataclass(frozen=True)
class AdministrativeUnit:
    id: str
    kind: str
    name: str
    historical_subtype: str | None
    provenance_status: str | None
    evidence_gap: str | None
    evidence: tuple[Evidence, ...]


@dataclass(frozen=True)
class EffectiveRelation:
    child_id: str
    parent_id: str
    effective_from: int
    effective_to: int | None

    def active(self, year: int) -> bool:
        return self.effective_from <= year and (
            self.effective_to is None or year < self.effective_to
        )


@dataclass(frozen=True)
class SeatAssignment:
    unit_id: str
    settlement_id: str
    effective_from: int
    effective_to: int | None

    def active(self, year: int) -> bool:
        return self.effective_from <= year and (
            self.effective_to is None or year < self.effective_to
        )


@dataclass(frozen=True)
class RoleAssignment:
    settlement_id: str
    role: str
    effective_from: int
    effective_to: int | None

    def active(self, year: int) -> bool:
        return self.effective_from <= year and (
            self.effective_to is None or year < self.effective_to
        )


@dataclass(frozen=True)
class AdministrativeHistory:
    schema_version: int
    supported_years: tuple[int, ...]
    units: tuple[AdministrativeUnit, ...]
    relations: tuple[EffectiveRelation, ...]
    seats: tuple[SeatAssignment, ...]
    roles: tuple[RoleAssignment, ...]
    require_county_seats: bool

    @property
    def units_by_id(self) -> Mapping[str, AdministrativeUnit]:
        return MappingProxyType({unit.id: unit for unit in self.units})


@dataclass(frozen=True)
class StrategicSite:
    id: str
    name: str
    kind: str
    rank: int
    evidence: tuple[Evidence, ...]
    alias_of: str | None


@dataclass(frozen=True)
class AdministrativeHierarchySnapshot:
    province_to_county: Mapping[str, str]
    county_to_commandery: Mapping[str, str]
    county_seats: Mapping[str, str]
    roles_by_settlement: Mapping[str, frozenset[str]]


def _parse_evidence_list(
    payload: Mapping[str, Any], label: str, *, required: bool = False
) -> tuple[Evidence, ...]:
    raw_evidence = payload.get("evidence", [])
    if not isinstance(raw_evidence, list):
        raise ValueError(f"{label}.evidence must be an array")
    evidence = tuple(
        Evidence.from_json(item, f"{label}.evidence[{index}]")
        for index, item in enumerate(raw_evidence)
    )
    if required and not evidence:
        raise ValueError(f"{label}.evidence must contain a verbatim quote")
    return evidence


def _load_catalog_reference(
    payload: Mapping[str, Any], history_path: Path
) -> tuple[list[AdministrativeUnit], list[EffectiveRelation]]:
    reference = payload.get("catalogReference")
    if reference is None:
        return [], []
    reference = _require_string(reference, "catalogReference")
    catalog = _read_json(history_path.parent / reference)
    _require_string(catalog.get("catalogId"), "catalogReference.catalogId")
    raw_groups = catalog.get("groups")
    if not isinstance(raw_groups, list):
        raise ValueError("catalogReference.groups must be an array")

    units: list[AdministrativeUnit] = []
    relations: list[EffectiveRelation] = []
    for group_index, raw_group in enumerate(raw_groups, start=1):
        label = f"catalogReference.groups[{group_index - 1}]"
        if not isinstance(raw_group, dict):
            raise ValueError(f"{label} must be an object")
        source_volume = _require_int(raw_group.get("sourceVolume"), f"{label}.sourceVolume")
        source_name = _require_string(raw_group.get("sourceGroupName"), f"{label}.sourceGroupName")
        canonical_name = _require_string(raw_group.get("canonicalGroup"), f"{label}.canonicalGroup")
        group_kind = _require_string(raw_group.get("groupType"), f"{label}.groupType")
        if group_kind not in {"COMMANDERY", "KINGDOM"}:
            raise ValueError(f"{label}.groupType is unsupported: {group_kind}")
        parent_id = f"{group_kind.lower()}:hhs:{source_volume}:{group_index}"
        group_evidence = _parse_evidence_list(raw_group, label, required=True)
        units.append(
            AdministrativeUnit(
                parent_id,
                group_kind,
                canonical_name,
                None,
                "SOURCED",
                None,
                group_evidence,
            )
        )
        raw_children = raw_group.get("units")
        if not isinstance(raw_children, list):
            raise ValueError(f"{label}.units must be an array")
        for child_index, raw_child in enumerate(raw_children, start=1):
            child_label = f"{label}.units[{child_index - 1}]"
            if not isinstance(raw_child, dict):
                raise ValueError(f"{child_label} must be an object")
            ordinal = _require_int(raw_child.get("ordinal"), f"{child_label}.ordinal")
            if ordinal != child_index:
                raise ValueError(f"{child_label}.ordinal must preserve source insertion order")
            source_child_name = _require_string(raw_child.get("sourceName"), f"{child_label}.sourceName")
            subtype = _require_string(raw_child.get("unitType"), f"{child_label}.unitType")
            child_id = f"county:hhs:{source_volume}:{group_index}:{ordinal}"
            units.append(
                AdministrativeUnit(
                    child_id,
                    "COUNTY",
                    source_child_name,
                    subtype,
                    "SOURCED",
                    None,
                    _parse_evidence_list(raw_child, child_label, required=True),
                )
            )
            relations.append(EffectiveRelation(child_id, parent_id, 184, None))
    return units, relations


def load_administrative_history(path: str | Path) -> AdministrativeHistory:
    history_path = Path(path)
    payload = _read_json(history_path)
    schema_version = _require_int(payload.get("schemaVersion"), "schemaVersion")
    if schema_version != 1:
        raise ValueError(f"unsupported history schemaVersion: {schema_version}")
    raw_years = payload.get("supportedYears")
    if not isinstance(raw_years, list) or not raw_years:
        raise ValueError("supportedYears must be a non-empty array")
    supported_years = tuple(_require_int(year, "supportedYears item") for year in raw_years)
    if len(set(supported_years)) != len(supported_years):
        raise ValueError("supportedYears contains duplicates")

    raw_units = payload.get("units")
    if not isinstance(raw_units, list):
        raise ValueError("units must be an array")
    units, catalog_relations = _load_catalog_reference(payload, history_path)
    seen_ids: set[str] = {unit.id for unit in units}
    for index, raw_unit in enumerate(raw_units):
        label = f"units[{index}]"
        if not isinstance(raw_unit, dict):
            raise ValueError(f"{label} must be an object")
        unit_id = _require_string(raw_unit.get("id"), f"{label}.id")
        if unit_id in seen_ids:
            raise ValueError(f"duplicate stable ID: {unit_id}")
        seen_ids.add(unit_id)
        kind = _require_string(raw_unit.get("kind"), f"{label}.kind")
        if kind not in ADMINISTRATIVE_UNIT_KINDS:
            raise ValueError(f"{label}.kind is unsupported: {kind}")
        provenance_status = (
            None
            if raw_unit.get("provenanceStatus") is None
            else _require_string(raw_unit.get("provenanceStatus"), f"{label}.provenanceStatus")
        )
        if provenance_status is not None and provenance_status not in PROVENANCE_STATUSES:
            raise ValueError(f"{label}.provenanceStatus is unsupported: {provenance_status}")
        units.append(
            AdministrativeUnit(
                id=unit_id,
                kind=kind,
                name=_require_string(raw_unit.get("name"), f"{label}.name"),
                historical_subtype=(
                    None
                    if raw_unit.get("historicalSubtype") is None
                    else _require_string(raw_unit.get("historicalSubtype"), f"{label}.historicalSubtype")
                ),
                provenance_status=provenance_status,
                evidence_gap=(
                    None
                    if raw_unit.get("evidenceGap") is None
                    else _require_string(raw_unit.get("evidenceGap"), f"{label}.evidenceGap")
                ),
                evidence=_parse_evidence_list(raw_unit, label, required=True),
            )
        )
    units_by_id = {unit.id: unit for unit in units}

    raw_relations = payload.get("relations")
    if not isinstance(raw_relations, list):
        raise ValueError("relations must be an array")
    relations: list[EffectiveRelation] = list(catalog_relations)
    for index, raw_relation in enumerate(raw_relations):
        label = f"relations[{index}]"
        if not isinstance(raw_relation, dict):
            raise ValueError(f"{label} must be an object")
        child_id = _require_string(raw_relation.get("childId"), f"{label}.childId")
        parent_id = _require_string(raw_relation.get("parentId"), f"{label}.parentId")
        if not child_id.startswith("province:") and child_id not in units_by_id:
            raise ValueError(f"{label} references unsupported child: {child_id}")
        if parent_id not in units_by_id:
            raise ValueError(f"{label} references unsupported parent: {parent_id}")
        effective_from, effective_to = _range_from(raw_relation, label)
        relations.append(EffectiveRelation(child_id, parent_id, effective_from, effective_to))

    raw_seats = payload.get("seats")
    if not isinstance(raw_seats, list):
        raise ValueError("seats must be an array")
    seats: list[SeatAssignment] = []
    for index, raw_seat in enumerate(raw_seats):
        label = f"seats[{index}]"
        if not isinstance(raw_seat, dict):
            raise ValueError(f"{label} must be an object")
        unit_id = _require_string(raw_seat.get("unitId"), f"{label}.unitId")
        if unit_id not in units_by_id:
            raise ValueError(f"{label} references unsupported unit: {unit_id}")
        effective_from, effective_to = _range_from(raw_seat, label)
        seats.append(
            SeatAssignment(
                unit_id,
                _require_string(raw_seat.get("settlementId"), f"{label}.settlementId"),
                effective_from,
                effective_to,
            )
        )

    raw_roles = payload.get("roles")
    if not isinstance(raw_roles, list):
        raise ValueError("roles must be an array")
    roles: list[RoleAssignment] = []
    for index, raw_role in enumerate(raw_roles):
        label = f"roles[{index}]"
        if not isinstance(raw_role, dict):
            raise ValueError(f"{label} must be an object")
        role = _require_string(raw_role.get("role"), f"{label}.role")
        if role not in SETTLEMENT_ROLES:
            raise ValueError(f"{label}.role is unsupported: {role}")
        effective_from, effective_to = _range_from(raw_role, label)
        roles.append(
            RoleAssignment(
                _require_string(raw_role.get("settlementId"), f"{label}.settlementId"),
                role,
                effective_from,
                effective_to,
            )
        )

    require_county_seats = payload.get("requireCountySeats", True)
    if not isinstance(require_county_seats, bool):
        raise ValueError("requireCountySeats must be a boolean")

    return AdministrativeHistory(
        schema_version,
        supported_years,
        tuple(units),
        tuple(relations),
        tuple(seats),
        tuple(roles),
        require_county_seats,
    )


def load_strategic_sites(path: str | Path) -> tuple[StrategicSite, ...]:
    payload = _read_json(Path(path))
    schema_version = _require_int(payload.get("schemaVersion"), "schemaVersion")
    if schema_version != 1:
        raise ValueError(f"unsupported strategic-site schemaVersion: {schema_version}")
    raw_sites = payload.get("sites")
    if not isinstance(raw_sites, list):
        raise ValueError("sites must be an array")
    sites: list[StrategicSite] = []
    seen_ids: set[str] = set()
    for index, raw_site in enumerate(raw_sites):
        label = f"sites[{index}]"
        if not isinstance(raw_site, dict):
            raise ValueError(f"{label} must be an object")
        site_id = _require_string(raw_site.get("id"), f"{label}.id")
        if site_id in seen_ids:
            raise ValueError(f"duplicate stable ID: {site_id}")
        seen_ids.add(site_id)
        kind = _require_string(raw_site.get("kind"), f"{label}.kind")
        if kind not in SITE_KINDS:
            raise ValueError(f"{label}.kind is unsupported: {kind}")
        rank = _require_int(raw_site.get("rank", 1), f"{label}.rank")
        if rank not in (1, 2, 3):
            raise ValueError(f"{label}.rank is unsupported: {rank}")
        evidence = _parse_evidence_list(raw_site, label)
        if not evidence:
            raise ValueError(f"{label}.evidence must contain a verbatim quote")
        alias_of = raw_site.get("aliasOf")
        sites.append(
            StrategicSite(
                site_id,
                _require_string(raw_site.get("name"), f"{label}.name"),
                kind,
                rank,
                evidence,
                None if alias_of is None else _require_string(alias_of, f"{label}.aliasOf"),
            )
        )
    return tuple(sites)


def _assert_non_overlapping_ranges(relations: tuple[EffectiveRelation, ...]) -> None:
    by_child: dict[str, list[EffectiveRelation]] = {}
    for relation in relations:
        by_child.setdefault(relation.child_id, []).append(relation)
    for child_id, child_relations in by_child.items():
        for index, relation in enumerate(child_relations):
            for contender in child_relations[index + 1 :]:
                relation_end = relation.effective_to
                contender_end = contender.effective_to
                if (relation_end is None or contender.effective_from < relation_end) and (
                    contender_end is None or relation.effective_from < contender_end
                ):
                    raise ValueError(f"overlapping parents for {child_id}")


def validate_history(history: AdministrativeHistory) -> None:
    _assert_non_overlapping_ranges(history.relations)
    for unit in history.units:
        if unit.kind == "DIRECT_TERRITORY" and (
            unit.provenance_status != "PROVISIONAL" or not unit.evidence_gap
        ):
            raise ValueError(
                f"DIRECT_TERRITORY {unit.id} requires PROVISIONAL provenanceStatus and evidenceGap"
            )
    units_by_id = history.units_by_id
    for year in history.supported_years:
        resolved = resolve_relations(history.relations, year)
        for direct_territory in (unit for unit in history.units if unit.kind == "DIRECT_TERRITORY"):
            parent_id = resolved.get(direct_territory.id)
            if parent_id is None:
                continue
            parent = units_by_id[parent_id]
            expected_name = f"{parent.name} 직할령"
            if direct_territory.name != expected_name:
                raise ValueError(
                    f"DIRECT_TERRITORY {direct_territory.id} name must be {expected_name}"
                )
            real_child_exists = any(
                child_id != direct_territory.id
                and units_by_id[child_id].kind == "COUNTY"
                and candidate_parent_id == parent_id
                for child_id, candidate_parent_id in resolved.items()
                if not child_id.startswith("province:")
            )
            if real_child_exists:
                raise ValueError(
                    f"DIRECT_TERRITORY {direct_territory.id} has a real child in {parent_id} for {year}"
                )


def resolve_relations(relations: tuple[EffectiveRelation, ...], year: int) -> Mapping[str, str]:
    resolved: dict[str, str] = {}
    for relation in relations:
        if not relation.active(year):
            continue
        if relation.child_id in resolved:
            raise ValueError(f"multiple active parents for {relation.child_id} in {year}")
        resolved[relation.child_id] = relation.parent_id
    return MappingProxyType(resolved)


def resolve_administrative_hierarchy(
    history: AdministrativeHistory, year: int
) -> AdministrativeHierarchySnapshot:
    validate_history(history)
    units_by_id = history.units_by_id
    resolved = resolve_relations(history.relations, year)
    province_to_county: dict[str, str] = {}
    county_to_commandery: dict[str, str] = {}
    for child_id, parent_id in resolved.items():
        if child_id.startswith("province:"):
            parent = units_by_id[parent_id]
            if parent.kind not in {"COUNTY", "DIRECT_TERRITORY"}:
                raise ValueError(f"province {child_id} has unsupported parent {parent_id}")
            province_to_county[child_id] = parent_id
            continue
        child = units_by_id[child_id]
        parent = units_by_id[parent_id]
        if child.kind not in {"COUNTY", "DIRECT_TERRITORY"} or parent.kind not in {
            "COMMANDERY",
            "KINGDOM",
        }:
            raise ValueError(f"unsupported administrative relation {child_id} -> {parent_id}")
        county_to_commandery[child_id] = parent_id

    active_commanderies = [
        unit
        for unit in history.units
        if unit.kind in {"COMMANDERY", "KINGDOM"}
    ]
    for commandery in active_commanderies:
        if commandery.id not in county_to_commandery.values():
            raise ValueError(f"commandery {commandery.id} has no active child in {year}")

    for child in (unit for unit in history.units if unit.kind in {"COUNTY", "DIRECT_TERRITORY"}):
        if child.id not in county_to_commandery:
            raise ValueError(f"county {child.id} has no active parent in {year}")

    county_seats: dict[str, str] = {}
    administrative_children = [
        unit for unit in history.units if unit.kind in {"COUNTY", "DIRECT_TERRITORY"}
    ]
    for child in administrative_children:
        active_seats = [seat for seat in history.seats if seat.unit_id == child.id and seat.active(year)]
        if len(active_seats) == 0 and history.require_county_seats:
            raise ValueError(f"county {child.id} has no active seat in {year}")
        if len(active_seats) > 1:
            raise ValueError(f"county {child.id} has multiple active seats in {year}")
        if active_seats:
            county_seats[child.id] = active_seats[0].settlement_id

    roles_by_settlement: dict[str, frozenset[str]] = {}
    mutable_roles: dict[str, list[str]] = {}
    for role in history.roles:
        if role.active(year):
            mutable_roles.setdefault(role.settlement_id, []).append(role.role)
    for settlement_id, roles in mutable_roles.items():
        roles_by_settlement[settlement_id] = frozenset(roles)

    return AdministrativeHierarchySnapshot(
        MappingProxyType(province_to_county),
        MappingProxyType(county_to_commandery),
        MappingProxyType(county_seats),
        MappingProxyType(roles_by_settlement),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", type=Path, required=True)
    parser.add_argument("--sites", type=Path, required=True)
    parser.add_argument("--years", required=True, help="comma-separated scenario years")
    args = parser.parse_args(argv)
    history = load_administrative_history(args.history)
    sites = load_strategic_sites(args.sites)
    years = tuple(int(year.strip()) for year in args.years.split(",") if year.strip())
    if not years:
        parser.error("--years must contain at least one year")
    for year in years:
        snapshot = resolve_administrative_hierarchy(history, year)
        print(
            f"{year}: provinces={len(snapshot.province_to_county)} "
            f"administrativeChildren={len(snapshot.county_to_commandery)} sites={len(sites)}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
