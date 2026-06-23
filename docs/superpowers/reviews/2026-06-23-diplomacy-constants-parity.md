# Review — loop/diplomacy-constants-parity

Scope: `postUpdateMonthlyDiplomacy` 외교 턴상수 하드코딩 제거 → `DiplomacyConst` 참조.

Reviewer: `oh-my-claudecode:code-reviewer` (cross-agent critique).
Date: 2026-06-23.

## Source of truth

- PHP `func_gamerule.php:336-421` — Q5 `valueFit(term+Δ, 0, 13)`, Q9 선포→교전 시 `term=6`.
- `DiplomacyConst` (`logic/src/main/kotlin/opensamguk/logic/diplomacy/DiplomacyState.kt:29-34`) — `MAX_WAR_TERM=13`, `DEFAULT_WAR_TERM=6`.
- `DiplomacyMonthProcessor`가 이미 동일 상수를 사용 중.

## Changes

- `PostUpdateMonthly.kt` Q5 clamp `13.0` → `DiplomacyConst.MAX_WAR_TERM.toDouble()`.
- `PostUpdateMonthly.kt` Q9 선포 만료 기본 턴 `6` → `DiplomacyConst.DEFAULT_WAR_TERM`.
- `PostUpdateMonthlyDiplomacyTest.kt`에 상수 사용 여부 단언 추가.

## Findings

- BLOCKER/HIGH/MEDIUM/LOW 없음.

## Verdict

Verdict: cleared. `DiplomacyMonthProcessor`와 `postUpdateMonthlyDiplomacy`가 동일 상수를 참조하며 미래 상수 변경 시 패러티 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
