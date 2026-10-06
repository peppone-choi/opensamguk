#!/usr/bin/env python3
"""Read-only A04 proof gate. Needs actual native XML and receipt, never runs the fixture."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

TARGET = "opensamguk.gameapi.compatibility.D119PopulatedV69CompatibilityIT"
OLD_SOURCE = "d50177b207897fc6c466095ec9699aab03f57536"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def production_path(path):
    return (
        path.startswith(("common/", "logic/", "infra/", "app/"))
        and ("/src/main/" in path or path.endswith((".gradle.kts", ".gradle", "/gradle.properties")))
    ) or path.startswith(("data/", "assets/", "gradle/")) or path in {
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"
    }


def production_digest(repo, ref):
    require(re.fullmatch(r"[a-f0-9]{40}", ref), "exact expected candidate SHA required")
    raw = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "ls-tree", "-r", "--full-tree", ref], cwd=repo, text=True
    )
    rows = [line for line in raw.splitlines() if production_path(line.partition("\t")[2])]
    require(rows, "expected candidate production tree is empty")
    return hashlib.sha256("\n".join(rows).encode()).hexdigest()


def verify(xml, receipt, expected_digest, contract):
    suite = ET.parse(xml).getroot()
    require(suite.tag == "testsuite" and suite.get("name") == TARGET, "native target XML required")
    tests = int(suite.get("tests", "0"))
    require(tests > 0 and len(suite.findall("testcase")) == tests, "positive native target test count required")
    for name in ("failures", "errors", "skipped"):
        require(int(suite.get(name, "-1")) == 0, f"target {name} must be exactly zero")
    require(not suite.findall(".//failure") and not suite.findall(".//error") and not suite.findall(".//skipped"),
            "native testcases contain a failure/error/skip")
    output = suite.findtext("system-out", "")
    embedded = [json.loads(line.split("D119_COMPATIBILITY_RECEIPT ", 1)[1])
                for line in output.splitlines() if line.startswith("D119_COMPATIBILITY_RECEIPT ")]
    embedded = [item for item in embedded if item.get("status") == "A04_CONTROLLED_POPULATED_VERIFIED"]
    require(len(embedded) == 1, "exactly one successful receipt in native target XML required")
    r = json.loads(Path(receipt).read_text()) if receipt is not None else embedded[0]
    require(all(embedded[0].get(k) == v for k, v in r.items()),
            "receipt does not match this native target XML")
    require("D119_OWNED_CLEANUP_COMPLETE " + str(embedded[0].get("runId")) in output,
            "owned cleanup receipt missing from target XML")
    require(r.get("status") == "A04_CONTROLLED_POPULATED_VERIFIED", "successful controlled receipt required")
    require(r.get("caseKind") == "CONTROLLED_POPULATED_OLD_V69", "controlled case must be explicit")
    require(r.get("oldSourceSha") == OLD_SOURCE, "immutable old source mismatch")
    require(r.get("productionSourceTreeSha256") == expected_digest, "final candidate production sources differ")
    require(r.get("candidateProductionIdentity") == "EXACT_MATCH", "runtime candidate identity failed")
    for name in ("candidateSourceSha", "ciCheckoutMergeRef", "proofImplementationSourceSha", "oldSourceTree"):
        require(re.fullmatch(r"[a-f0-9]{40}", str(r.get(name, ""))), f"exact {name} missing")
    for name in ("oldRuntimeJarSha256", "oldSourceManifestSha256", "helperSha256", "candidateApplicationClassSha256"):
        require(re.fullmatch(r"[a-f0-9]{64}", str(r.get(name, ""))), f"exact {name} missing")
    require(r.get("oldRuntimeJarBytes", 0) > 0, "old runtime bytes missing")
    require(r.get("contractSha256") == hashlib.sha256(Path(contract).read_bytes()).hexdigest(), "contract bytes differ")
    source_manifest = Path(contract).with_name("source-manifest.json")
    m = json.loads(source_manifest.read_text())
    require(r.get("oldSourceManifestSha256") == hashlib.sha256(source_manifest.read_bytes()).hexdigest(), "immutable source manifest differs")
    require(r.get("helperSha256") == hashlib.sha256(Path(contract).with_name("D119PopulatedReservationSeed.java").read_bytes()).hexdigest(),
            "controlled helper bytes differ")
    require(r.get("topologyHash") == m["topologyHash"] and r.get("baseTilesSha256") == m["tilesSha256"], "topology/tiles pins differ")
    require(r.get("operatingAccess") is False and r.get("productFilterBypass") is False, "boundary violation")
    require(r.get("immutableEmptyRows") == 0 and r.get("actualReservationCount") == 2, "controlled seed counts differ")
    require(r.get("actualCountBefore") == 2 and r.get("actualCountAfter") == 2, "reservation counts differ")
    require(r.get("oldProducer", {}).get("actualCount") == 2, "old producer count missing")
    require(r["oldProducer"].get("producerMethod", "").startswith("reserve-") and
            "/classpath/" in r["oldProducer"].get("producerClassOrigin", ""), "old classpath producer origin missing")
    require(r.get("before") and r["before"] == r.get("after"), "full schema/row fingerprint differs")
    require(r.get("controlledPopulatedFingerprint") == r["before"], "boot baseline differs from controlled seed")
    empty = r.get("immutableEmptyFingerprint", {})
    require(empty and {k: v for k, v in empty.items() if k != "general_turn"} ==
            {k: v for k, v in r["before"].items() if k != "general_turn"}, "controlled seed changed unrelated schema/rows")
    rows = r.get("reservationRowsBefore", [])
    require(len(rows) == 2 and rows == r.get("reservationRowsAfter") == r.get("reservationRows"), "reserved rows differ")
    c = json.loads(Path(contract).read_text())
    for row, expected in zip(rows, c["reservations"]):
        require(row.get("world_id") == 1 and row.get("general_id") == 1001, "reservation identities differ")
        require(row.get("turn_idx") == expected["slot"] and row.get("action_code") == expected["actionCode"]
                and row.get("arg") == expected["arg"] and row.get("brief") == expected["brief"], "fixed reservation inputs differ")
        require(row.get("request_id") is None, "unexpected authenticated request binding")
    changed = copy.deepcopy(rows)
    mutation = c["rollbackMutation"]
    require(changed[0]["turn_idx"] == mutation["slot"], "rollback mutation slot differs")
    changed[0].update(action_code=mutation["actionCode"], arg=mutation["arg"], brief=mutation["brief"])
    require(changed == r.get("reservationRowsDuringFlush") and
            r.get("reservationSlotWrite") == "WRITE_READ_ROLLBACK_VERIFIED", "actual reservation slot write/read proof missing")
    require(r.get("flywayVersions") == list(range(1, 70)), "old V1..69 history changed")
    require(r.get("jpaReservationRead") == "VERIFIED" and r.get("jdbcFlush") == "WRITE_READ_ROLLBACK_VERIFIED", "read/flush proof missing")
    require(r.get("privateHttp") == 401 and r.get("admissionSourceStopped") == 503, "filter boundary proof missing")
    require(r.get("actualGatewayPublication") == "UNVERIFIED" and r.get("engineReservationExecution") == "UNVERIFIED",
            "controlled fixture must not claim real gateway or engine execution")
    return {"target": TARGET, "tests": tests, "failures": 0, "errors": 0, "skipped": 0,
            "ciCheckoutMergeRef": r["ciCheckoutMergeRef"], "candidateSourceSha": r["candidateSourceSha"],
            "productionSourceTreeSha256": expected_digest, "actualCountBefore": 2, "actualCountAfter": 2}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--xml", type=Path, required=True)
    ap.add_argument("--receipt", type=Path, help="optional separate receipt; otherwise use native XML system-out")
    ap.add_argument("--expected-candidate", required=True)
    ap.add_argument("--repo-root", type=Path, default=Path("."))
    ap.add_argument("--contract", type=Path, default=Path(
        "app/game-api/src/test/resources/compatibility/d119-v69/populated-contract.json"))
    args = ap.parse_args()
    if not args.contract.is_absolute():
        args.contract = args.repo_root / args.contract
    result = verify(args.xml, args.receipt, production_digest(args.repo_root, args.expected_candidate), args.contract)
    print(json.dumps({"status": "A04_CONTROLLED_PROOF_ACCEPTED", "expectedCandidate": args.expected_candidate, **result}))


if __name__ == "__main__":
    main()
