"""PR #1115: actual production matcher mutation and fresh focused JVM evidence."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--root", type=Path, required=True)
parser.add_argument("--head", required=True)
parser.add_argument("--evidence", type=Path, required=True)
parser.add_argument("--execute", action="store_true")
args = parser.parse_args()
root = args.root.resolve()
evidence = args.evidence.resolve()
head = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
require(head == args.head, "checkout/head mismatch")
config = "app/game-api/src/main/kotlin/opensamguk/gameapi/security/GameApiSecurityConfig.kt"
target = "opensamguk.gameapi.security.ProvinceNamesMatcherSecurityChainTest"
hotcold = "opensamguk.engine.boot.HotColdWorldCatalogGuardTest"
api_suites = {
    target: 4,
    "opensamguk.gameapi.security.JwtVerifyFilterTest": 2,
    "opensamguk.gameapi.security.GameApiJwtVerifierTest": 7,
    "opensamguk.gameapi.security.CommandMutationSecurityChainTest": 2,
}
expected_failure = "all unsupported methods are forbidden before reader for every identity()"
deny = b'                    .requestMatchers("/api/map/provinces/names", "/api/map/provinces/names/v1").denyAll()\n'
source = (root / config).read_bytes()
require(source.count(deny) == 1, "exact production deny matcher missing/changed")
mutated = source.replace(deny, b"", 1)
tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).decode().split("\0")
tracked = [p for p in tracked if p]
require(all((root / p).is_file() for p in tracked), "tracked source missing")
manifest_paths = [p for p in tracked if p.endswith((".kt", ".kts", ".gradle")) or
                  p in ("gradle.properties", "gradle/wrapper/gradle-wrapper.properties") or
                  ("/src/" in p and "/resources/" in p)]
manifest = {p: sha((root / p).read_bytes()) for p in sorted(manifest_paths)}
plan = {
    "status": "PREPARED_NOT_EXECUTED", "head": head,
    "production_sha256": sha(source), "mutated_sha256": sha(mutated),
    "tracked_files": len(tracked), "jvm_source_manifest_count": len(manifest),
    "source_exclusions": [], "compiler_task_exclusions": [],
    "red_suites": {target: 4}, "restored_api_suites": api_suites,
    "hotcold_suites": {hotcold: 11}, "expected_red_failure": expected_failure,
    "postgres_services": [], "selected_fixtures_require_postgres": False,
}
if not args.execute:
    print(json.dumps(plan, ensure_ascii=False))
    raise SystemExit(0)
require(os.environ.get("GITHUB_ACTIONS") == "true" and
        os.environ.get("GITHUB_EVENT_NAME") == "pull_request" and
        os.environ.get("GITHUB_REPOSITORY") == "peppone-choi/opensamguk",
        "execute is authorized only on the PR GitHub-hosted runner")
require(subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD"]).returncode == 0,
        "tracked checkout bytes differ from the exact head")
evidence.mkdir(parents=True, exist_ok=False)
(evidence / "jvm-source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
started = time.time()
record = {**plan, "status": "RUNNING", "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "run_id": os.environ.get("GITHUB_RUN_ID"), "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
          "runner_os": os.environ.get("RUNNER_OS"), "runner_arch": os.environ.get("RUNNER_ARCH"),
          "platform": platform.platform(), "python": platform.python_version(),
          "jdk": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip()}
flags = ["--no-daemon", "--no-build-cache", "--no-configuration-cache", "--max-workers=1",
         "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]


def run_tests(tree, phase, module, expected):
    results = tree / module / "build/test-results/test"
    shutil.rmtree(results, ignore_errors=True)
    dest = evidence / phase
    dest.mkdir()
    init = tree / ".c1-province-evidence.gradle"
    init.write_text('''import groovy.json.JsonOutput
import java.security.MessageDigest
def digest = { byte[] bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString() }
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        outputs.upToDateWhen { false }
        doFirst {
            def entries = classpath.files.collect { f ->
                if (f.isDirectory()) {
                    def rows = []
                    f.eachFileRecurse { c -> if (c.isFile()) rows.add([path: f.toPath().relativize(c.toPath()).toString(), sha256: digest(c.bytes)]) }
                    rows.sort { it.path }
                    return [path: f.path, files: rows.size(), sha256: digest(JsonOutput.toJson(rows).getBytes("UTF-8"))]
                }
                return [path: f.path, sha256: f.isFile() ? digest(f.bytes) : "MISSING"]
            }
            new File(System.getProperty("c1.evidence"), "classpath.json").text = JsonOutput.prettyPrint(JsonOutput.toJson([
                task: path, javaVersion: System.getProperty("java.version"), gradleVersion: gradle.gradleVersion, entries: entries
            ]))
        }
    }
}
''')
    cmd = [str(tree / "gradlew"), ":" + module.replace("/", ":") + ":test",
           "-I", str(init), "-Dc1.evidence=" + str(dest)]
    for suite in expected:
        cmd += ["--tests", suite]
    cmd += flags
    at = time.time()
    with (evidence / (phase + ".log")).open("w") as output:
        done = subprocess.run(cmd, cwd=tree, stdout=output, stderr=subprocess.STDOUT)
    xmls = sorted(results.glob("TEST-*.xml"))
    require(bool(xmls), phase + ": no new XML; compiler failure is not target-red evidence")
    suites = []
    for xml in xmls:
        require(xml.stat().st_mtime >= at, phase + ": stale XML")
        suite = ET.parse(xml).getroot()
        suites.append({"name": suite.attrib["name"],
                       **{k: int(suite.attrib[k]) for k in ("tests", "failures", "errors", "skipped")},
                       "mtime": xml.stat().st_mtime,
                       "failed_cases": [{"name": c.attrib["name"], "message": c.find("failure").attrib.get("message", "")}
                                        for c in suite.findall("testcase") if c.find("failure") is not None]})
        shutil.copy2(xml, dest / xml.name)
    require({s["name"]: s["tests"] for s in suites} == expected, phase + ": suite/count mismatch")
    require((dest / "classpath.json").is_file(), phase + ": actual classpath evidence missing")
    classpath = json.loads((dest / "classpath.json").read_text())
    require(classpath["javaVersion"].startswith("21.") and classpath["entries"] and
            all(entry["sha256"] != "MISSING" for entry in classpath["entries"]),
            phase + ": JDK/classpath mismatch")
    return {"exit_code": done.returncode, "started_unix": at, "suites": suites,
            **{k: sum(s[k] for s in suites) for k in ("tests", "failures", "errors", "skipped")}}


try:
    with tempfile.TemporaryDirectory(prefix="c1-province-full-tree-") as temp:
        tree = Path(temp) / "tree"
        shutil.copytree(root, tree, ignore=shutil.ignore_patterns(".git", ".gradle", "node_modules"))
        require(all((tree / p).is_file() for p in tracked), "full tree copy missing tracked files")
        (tree / config).write_bytes(mutated)
        try:
            record["red"] = run_tests(tree, "red", "app/game-api", {target: 4})
            red = record["red"]
            failures = [c for s in red["suites"] for c in s["failed_cases"]]
            require(red["exit_code"] != 0 and red["failures"] == 1 and red["errors"] == 0 and red["skipped"] == 0,
                    "target red must have one assertion failure, no errors/skips")
            require(len(failures) == 1 and failures[0]["name"] == expected_failure and
                    "403" in failures[0]["message"] and "200" in failures[0]["message"], "wrong red case")
        finally:
            (tree / config).write_bytes(source)
            require(sha((tree / config).read_bytes()) == sha(source), "production byte restoration failed")
        record["restored_api"] = run_tests(tree, "restored-api", "app/game-api", api_suites)
        record["hotcold"] = run_tests(tree, "hotcold", "app/game-engine", {hotcold: 11})
        for phase in ("restored_api", "hotcold"):
            green = record[phase]
            require(green["exit_code"] == 0 and all(green[k] == 0 for k in ("failures", "errors", "skipped")),
                    phase + ": restored execution not green")
        require(all(sha((tree / p).read_bytes()) == h for p, h in manifest.items()), "restored JVM manifest mismatch")
        require((root / config).read_bytes() == source, "checkout production source changed")
        record["restored_source_manifest_matches"] = True
        record["status"] = "PASS"
except Exception as error:
    record["status"] = "FAIL"
    record["reason"] = str(error)
    raise
finally:
    record["elapsed_seconds"] = time.time() - started
    record["finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (evidence / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
