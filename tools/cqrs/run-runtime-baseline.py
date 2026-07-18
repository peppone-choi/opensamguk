from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ENGINE_DIR = REPOSITORY_ROOT / "app" / "game-engine"
DEFAULT_OUTPUT_ROOT = ENGINE_DIR / "build" / "cqrs-runtime-baseline"
BASELINE_JAR_DIRECTORY = DEFAULT_OUTPUT_ROOT / "jars"
BASELINE_JAR_PATH = BASELINE_JAR_DIRECTORY / "game-engine-cqrs-baseline.jar"
PRODUCTION_JAR_DIRECTORY = ENGINE_DIR / "build" / "libs"
PROBE_IMAGE = "eclipse-temurin:21-jre"
POSTGRES_IMAGE = "postgres:16-alpine"
POSTGRES_USER = "baseline"
POSTGRES_PASSWORD = "baseline"
POSTGRES_DATABASE = "baseline"
REQUIRED_CGROUP_BYTES = 2 * 1024 * 1024 * 1024
REQUIRED_JVM_ARGS = [
    "-XX:+UseG1GC",
    "-XX:MaxRAMPercentage=60",
    "-XX:InitialRAMPercentage=40",
]
PROFILES = ("current", "cold10x")
SAMPLES_PER_PROFILE = 3
RUN_LABEL_KEY = "opensamguk.cqrs-baseline.run"
REQUIRED_JFR_EVENTS = (
    "jdk.GCPhasePause",
    "jdk.GarbageCollection",
    "jdk.ObjectAllocationSample",
)
JFR_GC_PHASE_PAUSE_EVENT = "jdk.GCPhasePause"
JFR_GARBAGE_COLLECTION_EVENT = "jdk.GarbageCollection"
JFR_GC_PAUSE_ANALYSIS_EVENTS = f"{JFR_GC_PHASE_PAUSE_EVENT},{JFR_GARBAGE_COLLECTION_EVENT}"
FORCED_GC_CAUSE = "System.gc()"
ANALYSIS_SCHEMA_VERSION = "cqrs-runtime-baseline.analysis.v1"
ISO_DURATION_PATTERN = re.compile(
    r"^P(?:(?P<days>\d+(?:\.\d+)?)D)?"
    r"(?:T(?:(?P<hours>\d+(?:\.\d+)?)H)?(?:(?P<minutes>\d+(?:\.\d+)?)M)?"
    r"(?:(?P<seconds>\d+(?:\.\d+)?)S)?)?$"
)


class RunnerFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class AnalysisArtifactLayout:
    run_directory: Path
    raw_directory: Path
    jfr_directory: Path
    logs_directory: Path
    source_paths: Mapping[str, Path]
    derived_paths: Mapping[str, Path]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build and run the isolated OPENSAM-123 baseline probe in six fresh Docker runs."
    )
    parser.add_argument("--base-rows", type=int, default=10_000)
    parser.add_argument("--run-id")
    parser.add_argument("--analyze-run-id")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_ROOT)
    return parser.parse_args()


def command_output(
    command: Sequence[str],
    *,
    cwd: Path = REPOSITORY_ROOT,
    environment: Mapping[str, str] | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        env=dict(environment) if environment is not None else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RunnerFailure(
            f"command failed ({completed.returncode}): {' '.join(command)}\n{completed.stdout[-4000:]}"
        )
    return completed


def require_within_build(path: Path) -> Path:
    resolved = path.resolve()
    engine_build = (ENGINE_DIR / "build").resolve()
    try:
        resolved.relative_to(engine_build)
    except ValueError as exc:
        raise RunnerFailure(f"output directory must stay under {engine_build}, found {resolved}") from exc
    return resolved


def resolve_host_jdk21() -> tuple[Path, Path]:
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    mac_java_home = Path("/usr/libexec/java_home")
    if mac_java_home.is_file():
        selected = command_output([str(mac_java_home), "-v", "21"], check=False)
        if selected.returncode == 0:
            candidates.append(Path(selected.stdout.strip()) / "bin" / "java")
    discovered = shutil.which("java")
    if discovered:
        candidates.append(Path(discovered))
    for java in candidates:
        if not java.is_file():
            continue
        version_output = command_output([str(java), "-version"], check=False).stdout
        if java_major(version_output) == 21:
            return java.parent.parent, java
    raise RunnerFailure("JDK 21 is required to build the baseline classifier jar")


def java_major(version_output: str) -> int | None:
    match = re.search(r"(?:openjdk|java) version\s+\"?(\d+)", version_output, re.IGNORECASE)
    if match:
        return int(match.group(1))
    match = re.match(r"\s*(\d+)(?:[.+-]|$)", version_output)
    if match:
        return int(match.group(1))
    return None


def ensure_docker_and_images() -> dict[str, str]:
    if shutil.which("docker") is None:
        raise RunnerFailure("Docker CLI is required")
    command_output(["docker", "info", "--format", "{{.ServerVersion}}"])
    for image in (PROBE_IMAGE, POSTGRES_IMAGE):
        inspected = command_output(["docker", "image", "inspect", image], check=False)
        if inspected.returncode != 0:
            command_output(["docker", "pull", image])
    version = command_output(["docker", "run", "--rm", PROBE_IMAGE, "java", "-version"]).stdout
    if java_major(version) != 21:
        raise RunnerFailure(f"Probe image must provide JDK 21, got: {version.strip()}")
    return {
        "probeTag": PROBE_IMAGE,
        "probeId": image_id(PROBE_IMAGE),
        "postgresTag": POSTGRES_IMAGE,
        "postgresId": image_id(POSTGRES_IMAGE),
    }


def image_id(image: str) -> str:
    identity = command_output(["docker", "image", "inspect", "--format", "{{.Id}}", image]).stdout.strip()
    if not identity:
        raise RunnerFailure(f"Docker image {image} did not report an image ID")
    return identity


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        while chunk := artifact.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_gradle_success(output: str) -> None:
    if "BUILD SUCCESSFUL" not in output:
        raise RunnerFailure("baseline Gradle build did not report BUILD SUCCESSFUL")


def validate_fresh_classifier_jar(classifier_jar: Path, build_started_at_ns: int) -> str:
    if not classifier_jar.is_file():
        raise RunnerFailure(f"runtimeBaselineJar did not emit the expected classifier: {classifier_jar}")
    if classifier_jar.stat().st_mtime_ns < build_started_at_ns:
        raise RunnerFailure(f"runtimeBaselineJar did not refresh classifier artifact: {classifier_jar}")
    return file_sha256(classifier_jar)


def build_baseline_jar(run_dir: Path, host_java_home: Path) -> Path:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(host_java_home)
    environment["GRADLE_USER_HOME"] = str(DEFAULT_OUTPUT_ROOT / "gradle-user-home")
    build_log = run_dir / "logs" / "build.log"
    build_started_at_ns = time.time_ns()
    completed = command_output(
        [
            str(REPOSITORY_ROOT / "gradlew"),
            "--no-daemon",
            "--rerun-tasks",
            "-Dkotlin.compiler.execution.strategy=in-process",
            ":app:game-engine:verifyRuntimeBaselineJarIsolation",
        ],
        environment=environment,
        check=False,
    )
    build_log.write_text(completed.stdout, encoding="utf-8")
    if completed.returncode != 0:
        raise RunnerFailure(f"baseline jar build failed; see {build_log}")
    require_gradle_success(completed.stdout)
    classifier_digest = validate_fresh_classifier_jar(BASELINE_JAR_PATH, build_started_at_ns)
    validate_classifier_jar(BASELINE_JAR_PATH)
    build_log.write_text(
        completed.stdout.rstrip() + f"\nClassifier SHA-256: {classifier_digest}\n",
        encoding="utf-8",
    )
    return BASELINE_JAR_PATH


def validate_classifier_jar(classifier_jar: Path) -> None:
    expected_jar = BASELINE_JAR_PATH.resolve()
    if classifier_jar.resolve() != expected_jar:
        raise RunnerFailure(f"baseline classifier must be the expected task archive: {expected_jar}")
    expected_directory = BASELINE_JAR_DIRECTORY.resolve()
    if classifier_jar.resolve().parent != expected_directory:
        raise RunnerFailure(
            f"baseline classifier must stay under {expected_directory}, found {classifier_jar.resolve()}"
        )
    dedicated_candidates = sorted(expected_directory.glob("*-cqrs-baseline.jar"))
    if dedicated_candidates != [expected_jar]:
        raise RunnerFailure(
            "baseline classifier directory must contain only the fixed task archive: "
            + ", ".join(str(path) for path in dedicated_candidates)
        )
    production_collisions = sorted(PRODUCTION_JAR_DIRECTORY.glob("*-cqrs-baseline.jar"))
    if production_collisions:
        raise RunnerFailure(
            "production build/libs must not contain a baseline classifier: "
            + ", ".join(str(path) for path in production_collisions)
        )


def docker_name(run_id: str, suffix: str) -> str:
    normalized_run_id = re.sub(r"[^a-z0-9]+", "-", run_id.lower()).strip("-")
    normalized_suffix = re.sub(r"[^a-z0-9]+", "-", suffix.lower()).strip("-")
    token = hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:16]
    prefix_length = 63 - len("cqrs-baseline-") - len(token) - len(normalized_suffix) - 2
    if prefix_length < 1:
        raise RunnerFailure(f"Docker suffix is too long: {suffix}")
    return f"cqrs-baseline-{normalized_run_id[:prefix_length]}-{token}-{normalized_suffix}"


def label_value_belongs_to_run(label_value: str | None, run_id: str) -> bool:
    return label_value == run_id


def docker_label_args(run_id: str) -> list[str]:
    return ["--label", f"{RUN_LABEL_KEY}={run_id}"]


def container_run_label(name: str) -> str | None:
    template = f'{{{{ index .Config.Labels "{RUN_LABEL_KEY}" }}}}'
    inspected = command_output(["docker", "container", "inspect", "--format", template, name], check=False)
    if inspected.returncode != 0:
        return None
    return inspected.stdout.strip() or None


def cleanup_container(name: str, run_id: str) -> None:
    if label_value_belongs_to_run(container_run_label(name), run_id):
        command_output(["docker", "rm", "-f", name], check=False)


def wait_for_postgres(container: str) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ready = command_output(
            ["docker", "exec", container, "pg_isready", "-U", POSTGRES_USER, "-d", POSTGRES_DATABASE],
            check=False,
        )
        if ready.returncode == 0:
            return
        time.sleep(0.5)
    raise RunnerFailure(f"Postgres container {container} did not become ready within 60 seconds")


def start_postgres(container: str, network: str, run_id: str, image: str) -> None:
    command_output(
        [
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            container,
            "--network",
            network,
            *docker_label_args(run_id),
            "-e",
            f"POSTGRES_USER={POSTGRES_USER}",
            "-e",
            f"POSTGRES_PASSWORD={POSTGRES_PASSWORD}",
            "-e",
            f"POSTGRES_DB={POSTGRES_DATABASE}",
            image,
        ]
    )
    wait_for_postgres(container)


def jfr_event_counts(summary: str) -> dict[str, int]:
    if "Event Type" not in summary:
        raise RunnerFailure("JFR summary did not contain an event table")
    counts: dict[str, int] = {}
    for event_type, count in re.findall(r"^\s*(jdk\.[A-Za-z0-9_]+)\s+(\d+)\s+\d+\s*$", summary, re.MULTILINE):
        counts[event_type] = int(count)
    if not counts:
        raise RunnerFailure("JFR summary did not contain parseable event counts")
    return counts


def validate_jfr_summary(summary: str) -> dict[str, int]:
    counts = jfr_event_counts(summary)
    missing = [event_type for event_type in REQUIRED_JFR_EVENTS if counts.get(event_type, 0) <= 0]
    if missing:
        raise RunnerFailure(f"JFR summary omitted required profile events: {', '.join(missing)}")
    return counts


def validate_jfr_file(
    jfr_tool: Path,
    jfr_path: Path,
    summary_path: Path,
    layout: AnalysisArtifactLayout,
) -> dict[str, int]:
    completed = command_output([str(jfr_tool), "summary", str(jfr_path)], check=False)
    atomic_write_text(summary_path, completed.stdout, layout)
    if completed.returncode != 0:
        raise RunnerFailure(f"JFR summary failed for {jfr_path}; see {summary_path}")
    return validate_jfr_summary(completed.stdout)


def parse_iso_duration_millis(value: str) -> float:
    match = ISO_DURATION_PATTERN.fullmatch(value)
    if match is None or not any(match.group(name) is not None for name in ("days", "hours", "minutes", "seconds")):
        raise RunnerFailure(f"JFR duration is not a supported ISO-8601 duration: {value!r}")
    seconds = (
        float(match.group("days") or 0) * 86_400
        + float(match.group("hours") or 0) * 3_600
        + float(match.group("minutes") or 0) * 60
        + float(match.group("seconds") or 0)
    )
    if not math.isfinite(seconds) or seconds < 0:
        raise RunnerFailure(f"JFR duration must be finite and non-negative: {value!r}")
    return seconds * 1_000


def jfr_gc_phase_pause_durations_by_cause(output: str) -> dict[str, list[float]]:
    try:
        document = json.loads(output)
    except json.JSONDecodeError as exc:
        raise RunnerFailure("JFR GC pause analysis output was not valid JSON") from exc
    if not isinstance(document, Mapping):
        raise RunnerFailure("JFR GC pause analysis JSON root must be an object")
    recording = document.get("recording")
    if not isinstance(recording, Mapping):
        raise RunnerFailure("JFR GC pause analysis JSON omitted recording")
    events = recording.get("events")
    if not isinstance(events, list):
        raise RunnerFailure("JFR GC pause analysis JSON omitted events")
    garbage_collection_causes: dict[int, str] = {}
    phase_pauses: list[tuple[int, float]] = []
    for index, event in enumerate(events):
        if not isinstance(event, Mapping):
            raise RunnerFailure(f"JFR GC pause analysis event {index} was not an object")
        event_type = event.get("type")
        values = event.get("values")
        if not isinstance(values, Mapping) or not isinstance(values.get("gcId"), int):
            raise RunnerFailure(f"JFR GC pause analysis event {index} omitted numeric gcId")
        gc_id = values["gcId"]
        if event_type == JFR_GARBAGE_COLLECTION_EVENT:
            cause = values.get("cause")
            if not isinstance(cause, str):
                raise RunnerFailure(f"JFR GarbageCollection event {index} omitted cause")
            prior = garbage_collection_causes.setdefault(gc_id, cause)
            if prior != cause:
                raise RunnerFailure(f"JFR GarbageCollection gcId {gc_id} reported conflicting causes")
        elif event_type == JFR_GC_PHASE_PAUSE_EVENT:
            duration = values.get("duration")
            if not isinstance(duration, str):
                raise RunnerFailure(f"JFR GCPhasePause event {index} omitted duration")
            phase_pauses.append((gc_id, parse_iso_duration_millis(duration)))
        else:
            raise RunnerFailure(f"JFR GC pause analysis event {index} had unexpected type {event_type!r}")
    durations = {"operational": [], "forcedRetainedHeapProbe": []}
    for gc_id, duration in phase_pauses:
        cause = garbage_collection_causes.get(gc_id)
        if cause is None:
            raise RunnerFailure(f"JFR GCPhasePause gcId {gc_id} did not map to a GarbageCollection cause")
        bucket = "forcedRetainedHeapProbe" if cause == FORCED_GC_CAUSE else "operational"
        durations[bucket].append(duration)
    return durations


def gc_phase_pause_metrics(durations_millis: Sequence[float]) -> dict[str, int | float]:
    if not durations_millis:
        raise RunnerFailure("JFR GCPhasePause output did not contain any pause durations")
    ordered = sorted(float(value) for value in durations_millis)
    if any(not math.isfinite(value) or value < 0 for value in ordered):
        raise RunnerFailure("JFR GCPhasePause durations must be finite and non-negative")
    return {
        "eventCount": len(ordered),
        "totalDurationMillis": sum(ordered),
        "maxDurationMillis": ordered[-1],
        "p50DurationMillis": percentile(ordered, 0.50),
        "p95DurationMillis": percentile(ordered, 0.95),
    }


def analyze_jfr_gc_phase_pause(
    jfr_tool: Path,
    jfr_path: Path,
    output_path: Path,
    layout: AnalysisArtifactLayout,
) -> tuple[dict[str, dict[str, int | float]], dict[str, list[float]]]:
    completed = command_output(
        [str(jfr_tool), "print", "--json", "--events", JFR_GC_PAUSE_ANALYSIS_EVENTS, str(jfr_path)],
        check=False,
    )
    atomic_write_text(output_path, completed.stdout, layout)
    if completed.returncode != 0:
        raise RunnerFailure(f"JFR GC pause print failed for {jfr_path}; see {output_path}")
    durations = jfr_gc_phase_pause_durations_by_cause(completed.stdout)
    return {bucket: gc_phase_pause_metrics(values) for bucket, values in durations.items()}, durations


def run_probe(
    *,
    jar: Path,
    run_dir: Path,
    network: str,
    run_id: str,
    profile: str,
    sample: int,
    base_rows: int,
    images: Mapping[str, str],
) -> Mapping[str, Any]:
    stem = f"{profile}-{sample}"
    db_name = docker_name(run_id, f"{profile}-{sample}-db")
    probe_name = docker_name(run_id, f"{profile}-{sample}-probe")
    raw_path = run_dir / "raw" / f"{stem}.json"
    jfr_path = run_dir / "jfr" / f"{stem}.jfr"
    log_path = run_dir / "logs" / f"{stem}.log"
    jfr_summary_path = run_dir / "logs" / f"{stem}.jfr-summary.txt"
    try:
        start_postgres(db_name, network, run_id, images["postgresId"])
        command = [
            "docker",
            "run",
            "--rm",
            "--name",
            probe_name,
            "--network",
            network,
            *docker_label_args(run_id),
            "--memory=2g",
            "--memory-swap=2g",
            "--read-only",
            "--tmpfs",
            "/tmp:rw,nosuid,nodev,size=128m",
            "--mount",
            f"type=bind,src={jar.parent.resolve()},dst=/app,readonly",
            "--mount",
            f"type=bind,src={run_dir.resolve()},dst=/artifacts",
            "-e",
            f"BASELINE_DB_URL=jdbc:postgresql://{db_name}:5432/{POSTGRES_DATABASE}",
            "-e",
            f"BASELINE_DB_USERNAME={POSTGRES_USER}",
            "-e",
            f"BASELINE_DB_PASSWORD={POSTGRES_PASSWORD}",
            "-e",
            f"BASELINE_PROBE_IMAGE_TAG={images['probeTag']}",
            "-e",
            f"BASELINE_PROBE_IMAGE_ID={images['probeId']}",
            "-e",
            f"BASELINE_POSTGRES_IMAGE_TAG={images['postgresTag']}",
            "-e",
            f"BASELINE_POSTGRES_IMAGE_ID={images['postgresId']}",
            images["probeId"],
            "java",
            *REQUIRED_JVM_ARGS,
            "-jar",
            f"/app/{jar.name}",
            f"--profile={profile}",
            f"--base-rows={base_rows}",
            f"--output=/artifacts/raw/{raw_path.name}",
            f"--jfr=/artifacts/jfr/{jfr_path.name}",
        ]
        completed = command_output(command, check=False)
        log_path.write_text(completed.stdout, encoding="utf-8")
        if completed.returncode != 0:
            raise RunnerFailure(f"probe {stem} failed; see {log_path}")
    finally:
        cleanup_container(probe_name, run_id)
        cleanup_container(db_name, run_id)
    if not raw_path.is_file() or not jfr_path.is_file() or jfr_path.stat().st_size == 0:
        raise RunnerFailure(f"probe {stem} did not emit both raw JSON and non-empty JFR")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    validate_raw(raw, profile)
    return raw


def validate_raw(raw: Mapping[str, Any], profile: str) -> None:
    if raw.get("schemaVersion") != "cqrs-runtime-baseline.raw.v2":
        raise RunnerFailure("unexpected raw baseline schema")
    if raw.get("profile") != profile:
        raise RunnerFailure(f"raw profile mismatch: expected {profile}, got {raw.get('profile')}")
    cgroup = require_mapping(raw, "cgroup")
    if cgroup.get("memoryLimitBytes") != REQUIRED_CGROUP_BYTES:
        raise RunnerFailure(f"probe did not observe the required 2 GiB cgroup: {cgroup}")
    jvm = require_mapping(raw, "jvm")
    if java_major(str(jvm.get("version", ""))) != 21:
        raise RunnerFailure(f"probe did not report JDK 21: {jvm.get('version')}")
    arguments = jvm.get("inputArguments")
    if not isinstance(arguments, list) or any(argument not in arguments for argument in REQUIRED_JVM_ARGS):
        raise RunnerFailure(f"probe JVM arguments do not match contract: {arguments}")
    memory = require_mapping(raw, "memory")
    heap_after = require_mapping(memory, "heapAfterGc")
    if not isinstance(heap_after.get("usedBytes"), int):
        raise RunnerFailure("raw baseline omitted post-GC retained-heap proxy")
    gc = require_mapping(raw, "gc")
    if not isinstance(gc.get("collectionTimeDeltaMillis"), int):
        raise RunnerFailure("raw baseline omitted the GC collection-time proxy")
    artifacts = require_mapping(raw, "artifacts")
    if artifacts.get("jfrConfiguration") != "profile":
        raise RunnerFailure("raw baseline did not use the JFR profile configuration")
    images = require_mapping(raw, "images")
    for key in ("probeTag", "probeId", "postgresTag", "postgresId"):
        if not isinstance(images.get(key), str) or not images[key]:
            raise RunnerFailure(f"raw baseline omitted image identity {key}")


def require_mapping(value: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    candidate = value.get(key)
    if not isinstance(candidate, Mapping):
        raise RunnerFailure(f"raw baseline field {key} must be an object")
    return candidate


def percentile(sorted_values: Sequence[float], fraction: float) -> float:
    if not sorted_values:
        raise RunnerFailure("cannot calculate a percentile for no values")
    position = (len(sorted_values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


def summary_metric(values: Sequence[int | float], percentiles: bool = False) -> dict[str, Any]:
    if not values:
        raise RunnerFailure("cannot summarize no samples")
    ordered = sorted(float(value) for value in values)
    result: dict[str, Any] = {
        "n": len(ordered),
        "min": ordered[0],
        "max": ordered[-1],
        "mean": sum(ordered) / len(ordered),
        "runToRunSpread": ordered[-1] - ordered[0],
    }
    if percentiles:
        result["p50"] = percentile(ordered, 0.50)
        result["p95"] = percentile(ordered, 0.95)
    return result


def nested_integer(raw: Mapping[str, Any], *keys: str) -> int:
    current: Any = raw
    for key in keys:
        if not isinstance(current, Mapping) or key not in current:
            raise RunnerFailure(f"raw baseline missing {'/'.join(keys)}")
        current = current[key]
    if not isinstance(current, int):
        raise RunnerFailure(f"raw baseline field {'/'.join(keys)} must be an integer")
    return current


def fixture_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, int]:
    fields = ("baseRows", "fixedHotLogRows", "logPayloadCharacters", "coldHistoryMultiplier", "coldHistoryRows")
    profile_values: dict[str, dict[str, int]] = {}
    for profile in PROFILES:
        values: dict[str, int] = {}
        for field in fields:
            samples = {nested_integer(sample, "fixture", field) for sample in records[profile]}
            if len(samples) != 1:
                raise RunnerFailure(f"{profile} samples do not share fixture field {field}")
            values[field] = samples.pop()
        profile_values[profile] = values
    current = profile_values["current"]
    cold = profile_values["cold10x"]
    for field in ("baseRows", "fixedHotLogRows", "logPayloadCharacters"):
        if cold[field] != current[field]:
            raise RunnerFailure(f"cold10x fixture field {field} diverged from current")
    if current["coldHistoryMultiplier"] != 1 or cold["coldHistoryMultiplier"] != 10:
        raise RunnerFailure("fixture profiles must use cold-history multipliers 1 and 10")
    if current["coldHistoryRows"] != current["baseRows"]:
        raise RunnerFailure("current fixture cold-history row count must equal its base row count")
    if cold["coldHistoryRows"] != current["coldHistoryRows"] * 10:
        raise RunnerFailure("cold10x fixture must contain exactly ten times current cold-history rows")
    return {
        "baseRows": current["baseRows"],
        "fixedHotLogRows": current["fixedHotLogRows"],
        "logPayloadCharacters": current["logPayloadCharacters"],
        "currentColdHistoryRows": current["coldHistoryRows"],
        "cold10xColdHistoryRows": cold["coldHistoryRows"],
        "coldHistoryRatio": 10,
    }


def image_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, str]:
    image_keys = ("probeTag", "probeId", "postgresTag", "postgresId")
    identities = {
        tuple((key, str(require_mapping(sample, "images")[key])) for key in image_keys)
        for profile in PROFILES
        for sample in records[profile]
    }
    if len(identities) != 1:
        raise RunnerFailure("baseline samples did not use one stable probe and Postgres image identity")
    return dict(identities.pop())


def expected_sample_stems() -> tuple[str, ...]:
    return tuple(f"{profile}-{index}" for profile in PROFILES for index in range(1, SAMPLES_PER_PROFILE + 1))


def resolve_existing_path(path: Path, label: str) -> Path:
    try:
        return path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise RunnerFailure(f"baseline analysis {label} could not be resolved: {path}") from exc


def require_path_within(path: Path, root: Path, label: str) -> Path:
    resolved = resolve_existing_path(path, label)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise RunnerFailure(f"baseline analysis {label} escapes run directory: {path}") from exc
    return resolved


def require_real_child_directory(run_directory: Path, name: str) -> Path:
    path = run_directory / name
    if path.is_symlink():
        raise RunnerFailure(f"baseline analysis {name} directory must not be a symlink: {path}")
    if not path.is_dir():
        raise RunnerFailure(f"baseline analysis requires a real {name} directory: {path}")
    resolved = require_path_within(path, run_directory, f"{name} directory")
    if resolved.parent != run_directory:
        raise RunnerFailure(f"baseline analysis {name} directory must be a direct run-directory child: {path}")
    return resolved


def artifact_names(directory: Path, suffix: str) -> set[str]:
    return {
        path.name
        for path in directory.iterdir()
        if path.name.endswith(suffix) and (path.is_file() or path.is_symlink())
    }


def require_source_file(path: Path, directory: Path, run_directory: Path, label: str) -> Path:
    if path.is_symlink():
        raise RunnerFailure(f"baseline analysis source artifact must not be a symlink: {path}")
    if not path.is_file():
        raise RunnerFailure(f"baseline analysis requires a regular source artifact: {path}")
    resolved = require_path_within(path, run_directory, label)
    if resolved.parent != directory:
        raise RunnerFailure(f"baseline analysis source artifact escapes its expected directory: {path}")
    return resolved


def require_safe_derived_target(target: Path, parent: Path, run_directory: Path, label: str) -> None:
    if target.parent != parent:
        raise RunnerFailure(f"baseline analysis derived target has an unexpected parent: {target}")
    if parent.is_symlink() or not parent.is_dir():
        raise RunnerFailure(f"baseline analysis derived target parent is unsafe: {parent}")
    parent_resolved = require_path_within(parent, run_directory, f"{label} parent")
    if target.is_symlink():
        raise RunnerFailure(f"baseline analysis derived target must not be a symlink: {target}")
    if target.exists() and not target.is_file():
        raise RunnerFailure(f"baseline analysis derived target must be a file or absent: {target}")
    if parent_resolved != parent:
        raise RunnerFailure(f"baseline analysis derived target parent is not a real directory: {parent}")


def preflight_analysis_artifacts(run_dir: Path) -> AnalysisArtifactLayout:
    candidate_run_directory = run_dir if run_dir.is_absolute() else Path.cwd() / run_dir
    if candidate_run_directory.is_symlink():
        raise RunnerFailure(f"baseline analysis run directory must not be a symlink: {candidate_run_directory}")
    if not candidate_run_directory.is_dir():
        raise RunnerFailure(f"baseline analysis run does not exist: {candidate_run_directory}")
    run_directory = resolve_existing_path(candidate_run_directory, "run directory")
    raw_directory = require_real_child_directory(run_directory, "raw")
    jfr_directory = require_real_child_directory(run_directory, "jfr")
    logs_directory = require_real_child_directory(run_directory, "logs")
    expected_stems = expected_sample_stems()
    expected_raw_names = {f"{stem}.json" for stem in expected_stems}
    expected_jfr_names = {f"{stem}.jfr" for stem in expected_stems}
    raw_names = artifact_names(raw_directory, ".json")
    jfr_names = artifact_names(jfr_directory, ".jfr")
    if raw_names != expected_raw_names:
        raise RunnerFailure(
            f"baseline analysis requires exactly six raw artifacts, found {', '.join(sorted(raw_names))}"
        )
    if jfr_names != expected_jfr_names:
        raise RunnerFailure(
            f"baseline analysis requires exactly six JFR artifacts, found {', '.join(sorted(jfr_names))}"
        )
    source_paths: dict[str, Path] = {}
    for stem in expected_stems:
        raw_path = raw_directory / f"{stem}.json"
        jfr_path = jfr_directory / f"{stem}.jfr"
        source_paths[f"raw/{raw_path.name}"] = require_source_file(
            raw_path,
            raw_directory,
            run_directory,
            f"raw/{raw_path.name}",
        )
        source_paths[f"jfr/{jfr_path.name}"] = require_source_file(
            jfr_path,
            jfr_directory,
            run_directory,
            f"jfr/{jfr_path.name}",
        )
    derived_paths: dict[str, Path] = {
        "analysis": run_directory / "analysis.json",
        "summaryJson": run_directory / "summary.json",
        "summaryMarkdown": run_directory / "summary.md",
    }
    for stem in expected_stems:
        derived_paths[f"summary:{stem}"] = logs_directory / f"{stem}.jfr-summary.txt"
        derived_paths[f"pause:{stem}"] = logs_directory / f"{stem}.jfr-gc-phase-pause.json"
    for key, target in derived_paths.items():
        parent = logs_directory if target.parent == logs_directory else run_directory
        require_safe_derived_target(target, parent, run_directory, key)
    return AnalysisArtifactLayout(
        run_directory=run_directory,
        raw_directory=raw_directory,
        jfr_directory=jfr_directory,
        logs_directory=logs_directory,
        source_paths=source_paths,
        derived_paths=derived_paths,
    )


def atomic_write_text(target: Path, content: str, layout: AnalysisArtifactLayout) -> None:
    if target not in layout.derived_paths.values():
        raise RunnerFailure(f"baseline analysis refused an unplanned derived target: {target}")
    parent = layout.logs_directory if target.parent == layout.logs_directory else layout.run_directory
    require_safe_derived_target(target, parent, layout.run_directory, target.name)
    temporary_path: Path | None = None
    descriptor: int | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.",
            suffix=".tmp",
            dir=parent,
            text=True,
        )
        temporary_path = Path(temporary_name)
        with os.fdopen(descriptor, "w", encoding="utf-8") as artifact:
            descriptor = None
            artifact.write(content)
        os.replace(temporary_path, target)
        temporary_path = None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def read_existing_run_records(layout: AnalysisArtifactLayout) -> dict[str, list[Mapping[str, Any]]]:
    records: dict[str, list[Mapping[str, Any]]] = {profile: [] for profile in PROFILES}
    for profile in PROFILES:
        for index in range(1, SAMPLES_PER_PROFILE + 1):
            stem = f"{profile}-{index}"
            raw_key = f"raw/{stem}.json"
            jfr_key = f"jfr/{stem}.jfr"
            raw_path = layout.source_paths[raw_key]
            jfr_path = layout.source_paths[jfr_key]
            if jfr_path.stat().st_size == 0:
                raise RunnerFailure(f"baseline analysis found an empty JFR artifact: {jfr_path}")
            try:
                raw = json.loads(raw_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise RunnerFailure(f"baseline analysis could not parse raw artifact: {raw_path}") from exc
            validate_raw(raw, profile)
            artifacts = require_mapping(raw, "artifacts")
            if artifacts.get("jfrFile") != jfr_path.name:
                raise RunnerFailure(f"raw artifact {raw_path} does not reference its expected JFR file")
            records[profile].append(raw)
    return records


def source_artifact_hashes(layout: AnalysisArtifactLayout) -> dict[str, str]:
    hashes: dict[str, str] = {}
    for relative, path in layout.source_paths.items():
        directory = layout.raw_directory if relative.startswith("raw/") else layout.jfr_directory
        require_source_file(path, directory, layout.run_directory, relative)
        hashes[relative] = file_sha256(path)
    return hashes


def analyze_run_artifacts(
    run_dir: Path,
    jfr_tool: Path,
) -> tuple[dict[str, list[Mapping[str, Any]]], dict[str, Mapping[str, Any]]]:
    layout = preflight_analysis_artifacts(run_dir)
    records = read_existing_run_records(layout)
    source_hashes = source_artifact_hashes(layout)
    analysis_samples: dict[str, Mapping[str, Any]] = {}
    for profile in PROFILES:
        for index in range(1, SAMPLES_PER_PROFILE + 1):
            stem = f"{profile}-{index}"
            jfr_path = layout.source_paths[f"jfr/{stem}.jfr"]
            summary_path = layout.derived_paths[f"summary:{stem}"]
            pause_json_path = layout.derived_paths[f"pause:{stem}"]
            validate_jfr_file(jfr_tool, jfr_path, summary_path, layout)
            pause_metrics, pause_durations = analyze_jfr_gc_phase_pause(jfr_tool, jfr_path, pause_json_path, layout)
            analysis_samples[stem] = {
                "profile": profile,
                "rawFile": f"raw/{stem}.json",
                "jfrFile": f"jfr/{stem}.jfr",
                "jfrSummaryFile": f"logs/{summary_path.name}",
                "jfrGcPhasePauseJsonFile": f"logs/{pause_json_path.name}",
                "jfrGcPhasePause": pause_metrics,
                "jfrGcPhasePauseDurationsMillis": pause_durations,
            }
    write_analysis(layout, analysis_samples, source_hashes)
    write_summary(layout, records, analysis_samples)
    if source_hashes != source_artifact_hashes(layout):
        raise RunnerFailure("baseline raw/JFR source artifacts changed during derived analysis")
    return records, analysis_samples


def gc_phase_pause_profile_metrics(
    profile: str,
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, int | float]]:
    durations_by_bucket: dict[str, list[float]] = {
        "operational": [],
        "forcedRetainedHeapProbe": [],
    }
    for index in range(1, SAMPLES_PER_PROFILE + 1):
        stem = f"{profile}-{index}"
        sample = analysis_samples.get(stem)
        if not isinstance(sample, Mapping) or sample.get("profile") != profile:
            raise RunnerFailure(f"baseline analysis omitted {stem}")
        sample_durations = sample.get("jfrGcPhasePauseDurationsMillis")
        sample_metrics = sample.get("jfrGcPhasePause")
        if not isinstance(sample_durations, Mapping) or not isinstance(sample_metrics, Mapping):
            raise RunnerFailure(f"baseline analysis omitted GC pause details for {stem}")
        for bucket, profile_durations in durations_by_bucket.items():
            values = sample_durations.get(bucket)
            metrics = sample_metrics.get(bucket)
            if not isinstance(values, list) or any(not isinstance(duration, (int, float)) for duration in values):
                raise RunnerFailure(f"baseline analysis omitted numeric {bucket} GC pause durations for {stem}")
            expected_metrics = gc_phase_pause_metrics([float(duration) for duration in values])
            if metrics != expected_metrics:
                raise RunnerFailure(f"baseline analysis {bucket} GC pause metrics do not match durations for {stem}")
            profile_durations.extend(float(duration) for duration in values)
    return {bucket: gc_phase_pause_metrics(values) for bucket, values in durations_by_bucket.items()}


def build_summary(
    records: Mapping[str, Sequence[Mapping[str, Any]]],
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    profile_summary: dict[str, Any] = {}
    for profile in PROFILES:
        samples = records[profile]
        if len(samples) != SAMPLES_PER_PROFILE:
            raise RunnerFailure(f"summary requires {SAMPLES_PER_PROFILE} {profile} samples, found {len(samples)}")
        fixture_hashes = {str(require_mapping(sample, "fixture").get("sha256")) for sample in samples}
        if len(fixture_hashes) != 1:
            raise RunnerFailure(f"{profile} samples do not share a deterministic fixture hash")
        gc_phase_pause = gc_phase_pause_profile_metrics(profile, analysis_samples)
        profile_summary[profile] = {
            "n": len(samples),
            "fixtureSha256": fixture_hashes.pop(),
            "metrics": {
                "bootDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "bootDurationMs") for sample in samples], True
                ),
                "snapshotDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "snapshotDurationMs") for sample in samples], True
                ),
                "tickDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "tickDurationMs") for sample in samples], True
                ),
                "retainedHeapAfterGcBytes": summary_metric(
                    [nested_integer(sample, "memory", "heapAfterGc", "usedBytes") for sample in samples]
                ),
                "rssAfterGcBytes": summary_metric(
                    [nested_integer(sample, "memory", "rssAfterGcBytes") for sample in samples]
                ),
                "gcCollectionTimeProxyMillis": summary_metric(
                    [nested_integer(sample, "gc", "collectionTimeDeltaMillis") for sample in samples]
                ),
                "jfrGcPhasePause": gc_phase_pause,
            },
            "rawFiles": [f"raw/{profile}-{index}.json" for index in range(1, SAMPLES_PER_PROFILE + 1)],
        }
    current_retained = profile_summary["current"]["metrics"]["retainedHeapAfterGcBytes"]["mean"]
    cold_retained = profile_summary["cold10x"]["metrics"]["retainedHeapAfterGcBytes"]["mean"]
    return {
        "schemaVersion": "cqrs-runtime-baseline.summary.v2",
        "fixture": {
            "label": "synthetic production-shaped seed proxy; not production data or a sanitized production shape",
            "profiles": list(PROFILES),
            "samplesPerProfile": SAMPLES_PER_PROFILE,
            "crossProfileContract": fixture_contract(records),
        },
        "probe": {
            "cgroupMemoryBytes": REQUIRED_CGROUP_BYTES,
            "jvmArguments": REQUIRED_JVM_ARGS,
            "threeRunMetricPercentileMethod": "linear interpolation over sorted three-sample values",
            "jfrGcPhasePausePercentileMethod": (
                "linear interpolation over pooled per-event pause durations within each profile; event-weighted"
            ),
            "bootDurationMetric": {
                "scope": "harnessSetupAndBoot",
                "includes": [
                    "freshPostgresFlywayMigration",
                    "scenarioSeed",
                    "profileFixtureInsert",
                    "worldSnapshotLoad",
                    "inMemoryTurnWorldConstruction",
                ],
            },
            "gcCollectionTimeMetric": (
                "MXBean collection-time proxy; JFR GCPhasePause metrics are reported separately"
            ),
            "jfrGcPhasePauseMetric": (
                "Host JDK JFR JSON duration metrics split by GarbageCollection cause; operational excludes System.gc()"
            ),
        },
        "images": image_contract(records),
        "profiles": profile_summary,
        "retainedHeapComparison": {
            "currentMeanBytes": current_retained,
            "cold10xMeanBytes": cold_retained,
            "deltaBytes": cold_retained - current_retained,
            "deltaPercentOfCurrent": (
                None if current_retained == 0 else (cold_retained - current_retained) * 100 / current_retained
            ),
        },
    }


def format_number(value: float) -> str:
    return f"{value:.2f}"


def summary_markdown(summary: Mapping[str, Any]) -> str:
    profiles = require_mapping(summary, "profiles")
    lines = [
        "# CQRS runtime baseline",
        "",
        "Synthetic production-shaped seed proxy only; this is not production data or a sanitized production shape.",
        "",
        (
            "| Profile | n | Harness setup+boot p50 / p95 ms | Tick p50 / p95 ms | Retained heap mean bytes | "
            "Operational GC pause p50 / p95 / max ms | Forced-probe GC pause p50 / p95 / max ms |"
        ),
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for profile in PROFILES:
        detail = require_mapping(profiles, profile)
        metrics = require_mapping(detail, "metrics")
        boot = require_mapping(metrics, "bootDurationMs")
        tick = require_mapping(metrics, "tickDurationMs")
        retained = require_mapping(metrics, "retainedHeapAfterGcBytes")
        gc_pause = require_mapping(metrics, "jfrGcPhasePause")
        operational_pause = require_mapping(gc_pause, "operational")
        forced_pause = require_mapping(gc_pause, "forcedRetainedHeapProbe")
        lines.append(
            "| "
            + profile
            + " | "
            + str(detail["n"])
            + " | "
            + f"{format_number(float(boot['p50']))} / {format_number(float(boot['p95']))}"
            + " | "
            + f"{format_number(float(tick['p50']))} / {format_number(float(tick['p95']))}"
            + " | "
            + format_number(float(retained["mean"]))
            + " | "
            + " / ".join(
                format_number(float(operational_pause[key]))
                for key in ("p50DurationMillis", "p95DurationMillis", "maxDurationMillis")
            )
            + " | "
            + " / ".join(
                format_number(float(forced_pause[key]))
                for key in ("p50DurationMillis", "p95DurationMillis", "maxDurationMillis")
            )
            + " |"
        )
    comparison = require_mapping(summary, "retainedHeapComparison")
    lines.extend(
        [
            "",
            f"Retained heap delta (cold10x - current): {format_number(float(comparison['deltaBytes']))} bytes "
            f"({format_number(float(comparison['deltaPercentOfCurrent'] or 0.0))}%).",
            "",
            (
                "`heapAfterGc.usedBytes` is a retained-heap proxy after explicit `MemoryMXBean.gc()`, "
                "not an object-retention proof."
            ),
            (
                "`jfrGcPhasePause.operational` is host-JDK postprocessed JFR pause duration evidence and excludes "
                "the explicit retained-heap `System.gc()` probes; the forced-probe metrics are reported separately."
            ),
        ]
    )
    return "\n".join(lines) + "\n"


def write_analysis(
    layout: AnalysisArtifactLayout,
    analysis_samples: Mapping[str, Mapping[str, Any]],
    source_hashes: Mapping[str, str],
) -> None:
    analysis = {
        "schemaVersion": ANALYSIS_SCHEMA_VERSION,
        "jfrEvents": [JFR_GC_PHASE_PAUSE_EVENT, JFR_GARBAGE_COLLECTION_EVENT],
        "forcedGcCause": FORCED_GC_CAUSE,
        "sourceArtifactSha256": source_hashes,
        "samples": analysis_samples,
    }
    atomic_write_text(layout.derived_paths["analysis"], json.dumps(analysis, indent=2) + "\n", layout)


def write_summary(
    layout: AnalysisArtifactLayout,
    records: Mapping[str, Sequence[Mapping[str, Any]]],
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> None:
    summary = build_summary(records, analysis_samples)
    atomic_write_text(layout.derived_paths["summaryJson"], json.dumps(summary, indent=2) + "\n", layout)
    atomic_write_text(layout.derived_paths["summaryMarkdown"], summary_markdown(summary), layout)


def default_run_id() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ") + f"-{os.getpid()}"


def validate_run_id(run_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", run_id):
        raise RunnerFailure("run-id must be 1-64 alphanumeric, underscore, or hyphen characters")
    return run_id


def main() -> int:
    arguments = parse_args()
    if arguments.run_id and arguments.analyze_run_id:
        raise RunnerFailure("--run-id and --analyze-run-id cannot be used together")
    output_root = require_within_build(arguments.output_dir)
    if arguments.analyze_run_id:
        run_id = validate_run_id(arguments.analyze_run_id)
        run_dir = output_root / run_id
        require_within_build(run_dir)
        if not run_dir.is_dir():
            raise RunnerFailure(f"baseline analysis run does not exist: {run_dir}")
        host_java_home, _ = resolve_host_jdk21()
        jfr_tool = host_java_home / "bin" / "jfr"
        if not jfr_tool.is_file():
            raise RunnerFailure(f"JDK 21 did not provide the jfr tool: {jfr_tool}")
        analyze_run_artifacts(run_dir, jfr_tool)
        print(f"Baseline derived analysis written to {run_dir}")
        return 0
    if arguments.base_rows <= 0:
        raise RunnerFailure("--base-rows must be positive")
    run_id = validate_run_id(arguments.run_id or default_run_id())
    run_dir = require_within_build(output_root / run_id)
    if run_dir.exists():
        raise RunnerFailure(f"refusing to overwrite an existing baseline run: {run_dir}")
    for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
        directory.mkdir(parents=True, exist_ok=False)
    network = docker_name(run_id, "network")
    network_created = False
    try:
        host_java_home, _ = resolve_host_jdk21()
        jfr_tool = host_java_home / "bin" / "jfr"
        if not jfr_tool.is_file():
            raise RunnerFailure(f"JDK 21 did not provide the jfr tool: {jfr_tool}")
        images = ensure_docker_and_images()
        jar = build_baseline_jar(run_dir, host_java_home)
        command_output(["docker", "network", "create", *docker_label_args(run_id), network])
        network_created = True
        for profile in PROFILES:
            for sample in range(1, SAMPLES_PER_PROFILE + 1):
                print(f"Running {profile} sample {sample}/{SAMPLES_PER_PROFILE}")
                run_probe(
                    jar=jar,
                    run_dir=run_dir,
                    network=network,
                    run_id=run_id,
                    profile=profile,
                    sample=sample,
                    base_rows=arguments.base_rows,
                    images=images,
                )
        analyze_run_artifacts(run_dir, jfr_tool)
        print(f"Baseline artifacts written to {run_dir}")
        return 0
    finally:
        if network_created:
            command_output(["docker", "network", "rm", network], check=False)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RunnerFailure as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
