---
name: verify-type-parity
description: FE TypeScript 타입 ↔ BE Kotlin DTO/Entity 간 strict 타입 매칭을 검증합니다. DTO/타입 추가/수정 후 사용.
---

# Type Parity 검증

## Purpose

프론트엔드 TypeScript 타입과 백엔드 Kotlin DTO/Entity 간의 strict 1:1 타입 매칭을 검증합니다:

1. **Loose 타입 탐지** — `Record<string, unknown>`, `Record<string, any>`, `any`, `unknown`이 API 요청/응답 타입에 사용되는 곳을 탐지
2. **인라인 응답 타입** — `gameApi.ts`에서 `types/index.ts` 대신 인라인으로 정의된 응답/요청 타입 탐지
3. **인라인 DTO** — Controller 파일 내에 정의된 `data class`를 `dto/` 패키지로 이동해야 하는지 탐지
4. **누락된 TS 타입** — 백엔드 DTO/응답에 대응하는 TypeScript 인터페이스가 없는 경우 탐지
5. **필드 불일치** — FE 타입과 BE DTO/Entity 간 필드명/타입 불일치 탐지

## When to Run

- 백엔드 DTO 또는 Entity에 필드를 추가/수정한 후
- `frontend/src/types/index.ts`를 수정한 후
- `frontend/src/lib/gameApi.ts`에 새 API 메서드를 추가한 후
- Controller에 새 요청/응답 data class를 정의한 후
- 풀스택 기능 구현 완료 후 타입 안전성 점검

## Related Files

| File                                                  | Purpose                         |
| ----------------------------------------------------- | ------------------------------- |
| `frontend/src/types/index.ts`                         | 프론트엔드 공유 타입 정의       |
| `frontend/src/lib/gameApi.ts`                         | API 클라이언트 (응답 타입 사용) |
| `backend/src/main/kotlin/com/opensam/dto/*.kt`        | 백엔드 공유 DTO                 |
| `backend/src/main/kotlin/com/opensam/entity/*.kt`     | JPA 엔티티                      |
| `backend/src/main/kotlin/com/opensam/controller/*.kt` | 인라인 DTO 탐지 대상            |

## Workflow

### Step 1: Loose 타입 탐지

**파일:** `frontend/src/types/index.ts`, `frontend/src/lib/gameApi.ts`

**검사:** API 요청/응답에 `Record<string, unknown>`, `any`, `unknown`이 사용되는 곳을 찾습니다.

```bash
grep -n "Record<string, unknown>\|Record<string, any>\|: any\b\|: unknown\b" frontend/src/types/index.ts 2>/dev/null
grep -n "Record<string, unknown>\|Record<string, any>\|<any>\|<any\[" frontend/src/lib/gameApi.ts 2>/dev/null
```

허용되는 loose 타입 (면제):

- `General.meta`, `City.meta`, `Nation.meta` 등 JSON 컬럼의 `Record<string, unknown>` — 스키마가 유동적인 메타데이터 필드
- `Message.payload` — 메시지 유형별로 페이로드 구조가 다름 (향후 discriminated union으로 개선 가능)
- `GeneralTurn.arg`, `NationTurn.arg` — 커맨드별 인자 구조가 다름

**PASS:** 허용된 메타/payload/arg 외에 loose 타입 없음
**FAIL:** API 요청/응답의 핵심 필드에 loose 타입 사용

### Step 2: gameApi.ts 인라인 타입 탐지

**파일:** `frontend/src/lib/gameApi.ts`

**검사:** `api.get<...>()` / `api.post<...>()` 제네릭에서 `types/index.ts`의 타입 대신 인라인 객체 리터럴 타입을 사용하는 곳을 찾습니다.

```bash
# 인라인 타입 패턴: api.get<{ 또는 api.post<{
grep -n "api\.\(get\|post\|put\|delete\|patch\)<{" frontend/src/lib/gameApi.ts 2>/dev/null
```

각 발견에 대해:

- 인라인 타입 이름을 제안 (예: `{ points: number; buffs: ... }` → `InheritanceInfo`)
- `types/index.ts`에 인터페이스로 정의할 것을 권장

**PASS:** 모든 API 메서드가 `types/index.ts`의 명명된 타입을 참조
**FAIL:** 인라인 객체 리터럴 타입 사용

### Step 3: Controller 인라인 DTO 탐지

**파일:** `backend/src/main/kotlin/com/opensam/controller/*.kt`

**검사:** Controller 파일 내에 `data class`가 정의되어 있는지 찾습니다.

```bash
grep -rn "^data class\|^  data class" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
```

각 발견에 대해:

- 해당 data class가 `dto/` 패키지의 적절한 파일로 이동되어야 하는지 확인
- Controller 전용 요청 DTO (예: `SendMessageRequest`)는 Controller에 남아도 허용하되, 응답 DTO는 공유 가능성이 높으므로 `dto/`로 이동 권장

**PASS:** Controller에 인라인 data class 없음 (모두 dto/ 패키지에 위치)
**FAIL:** Controller에 data class 발견 — dto/로 이동 필요

### Step 4: 백엔드 DTO ↔ 프론트엔드 타입 1:1 매칭

**파일:** `backend/src/main/kotlin/com/opensam/dto/*.kt`, `frontend/src/types/index.ts`

**검사:** 백엔드 dto/ 패키지의 모든 data class에 대응하는 프론트엔드 TypeScript 타입이 존재하는지 확인합니다.

```bash
# 백엔드 DTO 클래스 목록
grep -rn "^data class" backend/src/main/kotlin/com/opensam/dto/ 2>/dev/null

# 프론트엔드 타입 목록
grep -n "^export interface\|^export type" frontend/src/types/index.ts 2>/dev/null
```

각 DTO에 대해:

- 응답 DTO → 프론트엔드에 대응 인터페이스 필요
- 요청 DTO → 프론트엔드에서 해당 구조로 요청을 보내는지 확인
- 내부 전용 DTO (Service 간 전달) → 프론트엔드 타입 불필요

**PASS:** 모든 응답 DTO에 대응하는 프론트엔드 타입 존재
**FAIL:** 프론트엔드 타입이 누락된 응답 DTO 발견

### Step 5: 핵심 엔티티 필드 매칭

**파일:** `backend/src/main/kotlin/com/opensam/entity/General.kt`, `frontend/src/types/index.ts`

**검사:** General/City/Nation/Troop/Diplomacy 등 핵심 엔티티의 직렬화 대상 필드가 프론트엔드 타입에 모두 존재하는지 확인합니다.

```bash
# General 엔티티 필드 추출
grep -n "var " backend/src/main/kotlin/com/opensam/entity/General.kt 2>/dev/null

# TS General 인터페이스 필드 추출
grep -A 80 "export interface General " frontend/src/types/index.ts 2>/dev/null | head -80
```

**핵심 매칭 대상:**

- General: 5-stat, crew/crewType/train/atmos, gold/rice, npc, officerLevel
- City: population, agriculture, commerce, security, def, wall, level, region
- Nation: gold, rice, tech, level, bill, rate, warState
- Troop: nationId, leaderGeneralId, name
- Diplomacy: srcNationId, destNationId, type, state

**PASS:** 핵심 필드가 양쪽에 모두 존재하고 이름 일치
**FAIL:** 필드 누락 또는 이름 불일치

### Step 6: 날짜/시간 직렬화 호환성

**검사:** 백엔드의 `OffsetDateTime`/`LocalDateTime` 필드가 프론트엔드에서 `string`으로 처리되는지 확인합니다.

```bash
# 백엔드 날짜 필드
grep -rn "OffsetDateTime\|LocalDateTime\|Instant" backend/src/main/kotlin/com/opensam/entity/ 2>/dev/null

# 프론트엔드에서 대응 필드의 타입
grep -n "createdAt\|updatedAt\|sentAt\|turnTime\|commandEndTime\|startedAt" frontend/src/types/index.ts 2>/dev/null
```

**PASS:** 날짜 필드가 프론트엔드에서 `string` 또는 `string | null`로 선언
**FAIL:** 날짜 필드가 `number`로 선언되거나 누락

## Output Format

```markdown
## Type Parity 검증 결과

### 요약

| 카테고리                   | 상태      | 이슈 수 |
| -------------------------- | --------- | ------- |
| Loose 타입 (FE)            | PASS/FAIL | N       |
| 인라인 응답 타입 (gameApi) | PASS/FAIL | N       |
| 인라인 DTO (Controller)    | PASS/FAIL | N       |
| 누락된 TS 타입             | PASS/FAIL | N       |
| 핵심 엔티티 필드 매칭      | PASS/FAIL | N       |
| 날짜 직렬화 호환           | PASS/FAIL | N       |

### 상세

| #   | 카테고리 | 파일:라인              | 문제                               | 수정 방법                      |
| --- | -------- | ---------------------- | ---------------------------------- | ------------------------------ |
| 1   | Loose    | `types/index.ts:22`    | WorldState.config: Record<unknown> | WorldConfig 인터페이스 정의    |
| 2   | Inline   | `gameApi.ts:342`       | tournamentApi 인라인 응답 타입     | TournamentInfo를 types/에 정의 |
| 3   | DTO 위치 | `VoteController.kt:45` | CreateVoteRequest 인라인 DTO       | dto/VoteDtos.kt로 이동         |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **메타데이터 필드의 loose 타입** — `meta`, `payload`, `penalty`, `config` 등 JSON 컬럼은 스키마가 유동적이므로 `Record<string, unknown>` 허용. 단, 구조가 알려진 경우 (예: `FrontInfoResponse`) strict 타입으로 전환 권장
2. **커맨드 인자의 loose 타입** — `GeneralTurn.arg`, `NationTurn.arg`는 커맨드별로 구조가 다르므로 `Record<string, unknown>` 허용 (향후 discriminated union 권장)
3. **Admin API의 `any` 타입** — Admin 페이지는 내부 도구이므로 loose 타입 허용. 단, eslint-disable 주석이 있어야 함
4. **Kotlin Short/Int/Long → TypeScript number** — JavaScript에는 integer 타입이 없으므로 Kotlin의 Short/Int/Long이 모두 TS `number`로 매핑되는 것은 정상
5. **Controller 전용 요청 DTO** — 단일 엔드포인트에서만 사용되는 작은 요청 DTO (1-3 필드)는 Controller 파일에 남아도 허용
6. **프론트엔드 추가 필드** — TS 타입에 백엔드에 없는 computed/display 필드 (예: `nationName`, `power`)가 있는 것은 허용
7. **FrontInfoDtos의 전용 타입** — `FrontInfoResponse` 내부의 sub-DTO들은 이미 `dto/FrontInfoDtos.kt`에 정리되어 있으므로 정상
