from pathlib import Path
import argparse, datetime, hashlib, json, os, shutil, subprocess, tempfile, time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description="C1 리뷰 응답 회귀의 전체 트리 적색/복원 검증")
parser.add_argument("--root", type=Path, required=True)
parser.add_argument("--evidence", type=Path, required=True)
parser.add_argument("--execute", action="store_true")
a = parser.parse_args()
root = a.root.resolve()
evidence = a.evidence.resolve()
api = "app/game-api"
board = api + "/src/main/kotlin/opensamguk/gameapi/controller/BoardController.kt"
troop = api + "/src/main/kotlin/opensamguk/gameapi/controller/TroopController.kt"
chain = "opensamguk.gameapi.security.ReadIdentitySecurityChainTest"
f4 = "opensamguk.gameapi.controller.F4ReadControllersTest"
expected_failures = {
    "same nation secret board denial returns INFO without reading private data()",
    "troops reject anonymous unresolved and foreign nation but return empty for owned nationless general()",
    "board 기밀실 blocked for own nation ordinary general with INFO reason()",
}
def sha(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

source = {rel: (root / rel).read_bytes() for rel in [board, troop]}
btext = source[board].decode()
bstart = btext.index("        if (secret && myPermission < 2) {")
bend = btext.index("        val nationLevel =", bstart)
board_red = (btext[:bstart] + "        if (secret && myPermission < 2) return ResponseEntity.status(403).build()\n" + btext[bend:]).encode()
ttext = source[troop].decode()
require(ttext.count("if (nationId < 0 ||") == 1, "부대 경계 변이 원천이 바뀜")
troop_red = ttext.replace("if (nationId < 0 ||", "if (nationId <= 0 ||").encode()
tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).decode().split("\0")
tracked = [p for p in tracked if p]
require(all((root / p).is_file() for p in tracked), "원본 tracked 파일 누락")
source_paths = [p for p in tracked if p.endswith((".kt", ".kts", ".gradle")) or p in ["gradle.properties", "gradle/wrapper/gradle-wrapper.properties"]]
source_paths += [p for p in tracked if "/src/" in p and "/resources/" in p and p not in source_paths]
source_manifest = {p: sha((root / p).read_bytes()) for p in sorted(source_paths)}
plan = {"status": "PREPARED_NOT_EXECUTED", "head": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
        "source_sha256": {p: sha(v) for p, v in source.items()}, "expected_red_failures": sorted(expected_failures),
        "tracked_files": len(tracked), "jvm_source_manifest_count": len(source_manifest), "source_exclusions": [], "compiler_task_exclusions": []}
if not a.execute:
    print(json.dumps(plan, ensure_ascii=False))
    raise SystemExit(0)
require(os.environ.get("GITHUB_ACTIONS") == "true", "현재 실행 준비안은 GitHub CI 전용; 로컬 슬롯 승인 없이 실행 금지")
evidence.mkdir(parents=True, exist_ok=False)
(evidence / "jvm-source-manifest.json").write_text(json.dumps(source_manifest, ensure_ascii=False, indent=2) + "\n")
started = time.time()
record = {**plan, "status": "RUNNING", "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "run_id": os.environ.get("GITHUB_RUN_ID"), "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
          "jdk": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip()}
flags = ["--no-daemon", "--no-build-cache", "--no-configuration-cache", "--max-workers=1", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]

def run_tests(tree, phase, module, patterns):
    results = tree / module / "build/test-results/test"
    shutil.rmtree(results, ignore_errors=True)
    init = tree / ".c1-force-test.gradle"
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
    dest = evidence / phase
    dest.mkdir()
    cmd = [str(tree / "gradlew"), ":" + module.replace("/", ":") + ":test", "-I", str(init)]
    cmd += ["-Dc1.evidence=" + str(dest)]
    for pattern in patterns:
        cmd += ["--tests", pattern]
    cmd += flags
    at = time.time()
    log = evidence / (phase + ".log")
    with log.open("w") as output:
        done = subprocess.run(cmd, cwd=tree, stdout=output, stderr=subprocess.STDOUT)
    xmls = sorted(results.glob("TEST-*.xml"))
    require(bool(xmls), phase + " 새 XML 없음: 컴파일/실행 실패는 적색 증거 아님")
    suites = []
    for xml in xmls:
        require(xml.stat().st_mtime >= at, phase + " 낡은 XML")
        parsed = ET.parse(xml).getroot()
        suites.append({"name": parsed.attrib["name"], "tests": int(parsed.attrib["tests"]),
                       "failures": int(parsed.attrib["failures"]), "errors": int(parsed.attrib["errors"]),
                       "skipped": int(parsed.attrib["skipped"]), "mtime": xml.stat().st_mtime,
                       "failed_cases": [case.attrib["name"] for case in parsed.findall("testcase") if case.find("failure") is not None]})
        shutil.copy2(xml, dest / xml.name)
    return {"exit_code": done.returncode, "suites": suites,
            "tests": sum(s["tests"] for s in suites), "failures": sum(s["failures"] for s in suites),
            "errors": sum(s["errors"] for s in suites), "skipped": sum(s["skipped"] for s in suites)}

try:
    with tempfile.TemporaryDirectory(prefix="c1-read-contract-full-tree-") as temp:
        tree = Path(temp) / "tree"
        # 소스와 테스트는 전부 복사. 의존성 설치 폴더/로컬 Git 메타데이터만 제외.
        shutil.copytree(root, tree, ignore=shutil.ignore_patterns(".git", ".gradle", "node_modules"))
        require(all((tree / p).is_file() for p in tracked), "전체 트리 사본 tracked 파일 누락")
        (tree / board).write_bytes(board_red)
        (tree / troop).write_bytes(troop_red)
        record["red"] = run_tests(tree, "red", api, [chain, f4])
        red = record["red"]
        cases = {case for suite in red["suites"] for case in suite["failed_cases"]}
        require(red["exit_code"] != 0 and red["tests"] == 31 and red["failures"] == 3 and red["errors"] == 0 and red["skipped"] == 0,
                "적색 실제 31건/실패3/error0/skip0 불일치")
        require(cases == expected_failures, "적색 실패 사례 불일치")
        for rel, data in source.items():
            (tree / rel).write_bytes(data)
            require(sha((tree / rel).read_bytes()) == sha(data), "원복 바이트 불일치")
        focused = [chain, f4, "opensamguk.gameapi.controller.FrontInfoControllerTest", "opensamguk.gameapi.controller.WorldMapControllerTest",
                   "opensamguk.gameapi.web.CityDetailControllerTest", "opensamguk.gameapi.web.ReservedCommandsControllerTest"]
        record["restored_api"] = run_tests(tree, "restored-api", api, focused)
        green = record["restored_api"]
        require(green["exit_code"] == 0 and green["tests"] == 81 and all(green[k] == 0 for k in ["failures", "errors", "skipped"]), "원복 API 불일치")
        record["hotcold"] = run_tests(tree, "hotcold", "app/game-engine", ["*HotCold*"])
        hot = record["hotcold"]
        require(hot["exit_code"] == 0 and hot["tests"] == 11 and all(hot[k] == 0 for k in ["failures", "errors", "skipped"]), "HotCold 불일치")
        require(all(sha((tree / p).read_bytes()) == value for p, value in source_manifest.items()), "원복 후 JVM 소스 지문 불일치")
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
