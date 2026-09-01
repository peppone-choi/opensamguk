"""Deterministically expand reviewed ownership claims to one row per province."""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping, Sequence

from tools.scenario.province_ownership_contract import (
    OwnershipContractError,
    OwnershipDocument,
    TerritoryClaim,
)


CLAIM_TIER = MappingProxyType({
    "SCENARIO_BASELINE_UNOWNED": 0,
    "ADMIN_REGION_CONTROL": 1,
    "TEMPORAL_CARRY": 1,
    "PROVINCE_DIRECT": 2,
    "UNOWNED_EXPLICIT": 2,
    "IF_SCENARIO": 3,
})
CONFIDENCE = MappingProxyType({
    "SCENARIO_BASELINE_UNOWNED": "EXPLICIT_UNOWNED",
    "ADMIN_REGION_CONTROL": "ADMIN_SCOPE",
    "TEMPORAL_CARRY": "TEMPORAL",
    "PROVINCE_DIRECT": "DIRECT",
    "UNOWNED_EXPLICIT": "EXPLICIT_UNOWNED",
    "IF_SCENARIO": "IF",
})


@dataclass(frozen=True)
class ProvinceCatalogEntry:
    province_id: str
    parent_region_id: str


@dataclass(frozen=True)
class ProvinceAssignment:
    scenario_code: int
    province_id: str
    owner_nation_id: int | None
    owner_nation_key: str | None
    controller_city_id: int | None
    winning_claim_id: str
    claim_trace: tuple[str, ...]
    basis_type: str
    evidence_ids: tuple[str, ...]
    confidence: str
    rationale: str


def _claim_targets(
    claim: TerritoryClaim,
    catalog: Sequence[ProvinceCatalogEntry],
) -> frozenset[str]:
    if claim.all_provinces:
        return frozenset(row.province_id for row in catalog)
    if claim.province_ids:
        return frozenset(claim.province_ids)
    parents = frozenset(claim.parent_region_ids)
    return frozenset(row.province_id for row in catalog if row.parent_region_id in parents)


def _specificity(claim: TerritoryClaim) -> int:
    if claim.province_ids:
        return 2
    if claim.parent_region_ids:
        return 1
    return 0


def _effective_claim_tier(
    claim: TerritoryClaim,
    claims_by_id: Mapping[str, TerritoryClaim],
) -> int:
    """A temporal carry replaces its source while retaining the source precedence."""
    cursor = claim
    while cursor.claim_kind == "TEMPORAL_CARRY":
        cursor = claims_by_id[cursor.inherits_claim_id]
    return CLAIM_TIER[cursor.claim_kind]


def materialize_scenario(
    document: OwnershipDocument,
    scenario_code: int,
    catalog: Sequence[ProvinceCatalogEntry],
) -> tuple[ProvinceAssignment, ...]:
    """Expand one validated scenario without using input array order as precedence."""
    if scenario_code not in document.scenarios:
        raise OwnershipContractError("UNKNOWN_SCENARIO", scenario_code=scenario_code)
    province_ids = [row.province_id for row in catalog]
    if len(province_ids) != len(set(province_ids)):
        raise OwnershipContractError("DUPLICATE_PROVINCE_CATALOG_ID")

    scenario = document.scenarios[scenario_code]
    baselines = [claim for claim in scenario.claims if claim.claim_kind == "SCENARIO_BASELINE_UNOWNED"]
    if len(baselines) != 1:
        raise OwnershipContractError(
            "MISSING_UNOWNED_BASELINE" if not baselines else "DUPLICATE_UNOWNED_BASELINE",
            scenario_code=scenario_code,
        )
    baseline = baselines[0]
    if not baseline.all_provinces:
        raise OwnershipContractError("INVALID_BASELINE_TARGET", claim_id=baseline.claim_id)

    claims_by_tier: dict[int, list[TerritoryClaim]] = {}
    targets: dict[str, frozenset[str]] = {}
    claims_by_id = {claim.claim_id: claim for claim in scenario.claims}
    inherited_claim_ids = {
        claim.inherits_claim_id
        for claim in scenario.claims
        if claim.claim_kind == "TEMPORAL_CARRY"
    }
    for claim in scenario.claims:
        targets[claim.claim_id] = _claim_targets(claim, catalog)
        if claim.claim_id in inherited_claim_ids:
            continue
        tier = _effective_claim_tier(claim, claims_by_id)
        claims_by_tier.setdefault(tier, []).append(claim)

    assignments: list[ProvinceAssignment] = []
    for province_id in province_ids:
        winner = baseline
        trace: list[str] = [baseline.claim_id]
        for tier in sorted(value for value in claims_by_tier if value > 0):
            candidates = [
                claim for claim in claims_by_tier[tier]
                if province_id in targets[claim.claim_id]
            ]
            if not candidates:
                continue
            ordered = sorted(candidates, key=lambda claim: (_specificity(claim), claim.claim_id))
            if winner is baseline:
                trace = []
            for specificity in sorted({_specificity(claim) for claim in ordered}):
                group = [claim for claim in ordered if _specificity(claim) == specificity]
                owners = {claim.owner_nation_key for claim in group}
                if len(owners) != 1:
                    raise OwnershipContractError(
                        "CLAIM_CONFLICT",
                        scenario_code=scenario_code,
                        province_id=province_id,
                        claim_ids=sorted(claim.claim_id for claim in group),
                    )
                remaining = {claim.claim_id: claim for claim in group}
                while remaining:
                    eligible = [
                        claim for claim in remaining.values()
                        if winner.claim_id in claim.overrides_claim_ids
                    ]
                    if not eligible:
                        claim = min(remaining.values(), key=lambda row: row.claim_id)
                        raise OwnershipContractError(
                            "MISSING_OVERRIDE_EDGE",
                            scenario_code=scenario_code,
                            province_id=province_id,
                            claim_id=claim.claim_id,
                            overridden_claim_id=winner.claim_id,
                        )
                    if len(eligible) != 1:
                        raise OwnershipContractError(
                            "AMBIGUOUS_OVERRIDE_CHAIN",
                            scenario_code=scenario_code,
                            province_id=province_id,
                            overridden_claim_id=winner.claim_id,
                            claim_ids=sorted(claim.claim_id for claim in eligible),
                        )
                    winner = eligible[0]
                    trace.append(winner.claim_id)
                    del remaining[winner.claim_id]

        owner_key = winner.owner_nation_key
        assignments.append(ProvinceAssignment(
            scenario_code=scenario_code,
            province_id=province_id,
            owner_nation_id=None if owner_key is None else scenario.nation_ids[owner_key],
            owner_nation_key=owner_key,
            controller_city_id=None,
            winning_claim_id=winner.claim_id,
            claim_trace=tuple(trace),
            basis_type=winner.claim_kind,
            evidence_ids=winner.evidence_ids,
            confidence=CONFIDENCE[winner.claim_kind],
            rationale=winner.rationale,
        ))
    return tuple(assignments)


def materialize_all(
    document: OwnershipDocument,
    catalog: Sequence[ProvinceCatalogEntry],
) -> Mapping[int, tuple[ProvinceAssignment, ...]]:
    return MappingProxyType({
        code: materialize_scenario(document, code, catalog)
        for code in sorted(document.active_scenario_codes)
    })
