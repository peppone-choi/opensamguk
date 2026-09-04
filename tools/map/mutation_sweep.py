#!/usr/bin/env python3
"""Delete one rule at a time from a gate and require its test suite to notice.

A red probe answers "does the gate refuse bad data?". This answers the other question:
"would CI notice if the rule were removed?" They are not the same, and the second one is
the durable property — a probe suite lives in a scratchpad, CI runs the tests. A rule with
no test is a rule a later change can drop while the build stays green.

Review of the territory-disconnection gate found exactly that: `overruledArgument must be
a non-empty string` was live on nine committed rows and deletable with the suite at 54/54.

Every `raise` and every `errors.append(...)` in the module is a rule. Each is replaced with
`pass` in a throwaway copy of `tools/`, the suite runs against that copy, and a rule whose
removal leaves the suite green is reported as a survivor. The working tree is never touched:
the copy gets its own `tools/`, and `data/` is symlinked read-only.

    python3 tools/map/mutation_sweep.py tools/map/audit_territory_disconnections.py \
        --tests 'test_territory*.py'

Exit 1 if any rule survived. It is deliberately not a CI step — it re-runs the whole suite
once per rule, so it belongs in review of a gate change, not in every build.
"""
from __future__ import annotations

import argparse
import ast
import pathlib
import shutil
import subprocess
import sys
import tempfile


def rule_spans(source: str) -> list[tuple[int, int, str]]:
    """Every statement that refuses something: a raise, or an append to `errors`."""
    spans: list[tuple[int, int, str]] = []
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Raise):
            spans.append((node.lineno, node.end_lineno, "raise"))
        elif (
            isinstance(node, ast.Expr)
            and isinstance(node.value, ast.Call)
            and isinstance(node.value.func, ast.Attribute)
            and node.value.func.attr == "append"
            and isinstance(node.value.func.value, ast.Name)
            and node.value.func.value.id == "errors"
        ):
            spans.append((node.lineno, node.end_lineno, "errors.append"))
    return sorted(spans)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", help="repository-relative path of the gate to mutate")
    parser.add_argument("--tests", default="test_*.py", help="unittest discover pattern")
    parser.add_argument("--test-dir", default="tools/map/tests")
    parser.add_argument("--repo", default=".", help="repository root")
    args = parser.parse_args(argv)

    repo = pathlib.Path(args.repo).resolve()
    source = (repo / args.module).read_text(encoding="utf-8")
    lines = source.split("\n")
    spans = rule_spans(source)

    sandbox = pathlib.Path(tempfile.mkdtemp(prefix="mutation-sweep-"))
    try:
        shutil.copytree(repo / "tools", sandbox / "tools",
                        ignore=shutil.ignore_patterns("__pycache__"))
        (sandbox / "data").symlink_to(repo / "data")
        target = sandbox / args.module

        def suite() -> int:
            return subprocess.run(
                ["python3", "-m", "unittest", "discover",
                 "-s", args.test_dir, "-p", args.tests],
                cwd=sandbox, capture_output=True, text=True,
            ).returncode

        target.write_text(source, encoding="utf-8")
        if suite() != 0:
            print("baseline suite is not green in the sandbox; nothing below would mean anything")
            return 2
        print(f"baseline green; {len(spans)} rules in {args.module}\n")

        survivors = []
        for start, end, kind in spans:
            mutated = list(lines)
            indent = len(mutated[start - 1]) - len(mutated[start - 1].lstrip())
            mutated[start - 1:end] = [" " * indent + "pass"]
            target.write_text("\n".join(mutated), encoding="utf-8")
            text = " ".join(part.strip() for part in lines[start - 1:end])[:88]
            if suite() == 0:
                survivors.append((start, kind, text))
                print(f"  L{start:<4} {kind:<13} SURVIVED {text}")
            else:
                print(f"  L{start:<4} {kind:<13} killed   {text}")

        print(f"\n{len(spans) - len(survivors)}/{len(spans)} rules killed by the test suite")
        for start, kind, text in survivors:
            print(f"  SURVIVED L{start} {kind}: {text}")
        return 1 if survivors else 0
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
