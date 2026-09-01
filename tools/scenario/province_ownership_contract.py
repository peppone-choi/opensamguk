"""Typed contract for reviewed scenario province-ownership claims."""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Mapping


CLAIM_KINDS = frozenset({
    "SCENARIO_BASELINE_UNOWNED",
    "PROVINCE_DIRECT",
    "ADMIN_REGION_CONTROL",
    "TEMPORAL_CARRY",
    "IF_SCENARIO",
    "UNOWNED_EXPLICIT",
})
EVIDENCE_SOURCE_TYPES = frozenset({
    "STANDARD_HISTORY",
    "CHRONICLE",
    "CONTEMPORARY_GEOGRAPHY",
    "LATER_GEOGRAPHY",
    "PROJECT_POLICY",
    "IF_DESIGN",
})
PLACEMENT_BASES = frozenset({"HISTORICAL", "IF_SCENARIO"})


class OwnershipContractError(ValueError):
    """Fail-closed validation error with a stable machine-readable code."""

    def __init__(self, code: str, **context: object):
        self.code = code
        self.context = MappingProxyType(dict(context))
        detail = ", ".join(f"{key}={value}" for key, value in sorted(context.items()))
        super().__init__(f"{code}: {detail}" if detail else code)


@dataclass(frozen=True)
class Evidence:
    evidence_id: str
    source_type: str
    work: str
    section: str
    locator: str
    excerpt: str
    url: str | None = None


@dataclass(frozen=True)
class TerritoryClaim:
    claim_id: str
    claim_kind: str
    owner_nation_key: str | None
    province_ids: tuple[str, ...]
    parent_region_ids: tuple[str, ...]
    all_provinces: bool
    evidence_ids: tuple[str, ...]
    overrides_claim_ids: tuple[str, ...]
    rationale: str
    inherits_claim_id: str | None = None
    effective_from: int | None = None
    effective_to: int | None = None


@dataclass(frozen=True)
class AuditAllowlistEntry:
    audit_kind: str
    province_ids: tuple[str, ...]
    evidence_ids: tuple[str, ...]
    rationale: str


@dataclass(frozen=True)
class ScenarioClaims:
    scenario_code: int
    effective_year: int
    placement_basis: str
    nation_ids: Mapping[str, int]
    claims: tuple[TerritoryClaim, ...]
    audit_allowlist: tuple[AuditAllowlistEntry, ...]


@dataclass(frozen=True)
class OwnershipDocument:
    schema_version: int
    map_id: str
    unit_set: str
    active_scenario_codes: tuple[int, ...]
    evidence: Mapping[str, Evidence]
    scenarios: Mapping[int, ScenarioClaims]


def _mapping(value: object, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise OwnershipContractError("INVALID_OBJECT", field=label)
    return value


def _rows(value: object, label: str) -> list[object]:
    if not isinstance(value, list):
        raise OwnershipContractError("INVALID_LIST", field=label)
    return value


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise OwnershipContractError("INVALID_TEXT", field=label)
    return value


def _integer(value: object, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise OwnershipContractError("INVALID_INTEGER", field=label)
    return value


def _unique_texts(value: object, label: str) -> tuple[str, ...]:
    result = tuple(_text(item, label) for item in _rows(value, label))
    if len(result) != len(set(result)):
        raise OwnershipContractError("DUPLICATE_REFERENCE", field=label)
    return result


def _parse_evidence(rows: object) -> Mapping[str, Evidence]:
    result: dict[str, Evidence] = {}
    for index, value in enumerate(_rows(rows, "evidence")):
        row = _mapping(value, f"evidence[{index}]")
        evidence_id = _text(row.get("evidenceId"), f"evidence[{index}].evidenceId")
        if evidence_id in result:
            raise OwnershipContractError("DUPLICATE_EVIDENCE", evidence_id=evidence_id)
        source_type = _text(row.get("sourceType"), f"evidence[{index}].sourceType")
        if source_type not in EVIDENCE_SOURCE_TYPES:
            raise OwnershipContractError("UNKNOWN_EVIDENCE_SOURCE_TYPE", evidence_id=evidence_id, source_type=source_type)
        result[evidence_id] = Evidence(
            evidence_id=evidence_id,
            source_type=source_type,
            work=_text(row.get("work"), f"evidence[{index}].work"),
            section=_text(row.get("section"), f"evidence[{index}].section"),
            locator=_text(row.get("locator"), f"evidence[{index}].locator"),
            excerpt=_text(row.get("excerpt"), f"evidence[{index}].excerpt"),
            url=row.get("url") if isinstance(row.get("url"), str) else None,
        )
    return MappingProxyType(result)


def _resolve_nations(
    rows: object,
    scenario_code: int,
    scenario_catalog: Mapping[int, Any],
) -> Mapping[str, int]:
    scenario = _mapping(scenario_catalog.get(scenario_code), f"scenarioCatalog[{scenario_code}]")
    source_nations = [_mapping(value, f"scenarioCatalog[{scenario_code}].nations")
                      for value in _rows(scenario.get("nations"), f"scenarioCatalog[{scenario_code}].nations")]
    result: dict[str, int] = {}
    for index, value in enumerate(_rows(rows, f"scenario[{scenario_code}].nationRefs")):
        row = _mapping(value, f"scenario[{scenario_code}].nationRefs[{index}]")
        nation_key = _text(row.get("nationKey"), f"scenario[{scenario_code}].nationKey")
        if nation_key in result:
            raise OwnershipContractError("DUPLICATE_NATION_KEY", scenario_code=scenario_code, nation_key=nation_key)
        nation_name = _text(row.get("scenarioNationName"), f"scenario[{scenario_code}].scenarioNationName")
        matches = [nation for nation in source_nations if nation.get("name") == nation_name]
        if not matches:
            raise OwnershipContractError("UNKNOWN_SCENARIO_NATION", scenario_code=scenario_code, nation_name=nation_name)
        if len(matches) != 1:
            raise OwnershipContractError("AMBIGUOUS_SCENARIO_NATION", scenario_code=scenario_code, nation_name=nation_name)
        result[nation_key] = _integer(matches[0].get("id"), f"scenarioCatalog[{scenario_code}].nation.id")
    return MappingProxyType(result)


def _parse_claims(
    rows: object,
    scenario_code: int,
    effective_year: int,
    placement_basis: str,
    nation_ids: Mapping[str, int],
    evidence: Mapping[str, Evidence],
    province_ids: frozenset[str],
    parent_region_ids: frozenset[str],
) -> tuple[TerritoryClaim, ...]:
    raw_rows = [_mapping(value, f"scenario[{scenario_code}].claims")
                for value in _rows(rows, f"scenario[{scenario_code}].claims")]
    claim_ids = [_text(row.get("claimId"), f"scenario[{scenario_code}].claimId") for row in raw_rows]
    if len(claim_ids) != len(set(claim_ids)):
        raise OwnershipContractError("DUPLICATE_CLAIM", scenario_code=scenario_code)
    baseline_count = sum(row.get("claimKind") == "SCENARIO_BASELINE_UNOWNED" for row in raw_rows)
    if baseline_count == 0:
        raise OwnershipContractError("MISSING_UNOWNED_BASELINE", scenario_code=scenario_code)
    if baseline_count != 1:
        raise OwnershipContractError("DUPLICATE_UNOWNED_BASELINE", scenario_code=scenario_code)
    known_claim_ids = frozenset(claim_ids)
    result: list[TerritoryClaim] = []
    for row, claim_id in zip(raw_rows, claim_ids, strict=True):
        claim_kind = _text(row.get("claimKind"), f"claim[{claim_id}].claimKind")
        if claim_kind not in CLAIM_KINDS:
            raise OwnershipContractError("UNKNOWN_CLAIM_KIND", claim_id=claim_id, claim_kind=claim_kind)
        if claim_kind == "IF_SCENARIO" and placement_basis != "IF_SCENARIO":
            raise OwnershipContractError("IF_CLAIM_IN_HISTORICAL_SCENARIO", claim_id=claim_id)
        inherits_claim_id = None
        effective_from = None
        effective_to = None
        if claim_kind == "TEMPORAL_CARRY":
            inherits_claim_id = _text(
                row.get("inheritsClaimId"),
                f"claim[{claim_id}].inheritsClaimId",
            )
            if inherits_claim_id not in known_claim_ids or inherits_claim_id == claim_id:
                raise OwnershipContractError(
                    "INVALID_TEMPORAL_CARRY",
                    claim_id=claim_id,
                    inherits_claim_id=inherits_claim_id,
                )
            effective_from = _integer(
                row.get("effectiveFrom"),
                f"claim[{claim_id}].effectiveFrom",
            )
            effective_to = _integer(
                row.get("effectiveTo"),
                f"claim[{claim_id}].effectiveTo",
            )
            if effective_from > effective_to:
                raise OwnershipContractError(
                    "INVALID_EFFECTIVE_INTERVAL",
                    claim_id=claim_id,
                    effective_from=effective_from,
                    effective_to=effective_to,
                )
        raw_owner = row.get("ownerNationKey")
        owner_nation_key = None if raw_owner is None else _text(raw_owner, f"claim[{claim_id}].ownerNationKey")
        if claim_kind in {"SCENARIO_BASELINE_UNOWNED", "UNOWNED_EXPLICIT"}:
            if owner_nation_key is not None:
                raise OwnershipContractError("UNOWNED_CLAIM_HAS_OWNER", claim_id=claim_id)
        elif owner_nation_key not in nation_ids:
            raise OwnershipContractError("UNKNOWN_NATION_KEY", claim_id=claim_id, nation_key=owner_nation_key)

        target = _mapping(row.get("target"), f"claim[{claim_id}].target")
        province_refs = _unique_texts(target.get("provinceIds", []), f"claim[{claim_id}].provinceIds")
        parent_refs = _unique_texts(target.get("parentRegionIds", []), f"claim[{claim_id}].parentRegionIds")
        all_provinces = target.get("allProvinces") is True
        unknown_provinces = sorted(set(province_refs) - province_ids)
        if unknown_provinces:
            raise OwnershipContractError("UNKNOWN_PROVINCE", claim_id=claim_id, province_id=unknown_provinces[0])
        unknown_parents = sorted(set(parent_refs) - parent_region_ids)
        if unknown_parents:
            raise OwnershipContractError("UNKNOWN_PARENT_REGION", claim_id=claim_id, parent_region_id=unknown_parents[0])
        if sum((bool(province_refs), bool(parent_refs), all_provinces)) != 1:
            raise OwnershipContractError("INVALID_CLAIM_TARGET", claim_id=claim_id)
        if claim_kind == "SCENARIO_BASELINE_UNOWNED" and not all_provinces:
            raise OwnershipContractError("INVALID_BASELINE_TARGET", claim_id=claim_id)

        evidence_refs = _unique_texts(row.get("evidenceIds"), f"claim[{claim_id}].evidenceIds")
        if not evidence_refs:
            raise OwnershipContractError("CLAIM_WITHOUT_EVIDENCE", claim_id=claim_id)
        unknown_evidence = sorted(set(evidence_refs) - evidence.keys())
        if unknown_evidence:
            raise OwnershipContractError("UNKNOWN_EVIDENCE", claim_id=claim_id, evidence_id=unknown_evidence[0])
        overrides = _unique_texts(row.get("overridesClaimIds", []), f"claim[{claim_id}].overridesClaimIds")
        unknown_overrides = sorted(set(overrides) - known_claim_ids)
        if unknown_overrides:
            raise OwnershipContractError("INVALID_OVERRIDE_EDGE", claim_id=claim_id, override=unknown_overrides[0])
        if claim_id in overrides:
            raise OwnershipContractError("INVALID_OVERRIDE_EDGE", claim_id=claim_id, override=claim_id)
        result.append(TerritoryClaim(
            claim_id=claim_id,
            claim_kind=claim_kind,
            owner_nation_key=owner_nation_key,
            province_ids=province_refs,
            parent_region_ids=parent_refs,
            all_provinces=all_provinces,
            evidence_ids=evidence_refs,
            overrides_claim_ids=overrides,
            rationale=_text(row.get("rationale"), f"claim[{claim_id}].rationale"),
            inherits_claim_id=inherits_claim_id,
            effective_from=effective_from,
            effective_to=effective_to,
        ))

    claims = tuple(result)
    by_id = {claim.claim_id: claim for claim in claims}

    def provenance_fingerprint(claim: TerritoryClaim) -> tuple[object, ...]:
        return (
            claim.owner_nation_key,
            claim.province_ids,
            claim.parent_region_ids,
            claim.all_provinces,
            claim.evidence_ids,
        )

    for claim in claims:
        if claim.claim_kind != "TEMPORAL_CARRY":
            continue
        if not claim.effective_from <= effective_year <= claim.effective_to:
            raise OwnershipContractError(
                "INVALID_TEMPORAL_CARRY",
                claim_id=claim.claim_id,
                effective_year=effective_year,
            )
        inherited = by_id[claim.inherits_claim_id]
        if provenance_fingerprint(claim) != provenance_fingerprint(inherited):
            raise OwnershipContractError(
                "INVALID_TEMPORAL_CARRY",
                claim_id=claim.claim_id,
                inherits_claim_id=inherited.claim_id,
            )

    for claim in claims:
        if claim.claim_kind != "TEMPORAL_CARRY":
            continue
        seen: set[str] = set()
        cursor = claim
        while cursor.claim_kind == "TEMPORAL_CARRY":
            if cursor.claim_id in seen:
                raise OwnershipContractError(
                    "TEMPORAL_CARRY_CYCLE",
                    claim_id=claim.claim_id,
                    cycle_claim_id=cursor.claim_id,
                )
            seen.add(cursor.claim_id)
            cursor = by_id[cursor.inherits_claim_id]

    return claims


def _parse_allowlist(
    rows: object,
    scenario_code: int,
    evidence: Mapping[str, Evidence],
    province_ids: frozenset[str],
) -> tuple[AuditAllowlistEntry, ...]:
    result: list[AuditAllowlistEntry] = []
    for index, value in enumerate(_rows(rows, f"scenario[{scenario_code}].auditAllowlist")):
        row = _mapping(value, f"scenario[{scenario_code}].auditAllowlist[{index}]")
        entry_scenario = _integer(row.get("scenarioCode"), "allowlist.scenarioCode")
        if entry_scenario != scenario_code:
            raise OwnershipContractError("ALLOWLIST_SCENARIO_MISMATCH", scenario_code=scenario_code)
        refs = _unique_texts(row.get("provinceIds"), "allowlist.provinceIds")
        if "*" in refs:
            raise OwnershipContractError("ALLOWLIST_WILDCARD_FORBIDDEN", scenario_code=scenario_code)
        unknown = sorted(set(refs) - province_ids)
        if unknown:
            raise OwnershipContractError("UNKNOWN_PROVINCE", scenario_code=scenario_code, province_id=unknown[0])
        evidence_refs = _unique_texts(row.get("evidenceIds"), "allowlist.evidenceIds")
        unknown_evidence = sorted(set(evidence_refs) - evidence.keys())
        if unknown_evidence:
            raise OwnershipContractError("UNKNOWN_EVIDENCE", scenario_code=scenario_code, evidence_id=unknown_evidence[0])
        if row.get("reviewState") != "APPROVED":
            raise OwnershipContractError("UNAPPROVED_ALLOWLIST", scenario_code=scenario_code)
        result.append(AuditAllowlistEntry(
            audit_kind=_text(row.get("auditKind"), "allowlist.auditKind"),
            province_ids=refs,
            evidence_ids=evidence_refs,
            rationale=_text(row.get("rationale"), "allowlist.rationale"),
        ))
    return tuple(result)


def parse_ownership_document(
    raw: Mapping[str, Any],
    catalog: Mapping[str, Any],
    scenario_catalog: Mapping[int, Any],
) -> OwnershipDocument:
    """Validate and freeze one ownership document without materializing claims."""
    schema_version = _integer(raw.get("schemaVersion"), "schemaVersion")
    if schema_version != 1:
        raise OwnershipContractError("UNSUPPORTED_SCHEMA_VERSION", schema_version=schema_version)
    active_codes = tuple(_integer(value, "activeScenarioCodes")
                         for value in _rows(raw.get("activeScenarioCodes"), "activeScenarioCodes"))
    if len(active_codes) != len(set(active_codes)):
        raise OwnershipContractError("DUPLICATE_ACTIVE_SCENARIO")
    if set(active_codes) != set(scenario_catalog):
        raise OwnershipContractError("ACTIVE_SCENARIO_SET_MISMATCH")

    provinces = frozenset(_unique_texts(catalog.get("provinceIds"), "catalog.provinceIds"))
    parents = frozenset(_unique_texts(catalog.get("parentRegionIds"), "catalog.parentRegionIds"))
    evidence = _parse_evidence(raw.get("evidence"))
    raw_scenarios = [_mapping(value, "scenarios") for value in _rows(raw.get("scenarios"), "scenarios")]
    scenario_codes = [_integer(row.get("scenarioCode"), "scenario.scenarioCode") for row in raw_scenarios]
    if len(scenario_codes) != len(set(scenario_codes)):
        raise OwnershipContractError("DUPLICATE_SCENARIO")
    if set(scenario_codes) != set(active_codes):
        raise OwnershipContractError("ACTIVE_SCENARIO_SET_MISMATCH")

    scenarios: dict[int, ScenarioClaims] = {}
    for row, scenario_code in zip(raw_scenarios, scenario_codes, strict=True):
        placement_basis = _text(row.get("placementBasis"), f"scenario[{scenario_code}].placementBasis")
        if placement_basis not in PLACEMENT_BASES:
            raise OwnershipContractError("UNKNOWN_PLACEMENT_BASIS", scenario_code=scenario_code)
        nation_ids = _resolve_nations(row.get("nationRefs"), scenario_code, scenario_catalog)
        effective_year = _integer(row.get("effectiveYear"), f"scenario[{scenario_code}].effectiveYear")
        claims = _parse_claims(
            row.get("claims"), scenario_code, effective_year, placement_basis,
            nation_ids, evidence, provinces, parents,
        )
        scenarios[scenario_code] = ScenarioClaims(
            scenario_code=scenario_code,
            effective_year=effective_year,
            placement_basis=placement_basis,
            nation_ids=nation_ids,
            claims=claims,
            audit_allowlist=_parse_allowlist(
                row.get("auditAllowlist", []), scenario_code, evidence, provinces,
            ),
        )

    return OwnershipDocument(
        schema_version=schema_version,
        map_id=_text(raw.get("mapId"), "mapId"),
        unit_set=_text(raw.get("unitSet"), "unitSet"),
        active_scenario_codes=active_codes,
        evidence=evidence,
        scenarios=MappingProxyType(scenarios),
    )
