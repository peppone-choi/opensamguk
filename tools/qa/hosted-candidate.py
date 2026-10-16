#!/usr/bin/env python3
"""Trusted-main, one-job candidate QA. No production secrets or remote hosts."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import runpy
import signal
import subprocess
import time
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
STACK = runpy.run_path(str(HERE / "local-stack.py"))
ROLE = runpy.run_path(str(HERE / "role-fixtures.py"))
GATE = runpy.run_path(str(HERE.parent / "ci/wait_for_pr_ci.py"))
REPO = "peppone-choi/opensamguk"


def gh(path, binary=False):
    value = subprocess.check_output(["gh", "api", "repos/" + REPO + "/" + path], stderr=subprocess.DEVNULL)
    return value if binary else json.loads(value)


def safe_json(path, value):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Symlink output refused")
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def verify_run(run, jobs, artifact, row, run_id):
    if (str(run.get("id")) != run_id or run.get("event") != "workflow_dispatch"
            or run.get("head_branch") != "main"
            or run.get("path") != ".github/workflows/build-pr-candidate-qa-images.yml"
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("repository", {}).get("full_name") != REPO
            or run.get("head_repository", {}).get("full_name") != REPO
            or run.get("run_attempt") != 1):
        raise ValueError("Original successful trusted-main image run attempt required")
    expected = {"images (" + s + ")" for s in STACK["SERVICES"]} | {"receipt"}
    if (len(jobs) != 7 or {j.get("name") for j in jobs} != expected
            or any(j.get("conclusion") != "success" or j.get("status") != "completed"
                   or j.get("run_attempt") != 1 or j.get("head_sha") != run["head_sha"] for j in jobs)):
        raise ValueError("All six original image jobs and merged receipt must succeed")
    origin = artifact.get("workflow_run", {})
    if (artifact.get("expired") is not False or str(origin.get("id")) != run_id
            or origin.get("head_sha") != run["head_sha"] or origin.get("head_branch") != "main"
            or origin.get("repository_id") != run["repository"]["id"]
            or origin.get("head_repository_id") != run["repository"]["id"]
            or row.get("baseSha") != run["head_sha"]):
        raise ValueError("Artifact provenance mismatch")


def download_receipt(run_id, sha, number, out):
    if not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", number):
        raise ValueError("Positive run and PR required")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Exact source required")
    run = gh("actions/runs/" + run_id)
    jobs = gh("actions/runs/" + run_id + "/jobs?per_page=100")["jobs"]
    artifacts = gh("actions/runs/" + run_id + "/artifacts?per_page=100")["artifacts"]

    def read(name):
        matches = [a for a in artifacts if a["name"] == name]
        if len(matches) != 1:
            raise ValueError("Unique unexpired original receipt required")
        artifact = matches[0]
        if artifact["expired"] or artifact["size_in_bytes"] > 65536:
            raise ValueError("Invalid receipt artifact size or lifetime")
        raw = gh("actions/artifacts/" + str(artifact["id"]) + "/zip", binary=True)
        if artifact.get("digest") != "sha256:" + hashlib.sha256(raw).hexdigest():
            raise ValueError("Artifact SHA256 mismatch")
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if archive.namelist() != ["candidate-receipt.json"] or archive.infolist()[0].file_size > 32768:
                raise ValueError("Receipt archive allowlist mismatch")
            value = json.loads(archive.read("candidate-receipt.json"))
        verify_run(run, jobs, artifact, value, run_id)
        return value

    row = read("candidate-receipt")
    if set(row) != {"pr", "sourceSha", "baseSha", "ciRunId", "runId", "channel", "digests"}:
        raise ValueError("Receipt metadata allowlist mismatch")
    with tempfile.TemporaryDirectory(prefix="qa-receipt-check-") as temporary:
        private = Path(temporary) / "receipt.json"
        safe_json(private, row)
        STACK["candidate_receipt"](private, sha, run_id, number)
    for surface in STACK["SERVICES"]:
        single = read("candidate-receipt-" + surface)
        if (set(single.get("digests", {})) != {surface}
                or single["digests"][surface] != row["digests"][surface]
                or {k:v for k,v in single.items() if k != "digests"} != {k:v for k,v in row.items() if k != "digests"}):
            raise ValueError("Merged/surface receipt disagreement")
    # Current exact PR/CI must remain eligible; image receipt retains build-time base.
    query = "actions/workflows/ci.yml/runs?event=pull_request&head_sha=" + sha + "&per_page=100"
    current_ci = GATE["eligible"](gh("pulls/" + number), gh(query)["workflow_runs"], REPO, sha)
    original_ci = gh("actions/runs/" + str(row["ciRunId"]))
    if (original_ci.get("path") != ".github/workflows/ci.yml" or original_ci.get("event") != "pull_request"
            or original_ci.get("head_sha") != sha or original_ci.get("conclusion") != "success"
            or original_ci.get("status") != "completed"
            or original_ci.get("head_repository", {}).get("full_name") != REPO
            or not any(p["number"] == int(number) for p in original_ci.get("pull_requests", []))):
        raise ValueError("Receipt exact CI is no longer successful")
    subprocess.run(["git", "fetch", "origin", "main"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["git", "fetch", "origin", "refs/pull/" + number + "/head"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", "origin/main..." + sha]).decode().split("\0")
    GATE["verify_paths"](paths)
    GATE["eligible"](gh("pulls/" + number), gh(query)["workflow_runs"], REPO, sha)
    safe_json(out, row)
    return dict(row, currentCiRunId=current_ci)


def env_values(directory):
    values = {}
    for line in (directory / "qa.env").read_text().splitlines():
        match = re.fullmatch(r"([A-Z_]+)='([^'\n]*)'", line)
        if match:
            values[match[1]] = match[2]
    return values


def bootstrap(directory):
    directory = STACK["custody"](directory)
    row = {"username": "qa160admin", "password": env_values(directory)["QA_ADMIN_PASSWORD"]}
    _, result = ROLE["api"]("/api/gateway/auth/login", row)
    token = result["accessToken"]
    _, me = ROLE["api"]("/api/gateway/auth/me", token=token)
    if me.get("role") != "ADMIN" or me.get("id") != result["user"]["id"]:
        raise ValueError("Separate ADMIN normal login failed")
    row["userId"] = me["id"]
    STACK["private_json"](directory / "admin-account.json", row)
    for scope in ("allow_join", "allow_login"):
        ROLE["api"]("/api/gateway/admin/system/" + scope, {"value": True}, token)
    _, policy = ROLE["api"]("/api/gateway/admin/users", token=token)
    if policy.get("allowJoin") is not True or policy.get("allowLogin") is not True:
        raise ValueError("ADMIN policy readback failed")


def fixture_plan(directory):
    # A spatial witness in an actual owned county, plus >=2 distinct transferable
    # same-nation NPC retainers (or seeded rulers inserted by the existing fixture).
    rows = json.loads(STACK["sql"](directory, "game", """
        SELECT coalesce(json_agg(row_to_json(x)), '[]') FROM (
          SELECT DISTINCT ON (c.nation_id) c.nation_id AS nation, c.id AS city, g.id AS sample
          FROM city c JOIN general g ON g.world_id=c.world_id AND g.city_id=c.id
          JOIN general_spatial_position p ON p.world_id=g.world_id AND p.general_id=g.id
          WHERE c.world_id=160 AND c.nation_id>0 AND g.npc_state>=2
          AND (SELECT count(DISTINCT n.id) FROM general n WHERE n.world_id=160
               AND n.nation_id=c.nation_id AND n.npc_state>=2 AND n.id<>g.id
               AND (n.officer_level=12 OR EXISTS (SELECT 1 FROM general_retainers r
                    WHERE r.world_id=160 AND r.general_id=n.id)))>=2
          ORDER BY c.nation_id,c.id,g.id LIMIT 3
        ) x;"""))
    if len(rows) != 3:
        raise ValueError("Three actual seeded nation/city/position/NPC graphs unavailable")
    return {"nationIds": [r["nation"] for r in rows], "cityIds": [r["city"] for r in rows],
            "positionGeneralIds": [r["sample"] for r in rows]}


def role_graph(directory):
    ROLE["verify"](directory)
    if STACK["sql"](directory, "game", "SELECT meta->>'qa160RoleFixture' FROM world_state WHERE id=160;") != "1":
        raise ValueError("Role fixture not applied")
    rows = ROLE["credentials"](directory)
    people = {p["id"]: p for p in ROLE["general_rows"](directory, rows)}
    result = []
    for row in rows:
        person = people[row["generalId"]]
        gid = int(row["generalId"])
        public = {k:row[k] for k in ("nation", "role", "userId", "generalId")}
        public.update(nationId=person["nationId"], cityId=person["cityId"], accountRole="USER")
        if row["nation"] != "free":
            lord = next(r["generalId"] for r in rows if r["nation"] == row["nation"] and r["role"] == "lord")
            binding = STACK["sql"](directory, "game", f"SELECT meta->'currentRulerBinding'->>'generalId' FROM nation WHERE world_id=160 AND id={int(person['nationId'])};")
            if int(binding) != lord:
                raise ValueError("Actual ruler binding mismatch")
            if row["role"] != "lord":
                count = STACK["sql"](directory, "game", f"SELECT count(*) FROM general_retainers WHERE world_id=160 AND master_general_id={lord} AND general_id={gid};")
                if int(count) != 1:
                    raise ValueError("Human must be directly bound to actual lord")
            if row["role"] in ("lord", "manager"):
                count = STACK["sql"](directory, "game", f"SELECT count(*) FROM general_bugok WHERE world_id=160 AND master_general_id={gid};")
                if int(count) < 1:
                    raise ValueError("Actual bugok missing")
            if row["role"] == "manager":
                followers = json.loads(STACK["sql"](directory, "game", f"SELECT coalesce(json_agg(g.id),'[]') FROM general_retainers r JOIN general g ON g.world_id=r.world_id AND g.id=r.general_id WHERE r.world_id=160 AND r.master_general_id={gid} AND g.nation_id={int(person['nationId'])} AND g.npc_state>=2;"))
                if len(followers) < 2:
                    raise ValueError("Actual secondary NPC graph missing")
                public["secondaryNpcGeneralIds"] = followers
        result.append(public)
    return {"serverId": "qa160", "worldId": 160, "generation": 0, "fixtureVersion": 1, "users": result}


def snapshot(directory, gid, request_id=None):
    if not re.fullmatch(r"[1-9][0-9]*", str(gid)):
        raise ValueError("Positive actor required")
    # Read-only persisted witnesses; never return log text, auth data or arbitrary JSON.
    request = ""
    if request_id is not None:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", request_id):
            raise ValueError("Invalid request identity")
        request = f""", 'reservation', (SELECT row_to_json(r) FROM (
             SELECT turn_idx AS slot, action_code AS action, reservation_revision AS revision
             FROM general_turn WHERE world_id=160 AND general_id={gid} AND request_id='{request_id}') r),
            'logCount',(SELECT count(*) FROM log_entry WHERE world_id=160 AND general_id={gid} AND meta->'refs'->>'requestId'='{request_id}'),
            'terminal', (SELECT row_to_json(t) FROM (
             SELECT terminal_status AS status, ok, committed_world_version AS version
             FROM command_result WHERE world_id=160 AND request_id='{request_id}' ORDER BY result_seq DESC LIMIT 1) t)"""
    return json.loads(STACK["sql"](directory, "game", f"""
        SELECT json_build_object('generalId',g.id,'cityId',g.city_id,
          'experience',g.experience,'dedication',g.dedication,'turnTime',g.turn_time,
          'personal',json_build_object('requestId',g.meta->'personalLastTurn'->>'requestId',
             'inputId',g.meta->'personalLastTurn'->>'inputId'),
          'world', (SELECT json_build_object('year',current_year,'month',current_month,'phase',current_phase,
              'lastTurnTime',meta->>'lastTurnTime') FROM world_state WHERE id=160)
          {request}) FROM general g WHERE g.world_id=160 AND g.id={gid};"""))



def snapshot_command(args):
    """Preserve custody/owner checks; only fixed failure codes cross stdout."""
    def failed(code):
        print(json.dumps({"error": code}))
        return 1

    try:
        directory = STACK["custody"](args.custody)
    except Exception:
        return failed("CUSTODY")
    try:
        owner = STACK["hosted_owner"]()
        if directory != Path(owner["directory"]) / "custody":
            raise ValueError("Foreign snapshot custody")
    except Exception:
        return failed("OWNER")
    if (not re.fullmatch(r"[1-9][0-9]*", str(args.general_id))
            or (args.request_id is not None
                and not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.request_id))):
        return failed("INVALID_ARG")
    try:
        value = snapshot(directory, args.general_id, args.request_id)
    except Exception:
        return failed("SQL_FAILED")
    print(json.dumps(value))
    return 0


def interrupt(signum, frame):
    raise InterruptedError("Hosted QA interrupted")


def live(directory):
    owner = STACK["hosted_owner"]()
    env = dict(os.environ, E2E_QA_CUSTODY=str(directory), QA_PROCESS_NONCE=owner["nonce"],
               E2E_QA_PUBLIC=str(Path(os.environ["RUNNER_TEMP"]) / "qa-public"))
    command = ["corepack", "pnpm", "--dir", "web/game", "exec", "playwright", "test",
               "--config=playwright.qa160.config.ts"]
    pending = Path(owner["directory"]) / "process-pending.json"
    receipt = Path(owner["directory"]) / "process.json"
    STACK["private_json"](pending, {"nonce": owner["nonce"]})
    child, process = None, None
    try:
        child = subprocess.Popen(command, env=env, start_new_session=True,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
        process = {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]}
        STACK["private_json"](receipt, process)
        pending.unlink()
        if child.wait(timeout=1500) != 0:
            raise RuntimeError("Actual browser QA failed; only sanitized evidence may be published")
    finally:
        if process is not None:
            # Even a failed receipt write must use the full PID/tick/nonce guard and
            # verify descendant absence before reporting successful cleanup.
            STACK["hosted_process_cleanup"](owner, process)
            if pending.exists():
                pending.unlink()
        elif child is None:
            pending.unlink()  # Popen never created any process.
        elif child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL)
            # Keep the pending marker: without a creation tick, cleanup cannot PASS.
        if child is not None and child.poll() is None:
            child.wait(timeout=15)


def verify_runtime():
    """Cross-check the runner claim with GitHub, not a VPS-supplied environment alone."""
    run = gh("actions/runs/" + os.environ["GITHUB_RUN_ID"])
    if (run.get("path") != ".github/workflows/run-pr-candidate-qa.yml"
            or run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("head_sha") != os.environ["GITHUB_SHA"]
            or run.get("repository", {}).get("full_name") != REPO
            or run.get("run_attempt") != int(os.environ["GITHUB_RUN_ATTEMPT"])):
        raise ValueError("Trusted runtime workflow identity mismatch")
    jobs = gh("actions/runs/" + os.environ["GITHUB_RUN_ID"] + "/jobs?per_page=100")["jobs"]
    current = [j for j in jobs if j.get("name") == "candidate" and j.get("status") == "in_progress"]
    if (len(current) != 1 or current[0].get("runner_group_name") != "GitHub Actions"
            or not current[0].get("runner_name", "").startswith("GitHub Actions ")
            or "self-hosted" in current[0].get("labels", [])
            or "ubuntu-24.04" not in current[0].get("labels", [])):
        raise ValueError("GitHub API did not attest a standard GitHub-hosted runtime job")

def execute(args):
    STACK["hosted_environment"]()
    verify_runtime()
    public = Path(os.environ["RUNNER_TEMP"]) / "qa-public"
    public.mkdir(mode=0o700, exist_ok=True)
    receipt_path = public / "candidate-receipt.json"
    receipt = download_receipt(args.image_run_id, args.source_sha, args.pr_number, receipt_path)
    try:
        owner, metrics = STACK["hosted_claim"]()
    except RuntimeError as exc:
        if hasattr(exc, "metrics"):
            safe_json(public / "preflight.json", {"status": "FAIL", **exc.metrics})
        raise
    safe_json(public / "preflight.json", metrics)
    directory = Path(owner["directory"]) / "custody"
    try:
        STACK["prepare"](args.source_sha, args.image_run_id, "pr-candidate", args.pr_number, receipt_path, directory)
        STACK["start"](directory)
        bootstrap(directory)
        ROLE["create_accounts"](directory)
        plan = public / "fixture-plan.json"
        safe_json(plan, fixture_plan(directory))
        ROLE["apply_roles"](directory, plan)
        STACK["refresh_routes"](directory)
        graph = role_graph(directory)
        safe_json(public / "role-map.json", graph)
        harness = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        safe_json(public / "identity.json", {"harnessSha": harness, "appSourceSha": args.source_sha,
                 "imageRunId": args.image_run_id, "pr": int(args.pr_number), "currentCiRunId": receipt["currentCiRunId"]})
        live(directory)
        safe_json(public / "runtime.json", {"status": "PASS", "serverTransition": "NOT_RUN",
                 "newGenerationLifecycle": "NOT_RUN", "fullPR1622Gate": "NOT_RUN"})
    except BaseException as exc:
        safe_json(public / "runtime.json", {"status": "FAIL", "errorType": type(exc).__name__,
                 "fullPR1622Gate": "NOT_RUN"})
        raise
    finally:
        safe_json(public / "cleanup.json", {"status": "CLEANUP_FAILED"})
        result = STACK["hosted_cleanup"]()
        safe_json(public / "cleanup.json", result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("run", "cleanup", "snapshot"))
    parser.add_argument("--pr-number")
    parser.add_argument("--source-sha")
    parser.add_argument("--image-run-id")
    parser.add_argument("--custody")
    parser.add_argument("--general-id")
    parser.add_argument("--request-id")
    args = parser.parse_args()
    os.environ["QA_EXECUTION_BACKEND"] = "github-hosted"
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupt)
    if args.action == "snapshot":
        return snapshot_command(args)
    elif args.action == "cleanup":
        STACK["hosted_environment"]()
        path = Path(os.environ["QA_OWNER_INDEX"])
        public = Path(os.environ["RUNNER_TEMP"]) / "qa-public"
        if path.exists():
            safe_json(public / "cleanup.json", {"status": "CLEANUP_FAILED"})
            safe_json(public / "cleanup.json", STACK["hosted_cleanup"]())
        else:
            previous = public / "cleanup.json"
            if (Path(os.environ["RUNNER_TEMP"]) / "opensamguk-qa160-owner").exists():
                safe_json(public / "cleanup.json", {"status": "CLEANUP_FAILED", "reason": "missing owner index"})
                raise RuntimeError("CLEANUP_FAILED: unindexed owner directory")
            if not previous.exists():
                # No claim was made; do not touch foreign resources or call that a QA pass.
                safe_json(previous, {"status": "NOT_STARTED", "privateCustodyCreated": False})
            elif json.loads(previous.read_text()).get("status") != "PASS":
                raise RuntimeError("CLEANUP_FAILED: missing owner index")
    else:
        execute(args)


if __name__ == "__main__":
    try:
        exit_code = main()
    except BaseException as exc:
        raise SystemExit("Hosted QA stopped: " + type(exc).__name__ + "; private values withheld") from None

    raise SystemExit(exit_code)
