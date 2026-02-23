# Engine Foundation Document

## 목적

`apps/engine`는 게임 턴을 진행하는 **상태 권위 프로세스**입니다. API는 요청 검증/라우팅만 수행하고, 엔진이 월드 상태를 메모리에서 소유하며 턴을 진행합니다.

## 핵심 원칙

- **In-Memory Authority**: 게임 상태는 메모리가 최종 권위이며 DB는 스냅샷/저널 역할만 수행
- **Deterministic Logic**: RNG는 `@sammo/common`의 `LiteHashDRBG` 사용
- **Single Writer**: 엔진만 월드 상태를 변경
- **Pure Logic Separation**: 도메인 로직은 `packages/logic`에 집중

## 프로세스 구성

- `apps/engine`: 턴 데몬
- `apps/api`: 입력/검증/라우팅
- `packages/logic`: 커맨드/제약/트리거/전투 등 순수 로직
- `packages/infra`: Prisma/Redis/스토리지

## 실행 흐름 (턴 사이클)

1. **Bootstrap**
   - DB에서 스냅샷 로드 → 메모리 월드 구성
   - 부족한 데이터는 초기 시나리오/게임 환경으로 보완
2. **Tick Loop**
   - 현재 시간 기준 대상 장수/국가 턴 추출
   - `WorldSnapshot` → 커맨드 실행 → `WorldDelta` 생성
   - `WorldDelta` 병합 후 메모리 상태 갱신
3. **Flush**
   - 일정 간격/조건에서 스냅샷 및 로그 DB 반영

## 데이터 모델

- `WorldSnapshot`: 현재 월드의 전체 스냅샷
- `WorldDelta`: 단일 턴 처리 결과(증분)
- `TurnRunBudget`: 1회 수행 예산(처리 수, 시간 제한)

## 입력 채널

- **Commands**: Redis Streams or DB Queue
- **Events**: Pub/Sub (엔진 → API)

## 장애/복구

- **Crash Recovery**: 마지막 스냅샷 + 이벤트 로그로 복구
- **Idempotency**: 동일 커맨드 중복 실행 방지 (command id, nonce)
- **Safe Stop**: stop 요청 시 현재 턴 처리 종료 후 flush

## 로그 및 모니터링

- 턴 처리 시간, 처리량, 실패/재시도 카운트
- 현재 턴 타임, queue depth, last flush time

## 구현 체크리스트

### 1) GameEngine 구현

- [ ] `GameEngine` 클래스 스켈레톤 확정 (입력: `WorldSnapshot`, 출력: `WorldDelta`)
- [ ] 커맨드 실행 파이프라인 정의 (`GeneralCommand`, `NationCommand` 분리)
- [ ] 턴 단위 처리 로직 추가 (일반/국가 턴 순서 규칙)
- [ ] `WorldDelta` 생성 규칙 명세 (업데이트/로그/스탯 변경)
- [ ] 예외 처리 정책 (실패 시 롤백/스킵/재시도)

### 2) SnapshotRepository 구현

- [ ] 스냅샷 로드 (초기 월드 구성)
- [ ] 스냅샷 저장 (flush 주기/조건)
- [ ] 저장 스키마 정의 (worldState/nation/city/general/troop/diplomacy)
- [ ] 저널(이벤트 로그) 연동 여부 결정
- [ ] 재시작 복구 절차 정의

### 3) DeltaUtil 병합/검증

- [ ] `WorldDelta` 구조 확정
- [ ] `WorldSnapshot + Delta` 병합 로직 구현
- [ ] 충돌/중복 업데이트 병합 전략 정의
- [ ] 검증기(필드 범위/무결성) 추가
- [ ] 실패 시 처리 (에러 로그/중단 기준)

### 4) UnitRegistry 로드/캐시

- [ ] 유닛 데이터 소스 확정 (정적 파일/DB)
- [ ] 로딩 API 정의 (`load`, `get`, `getAllUnits`)
- [ ] 캐시 정책 정의 (hot reload 여부)
- [ ] 유닛 데이터 스키마 정리 (명칭, 타입, 능력치)

### 5) TurnExecutionHelper 연결

- [ ] `TurnExecutionHelper`에서 `StateView` 제공 방식 확정
- [ ] pre-trigger/after-trigger 실행 지점 결정
- [ ] 커맨드 조건 검사(`hasFullConditionMet`)와 상태 뷰 연결
- [ ] 결과 기록 방식(로그/lastTurn)

---

## 상세 업무 분해 (작업 단위 + 해야 할 일)

### A. Engine Core

**A1. 엔진 루프 스케줄러**

- [ ] 엔진 루프 시작/중지 플로우 설계
- [ ] 주기 실행 간격 및 배치 처리 수 정의
- [ ] 예산(`TurnRunBudget`) 적용 방식 정의

**A2. Turn Dispatcher**

- [ ] 처리 대상 장수/국가 추출 기준 정의
- [ ] NPC/유저 턴 처리 우선순위 규칙 정의
- [ ] 누락/중복 턴 방지 로직 추가

**A3. Turn Executor**

- [ ] 커맨드 실행 전후 트리거 호출
- [ ] 실패 커맨드 처리 규칙 정의
- [ ] 마지막 턴 기록 업데이트

### B. World State

**B1. WorldSnapshot 구조 확정**

- [ ] 엔티티별 저장 구조 확정
- [ ] 메모리 인덱스 구조 확정 (Map key, id 타입)

**B2. WorldDelta 구조 확정**

- [ ] 업데이트 타입(부분 update) 명세
- [ ] 로그/이벤트 기록 스키마 확정

**B3. 검증/무결성**

- [ ] 기본 값 범위 체크 룰 작성
- [ ] 엔티티 간 참조 무결성 체크

### C. Persistence

**C1. Snapshot Load**

- [ ] DB → InMemoryWorld 변환
- [ ] 누락 데이터 초기화 규칙 정의

**C2. Snapshot Flush**

- [ ] 변경 추적(DirtyTracker) 적용
- [ ] 배치 업데이트 성능 검증

### D. Commands/Constraints Integration

**D1. StateView 공급**

- [ ] `ConstraintContext`와 연결되는 `StateView` 구현
- [ ] `get/has` 성능 최적화

**D2. Command Execution**

- [ ] 커맨드 실행 시 RNG 주입
- [ ] 결과 업데이트 및 로그 처리

### E. Observability

**E1. 메트릭**

- [ ] 처리 시간/처리 수/실패 수
- [ ] 큐 깊이, 최근 flush 시간

**E2. 로깅**

- [ ] turn별 로그 저장 포맷 정의
- [ ] 에러/경고 로깅 정책 확정

### F. Recovery

**F1. Crash Recovery**

- [ ] 마지막 스냅샷부터 재시작
- [ ] 중간 상태 복구 가능 여부 정의

**F2. Safe Stop**

- [ ] 현재 턴 마무리 후 종료
- [ ] 종료 시 flush 보장

## 참고 파일

- `packages/logic/src/world/*`
- `packages/logic/src/command/*`
- `packages/logic/src/trigger/*`
- `apps/engine/src/engine.service.ts`
