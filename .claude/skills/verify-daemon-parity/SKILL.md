---
name: verify-daemon-parity
description: NPC AI와 턴 데몬이 레거시/docs 대로 동작하는지 확인합니다. 턴 엔진/AI 구현/수정 후 사용.
---

# Daemon Parity 검증

## Purpose

턴 데몬과 NPC AI가 레거시 PHP/docs 문서대로 동작하는지 검증합니다:

1. **턴 데몬 생명주기** — Idle→Running→Flushing→Idle 상태 전환이 docs와 일치하는지 확인
2. **턴 실행 순서** — 장수 커맨드 → 국가 커맨드 → 월간 처리 순서가 레거시와 동일한지 확인
3. **NPC AI 판단** — GeneralAI의 커맨드 선택 로직이 레거시와 동일한지 확인
4. **NPC 정책 시스템** — AutorunNationPolicy/AutorunGeneralPolicy 우선순위 체계가 구현되었는지 확인
5. **결정적 RNG** — NPC 판단에 사용되는 RNG가 결정적이고 시드 구성이 레거시와 동일한지 확인
6. **데몬 제어** — run/pause/resume/getStatus 명령이 동작하는지 확인

## When to Run

- 턴 데몬 스케줄러를 구현하거나 수정한 후
- NPC AI 로직을 구현하거나 수정한 후
- 턴 실행 순서를 변경한 후
- 데몬 제어 API를 추가한 후
- in-memory 상태 관리 로직을 수정한 후

## Related Files

| File                                                                    | Purpose                        |
| ----------------------------------------------------------------------- | ------------------------------ |
| `docs/architecture/runtime.md`                                          | 런타임/데몬 구조 문서          |
| `docs/architecture/turn-daemon-lifecycle.md`                            | 턴 데몬 생명주기 문서          |
| `docs/architecture/legacy-engine-execution.md`                          | 레거시 턴 실행 흐름            |
| `docs/architecture/legacy-engine-ai.md`                                 | 레거시 NPC AI 문서             |
| `legacy/hwe/sammo/TurnExecutionHelper.php`                              | 레거시 턴 실행 코드            |
| `legacy/hwe/sammo/GeneralAI.php`                                        | 레거시 NPC AI 코드             |
| `legacy/hwe/sammo/AutorunNationPolicy.php`                              | 레거시 국가 정책               |
| `legacy/hwe/sammo/AutorunGeneralPolicy.php`                             | 레거시 장수 정책               |
| `core2026/packages/logic/src/ai/__tests__/GeneralAI.test.ts`            | core2026 AI 테스트 (참조)      |
| `backend/src/main/kotlin/com/opensam/engine/`                           | 백엔드 엔진 (구현 대상)        |
| `backend/src/main/kotlin/com/opensam/engine/RealtimeService.kt`         | 실시간 모드/턴 데몬 서비스     |
| `backend/src/main/kotlin/com/opensam/ai/`                               | 백엔드 NPC AI (구현 대상)      |
| `backend/src/main/kotlin/com/opensam/controller/RealtimeController.kt`  | 실시간/턴 제어 REST 엔드포인트 |
| `backend/src/main/kotlin/com/opensam/controller/NpcPolicyController.kt` | NPC 정책 관리 REST 엔드포인트  |

## Workflow

### Step 1: 턴 데몬 상태 모델 확인

**검사:** 턴 데몬이 docs에 정의된 상태 전환을 구현하는지 확인합니다.

docs 기준 상태 모델:

```
Idle -> Running -> Flushing -> Idle
   \-> Paused (admin)
   \-> Stopping (shutdown)
```

```bash
# 데몬 상태 관련 코드 검색
grep -rn "Idle\|Running\|Flushing\|Paused\|Stopping\|DaemonState\|TurnState" backend/src/main/kotlin/com/opensam/ 2>/dev/null
grep -rn "@Scheduled\|turnDaemon\|TurnRunner\|processTurn" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 상태 모델이 docs와 일치하는 enum/sealed class로 구현됨
**FAIL:** 상태 모델 누락 또는 불일치

### Step 2: 턴 실행 순서 확인

**파일:** `docs/architecture/legacy-engine-execution.md`

**검사:** 턴 루프가 레거시의 실행 순서를 따르는지 확인합니다.

레거시 턴 실행 순서:

1. Time gate (turntime 미도달 시 스킵)
2. Locking (중복 실행 방지)
3. Pre-turn maintenance
4. Catch-up monthly loop:
   - `executeGeneralCommandUntil()` — 장수별 커맨드 실행
   - `updateTraffic()`
   - 월간 파이프라인: PreMonth → preUpdate → turnDate → Month → postUpdate
5. Current partial month 처리
6. Post-turn (tournament, auction)

```bash
# 턴 루프 코드에서 실행 순서 확인
grep -rn "executeGeneral\|executeNation\|monthly\|preUpdate\|postUpdate\|turnDate\|catch.?up" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 턴 실행 순서가 레거시와 일치
**FAIL:** 실행 순서 차이 발견

### Step 3: 장수별 실행 로직 확인

**파일:** `docs/architecture/legacy-engine-execution.md` (Per-General Execution 섹션)

**검사:** 장수별 커맨드 실행이 레거시와 동일한 로직을 따르는지 확인합니다.

레거시 장수별 실행:

1. 장수 로드 (turntime 순서)
2. officer_level ≥ 5 → 국가 커맨드 실행
3. NPC(npc ≥ 2) → GeneralAI 판단
4. 장수 커맨드 실행
5. turntime 갱신

```bash
# 장수별 실행 관련 코드 검색
grep -rn "officer_level\|officerLevel\|npc\b\|GeneralAI\|autorun" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 장수별 실행 로직이 레거시와 일치
**FAIL:** 로직 차이 발견

### Step 4: NPC AI 판단 로직 확인

**파일:** `docs/architecture/legacy-engine-ai.md`

**검사:** NPC AI가 레거시 GeneralAI의 판단 로직을 구현하는지 확인합니다.

핵심 확인 사항:

**4a. 외교 상태 계산 (calcDiplomacyState):**

- d평화, d선포, d징병, d직전, d전쟁 상태가 구현되어 있는지

```bash
grep -rn "DiplomacyState\|d평화\|d선포\|d징병\|d직전\|d전쟁\|calcDiplomacy" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**4b. 장수 유형 분류 (calcGenType):**

- t무장, t지장, t통솔장 비트마스크가 구현되어 있는지

```bash
grep -rn "genType\|GenType\|t무장\|t지장\|t통솔장\|GeneralType" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**4c. 정책 기반 우선순위:**

- AutorunNationPolicy/AutorunGeneralPolicy의 priority 배열 처리

```bash
grep -rn "policy\|Policy\|priority\|autorun\|Autorun" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**4d. 국가 턴 행동 그룹:**

- 부대 이동, 자원 분배, 외교, 천도

**4e. 장수 턴 행동 그룹:**

- 내정, 전쟁 준비, 이동, 자원, 중립 행동

**PASS:** NPC AI 핵심 로직이 구현됨
**FAIL:** NPC AI 핵심 로직 누락

### Step 5: 결정적 RNG 확인

**검사:** NPC AI에 사용되는 RNG가 결정적이고 시드 구성이 레거시와 동일한지 확인합니다.

레거시 RNG 시드: `hiddenSeed + "GeneralAI" + year + month + generalID`

```bash
grep -rn "RNG\|Random\|seed\|deterministic\|LiteHash\|DRBG" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 결정적 RNG가 구현되고 시드 구성이 레거시와 동일
**FAIL:** 비결정적 RNG 사용 또는 시드 구성 불일치

### Step 6: 데몬 제어 명령 확인

**파일:** `docs/architecture/turn-daemon-lifecycle.md`

**검사:** 데몬 제어 명령(run/pause/resume/getStatus)이 구현되어 있는지 확인합니다.

```bash
# 데몬 제어 API 검색
grep -rn "run\|pause\|resume\|getStatus\|DaemonCommand" backend/src/main/kotlin/com/opensam/ 2>/dev/null | grep -i "daemon\|turn\|engine"
```

docs 기준 제어 명령:

- `run` (reason: schedule/manual/poke)
- `pause` (reason)
- `resume` (reason)
- `getStatus` (requestId)

**PASS:** 4개 제어 명령이 구현됨
**FAIL:** 제어 명령 누락

### Step 7: in-memory 상태 및 DB flush 확인

**검사:** 데몬이 in-memory 상태를 주 작업 세트로 사용하고 턴 처리 후 DB flush를 수행하는지 확인합니다.

```bash
grep -rn "flush\|Flush\|inMemory\|InMemory\|saveAll\|persist\|bulk" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** in-memory 상태 관리 + 벌크 DB flush 패턴 존재
**FAIL:** 매 작업마다 개별 DB 쓰기 (성능 문제)

## Output Format

```markdown
## Daemon Parity 검증 결과

### 턴 데몬

| #   | 항목              | 상태      | 상세 |
| --- | ----------------- | --------- | ---- |
| 1   | 상태 모델         | PASS/FAIL | ...  |
| 2   | 턴 실행 순서      | PASS/FAIL | ...  |
| 3   | 장수별 실행       | PASS/FAIL | ...  |
| 4   | 데몬 제어 명령    | PASS/FAIL | ...  |
| 5   | in-memory + flush | PASS/FAIL | ...  |

### NPC AI

| #   | 항목           | 상태      | 상세 |
| --- | -------------- | --------- | ---- |
| 1   | 외교 상태 계산 | PASS/FAIL | ...  |
| 2   | 장수 유형 분류 | PASS/FAIL | ...  |
| 3   | 정책 우선순위  | PASS/FAIL | ...  |
| 4   | 국가 턴 행동   | PASS/FAIL | ...  |
| 5   | 장수 턴 행동   | PASS/FAIL | ...  |
| 6   | 결정적 RNG     | PASS/FAIL | ...  |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **기술스택 차이** — docs는 TypeScript/Redis Stream 기반이지만, Spring Boot/@Scheduled 기반 구현은 허용; 기능적 동등성이 유지되면 PASS
2. **Gateway 모델 대체** — docs의 Gateway orchestration 대신 단일 Spring Boot 앱 구조는 CLAUDE.md의 의도적 설계 변경
3. **NPC AI 미구현** — 프로젝트 초기에 NPC AI가 아직 없을 수 있음; 이 경우 전체를 SKIP으로 보고하되, AI 관련 인터페이스/클래스 구조가 준비되어 있는지는 확인
4. **부분 구현** — GeneralAI의 모든 행동 그룹이 아닌 일부만 구현된 것은 진행 상황으로 보고 (FAIL이 아님)
5. **PM2 대신 Spring** — docs의 PM2 프로세스 관리 대신 Spring Boot 내장 라이프사이클 사용은 허용
6. **Run budget 미구현** — budgetMs/maxGenerals/catchUpCap은 최적화 사항; 기본 턴 루프가 동작하면 PASS
7. **RNG 시드 구성 조정** — 시드 문자열이 약간 다르더라도 결정적 RNG가 보장되면 warning으로 보고
