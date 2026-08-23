#!/usr/bin/env python3
"""Provider-agnostic working-system checks for opensamguk agents.

The checks intentionally use only git, JSON, and filesystem inspection so they
work for Codex, Claude, Gemini, local shells, and CI runners.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
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

PRODUCT_AUTHORITY_REQUIRED = {
    "AGENTS.md": ("ADR-LITE-042", "제품 정본 = 최신 승인 ADR·spec·현재 구현"),
    "CLAUDE.md": ("Product and regression discipline", "optional historical/reference inputs"),
    "docs/superpowers/WORKING_SYSTEM.md": ("ADR-LITE-042", "historical opt-in", "current implementation"),
    "docs/superpowers/LOOP_ENGINEERING.md": ("ADR-LITE-042", "명시적 역사", "current implementation"),
    "docs/agent/project-overview.md": ("신규 설계 정본", "명시적으로 요청된 동결 회귀"),
    "docs/agent/prompt-pack.md": (
        "ADR-LITE-042",
        "승인 ADR/spec",
        "opt-in",
        "승인된 제품 변경으로 골든 기대값을 갱신",
        "명시적 이유와 회귀 증거",
    ),
    "docs/agent/codex-user-manual.md": (
        "ADR-LITE-042",
        "opt-in 역사 동결 회귀 유지보수",
        "현재 제품 정본이 아니다",
        "PHP 원작은 명시적으로 요청된 역사 비교에서만 참고한다",
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
    "docs/agent/verification.md": (
        "명시적 역사 parity 유지보수일 때만",
        "현재 spec 테스트 green",
        "golden 기대값 갱신은 훅 NOTICE",
        "Golden path:",
        "Golden change reason:",
        "Regression command:",
        "Regression evidence:",
        "Critique: CLEARED",
    ),
    ".claude/HARNESS.md": ("ADR-LITE-042", "never a prerequisite for new product work"),
    ".agents/skills/opensamguk-working-system/SKILL.md": ("ADR-LITE-042", "latest approved ADR/spec"),
    ".agents/skills/opensamguk-php-oracle/SKILL.md": ("opt-in historical comparison skill", "not product authority"),
    ".claude/workflows/backlog-fanout.js": ("Retired mixed legacy/current backlog workflow", "ADR-LITE-042"),
    ".claude/workflows/parity-backlog-pipeline.js": ("Retired mixed legacy/current backlog workflow", "ADR-LITE-042"),
    ".claude/workflows/parity-wave.js": ("Opt-in historical frozen-regression maintenance", "ADR-LITE-042", "product authority"),
    ".claude/commands/os-debug.md": ("승인된 제품 변경", "명시적 이유와 회귀 증거"),
    ".claude/agents/parity-reviewer.md": (
        "explicitly selected historical frozen-regression surface",
        "does not constrain new design",
        "approved product change",
        "explicit reason and regression evidence",
    ),
    "scripts/agent/protect-sensitive-files.sh": ("동결 회귀 기준선", "명시적 변경 이유와 회귀 증거", "역사 참고 자료"),
    "tools/php-golden/README.md": ("opt-in historical comparison tool", "not product authority", "explicit reason and regression evidence"),
    "tools/php-golden/compare-command-logs/README.md": (
        "ADR-LITE-042", "opt-in historical frozen-regression comparison tool", "not current product authority",
    ),
    "docs/superpowers/MILESTONES.md": (
        "ADR-LITE-042", "동결 회귀 green + 운영 안정", "PHP 패러티 close를 선행 조건으로 삼지 않는다",
        "승인 ADR/spec·현재 구현이 제품 정본",
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

ADR_LITE_042_CONTRACTS = (
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

ADR_LITE_042_PROSE_REQUIRED = {
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

PARTIALLY_SUPERSEDED_BY_ADR_LITE_042 = (
    "## ADR-LITE-010 v2 콘텐츠 정체성 — RTK 종합으로 devsam 콘텐츠 대체",
    "## ADR-LITE-018 v1을 오리지널로 동결하고 v2 뉴버전을 상시 운영으로 삼는다",
)

ADR_LITE_042_CONTRADICTION_PATTERNS = (
    re.compile(
        r"\bPHP\s+(?:must|shall)\s+(?:remain\s+)?(?:be\s+)?(?:the\s+)?(?:mandatory\s+)?(?:oracle|product authority|source of truth|grand truth|winner)",
        re.IGNORECASE,
    ),
    re.compile(
        r"\bPHP\s+(?:is|remains)\s+(?:the\s+)?(?:mandatory|required)\s+(?:oracle|product authority|source of truth)",
        re.IGNORECASE,
    ),
    re.compile(r"PHP가[^\n]*(?:정본|오라클|우선)[^\n]*(?:이어야 한다|이다|우선한다)"),
    re.compile(r"PHP(?:가|는)[^\n]*(?:필수|의무)[^\n]*(?:오라클|정본)[^\n]*(?:이어야 한다|이다|유지한다)"),
)

ADR_LITE_042_ALLOWED_NEGATIONS = (
    re.compile(r"PHP가\s+더 이상\s+(?:정본|오라클|우선)(?:이어야 한다는)?\s+규칙은 없다[.。]?"),
)

PRODUCT_AUTHORITY_SURFACES = (
    *PRODUCT_AUTHORITY_REQUIRED,
    "docs/agent/README.md",
    "docs/agent/claude-user-manual.md",
    "docs/agent/codex-user-manual.md",
    "docs/agent/coding-rules.md",
    "docs/agent/context-strategy.md",
    "docs/agent/failure-cases.md",
    "docs/agent/lifecycle-planning.md",
    ".claude/commands/os-analyze.md",
    ".claude/commands/os-implement.md",
    ".claude/commands/os-review.md",
    ".claude/agents/golden-capturer.md",
    ".claude/agents/parity-gate-runner.md",
    ".claude/agents/parity-porter.md",
    ".claude/agents/parity-reviewer.md",
    ".claude/skills/parity-close/SKILL.md",
    ".claude/skills/parity-ship/SKILL.md",
    ".codex/agents/fe-submit-wirer.toml",
    ".codex/agents/golden-capturer.toml",
    ".codex/agents/parity-gate-runner.toml",
    ".codex/agents/parity-porter.toml",
    ".codex/agents/parity-reviewer.toml",
)

PRODUCT_AUTHORITY_SOURCE_GLOBS = (
    "*.gradle.kts", "settings.gradle.kts", "**/build.gradle.kts",
    "**/src/main/**/*.kt", "**/src/main/**/*.kts", "**/src/main/**/*.java",
    "**/src/baseline/**/*.kt", "**/src/baseline/**/*.kts", "**/src/baseline/**/*.java",
    "web/**/*.ts", "web/**/*.tsx", "web/**/*.js", "web/**/*.mjs", "web/**/*.cjs",
    "tools/**/*.py", "tools/**/*.sh", "tools/**/*.ts", "tools/**/*.js", "tools/**/*.mjs", "tools/**/*.cjs",
    "scripts/**/*.py", "scripts/**/*.sh", "**/*.yaml", "**/*.yml",
)

PRODUCT_AUTHORITY_FIXTURE_SURFACES = {
    "scripts/agent/test-codex-agent-os.sh",
    "tools/agent-system/check.py",
}

CURRENT_HANDOFF_HEADING_PATTERNS = {
    ".ai/handoff.md": re.compile(
        r"^## Current handoff \(\d{4}-\d{2}-\d{2}\)\s+—\s+(?P<title>.+)$",
        re.MULTILINE,
    ),
    "docs/superpowers/SESSION_HANDOFF.md": re.compile(
        r"^# SESSION HANDOFF\s+—\s+\d{4}-\d{2}-\d{2}\s+\((?P<title>.+)\)$",
        re.MULTILINE,
    ),
}


def check_current_handoff_authority() -> list[Finding]:
    task_path = ROOT / ".ai/task.md"
    task_text = task_path.read_text(encoding="utf-8") if task_path.is_file() else ""
    task_heading = re.search(r"^## \d{4}-\d{2}-\d{2}\s+—\s+(.+)$", task_text, re.MULTILINE)
    task_identifier = re.search(r"OPENSAM-\d+(?:~\d+)?", task_heading.group(1)) if task_heading else None
    if task_identifier is None:
        return [Finding(
            "error",
            "current-handoff-authority",
            ".ai/task.md does not declare a current OPENSAM task in its first dated section.",
        )]
    expected_task = task_identifier.group(0)
    findings: list[Finding] = []
    for rel, heading_pattern in CURRENT_HANDOFF_HEADING_PATTERNS.items():
        path = ROOT / rel
        text = path.read_text(encoding="utf-8") if path.is_file() else ""
        current = text.split("\n---\n", 1)[0]
        heading = heading_pattern.search(current)
        current_identifiers = re.findall(r"OPENSAM-\d+(?:~\d+)?", heading.group("title")) if heading else []
        if heading is None or not current_identifiers or current_identifiers[0] != expected_task:
            findings.append(Finding(
                "error",
                "current-handoff-authority",
                f"{rel} current heading must identify the active task {expected_task}.",
            ))
    return findings


def is_active_product_authority_source(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    return (
        path.is_file()
        and rel not in PRODUCT_AUTHORITY_FIXTURE_SURFACES
        and not any(part in {"legacy", "node_modules", "build", ".gradle", "generated"} for part in path.parts)
        and not any(part.lower() in {"test", "tests", "__tests__"} for part in path.parts)
        and re.search(
            r"(?:^test[_-].+|.+[_-]test|.+[._-](?:test|spec))\.[^.]+(?:\.[^.]+)?$",
            path.name,
            re.IGNORECASE,
        ) is None
    )

OBSOLETE_PRODUCT_AUTHORITY_PATTERNS = (
    re.compile(r"PHP\s+(?:is|as)\s+(?:the\s+)?grand truth", re.IGNORECASE),
    re.compile(
        r"PHP\s+(?:is|remains)\s+(?:the\s+)?(?:authoritative product source|source of truth|product authority|grand truth)",
        re.IGNORECASE,
    ),
    re.compile(
        r"PHP\s*=\s*(?:the\s+)?(?:authoritative product source|source of truth|product authority|grand truth)",
        re.IGNORECASE,
    ),
    re.compile(r"PHP wins every divergence", re.IGNORECASE),
    re.compile(r"PHP\s+should\s+remain\s+(?:the\s+)?(?:source of truth|product authority|grand truth|oracle)", re.IGNORECASE),
    re.compile(r"PHP\s+(?:is|remains)\s+(?:the\s+)?(?:mandatory|required)\s+(?:oracle|product authority|source of truth)", re.IGNORECASE),
    re.compile(r"PHP(?:가|는)[^\n]*(?:필수|의무)[^\n]*(?:오라클|정본)[^\n]*(?:이어야 한다|이다|유지한다)"),
    re.compile(r"all\s+golden\s+expectation\s+updates\s+require\s+(?:a\s+)?PHP\s+capture", re.IGNORECASE),
    re.compile(r"승인된 제품 변경도\s+PHP\s+오라클 캡처가\s+선행되어야 한다"),
    re.compile(r"PHP\s*레거시가[^\n]*모든 동작의 최종 기준"),
    re.compile(r"PHP\s*원작[^\n]*(?:최종 기준|우선한다)"),
    re.compile(r"PHP[^\n]*(?:풀 포트|전체 포트)[^\n]*(?:골든|golden)[^\n]*(?:필요|필수)", re.IGNORECASE),
    re.compile(r"(?:풀 포트|전체 포트)[^\n]*PHP[^\n]*(?:골든|golden)[^\n]*(?:필요|필수)", re.IGNORECASE),
    re.compile(r"PHP\s*증거[^\n]*(?:독립적으로\s*)?공격"),
    re.compile(r"\bPHP(?:가|는|\s+(?:is|as))?\s+(?:the\s+)?grand truth\b", re.IGNORECASE),
    re.compile(r"\bPHP(?:가|는|\s+(?:is|as))?\s+(?:the\s+)?grand[- ]truth\b", re.IGNORECASE),
    re.compile(r"\bFE\s+grand[- ]truth\b", re.IGNORECASE),
    re.compile(r"\bhwe(?:/ts)?\s+(?:is\s+|as\s+)?(?:the\s+)?grand[- ]truth\b", re.IGNORECASE),
    re.compile(
        r"(?:PHP(?:가|는|\s+)|legacy\s+table\s+|Vue\s+|FE\s+|hwe(?:/ts)?\s+)"
        r"[^\n]{0,100}(?:byte[- ]?oracle|parity\s+oracle|grand[- ]?truth|source[- ]of[- ]truth|authority|oracle|parity\s+target)",
        re.IGNORECASE,
    ),
    re.compile(
        r"PHP(?:가|는|\s+)(?:[^\n]*\n\s*(?:[/#*]+\s*)?){1,3}[^\n]{0,120}"
        r"grand[- ]?truth",
        re.IGNORECASE,
    ),
    re.compile(r"(?:PHP(?:가|는|\s+)|legacy\s+|Vue\s+|FE\s+|hwe(?:/ts)?\s+)[^\n]{0,100}(?:정본|오라클|패러티\s*대상)", re.IGNORECASE),
    re.compile(r"(?:Reference\s+oracle\s+TS|오라클\s+[^\n]{0,60}\.vue)", re.IGNORECASE),
    re.compile(r"(?:core2026\s+oracle|\bG\d+\s+byte\s+oracle\b)", re.IGNORECASE),
    re.compile(r"\bgrand truth\s*=\s*PHP\b", re.IGNORECASE),
    re.compile(r"\bPHP\s+wins\b", re.IGNORECASE),
    re.compile(r"PHP\s*정본", re.IGNORECASE),
    re.compile(r"byte[- ]?parity", re.IGNORECASE),
    re.compile(r"패러티\s*대상"),
    re.compile(r"골든으로\s*잠긴"),
    re.compile(r"\bv1\s+패러티\b", re.IGNORECASE),
    re.compile(r"\bPHP\s+(?:oracle|오라클)\b", re.IGNORECASE),
    re.compile(r"\bPHP\s+(?:is\s+)?(?:the\s+)?sole\s+oracle\b", re.IGNORECASE),
    re.compile(r"\bPHP\s+(?:path\s+)?wins\b", re.IGNORECASE),
    re.compile(r"PHP[^\n]{0,80}(?:\)|`)\s*(?:의\s*)?정본(?:이|으로|을|은|이다|이었)?", re.IGNORECASE),
    re.compile(r"PHP\s+source\s+is\s+(?:the\s+)?정본", re.IGNORECASE),
    re.compile(r"\bgrand[- ]?truth\s*:\s*legacy[^\n]*\.php\b", re.IGNORECASE),
    re.compile(r"(?m)^\*\*Oracle\s*=\s*PHP\b", re.IGNORECASE),
    re.compile(r"deny\s+\x22legacy/\s+원작\(PHP\s+grand truth\)", re.IGNORECASE),
    re.compile(r"(?m)^TS reference,\s+PHP wins\)\.", re.IGNORECASE),
    re.compile(r"regenerate\s+\*\*ONLY\*\*\s+when the PHP source changes", re.IGNORECASE),
    re.compile(r"골든 수정 금지[.\u3002]"),
    re.compile(r"골든 픽스처는 직접 수정 금지"),
    re.compile(r"tools/php-golden/\s*실 캡처로만"),
    re.compile(r"PHP가 (?:이김|이긴다)"),
    re.compile(r"frontend grand truth", re.IGNORECASE),
    re.compile(r"mandatory legacy-gap", re.IGNORECASE),
    re.compile(r"(?:all\s+)?new features?\s+must\s+(?:first\s+)?match\s+(?:devsam/core|PHP)", re.IGNORECASE),
    re.compile(
        r"hwe/ts\s+(?:is|remains)\s+(?:the\s+)?(?:authoritative(?:\s+frontend)?(?:\s+source)?|source of truth|product authority)",
        re.IGNORECASE,
    ),
    re.compile(r"의무 사슬[^\n]*opensamguk-php-oracle"),
)

PRODUCT_AUTHORITY_ALLOWED_CONTEXTS = (
    re.compile(r"It is no longer true that PHP is the source of truth\.", re.IGNORECASE),
    re.compile(r"Historical note: PHP wins every divergence was the retired rule\.", re.IGNORECASE),
    re.compile(r"Do not claim that PHP remains the product authority\.", re.IGNORECASE),
    re.compile(r"The former rule said PHP is the grand truth; ADR-LITE-042 retired it\.", re.IGNORECASE),
)

RETAINED_CONTRACT_ALIASES = (
    re.compile(r"retained invariants?", re.IGNORECASE),
    re.compile(r"(?:replay determinism|deterministic replay|리플레이 결정론)", re.IGNORECASE),
    re.compile(r"(?:one[-_ ]daemon[-_ ]write(?:[-_ ]rule)?|one[- ]writer|단일 writer)", re.IGNORECASE),
    re.compile(r"(?:flush delta|flush 델타|change recorder)", re.IGNORECASE),
    re.compile(r"(?:insertion order|삽입 순서)", re.IGNORECASE),
    re.compile(r"(?:truthfulness|거짓 완료 금지|no fabrication|evidence integrity)", re.IGNORECASE),
    re.compile(r"(?:frozen[- ]baseline|동결 회귀|existing tests?)", re.IGNORECASE),
    re.compile(r"(?:numerical(?: change)? record|intentional numerical changes?)", re.IGNORECASE),
    re.compile(r"(?:stable logs?(?: and ordering)?|stable ordering)", re.IGNORECASE),
)

RETAINED_WEAKENING_PATTERNS = (
    re.compile(r"(?:is|are)\s+(?:optional|advisory|irrelevant|not required)", re.IGNORECASE),
    re.compile(r"(?:is|are)\s+no longer required", re.IGNORECASE),
    re.compile(r"(?:may|can)\s+be\s+(?:ignored|skipped|weakened|bypassed)", re.IGNORECASE),
    re.compile(r"need not apply", re.IGNORECASE),
    re.compile(r"inline writes?\s+(?:are\s+)?(?:allowed|permitted)", re.IGNORECASE),
    re.compile(r"(?:무시|생략|약화|우회)(?:해도 된다|할 수 있다|할 수 있음| 가능하다)"),
    re.compile(r"(?:무관하다|권고일 뿐이다)"),
    re.compile(r"(?:필수가 아니다|더 이상 필요하지 않다|인라인 쓰기를? 허용)"),
)


def retained_contract_weakening(text: str) -> str | None:
    clauses = re.split(
        r"[\n.;。!?]+|(?:,\s*|\b)(?:but|however|yet)\b|(?:,?\s*)(?:그러나|하지만|[\w가-힣]+지만)",
        text,
        flags=re.IGNORECASE,
    )
    for clause in clauses:
        if any(alias.search(clause) for alias in RETAINED_CONTRACT_ALIASES) and any(
            weakening.search(clause) for weakening in RETAINED_WEAKENING_PATTERNS
        ):
            return clause.strip()
    return None


def is_retired_authority_context(text: str, start: int, end: int) -> bool:
    line_start = text.rfind("\n", 0, start) + 1
    line_end = text.find("\n", end)
    if line_end < 0:
        line_end = len(text)
    before = text[line_start:start]
    after = text[end:line_end]
    adversative_pattern = r"(?:,\s*|\b)(?:but|however|yet)\b|(?:,?\s*)(?:그러나|하지만|[\w가-힣]+지만)"
    adversatives = list(re.finditer(adversative_pattern, before, re.IGNORECASE))
    if adversatives:
        before = before[adversatives[-1].end():]
    next_adversative = re.search(adversative_pattern, after, re.IGNORECASE)
    if next_adversative:
        after = after[:next_adversative.start()]
    before_lower = before.lower()
    after_lower = after.lower()
    bounded_context = f"{before}{text[start:end]}{after}"
    if re.search(
        r"(?:historical|opt-in|frozen[- ](?:historical|regression)|"
        r"동결 회귀|역사 (?:PHP|비교|기준)|현재 제품 정본 아님|not current product authority)",
        bounded_context,
        re.IGNORECASE,
    ):
        return True
    if re.search(r"(?:무관(?:하게)?|아니다|아님|없다)", after):
        return True
    if re.search(r"\bretired\b", after_lower):
        return True
    if re.search(r"\b(?:no longer|do not claim|deny|retired)\b[^.!?。;]*$", before_lower):
        return True
    if re.search(r"^\s+(?:is no longer[^.!?。;]*policy|was[^.!?。;]*retired)", after_lower):
        return True
    if re.search(r"\b(?:former|historical(?: note)?|old handbook)\b", before_lower) and re.search(
        r"\b(?:retired|former|old)\b", after_lower
    ):
        return True
    if "과거에는" in before and re.search(r"규칙이\s+있었다", after):
        return True
    if re.search(r"규칙은\s+더 이상\s+유효하지 않다", after):
        return True
    return False


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


def run_git_bytes(args: list[str]) -> bytes:
    proc = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        message = proc.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(message or f"git {' '.join(args)} failed")
    return proc.stdout


def parse_name_status_z(raw: bytes) -> list[tuple[str, ...]]:
    tokens = [token.decode("utf-8", "surrogateescape") for token in raw.split(b"\0") if token]
    entries: list[tuple[str, ...]] = []
    cursor = 0
    while cursor < len(tokens):
        status = tokens[cursor]
        width = 3 if status.startswith(("R", "C")) else 2
        if cursor + width > len(tokens):
            raise RuntimeError("malformed NUL-delimited git name-status output")
        entries.append(tuple(tokens[cursor:cursor + width]))
        cursor += width
    return entries


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


def changed_name_status(base: str | None) -> list[tuple[str, ...]]:
    diffs: list[bytes] = []
    if base:
        diffs.append(run_git_bytes(["diff", "--find-renames", "--name-status", "-z", f"{base}...HEAD"]))
    diffs.append(run_git_bytes(["diff", "--find-renames", "--name-status", "-z"]))
    diffs.append(run_git_bytes(["diff", "--cached", "--find-renames", "--name-status", "-z"]))
    entries: set[tuple[str, ...]] = set()
    for output in diffs:
        entries.update(parse_name_status_z(output))
    return sorted(entries)


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


def check_rtk14_deploy_enrichment() -> list[Finding]:
    deploy_path = ROOT / ".github/workflows/deploy.yml"
    if not deploy_path.exists():
        return [Finding("error", "rtk14-enrichment", "Production deploy workflow is missing.")]

    text = deploy_path.read_text(encoding="utf-8")
    secret_step_match = re.search(
        r"(?ms)^      - name: Materialize RTK14 scenario stats for image build\n(?P<body>.*?)(?=^      - name:|\Z)",
        text,
    )
    secret_step = secret_step_match.group("body") if secret_step_match else text
    required = {
        "main-build secret binding": "RTK14_STATS_JSON_B64: ${{ secrets.RTK14_STATS_JSON_B64 }}",
        "fail-closed missing-secret check": "RTK14_STATS_JSON_B64 is required for main production image builds.",
        "temporary-material cleanup": "trap cleanup EXIT",
        "temporary source cleanup": 'rm -f -- "$source_json" "$diagnostics"',
        "temporary staging cleanup": 'rm -rf -- "$staging_dir"',
        "gzip-base64 source reconstruction": 'printf \'%s\' "$RTK14_STATS_JSON_B64" | base64 -d | gzip -dc',
        "silent decode diagnostics": '2>"$diagnostics"',
        "RTK14 builder source input": '--rtk-source-json "$source_json"',
        "runtime scenario input directory": '--scenario-dir "$SCENARIO_DIR"',
        "staged RTK14 output directory": '--out-dir "$staging_dir"',
        "silent builder diagnostics": '> "$diagnostics" 2>&1',
        "runtime scenario candidates": 'candidate_scenario_files=("$SCENARIO_DIR"/scenario_*.json)',
        "normalized archive schema filter": 'archive_sections = ("generals", "generalsEx")',
        "normalized archive exclusion": "normalized_archive)",
        "runtime scenario retention": 'scenario_files+=("$candidate")',
        "all-scenario count check": 'if (( ${#staged_files[@]} != scenario_count )); then',
        "per-file staged output check": 'if [[ ! -f "$staged_file" ]]; then',
        "all-scenario replacement": 'cp "$staged_file" "$scenario_file"',
        "non-secret completion summary": "RTK14 scenario enrichment completed for ${scenario_count} scenario file(s).",
    }
    missing = [name for name, snippet in required.items() if snippet not in text]
    if missing:
        return [
            Finding(
                "error",
                "rtk14-enrichment",
                "Production deploy workflow must fail closed while materializing RTK14 scenario stats; missing "
                + ", ".join(missing)
                + ".",
            )
        ]

    unsafe_output = (
        'echo "$RTK14_STATS_JSON_B64"',
        'echo "${RTK14_STATS_JSON_B64',
        "echo $RTK14_STATS_JSON_B64",
        "echo ${RTK14_STATS_JSON_B64",
        'cat "$source_json"',
        'cat "${source_json',
        "cat $source_json",
        "cat ${source_json",
        'cat "$diagnostics"',
        'cat "${diagnostics',
        "cat $diagnostics",
        "cat ${diagnostics",
    )
    exposed = [snippet for snippet in unsafe_output if snippet in secret_step]
    safe_source_reconstruction = 'printf \'%s\' "$RTK14_STATS_JSON_B64" | base64 -d | gzip -dc'
    for line in secret_step.splitlines():
        if "printf" in line and "RTK14_STATS_JSON_B64" in line and safe_source_reconstruction not in line:
            exposed.append("unsafe printf expansion")
    tracing_patterns = (
        r"(?m)^[ \t]*set[ \t]+-[A-Za-z]*x[A-Za-z]*\b",
        r"(?m)^[ \t]*set[ \t]+-o[ \t]+xtrace\b",
        r"(?m)^[ \t]*(?:bash|sh)[ \t]+-[A-Za-z]*x[A-Za-z]*\b",
    )
    if any(re.search(pattern, secret_step) for pattern in tracing_patterns):
        exposed.append("shell tracing")
    if exposed:
        return [
            Finding(
                "error",
                "rtk14-enrichment",
                "Production deploy workflow must not print RTK14 source material or builder diagnostics, or enable shell tracing.",
            )
        ]
    return []


def check_required_docs() -> list[Finding]:
    findings: list[Finding] = []
    required_phrases = {
        "README.md": ("작업 운영 체계", "tools/parity/gate.sh backend"),
        "AGENTS.md": ("작업 운영 체계 / skills.sh", "legacy/devsam-core"),
        "CLAUDE.md": ("working system", "skills-lock.json"),
        "docs/superpowers/WORKING_SYSTEM.md": ("Historical PHP comparison protocol", "Production policy"),
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


def check_product_authority_policy() -> list[Finding]:
    findings: list[Finding] = []
    for rel, phrases in PRODUCT_AUTHORITY_REQUIRED.items():
        path = ROOT / rel
        if not path.is_file():
            findings.append(Finding("error", "product-authority", f"{rel} is missing from the active policy surface."))
            continue
        text = path.read_text(encoding="utf-8")
        for phrase in phrases:
            if phrase not in text:
                findings.append(
                    Finding("error", "product-authority", f"{rel} is missing ADR-LITE-042 policy phrase: {phrase}")
                )

    source_surfaces = {
        path.relative_to(ROOT).as_posix()
        for pattern in PRODUCT_AUTHORITY_SOURCE_GLOBS
        for path in ROOT.glob(pattern)
        if is_active_product_authority_source(path)
    }
    for rel in (*PRODUCT_AUTHORITY_SURFACES, *sorted(source_surfaces - set(PRODUCT_AUTHORITY_SURFACES))):
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        scan_text = text
        for pattern in PRODUCT_AUTHORITY_ALLOWED_CONTEXTS:
            scan_text = pattern.sub("", scan_text)
        reported_lines: set[int] = set()
        weakening = retained_contract_weakening(scan_text)
        if weakening is not None:
            findings.append(
                Finding(
                    "error",
                    "product-authority",
                    f"{rel} weakens a retained ADR-LITE-042 invariant: {weakening}",
                )
            )
        for pattern in OBSOLETE_PRODUCT_AUTHORITY_PATTERNS:
            for match in pattern.finditer(scan_text):
                if is_retired_authority_context(scan_text, match.start(), match.end()):
                    continue
                line = scan_text.count("\n", 0, match.start()) + 1
                if line in reported_lines:
                    continue
                reported_lines.add(line)
                findings.append(
                    Finding(
                        "error",
                        "product-authority",
                        f"{rel}:{line} restores obsolete mandatory legacy authority: {match.group(0)}",
                    )
                )
    return findings


def check_adr_lite_042_contract() -> list[Finding]:
    findings: list[Finding] = []
    sections: dict[str, str] = {}
    for rel, heading, marker in ADR_LITE_042_CONTRACTS:
        path = ROOT / rel
        if not path.is_file():
            findings.append(Finding("error", "product-authority", f"{rel} is missing ADR-LITE-042."))
            continue
        text = path.read_text(encoding="utf-8")
        match = re.search(rf"(?ms)^{re.escape(heading)}\n.*?(?=^##\s|\Z)", text)
        marker_pattern = re.compile(r"<!-- ADR-LITE-042-(?:CONTRACT|RULES)\b[^>]*-->")
        if (
            match is None
            or text.count(marker) != 1
            or marker_pattern.findall(text) != [marker]
        ):
            findings.append(Finding("error", "product-authority", f"{rel} has an invalid ADR-LITE-042 contract."))
            continue
        sections[rel] = match.group(0)
    for rel, phrases in ADR_LITE_042_PROSE_REQUIRED.items():
        section = sections.get(rel, "")
        if any(phrase not in section for phrase in phrases):
            findings.append(Finding("error", "product-authority", f"{rel} has incomplete ADR-LITE-042 policy prose."))
        contradiction_scope = section
        for pattern in ADR_LITE_042_ALLOWED_NEGATIONS:
            contradiction_scope = pattern.sub("", contradiction_scope)
        if (
            any(pattern.search(contradiction_scope) for pattern in ADR_LITE_042_CONTRADICTION_PATTERNS)
            or retained_contract_weakening(contradiction_scope) is not None
        ):
            findings.append(Finding("error", "product-authority", f"{rel} contradicts ADR-LITE-042 policy."))

    decisions_path = ROOT / ".ai/decisions.md"
    decisions = decisions_path.read_text(encoding="utf-8") if decisions_path.is_file() else ""
    partial_status = "- Status: approved; parity-authority clauses superseded by ADR-LITE-042"
    for heading in PARTIALLY_SUPERSEDED_BY_ADR_LITE_042:
        match = re.search(rf"(?ms)^{re.escape(heading)}\n.*?(?=^##\s|\Z)", decisions)
        statuses = re.findall(r"(?m)^- Status:\s*.+$", match.group(0)) if match is not None else []
        if match is None or statuses != [partial_status]:
            findings.append(Finding("error", "product-authority", f"{heading} lacks ADR-LITE-042 partial supersession."))
    return findings


def is_protected_golden_path(path: str) -> bool:
    lower = path.lower()
    return (
        "/resources/golden/" in lower
        or ("/src/test/" in lower and "/golden/" in lower)
        or ("/src/test/" in lower and lower.endswith(("goldentest.kt", "goldenit.kt")))
    )


def is_test_source_path(path: str) -> bool:
    original_name = path.replace("\\", "/").rsplit("/", 1)[-1]
    normalized = "/" + path.replace("\\", "/").lower().lstrip("/")
    name = normalized.rsplit("/", 1)[-1]
    return (
        any(segment in normalized for segment in ("/src/test/", "/test/", "/tests/", "/__tests__/"))
        or re.search(r"(?:^test_.*|.*_test)\.py$", name) is not None
        or re.search(r"(?:^|[._-])(?:test|spec)\.[^.]+(?:\.[^.]+)?$", name) is not None
        or re.search(r"(?:Test|Tests|IT)\.(?:kt|java|groovy)$", original_name) is not None
    )


def is_test_directory_path(path: str) -> bool:
    normalized = "/" + path.replace("\\", "/").lower().strip("/") + "/"
    return any(segment in normalized for segment in ("/src/test/", "/test/", "/tests/", "/__tests__/"))


def check_golden_deletions(entries: list[tuple[str, ...]]) -> list[Finding]:
    removed: list[str] = []
    for entry in entries:
        status, source = entry[:2]
        if status == "D" and (is_protected_golden_path(source) or is_test_source_path(source)):
            removed.append(source)
        elif status.startswith("R") and len(entry) >= 3:
            destination = entry[2]
            if is_protected_golden_path(source) and not is_protected_golden_path(destination):
                removed.append(f"{source} -> {destination}")
            elif is_test_source_path(source) and not (
                is_test_directory_path(destination) or is_test_source_path(destination)
            ):
                removed.append(f"{source} -> {destination}")
    return [
        Finding(
            "error",
            "golden-deletion",
            f"Existing frozen test fixture/source cannot be deleted or moved outside the test surface: {path}",
        )
        for path in removed
    ]


def review_added_lines(rel: str) -> str:
    outputs = (
        run_git(["diff", "origin/main...HEAD", "--", rel]),
        run_git(["diff", "--", rel]),
        run_git(["diff", "--cached", "--", rel]),
    )
    return "\n".join(
        line[1:]
        for output in outputs
        for line in output.splitlines()
        if line.startswith("+") and not line.startswith("+++")
    )


def is_regression_command(command: str) -> bool:
    try:
        words = shlex.split(command)
    except ValueError:
        return False
    if not words or words[0] == "git":
        return False
    executable = Path(words[0]).name
    args = words[1:]
    def repo_local_runner(raw_path: str, require_executable: bool) -> bool:
        candidate = Path(raw_path)
        if not candidate.is_absolute():
            candidate = ROOT / candidate
        try:
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(ROOT.resolve())
        except (FileNotFoundError, ValueError):
            return False
        return resolved.is_file() and (not require_executable or os.access(resolved, os.X_OK))

    def has_nonexecuting_runner_flag(runner_args: list[str]) -> bool:
        normalized = [word.lower() for word in runner_args]
        return any(
            word in {
                "-h", "--help", "--version", "--collect-only", "--co", "--fixtures",
                "--fixtures-per-test", "--setup-plan", "--markers", "-list", "--list",
                "--listtests", "--list-tests",
            }
            or word.startswith(("--collect-only=", "--co=", "-list=", "--listtests=", "--list-tests="))
            for word in normalized
        )
    normalized_args = [word.lower() for word in args]
    if has_nonexecuting_runner_flag(args) or any(
        word in {"--dry-run", "--no-run"} for word in normalized_args
    ):
        return False
    if executable in {"gradle", "gradlew"}:
        if "-m" in args:
            return False
        tasks = [word for word in args if not word.startswith("-")]
        return any(re.search(r"(?:test|check|build|verify|gate)$", task.rsplit(":", 1)[-1], re.IGNORECASE) for task in tasks)
    if executable in {"mvn", "mvnw"}:
        if any(
            re.fullmatch(r"-D(?:skipTests|maven\.test\.skip)(?:=(?:true|1|yes))?", word, re.IGNORECASE)
            for word in args
        ):
            return False
        goals = [word for word in args if not word.startswith("-")]
        return any(
            goal in {"test", "verify", "package", "install"}
            or re.search(r":(?:test|check|verify)$", goal, re.IGNORECASE)
            for goal in goals
        )
    if executable == "pytest":
        return True
    if executable in {"npm", "pnpm", "yarn"}:
        return any(word in {"test", "check", "build", "verify"} for word in args)
    if executable == "cargo":
        return bool(args and args[0] in {"test", "check", "build"})
    if executable == "go":
        return bool(args and args[0] == "test")
    if executable in {"python", "python3"}:
        if len(args) >= 2 and args[0] == "-m" and args[1] in {"pytest", "unittest"}:
            return True
        return bool(
            args
            and re.search(r"(?:test|check|verify|gate)", Path(args[0]).name, re.IGNORECASE)
            and repo_local_runner(args[0], require_executable=False)
        )
    if executable in {"bash", "sh"}:
        return bool(
            args
            and args[0] not in {"-c", "-lc"}
            and re.search(r"(?:test|check|verify|gate)", Path(args[0]).name, re.IGNORECASE)
            and repo_local_runner(args[0], require_executable=False)
        )
    if not words[0].startswith(("./", "/")) or not re.search(
        r"(?:test|check|verify|gate)", executable, re.IGNORECASE
    ):
        return False
    return repo_local_runner(words[0], require_executable=True)


def check_golden_update_evidence(
    files: list[str], strict: bool, review_additions: dict[str, str] | None = None
) -> list[Finding]:
    golden_paths = sorted(path for path in files if is_protected_golden_path(path))
    if not strict or not golden_paths:
        return []
    for rel in files:
        if not rel.startswith("docs/superpowers/reviews/") or not rel.endswith(".md"):
            continue
        path = ROOT / rel
        if not path.is_file():
            continue
        text = review_additions.get(rel, "") if review_additions is not None else review_added_lines(rel)
        blocks = re.split(r"(?m)(?=^Golden path:\s*)", text)
        covered: set[str] = set()
        placeholder_pattern = re.compile(
            r"\b(?:placeholder|tbd|todo|fixme|fill\s+(?:me|this|in)|not\s+(?:run|executed|actually\s+executed)|pending|n[/-]?a|self[- ]?(?:pass|approved|cleared))\b",
            re.IGNORECASE,
        )
        for block in blocks:
            path_match = re.search(r"(?m)^Golden path:\s*(\S+)\s*$", block)
            reason = re.search(r"(?m)^Golden change reason:\s*(.+)$", block)
            command = re.search(r"(?m)^Regression command:\s*(.+)$", block)
            evidence = re.search(r"(?m)^Regression evidence:\s*PASS\s+[—-]\s*(.+)$", block, re.IGNORECASE)
            critique = re.search(r"(?m)^Critique:\s*CLEARED\s+[—-]\s*(.+)$", block, re.IGNORECASE)
            if not all((path_match, reason, command, evidence, critique)):
                continue
            values = (reason.group(1), command.group(1), evidence.group(1), critique.group(1))
            if any(len(value.strip()) < 12 or placeholder_pattern.search(value) for value in values):
                continue
            if not is_regression_command(command.group(1).strip()):
                continue
            bound_path = path_match.group(1)
            basename = bound_path.rsplit("/", 1)[-1]
            if basename not in evidence.group(1) or basename not in critique.group(1):
                continue
            if not re.search(r"(?:failures?=0|errors?=0|build successful|tests? passed|completed)", evidence.group(1), re.IGNORECASE):
                continue
            covered.add(bound_path)
        if set(golden_paths) <= covered:
            return []
    return [
        Finding(
            "error",
            "golden-change-evidence",
            "Each golden update requires path-bound meaningful reason, executed regression command with PASS evidence, and CLEARED critique in a changed review artifact.",
        )
    ]


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
        "## Product authority",
        "",
        "1. Latest approved ADR/spec",
        "2. Current implementation and executable tests",
        "3. `CLAUDE.md`, `AGENTS.md`, and `docs/superpowers/WORKING_SYSTEM.md` repository policy",
        "4. PHP/hwe historical references only when explicitly requested for frozen-regression maintenance",
        "5. skills.sh installed skills in `skills-lock.json` as advisory workflow aids",
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
    # TEMPORARY deliberate break for CI masking-fix evidence — reverted before the real PR.
    print("DELIBERATE-BREAK-FOR-CI-EVIDENCE: work/opensamguk/ci-agent-system-masking", file=sys.stderr)
    return 1
    parser = argparse.ArgumentParser(description="Check opensamguk working-system invariants.")
    parser.add_argument("--base", help="Git base ref for CI/PR mode, e.g. origin/main.")
    parser.add_argument("--strict", action="store_true", help="Promote drift/evidence warnings to errors.")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args()

    files = changed_files(args.base)
    name_status = changed_name_status(args.base)
    findings: list[Finding] = []
    findings += check_skills_lock(files)
    findings += check_codex_surface()
    findings += check_required_docs()
    findings += check_product_authority_policy()
    findings += check_adr_lite_042_contract()
    findings += check_current_handoff_authority()
    findings += check_golden_deletions(name_status)
    findings += check_golden_update_evidence(files, args.strict)
    findings += check_mandatory_workflow_fallbacks()
    findings += check_cross_agent_critique(files, args.strict)
    findings += check_docs_with_code(files, args.strict)
    findings += check_behavior_evidence(files, args.strict)
    findings += check_gateway_server_registry()
    findings += check_production_seed_default()
    findings += check_no_baked_secondary_servers()
    findings += check_rtk14_deploy_enrichment()

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
