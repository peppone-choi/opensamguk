#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "missing $1"
}

require_file .codex/config.toml
require_file .codex/hooks.json
require_file scripts/agent/project-skills.sh
require_file scripts/agent/codex-session-start.sh
require_file scripts/agent/codex-post-tool-use.sh
require_file scripts/agent/codex-bash-guard.sh

for agent in \
  deployer \
  fe-submit-wirer \
  golden-capturer \
  intake-wirer \
  parity-gate-runner \
  parity-porter \
  parity-reviewer
do
  require_file ".codex/agents/$agent.toml"
done

for skill in \
  find-project-skill \
  loop-engineering \
  opensamguk-php-oracle \
  opensamguk-working-system \
  os-analyze \
  os-checkpoint \
  os-debug \
  os-e2e \
  os-implement \
  os-plan-tickets \
  os-review \
  os-start-task \
  os-verify \
  parity-close \
  parity-ship
do
  require_file ".agents/skills/$skill/SKILL.md"
done

"${PYTHON_BIN:-python3}" - <<'PY'
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import tomllib
from pathlib import Path

root = Path.cwd()
with (root / ".codex/config.toml").open("rb") as handle:
    config = tomllib.load(handle)
assert config["features"]["hooks"] is True
assert config["features"]["multi_agent"] is True
agents_cfg = config.get("agents", {})
max_threads = agents_cfg.get("max_threads", agents_cfg.get("max_depth", None))
assert max_threads is not None, "tracked-base max_threads/max_depth must be configured"
assert max_threads <= 16, "tracked-base max_threads/max_depth must be <= 16"
with (root / ".codex/hooks.json").open(encoding="utf-8") as handle:
    hooks = json.load(handle)["hooks"]
for event in ("SessionStart", "PreToolUse", "PostToolUse"):
    assert event in hooks
    for group in hooks[event]:
        for handler in group["hooks"]:
            assert handler["type"] == "command"
            assert handler["async"] is False
            assert handler["timeout"] > 0
            assert "timeoutSec" not in handler
session_command = hooks["SessionStart"][0]["hooks"][0]["command"]
assert "codex-session-start.sh" in session_command
assert "project-skills.sh" in (root / "scripts/agent/codex-session-start.sh").read_text(encoding="utf-8")
assert "Bash" in "|".join(group.get("matcher", "") for group in hooks["PreToolUse"])
assert "Bash" in "|".join(group.get("matcher", "") for group in hooks["PostToolUse"])

expected_agents = {
    "deployer",
    "fe-submit-wirer",
    "golden-capturer",
    "intake-wirer",
    "parity-gate-runner",
    "parity-porter",
    "parity-reviewer",
}
for name in expected_agents:
    with (root / ".codex/agents" / f"{name}.toml").open("rb") as handle:
        agent = tomllib.load(handle)
    assert agent["name"] == name
    assert agent["description"]
    assert agent["developer_instructions"]
for name in ("deployer", "parity-reviewer"):
    with (root / ".codex/agents" / f"{name}.toml").open("rb") as handle:
        assert tomllib.load(handle)["sandbox_mode"] == "read-only"
with (root / ".codex/agents/parity-gate-runner.toml").open("rb") as handle:
    assert tomllib.load(handle)["sandbox_mode"] == "workspace-write"

verify_script = (root / "scripts/agent/verify-changes.sh").read_text(encoding="utf-8")
assert "pipefail" in verify_script
assert "BUILD SUCCESSFUL" in verify_script
assert 'PIPESTATUS[0]' in verify_script
assert '-newer "$START_MARKER"' in verify_script
assert "git diff --no-index --check" in verify_script
assert "--strict --base" in verify_script
assert 'git diff --name-only "$BASE_REF...HEAD"' in verify_script

project_skills = (root / "scripts/agent/project-skills.sh").read_text(encoding="utf-8")
assert ".skills-integrity.json" in project_skills
assert "installedHash" in project_skills

spec = importlib.util.spec_from_file_location("agent_system_check", root / "tools/agent-system/check.py")
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    findings = module.check_codex_surface()
assert any(finding.check == "codex-surface" for finding in findings)
assert module.scope_covers_area("tools/assets/", "tools/")
assert not module.scope_covers_area("docs/webapp/", "app/")
assert not module.scope_covers_area("not-tools/", "tools/")

module.ROOT = root
assert not module.check_product_authority_policy()
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    for rel, phrases in module.PRODUCT_AUTHORITY_REQUIRED.items():
        path = module.ROOT / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(phrases) + "\n", encoding="utf-8")
    for rel in module.PRODUCT_AUTHORITY_SURFACES:
        path = module.ROOT / rel
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                "Current product authority follows approved ADR/spec/current implementation.\n",
                encoding="utf-8",
            )
    assert not module.check_product_authority_policy()
    stale = module.ROOT / "docs/superpowers/WORKING_SYSTEM.md"
    baseline = stale.read_text(encoding="utf-8")
    stale.write_text(
        baseline + "PHP legacy behavior is optional historical evidence, not product authority.\n",
        encoding="utf-8",
    )
    assert not module.check_product_authority_policy()
    obsolete_statements = (
        "PHP wins every divergence",
        "PHP is the authoritative product source",
        "PHP remains the source of truth",
        "All new features must first match devsam/core",
        "hwe/ts is authoritative for frontend work",
    )
    for statement in obsolete_statements:
        stale.write_text(baseline + statement + "\n", encoding="utf-8")
        authority_findings = module.check_product_authority_policy()
        assert any(
            finding.check == "product-authority"
            and "obsolete mandatory legacy authority" in finding.message
            for finding in authority_findings
        ), statement
    stale.write_text(baseline, encoding="utf-8")
    surface_baselines = {
        rel: (module.ROOT / rel).read_text(encoding="utf-8")
        for rel in module.PRODUCT_AUTHORITY_SURFACES
    }
    for rel, surface_baseline in surface_baselines.items():
        surface = module.ROOT / rel
        surface.write_text(surface_baseline + "PHP remains the source of truth\n", encoding="utf-8")
        authority_findings = module.check_product_authority_policy()
        assert any(
            finding.check == "product-authority"
            and finding.message.startswith(f"{rel}:")
            for finding in authority_findings
        ), rel
        surface.write_text(surface_baseline, encoding="utf-8")
module.ROOT = root

def critique_findings(code_files: list[str], reviews: dict[str, str]):
    with tempfile.TemporaryDirectory() as tmp:
        module.ROOT = Path(tmp)
        working = module.ROOT / "docs/superpowers/WORKING_SYSTEM.md"
        working.parent.mkdir(parents=True)
        working.write_text(
            "Cross-agent critique\nfix-required\nquarantined-with-proof\n",
            encoding="utf-8",
        )
        for rel, content in reviews.items():
            review = module.ROOT / rel
            review.parent.mkdir(parents=True, exist_ok=True)
            review.write_text(content, encoding="utf-8")
        return module.check_cross_agent_critique([*code_files, *reviews], strict=True)


historical_review = "docs/superpowers/reviews/historical-narrow.md"
disjoint_review = "docs/superpowers/reviews/disjoint-tools.md"
findings = critique_findings(
    ["scripts/agent/example.sh", "tools/example.py"],
    {
        historical_review: "Scope: scripts/agent/\nVerdict: cleared\n",
        disjoint_review: "Scope: tools/\nVerdict: cleared\n",
    },
)
assert not any(finding.check == "cross-agent-critique" for finding in findings)

findings = critique_findings(
    ["scripts/agent/example.sh", "tools/example.py"],
    {
        historical_review: "Scope: scripts/agent/\nVerdict: cleared\n",
        disjoint_review: "Scope: docs/\nVerdict: cleared\n",
    },
)
cross_agent_findings = [finding for finding in findings if finding.check == "cross-agent-critique"]
assert len(cross_agent_findings) == 1
assert "collectively cover changed areas: tools/" in cross_agent_findings[0].message

single_review = "docs/superpowers/reviews/single-review.md"
findings = critique_findings(
    ["scripts/agent/example.sh", "tools/example.py"],
    {single_review: "Scope: scripts/agent/\nVerdict: cleared\n"},
)
assert any(
    finding.message == f"{single_review} does not cover changed areas: tools/"
    for finding in findings
)

findings = critique_findings(
    ["scripts/agent/example.sh", "tools/example.py"],
    {single_review: "Scope: `scripts/agent/`; tools/, common/\nVerdict: cleared\n"},
)
assert not any(finding.check == "cross-agent-critique" for finding in findings)

findings = critique_findings(
    ["tools/example.py"],
    {
        single_review: (
            "Scope: `tools/`\n"
            "Verdict: quarantined-with-proof\n"
            "Proof: fixture-evidence\n"
        )
    },
)
assert not any(finding.check == "cross-agent-critique" for finding in findings)

findings = critique_findings(
    ["app/example.py"],
    {single_review: "Scope: docs/webapp/\nVerdict: cleared\n"},
)
assert any(
    finding.message == f"{single_review} does not cover changed areas: app/"
    for finding in findings
)

findings = critique_findings(
    ["tools/example.py"],
    {single_review: "Scope: not-tools/\nVerdict: cleared\n"},
)
assert any(
    finding.message == f"{single_review} does not cover changed areas: tools/"
    for finding in findings
)

findings = critique_findings(
    ["scripts/agent/example.sh"],
    {single_review: "Verdict: cleared\n"},
)
assert any("exactly one anchored Scope and Verdict" in finding.message for finding in findings)

findings = critique_findings(
    ["scripts/agent/example.sh"],
    {single_review: "Scope: scripts/agent/\nVerdict: fix-required\n"},
)
assert any("fix-required blocks" in finding.message for finding in findings)

findings = critique_findings(
    ["scripts/agent/example.sh"],
    {single_review: "Scope: scripts/agent/\nVerdict: quarantined-with-proof\n"},
)
assert any("without an anchored Proof line" in finding.message for finding in findings)
module.ROOT = root
PY

set +e
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: .env.local\n@@\n-secret\n+secret\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
blocked_status=$?
set -e
[ "$blocked_status" -eq 2 ] || fail "Codex apply_patch did not block .env.local"

set +e
printf '%s' '{malformed' | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
malformed_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
pathless_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: README.md\n@@\n-old\n+new\n*** Update File: legacy/devsam-core/index.php\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
legacy_status=$?
scripts/agent/protect-sensitive-files.sh README.md edit >/dev/null 2>&1
mode_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: .mcp.json\n@@\n-{}\n+{\"token\":\"sk-AAAAAAAAAAAAAAAAAAAA\"}\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
mcp_status=$?
set -e
[ "$malformed_status" -eq 2 ] || fail "malformed hook JSON did not fail closed"
[ "$pathless_status" -eq 2 ] || fail "pathless hook input did not fail closed"
[ "$legacy_status" -eq 2 ] || fail "multi-file apply_patch did not block legacy write"
[ "$mode_status" -eq 2 ] || fail "unknown manual guard mode was accepted"
[ "$mcp_status" -eq 2 ] || fail "literal MCP token pattern was accepted"

set +e
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: README.md\n*** Move to: .env.local\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
move_secret_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: README.md\n*** Move to: legacy/devsam-core/README.md\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
move_legacy_status=$?
set -e
[ "$move_secret_status" -eq 2 ] || fail "apply_patch Move to did not block a secret destination"
[ "$move_legacy_status" -eq 2 ] || fail "apply_patch Move to did not block a legacy destination"

printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: README.md\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1 \
  || fail "Codex apply_patch blocked a safe path"

printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git status --short"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a safe command"
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"sed -n 1,20p legacy/devsam-core/index.php"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a read-only legacy command"

set +e
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"cat .env.local"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1
bash_secret_status=$?
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"printf x > legacy/devsam-core/index.php"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1
bash_legacy_status=$?
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"printf x | tee logic/src/test/resources/golden/x.json"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1
bash_golden_status=$?
set -e
[ "$bash_secret_status" -eq 2 ] || fail "Codex Bash guard allowed a secret read"
[ "$bash_legacy_status" -eq 2 ] || fail "Codex Bash guard allowed a legacy write"
[ "$bash_golden_status" -eq 2 ] || fail "Codex Bash guard allowed a golden write"

help_output="$(scripts/agent/project-skills.sh --help)"
for command in restore find inspect add list update; do
  printf '%s\n' "$help_output" | grep -q "$command" || fail "project-skills help is missing $command"
done

for path in \
  .codex/config.toml \
  .codex/hooks.json \
  .codex/agents/deployer.toml \
  .agents/skills/os-verify/SKILL.md \
  .agents/skills/find-project-skill/SKILL.md
do
  if git check-ignore -q "$path"; then
    fail "$path is still ignored"
  fi
done

printf 'PASS: Codex Agent OS contract\n'
