---
name: verify-entity-parity
description: Entity/Type/Schema 정합성과 필드 네이밍 규칙을 검증합니다. 엔티티 추가/수정 후 사용.
---

# Entity Parity 검증

## Purpose

백엔드 Entity, 프론트엔드 TypeScript 타입, DB 스키마 간 일관성을 검증합니다:

1. **5-stat 시스템** — General의 leadership, strength, intel, politics, charm 필드가 모든 레이어에 존재하고 올바른 이름 사용
2. **필드 네이밍 규칙** — `intel`(not `intelligence`), `crew`/`crewType`/`train`/`atmos` 등 core2026 규칙 준수
3. **world_id FK** — User/World 제외 모든 게임 엔티티에 world_id FK 존재
4. **Entity ↔ DB 스키마 정합** — JPA 엔티티 필드가 SQL migration 컬럼과 일치
5. **Entity ↔ TypeScript 타입 정합** — 프론트엔드 타입이 백엔드 엔티티 핵심 필드를 포함

## When to Run

- 백엔드 Entity 클래스를 추가하거나 수정한 후
- DB migration SQL을 추가한 후
- 프론트엔드 `types/index.ts`를 수정한 후
- 새로운 게임 엔티티를 도입한 후
- 레거시 PHP에서 엔티티를 포팅한 후

## Related Files

| File                                                            | Purpose                                |
| --------------------------------------------------------------- | -------------------------------------- |
| `backend/src/main/kotlin/com/opensam/entity/General.kt`         | 장수 엔티티 (5-stat, crew/train/atmos) |
| `backend/src/main/kotlin/com/opensam/entity/City.kt`            | 도시 엔티티                            |
| `backend/src/main/kotlin/com/opensam/entity/Nation.kt`          | 국가 엔티티                            |
| `backend/src/main/kotlin/com/opensam/entity/WorldState.kt`      | 월드 상태 엔티티                       |
| `backend/src/main/kotlin/com/opensam/entity/AppUser.kt`         | 유저 엔티티                            |
| `backend/src/main/kotlin/com/opensam/entity/Troop.kt`           | 부대 엔티티                            |
| `backend/src/main/kotlin/com/opensam/entity/Diplomacy.kt`       | 외교 엔티티                            |
| `backend/src/main/kotlin/com/opensam/entity/Message.kt`         | 메시지 엔티티                          |
| `backend/src/main/kotlin/com/opensam/entity/Event.kt`           | 게임 이벤트 엔티티                     |
| `backend/src/main/kotlin/com/opensam/entity/GeneralTurn.kt`     | 장수 턴 엔티티                         |
| `backend/src/main/kotlin/com/opensam/entity/NationTurn.kt`      | 국가 턴 엔티티                         |
| `backend/src/main/kotlin/com/opensam/entity/NationFlag.kt`      | 국가 플래그 엔티티                     |
| `backend/src/main/resources/db/migration/V1__core_tables.sql`   | 초기 DB 스키마 (핵심 테이블)           |
| `backend/src/main/resources/db/migration/V2__add_user_role.sql` | 유저 역할 추가                         |
| `frontend/src/types/index.ts`                                   | 프론트엔드 타입 정의                   |

## Workflow

### Step 1: 5-stat 시스템 검증

**파일:** `backend/src/main/kotlin/com/opensam/entity/General.kt`

**검사:** General 엔티티에 정확히 5개의 스탯 필드가 존재하는지 확인합니다.

```bash
grep -n "var leadership\|var strength\|var intel\|var politics\|var charm" backend/src/main/kotlin/com/opensam/entity/General.kt
```

**PASS:** 5개 필드 모두 존재 (leadership, strength, intel, politics, charm)
**FAIL:** 누락된 필드가 있거나, 잘못된 이름 사용 (intelligence, charisma 등)

**추가 검사:** 금지된 이름이 사용되지 않는지 확인합니다.

```bash
grep -rn "intelligence\|charisma\b" backend/src/main/kotlin/com/opensam/entity/ frontend/src/types/
```

**PASS:** 결과 없음
**FAIL:** 금지된 필드 이름 발견 — `intel`/`charm`으로 변경 필요

### Step 2: 필드 네이밍 규칙 검증

**파일:** 모든 entity/\*.kt, types/index.ts

**검사:** core2026 필드 네이밍 규칙을 따르는지 확인합니다.

```bash
grep -rn "soldiers\|unitType\|training\b\|morale\b" backend/src/main/kotlin/com/opensam/entity/ frontend/src/types/
```

**PASS:** 결과 없음 (crew/crewType/train/atmos 사용)
**FAIL:** 금지된 필드 이름 발견

**올바른 필드명 확인:**

```bash
grep -n "var crew\b\|var crewType\|var train\b\|var atmos\b" backend/src/main/kotlin/com/opensam/entity/General.kt
```

**PASS:** 4개 필드 모두 존재
**FAIL:** crew/crewType/train/atmos 중 누락

### Step 3: world_id FK 검증

**검사:** AppUser와 WorldState를 제외한 모든 게임 엔티티에 world_id FK가 있는지 확인합니다.

대상 엔티티: Nation, City, General, Troop, Diplomacy, Message, Event, GeneralTurn, NationTurn, NationFlag

```bash
for f in Nation City General Troop Diplomacy Message Event GeneralTurn NationTurn NationFlag; do
  if grep -q 'var world\b\|var worldState\b' "backend/src/main/kotlin/com/opensam/entity/${f}.kt"; then
    echo "PASS: ${f} has world FK"
  else
    echo "FAIL: ${f} missing world FK"
  fi
done
```

**PASS:** 모든 대상 엔티티에 world FK 존재
**FAIL:** world FK가 누락된 엔티티 발견 — `@ManyToOne` world 관계 추가 필요

### Step 4: Entity ↔ DB 스키마 정합 검증

**파일:** entity/_.kt, db/migration/_.sql

**검사:** 각 엔티티의 @Table 이름이 SQL에 존재하는지 확인합니다.

```bash
# 엔티티에서 @Table 이름을 추출하여 migration과 대조
grep -rn "@Table" backend/src/main/kotlin/com/opensam/entity/ 2>/dev/null

# migration 파일의 CREATE TABLE 목록
grep -rn "CREATE TABLE" backend/src/main/resources/db/migration/ 2>/dev/null
```

각 엔티티의 @Table 이름이 migration에 존재하는지 교차 확인합니다.

**PASS:** 모든 테이블이 migration에 존재
**FAIL:** 엔티티의 @Table 이름에 대응하는 CREATE TABLE이 없음

**추가 검사:** General 엔티티의 5-stat 컬럼이 SQL에도 존재하는지 확인합니다.

```bash
grep -n "leadership\|strength\|intel\b\|politics\|charm" backend/src/main/resources/db/migration/V1__core_tables.sql
```

**PASS:** 5개 컬럼 모두 존재
**FAIL:** SQL에 누락된 스탯 컬럼

### Step 5: Entity ↔ TypeScript 타입 정합 검증

**파일:** `frontend/src/types/index.ts`

**검사:** 프론트엔드 TypeScript 타입이 백엔드 핵심 엔티티와 대응하는지 확인합니다.

```bash
grep -n "^export interface" frontend/src/types/index.ts
```

**PASS:** 최소한 다음 타입이 존재: User, World, Nation, City, General, Troop, Battle, Diplomacy, Message, GameLog
**FAIL:** 백엔드 엔티티에 대응하는 TypeScript 타입 누락

**General 타입 5-stat 검사:**

```bash
grep -A 5 "leadership\|strength\|intel\|politics\|charm" frontend/src/types/index.ts
```

**PASS:** General 인터페이스에 5개 스탯 필드 존재
**FAIL:** TypeScript General 타입에 스탯 필드 누락

**General 타입 crew/train/atmos 검사:**

```bash
grep -n "crew:\|crewType:\|train:\|atmos:" frontend/src/types/index.ts
```

**PASS:** 4개 필드 존재
**FAIL:** TypeScript General 타입에 군사 필드 누락

## Output Format

```markdown
## Entity Parity 검증 결과

| #   | 검사                | 상태      | 상세 |
| --- | ------------------- | --------- | ---- |
| 1   | 5-stat 시스템       | PASS/FAIL | ...  |
| 2   | 필드 네이밍 규칙    | PASS/FAIL | ...  |
| 3   | world_id FK         | PASS/FAIL | ...  |
| 4   | Entity ↔ DB 스키마  | PASS/FAIL | ...  |
| 5   | Entity ↔ TypeScript | PASS/FAIL | ...  |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **User 엔티티에 world_id 없음** — User는 글로벌 엔티티이므로 world FK가 불필요
2. **World 엔티티에 world_id 없음** — World 자체가 최상위 엔티티
3. **TypeScript 타입에 추가 필드** — 프론트엔드에만 있는 computed/display 필드 (예: `nationName`, `cityName`, `power`)는 백엔드 엔티티와 1:1 매칭 불필요
4. **@Column name 명시** — JPA의 기본 camelCase→snake_case 변환이 충분하지 않은 경우 (예: `def_val` for `def`) 명시적 @Column name은 정상
5. **Enum 타입 차이** — Kotlin enum과 TypeScript string union은 값이 동일하면 정합으로 간주 (예: `DiplomacyType` enum vs `"NON_AGGRESSION" | "ALLIANCE" | "CEASEFIRE"`)
