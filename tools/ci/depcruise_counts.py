#!/usr/bin/env python3
"""Count front layer violations (ADR-LITE-070) with dependency-cruiser and ratchet them.

Runs `web/node_modules/.bin/depcruise` with `web/.dependency-cruiser.cjs` once per app (game · gateway · shared). Each app
gets a throwaway tsconfig in web/ that extends its own and sets `baseUrl` to the app folder: the apps declare `paths`
(`@/*`) without `baseUrl`, and dependency-cruiser then resolves `@/` against the working directory (web/), which leaves
every `@/` import unresolved and silently drops it from the rules. A run with an unresolved `@/` import or no cruised
module is a configuration error (exit 2), not a pass.

Kinds are `<rule>_<app>` (rule names with `-` → `_`). A violation counts once, under the app that owns its `from` module
(shared modules are cruised by every app run). Judgement is tools/ci/ratchet.py against tools/ci/depcruise_baseline.json.
There is no `--base-ref`: re-running dependency-cruiser on the merge base needs that commit's node_modules (the JVM
ArchUnit check takes the same stance). With `--base-ref`, files the PR adds must have no violation (new-file rule; PRs opened
before this check's ratchet merged get a NOTE). `--report-only` prints the verdict but always exits 0.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

from ratchet import added_paths, judge, new_file_verdict, rule_active_since, write_baseline

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "web"
CONFIG = ".dependency-cruiser.cjs"
BASELINE = Path(__file__).with_name("depcruise_baseline.json")
APPS = ("game", "gateway", "shared")
RULES = (
    "no-circular", "shared-not-to-apps", "no-cross-app", "lib-not-to-components", "lib-not-to-hooks",
    "hooks-not-to-components", "view-not-to-api", "parts-not-to-api", "client-not-to-route-handlers",
)
# an unresolved import through these silently drops out of every rule (app `@/` paths, the workspace package)
ALIASES = ("@/", "@opensamguk/")
# 새 파일 규칙의 표식 — 이 줄이 main 에 처음 들어온 커밋(이 검사의 래칫 PR 병합) 시각이 시행 시각이다(ratchet.rule_active_since). 바꾸지 마라.
NEW_FILE_RULE_MARKER = "ADR-LITE-070 new-file rule: depcruise"
KINDS = tuple(f"{rule.replace('-', '_')}_{app}" for rule in RULES for app in APPS)


def config_rules(config: Path) -> list[str]:
    return re.findall(r"^\s*name:\s*'([\w-]+)'", config.read_text(encoding="utf-8"), re.M)


def cruise(web: Path, app: str, depcruise: Path) -> dict:
    """One dependency-cruiser run over web/<app> → its JSON result."""
    # Always through a wrapper in web/: `--ts-config <app>/tsconfig.json` makes TypeScript look for the app's `include`
    # under web/ (TS18003 no inputs). The wrapper's `extends` keeps `include` relative to the app's own tsconfig.
    wrapped: dict = {"extends": f"./{app}/tsconfig.json"}
    if '"paths"' in (web / app / "tsconfig.json").read_text(encoding="utf-8"):
        # baseUrl relative to the wrapper: an absolute one is joined onto the config folder again and breaks.
        wrapped["compilerOptions"] = {"baseUrl": f"./{app}"}
    wrapper = tempfile.NamedTemporaryFile("w", dir=web, prefix=f".depcruise-tsconfig-{app}-", suffix=".json", delete=False)
    json.dump(wrapped, wrapper)
    wrapper.close()
    try:
        run = subprocess.run([str(depcruise), "--config", CONFIG, "--ts-config", Path(wrapper.name).name, "--output-type", "json", app],
                             cwd=web, capture_output=True, text=True)
    finally:
        Path(wrapper.name).unlink(missing_ok=True)
    if not run.stdout.strip():
        raise ValueError(f"dependency-cruiser produced no JSON for {app} (exit {run.returncode}): {run.stderr.strip()[:500]}")
    return json.loads(run.stdout)


def count(results: dict[str, dict]) -> tuple[Counter, dict[str, list[str]]]:
    """Violations per kind, each (rule, from, to) once under the app owning `from`; checks every run resolved `@/`."""
    counts: Counter = Counter()
    findings: dict[str, list[str]] = defaultdict(list)
    seen: set[tuple[str, str, str]] = set()
    for app, result in results.items():
        modules = result.get("modules", [])
        if not modules:
            raise ValueError(f"dependency-cruiser cruised no module for {app}")
        unresolved = sorted({f"{m['source']} → {d['module']}" for m in modules for d in m.get("dependencies", [])
                             if d.get("couldNotResolve") and d["module"].startswith(ALIASES)})
        if unresolved:
            raise ValueError(f"{app}: {len(unresolved)} unresolved alias import(s), e.g. {unresolved[0]} — tsconfig paths / workspace link not applied")
        for violation in result["summary"]["violations"]:
            rule = violation["rule"]["name"]
            if rule not in RULES:
                raise ValueError(f"unknown rule {rule!r} — add it to RULES in {Path(__file__).name}")
            key = (rule, violation["from"], violation["to"])
            if key in seen:
                continue
            seen.add(key)
            owner = violation["from"].split("/", 1)[0]
            if owner not in APPS:
                raise ValueError(f"violation outside web apps: {violation['from']}")
            kind = f"{rule.replace('-', '_')}_{owner}"
            counts[kind] += 1
            findings[kind].append(f"{violation['from']} → {violation['to']}")
    return counts, findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--web", type=Path, default=WEB)
    parser.add_argument("--depcruise", type=Path, help="dependency-cruiser binary (default web/node_modules/.bin/depcruise)")
    parser.add_argument("--from-json", type=Path, metavar="DIR", help="read <app>.json results from DIR instead of running")
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--list", metavar="KIND", help="print every finding of one kind")
    parser.add_argument("--write-baseline", action="store_true", help="write measured counts to the baseline (ratchet PR)")
    parser.add_argument("--report-only", action="store_true", help="print the verdict but always exit 0 (rollout)")
    parser.add_argument("--base-ref", help="merge base: files the PR adds must have 0 violations (ADR-LITE-070 new-file rule)")
    parser.add_argument("--head-ref", default="HEAD", help="PR head for the new-file rule (CI: the PR head sha)")
    parser.add_argument("--repo", type=Path, default=ROOT, help="git repository holding --base-ref")
    parser.add_argument("--pr-created", help="PR created_at (ISO 8601); PRs opened before the rule took effect get a NOTE, not a FAIL")
    args = parser.parse_args()
    web = args.web.resolve()
    try:
        names = config_rules(web / CONFIG)
        if sorted(names) != sorted(RULES):
            raise ValueError(f"{CONFIG} rules {sorted(names)} differ from RULES {sorted(RULES)}")
        if args.from_json:
            results = {app: json.loads((args.from_json / f"{app}.json").read_text(encoding="utf-8")) for app in APPS}
        else:
            depcruise = args.depcruise or web / "node_modules/.bin/depcruise"
            if not depcruise.exists():
                raise ValueError(f"{depcruise} is missing — install web dependencies first")
            results = {app: cruise(web, app, depcruise) for app in APPS}
        counts, findings = count(results)
        if args.list:
            print("\n".join(findings.get(args.list, [])))
            return 0
        if args.counts:
            print(json.dumps({kind: counts[kind] for kind in KINDS}, indent=2))
            return 0
        if args.write_baseline:
            write_baseline(args.baseline, counts, KINDS)
            print(f"wrote {args.baseline.name}")
            return 0
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        messages, failed = judge(counts, baseline, None, KINDS, args.baseline.name)
        if args.base_ref:
            repo = args.repo.resolve()
            # depcruise paths are relative to web/ (game/lib/x.ts); git's are repository paths (web/game/lib/x.ts)
            added = {path.removeprefix("web/") for path in added_paths(repo, args.base_ref, args.head_ref) if path.startswith("web/")}
            fresh = [f"{kind}: {finding}" for kind in KINDS for finding in findings.get(kind, []) if finding.split(" → ", 1)[0] in added]
            since = rule_active_since(repo, args.base_ref, "tools/ci/depcruise_counts.py", NEW_FILE_RULE_MARKER)
            new_messages, new_failed = new_file_verdict(fresh, args.pr_created, since)
            messages += new_messages
            failed = failed or new_failed
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"depcruise counts configuration error: {exc}")
        return 2
    for message in messages:
        print(("WOULD " + message) if args.report_only and message.startswith("FAIL ") else message)
    for message in messages:
        if message.startswith("FAIL ") and not message.startswith("FAIL new-file"):
            kind = message.split()[1].rstrip(":")
            print(f"{kind} examples: " + ", ".join(findings[kind][:10]))
    if args.report_only:
        print("depcruise counts: report-only (ADR-LITE-070 rollout) — exit 0" + (" despite WOULD FAIL above" if failed else ""))
        return 0
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
