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
EXPECTED_ADR_LITE_042_CONTRACTS = (
    (
        ".ai/decisions.md",
        "## ADR-LITE-042 — PHP 패러티를 설계 제약에서 해제한다 (2026-08-20)",
        "<!-- ADR-LITE-042-CONTRACT retired=php_grand_truth,php_wins,draw_for_draw,byte_log,golden_first; retained=truthfulness,frozen_baseline,replay_determinism,one_daemon_write,flush_delta,insertion_order -->",
    ),
    (
        "CLAUDE.md",
        "## Product and regression discipline (ADR-LITE-042, 2026-08-20)",
        "<!-- ADR-LITE-042-RULES replay_determinism,numerical_change_record,stable_logs_and_order,flush_delta,no_fabrication_or_weakening,insertion_order -->",
    ),
)
assert module.ADR_LITE_042_CONTRACTS == EXPECTED_ADR_LITE_042_CONTRACTS
EXPECTED_ADR_LITE_042_PROSE = {
    ".ai/decisions.md": (
        "예전의 **`PHP wins`** 우선 규칙은 은퇴했고",
        "RNG draw-for-draw 일치. 드로우 개수·순서·인자를 PHP 에 맞출 의무가 없다.",
        "한국어 로그 바이트 일치. 로그는 이제 UX 산출물이지 게이트가 아니다.",
        "즉 **golden-first** 작업 순서는 신규 제품 작업의 기본 게이트가 아니다.",
        "**거짓 완료 금지.**",
        "**기존 골든·테스트는 지우지 않는다.**",
        "**리플레이 결정론.**",
        "**one-daemon-write-rule**",
        "삽입 순서 보존, flush 델타 규약",
    ),
    "CLAUDE.md": (
        "PHP parity porting and its `PHP wins`/golden-first gates are retired.",
        "New work does not require PHP draw-for-draw, byte-log parity, or an oracle capture.",
        "1. **Deterministic replay.**",
        "2. **Intentional numerical changes.**",
        "3. **Stable logs and ordering.**",
        "4. **Flush delta, not inline writes.**",
        "5. **Never fabricate or weaken evidence.**",
        "6. **Insertion order matters.**",
    ),
}
assert module.ADR_LITE_042_PROSE_REQUIRED == EXPECTED_ADR_LITE_042_PROSE
EXPECTED_PARTIAL_SUPERSESSION_HEADINGS = (
    "## ADR-LITE-010 v2 콘텐츠 정체성 — RTK 종합으로 devsam 콘텐츠 대체",
    "## ADR-LITE-018 v1을 오리지널로 동결하고 v2 뉴버전을 상시 운영으로 삼는다",
)
assert module.PARTIALLY_SUPERSEDED_BY_ADR_LITE_042 == EXPECTED_PARTIAL_SUPERSESSION_HEADINGS
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    findings = module.check_codex_surface()
assert any(finding.check == "codex-surface" for finding in findings)
assert module.scope_covers_area("tools/assets/", "tools/")
assert not module.scope_covers_area("docs/webapp/", "app/")
assert not module.scope_covers_area("not-tools/", "tools/")

module.ROOT = root
assert not module.check_product_authority_policy()
assert not module.check_adr_lite_042_contract()
expected_active_policy_surfaces = {
    "scripts/agent/protect-sensitive-files.sh",
    "docs/agent/prompt-pack.md",
    ".claude/commands/os-debug.md",
    "tools/php-golden/README.md",
    "docs/agent/codex-user-manual.md",
    ".coderabbit.yaml",
    "logic/src/main/kotlin/opensamguk/logic/util/StringUtil.kt",
    "logic/src/main/kotlin/opensamguk/logic/constraints/Presets.kt",
}
assert expected_active_policy_surfaces <= set(module.PRODUCT_AUTHORITY_SURFACES)
EXPECTED_PRODUCT_AUTHORITY_SOURCE_GLOBS = (
    "*.gradle.kts", "settings.gradle.kts", "**/build.gradle.kts",
    "**/src/main/**/*.kt", "**/src/main/**/*.kts", "**/src/main/**/*.java",
    "**/src/baseline/**/*.kt", "**/src/baseline/**/*.kts", "**/src/baseline/**/*.java",
    "web/**/*.ts", "web/**/*.tsx", "web/**/*.js", "web/**/*.mjs", "web/**/*.cjs",
    "tools/**/*.py", "tools/**/*.sh", "tools/**/*.ts", "tools/**/*.js", "tools/**/*.mjs", "tools/**/*.cjs",
    "scripts/**/*.py", "scripts/**/*.sh", "**/*.yaml", "**/*.yml",
)
assert module.PRODUCT_AUTHORITY_SOURCE_GLOBS == EXPECTED_PRODUCT_AUTHORITY_SOURCE_GLOBS
EXPECTED_PRODUCT_AUTHORITY_FIXTURE_SURFACES = {
    "scripts/agent/test-codex-agent-os.sh",
    "tools/agent-system/check.py",
}
assert module.PRODUCT_AUTHORITY_FIXTURE_SURFACES == EXPECTED_PRODUCT_AUTHORITY_FIXTURE_SURFACES
for active_rel in (
    "logic/src/main/kotlin/opensamguk/logic/util/StringUtil.kt",
    "build.gradle.kts",
    "settings.gradle.kts",
    "app/game-api/build.gradle.kts",
    "app/game-engine/src/baseline/kotlin/opensamguk/engine/baseline/CqrsBaselineMain.kt",
    "web/game/app/game/vote/page.tsx",
    "tools/php-golden/compare-command-logs/compare-command-logs.mjs",
    "scripts/agent/codex-bash-guard.sh",
    ".coderabbit.yaml",
):
    active = root / active_rel
    assert module.is_active_product_authority_source(active), active_rel
for excluded_rel in (
    "logic/src/test/kotlin/example/FrozenTest.kt",
    "web/game/__tests__/Frozen.spec.ts",
    "tools/rtk-faces/tests/test_pipeline.py",
    "tools/rtk14/test_build_rtk14_stats.py",
    "tools/example/generated/current.py",
    "legacy/example/old.py",
    "scripts/agent/test-codex-agent-os.sh",
    "tools/agent-system/check.py",
):
    excluded = root / excluded_rel
    assert not module.is_active_product_authority_source(excluded), excluded_rel
EXPECTED_IMPLEMENTATION_AUTHORITY_SCOPES = {
    "docs/superpowers/MILESTONES.md": (
        "ADR-LITE-042", "동결 회귀 green + 운영 안정", "PHP 패러티 close를 선행 조건으로 삼지 않는다",
        "승인 ADR/spec·현재 구현이 제품 정본",
    ),
    "tools/php-golden/compare-command-logs/README.md": (
        "ADR-LITE-042", "opt-in historical frozen-regression comparison tool", "not current product authority",
    ),
    ".ai/known-issues.md": (
        "동결 회귀·테스트 disposition 백로그",
        "ADR-LITE-042",
        "PHP 캡처는 opt-in 역사 증거",
    ),
    "docs/agent/lifecycle-review.md": (
        "ADR-LITE-042",
        "현재 승인 ADR/spec·구현·테스트 결과",
        "명시적으로 선택한 역사 비교에서만 PHP 증거",
        "opt-in 역사 동결 회귀 범위에서만 PHP 비교 증거",
    ),
    "docs/agent/codex-user-manual.md": (
        "ADR-LITE-042",
        "opt-in 역사 동결 회귀 유지보수",
        "현재 제품 정본이 아니다",
        "PHP 원작은 명시적으로 요청된 역사 비교에서만 참고한다",
    ),
    ".coderabbit.yaml": ("ADR-LITE-042", "동결 회귀 기준선", "현재 제품 정본이라는 뜻은 아니다"),
    "logic/src/main/kotlin/opensamguk/logic/util/StringUtil.kt": (
        "ADR-LITE-042",
        "frozen historical",
        "compatibility baseline",
        "not current product authority",
    ),
    "logic/src/main/kotlin/opensamguk/logic/constraints/Presets.kt": (
        "ADR-LITE-042",
        "frozen historical PHP baseline",
        "not current product authority",
    ),
}
for rel, phrases in EXPECTED_IMPLEMENTATION_AUTHORITY_SCOPES.items():
    assert module.PRODUCT_AUTHORITY_REQUIRED[rel] == phrases
assert module.PRODUCT_AUTHORITY_REQUIRED["docs/agent/verification.md"] == (
    "명시적 역사 parity 유지보수일 때만",
    "현재 spec 테스트 green",
    "golden 기대값 갱신은 훅 NOTICE",
    "Golden path:",
    "Golden change reason:",
    "Regression command:",
    "Regression evidence:",
    "Critique: CLEARED",
)
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    for rel, heading, marker in EXPECTED_ADR_LITE_042_CONTRACTS:
        contract = module.ROOT / rel
        contract.parent.mkdir(parents=True, exist_ok=True)
        contract.write_text((root / rel).read_text(encoding="utf-8"), encoding="utf-8")
    assert not module.check_adr_lite_042_contract()
    for rel, heading, marker in EXPECTED_ADR_LITE_042_CONTRACTS:
        contract = module.ROOT / rel
        baseline = (root / rel).read_text(encoding="utf-8")
        contract.write_text(f"{heading}\n\n{marker}\n", encoding="utf-8")
        assert any(finding.message.startswith(rel) for finding in module.check_adr_lite_042_contract())
        invalid_marker = baseline.replace("replay_determinism", "replay_determinism_broken", 1)
        contract.write_text(invalid_marker, encoding="utf-8")
        assert any(finding.message.startswith(rel) for finding in module.check_adr_lite_042_contract())
        conflicting_marker = marker.replace("retained=", "retained=conflict,")
        contract.write_text(baseline.replace(marker, marker + "\n" + conflicting_marker, 1), encoding="utf-8")
        assert any(finding.message.startswith(rel) for finding in module.check_adr_lite_042_contract())
        contract.write_text(baseline + "\n" + conflicting_marker + "\n", encoding="utf-8")
        assert any(finding.message.startswith(rel) for finding in module.check_adr_lite_042_contract())
        contract.write_text(baseline, encoding="utf-8")
    decisions_rel, decisions_heading, decisions_marker = EXPECTED_ADR_LITE_042_CONTRACTS[0]
    (module.ROOT / decisions_rel).write_text(
        f"{decisions_heading} test\n\n{decisions_marker}\n", encoding="utf-8"
    )
    assert any(finding.message.startswith(decisions_rel) for finding in module.check_adr_lite_042_contract())
    for rel, required_phrases in EXPECTED_ADR_LITE_042_PROSE.items():
        source = root / rel
        target = module.ROOT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        baseline = source.read_text(encoding="utf-8")
        target.write_text(baseline, encoding="utf-8")
        for phrase in required_phrases:
            target.write_text(baseline.replace(phrase, "contradicted-policy", 1), encoding="utf-8")
            findings = module.check_adr_lite_042_contract()
            assert any(
                finding.message == f"{rel} has incomplete ADR-LITE-042 policy prose."
                for finding in findings
            ), phrase
        marker = next(marker for contract_rel, _, marker in EXPECTED_ADR_LITE_042_CONTRACTS if contract_rel == rel)
        contradictions = (
            "PHP must remain the mandatory oracle for all product changes.",
            "PHP is not the product authority, but PHP remains the mandatory oracle for every change.",
            "PHP가 정본이어야 한다. 예외는 없다.",
            "PHP가 정본이어야 한다는 데 이견이 없다.",
            "PHP는 더 이상 정본이 아니다. 하지만 PHP가 필수 오라클이어야 한다.",
        )
        for contradiction in contradictions:
            target.write_text(baseline.replace(marker, marker + "\n" + contradiction, 1), encoding="utf-8")
            assert any(
                finding.message == f"{rel} contradicts ADR-LITE-042 policy."
                for finding in module.check_adr_lite_042_contract()
            ), contradiction
        retained_weakenings = (
            "Replay determinism is no longer required.",
            "The one-daemon-write-rule is advisory and may be bypassed.",
            "Flush delta is advisory; inline writes are allowed.",
            "Insertion order is irrelevant.",
            "Frozen baseline tests can be skipped.",
            "Evidence integrity is optional.",
        )
        for weakening in retained_weakenings:
            target.write_text(baseline.replace(marker, marker + "\n" + weakening, 1), encoding="utf-8")
            assert any(
                finding.message == f"{rel} contradicts ADR-LITE-042 policy."
                for finding in module.check_adr_lite_042_contract()
            ), weakening
        allowed_negation = "PHP가 더 이상 정본이어야 한다는 규칙은 없다."
        target.write_text(baseline.replace(marker, marker + "\n" + allowed_negation, 1), encoding="utf-8")
        assert not any(
            finding.message == f"{rel} contradicts ADR-LITE-042 policy."
            for finding in module.check_adr_lite_042_contract()
        )
        combined = allowed_negation + "\nPHP가 정본이어야 한다. 예외는 없다."
        target.write_text(baseline.replace(marker, marker + "\n" + combined, 1), encoding="utf-8")
        assert any(
            finding.message == f"{rel} contradicts ADR-LITE-042 policy."
            for finding in module.check_adr_lite_042_contract()
        )
        for same_line in (
            "PHP가 정본이어야 한다. " + allowed_negation,
            allowed_negation + " PHP가 정본이어야 한다. 예외는 없다.",
        ):
            target.write_text(baseline.replace(marker, marker + "\n" + same_line, 1), encoding="utf-8")
            assert any(
                finding.message == f"{rel} contradicts ADR-LITE-042 policy."
                for finding in module.check_adr_lite_042_contract()
            ), same_line
        target.write_text(baseline, encoding="utf-8")
    decisions = module.ROOT / ".ai/decisions.md"
    baseline = decisions.read_text(encoding="utf-8")
    partial_status = "- Status: approved; parity-authority clauses superseded by ADR-LITE-042"
    for heading in EXPECTED_PARTIAL_SUPERSESSION_HEADINGS:
        section_start = baseline.index(heading)
        status_start = baseline.index(partial_status, section_start)
        invalid = baseline[:status_start] + "- Status: approved" + baseline[status_start + len(partial_status):]
        decisions.write_text(invalid, encoding="utf-8")
        assert any(heading in finding.message for finding in module.check_adr_lite_042_contract())
        section_end = baseline.find("\n## ", section_start + 1)
        if section_end < 0:
            section_end = len(baseline)
        conflicting_status = baseline[:section_end] + "\n- Status: approved\n" + baseline[section_end:]
        decisions.write_text(conflicting_status, encoding="utf-8")
        assert any(heading in finding.message for finding in module.check_adr_lite_042_contract())
        decisions.write_text(baseline, encoding="utf-8")
module.ROOT = root
assert not module.check_current_handoff_authority()
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    task_contract = module.ROOT / ".ai/task.md"
    task_contract.parent.mkdir(parents=True, exist_ok=True)
    task_contract.write_text(
        "# Current Task\n\n## 2026-08-21 — OPENSAM-206~220 integration wave (활성 계약)\n",
        encoding="utf-8",
    )
    current_handoffs = {
        ".ai/handoff.md": (
            "# Agent Handoff\n\n## Current handoff (2026-08-23) — OPENSAM-206~220 integration wave; "
            "OPENSAM-218 policy gate\nADR-LITE-042\nPR #499\n"
        ),
        "docs/superpowers/SESSION_HANDOFF.md": (
            "# SESSION HANDOFF — 2026-08-23 (OPENSAM-206~220 integration wave; "
            "OPENSAM-218 policy gate)\nADR-LITE-042\nPR #499\n"
        ),
    }
    for rel, current in current_handoffs.items():
        target = module.ROOT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(current + "\n---\nHistorical OPENSAM-43 at 8abb47a1\n", encoding="utf-8")
    assert not module.check_current_handoff_authority(), "historical handoff body leaked into current-section check"
    stale = module.ROOT / ".ai/handoff.md"
    stale.write_text(stale.read_text(encoding="utf-8").replace("OPENSAM-206~220", "OPENSAM-43", 1), encoding="utf-8")
    assert module.check_current_handoff_authority(), "stale current handoff authority was accepted"
    stale.write_text(current_handoffs[".ai/handoff.md"] + "\n---\nHistorical OPENSAM-43 at 8abb47a1\n", encoding="utf-8")
    session_stale = module.ROOT / "docs/superpowers/SESSION_HANDOFF.md"
    session_stale.write_text(
        session_stale.read_text(encoding="utf-8").replace("OPENSAM-206~220", "OPENSAM-43", 1),
        encoding="utf-8",
    )
    assert module.check_current_handoff_authority(), "stale current session handoff authority was accepted"
    task_contract.write_text(
        "# Current Task\n\n## 2026-09-01 — OPENSAM-221 release transition (활성 계약)\n",
        encoding="utf-8",
    )
    for rel in current_handoffs:
        target = module.ROOT / rel
        transitioned = target.read_text(encoding="utf-8").replace("OPENSAM-43", "OPENSAM-221", 1)
        transitioned = transitioned.replace("OPENSAM-206~220", "OPENSAM-221", 1)
        target.write_text(transitioned, encoding="utf-8")
    assert not module.check_current_handoff_authority(), "a new current task still required OPENSAM-218/PR #499 metadata"
module.ROOT = root
with tempfile.TemporaryDirectory() as tmp:
    module.ROOT = Path(tmp)
    review = module.ROOT / "docs/superpowers/reviews/golden-change.md"
    review.parent.mkdir(parents=True)
    custom_gate = module.ROOT / "custom-regression-gate"
    custom_gate.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    custom_gate.chmod(0o755)
    custom_python_test = module.ROOT / "custom_python_test.py"
    custom_python_test.write_text("raise SystemExit(0)\n", encoding="utf-8")
    custom_shell_check = module.ROOT / "custom-shell-check.sh"
    custom_shell_check.write_text("exit 0\n", encoding="utf-8")
    review_rel = "docs/superpowers/reviews/golden-change.md"
    golden_files = ["common/src/test/resources/golden/city_const.golden.json", "docs/superpowers/reviews/golden-change.md"]
    assert module.check_golden_update_evidence(golden_files, strict=True, review_additions={})
    valid_evidence = (
        "Golden path: common/src/test/resources/golden/city_const.golden.json\n"
        "Golden change reason: Approved combat formula change updates the expected damage.\n"
        "Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks\n"
        "Regression evidence: PASS — city_const.golden.json ExampleGoldenTest completed with failures=0.\n"
        "Critique: CLEARED — reviewer confirmed city_const.golden.json matches the approved rule.\n"
    )
    review.write_text(valid_evidence, encoding="utf-8")
    assert not module.check_golden_update_evidence(
        golden_files, strict=True, review_additions={review_rel: valid_evidence}
    )
    for invalid_evidence in (
        "Golden path: common/src/test/resources/golden/city_const.golden.json\nGolden change reason: x\nRegression command: x\nRegression evidence: x\nCritique: CLEARED\n",
        valid_evidence.replace("common/src/test/resources/golden/city_const.golden.json", "logic/src/test/resources/golden/other.json"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: TODO"),
        valid_evidence.replace("Regression evidence: PASS — city_const.golden.json ExampleGoldenTest completed with failures=0.", "Regression evidence: pending"),
        valid_evidence.replace("Critique: CLEARED — reviewer confirmed city_const.golden.json matches the approved rule.", "Critique: pending"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: git status"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: git diff --check"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: ./gradlew tasks"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: mvn help:help"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: python3 -m pytest --collect-only"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: python -m pytest --help"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: python3 -m pytest --collect-only=yes"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: pytest --fixtures"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: python3 -m pytest --fixtures-per-test"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: pytest --setup-plan"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: pytest --markers"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: ./gradlew :logic:test --dry-run"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: mvn test -DskipTests"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: cargo test --no-run"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: pytest --co"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: python3 -m pytest --co"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: npm test -- --listTests"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: yarn test --listTests"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: go test -list ."),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: go test -list=."),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: echo PASS"),
        valid_evidence.replace("Regression command: ./gradlew :logic:test --tests ExampleGoldenTest --rerun-tasks", "Regression command: bash -c 'echo test PASS'"),
        valid_evidence.replace("Golden change reason: Approved combat formula change updates the expected damage.", "Golden change reason: FIXME explain later"),
        valid_evidence.replace(
            "Golden change reason: Approved combat formula change updates the expected damage.",
            "Golden change reason: N-A because unknown",
        ),
        (
            "Golden path: common/src/test/resources/golden/city_const.golden.json\n"
            "Golden change reason: placeholder text here\n"
            "Regression command: not actually executed\n"
            "Regression evidence: PASS — placeholder evidence\n"
            "Critique: CLEARED — placeholder critique\n"
        ),
    ):
        review.write_text(invalid_evidence, encoding="utf-8")
        assert module.check_golden_update_evidence(
            golden_files, strict=True, review_additions={review_rel: invalid_evidence}
        ), invalid_evidence
    assert module.is_regression_command("mvn test -DskipTests=false")
    assert module.is_regression_command("mvn test -Dmaven.test.skip=false")
    assert module.is_regression_command("go test ./...")
    assert module.is_regression_command("./custom-regression-gate")
    assert not module.is_regression_command("./not-a-real-test")
    assert module.is_regression_command("python3 ./custom_python_test.py")
    assert module.is_regression_command("bash ./custom-shell-check.sh")
    assert not module.is_regression_command("python3 ./not-a-real-test.py")
    assert not module.is_regression_command("bash ./not-a-real-test")
    assert not module.is_regression_command("sh ./not-a-real-test")
    with tempfile.TemporaryDirectory() as outside_tmp:
        outside_gate = Path(outside_tmp) / "outside-regression-gate"
        outside_gate.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        outside_gate.chmod(0o755)
        assert not module.is_regression_command(str(outside_gate)), "runner outside ROOT was accepted"
    review.write_text(valid_evidence, encoding="utf-8")
    assert module.check_golden_update_evidence(
        golden_files, strict=True, review_additions={review_rel: "\n"}
    ), "whitespace-only review change reused stale evidence"
    second_golden = "common/src/test/resources/golden/game_unit_const.golden.json"
    two_golden_files = [golden_files[0], second_golden, golden_files[1]]
    review.write_text(valid_evidence, encoding="utf-8")
    assert module.check_golden_update_evidence(
        two_golden_files, strict=True, review_additions={review_rel: valid_evidence}
    ), "one evidence block was reused"
    review.write_text(
        valid_evidence
        + valid_evidence.replace(golden_files[0], second_golden)
        .replace("city_const.golden.json", "game_unit_const.golden.json")
        .replace("combat formula", "unit catalog"),
        encoding="utf-8",
    )
    two_blocks = review.read_text(encoding="utf-8")
    assert not module.check_golden_update_evidence(
        two_golden_files, strict=True, review_additions={review_rel: two_blocks}
    )
    protected_golden_tests = (
        "logic/src/test/kotlin/opensamguk/logic/actions/ExampleGoldenTest.kt",
        "logic/src/test/kotlin/opensamguk/logic/golden/BattleReplayGateTest.kt",
    )
    for golden_test in protected_golden_tests:
        review.write_text("", encoding="utf-8")
        changed = [golden_test, "docs/superpowers/reviews/golden-change.md"]
        assert module.check_golden_update_evidence(changed, strict=True, review_additions={}), golden_test
        review.write_text(
            f"Golden path: {golden_test}\n"
            "Golden change reason: Approved replay behavior changes the frozen expected assertion.\n"
            "Regression command: ./gradlew :logic:test --tests ReplayGate --rerun-tasks\n"
            f"Regression evidence: PASS — {golden_test} replay gate completed with failures=0.\n"
            f"Critique: CLEARED — reviewer confirmed {golden_test} remains covered.\n",
            encoding="utf-8",
        )
        test_evidence = review.read_text(encoding="utf-8")
        assert not module.check_golden_update_evidence(
            changed, strict=True, review_additions={review_rel: test_evidence}
        ), golden_test
    deleted_golden_entries = [
        ("D", "common/src/test/resources/golden/city_const.golden.json"),
        ("A", "docs/superpowers/reviews/golden-change.md"),
    ]
    assert module.check_golden_deletions(deleted_golden_entries)
    assert module.check_golden_deletions([("D", "app/game-api/src/main/resources/golden/current.json")])
    deleted_test_entries = [("D", "common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt")]
    assert module.check_golden_deletions(deleted_test_entries)
    renamed_out_entries = [("R100", "common/src/test/resources/golden/city_const.golden.json", "docs/example.json")]
    assert module.check_golden_deletions(renamed_out_entries)
    renamed_within_entries = [
        (
            "R100",
            "common/src/test/resources/golden/city_const.golden.json",
            "common/src/test/resources/golden/city_const.renamed.golden.json",
        )
    ]
    assert not module.check_golden_deletions(renamed_within_entries)
    assert not module.check_golden_deletions(
        [("R100", "app/game-api/src/main/resources/golden/current.json", "logic/src/main/resources/golden/moved.json")]
    )
    assert module.check_golden_deletions(
        [("R100", "app/game-api/src/main/resources/golden/current.json", "docs/current.json")]
    )
    assert module.check_golden_deletions(
        [("R100", "common/src/test/resources/golden/city_const.golden.json", "common/src/test/resources/not-golden.json")]
    )
    assert not module.check_golden_deletions([("M", "common/src/test/resources/golden/city_const.golden.json")])
    assert module.check_golden_deletions([("D", "logic/src/test/kotlin/opensamguk/logic/OrdinaryTest.kt")])
    assert module.check_golden_deletions([("D", "web/game/src/components/widget.test.ts")])
    assert module.check_golden_deletions(
        [("R100", "web/game/src/components/widget.test.ts", "web/game/src/components/widget.ts")]
    )
    assert not module.check_golden_deletions(
        [("R100", "web/game/src/components/widget.test.ts", "web/game/src/components/renamed.spec.ts")]
    )
    assert not module.check_golden_deletions(
        [("R100", "web/game/src/components/widget.test.ts", "web/game/__tests__/renamed.test.ts")]
    )
    assert module.parse_name_status_z(
        b"D\x00common/src/test/resources/golden/city_const.golden.json\x00"
        b"R100\x00web/game/src/components/widget.test.ts\x00docs/widget.test.ts\x00"
    ) == [
        ("D", "common/src/test/resources/golden/city_const.golden.json"),
        ("R100", "web/game/src/components/widget.test.ts", "docs/widget.test.ts"),
    ]
    assert not module.is_test_source_path("logic/src/main/kotlin/opensamguk/Submit.kt")
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
        "**Oracle = PHP devsam-core**",
        "deny \"legacy/ 원작(PHP grand truth)",
        "TS reference, PHP wins).",
        "regenerate **ONLY** when the PHP source changes",
        "골든 수정 금지.",
        "골든 픽스처는 직접 수정 금지",
        "갱신이 필요하면 tools/php-golden/ 실 캡처로만",
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
    allowed_retirement_statements = (
        "The old rule Oracle = PHP was retired.",
        "PHP grand truth is no longer our policy.",
        "The phrase PHP wins. names a retired rule.",
        "We deny PHP grand truth as product authority.",
        "Retired legacy rule (PHP wins).",
        "골든 수정 금지 규칙은 승인된 제품 변경에는 적용되지 않는다.",
        "It is no longer true that PHP is the source of truth.",
        "Historical note: PHP wins every divergence was the retired rule.",
        "Do not claim that PHP remains the product authority.",
        "The former rule said PHP is the grand truth; ADR-LITE-042 retired it.",
        "The old handbook described PHP as the grand truth; that policy is retired.",
        "We no longer treat PHP as the grand truth.",
        "과거에는 PHP가 정본이어야 한다는 규칙이 있었다.",
        "PHP가 정본이어야 한다는 규칙은 더 이상 유효하지 않다.",
    )
    for statement in allowed_retirement_statements:
        stale.write_text(baseline + statement + "\n", encoding="utf-8")
        assert not module.check_product_authority_policy(), statement
    combined_authority = (
        "Historical note: PHP wins every divergence was the retired rule.\n"
        "PHP wins every divergence\n"
    )
    stale.write_text(baseline + combined_authority, encoding="utf-8")
    assert module.check_product_authority_policy()
    for combined_clause in (
        "Do not claim that PHP remains the product authority, but PHP remains the product authority.",
        "The old handbook described PHP as the grand truth, but PHP is the grand truth for new work.",
    ):
        stale.write_text(baseline + combined_clause + "\n", encoding="utf-8")
        assert module.check_product_authority_policy(), combined_clause
    stale.write_text(baseline + "PHP is the grand truth\n", encoding="utf-8")
    duplicate_findings = [
        finding
        for finding in module.check_product_authority_policy()
        if finding.message.startswith("docs/superpowers/WORKING_SYSTEM.md:")
    ]
    assert len(duplicate_findings) == 1
    for active_claim in (
        "PHP should remain the source of truth.",
        "All golden expectation updates require a PHP capture.",
        "승인된 제품 변경도 PHP 오라클 캡처가 선행되어야 한다.",
        "PHP 레거시가 모든 동작의 최종 기준이다.",
        "외부 스킬보다 PHP 원작이 우선한다.",
        "fight() 전체 포트와 PHP 골든 캡처가 필요하다.",
        "critic이 PHP 증거를 독립적으로 공격한다.",
        "Retained invariants are optional and may be ignored.",
        "Retained invariants may be ignored.",
        "one-daemon-write-rule may be ignored.",
        "Stable logs and ordering are optional.",
        "replay determinism may be ignored for product changes.",
        "Numerical change record can be skipped.",
        "flush delta need not apply.",
        "no fabrication or weakening can be weakened.",
        "insertion order may be skipped.",
    ):
        stale.write_text(baseline + active_claim + "\n", encoding="utf-8")
        assert module.check_product_authority_policy(), active_claim
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
    repo_source = module.ROOT / "web/game/app/example/Active.tsx"
    repo_source.parent.mkdir(parents=True, exist_ok=True)
    for bare_claim in (
        "PHP grand truth", "PHP grand-truth", "PHP가 grand truth", "PHP가 grand-truth", "GRAND TRUTH = PHP", "PHP wins", "PHP 정본",
        "FE grand truth", "FE grand-truth", "hwe/ts grand-truth", "PHP byte oracle",
        "legacy table remains the parity oracle", "PHP golden DB dump is the authority",
        "Vue 프론트 정본", "Reference oracle TS is the parity target",
        "This mirrors the core2026 oracle", "(G2 byte oracle.)",
        "PHP is the SOLE oracle", "PHP source is the 정본", "Grand-truth: legacy old.php",
        "byte-parity", "패러티 대상", "골든으로 잠긴", "v1 패러티", "PHP oracle",
    ):
        repo_source.write_text(f"// {bare_claim}\n", encoding="utf-8")
        assert any(
            finding.message.startswith("web/game/app/example/Active.tsx:")
            for finding in module.check_product_authority_policy()
        ), bare_claim
    for multiline_claim in (
        "// PHP nation.cities\n// is the grand-truth for all current scenarios\n",
        "// Faithful PHP port\n// These are the grand-truth keys for new work\n",
    ):
        repo_source.write_text(multiline_claim, encoding="utf-8")
        assert any(
            finding.message.startswith("web/game/app/example/Active.tsx:")
            for finding in module.check_product_authority_policy()
        ), multiline_claim
    repo_source.write_text(
        "// frozen historical PHP baseline (ADR-LITE-042; not current product authority)\n",
        encoding="utf-8",
    )
    assert not any(
        finding.message.startswith("web/game/app/example/Active.tsx:")
        for finding in module.check_product_authority_policy()
    )
    for historical_claim in (
        "// historical byte-parity frozen regression; not current product authority\n",
        "// historical PHP grand-truth frozen regression; not current product authority\n",
        "// historical FE grand-truth frozen regression; not current product authority\n",
        "// 역사 PHP 정본 비교 (ADR-LITE-042; 현재 제품 정본 아님)\n",
        "// Historical PHP grand-truth\n// retired by ADR-LITE-042; not current product authority\n",
    ):
        repo_source.write_text(historical_claim, encoding="utf-8")
        assert not any(
            finding.message.startswith("web/game/app/example/Active.tsx:")
            for finding in module.check_product_authority_policy()
        ), historical_claim
    for restored_claim in (
        "// historical byte-parity was retired, but byte-parity is mandatory for v2\n",
        "// historical PHP grand-truth was retired, but PHP grand-truth is mandatory for v2\n",
        "// historical FE grand-truth was retired, but FE grand-truth is mandatory for new work\n",
        "// 역사 PHP 정본 규칙은 은퇴했지만 PHP 정본을 신규 작업에 유지한다\n",
    ):
        repo_source.write_text(restored_claim, encoding="utf-8")
        assert any(
            finding.message.startswith("web/game/app/example/Active.tsx:")
            for finding in module.check_product_authority_policy()
        ), restored_claim
    for rel, phrases in EXPECTED_IMPLEMENTATION_AUTHORITY_SCOPES.items():
        surface = module.ROOT / rel
        scoped_baseline = surface.read_text(encoding="utf-8")
        for phrase in phrases:
            surface.write_text(scoped_baseline.replace(phrase, "removed-scope", 1), encoding="utf-8")
            assert any(
                finding.message.startswith(f"{rel} is missing ADR-LITE-042 policy phrase")
                for finding in module.check_product_authority_policy()
            ), (rel, phrase)
        surface.write_text(scoped_baseline + "PHP wins every divergence\n", encoding="utf-8")
        assert any(
            finding.message.startswith(f"{rel}:")
            for finding in module.check_product_authority_policy()
        ), rel
        surface.write_text(scoped_baseline, encoding="utf-8")
    parity_reviewer = module.ROOT / ".claude/agents/parity-reviewer.md"
    parity_baseline = parity_reviewer.read_text(encoding="utf-8")
    for phrase in module.PRODUCT_AUTHORITY_REQUIRED[".claude/agents/parity-reviewer.md"]:
        parity_reviewer.write_text(parity_baseline.replace(phrase, "removed-policy", 1), encoding="utf-8")
        assert any(
            finding.message.startswith(".claude/agents/parity-reviewer.md is missing ADR-LITE-042 policy phrase")
            for finding in module.check_product_authority_policy()
        ), phrase
    parity_reviewer.write_text(parity_baseline, encoding="utf-8")
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

golden_notice="$(printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: common/src/test/resources/golden/city_const.golden.json\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh 2>&1)" \
  || fail "Codex apply_patch blocked an intentional frozen-baseline update"
printf '%s\n' "$golden_notice" | grep -q "Regression command:" \
  || fail "golden update notice omitted the ADR-LITE-042 evidence requirement"

set +e
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Delete File: common/src/test/resources/golden/city_const.golden.json\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
delete_golden_fixture_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Delete File: common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
delete_golden_test_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Delete File: common/src/test/resources/golden/city_const.golden.json\nGolden change reason: approved\nRegression evidence: passed\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
delete_golden_boilerplate_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: common/src/test/resources/golden/city_const.golden.json\n*** Move to: docs/example.json\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
rename_golden_out_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: common/src/test/resources/golden/city_const.golden.json\n*** Move to: common/src/test/resources/golden/city_const.renamed.golden.json\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
rename_golden_within_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: common/src/test/resources/golden/city_const.golden.json\n*** Move to: common/src/test/resources/city_const.golden.json\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
rename_golden_to_test_only_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Delete File: app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
delete_ordinary_test_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: web/game/__tests__/GameInfo.test.tsx\n*** Move to: web/game/GameInfo.tsx\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
rename_test_out_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: web/game/__tests__/GameInfo.test.tsx\n*** Move to: docs/GameInfo.test.tsx\n@@\n-old\n+new\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
rename_test_like_docs_status=$?
printf '%s' '{"tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Delete File: logic/src/test/kotlin/NewUntrackedTest.kt\n*** End Patch"}}' \
  | scripts/agent/protect-sensitive-files.sh >/dev/null 2>&1
delete_new_test_status=$?
set -e
[ "$delete_golden_fixture_status" -eq 2 ] || fail "Codex apply_patch allowed a golden fixture deletion"
[ "$delete_golden_test_status" -eq 2 ] || fail "Codex apply_patch allowed a golden test deletion"
[ "$delete_golden_boilerplate_status" -eq 2 ] || fail "golden deletion bypassed hook with evidence boilerplate"
[ "$rename_golden_out_status" -eq 2 ] || fail "golden rename escaped the protected path boundary"
[ "$rename_golden_within_status" -eq 0 ] || fail "protected-to-protected golden rename was blocked"
[ "$rename_golden_to_test_only_status" -eq 2 ] || fail "golden escaped its protected golden surface"
[ "$delete_ordinary_test_status" -eq 2 ] || fail "ordinary tracked test deletion bypassed hook"
[ "$rename_test_out_status" -eq 2 ] || fail "tracked test rename escaped the test surface"
[ "$rename_test_like_docs_status" -eq 0 ] || fail "recognized test-source rename destination was blocked"
[ "$delete_new_test_status" -eq 0 ] || fail "new untracked test deletion was blocked"

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

guard_external_root="$(mktemp -d)"
trap 'rm -rf "$guard_external_root"' EXIT
guard_external_repo="$guard_external_root/sibling-worktree"
guard_external_link="$guard_external_root/sibling-link"
mkdir -p "$guard_external_repo/src/test/kotlin"
cp common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt "$guard_external_repo/src/test/kotlin/ProtectedTest.kt"
git -C "$guard_external_repo" init -q
git -C "$guard_external_repo" add src/test/kotlin/ProtectedTest.kt
ln -s "$guard_external_repo" "$guard_external_link"
guard_external_relative="$(python3 -c 'import os, sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$guard_external_repo" "$ROOT")"
guard_source_fixture="$guard_external_root/op218-env.sh"
printf 'export PYTHONPATH=.ai\n' > "$guard_source_fixture"
guard_zsh_autoload_fixture="$guard_external_root/guarded"
printf 'export PYTHONPATH=.ai\n' > "$guard_zsh_autoload_fixture"
emulate_command="zsh -fc 'emulate zsh -c \"source $guard_source_fixture; python3.13 tools/agent-system/check.py\"'"
set +e
printf '%s' "$emulate_command" \
  | python3 -c 'import json, sys; print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.stdin.read()}}))' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1
emulate_status=$?
set -e
[ "$emulate_status" -eq 2 ] || fail "Codex Bash guard allowed zsh emulate meta-evaluation"

for protected_command in \
  "rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "/bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "env rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "env /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "env -u FOO /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "env -C common /bin/rm src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "env --chdir=common /bin/rm src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "sudo -n rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "sudo --user root /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "sudo --chdir common /bin/rm src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "command -p /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "exec /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "exec -- /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "nice /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "nice -n 5 /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "nice --adjustment=5 /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "time /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "time -p -- /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "timeout 5 /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "timeout -k 1 5 /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "stdbuf -o0 /bin/rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "true && rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "cd common && rm src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "cd does-not-exist || rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "sh -c 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "sh -lc 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "sh -lc -- 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "bash -c -- 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "TARGET=common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt; rm \$TARGET" \
  "rm common/src/test/kotlin/opensamguk/common/golden/{JosaLogGoldenTest,OtherTest}.kt" \
  "find common/src/test -delete" \
  "find . -delete" \
  "rm -rf app/game-api/src/test" \
  "rm -rf common" \
  "DELETE=rm; \$DELETE common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo \$(rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)" \
  "JAVA_HOME=\$(/usr/libexec/java_home -v 17) ./gradlew --version" \
  "\${X:-\$(rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)}" \
  "<(rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)" \
  ">(rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)" \
  "\`printf rm\` common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "dash -c 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "bash --rcfile /tmp/guard-payload -ic 'printf ok'" \
  "bash --init-file=/tmp/guard-payload -i" \
  "custom-shell -c 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "env --argv0=rg reads" \
  "/tmp/git status" \
  "/tmp/echo ok" \
  "/usr/bin/git status" \
  "/tmp/env git status" \
  "/tmp/sudo -n git status" \
  "/tmp/command git status" \
  "/tmp/exec git status" \
  "/tmp/nice git status" \
  "/tmp/time git status" \
  "/tmp/timeout 5 git status" \
  "printf -v PATH /tmp; git status" \
  "printf -v PATH '%s' /tmp; git status" \
  "builtin printf -v PATH /tmp; git status" \
  "command printf -v PATH /tmp; git status" \
  "printf -vPATH /tmp; git status" \
  "printf -vPATH '%s' /tmp; git status" \
  "builtin printf -vPATH /tmp; git status" \
  "command printf -vPATH /tmp; git status" \
  "PATH=/tmp git status" \
  "env PATH=/tmp rg pattern docs/agent/verification.md" \
  "env -- PATH=/tmp git status" \
  "PATH=/tmp bash -ec 'git status'" \
  "CDPATH=/tmp bash -ec 'cd common && git status'" \
  "LD_PRELOAD=/tmp/evil.so git status" \
  "env LD_LIBRARY_PATH=/tmp rg pattern docs/agent/verification.md" \
  "DYLD_INSERT_LIBRARIES=/tmp/evil.dylib echo ok" \
  "LD_PRELOAD=/tmp/evil.so bash -ec 'git status'" \
  "env DYLD_LIBRARY_PATH=/tmp zsh -fc 'rg pattern docs/agent/verification.md'" \
  "LIBPATH=/tmp git status" \
  "SHLIB_PATH=/tmp git status" \
  "rg --pre rm x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg --pre=rm x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg --search-zip x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg -z x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "RIPGREP_CONFIG_PATH=/tmp/rg-config rg x docs/agent/verification.md" \
  "rg --hostname-bin=rm --hyperlink-format=default x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg --hostname-bin rm x common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg --unknown-option x docs/agent/verification.md" \
  "rg -g" \
  "rg --glob" \
  "echo x > common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "printf x >> common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "rg pattern docs/agent/verification.md > common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x >| legacy/devsam-core/index.php" \
  "printf x >common/src/test/resources/golden/city_const.golden.json" \
  "printf x>common/src/test/resources/golden/city_const.golden.json" \
  "printf x>/tmp/op218-first-output>common/src/test/resources/golden/city_const.golden.json" \
  "echo x > \$TARGET" \
  "echo x >" \
  "echo x &>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x &>>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x >|common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x >&common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x <>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo x {guardfd}>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "command -v git > common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "command -V git >common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "command -v git>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "command -V git>common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "EDITOR=/tmp/evil git status" \
  "VISUAL=/tmp/evil git status" \
  "PAGER=/tmp/evil git status" \
  "SSH_ASKPASS=/tmp/evil git status" \
  "GIT_EXEC_PATH=/tmp git status" \
  "GIT_INDEX_FILE=/tmp/index git status" \
  "GIT_OBJECT_DIRECTORY=/tmp/objects git status" \
  "GIT_COMMON_DIR=/tmp/common git status" \
  "GIT_NAMESPACE=other git status" \
  "GIT_OPTIONAL_LOCKS=1 git status" \
  "./gradlew --init-script /tmp/evil.gradle :logic:test" \
  "./gradlew -I /tmp/evil.gradle :logic:test" \
  "./gradlew --build-file /tmp/evil.gradle :logic:test" \
  "./gradlew -b /tmp/evil.gradle :logic:test" \
  "./gradlew --project-dir /tmp :logic:test" \
  "./gradlew -p /tmp :logic:test" \
  "./gradlew -Dorg.gradle.jvmargs=-agentlib:jdwp :logic:test" \
  "./gradlew --include-build /tmp :logic:test" \
  "./gradlew --unknown-option :logic:test" \
  "JAVA_HOME=/tmp ./gradlew :logic:test" \
  "env JAVA_HOME=/tmp ./gradlew :logic:test" \
  "JAVA_TOOL_OPTIONS=-javaagent:/tmp/evil.jar ./gradlew :logic:test" \
  "JAVA_OPTS=-javaagent:/tmp/evil.jar ./gradlew :logic:test" \
  "JDK_JAVA_OPTIONS=-javaagent:/tmp/evil.jar ./gradlew :logic:test" \
  "_JAVA_OPTIONS=-javaagent:/tmp/evil.jar ./gradlew :logic:test" \
  "GRADLE_OPTS=-I/tmp/evil.gradle ./gradlew :logic:test" \
  "GRADLE_USER_HOME=/tmp ./gradlew :logic:test" \
  "ORG_GRADLE_PROJECT_initScript=/tmp/evil.gradle ./gradlew :logic:test" \
  "SAFE_BUT_UNPROVEN=1 ./gradlew :logic:test" \
  "CLASSPATH=/tmp/evil.jar ./gradlew :logic:test" \
  "./gradlew :logic:test --tests \$FILTER" \
  "./gradlew :logic:test --tests 'opensamguk.*'" \
  "JAVA_TOOL_OPTIONS=-javaagent:/tmp/evil.jar bash -ec './gradlew :logic:test'" \
  "env GRADLE_USER_HOME=/tmp bash -ec './gradlew :logic:test'" \
  "JAVA_HOME=/tmp zsh -fc './gradlew :logic:test'" \
  "CLASSPATH=/tmp/evil.jar sh -c './gradlew :logic:test'" \
  "ORG_GRADLE_PROJECT_initScript=/tmp bash -ec './gradlew :logic:test'" \
  "env JAVA_OPTS=-javaagent:/tmp/evil.jar bash -ec './gradlew :logic:test'" \
  "bash -ec 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "dash -xec 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "env zsh -fc 'rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "BASH_ENV=docs/agent/verification.md bash scripts/agent/test-codex-agent-os.sh" \
  "ENV=docs/agent/verification.md sh scripts/agent/test-codex-agent-os.sh" \
  "KSH_ENV=docs/agent/verification.md ksh scripts/agent/test-codex-agent-os.sh" \
  "ZDOTDIR=docs zsh scripts/agent/test-codex-agent-os.sh" \
  "unlink common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "perl -e 'unlink q(common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)'" \
  "python -c 'import os; os.unlink(\"common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt\")'" \
  "python -c 'import os; p=\"common/src/\"+\"test/kotlin/opensamguk/common/golden/\"+\"JosaLogGoldenTest.kt\"; os.unlink(p)'" \
  "perl -e 'my \$p=join q{}, qw(common/src/ test/kotlin/opensamguk/common/golden/ JosaLogGoldenTest.kt); unlink \$p'" \
  "python -c 'print(1)'" \
  "python3 -Bc 'print(1)'" \
  "python3 -W scripts/agent/verify-changes.sh /tmp/payload.py" \
  "python3 -X scripts/agent/verify-changes.sh /tmp/payload.py" \
  "python3 --unknown-option scripts/agent/verify-changes.sh" \
  "python3 -i tools/agent-system/check.py" \
  "PYTHONINSPECT=1 python3 tools/agent-system/check.py" \
  "PYTHONPATH=/tmp python3 tools/agent-system/check.py" \
  "PYTHONSTARTUP=/tmp/startup.py python3 tools/agent-system/check.py" \
  "PYTHONWARNINGS=error python3 tools/agent-system/check.py" \
  "PYTHONBREAKPOINT=helper.breakpoint python3 tools/agent-system/check.py" \
  "PYTHONHOME=/tmp python3 tools/agent-system/check.py" \
  "PYTHONUSERBASE=/tmp python3 tools/agent-system/check.py" \
  "PYTHONPLATLIBDIR=lib python3 tools/agent-system/check.py" \
  "PYTHON_FUTURE_GUARD=1 python3 tools/agent-system/check.py" \
  "PHPRC=/tmp/evil.ini php tools/php-golden/capture_che.php" \
  "PHP_INI_SCAN_DIR=/tmp php tools/php-golden/capture_che.php" \
  "PHP_FUTURE_GUARD=1 php tools/php-golden/capture_che.php" \
  "RUBYLIB=/tmp ruby tools/agent-system/check.py" \
  "RUBY_FUTURE_GUARD=1 ruby tools/agent-system/check.py" \
  "NODE_PATH=/tmp node tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "NODE_FUTURE_GUARD=1 node tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "LUA_INIT=@/tmp/startup.lua lua tools/agent-system/check.py" \
  "LUA_INIT_5_4=@/tmp/startup.lua lua tools/agent-system/check.py" \
  "LUA_FUTURE_GUARD=1 lua tools/agent-system/check.py" \
  "PYTHONHOME=/tmp python3.13 tools/agent-system/check.py" \
  "env PYTHONHOME=/tmp /opt/python/bin/python3.13 tools/agent-system/check.py" \
  "PHPRC=/tmp/evil.ini php8.3 tools/php-golden/capture_che.php" \
  "env PHPRC=/tmp/evil.ini /opt/php/bin/php8.3 tools/php-golden/capture_che.php" \
  "RUBYLIB=/tmp ruby3.3 tools/agent-system/check.py" \
  "env RUBYLIB=/tmp /opt/ruby/bin/ruby3.3 tools/agent-system/check.py" \
  "NODE_PATH=/tmp node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "env NODE_PATH=/tmp /opt/node/bin/node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "LUA_INIT=@/tmp/startup.lua lua5.4 tools/agent-system/check.py" \
  "env LUA_INIT=@/tmp/startup.lua /opt/lua/bin/lua5.4 tools/agent-system/check.py" \
  "PYTHONPATH=/tmp bash -ec 'python3 tools/agent-system/check.py'" \
  "env PYTHONHOME=/tmp bash -ec 'python3 tools/agent-system/check.py'" \
  "PHPRC=/tmp sh -c 'php tools/php-golden/capture_che.php'" \
  "RUBYLIB=/tmp bash -ec 'ruby tools/agent-system/check.py'" \
  "NODE_PATH=/tmp bash -ec 'node tools/php-golden/compare-command-logs/compare-command-logs.mjs'" \
  "LUA_INIT=@/tmp/startup.lua bash -ec 'lua tools/agent-system/check.py'" \
  "PYTHONPATH=/tmp bash scripts/agent/test-codex-agent-os.sh" \
  "export PYTHONPATH=.ai; python3 tools/agent-system/check.py" \
  "export PYTHONHOME=/tmp; python3.13 tools/agent-system/check.py" \
  "export RUBYLIB=/tmp; ruby3.3 tools/agent-system/check.py" \
  "export NODE_PATH=/tmp; node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "export LUA_INIT=@/tmp/startup.lua; bash -ec 'lua5.4 tools/agent-system/check.py'" \
  "export PHPRC=/tmp; sh -c 'php8.3 tools/php-golden/capture_che.php'" \
  "bash -ec 'export PYTHONHOME=/tmp; python3.13 tools/agent-system/check.py'" \
  "bash -ec 'export NODE_PATH=/tmp; node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs'" \
  "export RUBYOPT=-w; ruby3.3 tools/agent-system/check.py" \
  "export RUBY_FUTURE_EXPORT=1; ruby3.3 tools/agent-system/check.py" \
  "export LUA_INIT=@/tmp/startup.lua; lua5.4 tools/agent-system/check.py" \
  "export LUA_FUTURE_EXPORT=1; lua5.4 tools/agent-system/check.py" \
  "declare -x PYTHONHOME=/tmp; python3.13 tools/agent-system/check.py" \
  "typeset -x PHPRC=/tmp; php8.3 tools/php-golden/capture_che.php" \
  "readonly -x NODE_PATH=/tmp; node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "bash -ec 'declare -x RUBYLIB=/tmp; ruby3.3 tools/agent-system/check.py'" \
  "fish -c 'set -x PYTHONPATH .ai; python3.13 tools/agent-system/check.py'" \
  "fish -c 'set --export PHPRC /tmp; php8.3 tools/php-golden/capture_che.php'" \
  "fish -c 'set -gx RUBYLIB /tmp; ruby3.3 tools/agent-system/check.py'" \
  "fish -c 'set --export NODE_PATH /tmp; node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs'" \
  "fish -c 'set -x LUA_INIT @/tmp/startup.lua; lua5.4 tools/agent-system/check.py'" \
  "csh -c 'setenv PYTHONHOME /tmp; python3.13 tools/agent-system/check.py'" \
  "csh -c 'setenv PHPRC /tmp; php8.3 tools/php-golden/capture_che.php'" \
  "tcsh -c 'setenv NODE_PATH /tmp; node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs'" \
  "( export PHPRC=README.md; php8.3 README.md )" \
  "export PYTHONINSPECT=1; (python3.13 README.md)" \
  "unset PYTHONHOME; python3 tools/agent-system/check.py" \
  "export -n PYTHONHOME; python3 tools/agent-system/check.py" \
  "bash -ec 'source $guard_source_fixture; python3 tools/agent-system/check.py'" \
  "zsh -fc '. $guard_source_fixture; python3.13 tools/agent-system/check.py'" \
  "fish -c 'source $guard_source_fixture; python3 tools/agent-system/check.py'" \
  "bash -ec 'alias guarded_python=python3; guarded_python tools/agent-system/check.py'" \
  "bash -ec 'function guarded_python; guarded_python tools/agent-system/check.py'" \
  "zsh -fc 'fpath=($guard_external_root); autoload -Uz guarded; guarded; python3 tools/agent-system/check.py'" \
  "fish -c 'functions -c source guarded; guarded $guard_source_fixture; python3 tools/agent-system/check.py'" \
  "fish -c 'funcsave guarded; python3 tools/agent-system/check.py'" \
  "bash -ec 'enable -f $guard_source_fixture guarded; guarded; python3 tools/agent-system/check.py'" \
  "zsh -fc 'zmodload $guard_source_fixture; python3 tools/agent-system/check.py'" \
  "ruby -I scripts/agent/verify-changes.sh /tmp/payload.rb" \
  "node -r scripts/agent/verify-changes.sh /tmp/payload.js" \
  "php -c scripts/agent/verify-changes.sh /tmp/payload.php" \
  "fish --init-command scripts/agent/verify-changes.sh /tmp/payload.fish" \
  "perl -we 'print 1'" \
  "perl -Ilib scripts/check.pl" \
  "perl -Mstrict scripts/check.pl" \
  "bash scripts/agent/codex-post-tool-use.sh" \
  "PERL5OPT=-Mstrict perl scripts/check.pl" \
  "python3 -" \
  "python3 <<'PY'\nprint(1)\nPY" \
  "awk -v target=common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt 'BEGIN { print target > target }'" \
  "sed -n 'w common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt' README.md" \
  "echo ok\nrm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git status\nunlink common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "find . -exec rm {} +" \
  "find common -execdir unlink {} +" \
  "find . -ok rm {} +" \
  "find common -okdir unlink {} +" \
  "find . -exec echo {} + -delete" \
  "find docs -exec sh -c 'printf x' {} +" \
  "find . -exec echo {} + -exec rm {} +" \
  "find . -exec echo {} + -ok unlink {} +" \
  "find . -fprint common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "find . -fprint0 common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "find . -fprintf common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt %p" \
  "find . -fls common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git rm app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt" \
  "git -C common rm src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git --work-tree . --git-dir .git --namespace guarded --config-env=protocol.file.allow=SAFE_PROTOCOL rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git --work-tree=. --git-dir=.git --namespace=guarded --config-env protocol.file.allow=SAFE_PROTOCOL rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git -c alias.nuke=!rm nuke common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git --config-env=alias.nuke=NUKE nuke common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git config alias.nuke '!rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=alias.nuke GIT_CONFIG_VALUE_0='!rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt' git nuke" \
  "env GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=alias.nuke GIT_CONFIG_VALUE_0='!rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt' git nuke" \
  "git nuke common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git worktree remove $guard_external_repo" \
  "git worktree move $guard_external_repo $guard_external_root/moved" \
  "git worktree prune" \
  "git worktree repair $guard_external_repo" \
  "git worktree lock $guard_external_repo" \
  "git worktree unlock $guard_external_repo" \
  "git rm --pathspec-from-file=paths.txt" \
  "git rm ':(literal)common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "git rm ':(top,literal)common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "git rm -- ':(literal)common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "git --icase-pathspecs rm -- COMMON/SRC/TEST/KOTLIN/OPENSAMGUK/COMMON/GOLDEN/JOSALOGGOLDENTEST.KT" \
  "GIT_ICASE_PATHSPECS=1 git rm -- COMMON/SRC/TEST/KOTLIN/OPENSAMGUK/COMMON/GOLDEN/JOSALOGGOLDENTEST.KT" \
  "env GIT_ICASE_PATHSPECS=1 git rm -- COMMON/SRC/TEST/KOTLIN/OPENSAMGUK/COMMON/GOLDEN/JOSALOGGOLDENTEST.KT" \
  "GIT_LITERAL_PATHSPECS=1 git status" \
  "GIT_GLOB_PATHSPECS=1 git status" \
  "GIT_NOGLOB_PATHSPECS=1 git status" \
  "GIT_DIR=.git git status" \
  "env GIT_WORK_TREE=. git status" \
  "git --git-dir=$guard_external_repo/.git --work-tree=$guard_external_repo commit" \
  "git -C $guard_external_repo commit" \
  "git -C $guard_external_repo add --all" \
  "git -C $guard_external_repo fetch origin" \
  "git -C $guard_external_repo push origin guarded" \
  "git -C common --work-tree=. add -u && git commit --amend --no-edit && git push origin guarded" \
  "git fetch ext::printf" \
  "git fetch --multiple origin ext::printf" \
  "git push ext::printf guarded" \
  "git --bare commit" \
  "git --namespace guarded commit" \
  "GIT_EDITOR=/tmp/helper git commit" \
  "GIT_SEQUENCE_EDITOR=/tmp/helper git commit" \
  "GIT_EXTERNAL_DIFF=/tmp/helper git diff" \
  "GIT_PAGER=/tmp/helper git log" \
  "GIT_ASKPASS=/tmp/helper git fetch" \
  "GIT_SSH=/tmp/helper git fetch" \
  "GIT_SSH_COMMAND=/tmp/helper git push" \
  "GIT_PROXY_COMMAND=/tmp/helper git fetch" \
  "git mv ':/common/src/test/resources/golden/city_const.golden.json' docs/example.json" \
  "git update-index --force-remove ':!common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt'" \
  "git update-index --force-remove --stdin" \
  "git update-index --force-remove --index-info" \
  "git update-index --force-remove --pathspec-from-file=paths.txt" \
  "git update-index --force-remove --pathspec-from-file paths.txt" \
  "git update-index --force-remove --std" \
  "git update-index --force-remove --index-inf" \
  "git --git-dir ../alternate/.git --work-tree ../alternate rm src/test/kotlin/HiddenTest.kt" \
  "git update-index --force-remove common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git reset --hard" \
  "git reset --hard HEAD" \
  "git clean -fdx" \
  "git switch main" \
  "git rebase origin/main" \
  "git -C $guard_external_relative reset --hard" \
  "git -C $guard_external_repo clean -fdx" \
  "git -C $guard_external_link switch main" \
  "GIT_CONFIG_PARAMETERS=alias.nuke=!rm git nuke" \
  "env GIT_CONFIG_PARAMETERS=alias.nuke=!rm git nuke" \
  "git -c core.fsmonitor=/tmp/helper status" \
  "GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=/tmp/helper git status" \
  "git mv common/src/test/resources/golden/city_const.golden.json docs/example.json" \
  "mv -t docs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "mv -tdocs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "mv --target-directory=docs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "mv --unknown-option app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt docs" \
  "git mv -t docs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "git mv -tdocs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "git mv --target-directory docs app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GeneralResolverFixture.kt" \
  "mv app/game-api/src/test docs/FooTest.kt" \
  "git mv app/game-api/src/test docs/FooTest.kt" \
  "git mv common/src/test/resources/golden/city_const.golden.json common/src/test/resources/golden/game_unit_const.golden.json docs/" \
  "git -C $guard_external_relative rm src/test/kotlin/ProtectedTest.kt" \
  "cd $guard_external_relative && git rm src/test/kotlin/ProtectedTest.kt" \
  "rm $guard_external_relative/src/test/kotlin/ProtectedTest.kt" \
  "rm $guard_external_repo/src/test/kotlin/ProtectedTest.kt" \
  "rm $guard_external_link/src/test/kotlin/ProtectedTest.kt" \
  "python -c 'import os; os.unlink(\"$guard_external_repo/src/test/kotlin/ProtectedTest.kt\")'"; do
  set +e
  printf '%s' "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"$protected_command\"}}" \
    | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1
  protected_command_status=$?
  set -e
  [ "$protected_command_status" -eq 2 ] || fail "Codex Bash guard allowed protected removal/move: $protected_command"
done

git -C "$guard_external_repo" -c user.name=Guard -c user.email=guard@example.invalid commit -qm baseline
git -C "$guard_external_repo" remote add safe https://example.invalid/opensamguk.git
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git fetch safe"}}' \
  | (cd "$guard_external_repo" && "$ROOT/scripts/agent/codex-bash-guard.sh") >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a configured non-helper remote"
git -C "$guard_external_repo" remote add helper 'ext::printf'
set +e
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git fetch helper"}}' \
  | (cd "$guard_external_repo" && "$ROOT/scripts/agent/codex-bash-guard.sh") >/dev/null 2>&1
configured_helper_status=$?
set -e
[ "$configured_helper_status" -eq 2 ] || fail "Codex Bash guard allowed a configured remote helper URL"
git -C "$guard_external_repo" config 'url.https://example.invalid/.insteadOf' 'rewrite:'
git -C "$guard_external_repo" remote add rewritten rewrite:opensamguk.git
set +e
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git fetch rewritten"}}' \
  | (cd "$guard_external_repo" && "$ROOT/scripts/agent/codex-bash-guard.sh") >/dev/null 2>&1
configured_rewrite_status=$?
set -e
[ "$configured_rewrite_status" -eq 2 ] || fail "Codex Bash guard allowed an insteadOf-rewritten remote URL"
mkdir -p "$guard_external_repo/scripts/agent"
cp scripts/agent/codex-post-tool-use.sh "$guard_external_repo/scripts/agent/codex-post-tool-use.sh"
printf '#!/usr/bin/env bash\nprintf "== 필요한 최소 검증 ==\\n"\n' > "$guard_external_repo/scripts/agent/verify-changes.sh"
chmod +x "$guard_external_repo/scripts/agent/verify-changes.sh"
python3 -c 'import os, sys; os.unlink(os.path.join(sys.argv[1], "src", "test", "kotlin", "ProtectedTest.kt"))' "$guard_external_repo"
for prospective_command in "git add -u" "git add --all" "git commit -a -m guarded"; do
  set +e
  (cd "$guard_external_repo" && printf '%s' "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"$prospective_command\"}}" \
    | "$ROOT/scripts/agent/codex-bash-guard.sh" >/dev/null 2>&1)
  prospective_status=$?
  set -e
  [ "$prospective_status" -eq 2 ] || fail "Codex Bash guard allowed prospective protected deletion: $prospective_command"
done
set +e
(cd "$guard_external_repo" && scripts/agent/codex-post-tool-use.sh </dev/null >/dev/null 2>&1)
post_dynamic_delete_status=$?
set -e
[ "$post_dynamic_delete_status" -eq 2 ] || fail "PostToolUse protected diff missed dynamically constructed deletion"
git -C "$guard_external_repo" restore src/test/kotlin/ProtectedTest.kt
mkdir -p "$guard_external_repo/docs"
git -C "$guard_external_repo" mv src/test/kotlin/ProtectedTest.kt docs/Protected.kt
set +e
(cd "$guard_external_repo" && scripts/agent/codex-post-tool-use.sh </dev/null >/dev/null 2>&1)
post_rename_out_status=$?
set -e
[ "$post_rename_out_status" -eq 2 ] || fail "PostToolUse protected diff missed test rename-out"
git -C "$guard_external_repo" mv docs/Protected.kt src/test/kotlin/ProtectedTest.kt
git -C "$guard_external_repo" mv src/test/kotlin/ProtectedTest.kt docs/Protected.spec.ts
set +e
(cd "$guard_external_repo" && scripts/agent/codex-post-tool-use.sh </dev/null >/dev/null 2>&1)
post_filename_test_rename_status=$?
set -e
[ "$post_filename_test_rename_status" -eq 0 ] || fail "PostToolUse blocked a rename to a recognized test-source filename"

for safe_command in \
  "rg rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "grep rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "echo rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "git --version" \
  "git --help" \
  "git --exec-path" \
  "git -P status" \
  "git -p status" \
  "env --argv0=rg rg pattern docs/agent/verification.md" \
  "echo '\$(rm common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt)'" \
  "JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew --version" \
  "JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew :logic:test" \
  "JAVA_HOME=\$(/usr/libexec/java_home -v 21) bash -ec './gradlew :logic:test'" \
  "./gradlew :common:test :logic:test --rerun-tasks" \
  "./gradlew build --no-daemon" \
  "./gradlew :logic:test --tests opensamguk.ExampleTest --rerun-tasks" \
  "bash -ec 'printf ok'" \
  "printf -- -vPATH" \
  "printf '%s' -vPATH" \
  "printf '%s' '>' common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "printf '%s' '__CODEX_REDIR_OUT__' common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt" \
  "printf '%s' PATH=/tmp" \
  "echo CDPATH=/tmp" \
  "printf '%s' LD_PRELOAD=/tmp/x.so" \
  "rg DYLD_LIBRARY_PATH=/tmp docs/agent/verification.md" \
  "rg --no-pre --no-search-zip pattern docs/agent/verification.md" \
  "rg --pretty pattern docs/agent/verification.md" \
  "rg -nNSocHI pattern docs/agent/verification.md" \
  "rg --files -g '*.kt' logic/src/main" \
  "rg --glob '*.md' --with-filename pattern docs" \
  "rg --no-filename pattern docs/agent/verification.md" \
  "rg -- -pattern docs/agent/verification.md" \
  "find docs -print" \
  "find docs -print0" \
  "find docs -printf %p" \
  "find docs -ls" \
  "git config --get user.name" \
  "git worktree list" \
  "GIT_OPTIONAL_LOCKS=0 git status" \
  "PYTHONHOME=/tmp git status" \
  "env SAFE_INTERPRETER_CONTROL=1 python3.13 tools/agent-system/check.py" \
  "env SAFE_INTERPRETER_CONTROL=1 php8.3 tools/php-golden/capture_che.php" \
  "env SAFE_INTERPRETER_CONTROL=1 ruby3.3 tools/agent-system/check.py" \
  "env SAFE_INTERPRETER_CONTROL=1 node22 tools/php-golden/compare-command-logs/compare-command-logs.mjs" \
  "env SAFE_INTERPRETER_CONTROL=1 lua5.4 tools/agent-system/check.py" \
  "PYTHONPATH=/tmp bash -ec 'git status'" \
  "env PHP_SAFE_CONTROL=1 bash -ec 'git status'" \
  "export -p" \
  "LOADER_SAFE_CONTROL=1 git status" \
  "SAFE_PATH_CONTROL=1 git status" \
  "echo ok > /tmp/op218-safe-output" \
  "printf ok >> /dev/null" \
  "echo ok &>/tmp/op218-safe-output" \
  "echo ok &>>/tmp/op218-safe-output" \
  "echo ok >|/tmp/op218-safe-output" \
  "echo ok >&/tmp/op218-safe-output" \
  "echo ok <>/tmp/op218-safe-output" \
  "echo ok {guardfd}>/tmp/op218-safe-output" \
  "(python3 tools/agent-system/check.py)" \
  "sed -n 1p $guard_source_fixture" \
  "command -v python3" \
  "command -v git >/tmp/op218-command-v-output" \
  "command -V git > /tmp/op218-command-V-output" \
  "git add --all" \
  "cd common && git add --all" \
  "git commit --amend --no-edit" \
  "git fetch origin" \
  "git fetch --multiple origin origin" \
  "git push --force-with-lease origin work/opensamguk/op218-parity-adr" \
  "bash -n scripts/agent/codex-post-tool-use.sh" \
  "bash scripts/agent/test-codex-agent-os.sh" \
  "mv -t docs app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt" \
  "git mv --target-directory=docs app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt" \
  "python3 tools/agent-system/check.py --help"; do
  printf '%s' "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"$safe_command\"}}" \
    | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
    || fail "Codex Bash guard false-positive on proven read-only command: $safe_command"
done
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"rm logic/src/test/kotlin/NewUntrackedTest.kt"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked removal of an untracked new test"
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git mv common/src/test/resources/golden/city_const.golden.json common/src/test/resources/golden/city_const.renamed.golden.json"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a golden rename within the protected golden surface"
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git mv web/game/__tests__/GameInfo.test.tsx web/game/__tests__/GameInfo.renamed.test.tsx"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a rename within a recognized test directory"
printf '%s' '{"tool_name":"Bash","tool_input":{"command":"git mv web/game/__tests__/GameInfo.test.tsx web/game/GameInfo.renamed.spec.ts"}}' \
  | scripts/agent/codex-bash-guard.sh >/dev/null 2>&1 \
  || fail "Codex Bash guard blocked a rename to a recognized test-source filename"

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
