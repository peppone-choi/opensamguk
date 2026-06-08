#!/usr/bin/env python3
"""Provider-agnostic working-system checks for opensamguk agents.

The checks intentionally use only git, JSON, and filesystem inspection so they
work for Codex, Claude, Gemini, local shells, and CI runners.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]

DOC_FILES = {
    "AGENTS.md",
    "CLAUDE.md",
    "README.md",
    "docs/superpowers/WORKING_SYSTEM.md",
}

CODE_PREFIXES = (
    "app/",
    "common/",
    "infra/",
    "logic/",
    "web/",
)

BEHAVIOR_PREFIXES = (
    "app/game-api/",
    "app/game-engine/",
    "common/src/",
    "infra/src/",
    "logic/src/",
    "web/game/",
    "web/gateway/",
)

EVIDENCE_PREFIXES = (
    "docs/superpowers/",
    "tools/php-golden/",
    "common/src/test/",
    "infra/src/test/",
    "logic/src/test/",
    "app/game-api/src/test/",
    "app/game-engine/src/test/",
    "web/game/",
    "web/gateway/",
)


@dataclass(frozen=True)
class Finding:
    severity: str
    check: str
    message: str


def run_git(args: list[str]) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or f"git {' '.join(args)} failed")
    return proc.stdout


def changed_files(base: str | None) -> list[str]:
    if base:
        diff = run_git(["diff", "--name-only", f"{base}...HEAD"])
        working = run_git(["diff", "--name-only"])
        staged = run_git(["diff", "--cached", "--name-only"])
        files = diff.splitlines() + working.splitlines() + staged.splitlines()
    else:
        files = (
            run_git(["diff", "--name-only"]).splitlines()
            + run_git(["diff", "--cached", "--name-only"]).splitlines()
        )
        status = run_git(["status", "--short"]).splitlines()
        files += [line[3:] for line in status if line.startswith("?? ")]
    return sorted({f for f in files if f})


def read_json(path: Path) -> object:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def is_prefix(path: str, prefixes: Iterable[str]) -> bool:
    return any(path == prefix.rstrip("/") or path.startswith(prefix) for prefix in prefixes)


def check_skills_lock(files: list[str]) -> list[Finding]:
    findings: list[Finding] = []
    lock_path = ROOT / "skills-lock.json"
    working_doc = ROOT / "docs/superpowers/WORKING_SYSTEM.md"
    if not lock_path.exists():
        return [Finding("error", "skills-lock", "skills-lock.json is missing; skills.sh installs are not reproducible.")]
    if not working_doc.exists():
        return [Finding("error", "working-system", "docs/superpowers/WORKING_SYSTEM.md is missing.")]

    lock = read_json(lock_path)
    skills = lock.get("skills", {}) if isinstance(lock, dict) else {}
    if not isinstance(skills, dict) or not skills:
        findings.append(Finding("error", "skills-lock", "skills-lock.json has no skills entries."))
        return findings

    doc_text = working_doc.read_text(encoding="utf-8")
    for name, meta in skills.items():
        if name not in doc_text:
            findings.append(Finding("error", "skills-doc", f"{name} is in skills-lock.json but not documented in WORKING_SYSTEM.md."))
        if not isinstance(meta, dict) or not meta.get("computedHash"):
            findings.append(Finding("error", "skills-lock", f"{name} is missing computedHash."))

    if "skills-lock.json" in files and "docs/superpowers/WORKING_SYSTEM.md" not in files:
        findings.append(Finding("warning", "skills-drift", "skills-lock.json changed without WORKING_SYSTEM.md; document routing and risk notes."))
    return findings


def check_docs_with_code(files: list[str], strict: bool) -> list[Finding]:
    code_files = [f for f in files if is_prefix(f, CODE_PREFIXES) and not f.endswith(".md")]
    if not code_files:
        return []

    docs_changed = any(f in DOC_FILES or f.startswith("docs/superpowers/") for f in files)
    if docs_changed:
        return []

    severity = "error" if strict else "warning"
    return [
        Finding(
            severity,
            "docs-drift",
            "Code changed without README/AGENTS/CLAUDE/docs/superpowers updates. Document source-of-truth evidence or why docs are unchanged.",
        )
    ]


def check_behavior_evidence(files: list[str], strict: bool) -> list[Finding]:
    behavior_files = [
        f
        for f in files
        if is_prefix(f, BEHAVIOR_PREFIXES)
        and not f.endswith((".md", ".json", ".png", ".gif", ".css"))
        and "/src/test/" not in f
        and not f.endswith(".test.tsx")
        and not f.endswith(".test.ts")
    ]
    if not behavior_files:
        return []

    evidence_files = [
        f
        for f in files
        if is_prefix(f, EVIDENCE_PREFIXES)
        and (
            f.endswith(".md")
            or "/src/test/" in f
            or "resources/golden/" in f
            or f.startswith("tools/php-golden/")
            or f.endswith(".test.tsx")
            or f.endswith(".test.ts")
        )
    ]
    if evidence_files:
        return []

    severity = "error" if strict else "warning"
    return [
        Finding(
            severity,
            "parity-evidence",
            "Behavior files changed without tests, golden capture, or docs evidence. Add PHP oracle notes and a targeted gate.",
        )
    ]


def check_gateway_server_registry() -> list[Finding]:
    path = ROOT / "web/gateway/config/servers.json"
    if not path.exists():
        return [Finding("error", "server-registry", "web/gateway/config/servers.json is missing.")]

    data = read_json(path)
    servers = data.get("servers") if isinstance(data, dict) else None
    if not isinstance(servers, list):
        return [Finding("error", "server-registry", "servers.json must contain a servers array.")]
    if servers:
        return [
            Finding(
                "error",
                "server-registry",
                "Default server list must be empty. Servers are admin-created runtime data, not baked config.",
            )
        ]
    return []


def check_required_docs() -> list[Finding]:
    findings: list[Finding] = []
    required_phrases = {
        "README.md": ("작업 운영 체계", "tools/parity/gate.sh backend"),
        "AGENTS.md": ("작업 운영 체계 / skills.sh", "legacy/devsam-core"),
        "CLAUDE.md": ("working system", "skills-lock.json"),
        "docs/superpowers/WORKING_SYSTEM.md": ("PHP oracle protocol", "Production policy"),
    }
    for rel, phrases in required_phrases.items():
        path = ROOT / rel
        if not path.exists():
            findings.append(Finding("error", "required-docs", f"{rel} is missing."))
            continue
        text = path.read_text(encoding="utf-8")
        for phrase in phrases:
            if phrase not in text:
                findings.append(Finding("error", "required-docs", f"{rel} is missing required phrase: {phrase}"))
    return findings


def check_cross_agent_critique(files: list[str], strict: bool) -> list[Finding]:
    if not strict:
        return []
    code_or_tool_files = [
        f
        for f in files
        if is_prefix(f, CODE_PREFIXES)
        or f.startswith("tools/")
        or f.startswith(".github/workflows/")
    ]
    if not code_or_tool_files:
        return []

    working_doc = ROOT / "docs/superpowers/WORKING_SYSTEM.md"
    text = working_doc.read_text(encoding="utf-8") if working_doc.exists() else ""
    required = ("Cross-agent critique", "fix-required", "quarantined-with-proof")
    missing = [phrase for phrase in required if phrase not in text]
    if missing:
        return [
            Finding(
                "error",
                "cross-agent-critique",
                "WORKING_SYSTEM.md is missing cross-agent critique terms: " + ", ".join(missing),
            )
        ]
    return []


def render_markdown(files: list[str], findings: list[Finding]) -> str:
    errors = [f for f in findings if f.severity == "error"]
    warnings = [f for f in findings if f.severity == "warning"]
    lines = [
        "# Agent system check",
        "",
        f"- Changed files: {len(files)}",
        f"- Errors: {len(errors)}",
        f"- Warnings: {len(warnings)}",
        "",
        "## Source hierarchy",
        "",
        "1. `legacy/devsam-core` PHP grand truth",
        "2. `hwe/ts/` frontend grand truth when PHP shell is silent",
        "3. `CLAUDE.md`, `AGENTS.md`, `docs/superpowers/WORKING_SYSTEM.md`",
        "4. skills.sh installed skills in `skills-lock.json` as advisory workflow aids",
        "",
    ]
    if files:
        lines += ["## Changed files", "", *[f"- `{f}`" for f in files], ""]
    if findings:
        lines += ["## Findings", ""]
        for finding in findings:
            lines.append(f"- **{finding.severity.upper()} {finding.check}**: {finding.message}")
    else:
        lines += ["## Findings", "", "No findings."]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Check opensamguk working-system invariants.")
    parser.add_argument("--base", help="Git base ref for CI/PR mode, e.g. origin/main.")
    parser.add_argument("--strict", action="store_true", help="Promote drift/evidence warnings to errors.")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args()

    files = changed_files(args.base)
    findings: list[Finding] = []
    findings += check_skills_lock(files)
    findings += check_required_docs()
    findings += check_cross_agent_critique(files, args.strict)
    findings += check_docs_with_code(files, args.strict)
    findings += check_behavior_evidence(files, args.strict)
    findings += check_gateway_server_registry()

    if args.format == "json":
        print(
            json.dumps(
                {
                    "changedFiles": files,
                    "findings": [finding.__dict__ for finding in findings],
                    "ok": not any(f.severity == "error" for f in findings),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        print(render_markdown(files, findings))

    return 1 if any(f.severity == "error" for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
