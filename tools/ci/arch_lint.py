#!/usr/bin/env python3
"""Count layered-architecture and one-command-per-file violations (ADR-LITE-070) and ratchet them.

Static source scan — no build, no network. Every count is a heuristic described next to its kind; the point is that
the number must not grow, not that it is exact. Judgement is tools/ci/ratchet.py: a PR passes while
`count <= min(baseline JSON, count at --base-ref)`. `--report-only` prints the same verdict but always exits 0
(first rollout PR); the follow-up ratchet PR drops the flag.

Kinds (backend, `*/src/main/kotlin`):
  c1_multi_input_files      main .kt files holding 2+ distinct input-catalog inputId string literals (allowlist exempts indexes)
  c2_reserved_turn_handlers `InputHandler {` registrations in engine/turn/ReservedTurnHandler.kt (hand-written hub)
  c2_court_handlers         `InputHandler {` registrations in engine/campaign/CourtHandler.kt (hand-written hub)
  c3_wire_variants          subtypes declared in common/wire/TurnDaemonCommand.kt
  s1_kotlin_files_over_p95  non-generated main .kt files longer than KOTLIN_FILE_P95 lines
  s2_kotlin_funs_over_p99   main .kt functions longer than KOTLIN_FUN_P99 lines (brace matching after stripping strings/comments)
  d1_kotlin_unused_private  `private fun|val|var` names that occur once in their file
  p_frozen_<package>        .kt files directly in a frozen horizontal package (no new files)
Kinds (frontend, per app game|gateway|shared, tests excluded; f1/f2 are game|gateway only):
  f1_raw_fetch_<app>        components/pages (not route handlers) that call fetch( directly
  f2_screen_api_<app>       components/pages that value-import an API client module (lib module that calls fetch, or
                            lib/api · server-api · requests · mailbox · *-reads · lib/api/*)
  c1f_multi_input_<app>     .ts/.tsx files holding 2+ distinct inputId string literals (allowlist exempts generated/index files)
  s1f_files_over_p95_<app>  files longer than the app's p95 line count
  s2f_funs_over_p99_<app>   functions longer than the app's p99 line count
  d1f_unreferenced_<app>    dead value exports: declared once in their own file and named in no other file
  d1f_test_only_<app>       value exports named only by test files (declared once in their own file)

Thresholds (P95/P99) are the 2026-10-05 measurements of ADR-LITE-070 §3 at 78a0f0ae — measured, not invented.
Change them only by re-measuring.
"""

from __future__ import annotations

import argparse
import json
import posixpath
import re
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

from lint_files import git_visible_files, is_visible
from ratchet import added_paths, allowlist_growth, judge, new_file_verdict, rule_active_since, tree_at, write_baseline

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name("arch_lint_baseline.json")
ALLOWLIST = Path(__file__).with_name("arch_lint_allowlist.json")
CATALOG = "data/commands/input-catalog.json"
# 새 파일 규칙의 표식 — 이 줄이 main 에 처음 들어온 커밋(래칫 PR 병합) 시각이 시행 시각이다(ratchet.rule_active_since). 바꾸지 마라.
NEW_FILE_RULE_MARKER = "ADR-LITE-070 new-file rule: arch_lint"
KOTLIN_FILE_P95 = 436
KOTLIN_FUN_P99 = 119
WEB_THRESHOLDS = {"game": (300, 169), "gateway": (230, 174), "shared": (414, 129)}  # (file p95, function p99)
WEB_APPS = {"game": "web/game", "gateway": "web/gateway", "shared": "web/shared"}
FROZEN_PACKAGES = {
    "gameapi_controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller",
    "gameapi_web": "app/game-api/src/main/kotlin/opensamguk/gameapi/web",
    "gameapi_dto": "app/game-api/src/main/kotlin/opensamguk/gameapi/dto",
    "gameapi_read": "app/game-api/src/main/kotlin/opensamguk/gameapi/read",
    "engine_campaign": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign",
    "logic_input": "logic/src/main/kotlin/opensamguk/logic/input",
}
HUBS = {
    "c2_reserved_turn_handlers": "app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt",
    "c2_court_handlers": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
}
WIRE = "common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt"
KOTLIN_ROOTS = ("common/src/main/kotlin", "logic/src/main/kotlin", "infra/src/main/kotlin", "app")
KINDS = (
    "c1_multi_input_files", *HUBS, "c3_wire_variants", "s1_kotlin_files_over_p95", "s2_kotlin_funs_over_p99",
    "d1_kotlin_unused_private", *(f"p_frozen_{name}" for name in FROZEN_PACKAGES),
    *(f"{kind}_{app}" for app in WEB_APPS for kind in
      (("f1_raw_fetch", "f2_screen_api") if app != "shared" else ()) +
      ("c1f_multi_input", "s1f_files_over_p95", "s2f_funs_over_p99", "d1f_unreferenced", "d1f_test_only")),
)
# Paths the base-ref scan needs (kept narrow: git archive of web/*/public would pull map assets).
SCAN_PATHS = (
    CATALOG, "common/src/main/kotlin", "logic/src/main/kotlin", "infra/src/main/kotlin", "app",
    *(f"{base}/{sub}" for base in ("web/game", "web/gateway") for sub in
      ("app", "components", "lib", "hooks", "types", "__tests__", "e2e", "middleware.ts")),
    "web/shared/src", "tools/ci",
)
GENERATED = re.compile(r"(?i)GENERATED|generated by|do not edit|release snapshot")
SKIP_DIRS = {"node_modules", ".next", "build", "dist", "coverage", ".gradle", "test-results"}
WEB_ENTRY = re.compile(r"(?:^|/)app/(?:.*/)?(?:page|layout|loading|error|not-found|template|default|route|global-error|opengraph-image|icon)\.(?:ts|tsx)$|(?:^|/)middleware\.ts$")


def load_allowlist(path: Path) -> dict[str, list[str]]:
    data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    return {kind: [entry["path"] for entry in entries] for kind, entries in data.get("exempt", {}).items()}


def by_path(allowed: dict[str, list[str]]) -> dict[str, dict[str, list[str]]]:
    """{kind: [path]} → {path: {"kinds": [kind]}} — the shape ratchet.allowlist_growth compares."""
    out: dict[str, dict[str, list[str]]] = defaultdict(lambda: {"kinds": []})
    for kind, paths in allowed.items():
        for path in paths:
            out[path]["kinds"].append(kind)
    return dict(out)


def strip_code(text: str) -> str:
    """Blank out string literals and comments (keeps line breaks) so braces and names inside them do not count."""
    out, i, n = [], 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", text[i:j])); i = j
        elif text[i] in "\"'`":
            quote = text[i]
            triple = quote == '"' and text[i:i + 3] == '"""'
            end = '"""' if triple else quote
            j = i + (3 if triple else 1)
            while j < n and not text.startswith(end, j):
                if text[j] == "\n" and quote != "`" and not triple:
                    break  # one-line literals never span lines (an apostrophe in JSX text is not a string)
                j += 2 if text[j] == "\\" and not triple else 1
            j = min(n, j + (len(end) if text.startswith(end, j) else 0))
            out.append(re.sub(r"[^\n]", " ", text[i:j])); i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def long_blocks(stripped: str, starts: list[int], limit: int) -> int:
    """How many block bodies opened right after each signature start span more than `limit` lines.

    The signature runs from `start` to its parameter list's closing paren; a body counts only when the next
    token after the signature (return type allowed) is `{`. Expression bodies (`= …`) and bodiless declarations
    (abstract · interface · expect) are skipped instead of borrowing the next declaration's brace.
    """
    over = 0
    for start in starts:
        open_paren = stripped.find("(", start)
        if open_paren < 0:
            continue
        depth, k = 0, open_paren
        while k < len(stripped):
            if stripped[k] == "(":
                depth += 1
            elif stripped[k] == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        brace = stripped.find("{", k)
        if brace < 0:
            continue
        between = stripped[k + 1:brace]
        if re.search(r"(?<![=!<>])=(?!>)|\n\s*\n|\n\s*(?:fun|val|var|override|private|internal|public|protected|abstract|class|object|interface|@)\b", between):
            continue
        depth, j = 0, brace
        while j < len(stripped):
            if stripped[j] == "{":
                depth += 1
            elif stripped[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        lines = stripped.count("\n", start, j) + 1
        if lines > limit:
            over += 1
    return over


def literals(text: str) -> set[str]:
    return {a or b or c for a, b, c in re.findall(r"\"([^\"\\\n]{3,80})\"|'([^'\\\n]{3,80})'|`([^`\\\n]{3,80})`", text)}


class Tree:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.visible = git_visible_files(root)

    def files(self, base: str, suffixes: tuple[str, ...]) -> list[str]:
        start = self.root / base
        if start.is_file():
            return [base] if base.endswith(suffixes) else []
        if not start.is_dir():
            return []
        out = []
        for path in sorted(start.rglob("*")):
            if not path.is_file() or not path.name.endswith(suffixes) or any(part in SKIP_DIRS for part in path.parts):
                continue
            if is_visible(path, self.root, self.visible):
                out.append(path.relative_to(self.root).as_posix())
        return out

    def read(self, rel: str) -> str:
        return (self.root / rel).read_text(encoding="utf-8", errors="replace")


def scan(root: Path, allowed: dict[str, list[str]]) -> tuple[Counter, dict[str, list[str]]]:
    tree = Tree(root)
    counts: Counter = Counter()
    findings: dict[str, list[str]] = defaultdict(list)
    exempt = lambda kind, rel: rel in allowed.get(kind, [])  # noqa: E731
    input_ids = {row["inputId"] for row in json.loads(tree.read(CATALOG))["inputs"]}

    def hit(kind: str, rel: str, detail: str = "") -> None:
        if exempt(kind, rel):
            return
        counts[kind] += 1
        findings[kind].append(f"{rel}{(' ' + detail) if detail else ''}")

    # backend
    kotlin = sorted({rel for base in KOTLIN_ROOTS for rel in tree.files(base, (".kt",)) if "/src/main/kotlin/" in rel})
    for rel in kotlin:
        text = tree.read(rel)
        ids = {value for value in re.findall(r'"([^"\\\n]+)"', text) if value in input_ids}
        if len(ids) >= 2:
            hit("c1_multi_input_files", rel, str(len(ids)))
        stripped = strip_code(text)
        generated = GENERATED.search("\n".join(text.splitlines()[:15])) is not None
        if not generated and text.count("\n") + (0 if text.endswith("\n") else 1) > KOTLIN_FILE_P95:
            hit("s1_kotlin_files_over_p95", rel, str(text.count("\n")))
        starts = [m.start() for m in re.finditer(r"\bfun\b[^\n(]*\(", stripped)]
        for _ in range(long_blocks(stripped, starts, KOTLIN_FUN_P99)):
            hit("s2_kotlin_funs_over_p99", rel)
        for name in re.findall(r"\bprivate\s+(?:inline\s+|const\s+|lateinit\s+)*(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)", stripped):
            if len(re.findall(rf"\b{re.escape(name)}\b", stripped)) == 1:
                hit("d1_kotlin_unused_private", rel, name)
    for kind, rel in HUBS.items():
        if (root / rel).exists():
            counts[kind] = len(re.findall(r"\bInputHandler\s*\{", strip_code(tree.read(rel))))
    if (root / WIRE).exists():
        wire = strip_code(tree.read(WIRE))
        counts["c3_wire_variants"] = len(re.findall(r"\b(?:class|object)\s+\w+(?:\s*<[^>]*>)?(?:\s*\([^{}]*?\))?\s*:\s*TurnDaemonCommand\b", wire, re.S))
    for name, rel in FROZEN_PACKAGES.items():
        folder = root / rel
        counts[f"p_frozen_{name}"] = 0
        for path in sorted(folder.glob("*.kt")) if folder.is_dir() else ():
            if is_visible(path, root, tree.visible):
                hit(f"p_frozen_{name}", path.relative_to(root).as_posix())

    # frontend
    for app, base in WEB_APPS.items():
        file_p95, fun_p99 = WEB_THRESHOLDS[app]
        source_roots = ("src",) if app == "shared" else ("app", "components", "lib", "hooks", "types", "middleware.ts")
        test_roots = ("src",) if app == "shared" else ("__tests__", "e2e")
        scanned = sorted({rel for sub in (*source_roots, *test_roots) for rel in tree.files(f"{base}/{sub}", (".ts", ".tsx"))})
        sources = [rel for rel in scanned if not is_test(rel)]
        # tests anywhere under the scanned roots — also `__tests__` next to a component (components/x/__tests__/)
        tests = [rel for rel in scanned if is_test(rel)]
        if app == "shared":
            # shared exports are consumed by the apps too: app sources count as users, app tests as tests
            used_by = [rel for other in ("web/game", "web/gateway") for sub in ("app", "components", "lib", "hooks", "types", "__tests__", "e2e")
                       for rel in tree.files(f"{other}/{sub}", (".ts", ".tsx"))]
            consumers = [rel for rel in used_by if not is_test(rel)]
            tests += [rel for rel in used_by if is_test(rel)]
        else:
            consumers = []
        texts = {rel: tree.read(rel) for rel in {*sources, *tests, *consumers}}
        clients = {rel for rel in sources if "/lib/" in f"/{rel}" and (re.search(r"\bfetch\s*\(", strip_code(texts[rel]))
                                                                     or re.search(r"/lib/(?:api|server-api|requests|mailbox)\.tsx?$|/lib/api/|-reads\.ts$", rel))}
        for rel in sources:
            text = texts[rel]
            stripped = strip_code(text)
            screen = (f"{base}/components/" in rel or f"{base}/app/" in rel) and not rel.startswith(f"{base}/app/api/") \
                and not rel.endswith("/route.ts")
            if app != "shared" and screen and re.search(r"(?<![\w.])fetch\s*\(", stripped):
                hit(f"f1_raw_fetch_{app}", rel)
            if app != "shared" and screen:
                if any(target in clients for target in value_imports(rel, text, base)):
                    hit(f"f2_screen_api_{app}", rel)
            ids = {value for value in literals(text) if value in input_ids}
            if len(ids) >= 2:
                hit(f"c1f_multi_input_{app}", rel, str(len(ids)))
            if text.count("\n") > file_p95:
                hit(f"s1f_files_over_p95_{app}", rel, str(text.count("\n")))
            starts = [m.start() for m in re.finditer(r"\bfunction\b\s*\*?\s*\w*\s*\(|=\s*(?:async\s*)?\([^()]*(?:\([^()]*\)[^()]*)*\)\s*(?::[^=]{1,120})?=>\s*\{", stripped)]
            for _ in range(long_blocks(stripped, starts, fun_p99)):
                hit(f"s2f_funs_over_p99_{app}", rel)
        # unreferenced value exports (types · interfaces are kept beside their component by convention; default
        # exports are framework or lazy entry points). One identifier index per file keeps this linear.
        words = {rel: Counter(re.findall(r"[A-Za-z_$][\w$]*", strip_code(text))) for rel, text in texts.items()}
        source_users = [o for o in {*sources, *consumers}]
        for rel in sources:
            if WEB_ENTRY.search(rel):
                continue
            names = re.findall(r"^export\s+(?:declare\s+)?(?:async\s+)?(?:function\*?|const|let|class|enum|abstract\s+class)\s+(\w+)",
                               texts[rel], re.M)
            for name in names:
                if words[rel][name] > 1:
                    continue  # used inside its own file — not dead, only over-exported
                in_sources = any(name in words[o] for o in source_users if o != rel)
                in_tests = any(name in words[o] for o in tests if o != rel)
                if not in_sources and not in_tests:
                    hit(f"d1f_unreferenced_{app}", rel, name)
                elif not in_sources and in_tests:
                    hit(f"d1f_test_only_{app}", rel, name)
    return counts, findings


def new_file_violations(findings: dict[str, list[str]], added: set[str]) -> list[str]:
    """Every finding in a file the PR adds — new code follows ADR-LITE-070 from day one, whatever the baseline."""
    return [f"{kind}: {finding}" for kind in KINDS for finding in findings.get(kind, []) if finding.split(" ", 1)[0] in added]


def is_test(rel: str) -> bool:
    return bool(re.search(r"(?:^|/)(?:__tests__|e2e)/|\.(?:test|spec)\.tsx?$", rel))


def value_imports(rel: str, text: str, base: str) -> list[str]:
    """Resolved targets of non-type imports (`import type` and all-`type` specifier lists are skipped)."""
    out = []
    for match in re.finditer(r"^import\s+(type\s+)?([^;]*?)\s+from\s+['\"]([^'\"]+)['\"]", text, re.M):
        if match.group(1):
            continue
        clause = match.group(2)
        specs = re.findall(r"\{([^}]*)\}", clause)
        if specs and not re.sub(r"\{[^}]*\}", "", clause).strip(" ,") and all(
                s.strip().startswith("type ") for s in specs[0].split(",") if s.strip()):
            continue
        target = match.group(3)
        if target.startswith("@/"):
            stem = f"{base}/{target[2:]}"
        elif target.startswith("."):
            stem = posixpath.normpath(posixpath.join(posixpath.dirname(rel), target))
        else:
            continue
        for candidate in (f"{stem}.ts", f"{stem}.tsx", f"{stem}/index.ts", f"{stem}/index.tsx", stem):
            out.append(candidate)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--allowlist", type=Path, default=ALLOWLIST)
    parser.add_argument("--counts", action="store_true", help="print measured counts as JSON")
    parser.add_argument("--list", metavar="KIND", help="print every finding of one kind")
    parser.add_argument("--base-ref", help="merge-base commit: limit = min(baseline, counts at this commit) — tools/ci/ratchet.py")
    parser.add_argument("--repo", type=Path, default=ROOT, help="git repository holding --base-ref")
    parser.add_argument("--write-baseline", action="store_true", help="write measured counts to the baseline (ratchet PR)")
    parser.add_argument("--report-only", action="store_true", help="print the verdict but always exit 0 (rollout)")
    parser.add_argument("--head-ref", default="HEAD", help="PR head for the new-file rule (CI: the PR head sha, not the merge commit)")
    parser.add_argument("--pr-created", help="PR created_at (ISO 8601); PRs opened before the rule took effect get a NOTE, not a FAIL")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        allowed = load_allowlist(args.allowlist)
        counts, findings = scan(root, allowed)
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
        base, notes = None, []
        if args.base_ref:
            with tree_at(args.base_ref, args.repo.resolve(), SCAN_PATHS) as base_root:
                base_allowed = load_allowlist(base_root / "tools/ci" / args.allowlist.name)
                base_counts, _ = scan(base_root, base_allowed)
            base = {kind: base_counts[kind] for kind in KINDS}
            notes = allowlist_growth(by_path(allowed), by_path(base_allowed), args.allowlist.name)
        messages, failed = judge(counts, baseline, base, KINDS, args.baseline.name)
        if args.base_ref:
            fresh = new_file_violations(findings, added_paths(args.repo.resolve(), args.base_ref, args.head_ref))
            since = rule_active_since(args.repo.resolve(), args.base_ref, "tools/ci/arch_lint.py", NEW_FILE_RULE_MARKER)
            new_messages, new_failed = new_file_verdict(fresh, args.pr_created, since)
            messages += new_messages
            failed = failed or new_failed
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"arch lint configuration error: {exc}")
        return 2
    for message in messages:
        print(("WOULD " + message) if args.report_only and message.startswith("FAIL ") else message)
    for note in notes:
        print(note)
    for message in messages:
        if message.startswith("FAIL ") and not message.startswith("FAIL new-file"):
            kind = message.split()[1].rstrip(":")
            print(f"{kind} examples: " + ", ".join(findings[kind][:10]))
    if args.report_only:
        print("arch lint: report-only (ADR-LITE-070 rollout) — exit 0" + (" despite WOULD FAIL above" if failed else ""))
        return 0
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
