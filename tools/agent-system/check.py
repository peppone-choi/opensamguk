#!/usr/bin/env python3
"""Provider-agnostic working-system checks for opensamguk agents.

The checks intentionally use only git, JSON, and filesystem inspection so they
work for Codex, Claude, Gemini, local shells, and CI runners.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tomllib
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

BEHAVIOR_AREAS = {
    "app/game-api/": ("app/game-api/src/test/",),
    "app/game-engine/": ("app/game-engine/src/test/",),
    "common/src/": ("common/src/test/",),
    "infra/src/": ("infra/src/test/",),
    "logic/src/": ("logic/src/test/", "logic/src/test/resources/golden/", "tools/php-golden/"),
    "web/game/": ("web/game/",),
    "web/gateway/": ("web/gateway/",),
}

CODEX_REQUIRED_AGENTS = frozenset(
    {
        "deployer",
        "fe-submit-wirer",
        "golden-capturer",
        "intake-wirer",
        "parity-gate-runner",
        "parity-porter",
        "parity-reviewer",
    }
)

CODEX_REQUIRED_SKILLS = frozenset(
    {
        "find-project-skill",
        "loop-engineering",
        "opensamguk-php-oracle",
        "opensamguk-working-system",
        "os-analyze",
        "os-checkpoint",
        "os-debug",
        "os-e2e",
        "os-implement",
        "os-plan-tickets",
        "os-review",
        "os-start-task",
        "os-verify",
        "parity-close",
        "parity-ship",
    }
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
    untracked = run_git(["ls-files", "--others", "--exclude-standard"]).splitlines()
    if base:
        diff = run_git(["diff", "--name-only", f"{base}...HEAD"])
        working = run_git(["diff", "--name-only"])
        staged = run_git(["diff", "--cached", "--name-only"])
        files = diff.splitlines() + working.splitlines() + staged.splitlines() + untracked
    else:
        files = (
            run_git(["diff", "--name-only"]).splitlines()
            + run_git(["diff", "--cached", "--name-only"]).splitlines()
        )
        files += untracked
    return sorted({f for f in files if f})


def read_json(path: Path) -> object:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def is_prefix(path: str, prefixes: Iterable[str]) -> bool:
    return any(path == prefix.rstrip("/") or path.startswith(prefix) for prefix in prefixes)


def scope_covers_area(scope: str, area: str) -> bool:
    return bool(re.search(rf"(?<![A-Za-z0-9_./-]){re.escape(area)}", scope))


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


def check_codex_surface() -> list[Finding]:
    findings: list[Finding] = []

    required_files = (
        ".codex/config.toml",
        ".codex/hooks.json",
        "scripts/agent/codex-session-start.sh",
        "scripts/agent/codex-post-tool-use.sh",
        "scripts/agent/codex-bash-guard.sh",
        "scripts/agent/project-skills.sh",
    )
    for rel in required_files:
        if not (ROOT / rel).is_file():
            findings.append(Finding("error", "codex-surface", f"{rel} is required for reproducible Codex startup."))

    config_path = ROOT / ".codex/config.toml"
    if config_path.is_file():
        try:
            with config_path.open("rb") as handle:
                config = tomllib.load(handle)
        except (OSError, tomllib.TOMLDecodeError) as exc:
            findings.append(Finding("error", "codex-surface", f".codex/config.toml is invalid: {exc}"))
        else:
            features = config.get("features", {})
            if not isinstance(features, dict) or features.get("hooks") is not True:
                findings.append(Finding("error", "codex-surface", "Codex project config must enable stable hooks."))
            if not isinstance(features, dict) or features.get("multi_agent") is not True:
                findings.append(Finding("error", "codex-surface", "Codex project config must enable stable multi_agent."))
            if "model" in config:
                findings.append(Finding("error", "codex-surface", "Project Codex config must not pin a personal model."))

    hooks_path = ROOT / ".codex/hooks.json"
    if hooks_path.is_file():
        try:
            hook_document = read_json(hooks_path)
        except (OSError, json.JSONDecodeError) as exc:
            findings.append(Finding("error", "codex-surface", f".codex/hooks.json is invalid: {exc}"))
        else:
            hooks = hook_document.get("hooks") if isinstance(hook_document, dict) else None
            if not isinstance(hooks, dict):
                findings.append(Finding("error", "codex-surface", ".codex/hooks.json must contain a hooks object."))
            else:
                for event in ("SessionStart", "PreToolUse", "PostToolUse"):
                    groups = hooks.get(event)
                    if not isinstance(groups, list) or not groups:
                        findings.append(Finding("error", "codex-surface", f"Codex hook event {event} is missing."))
                        continue
                    for group in groups:
                        handlers = group.get("hooks") if isinstance(group, dict) else None
                        if not isinstance(handlers, list) or not handlers:
                            findings.append(Finding("error", "codex-surface", f"Codex hook event {event} has no handlers."))
                            continue
                        for handler in handlers:
                            valid_handler = (
                                isinstance(handler, dict)
                                and handler.get("type") == "command"
                                and handler.get("async") is False
                                and isinstance(handler.get("timeout"), int)
                                and "timeoutSec" not in handler
                            )
                            if not valid_handler:
                                findings.append(
                                    Finding("error", "codex-surface", f"Codex hook event {event} has an invalid command handler.")
                                )

                pre_matchers = "|".join(str(group.get("matcher", "")) for group in hooks.get("PreToolUse", []))
                post_matchers = "|".join(str(group.get("matcher", "")) for group in hooks.get("PostToolUse", []))
                if "Bash" not in pre_matchers or "Bash" not in post_matchers:
                    findings.append(Finding("error", "codex-surface", "Codex hooks must cover supported Bash calls."))

    agents_dir = ROOT / ".codex/agents"
    for name in sorted(CODEX_REQUIRED_AGENTS):
        path = agents_dir / f"{name}.toml"
        if not path.is_file():
            findings.append(Finding("error", "codex-surface", f"Codex agent {name} is missing."))
            continue
        try:
            with path.open("rb") as handle:
                agent = tomllib.load(handle)
        except (OSError, tomllib.TOMLDecodeError) as exc:
            findings.append(Finding("error", "codex-surface", f"{path.relative_to(ROOT)} is invalid: {exc}"))
            continue
        for field in ("name", "description", "developer_instructions", "sandbox_mode"):
            if not agent.get(field):
                findings.append(Finding("error", "codex-surface", f"{path.relative_to(ROOT)} is missing {field}."))
        if agent.get("name") != name:
            findings.append(Finding("error", "codex-surface", f"{path.relative_to(ROOT)} has the wrong agent name."))

    for name in sorted(CODEX_REQUIRED_SKILLS):
        path = ROOT / ".agents/skills" / name / "SKILL.md"
        if not path.is_file():
            findings.append(Finding("error", "codex-surface", f"Codex project skill {name} is missing."))
            continue
        text = path.read_text(encoding="utf-8")
        if f"name: {name}" not in text or "description: Use when" not in text:
            findings.append(Finding("error", "codex-surface", f"Codex project skill {name} has invalid discovery metadata."))

    codex_files = [config_path, hooks_path, *sorted(agents_dir.glob("*.toml"))]
    forbidden_fragments = ("Codex Opus", "/Users/", "ctx_execute")
    for path in codex_files:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for fragment in forbidden_fragments:
            if fragment in text:
                findings.append(
                    Finding("error", "codex-surface", f"{path.relative_to(ROOT)} contains forbidden local drift: {fragment}")
                )

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

    review_files = [f for f in files if f.startswith("docs/superpowers/reviews/") and f.endswith(".md")]
    review_text = "\n".join((ROOT / f).read_text(encoding="utf-8") for f in review_files if (ROOT / f).exists())

    missing_areas: list[str] = []
    for area, evidence_prefixes in BEHAVIOR_AREAS.items():
        area_changed = any(f.startswith(area) for f in behavior_files)
        if not area_changed:
            continue
        area_evidence = any(
            f.startswith(evidence_prefixes)
            and (
                "/src/test/" in f
                or "resources/golden/" in f
                or f.startswith("tools/php-golden/")
                or f.endswith(".test.tsx")
                or f.endswith(".test.ts")
            )
            for f in files
        )
        review_mentions_area = area.rstrip("/") in review_text
        if not area_evidence and not review_mentions_area:
            missing_areas.append(area.rstrip("/"))

    if not missing_areas:
        return []

    severity = "error" if strict else "warning"
    return [
        Finding(
            severity,
            "parity-evidence",
            "Behavior areas changed without mapped tests, golden capture, or review evidence: " + ", ".join(missing_areas),
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


def check_production_seed_default() -> list[Finding]:
    path = ROOT / "docker-compose.production.yml"
    if not path.exists():
        return [Finding("error", "production-seed", "docker-compose.production.yml is missing.")]
    text = path.read_text(encoding="utf-8")
    if "SCENARIO_SEED_ENABLED: ${SCENARIO_SEED_ENABLED:-false}" not in text:
        return [
            Finding(
                "error",
                "production-seed",
                "Production must default SCENARIO_SEED_ENABLED to false for admin-created empty-server startup.",
            )
        ]
    return []


def check_no_baked_secondary_servers() -> list[Finding]:
    findings: list[Finding] = []
    deploy_path = ROOT / ".github/workflows/deploy.yml"
    if deploy_path.exists() and "bbae" in deploy_path.read_text(encoding="utf-8"):
        findings.append(
            Finding(
                "error",
                "server-registry",
                "Production deploy workflow must not start baked bbae/secondary servers; admins create servers at runtime.",
            )
        )
    if (ROOT / "docker-compose.bbae.yml").exists():
        findings.append(
            Finding(
                "error",
                "server-registry",
                "docker-compose.bbae.yml must not exist while production uses admin-created runtime servers.",
            )
        )
    return findings


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
        or f.startswith(".codex/")
        or f.startswith(".agents/skills/")
        or f.startswith("scripts/agent/")
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

    review_files = [f for f in files if f.startswith("docs/superpowers/reviews/") and f.endswith(".md")]
    if not review_files:
        return [
            Finding(
                "error",
                "cross-agent-critique",
                "Strict non-trivial changes require a PR-visible docs/superpowers/reviews/*.md critique artifact.",
            )
        ]

    required_areas: set[str] = set()
    area_prefixes = (
        ".codex/",
        ".agents/skills/",
        "scripts/agent/",
        "tools/",
        ".github/workflows/",
        *CODE_PREFIXES,
    )
    for changed in code_or_tool_files:
        required_areas.update(prefix for prefix in area_prefixes if changed.startswith(prefix))

    valid_verdict = False
    covered_areas: set[str] = set()
    for rel in review_files:
        review_text = (ROOT / rel).read_text(encoding="utf-8")
        verdicts = re.findall(r"^Verdict: (cleared|fix-required|quarantined-with-proof)\s*$", review_text, re.MULTILINE)
        scopes = re.findall(r"^Scope:\s*(.+?)\s*$", review_text, re.MULTILINE)
        if len(verdicts) != 1 or len(scopes) != 1:
            return [Finding("error", "cross-agent-critique", f"{rel} must contain exactly one anchored Scope and Verdict line.")]
        if verdicts[0] == "fix-required":
            return [Finding("error", "cross-agent-critique", f"Unresolved Verdict: fix-required blocks completion: {rel}")]
        if verdicts[0] == "quarantined-with-proof" and not re.search(r"^Proof:\s*\S", review_text, re.MULTILINE):
            return [Finding("error", "cross-agent-critique", f"{rel} quarantines work without an anchored Proof line.")]
        covered_areas.update(area for area in required_areas if scope_covers_area(scopes[0], area))
        valid_verdict = True
    if not valid_verdict:
        return [
            Finding(
                "error",
                "cross-agent-critique",
                "Critique artifact must contain Verdict: cleared or Verdict: quarantined-with-proof.",
            )
        ]
    missing_scope = sorted(required_areas - covered_areas)
    if missing_scope:
        if len(review_files) == 1:
            return [
                Finding(
                    "error",
                    "cross-agent-critique",
                    f"{review_files[0]} does not cover changed areas: " + ", ".join(missing_scope),
                )
            ]
        return [
            Finding(
                "error",
                "cross-agent-critique",
                "Cleared/quarantined critique scopes do not collectively cover changed areas: " + ", ".join(missing_scope),
            )
        ]
    return []


def check_mandatory_workflow_fallbacks() -> list[Finding]:
    path = ROOT / "docs/superpowers/WORKING_SYSTEM.md"
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    required = (
        "Provider-agnostic fallback for parity-close",
        "Provider-agnostic fallback for parity-ship",
    )
    missing = [phrase for phrase in required if phrase not in text]
    if missing:
        return [
            Finding(
                "error",
                "workflow-fallbacks",
                "WORKING_SYSTEM.md is missing mandatory workflow fallback docs: " + ", ".join(missing),
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
    findings += check_codex_surface()
    findings += check_required_docs()
    findings += check_mandatory_workflow_fallbacks()
    findings += check_cross_agent_critique(files, args.strict)
    findings += check_docs_with_code(files, args.strict)
    findings += check_behavior_evidence(files, args.strict)
    findings += check_gateway_server_registry()
    findings += check_production_seed_default()
    findings += check_no_baked_secondary_servers()

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
