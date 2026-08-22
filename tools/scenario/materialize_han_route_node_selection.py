#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# ─── How to run ───
# uv run tools/scenario/materialize_han_route_node_selection.py --check
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.scenario.han_route_node_selection import (
    BuildResult,
    JsonObject,
    JsonValue,
    MaterializationContractError,
    build_outputs,
    obj,
    rows,
    text,
)

CURATED = ROOT / "data/curated/han"
SCENARIOS = ROOT / "infra/src/main/resources/scenario"


@dataclass(frozen=True, slots=True)
class MaterializerInputs:
    candidate: Path
    catalog: Path
    overlay: Path
    review_policy: Path
    adjudications: Path
    source_claims: Path
    key_registry: Path
    han: Path
    tiles: Path
    scenario_dir: Path


def default_inputs() -> MaterializerInputs:
    return MaterializerInputs(
        candidate=CURATED / "route-node-selection-candidates-v1.json",
        catalog=CURATED / "administrative-units.json",
        overlay=ROOT / "data/map/administrative-place-overlay.json",
        review_policy=CURATED / "route-node-review-policy-v1.json",
        adjudications=CURATED / "route-node-location-adjudications-v1.json",
        source_claims=CURATED / "route-node-source-claims-v1.json",
        key_registry=CURATED / "route-node-key-registry-v1.json",
        han=ROOT / "infra/src/main/resources/map/han.json",
        tiles=ROOT / "data/map/han-tiles.json",
        scenario_dir=SCENARIOS,
    )


def serialize(document: JsonObject) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load(path: Path) -> JsonObject:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise MaterializationContractError(f"{path.name} root must be an object")
    return value


def _verify_location_claim_sources(claims: JsonObject) -> None:
    claim_rows = rows(claims, "claims")
    if len(claim_rows) != 8:
        raise MaterializationContractError("location claim set must contain exactly 8 LOCATION_ONLY claims")
    for claim in claim_rows:
        claim_id = text(claim, "sourceClaimId")
        if claim.get("claimRole") != "LOCATION_ONLY":
            raise MaterializationContractError(f"{claim_id} must be LOCATION_ONLY")
        records = rows(claim, "sourceRecords")
        if not records:
            raise MaterializationContractError(f"{claim_id} requires source records")
        for record in records:
            corpus_path = Path(text(record, "corpusPath"))
            if corpus_path.is_absolute() or corpus_path.parts[:2] != ("data", "corpus") or ".." in corpus_path.parts:
                raise MaterializationContractError(f"{claim_id} source record path must be repo-relative data/corpus")
            source = (ROOT / corpus_path).resolve()
            try:
                source.relative_to(ROOT)
            except ValueError as error:
                raise MaterializationContractError(f"{claim_id} source record escapes the repository") from error
            if not source.is_file() or record.get("snapshotSha256") != _digest(source):
                raise MaterializationContractError(f"{claim_id} source record hash does not match")
            start, end = record.get("lineStart"), record.get("lineEnd")
            if not isinstance(start, int) or isinstance(start, bool) or not isinstance(end, int) or isinstance(end, bool):
                raise MaterializationContractError(f"{claim_id} source record line range is malformed")
            lines = source.read_text(encoding="utf-8").splitlines()
            if start <= 0 or end < start or end > len(lines):
                raise MaterializationContractError(f"{claim_id} source record line range is outside the corpus")
            if text(record, "verbatim") not in "\n".join(lines[start - 1:end]):
                raise MaterializationContractError(f"{claim_id} source record verbatim does not match")


def _verify_hash(label: str, expected: JsonValue, path: Path) -> str:
    expected_hash = expected.get("sha256") if isinstance(expected, dict) else expected
    actual = _digest(path)
    if expected_hash != actual:
        raise MaterializationContractError(f"{label} hash does not match approved review policy")
    return actual


def _scenario_resources(candidate: JsonObject, scenario_dir: Path) -> list[JsonObject]:
    expected_rows = rows(candidate, "scenarioCatalog")
    if len(expected_rows) != 31:
        raise MaterializationContractError("candidate must pin exactly 31 scenario resources")
    resources: list[JsonObject] = []
    seen: set[str] = set()
    for expected in expected_rows:
        scenario_id = text(expected, "code")
        resource_path = text(expected, "resourcePath")
        path = scenario_dir / Path(resource_path).name
        if scenario_id in seen or not path.is_file() or _digest(path) != expected.get("resourceSha256"):
            raise MaterializationContractError("scenario hash drift")
        payload = _load(path)
        if obj(payload, "map").get("mapName") != "han" or payload.get("startYear") != expected.get("startYear"):
            raise MaterializationContractError("scenario resource metadata drift")
        seen.add(scenario_id)
        resources.append({"scenarioId": scenario_id, "resourceName": path.name, "resourcePath": resource_path,
                          "startYear": expected["startYear"], "sha256": expected["resourceSha256"]})
    return sorted(resources, key=lambda row: int(text(row, "scenarioId")))


def _verify_policy(inputs: MaterializerInputs, policy: JsonObject, candidate: JsonObject) -> JsonObject:
    approved = obj(policy, "inputs")
    hashes: JsonObject = {
        "candidate": _verify_hash("candidate manifest", obj(approved, "candidateManifest"), inputs.candidate),
        "administrativeCatalog": _verify_hash("administrative catalog", approved.get("administrativeCatalogSha256"), inputs.catalog),
        "administrativePlaceOverlay": _verify_hash("coordinate overlay", approved.get("coordinateOverlaySha256"), inputs.overlay),
        "reviewPolicy": _digest(inputs.review_policy),
        "locationAdjudications": _verify_hash("adjudication", obj(approved, "locationAdjudications"), inputs.adjudications),
        "externalClaims": _verify_hash("location claim", obj(approved, "locationClaims"), inputs.source_claims),
        "routeNodeKeyRegistry": _verify_hash("route-node registry", obj(approved, "routeNodeKeyRegistry"), inputs.key_registry),
    }
    candidate_inputs = obj(obj(candidate, "provenance"), "inputs")
    for label, path in (("administrativeCatalog", inputs.catalog), ("administrativePlaceOverlay", inputs.overlay),
                        ("legacyTileMap", inputs.tiles), ("legacyHanMap", inputs.han)):
        actual = _verify_hash(f"candidate {label}", obj(candidate_inputs, label), path)
        hashes[label] = actual
    return {"generator": "tools/scenario/materialize_han_route_node_selection.py", "inputs": {
        label: {"sha256": value} for label, value in hashes.items()}}


def materialize(inputs: MaterializerInputs) -> BuildResult:
    candidate, catalog, overlay = _load(inputs.candidate), _load(inputs.catalog), _load(inputs.overlay)
    policy, adjudications = _load(inputs.review_policy), _load(inputs.adjudications)
    claims, registry = _load(inputs.source_claims), _load(inputs.key_registry)
    _verify_location_claim_sources(claims)
    provenance = _verify_policy(inputs, policy, candidate)
    result = build_outputs(candidate, catalog, overlay, policy, adjudications, claims, registry,
                           _scenario_resources(candidate, inputs.scenario_dir), provenance)
    selection_hash = hashlib.sha256(serialize(result.selection).encode()).hexdigest()
    migration = dict(result.migration)
    migration["sourceSelectionSha256"] = selection_hash
    migration["sourceCandidateSha256"] = _digest(inputs.candidate)
    return BuildResult(selection=result.selection, migration=migration)


def copy_default_inputs(destination: Path) -> MaterializerInputs:
    source = default_inputs()
    destination.mkdir(parents=True, exist_ok=True)
    scenario_dir = destination / "scenarios"
    scenario_dir.mkdir()
    copied: dict[str, Path] = {}
    for field in ("candidate", "catalog", "overlay", "review_policy", "adjudications",
                  "source_claims", "key_registry", "han", "tiles"):
        source_path = getattr(source, field)
        copied[field] = Path(shutil.copy2(source_path, destination / source_path.name))
    for path in source.scenario_dir.glob("scenario_*.json"):
        shutil.copy2(path, scenario_dir / path.name)
    return MaterializerInputs(**copied, scenario_dir=scenario_dir)


def repin_policy_input(inputs: MaterializerInputs, key: str, path: Path) -> None:
    policy = _load(inputs.review_policy)
    entry = obj(obj(policy, "inputs"), key)
    entry["sha256"] = _digest(path)
    inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")


def _parser() -> argparse.ArgumentParser:
    defaults = default_inputs()
    parser = argparse.ArgumentParser(description="Materialize the approved W0-C RouteNode selection and migration")
    for option, field in (("candidate", "candidate"), ("catalog", "catalog"), ("overlay", "overlay"),
                          ("review-policy", "review_policy"), ("adjudications", "adjudications"),
                          ("source-claims", "source_claims"), ("key-registry", "key_registry"),
                          ("han", "han"), ("tiles", "tiles"), ("scenario-dir", "scenario_dir")):
        parser.add_argument(f"--{option}", type=Path, default=getattr(defaults, field))
    parser.add_argument("--selection-output", type=Path, default=CURATED / "route-node-selection-v1.json")
    parser.add_argument("--migration-output", type=Path, default=CURATED / "route-node-migration-v1.json")
    parser.add_argument("--check", action="store_true")
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    inputs = MaterializerInputs(**{field: getattr(arguments, field) for field in MaterializerInputs.__dataclass_fields__})
    try:
        result = materialize(inputs)
        selection_blob, migration_blob = serialize(result.selection), serialize(result.migration)
        if arguments.check:
            if (not arguments.selection_output.is_file() or not arguments.migration_output.is_file()
                    or arguments.selection_output.read_text(encoding="utf-8") != selection_blob
                    or arguments.migration_output.read_text(encoding="utf-8") != migration_blob):
                print("han route-node selection or migration drift", file=sys.stderr)
                return 1
            print("han route-node selection and migration: no drift")
            return 0
        arguments.selection_output.parent.mkdir(parents=True, exist_ok=True)
        arguments.migration_output.parent.mkdir(parents=True, exist_ok=True)
        arguments.selection_output.write_text(selection_blob, encoding="utf-8")
        arguments.migration_output.write_text(migration_blob, encoding="utf-8")
        summary = obj(result.migration, "summary")
        print(f"approved=780 scenarios=31 replacements={summary['routeNodeReplacementCount']} "
              f"bindingCorrections={summary['historicalBindingCorrectionCount']}")
        return 0
    except (OSError, json.JSONDecodeError, MaterializationContractError) as error:
        print(f"han route-node materialization failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
