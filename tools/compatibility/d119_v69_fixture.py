"""Build the public pinned old runtime in an isolated hosted CI checkout.

No VM/image/operating DB input. Local execution belongs to a separately owned
heavy driver, so this direct entry point only accepts normal hosted CI.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

SOURCE_SHA = "d50177b207897fc6c466095ec9699aab03f57536"


def run(command, *, cwd, env=None, log=None):
    result = subprocess.run(command, cwd=cwd, env=env, stdout=log or subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=True, timeout=1800)
    return result.stdout


def provision(source_root: Path, output_dir: Path, gradle_user_home: Path):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != "peppone-choi/opensamguk":
        raise RuntimeError("normal hosted CI required; local heavy driver owns local execution")
    source_root = source_root.resolve(strict=True)
    if run(["git", "rev-parse", "HEAD"], cwd=source_root).decode().strip() != SOURCE_SHA:
        raise RuntimeError("old source SHA mismatch")
    if run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=source_root).strip():
        raise RuntimeError("old source tracked bytes changed")
    output_dir = output_dir.absolute()
    output_dir.mkdir(parents=True, exist_ok=False)
    if output_dir.is_relative_to(source_root) or source_root.is_relative_to(output_dir):
        raise RuntimeError("output and source checkout must be independent")
    gradle_user_home = gradle_user_home.absolute()
    gradle_user_home.mkdir(parents=True, exist_ok=False)
    if gradle_user_home.is_relative_to(source_root) or gradle_user_home.is_relative_to(output_dir):
        raise RuntimeError("Gradle home must be isolated")
    inputs = []
    for relative in ("build.gradle.kts", "settings.gradle.kts", "app/game-api/build.gradle.kts"):
        path = source_root / relative
        wire = path.read_bytes()
        inputs.append({"path": relative, "bytes": len(wire), "sha256": hashlib.sha256(wire).hexdigest()})
    started = time.time()
    env = os.environ.copy()
    env["GRADLE_USER_HOME"] = str(gradle_user_home)
    log_path = output_dir / "old-game-api-build.log"
    with log_path.open("xb") as log:
        run(["./gradlew", ":app:game-api:bootJar", "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx2g"], cwd=source_root, env=env, log=log)
    # Do not guess a jar name/version or accept a plain library jar.
    candidates = [p for p in (source_root / "app/game-api/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
    if len(candidates) != 1:
        raise RuntimeError("exactly one old executable game-api jar required")
    import zipfile
    jar = candidates[0]
    with zipfile.ZipFile(jar) as archive:
        if not any(name.startswith("BOOT-INF/classes/") for name in archive.namelist()):
            raise RuntimeError("old jar is not an executable runtime")
    wire = jar.read_bytes()
    target = output_dir / "d501-game-api.jar"
    with target.open("xb") as output:
        output.write(wire)
    if run(["git", "rev-parse", "HEAD"], cwd=source_root).decode().strip() != SOURCE_SHA or run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=source_root).strip():
        raise RuntimeError("old source changed during build")
    receipt = {"schemaVersion": 1, "sourceSha": SOURCE_SHA, "runtimeJarPath": str(target),
               "runtimeJarSha256": hashlib.sha256(wire).hexdigest(), "runtimeJarBytes": len(wire),
               "inputs": inputs, "buildStartedAtUnix": started, "buildCompletedAtUnix": time.time(),
               "buildLogSha256": hashlib.sha256(log_path.read_bytes()).hexdigest(),
               "operatingVm": False, "operatingDatabase": False, "imagePull": False,
               "startupVerified": False, "compatibilityVerified": False}
    receipt_path = output_dir / "old-runtime-build-receipt.json"
    with receipt_path.open("x") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")
    return receipt_path


# Runtime provision adapted from C8 source SHA f800c7b05f750da451fc46246398a09ceddb9ddc3a779c5328f3e55141f40e64; only an explicit build heap cap is added.

SEED_HELPER = r'''
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.lang.reflect.Method;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import opensamguk.infra.seed.*;

public final class D119PublicSeed {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("D119_ISOLATED_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/d119_[a-f0-9]+(?:\\?[^\\s]*)?"))
            throw new IllegalArgumentException("isolated loopback D119 database required");
        Path root = Path.of(args[0]);
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
            System.getenv("D119_ISOLATED_USER"), System.getenv("D119_ISOLATED_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("69")
            .placeholders(Map.of("scenario_dir", ""))
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
            .load().migrate();
        Scenario scenario = ScenarioJson.INSTANCE.loadScenario(Files.readString(root.resolve(
            "infra/src/main/resources/scenario/scenario_990002.json")));
        var cities = ScenarioJson.INSTANCE.loadMapCities(Files.readString(root.resolve(
            "infra/src/main/resources/map/han-world-v3.json")));
        ScenarioImporter importer = new ScenarioImporter(scenario, cities, "scenario_990002",
            990002, 60, 0, 0, 1, 3, true, "8ebfeb6fa932a181ec9ef43b7473f4c9",
            OffsetDateTime.parse("2026-10-05T00:00:00Z"), root);
        // WorldId is an inline Kotlin value class; invoke its real mangled importer entry.
        Method entry = Arrays.stream(ScenarioImporter.class.getMethods())
            .filter(m -> m.getName().startsWith("importAll-") && m.getParameterCount() == 2
                && m.getParameterTypes()[0] == JdbcTemplate.class
                && m.getParameterTypes()[1] == int.class).findFirst().orElseThrow();
        entry.invoke(importer, new JdbcTemplate(ds), 1);
        System.out.println("D119_OLD_FLYWAY_AND_IMPORTER_COMPLETE");
    }
}
'''


def verify_inputs(root, manifest):
    if run(["git", "rev-parse", "HEAD"], cwd=root).decode().strip() != SOURCE_SHA:
        raise RuntimeError("public old checkout identity changed")
    if run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root).strip():
        raise RuntimeError("public old tracked source bytes changed")
    pairs = [(row["path"], row["old_sha256"]) for row in manifest["migrations"]]
    pairs.extend([(manifest["scenarioPath"], manifest["scenarioSha256"]),
                  (manifest["catalogPath"], manifest["catalogSha256"])])
    for path, expected in pairs:
        if hashlib.sha256((root / path).read_bytes()).hexdigest() != expected:
            raise RuntimeError(f"immutable source changed: {path}")
    import gzip
    archive_root = root / Path(manifest["catalogPath"]).parent
    for row in manifest["archiveFiles"]:
        blob = (archive_root / row["blob"]).read_bytes()
        raw = gzip.decompress(blob)
        if (hashlib.sha256(blob).hexdigest() != row["compressedSha256"]
                or hashlib.sha256(raw).hexdigest() != row["sha256"] or len(raw) != row["bytes"]):
            raise RuntimeError(f"archive bytes changed: {row['path']}")


def prepare(repo, output, manifest_path, supplied_receipt=None, supplied_source=None):
    import zipfile
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != "peppone-choi/opensamguk":
        raise RuntimeError("hosted CI only; local execution needs a separate approved heavy driver")
    manifest = json.loads(manifest_path.read_text())
    if manifest["oldSourceSha"] != SOURCE_SHA:
        raise RuntimeError("old source contract mismatch")
    output.mkdir(parents=True, exist_ok=False)
    if supplied_receipt:
        if not supplied_source:
            raise RuntimeError("C8 receipt requires its exact public source checkout")
        source = supplied_source.resolve(strict=True)
        receipt_path = supplied_receipt.resolve(strict=True)
    else:
        source = output / "old-source"
        available = subprocess.run(["git", "cat-file", "-e", SOURCE_SHA + "^{commit}"], cwd=repo,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if not available:
            # Fetch only the specified repository commit, never an operating image or scenario.
            run(["git", "fetch", "origin", SOURCE_SHA], cwd=repo)
        run(["git", "clone", "--shared", "--no-checkout", str(repo), str(source)], cwd=repo)
        run(["git", "checkout", "--detach", SOURCE_SHA], cwd=source)
        verify_inputs(source, manifest)
        receipt_path = provision(source, output / "runtime", output / "gradle-home")
    verify_inputs(source, manifest)
    receipt = json.loads(receipt_path.read_text())
    jar = Path(receipt["runtimeJarPath"]).resolve(strict=True)
    wire = jar.read_bytes()
    if (receipt["sourceSha"] != SOURCE_SHA or len(wire) != receipt["runtimeJarBytes"]
            or hashlib.sha256(wire).hexdigest() != receipt["runtimeJarSha256"]
            or any(receipt[key] for key in ("operatingVm", "operatingDatabase", "imagePull"))):
        raise RuntimeError("C8 runtime receipt/source/digest contract mismatch")
    classes = output / "classpath/classes"
    classes.mkdir(parents=True)
    libraries = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            if entry.filename.startswith("BOOT-INF/classes/"):
                relative = Path(entry.filename.removeprefix("BOOT-INF/classes/"))
                target = classes / relative
            elif entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                relative = Path(entry.filename.removeprefix("BOOT-INF/lib/"))
                target = output / "classpath/lib" / relative
                libraries.append(target)
            else:
                continue
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError("runtime archive path traversal")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    if not libraries or not (classes / "db/migration").is_dir():
        raise RuntimeError("old runtime classpath missing")
    helper = output / "D119PublicSeed.java"
    helper.write_text(SEED_HELPER)
    cp = os.pathsep.join(map(str, [classes, *sorted(libraries)]))
    java_home = Path(os.environ["JAVA_HOME"])
    helper_classes = output / "helper-classes"
    helper_classes.mkdir()
    with (output / "helper-compile.log").open("xb") as log:
        run([str(java_home / "bin/javac"), "-cp", cp, "-d", str(helper_classes), str(helper)],
            cwd=output, log=log)
    result = {"sourceSha": SOURCE_SHA, "sourceRoot": str(source), "runtimeReceipt": str(receipt_path),
              "runtimeJarSha256": receipt["runtimeJarSha256"],
              "seedCommand": [str(java_home / "bin/java"), "-Xmx1g", "-cp",
                              os.pathsep.join([str(helper_classes), cp]), "D119PublicSeed", str(source)],
              "sourceManifestSha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
              "classpathEntries": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                                   for p in sorted(libraries)],
              "startupVerified": False, "compatibilityVerified": False}
    target = output / "prepared-runtime.json"
    target.write_text(json.dumps(result, indent=2) + "\n")
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="D119 immutable V69 fixture preparation (no DB access)")
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--source-root", type=Path)
    args = parser.parse_args()
    print(prepare(args.repo.resolve(strict=True), args.output.absolute(), args.manifest,
                  args.receipt, args.source_root))
