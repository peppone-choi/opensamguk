# Lifecycle Runbook: Review (리뷰)

## Status

**ACTIVE** — cross-agent critique가 strict CI로 강제됨(`tools/agent-system/check.py --strict`가 비자명 변경에 `docs/superpowers/reviews/*.md` 아티팩트 요구). parity-reviewer 에이전트, 재채점(critic) 워크플로 실적 다수.

## Read This When

커밋 전(비자명 변경), PR 검토, 타 에이전트 산출물 검증.

## Preconditions

리뷰어는 **구현자와 다른 컨텍스트/에이전트** — 같은 세션에서 자기 승인 금지.

## Inputs

diff, `.ai/task.md`(요구사항), 현재 승인 ADR/spec·구현·테스트 결과. ADR-LITE-042에 따라 명시적으로 선택한 역사 비교에서만 PHP 증거를 추가한다.

## Procedure

```text
diff 확인 (git diff / code-review-graph detect_changes)
→ 요구사항 대조 (.ai/task.md In scope/AC)
→ AI 1차 리뷰 (아래 책임 분담)
→ 테스트 확인 (실행 결과 XML — 주장 아님)
→ 사람 리뷰 (아키텍처 의도·운영 위험·병합 여부)
→ 수정 → 재검증 → 병합 승인
```

리뷰 계약(정본: `docs/superpowers/WORKING_SYSTEM.md` §Cross-agent critique): 구현자 claim → critic이 승인 요구사항·테스트·문서·하드코딩·운영 불변식을 **독립적으로** 공격하고, opt-in 역사 동결 회귀 범위에서만 PHP 비교 증거를 검토 → `cleared` / `fix-required` / `quarantined-with-proof`. `fix-required`가 남으면 merge/ship 금지. 아티팩트는 `docs/superpowers/reviews/<date>-<scope>.md`.

## AI 책임 / 사람 책임

- AI: 잠재 버그, 누락 테스트, replay/RNG 수치 변경·로그·write-order 보존 규칙, 일관성, 일반 보안, 성능 위험, 변경 요약.
- 사람: 비즈니스 요구, 아키텍처 의도, 운영 위험 수용, 보안 승인, **병합 여부**.

## 심각도

`BLOCKER / MAJOR / MINOR / QUESTION`. 각 지적에 파일·위치, 문제, 실제 위험, 재현/근거, 권장 수정, 확신 수준 필수. 근거 없는 지적 금지.

## Human Approval Gates

병합/배포 결정, `fix-required` 해제 판정의 최종 승인.

## Verification

리뷰 지적이 실제 코드와 대조 가능한지(파일:라인), 수정 후 재검증 green.

## Failure Handling

리뷰 결과가 서로 충돌하면 리더(사람)가 증거로 재정 — 다수결 아님.

## Completion Criteria

critique `cleared` + 아티팩트 존재(비자명 변경) + 사람 병합 승인.

## State Files to Update

`.ai/current-state.md`, 리뷰 아티팩트.

## Handoff Requirements

리뷰 판정과 미해결 지적을 handoff에 기재.
