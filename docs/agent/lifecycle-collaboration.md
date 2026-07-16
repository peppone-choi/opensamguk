# Lifecycle Runbook: Collaboration (다중 에이전트 협업)

## Status

**PARTIAL** — worktree 병렬 팬아웃(`parity-wave.js`, disjoint 파일 규칙)과 cross-agent critique는 실적 다수(ACTIVE). `.ai/ownership.md` 등록부는 2026-07-16 신설(아직 미검증 관행).

## Read This When

Claude와 Codex(또는 복수 세션)가 동시에 작업할 때, 팬아웃 전, 타 에이전트 작업을 이어받을 때.

## Preconditions

`.ai/ownership.md` 확인 — 대상 파일이 비어 있어야 시작.

## Procedure

1. 작업 분해가 **disjoint 파일**인지 확인(공유 확장점은 sequential — `collaboration-protocol.md`).
2. `.ai/ownership.md`에 등록(에이전트/태스크/브랜치·worktree/소유 파일).
3. worktree/브랜치 격리로 실행. 규칙 정본: `collaboration-protocol.md`(single-writer, 추측 승격 금지, 덮어쓰기 금지).
4. 완료 시 결과를 `.ai/handoff.md`(+ critique 아티팩트)로 전달, ownership 해제.
5. 이어받는 쪽은 handoff의 "결정 vs 추측", "실행한/안 한 검증"을 구분해 수용 — 미검증 주장은 재검증.

## 병렬화 적합/부적합

`collaboration-protocol.md`의 표가 정본 (여기 중복하지 않음).

## Human Approval Gates

동일 기능의 경쟁 구현 지시, stale ownership 강제 해제.

## Verification

fan-in 시 각 샤드의 게이트 green을 **개별 확인**(집계 주장 금지) 후 통합 게이트 재실행.

## Failure Handling

- 죽은 에이전트: 출력 동결 + worktree 무활동으로 판정 → 사람 확인 → 잔존물 회수(선례: `docs/superpowers/SESSION_HANDOFF.md` 2026-06-10).
- 소유권 충돌: `failure-cases.md` AI-Failure-005 절차.

## Completion Criteria

모든 샤드 green + ownership 전부 해제 + handoff 완성.

## State Files to Update

`.ai/ownership.md`, `.ai/handoff.md`, `.ai/current-state.md`.

## Handoff Requirements

샤드별 결과, 실패/재시도 이력, 통합 게이트 결과.
