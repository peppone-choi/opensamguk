"""Static QA lower bounds and separately identified execution receipts."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from .schema import SHA, json_data, safe_path
from .surface import expanded_inputs


def required_lanes(impact):
    lanes = {"jvm": False, "web": False, "contracts": False}
    if impact["result"] in {"BROAD", "UNRESOLVED"}:
        return dict.fromkeys(lanes, True)
    paths = [p["path"] for p in impact.get("paths", [])]
    lanes["jvm"] = impact.get("sammo", False) or any(
        p.startswith(("app/", "logic/", "common/", "infra/")) for p in paths) or bool(
            impact["inputIds"] and not any(p.startswith("web/") for p in paths))
    lanes["web"] = bool(impact["inputIds"] and any(p.startswith("web/") for p in paths))
    lanes["contracts"] = any(p.startswith("docs/user/") for p in paths)
    lanes["web"] |= lanes["contracts"]
    return lanes


def strip_comments(text):
    token = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|//[^\n]*|/\*[\s\S]*?\*/')
    return token.sub(lambda m: " " * len(m[0]) if m[0].startswith(("//", "/*")) else m[0], text)


def test_blocks(text, kind):
    text = strip_comments(text)
    quoted = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27'
    if kind == "typescript":
        quoted += r"|\x60(?:\\.|[^\x60\\])*\x60"
    code = re.sub(quoted, lambda m: " " * len(m[0]), text)
    opening = (r"@Test\b(?:\s*@[\w.]+(?:\([^)]*\))?)*\s+(?:suspend\s+)?fun\s+"
               r"(?:\x60[^\x60]+\x60|\w+)\s*\([^)]*\)\s*(?::[\w<>?]+\s*)?\{"
               if kind == "kotlin" else
               r"\b(?:test|it)(?!\.(?:skip|fixme))\s*\(\s*['\x22][^'\x22]*['\x22]\s*,"
               r"\s*(?:async\s*)?\([^)]*\)\s*=>\s*\{")
    result = []
    for match in re.finditer(opening, text):
        if not code[match.start():match.start() + 4].strip():
            continue
        start, depth = match.end(), 1
        tokens = re.finditer(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|\x60(?:\\.|[^\x60\\])*\x60|[{}]',
                            text[start:])
        for token in tokens:
            if token[0] == "{":
                depth += 1
            elif token[0] == "}":
                depth -= 1
            if not depth:
                result.append(text[start:start + token.start()])
                break
    return result


def static_reference(tree, input_id, ref):
    path = safe_path(ref.get("path", ""))
    text = tree.text(path, "") or ""
    role = ref.get("role")
    if role == "handler-test":
        if "/src/test/" not in path or not path.endswith(".kt"):
            raise ValueError("QA_KOTLIN_TEST_PATH")
        package = re.search(r"^package\s+([\w.]+)", strip_comments(text), re.M)
        classes = re.findall(r"\bclass\s+(\w+)", strip_comments(text))
        if not package or ref.get("class") not in {package[1] + "." + c for c in classes}:
            raise ValueError("QA_CLASS_MISSING")
        blocks = test_blocks(text, "kotlin")
    elif role == "ui-e2e":
        if "/e2e/" not in path or not path.endswith((".spec.ts", ".spec.tsx")):
            raise ValueError("QA_E2E_PATH")
        blocks = test_blocks(text, "typescript")
    elif role == "help-topic":
        if not path.startswith("docs/user/") or input_id not in strip_comments(text):
            raise ValueError("QA_HELP_TOPIC")
        return
    else:
        raise ValueError("QA_ROLE_UNSUPPORTED")
    literal = re.compile(r'["\x27]' + re.escape(input_id) + r'["\x27]')
    if any(literal.search(block) for block in blocks):
        return
    sources = [(path, text)]
    resolved = {}
    if role == "handler-test":
        identifiers = set(re.findall(r"\b(\w+)\.(INPUT_IDS?)\b", "\n".join(blocks)))
        for owner, member in identifiers:
            for source in tree.entries("logic/src/main").keys():
                if source.endswith("/" + owner + ".kt"):
                    sources.append((source, tree.text(source, "") or ""))
                    resolved[source] = owner + "." + member
    for source_path, source in sources:
        source = strip_comments(source)
        for member in ("INPUT_ID", "INPUT_IDS"):
            declaration = re.search(r"\b" + member + r"\s*=\s*([^\n;]+)", source)
            if declaration and literal.search(declaration[1]):
                names = [member] if source_path == path else [resolved[source_path]]
                if any(re.search(r"\b" + re.escape(name) + r"\b", block)
                       for name in names for block in blocks):
                    return
    raise ValueError("QA_ID_NOT_IN_TEST")


def validate_qa(tree, manifest, impact):
    reasons, plan = [], []
    catalog = tree.json("data/commands/input-catalog.json") or {}
    rows = {r["inputId"]: r for r in catalog.get("inputs", [])}
    paths = [r["path"] for r in impact.get("paths", [])]
    for input_id in expanded_inputs(impact, catalog):
        row = rows.get(input_id)
        if not row:
            reasons.append("QA_INPUT_UNKNOWN:" + input_id)
            continue
        if impact["result"] == "BROAD":
            plan.append({"inputId": input_id, "role": "existing-evidence-gate"})
            continue
        if row["deliveryState"] == "PLANNED":
            if manifest["qaNa"].get(input_id) != "PLANNED_NOT_DELIVERED":
                reasons.append("QA_PLANNED_REASON:" + input_id)
            if input_id in registered_handler_ids(tree):
                reasons.append("PLANNED_HANDLER_PRESENT:" + input_id)
            plan.append({"inputId": input_id, "role": "planned-not-delivered"})
            continue
        required = set()
        if any(p.startswith("web/") for p in paths):
            required.add("ui-e2e")
        if any(p.startswith("docs/user/") for p in paths):
            required.add("help-topic")
        if any(p.startswith(("app/", "logic/", "common/", "infra/")) for p in paths) or not required:
            required.add("handler-test")
        refs = manifest["qa"].get(input_id, [])
        if not isinstance(refs, list):
            reasons.append("QA_REFS_SCHEMA:" + input_id)
            continue
        for role in sorted(required):
            chosen = [r for r in refs if isinstance(r, dict) and r.get("role") == role]
            if not chosen:
                reasons.append(f"QA_ROLE_MISSING:{input_id}:{role}")
            for ref in chosen:
                try:
                    static_reference(tree, input_id, ref)
                    plan.append(dict(ref, inputId=input_id))
                except ValueError as exc:
                    reasons.append(f"{exc}:{input_id}")
    return plan, reasons


def registered_handler_ids(tree):
    """Literal and named-constant registration keys; never execute dispatch."""
    registered, symbols = set(), set()
    source_paths = list(tree.entries("app/game-engine/src/main"))
    for path in source_paths:
        if not path.endswith(".kt"):
            continue
        source = strip_comments(tree.text(path, "") or "")
        registered.update(re.findall(r'["\x27]([a-z]+\.[A-Za-z0-9]+)["\x27]\s+to\s+', source))
        symbols.update(re.findall(r"\b(\w+)\.([A-Z][A-Z0-9_]+)\s+(?:to\b)", source))
        symbols.update(re.findall(r"\b(\w+)\.(INPUT_IDS?)\.associateWith\b", source))
    for owner, member in symbols:
        for path in list(tree.entries("logic/src/main")) + source_paths:
            if not path.endswith("/" + owner + ".kt"):
                continue
            source = strip_comments(tree.text(path, "") or "")
            declaration = re.search(r"\b" + re.escape(member) +
                r"(?:\s*:\s*[\w<>?, .]+)?\s*=\s*((?:setOf|listOf)\([^)]*\)|[^\n;]+)", source)
            if declaration:
                registered.update(re.findall(r'["\x27]([a-z]+\.[A-Za-z0-9]+)["\x27]', declaration[1]))
    return registered


def executed_tests(paths, *, head, lane, run_id, attempt):
    if not SHA.fullmatch(head):
        raise ValueError("EXECUTION_HEAD")
    cases = []
    for root in paths:
        root = Path(root)
        files = [root] if root.is_file() else sorted(root.rglob("TEST-*.xml"))
        for path in files:
            if path.is_symlink():
                raise ValueError("UNSAFE_EXECUTION_FILE")
            data = path.read_bytes()
            if len(data) > 20 * 1024 * 1024 or b"<!DOCTYPE" in data or b"<!ENTITY" in data:
                raise ValueError("UNSAFE_EXECUTION_XML")
            xml = ET.fromstring(data)
            for case in xml.iter("testcase"):
                cases.append({"class": case.get("classname", ""), "name": case.get("name", ""),
                              "status": "skipped" if case.find("skipped") is not None else
                              "failure" if case.find("failure") is not None or case.find("error") is not None else "passed"})
    return {"schema": "wu-executed/1", "headSha": head, "lane": lane, "runId": str(run_id),
            "attempt": int(attempt), "cases": cases, "tests": len(cases), "source": "JUNIT_XML",
            "producer": "game-engine" if all("game-engine" in str(p) for p in paths) else "jvm-core"}


def verify_executed(receipt, executions, *, lane, run_id=None):
    reasons = []
    if receipt["status"] == "BOOTSTRAP_NOT_ENFORCED":
        return {"status": "BOOTSTRAP_NOT_ENFORCED", "reasons": receipt["reasons"]}
    lanes = ["jvm", "web"] if lane == "all" else [lane]
    for required in lanes:
        if not receipt.get("requiredLanes", {}).get(required):
            continue
        selected = [e for e in executions if e.get("lane") == required]
        if required == "jvm":
            newest = {}
            for execution in selected:
                producer = execution.get("producer", "")
                if producer not in newest or execution["attempt"] > newest[producer]["attempt"]:
                    newest[producer] = execution
            selected = list(newest.values())
            if not {"jvm-core", "game-engine"} <= set(newest):
                reasons.append("EXECUTION_PRODUCER_MISSING:jvm")
        if not selected:
            reasons.append("EXECUTION_MISSING:" + required)
        cases = []
        for execution in selected:
            if execution.get("headSha") != receipt["headSha"] or (
                    run_id is not None and execution.get("runId") != str(run_id)):
                reasons.append("EXECUTION_IDENTITY:" + required)
            if not execution.get("tests") or not execution.get("cases"):
                reasons.append("EXECUTION_TESTS_ZERO:" + required)
            cases += execution.get("cases", [])
            if any(c.get("status") != "passed" for c in execution.get("cases", [])):
                reasons.append("EXECUTION_SKIP_OR_FAILURE:" + required)
        for ref in receipt.get("qaPlan", []):
            if ref.get("role") == "handler-test" and required == "jvm" and not any(
                    c.get("class") == ref["class"] and c.get("status") == "passed" for c in cases):
                reasons.append("EXECUTION_CLASS_MISSING:" + ref["class"])
            if ref.get("role") == "ui-e2e" and required == "web" and not any(
                    c.get("spec", "").endswith(ref["path"].split("/e2e/", 1)[1]) and
                    c.get("status") == "passed" for c in cases):
                reasons.append("EXECUTION_SPEC_MISSING:" + ref["path"])
    return {"status": "FAIL" if reasons else "PASS", "reasons": reasons}


def browser_execution(root, *, head, run_id, attempt):
    """Read the verified union, retaining original shard receipt provenance."""
    cases = []
    provenance = []
    for phase in ("smoke", "topdown-screens"):
        directory = Path(root) / phase
        phase_file = directory / "phase.json"
        if phase_file.is_symlink():
            raise ValueError("UNSAFE_EXECUTION_FILE")
        record = json_data(phase_file.read_bytes())
        if any(record.get(k) != v for k, v in {
            "schema": "web-e2e-shard-aggregate-v1", "app": "game", "headSha": head,
            "runId": str(run_id), "runAttempt": str(attempt), "phase": phase}.items()):
            raise ValueError("BROWSER_EXECUTION_IDENTITY")
        provenance.append(record)
        path = directory / "results.json"
        if path.is_symlink():
            raise ValueError("UNSAFE_EXECUTION_FILE")
        data = json_data(path.read_bytes(), max_size=50 * 1024 * 1024)
        if not isinstance(data, dict) or "suites" not in data:
            raise ValueError("BROWSER_EXECUTION_REPORT")
        if data.get("errors"):
            raise ValueError("BROWSER_EXECUTION_ERRORS")
        def visit(suite, inherited=""):
            filename = suite.get("file", inherited)
            for spec in suite.get("specs", []):
                for test in spec.get("tests", []):
                    results = test.get("results", [])
                    passed = (test.get("status") == "expected" and len(results) == 1 and
                              results[0].get("status") == "passed" and results[0].get("retry", 0) == 0)
                    cases.append({"spec": spec.get("file", filename), "name": spec.get("title", ""),
                                  "project": test.get("projectName", ""),
                                  "status": "passed" if passed else "skipped" if test.get("status") == "skipped" else "failure"})
            for child in suite.get("suites", []):
                visit(child, filename)
        for suite in data["suites"]:
            visit(suite)
    return {"schema": "wu-executed/1", "headSha": head, "lane": "web", "runId": str(run_id),
            "attempt": int(attempt), "cases": cases, "tests": len(cases),
            "source": "VERIFIED_ORIGINAL_SHARD_UNION", "provenance": provenance,
            "evidenceGrade": "UI_ROUTE_MOCK_OR_BROWSER"}
