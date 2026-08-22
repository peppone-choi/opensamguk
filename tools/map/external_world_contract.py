from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Final, TypeAlias

from tools.map.route_network_contract import (
    ContractError,
    JsonObject,
    JsonValue,
    require_array,
    require_object,
    require_text,
    serialize,
)

GeneratedDocuments: TypeAlias = dict[str, JsonObject]
PACK_IDS: Final = ("east-sea-wa", "northeast", "western-regions", "northern-steppe", "southern-maritime")
RUNTIME_BOUNDARY: Final = "NOT_CLAIMED_BY_W1_B_DATA_CONTRACT"
EXTERNAL_CANDIDATE_SHA: Final = "dd7359dee0060c941e563aaa3ed6ecf19086e700343518f940deae6d9bbd63fb"
ROUTE_CONTRACT_SHA: Final = "fcc6a11031137ca97b73f17df464d517491a19853e7100ac8a472bcfecab6151"
EVIDENCE_CLASSES: Final = ("PRIMARY_ATTESTED", "SCHOLARLY_RECONSTRUCTION", "ROMANCE_ATTESTED", "GAME_REFERENCE", "BALANCE_ONLY")
SOURCE_PROXIMITIES: Final = ("CONTEMPORARY", "OFFICIAL_HISTORY", "EARLY_ANNOTATION", "LATER_TRADITION", "MODERN_STUDY", "FICTION", "GAME")
COMPATIBILITY: Final = {
    "PRIMARY_ATTESTED": frozenset({"CONTEMPORARY", "OFFICIAL_HISTORY"}),
    "SCHOLARLY_RECONSTRUCTION": frozenset({"CONTEMPORARY", "OFFICIAL_HISTORY", "EARLY_ANNOTATION", "LATER_TRADITION", "MODERN_STUDY"}),
    "ROMANCE_ATTESTED": frozenset({"FICTION"}),
    "GAME_REFERENCE": frozenset({"GAME"}),
    "BALANCE_ONLY": frozenset(),
}
SUPERSEDE_IDS: Final = tuple([f"X{i:03d}" for i in range(4)] + [f"X{i:03d}" for i in range(5, 26)] + ["X027", "X060"])
PACK_LEGACY_IDS: Final = {
    "east-sea-wa": tuple([f"X{i:03d}" for i in range(31, 36)] + [f"X{i:03d}" for i in range(40, 56)]),
    "northeast": ("X004", "X028", "X029", "X030", "X036", "X037", "X038", "X039"),
    "western-regions": ("X058", "X059"),
    "northern-steppe": ("X062", "X063", "X064"),
    "southern-maritime": ("X056", "X057", "X061"),
}
SOURCE_SPECS: Final = {
    "east-sea-wa": (("sgz-30-han-wa-context", "data/corpus/sgz-30.txt", 70, 82), ("sgz-30-wa-itinerary", "data/corpus/sgz-30.txt", 88, 101)),
    "northeast": (("sgz-30-daifang", "data/corpus/sgz-30.txt", 74, 74), ("sgz-30-northeast", "data/corpus/sgz-30.txt", 37, 65)),
    "western-regions": (("hhs-88-western-gates", "data/corpus/hhs-088.txt", 26, 26), ("hhs-88-western-polities", "data/corpus/hhs-088.txt", 28, 108), ("hhs-87-western-qiang", "data/corpus/hhs-087.txt", 10, 10), ("yuanhe-39-baima-di", "data/corpus/yhjx-39.txt", 78, 78)),
    "northern-steppe": (("hhs-90-xianbei", "data/corpus/hhs-090.txt", 39, 61), ("hhs-89-southern-xiongnu", "data/corpus/hhs-089.txt", 9, 26)),
    "southern-maritime": (("sgz-55-shanyue", "data/corpus/sgz-55.txt", 23, 23), ("suishu-82-linyi", "data/corpus/sui-082.txt", 9, 9), ("sgz-47-yizhou", "data/corpus/sgz-47.txt", 85, 85), ("sgz-47-funan", "data/corpus/sgz-47.txt", 119, 119), ("suishu-81-liuqiu", "data/corpus/sui-081.txt", 67, 69)),
}


@dataclass(frozen=True, slots=True)
class ExternalWorldContext:
    root: Path
    candidates: JsonObject
    route_contract: JsonObject
    scenarios: tuple[JsonObject, ...]
    active_scenario_ids: tuple[str, ...]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> JsonObject:
    value: JsonValue = json.loads(path.read_text(encoding="utf-8"))
    return require_object(value, path.name)


def load_context(root: Path) -> ExternalWorldContext:
    candidates_path = root / "data/curated/han/external-world-candidates-v1.json"
    contract_path = root / "data/curated/han/route-network-contract-v1.json"
    if sha256(candidates_path) != EXTERNAL_CANDIDATE_SHA or sha256(contract_path) != ROUTE_CONTRACT_SHA:
        raise ContractError("W1-A external candidate or route contract SHA drift")
    candidates = load_json(candidates_path)
    contract = load_json(contract_path)
    han = require_object(contract.get("hanMapContractScenarios"), "Han scenarios")
    active = require_object(contract.get("activeProductScenarios"), "active scenarios")
    scenarios = tuple(require_object(row, "scenario") for row in require_array(han, "resources"))
    ids = tuple(str(value) for value in require_array(active, "scenarioIds"))
    if len(scenarios) != 31 or len(ids) != 15:
        raise ContractError("W1-A scenario contract count drift")
    for scenario in scenarios:
        relative = require_text(scenario, "resourcePath")
        path = (root / relative).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            raise ContractError(f"missing or escaping scenario resource: {relative}")
        resource = load_json(path)
        if scenario.get("sha256") != sha256(path) or scenario.get("resourceName") != path.name or scenario.get("startYear") != resource.get("startYear"):
            raise ContractError(f"scenario resource source drift: {relative}")
    active_source = require_object(active.get("source"), "active scenario source")
    active_path = (root / require_text(active_source, "path")).resolve()
    if not active_path.is_relative_to(root.resolve()) or active_source.get("sha256") != sha256(active_path):
        raise ContractError("active product scenario source drift")
    return ExternalWorldContext(root.resolve(), candidates, contract, scenarios, ids)


def source_registry(context: ExternalWorldContext, pack_id: str) -> list[JsonObject]:
    rows: list[JsonObject] = []
    for source_ref, relative, start, end in SOURCE_SPECS[pack_id]:
        path = (context.root / relative).resolve()
        if not path.is_relative_to(context.root) or not path.is_file():
            raise ContractError(f"missing or escaping corpus source: {relative}")
        lines = path.read_text(encoding="utf-8").splitlines()
        if start < 1 or end < start or end > len(lines):
            raise ContractError(f"invalid corpus line range: {source_ref}")
        rows.append({"sourceRef": source_ref, "path": relative, "sha256": sha256(path), "lineStart": start, "lineEnd": end, "verbatim": "\n".join(lines[start - 1 : end]), "sourceProximity": "OFFICIAL_HISTORY"})
    return rows


def write_documents(output_dir: Path, documents: GeneratedDocuments) -> None:
    pack_dir = output_dir / "external-world-packs"
    pack_dir.mkdir(parents=True, exist_ok=True)
    for name, document in documents.items():
        path = pack_dir / name if name.startswith(tuple(PACK_IDS)) else output_dir / name
        path.write_text(serialize(document), encoding="utf-8")
