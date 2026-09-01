#!/usr/bin/env python3
"""Protected local executor for an already-approved Han tile build contract.

The public contract intentionally contains no paths or coordinates.  This module is
the protected boundary that binds its fixed roles to repository-relative files,
checks their exact bytes before execution, and emits only coordinate-free manifests.
It never approves or invents a contract or dependency version.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Mapping

try:
    from tools.map import han_tiles_contract
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    import han_tiles_contract


INPUT_RELATIVE_PATHS = {
    "CHGIS_PREF_DBF": "data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf",
    "CHGIS_COUNTY_DBF": "data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf",
    "CTEXT_JUNGUOZHI_YI": "data/chgis-source/junguozhi/yi.html",
    "CTEXT_JUNGUOZHI_ER": "data/chgis-source/junguozhi/er.html",
    "CTEXT_JUNGUOZHI_SAN": "data/chgis-source/junguozhi/san.html",
    "CTEXT_JUNGUOZHI_SI": "data/chgis-source/junguozhi/si.html",
    "CTEXT_JUNGUOZHI_WU": "data/chgis-source/junguozhi/wu.html",
    "NE_LAND_50M": "data/natural-earth/ne_50m_land.geojson",
    "NE_LAKES_50M": "data/natural-earth/ne_50m_lakes.geojson",
    "NE_RIVERS_50M": "data/natural-earth/ne_50m_rivers_lake_centerlines.geojson",
    "NE_REGIONS_10M": "data/natural-earth/ne_10m_geography_regions_polys.geojson",
    "MODERN_ADMIN_ADM2": "data/modern-admin/geoBoundaries-CGAZ-ADM2.geojson",
    "ADMINISTRATIVE_UNITS": "data/curated/han/administrative-units.json",
    "ADMINISTRATIVE_BINDINGS": "data/curated/han/administrative-place-bindings-v1.json",
    "ADMINISTRATIVE_HISTORY": "data/map/han-administrative-history.json",
    "DUPLICATE_ADJUDICATIONS": "data/curated/han/han-place-duplicate-adjudications-v1.json",
    "STABLE_ID_ADJUDICATIONS": "data/curated/han/han-place-stable-id-adjudications-v1.json",
    "MERGE_ADJUDICATIONS": "data/curated/han/han-place-merge-adjudications-v1.json",
    "TEMPORAL_ADJUDICATIONS": "data/curated/han/administrative-temporal-adjudications-v1.json",
    "EXTERNAL_PLACES": "data/map/external-places.json",
    "EXTERNAL_PROVINCE_SEEDS": "data/curated/han/external-province-seeds-v1.json",
    "EXTERNAL_ADMINISTRATIVE_SYSTEMS": "data/curated/han/external-administrative-systems-v1.json",
    "PROVINCE_SHAPE_EXCEPTIONS": "data/curated/map/province-shape-exceptions-v1.json",
    "NON_PLAYABLE_REGIONS": "data/curated/map/non-playable-regions-v1.json",
    "MODERN_ADMIN_RECIPE": "data/curated/map/modern-admin-boundaries-v1.json",
}
GENERATOR_RELATIVE_PATHS = {
    "BUILD_HAN_PLACES": "tools/map/build_han_places.py",
    "BUILD_JUNGUOZHI": "tools/map/build_junguozhi.py",
    "BUILD_TERRAIN_GRID": "tools/map/build_terrain_grid.py",
    "BUILD_READINGS": "tools/map/build_readings.py",
    "BUILD_HAN_TILES": "tools/map/build_tile_grid.py",
}
HELPER_RELATIVE_PATHS = {
    "HAN_PLACE_STABLE_ID_LOADER": "tools/map/han_place_stable_id_adjudications.py",
    "HAN_PLACE_MERGE_ADJUDICATIONS": "tools/map/han_place_merge_adjudications.py",
    "HAN_PLACE_MERGE_RUNTIME": "tools/map/han_place_merge_runtime.py",
    "HAN_TEMPORAL_PARENT_RUNTIME": "tools/map/han_temporal_parent_runtime.py",
    "HAN_PARENT_RECONCILIATION_HELPER": "tools/map/build_han_parent_reconciliation.py",
    "HAN_PROVINCE_MODEL": "tools/map/han_province_model.py",
    "WORLD_PROVINCE_GEOMETRY": "tools/map/world_province_geometry.py",
    "PROVINCE_QUALITY": "tools/map/province_quality.py",
    "NON_PLAYABLE_REGIONS_HELPER": "tools/map/non_playable_regions.py",
    "EXTERNAL_PROVINCE_SYSTEMS": "tools/map/external_province_systems.py",
    "HAN_TILES_CONTRACT_HELPER": "tools/map/han_tiles_contract.py",
}
VERIFIER_RELATIVE_PATHS = {
    "HAN_TILES_CONTRACT_VALIDATOR": "tools/map/han_tiles_contract.py",
    "HAN_TILES_ORCHESTRATOR": "tools/map/han_tiles_protected_orchestrator.py",
}
DEPENDENCY_LOCK_RELATIVE_PATH = "data/curated/han/han-tiles-python-lock-v1.json"
OUTPUT_RELATIVE_PATHS = {
    "HAN_PLACES": "data/map/han-places.json",
    "JUNGUOZHI": "data/map/junguozhi.json",
    "TERRAIN_GRID": "data/map/terrain-grid.json",
    "READINGS": "data/map/readings.json",
    "HAN_TILES": "data/map/han-tiles.json",
}
STAGE_COMMANDS = (
    ("HAN_PLACES", "BUILD_HAN_PLACES"),
    ("JUNGUOZHI", "BUILD_JUNGUOZHI"),
    ("TERRAIN_GRID", "BUILD_TERRAIN_GRID"),
    ("READINGS", "BUILD_READINGS"),
    ("HAN_TILES", "BUILD_HAN_TILES"),
)


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _digest(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest()


def _exact_keys(value: Any, expected: set[str], where: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        actual = set(value) if isinstance(value, dict) else set()
        raise ValueError(
            f"{where}: exact keys required; missing={sorted(expected - actual)}, "
            f"unknown={sorted(actual - expected)}"
        )
    return value


def validate_dependency_lock_bytes(
    document: bytes,
    contract: dict[str, Any],
    installed_versions: Mapping[str, str],
    runtime_platform: Mapping[str, str] | None = None,
) -> bool:
    """Validate an approved lock against the contract and installed distributions."""
    lock = han_tiles_contract.loads_json_strict(document)
    lock = _exact_keys(
        lock,
        {
            "schemaVersion", "lockId", "lockType", "installPolicy",
            "pythonImplementation", "pythonVersion", "platform",
            "reviewedException", "distributions",
        },
        "dependency lock",
    )
    if type(lock["schemaVersion"]) is not int or lock["schemaVersion"] != 1:
        raise ValueError("dependency lock schemaVersion must be 1")
    if lock["lockId"] != "han-tiles-python-lock-v1":
        raise ValueError("dependency lockId mismatch")
    if lock["lockType"] != "REVIEWED_PLATFORM_WHEELHOUSE":
        raise ValueError("dependency lock must not claim to be a general resolver lock")
    if lock["installPolicy"] != "NO_INDEX_ONLY_BINARY_NO_DEPS":
        raise ValueError("dependency lock install policy mismatch")
    runtime = contract["recipe"]["runtime"]
    if (lock["pythonImplementation"], lock["pythonVersion"]) != (
        runtime["pythonImplementation"], runtime["pythonVersion"]
    ):
        raise ValueError("dependency lock runtime mismatch")
    version_parts = runtime["pythonVersion"].split(".")
    expected_tag = f"cp{version_parts[0]}{version_parts[1]}"
    platform_row = _exact_keys(
        lock["platform"], {"system", "machine", "pythonTag"},
        "dependency lock platform",
    )
    expected_platform = dict(runtime_platform) if runtime_platform is not None else {
        "system": platform.system(), "machine": platform.machine(), "pythonTag": expected_tag,
    }
    if platform_row != expected_platform:
        raise ValueError("dependency lock platform mismatch")
    exception = _exact_keys(
        lock["reviewedException"], {
            "dependencyRole", "requiredByRole", "declaredVersion",
            "selectedVersion", "reason",
        }, "dependency lock reviewedException",
    )
    expected_exception = {
        "dependencyRole": "PYYAML", "requiredByRole": "HANJA",
        "declaredVersion": "6.0.1", "selectedVersion": "6.0.3",
        "reason": (
            "hanja METADATA pins PyYAML==6.0.1 + unmarked test deps; "
            "6.0.1 has no cp314 wheel; reviewed no-deps runtime closure is exactly 4"
        ),
    }
    if exception != expected_exception:
        raise ValueError("dependency lock reviewed exception mismatch")
    dependencies = contract["recipe"]["dependencies"]
    distributions = _exact_keys(
        lock["distributions"], set(dependencies), "dependency lock distributions"
    )
    for role, dependency in dependencies.items():
        row = _exact_keys(
            distributions[role], {
                "distributionName", "version", "artifactFilename",
                "artifactSha256", "artifactBytes",
            },
            f"dependency lock distributions.{role}",
        )
        if row["distributionName"] != dependency["distributionName"]:
            raise ValueError(f"dependency lock distribution mismatch for {role}")
        version = row["version"]
        if not isinstance(version, str) or not version:
            raise ValueError(f"dependency lock version missing for {role}")
        filename = row["artifactFilename"]
        if (not isinstance(filename, str) or not filename
                or Path(filename).name != filename or not filename.endswith(".whl")):
            raise ValueError(f"dependency lock artifact filename invalid for {role}")
        wheel_parts = filename[:-4].split("-")
        if len(wheel_parts) != 5:
            raise ValueError(f"dependency lock wheel filename invalid for {role}")
        wheel_name, wheel_version, python_tag, abi_tag, platform_tag = wheel_parts
        normalize = lambda value: re.sub(r"[-_.]+", "-", value).lower()
        if (normalize(wheel_name) != normalize(row["distributionName"])
                or wheel_version != version):
            raise ValueError(f"dependency lock wheel identity mismatch for {role}")
        compatible = (
            role == "HANJA" and python_tag == "py3"
            and abi_tag == "none" and platform_tag == "any"
        ) or (
            role != "HANJA" and python_tag == expected_tag and abi_tag == expected_tag
            and platform_tag.startswith("macosx_") and platform_tag.endswith("_x86_64")
        )
        if not compatible:
            raise ValueError(f"dependency lock wheel platform mismatch for {role}")
        artifact_sha = row["artifactSha256"]
        if (not isinstance(artifact_sha, str) or len(artifact_sha) != 64
                or any(ch not in "0123456789abcdef" for ch in artifact_sha)
                or artifact_sha == "0" * 64):
            raise ValueError(f"dependency lock artifact hash invalid for {role}")
        if (type(row["artifactBytes"]) is not int or row["artifactBytes"] < 1):
            raise ValueError(f"dependency lock artifact bytes invalid for {role}")
        if installed_versions.get(row["distributionName"]) != version:
            raise ValueError(f"installed dependency version mismatch for {role}")
    if distributions["PYYAML"]["version"] != exception["selectedVersion"]:
        raise ValueError("reviewed PyYAML exception does not match locked version")
    return True


def validate_wheelhouse(lock_document: bytes, wheelhouse_root: Path | str) -> bool:
    """Require the explicit local wheelhouse to contain each exact locked artifact."""
    lock = han_tiles_contract.loads_json_strict(lock_document)
    root = Path(wheelhouse_root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError("wheelhouse root must be a real directory")
    expected_names = set()
    for role, row in lock["distributions"].items():
        filename = row["artifactFilename"]
        expected_names.add(filename)
        path = root / filename
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"locked wheel artifact is missing for {role}")
        blob = path.read_bytes()
        if len(blob) != row["artifactBytes"] or _digest(blob) != row["artifactSha256"]:
            raise ValueError(f"locked wheel artifact hash/bytes drift for {role}")
    actual_names = {path.name for path in root.iterdir() if path.is_file()}
    if actual_names != expected_names:
        raise ValueError("wheelhouse must contain exactly the reviewed artifacts")
    return True


def _record_matches(path: Path, record: dict[str, Any], role: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"approved role {role} is missing or not a regular file")
    blob = path.read_bytes()
    if len(blob) != record["bytes"] or _digest(blob) != record["sha256"]:
        raise ValueError(f"approved role {role} hash/bytes drift")


def validate_approved_source_files(
    source_root: Path | str, contract: dict[str, Any]
) -> tuple[Path, ...]:
    """Validate and return every fixed source/code file authorized by the contract."""
    han_tiles_contract.validate_contract(contract)
    unresolved_root = Path(source_root)
    if unresolved_root.is_symlink():
        raise ValueError("source root symlink is forbidden")
    root = unresolved_root.resolve()
    if not root.is_dir():
        raise ValueError("source root is missing")
    approved: dict[Path, None] = {}
    sections = (
        (INPUT_RELATIVE_PATHS, contract["recipe"]["inputs"]),
        (GENERATOR_RELATIVE_PATHS, contract["recipe"]["generators"]),
        (HELPER_RELATIVE_PATHS, contract["recipe"]["helpers"]),
        (VERIFIER_RELATIVE_PATHS, contract["recipe"]["verifiers"]),
    )
    for paths, records in sections:
        if set(paths) != set(records):
            raise ValueError("approved source role closure mismatch")
        for role, relative in paths.items():
            path = root / relative
            _record_matches(path, records[role], role)
            approved[path] = None
    lock_path = root / DEPENDENCY_LOCK_RELATIVE_PATH
    _record_matches(lock_path, contract["recipe"]["runtime"]["dependencyLock"], "HAN_TILES_PYTHON_LOCK")
    approved[lock_path] = None
    return tuple(sorted(approved))


def _probe_interpreter(
    python_executable: Path, contract: dict[str, Any], *, include_distributions: bool = True,
) -> dict[str, Any]:
    names = (
        [row["distributionName"] for row in contract["recipe"]["dependencies"].values()]
        if include_distributions else []
    )
    code = (
        "import json,platform,sys; from importlib import metadata; "
        "print(json.dumps({'implementation':platform.python_implementation(),"
        "'version':platform.python_version(),'system':platform.system(),"
        "'machine':platform.machine(),'pythonTag':f'cp{sys.version_info.major}{sys.version_info.minor}',"
        "'distributions':{name:metadata.version(name) for name in sys.argv[1:]}}))"
    )
    result = subprocess.run(
        [str(python_executable), "-c", code, *names], capture_output=True,
        text=True, check=False,
    )
    if result.returncode != 0:
        raise ValueError("explicit interpreter cannot resolve the reviewed runtime closure")
    try:
        probe = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ValueError("explicit interpreter returned an invalid runtime probe") from error
    return probe


def prevalidate_runtime_and_lock(
    source_root: Path, contract: dict[str, Any], *, python_executable: Path,
    wheelhouse_root: Path,
) -> bytes:
    runtime = contract["recipe"]["runtime"]
    if python_executable.is_symlink() or not python_executable.is_file():
        raise ValueError("explicit Python interpreter must be a real file")
    probe = _probe_interpreter(
        python_executable, contract, include_distributions=False
    )
    if (probe["implementation"], probe["version"]) != (
        runtime["pythonImplementation"], runtime["pythonVersion"]
    ):
        raise ValueError("approved Python runtime mismatch")
    lock_path = source_root / DEPENDENCY_LOCK_RELATIVE_PATH
    lock_blob = lock_path.read_bytes()
    lock = han_tiles_contract.loads_json_strict(lock_blob)
    locked_versions = {
        row["distributionName"]: row["version"]
        for row in lock.get("distributions", {}).values()
        if isinstance(row, dict) and "distributionName" in row and "version" in row
    }
    validate_dependency_lock_bytes(
        lock_blob, contract, locked_versions,
        {key: probe[key] for key in ("system", "machine", "pythonTag")},
    )
    validate_wheelhouse(lock_blob, wheelhouse_root)
    return lock_blob


def prepare_reviewed_runtime(
    *, base_python: Path, runtime_root: Path, wheelhouse_root: Path,
    lock_blob: bytes, contract: dict[str, Any],
) -> Path:
    """Create an isolated interpreter populated only from the reviewed wheels."""
    if runtime_root.exists():
        raise ValueError("reviewed runtime root must not already exist")
    created = subprocess.run(
        [str(base_python), "-m", "venv", "--copies", str(runtime_root)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    runtime_python = runtime_root / "bin/python"
    if created.returncode != 0 or not runtime_python.is_file() or runtime_python.is_symlink():
        raise ValueError("could not create an isolated reviewed runtime")
    lock = han_tiles_contract.loads_json_strict(lock_blob)
    artifacts = [
        str(wheelhouse_root / lock["distributions"][role]["artifactFilename"])
        for role in contract["recipe"]["dependencies"]
    ]
    installed = subprocess.run(
        [
            str(runtime_python), "-m", "pip", "install", "--no-index",
            "--only-binary=:all:", "--no-deps", "--disable-pip-version-check",
            *artifacts,
        ],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    if installed.returncode != 0:
        raise ValueError("reviewed wheel runtime installation failed")
    probe = _probe_interpreter(runtime_python, contract)
    validate_dependency_lock_bytes(
        lock_blob, contract, probe["distributions"],
        {key: probe[key] for key in ("system", "machine", "pythonTag")},
    )
    return runtime_python


def create_clean_copy(
    source_root: Path | str, run_root: Path | str, approved_files: tuple[Path, ...]
) -> None:
    """Create a clean run root containing only prevalidated approved files."""
    source = Path(source_root).resolve()
    destination = Path(run_root)
    if destination.exists():
        raise ValueError("clean run root must not already exist")
    destination.mkdir(parents=True)
    for path in approved_files:
        resolved = path.resolve()
        try:
            relative = resolved.relative_to(source)
        except ValueError as error:
            raise ValueError("approved file escapes source root") from error
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())


def _output_document(path: Path, role: str) -> tuple[bytes, Any]:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"stage {role} did not produce its required output")
    blob = path.read_bytes()
    try:
        document = han_tiles_contract.loads_json_strict(blob)
    except ValueError as error:
        raise ValueError(f"stage {role} produced invalid JSON") from error
    return blob, document


def _rectangular(value: Any, rows: int, cols: int, where: str) -> None:
    if not isinstance(value, list) or len(value) != rows or any(
        not isinstance(row, list) or len(row) != cols for row in value
    ):
        raise ValueError(f"{where} does not match canonical grid")


def _summary(role: str, document: Any) -> dict[str, int]:
    if not isinstance(document, dict):
        raise ValueError(f"{role} output root must be an object")
    if role == "HAN_PLACES":
        projection = document.get("projection", {})
        places = document.get("places")
        if not isinstance(places, list) or document.get("count") != len(places):
            raise ValueError("HAN_PLACES count mismatch")
        return {
            "year": document.get("year"), "cols": projection.get("cols"),
            "rows": projection.get("rows"), "placeCount": len(places),
            "nudgedCount": document.get("nudged"),
        }
    if role == "JUNGUOZHI":
        groups = document.get("places")
        if not isinstance(groups, list):
            raise ValueError("JUNGUOZHI places must be an array")
        counties = [county for group in groups for county in group.get("counties", [])]
        if document.get("junCount") != len(groups) or document.get("countyCount") != len(counties):
            raise ValueError("JUNGUOZHI declared counts mismatch content")
        return {
            "groupCount": len(groups), "countyCount": len(counties),
            "resolvedCount": sum(group.get("resolved", 0) for group in groups),
            "candidateCount": sum(group.get("candidate", 0) for group in groups),
            "checksumPassCount": sum(group.get("checksum") == "PASS" for group in groups),
            "noCountCount": sum(group.get("checksum") == "NO_COUNT" for group in groups),
        }
    if role == "TERRAIN_GRID":
        rows, cols = document.get("rows"), document.get("cols")
        for key in ("terrain", "owner", "seatOwner", "parentOwner"):
            _rectangular(document.get(key), rows, cols, f"TERRAIN_GRID.{key}")
        adjacency = document.get("adjacency", {})
        return {
            "year": document.get("year"), "cols": cols, "rows": rows,
            "terrainCellCount": rows * cols, "ownerCellCount": rows * cols,
            "seatOwnerCellCount": rows * cols,
            "hubCount": len(document.get("hubs", [])),
            "regionCount": len(document.get("regionNames", [])),
            "countyEdgeCount": len(adjacency.get("county", [])),
            "commanderyEdgeCount": len(adjacency.get("commandery", [])),
            "provinceCount": len(document.get("provinceRecords", [])),
            "parentRegionCount": len(document.get("parentRegions", [])),
        }
    if role == "READINGS":
        return {"entryCount": len(document)}
    meta = document.get("_meta", {})
    counts = meta.get("counts", {})
    return {
        "year": meta.get("year"), "cols": meta.get("cols"), "rows": meta.get("rows"),
        "cityCount": len(document.get("cities", [])),
        "junCount": len(document.get("juns", [])),
        "regionCount": len(document.get("regions", [])),
        "ownerRunCount": len(document.get("owner", [])),
        "parentOwnerRunCount": len(document.get("parentOwner", [])),
        "countyEdgeCount": len(document.get("adjacency", {}).get("county", [])),
        "commanderyEdgeCount": len(document.get("adjacency", {}).get("commandery", [])),
        "provinceCount": len(document.get("provinceRecords", [])),
        "parentRegionCount": len(document.get("parentRegions", [])),
    }


def _manifest(role: str, blob: bytes, document: Any) -> dict[str, Any]:
    return {
        "role": role, "sha256": _digest(blob), "bytes": len(blob),
        "summary": _summary(role, document),
    }


def _components(node_count: int, edges: list[dict[str, Any]]) -> list[int]:
    parent = list(range(node_count))

    def find(node: int) -> int:
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    seen = set()
    for edge in edges:
        if not isinstance(edge, dict) or type(edge.get("a")) is not int or type(edge.get("b")) is not int:
            raise ValueError("adjacency edge identity is invalid")
        a, b = edge["a"], edge["b"]
        if not (0 <= a < node_count and 0 <= b < node_count) or a == b:
            raise ValueError("adjacency edge is out of range or self-linked")
        identity = (min(a, b), max(a, b))
        if identity in seen:
            raise ValueError("adjacency edge is duplicated")
        seen.add(identity)
        left, right = find(a), find(b)
        if left != right:
            parent[left] = right
    sizes: dict[int, int] = {}
    for node in range(node_count):
        root = find(node)
        sizes[root] = sizes.get(root, 0) + 1
    return sorted(sizes.values(), reverse=True)


def validate_semantic_outputs(documents: Mapping[str, Any]) -> bool:
    """Run canonical shape, RLE, adjacency, and connectivity gates."""
    if set(documents) != set(han_tiles_contract.OUTPUT_ROLES):
        raise ValueError("semantic output role closure mismatch")
    tiles = documents["HAN_TILES"]
    rows, cols = tiles["_meta"]["rows"], tiles["_meta"]["cols"]
    cells = rows * cols
    terrain = tiles.get("terrain")
    if not isinstance(terrain, list) or len(terrain) != rows or any(
        not isinstance(row, str) or len(row) != cols for row in terrain
    ):
        raise ValueError("HAN_TILES terrain shape mismatch")
    cities = tiles.get("cities")
    juns = tiles.get("juns")
    provinces = tiles.get("provinceRecords")
    parents = tiles.get("parentRegions")
    if (not isinstance(cities, list) or not isinstance(juns, list) or not cities or not juns
            or not isinstance(provinces, list) or not isinstance(parents, list)
            or not provinces or not parents):
        raise ValueError("HAN_TILES city/jun roster is empty")
    for key, upper_bound in (("owner", len(provinces)), ("parentOwner", len(parents))):
        runs = tiles.get(key)
        if not isinstance(runs, list) or any(
            not isinstance(run, list) or len(run) != 2
            or type(run[0]) is not int or type(run[1]) is not int
            or run[1] < 1 or not -1 <= run[0] < upper_bound
            for run in runs
        ) or sum(run[1] for run in runs) != cells:
            raise ValueError(f"HAN_TILES {key} RLE length mismatch")
    adjacency = tiles.get("adjacency", {})
    county = adjacency.get("county")
    commandery = adjacency.get("commandery")
    if not isinstance(county, list) or not isinstance(commandery, list):
        raise ValueError("HAN_TILES adjacency is missing")
    sizes = _components(len(provinces), county)
    isolated = sum(size == 1 for size in sizes)
    # Water-separated archipelagos are legitimate disconnected province-graph
    # components.  The gate still rejects a missing/fragmented mainland graph.
    if sizes[0] / len(provinces) < 0.85 or isolated >= 120 or len(sizes) >= 130:
        raise ValueError("HAN_TILES county connectivity gate failed")
    _components(len(parents), commandery)
    if len(commandery) < 150:
        raise ValueError("HAN_TILES commandery adjacency gate failed")
    return True


def _child_environment(contract: dict[str, Any]) -> dict[str, str]:
    environment = contract["recipe"]["runtime"]["environment"]
    return {
        "PATH": os.defpath,
        "LC_ALL": environment["locale"], "LANG": environment["locale"],
        "TZ": environment["timezone"],
        "PYTHONHASHSEED": environment["pythonHashSeed"],
        "PYTHONIOENCODING": "utf-8", "PYTHONDONTWRITEBYTECODE": "1",
        "OMP_NUM_THREADS": str(environment["threadCount"]),
        "OPENBLAS_NUM_THREADS": str(environment["threadCount"]),
        "MKL_NUM_THREADS": str(environment["threadCount"]),
        "NUMEXPR_NUM_THREADS": str(environment["threadCount"]),
    }


def execute_clean_run(
    *, run_root: Path, contract: dict[str, Any], python_executable: Path
) -> tuple[dict[str, Any], dict[str, bytes]]:
    """Execute all five approved stages in one already-created clean copy."""
    documents: dict[str, Any] = {}
    manifests: dict[str, Any] = {}
    output_blobs: dict[str, bytes] = {}
    stages = {stage["stageId"]: stage for stage in contract["recipe"]["stages"]}
    for stage_id, generator_role in STAGE_COMMANDS:
        stage = stages[stage_id]
        command = [
            str(python_executable), GENERATOR_RELATIVE_PATHS[generator_role], *stage["argv"]
        ]
        result = subprocess.run(
            command, cwd=run_root, env=_child_environment(contract),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0:
            raise ValueError(f"canonical stage {stage_id} failed")
        blob, document = _output_document(run_root / OUTPUT_RELATIVE_PATHS[stage_id], stage_id)
        documents[stage_id] = document
        output_blobs[stage_id] = blob
        manifests[stage_id] = _manifest(stage_id, blob, document)
    validate_semantic_outputs(documents)
    if manifests != contract["expectedOutputs"]:
        raise ValueError("clean run outputs do not match approved expectedOutputs")
    return manifests, output_blobs


def _attestation(
    contract: dict[str, Any], outputs: dict[str, Any]
) -> dict[str, Any]:
    runtime = contract["recipe"]["runtime"]
    return {
        "schemaVersion": 1,
        "attestationId": han_tiles_contract.ATTESTATION_ID,
        "contractId": contract["contractId"],
        "contractSha256": _digest(_canonical_bytes(contract)),
        "recipeSha256": contract["recipeSha256"],
        "sourceBundleSha256": han_tiles_contract.recipe_sha256(contract["recipe"]["inputs"]),
        "runtimeFingerprint": {
            "pythonImplementation": runtime["pythonImplementation"],
            "pythonVersion": runtime["pythonVersion"],
            "dependencyLockSha256": runtime["dependencyLock"]["sha256"],
            "environment": dict(runtime["environment"]),
        },
        "cleanRuns": [
            {"runOrdinal": 1, "outputs": outputs},
            {"runOrdinal": 2, "outputs": json.loads(json.dumps(outputs))},
        ],
        "materializedArtifact": json.loads(json.dumps(outputs["HAN_TILES"])),
    }


def _disjoint_roots(source: Path, output: Path, work: Path) -> None:
    roots = (source, output, work)
    if len(set(roots)) != 3:
        raise ValueError("source, output, and work roots must be distinct")
    for candidate in (output, work):
        try:
            candidate.relative_to(source)
        except ValueError:
            continue
        raise ValueError("output/work roots must be outside source root")
    for left, right in ((output, work), (work, output)):
        try:
            left.relative_to(right)
        except ValueError:
            continue
        raise ValueError("output and work roots must not overlap")


def run_protected_build(
    *, source_root: Path | str, output_root: Path | str, work_root: Path | str,
    wheelhouse_root: Path | str, contract: dict[str, Any],
    python_executable: Path | str = Path(sys.executable).resolve(),
) -> dict[str, Any]:
    """Prevalidate, run twice, materialize exact HAN_TILES, and attest."""
    han_tiles_contract.validate_contract(contract)
    unresolved_source = Path(source_root)
    unresolved_output = Path(output_root)
    unresolved_work = Path(work_root)
    unresolved_python = Path(python_executable)
    for path, label in (
        (unresolved_source, "source root"), (unresolved_output, "output root"),
        (unresolved_work, "work root"), (unresolved_python, "Python interpreter"),
    ):
        if path.is_symlink():
            raise ValueError(f"{label} symlink is forbidden")
    source, output, work = (
        unresolved_source.resolve(), unresolved_output.resolve(), unresolved_work.resolve()
    )
    _disjoint_roots(source, output, work)
    approved = validate_approved_source_files(source, contract)
    approved_interpreter = unresolved_python.resolve()
    lock_blob = prevalidate_runtime_and_lock(
        source, contract, python_executable=approved_interpreter,
        wheelhouse_root=Path(wheelhouse_root),
    )
    if work.exists():
        raise ValueError("work root must be absent for two clean runs")
    work.mkdir(parents=True)
    reviewed_interpreter = prepare_reviewed_runtime(
        base_python=approved_interpreter, runtime_root=work / "runtime",
        wheelhouse_root=Path(wheelhouse_root), lock_blob=lock_blob,
        contract=contract,
    )
    run_results = []
    run_blobs = []
    for ordinal in (1, 2):
        run_root = work / f"run-{ordinal}"
        create_clean_copy(source, run_root, approved)
        manifests, output_blobs = execute_clean_run(
            run_root=run_root, contract=contract,
            python_executable=reviewed_interpreter,
        )
        run_results.append(manifests)
        run_blobs.append(output_blobs)
    if run_results[0] != run_results[1] or run_blobs[0] != run_blobs[1]:
        raise ValueError("clean run drift: manifests or final bytes differ")
    if run_results[0] != contract["expectedOutputs"]:
        raise ValueError("clean run differs from approved expectedOutputs")
    for role, record in run_results[0].items():
        blob = run_blobs[0].get(role)
        if blob is None or _digest(blob) != record["sha256"] or len(blob) != record["bytes"]:
            raise ValueError(f"{role} bytes differ from its manifest")
    final_record = run_results[0]["HAN_TILES"]
    final_blob = run_blobs[0]["HAN_TILES"]
    target = output / OUTPUT_RELATIVE_PATHS["HAN_TILES"]
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_bytes(final_blob)
    os.replace(temporary, target)
    _record_matches(target, final_record, "HAN_TILES")
    attestation = _attestation(contract, run_results[0])
    han_tiles_contract.validate_attestation(contract, attestation)
    return attestation


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--work-root", required=True)
    parser.add_argument("--wheelhouse-root", required=True)
    parser.add_argument("--contract", required=True)
    parser.add_argument("--attestation", required=True)
    args = parser.parse_args()
    contract = han_tiles_contract.loads_json_strict(Path(args.contract).read_bytes())
    attestation = run_protected_build(
        source_root=args.source_root, output_root=args.output_root,
        work_root=args.work_root, wheelhouse_root=args.wheelhouse_root,
        contract=contract,
    )
    destination = Path(args.attestation)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(_canonical_bytes(attestation) + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
