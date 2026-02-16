---
name: verify-logic-parity
description: core2026/레거시 대비 게임 로직이 동일한 결과를 내는지 확인합니다(같은 입력 → 같은 출력). 엔진 로직 구현/수정 후 사용.
---

# Logic Parity 검증

## Purpose

레거시 PHP/core2026의 게임 엔진 로직이 새 백엔드에서 동일한 결과를 내는지 검증합니다:

1. **커맨드 실행 로직** — 같은 입력(장수 상태, 도시 상태)으로 같은 출력(스탯 변화, 메시지)이 나오는지 확인
2. **턴 실행 순서** — 턴 처리 순서가 레거시와 일치하는지 확인
3. **제약조건(Constraints)** — 커맨드 실행 조건이 레거시와 동일한지 확인
4. **수식/공식** — 스탯 계산, 전투 공식, 경제 공식이 레거시와 일치하는지 확인
5. **이벤트/트리거** — 게임 이벤트 발화 조건이 레거시와 동일한지 확인

## When to Run

- 커맨드 run() 로직을 구현하거나 수정한 후
- 턴 엔진(TurnExecutionHelper) 로직을 구현한 후
- 제약조건(Constraint) 평가기를 구현한 후
- 전투/경제/외교 엔진을 구현한 후
- 스탯 계산 공식을 변경한 후

## Related Files

| File                                                    | Purpose                     |
| ------------------------------------------------------- | --------------------------- |
| `docs/architecture/legacy-engine-execution.md`          | 턴 실행 순서/로직 문서      |
| `docs/architecture/legacy-engine-war.md`                | 전투 엔진 문서              |
| `docs/architecture/legacy-engine-economy.md`            | 경제 엔진 문서              |
| `docs/architecture/legacy-engine-diplomacy.md`          | 외교 엔진 문서              |
| `docs/architecture/legacy-engine-constraints.md`        | 제약조건 문서               |
| `docs/architecture/legacy-engine-general.md`            | 장수 엔진 문서              |
| `docs/architecture/legacy-engine-events.md`             | 이벤트 엔진 문서            |
| `docs/architecture/legacy-engine-triggers.md`           | 트리거 문서                 |
| `docs/architecture/legacy-engine-ai.md`                 | NPC AI 문서                 |
| `docs/architecture/legacy-engine-constants.md`          | 게임 상수 문서              |
| `legacy/hwe/sammo/TurnExecutionHelper.php`              | 레거시 턴 실행 코드         |
| `legacy/hwe/sammo/WarUnit.php`                          | 레거시 전투 유닛            |
| `legacy/hwe/sammo/WarUnitGeneral.php`                   | 레거시 장수 전투            |
| `legacy/hwe/sammo/WarUnitCity.php`                      | 레거시 도시 전투            |
| `legacy/hwe/sammo/GeneralAI.php`                        | 레거시 NPC AI               |
| `core2026/packages/logic/src/command/BaseCommand.ts`    | core2026 커맨드 기반 클래스 |
| `core2026/packages/logic/src/command/GeneralCommand.ts` | core2026 장수 커맨드        |
| `core2026/packages/logic/src/command/NationCommand.ts`  | core2026 국가 커맨드        |
| `core2026/packages/logic/src/constraints/`              | core2026 제약조건 평가기    |
| `backend/src/main/kotlin/com/opensam/engine/`           | 백엔드 엔진 (구현 대상)     |
| `backend/src/main/kotlin/com/opensam/command/`          | 백엔드 커맨드 (구현 대상)   |

## Workflow

### Step 1: 구현된 커맨드의 로직 대조

**검사:** 구현된 각 커맨드의 run() 로직이 레거시/core2026와 동일한 결과를 내는지 확인합니다.

각 구현된 커맨드에 대해:

1. 백엔드 커맨드 코드를 읽음
2. 대응하는 core2026 TypeScript 커맨드를 읽음
3. 대응하는 legacy PHP 커맨드를 읽음
4. 핵심 로직(스탯 변화, 조건, 메시지)이 동일한지 비교

```bash
# 구현된 백엔드 커맨드 목록
ls backend/src/main/kotlin/com/opensam/command/general/ 2>/dev/null
ls backend/src/main/kotlin/com/opensam/command/nation/ 2>/dev/null
```

**비교 기준:**

- 스탯 변화량 (예: 농지개간 → city.agriculture += 계산값)
- 자원 소모량 (예: 모병 → gold -= 계산값)
- 실행 조건 (예: 건국 → 소속 국가 없음)
- 결과 메시지 텍스트 패턴

**PASS:** 핵심 로직이 레거시/core2026와 동일
**FAIL:** 로직 차이 발견 — 차이점을 상세히 기록

### Step 2: 수식/공식 일치 확인

**검사:** 게임 내 주요 수식이 레거시 문서/코드와 일치하는지 확인합니다.

주요 수식 카테고리:

- **내정 커맨드 효과량** — leadership/intel/politics 기반 계산
- **모병/징병 수** — crew 증감 공식
- **전투 공식** — 공격력/방어력/피해량 계산
- **경제 공식** — 세수/인구 증감

```bash
# 백엔드에서 수식 관련 코드 검색
grep -rn "leadership\|strength\|intel\|politics\|charm" backend/src/main/kotlin/com/opensam/command/ 2>/dev/null | grep -v "import\|package"
grep -rn "calculate\|formula\|compute" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

각 발견된 수식에 대해 레거시 문서의 해당 공식과 비교합니다.

**PASS:** 수식이 레거시 문서와 일치
**FAIL:** 수식 차이 발견

### Step 3: 턴 실행 순서 확인

**파일:** `docs/architecture/legacy-engine-execution.md`

**검사:** 턴 실행 순서가 레거시와 동일한지 확인합니다.

```bash
# 백엔드 턴 실행 코드 검색
grep -rn "TurnExecut\|processTurn\|@Scheduled" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

레거시 턴 실행 순서 (legacy-engine-execution.md 참조):

1. 장수 커맨드 실행 (턴 순서대로)
2. 국가 커맨드 실행
3. 도시 자원 업데이트
4. 이벤트 처리
5. AI 판단

**PASS:** 턴 실행 순서가 레거시와 일치
**FAIL:** 턴 실행 순서 차이 발견

### Step 4: 제약조건 평가 확인

**파일:** `docs/architecture/legacy-engine-constraints.md`

**검사:** 커맨드 실행 제약조건이 레거시와 동일한지 확인합니다.

```bash
# 백엔드 제약조건 코드 검색
grep -rn "constraint\|Constraint\|canExecute\|checkCondition" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 제약조건이 레거시/core2026와 동일
**FAIL:** 제약조건 차이 발견

### Step 5: 이벤트/트리거 확인

**파일:** `docs/architecture/legacy-engine-events.md`, `docs/architecture/legacy-engine-triggers.md`

**검사:** 게임 이벤트 발화 조건이 레거시와 동일한지 확인합니다.

```bash
# 백엔드 이벤트/트리거 코드 검색
grep -rn "event\|trigger\|EventHandler\|TriggerHandler" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 이벤트/트리거 로직이 레거시와 일치
**FAIL:** 이벤트/트리거 로직 차이 발견

## Output Format

```markdown
## Logic Parity 검증 결과

### 검증 대상 커맨드: N개

| #   | 커맨드         | 로직 일치 | 수식 일치 | 제약조건 일치 | 상세      |
| --- | -------------- | --------- | --------- | ------------- | --------- |
| 1   | `che_농지개간` | PASS      | PASS      | PASS          | ...       |
| 2   | `che_모병`     | FAIL      | PASS      | PASS          | 차이: ... |

### 엔진 로직

| #   | 항목          | 상태      | 상세 |
| --- | ------------- | --------- | ---- |
| 1   | 턴 실행 순서  | PASS/FAIL | ...  |
| 2   | 제약조건 평가 | PASS/FAIL | ...  |
| 3   | 이벤트/트리거 | PASS/FAIL | ...  |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **미구현 커맨드** — 아직 구현되지 않은 커맨드는 로직 비교 대상에서 제외; verify-command-parity에서 추적
2. **의도적 개선** — 레거시 버그를 수정한 경우, 코드 주석에 `// DIFF: <사유>` 가 있으면 허용
3. **메시지 텍스트 차이** — 결과 메시지의 정확한 문구 차이는 로직 차이가 아님; 스탯 변화와 효과가 동일하면 PASS
4. **부동소수점 오차** — 계산 결과가 ±1 범위 내 차이인 경우 (Int 변환 시 반올림/절삭 차이)
5. **턴 실행 미구현** — 프로젝트 초기에 턴 엔진이 아직 없을 수 있음; 이 경우 Step 3을 SKIP으로 보고
6. **core2026와의 차이** — core2026 자체에 레거시와의 차이가 있을 수 있음; 이 경우 레거시 PHP를 우선 기준으로 함
