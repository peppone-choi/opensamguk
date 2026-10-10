"""Installed-host entrypoints. No assumed connector or META activation."""
import argparse
import importlib.machinery
import importlib.util
import json
import os
import sys
import subprocess
import zipfile
from pathlib import Path
from . import bundle_hash
from . import completion
from .adapters import GitHubReader
from .claim import active_leases
from .queue import eligibility, next_units
from .schema import binding, issue_ref, read_record, explicit_exemption, trusted_registration
from .acceptance import parse_ac
from .surface import GitTree, RemoteTree


def context():
    meta = Path(os.environ.get("OPENSAMGUK_META_ROOT", Path(__file__).resolve().parents[2]))
    state = Path(os.environ.get("PR_LOOP_STATE", Path.home() / ".cache/pr-loop"))
    return meta, state


def independent_review(meta, raw, reader):
    path = meta / "bin/pr-loop"
    if not path.is_file() or path.is_symlink():
        raise ValueError("INSTALLED_REVIEW_PROVIDER_MISSING")
    loader = importlib.machinery.SourceFileLoader("work_unit_review_provider", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    repo = raw["base"]["repo"]["full_name"]
    login = reader.get("user")["login"]
    # Reuse the installed exact-head/session/account gate, never author claims.
    authors = list(dict.fromkeys(module.AUTHOR.findall(raw.get("body", ""))))
    verdict = module.verdict_for_head(module.review_bodies(repo, raw["number"], login),
                                     raw["head"]["sha"], authors)
    return {"head": raw["head"]["sha"], "verdict": verdict, "independent": verdict == "MERGEABLE",
            "provenance": "installed-pr-loop-account-and-session-gate"}


def queue_main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["next", "explain", "migration-report"])
    parser.add_argument("ref", nargs="?")
    parser.add_argument("--project", default="opensamguk")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    meta, state = context()
    reader = GitHubReader()
    try:
        tree = GitTree(meta / "projects" / args.project, "HEAD")
        policy = binding(tree.json("work-units/binding.json"))
        repo = policy["repo"]
        if args.command == "explain":
            ref = issue_ref(args.ref, repo)
            issue = reader.get(f"repos/{repo}/issues/{ref['number']}")
            ac = parse_ac(issue.get("body", ""))
            deps = {n: reader.get(f"repos/{repo}/issues/{n}") for n in ac["depends"]}
            output = eligibility(issue, active_leases(state), deps, repo=repo)
        elif args.command == "migration-report":
            pulls = reader.pages(f"repos/{repo}/pulls?state=open&per_page=100")
            output = []
            for pull in pulls:
                candidate = RemoteTree(reader, repo, pull["head"]["sha"])
                units = [candidate.json(p) for p in candidate.entries("work-units/units") if p.endswith(".json")]
                present = any(u["branch"] == pull["head"]["ref"] for u in units)
                exception = explicit_exemption(policy, repo=repo, pr=pull["number"], head=pull["head"]["sha"])
                output.append({"pr": pull["number"], "head": pull["head"]["sha"],
                               "branch": pull["head"]["ref"], "status": "MANIFEST_PRESENT" if present else
                               "EXPLICIT_EXCEPTION" if exception else "MIGRATION_OR_EXPLICIT_EXCEPTION_REQUIRED",
                               "exception": exception, "automaticCompletion": False})
        else:
            output = next_units(reader, repo, active_leases(state),
                                tree.json("data/commands/command-work-gaps-v1.json"), now=None)
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({"status": "UNKNOWN_AUTH", "reason": str(exc)}))
        return 1


def complete_main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["record", "scan", "drain", "status", "attest",
                                           "export-jira", "ingest-receipt"])
    parser.add_argument("--repo")
    parser.add_argument("--pr", type=int)
    parser.add_argument("--registration", help="trusted registry identity PROJECT/TASK (not a file)")
    parser.add_argument("--audit-id")
    parser.add_argument("--input")
    parser.add_argument("--write", action="store_true", help="requires preexisting verified writer-host")
    args = parser.parse_args(argv)
    meta, state = context()
    reader = GitHubReader()
    try:
        if args.command == "record":
            if not args.repo or not args.pr or not (args.registration or args.input):
                raise ValueError("RECORD_IDENTITY_REQUIRED")
            registration = trusted_registration(state, args.registration, repo=args.repo) if args.registration else None
            raw = reader.get(f"repos/{args.repo}/pulls/{args.pr}")
            if ((registration and raw["head"]["ref"] != registration["branch"]) or
                    raw["base"]["repo"]["full_name"] != args.repo):
                raise ValueError("REGISTRATION_BRANCH_MISMATCH")
            review = independent_review(meta, raw, reader)
            host, unit = completion.host_verify(raw, reader, registration, review)
            output = completion.record(state, raw, reader, host, unit=unit, registration=registration,
                                       attestation=read_record(args.input) if args.input else None,
                                       merged_tree=RemoteTree(reader, args.repo, raw["merge_commit_sha"]))
        elif args.command == "scan":
            output = completion.scan(state)
        elif args.command == "drain":
            output = completion.drain(state, reader, enabled=args.write)
        elif args.command == "status":
            output = completion.status(state)
        elif args.command == "export-jira":
            output = completion.export_jira(state)
        elif args.command == "attest":
            output = completion.attest(state, args.audit_id, read_record(args.input))
        else:
            output = completion.ingest_receipt(state, read_record(args.input))
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as exc:
        print(json.dumps({"status": "MANUAL", "reason": str(exc), "bundleHash": bundle_hash()}))
        return 1
