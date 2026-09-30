from pathlib import Path
import argparse, datetime, hashlib, json, os, shutil, subprocess, tempfile, time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description="C1 서신 권한의 전체 트리 적색/복원 검증")
parser.add_argument("--root", type=Path, required=True)
parser.add_argument("--evidence", type=Path, required=True)
parser.add_argument("--execute", action="store_true")
a = parser.parse_args()
root = a.root.resolve()
evidence = a.evidence.resolve()
api = "app/game-api"
controller = api + "/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt"
security = api + "/src/main/kotlin/opensamguk/gameapi/security/GameApiSecurityConfig.kt"
chain = "opensamguk.gameapi.security.MailboxSecurityChainTest"
expected_failures = {'nationless general has no national mailbox and unknown message returns 404 after authentication()', 'sender dest and body targets cannot grant access to a foreign receiving mailbox()', 'security chain blocks anonymous and invalid bearer before reading any mailbox()', 'foreign private and national IDs remain forbidden even with a forged general query()'}
def sha(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

source = {rel: (root / rel).read_bytes() for rel in [controller, security]}
ctext = source[controller].decode()
cstart = ctext.index("    private fun canReadMailbox(")
cend = ctext.index("    /** D6/D7/D8", cstart)
controller_red = (ctext[:cstart] + "    private fun canReadMailbox(me: GeneralReadEntity, mailbox: Int): Boolean = true\n\n" + ctext[cend:]).encode()
stext = source[security].decode()
matcher = '                    .requestMatchers("/api/mailbox/**", "/api/messages/**").authenticated()\n'
require(stext.count(matcher) == 1, "서신 체인 인증 변이 원천이 바뀜")
security_red = stext.replace(matcher, "").encode()
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
    with tempfile.TemporaryDirectory(prefix="c1-mailbox-full-tree-") as temp:
        tree = Path(temp) / "tree"
        # 소스와 테스트는 전부 복사. 의존성 설치 폴더/로컬 Git 메타데이터만 제외.
        shutil.copytree(root, tree, ignore=shutil.ignore_patterns(".git", ".gradle", "node_modules"))
        require(all((tree / p).is_file() for p in tracked), "전체 트리 사본 tracked 파일 누락")
        (tree / controller).write_bytes(controller_red)
        (tree / security).write_bytes(security_red)
        record["red"] = run_tests(tree, "red", api, [chain])
        red = record["red"]
        cases = {case for suite in red["suites"] for case in suite["failed_cases"]}
        require(red["exit_code"] != 0 and red["tests"] == 7 and red["failures"] == 4 and red["errors"] == 0 and red["skipped"] == 0,
                "적색 실제 7건/실패4/error0/skip0 불일치")
        require(cases == expected_failures, "적색 실패 사례 불일치")
        for rel, data in source.items():
            (tree / rel).write_bytes(data)
            require(sha((tree / rel).read_bytes()) == sha(data), "원복 바이트 불일치")
        focused = [chain, "opensamguk.gameapi.controller.MailboxControllerTest", "opensamguk.gameapi.security.CommandMutationSecurityChainTest"]
        record["restored_api"] = run_tests(tree, "restored-api", api, focused)
        green = record["restored_api"]
        require(green["exit_code"] == 0 and green["tests"] == 23 and all(green[k] == 0 for k in ["failures", "errors", "skipped"]), "원복 API 불일치")
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
