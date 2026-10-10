#!/usr/bin/env python3
"""PR structure/lanes from trusted base; never wait for this workflow's result."""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

GATE = "tools/ci/work_unit_gate.py"


def union_lanes(existing, required):
    return {key: bool(value or required.get(key, False)) for key, value in existing.items()}


def bootstrap_receipt(base, head, *, stale=False):
    return {"schema": "wu-ci-receipt/1", "status": "BOOTSTRAP_NOT_ENFORCED",
            "mode": "report", "baseSha": base, "headSha": head, "gateTreeBlobs": {},
            "impact": None, "requiredLanes": dict.fromkeys(("jvm", "web", "contracts"), False),
            "qaPlan": [], "reservation": "UNVERIFIED_IN_CI", "valid": False,
            "reasons": ["STALE_BASE_REBASE_REQUIRED" if stale else "TRUSTED_BASE_GATE_ABSENT"]}


def git(repo, *args, optional=False):
    done = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, timeout=60)
    if done.returncode and not optional:
        raise ValueError("GIT_OBJECT_UNAVAILABLE")
    return None if done.returncode else done.stdout


def identity(args):
    event = json.loads(Path(args.github_event).read_text()) if args.github_event else {}
    pr = event.get("pull_request", {})
    base_ref = args.base or pr.get("base", {}).get("sha") or event.get("before") or "HEAD^"
    head_ref = args.head or pr.get("head", {}).get("sha") or event.get("after") or "HEAD"
    base = git(args.repo, "rev-parse", "--verify", base_ref + "^{commit}").decode().strip()
    head = git(args.repo, "rev-parse", "--verify", head_ref + "^{commit}").decode().strip()
    return base, head, pr.get("head", {}).get("ref") or args.branch


def trusted_base(args):
    base, head, branch = identity(args)
    if git(args.repo, "cat-file", "-e", base + ":" + GATE, optional=True) is None:
        main = git(args.repo, "show", "refs/remotes/origin/main:" + GATE, optional=True)
        return bootstrap_receipt(base, head, stale=main is not None)
    # Materialize only trusted base code. All candidate data stays in GitTree;
    # no candidate symlink, submodule or import is extracted or followed.
    with tempfile.TemporaryDirectory(prefix="wu-trusted-base-") as temporary:
        root = Path(temporary)
        entries = git(args.repo, "ls-tree", "-rz", base, "--", "tools/pr-loop/lib", "tools/ci").split(b"\0")
        for entry in entries:
            if not entry:
                continue
            metadata, raw_path = entry.split(b"\t", 1)
            mode, kind, blob = metadata.decode().split()
            path = raw_path.decode()
            if mode not in {"100644", "100755"} or kind != "blob" or ".." in Path(path).parts:
                raise ValueError("UNSAFE_TRUSTED_OBJECT")
            if int(git(args.repo, "cat-file", "-s", blob)) > 2 * 1024 * 1024:
                raise ValueError("TRUSTED_BLOB_TOO_LARGE")
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(git(args.repo, "cat-file", "blob", blob))
        command = [sys.executable, "-I", str(root / GATE), args.command,
                   "--worker", "--repo", str(Path(args.repo).resolve()), "--base", base, "--head", head]
        if branch:
            command += ["--branch", branch]
        for key in ("lane", "run_id", "attempt", "input", "browser_root"):
            value = getattr(args, key, None)
            if value is not None:
                command += ["--" + key.replace("_", "-"), str(value)]
        for path in args.paths:
            command += ["--path", str(Path(path).resolve())]
        done = subprocess.run(command, capture_output=True, text=True, timeout=180)
        if done.returncode not in {0, 1}:
            raise ValueError("TRUSTED_VALIDATOR_UNAVAILABLE")
        return json.loads(done.stdout)


def libraries():
    root = Path(__file__).resolve().parents[2]
    # Python -I plus explicit stdlib/trusted roots: neither candidate cwd nor
    # PYTHONPATH/site-packages supplies executable modules.
    standard = [p for p in sys.path if p and "site-packages" not in p and
                Path(p).is_absolute() and str(Path(p)).startswith(sys.base_prefix)]
    sys.path[:] = [str(root / "tools/pr-loop/lib"), str(root / "tools/ci"), *standard]
    from work_units.surface import GitTree, changes_between
    from work_units.verify import structural_check
    from work_units.qa import executed_tests, browser_execution, verify_executed
    from work_units.gaps import recompute, compare
    return GitTree, changes_between, structural_check, executed_tests, browser_execution, verify_executed, recompute, compare


def worker(args):
    Tree, changes, check, executed, browser, verify, recompute, compare = libraries()
    base, head = Tree(args.repo, args.base), Tree(args.repo, args.head)
    if args.command in {"check", "lanes"}:
        return check(base, head, changes(base, head), branch=args.branch)
    if args.command == "gaps":
        data, reasons = compare(base, head)
        return {"schema": "wu-gaps-receipt/1", "gaps": data, "reasons": reasons, "mode": "report"}
    if args.command == "executed-tests":
        if args.browser_root:
            return browser(args.browser_root, head=head.sha, run_id=args.run_id, attempt=args.attempt)
        return executed(args.paths, head=head.sha, lane=args.lane, run_id=args.run_id, attempt=args.attempt)
    receipt = check(base, head, changes(base, head), branch=args.branch)
    executions = []
    for root in args.paths:
        for path in sorted(Path(root).rglob("*.json")) if Path(root).is_dir() else [Path(root)]:
            data = json.loads(path.read_text())
            if data.get("schema") == "wu-executed/1":
                executions.append(data)
    result = verify(receipt, executions, lane=args.lane, run_id=args.run_id)
    return dict(result, mode=receipt["mode"], headSha=head.sha)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["check", "lanes", "executed-tests", "verify-executed", "gaps"])
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--branch")
    parser.add_argument("--github-event")
    parser.add_argument("--receipt")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--write", action="store_true", help="local gap generation only; never CI")
    parser.add_argument("--lane", choices=["jvm", "web", "all"], default="all")
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", "local"))
    parser.add_argument("--attempt", type=int, default=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")))
    parser.add_argument("--path", dest="paths", action="append", default=[])
    parser.add_argument("--input")
    parser.add_argument("--browser-root")
    args = parser.parse_args(argv)
    try:
        if args.write:
            if args.command != "gaps" or os.environ.get("CI"):
                raise ValueError("GAPS_WRITE_LOCAL_ONLY")
            Tree, _, _, _, _, _, recompute, _ = libraries()
            result = recompute(Tree(args.repo, args.head or "HEAD"))
            target = Path(args.repo) / "data/commands/command-work-gaps-v1.json"
            old = json.loads(target.read_text()) if target.is_file() else {"entries": []}
            links = {r["gapId"]: r for r in old["entries"]}
            for row in result["entries"]:
                if row["gapId"] in links:
                    row["trackedBy"] = links[row["gapId"]].get("trackedBy", [])
                    row["status"] = links[row["gapId"]].get("status", row["status"])
            target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        else:
            result = worker(args) if args.worker else trusted_base(args)
        if args.receipt:
            Path(args.receipt).write_text(json.dumps(result, indent=2) + "\n")
        if args.command == "lanes":
            lanes = result.get("requiredLanes", {})
            output = os.environ.get("GITHUB_OUTPUT")
            if output:
                with open(output, "a") as handle:
                    for key in ("jvm", "web", "contracts"):
                        handle.write(f"{key}={str(bool(lanes.get(key))).lower()}\n")
        print(json.dumps(result, sort_keys=True))
        if result.get("status") == "UNKNOWN":
            return 1
        return 1 if result.get("mode") == "enforce" and (
            result.get("valid") is False or result.get("status") == "FAIL" or
            (args.command == "gaps" and result.get("reasons"))) else 0
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({"status": "UNKNOWN", "reasons": [str(exc)]}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
