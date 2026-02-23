# Engine Parallel Work Plan

Generated: 2026-01-15

## 목표

엔진 구축 작업을 병렬로 분해하여 동시 진행 가능한 트랙과 병합 지점을 정의한다.

---

## Dependency Graph (요약)

- **World State 정의(B)** → **Persistence(C)**, **Engine Core(A)** 의존
- **Command/Constraint Integration(D)** → **Engine Core(A)** 의존
- **Observability(E)** → **Engine Core(A)** 결과 연동
- **Recovery/Control(F)** → **Persistence(C)** + **Engine Core(A)** 의존

---

## Parallel Lanes

### Lane A — Engine Core

**목표:** 엔진 메인 루프/디스패처/실행기 구현

- A1. GameEngine 스켈레톤 확정 (입력/출력 타입 명세)
- A2. 루프 스케줄러 구현 (tick interval, budget)
- A3. Turn Dispatcher 구현 (처리 대상 선정/우선순위)
- A4. Turn Executor 구현 (커맨드 실행/실패 처리)
- A5. State 적용 연결점 정의 (WorldDelta 병합 호출 위치)

**체크리스트**

- [ ] `GameEngine` class 생성 및 public API 고정
- [ ] `TurnRunBudget` 처리 규칙 정의
- [ ] `TurnDispatcher`에서 대상 추출 로직 작성
- [ ] `TurnExecutor`에서 커맨드 파이프라인 구현
- [ ] 실패 시 처리 규칙 문서화

**산출물**

- `apps/engine/src/engine.service.ts`에 loop/dispatch/execute 통합
- `packages/logic/src/stubs.ts` 제거 가능한 실제 구현 기반

---

### Lane B — World State

**목표:** WorldSnapshot/WorldDelta 구조 및 검증 규칙 확정

- B1. WorldSnapshot 모델 확정 (entity maps)
- B2. WorldDelta 모델 확정 (updates/logs)
- B3. Validation Rules 정의 (range/refs)

**체크리스트**

- [ ] `WorldSnapshot` 타입 및 필드 확정
- [ ] `WorldDelta` 타입 및 필드 확정
- [ ] 엔티티 참조 무결성 룰 정의

**산출물**

- `packages/common/src/types/world.ts` 정식 확정
- `packages/logic/src/world/*` 구조 정비

---

### Lane C — Persistence

**목표:** Snapshot Load/Flush 구현

- C1. Snapshot Load (DB → InMemoryWorld)
- C2. Snapshot Flush (DirtyTracker 기반)
- C3. Flush 정책/주기 정의

**체크리스트**

- [ ] DB 스키마 ↔ 메모리 구조 매핑 완료
- [ ] DirtyTracker 로직 적용
- [ ] Flush interval/조건 문서화

**산출물**

- `SnapshotRepository` 실제 구현
- Flush 성능 테스트 스크립트

---

### Lane D — Commands/Constraints Integration

**목표:** StateView + 커맨드 실행 연결

- D1. StateView 구현 (`get`, `has`)
- D2. Command 조건 검사 경로 연결
- D3. RNG 주입 규칙 확정

**체크리스트**

- [ ] `StateView` 구현체 추가
- [ ] `hasFullConditionMet(stateView)` 경로 통합
- [ ] RNG 주입 일관성 확인

**산출물**

- `packages/logic/src/command/BaseCommand.ts` 경로 검증
- `TurnExecutionHelper` 연동 완료

---

### Lane E — Observability

**목표:** 상태/메트릭/로그 수집

- E1. 처리량/지연/실패 수집
- E2. 상태 노출 (queue depth, last flush)
- E3. 로그 구조 정의

**체크리스트**

- [ ] 기본 메트릭 정의
- [ ] 상태 조회 API 설계
- [ ] 로그 포맷 정의

**산출물**

- `apps/engine` 상태 확인 엔드포인트 또는 내부 API
- 로그 스키마 문서

---

### Lane F — Recovery/Control

**목표:** Crash Recovery + Safe Stop

- F1. Crash Recovery 절차
- F2. Safe Stop 구현
- F3. Restart/Resume 플로우

**체크리스트**

- [ ] 마지막 스냅샷 복구 경로 문서화
- [ ] safe stop 처리 규칙 정의
- [ ] 재시작 시 checkpoint 적용

**산출물**

- Recovery runbook 문서
- 엔진 종료 처리 코드

---

## Merge Points (병합 지점)

- **M1**: B + C → Snapshot/Delta 기반 확정
- **M2**: A + D → 커맨드 실행 파이프라인 완성
- **M3**: A + E + F → 운영 가능 수준

---

## 권장 진행 순서

1. Lane B/C 병렬 진행 → M1
2. Lane A/D 병렬 진행 → M2
3. Lane E/F 병렬 진행 → M3

---

## 협업 체크포인트

- **CP1**: WorldSnapshot/Delta 확정 리뷰
- **CP2**: Turn 실행 파이프라인 데모
- **CP3**: Crash Recovery 리허설

---

## 6개 서브에이전트 지시서

### Agent 1 — Engine Core (Lane A)

**목표:** `GameEngine` 루프/디스패처/실행기 구현
**해야 할 일**

- `GameEngine` public API 확정 (입력 `WorldSnapshot`, 출력 `WorldDelta`)
- Turn Scheduler + Dispatcher 구현
- Turn Executor 구현 (명령 실행/실패 처리)
  **입력:** `apps/engine/src/engine.service.ts`, `packages/logic/src/world/*`
  **산출물:** 엔진 루프 동작 코드, 실패 처리 정책 요약
  **체크포인트:** M2

### Agent 2 — World State (Lane B)

**목표:** `WorldSnapshot`/`WorldDelta` 스키마 확정
**해야 할 일**

- `WorldSnapshot` 필드/맵 구조 확정
- `WorldDelta` 업데이트/로그 구조 정의
- 무결성 검증 룰 정의
  **입력:** `packages/common/src/types/world.ts`, `packages/logic/src/world/*`
  **산출물:** 타입 정의 + 검증 규칙 문서
  **체크포인트:** M1

### Agent 3 — Persistence (Lane C)

**목표:** Snapshot Load/Flush 구현
**해야 할 일**

- DB→메모리 로더 구현
- DirtyTracker 기반 Flush 구현
- Flush 주기/조건 문서화
  **입력:** `packages/infra/*`, `SnapshotRepository`
  **산출물:** 실제 SnapshotRepository 구현 + 테스트
  **체크포인트:** M1

### Agent 4 — Commands/Constraints Integration (Lane D)

**목표:** 커맨드 실행 + StateView 연결
**해야 할 일**

- `StateView` 구현 (`get`, `has`)
- `hasFullConditionMet(stateView)` 경로 통합
- RNG 주입 규칙 확정
  **입력:** `packages/logic/src/command/*`, `BaseCommand`
  **산출물:** 실행 경로 연결 코드 + 검증 시나리오
  **체크포인트:** M2

### Agent 5 — Observability (Lane E)

**목표:** 메트릭/상태/로그 설계
**해야 할 일**

- 메트릭 항목 정의 (처리량/지연/실패)
- 상태 조회 API 설계
- 로그 포맷 정의
  **입력:** `apps/engine`, 로깅 시스템
  **산출물:** 상태 API 스펙 + 로그 스키마 문서
  **체크포인트:** M3

### Agent 6 — Recovery/Control (Lane F)

**목표:** Crash Recovery + Safe Stop
**해야 할 일**

- 재시작 복구 절차 정의
- Safe Stop 흐름 구현
- 체크포인트/재개 전략 문서화
  **입력:** Snapshot/Delta, Engine loop
  **산출물:** 복구/정지 플로우 문서 + 코드 스텁
  **체크포인트:** M3
