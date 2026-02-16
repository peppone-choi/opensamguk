---
name: verify-docs-parity
description: docs/architecture 문서의 의도대로 구현되었는지 확인합니다. 기술스택 차이(TS→Kotlin, Vue→Next.js 등)는 허용. 기능 구현 후 사용.
---

# Docs Parity 검증

## Purpose

`docs/architecture/` 문서에 기술된 설계 의도가 실제 구현에 반영되었는지 검증합니다:

1. **엔티티 모델** — `legacy-entities.md`에 정의된 엔티티 구조가 백엔드에 존재하는지 확인
2. **엔진 로직** — `legacy-engine*.md` 시리즈에 기술된 게임 엔진 동작이 구현되었는지 확인
3. **DB 스키마** — `postgres-schema.md`에 정의된 테이블 구조가 실제 migration과 일치하는지 확인
4. **런타임/데몬** — `runtime.md` + `turn-daemon-lifecycle.md`의 턴 처리 구조가 구현되었는지 확인
5. **프론트엔드 구조** — `game-frontend-spa-plan.md`의 Phase별 구현이 반영되었는지 확인

## When to Run

- 새로운 기능을 docs 문서 기반으로 구현한 후
- docs 문서를 수정하여 설계 의도를 변경한 후
- 마일스톤 점검 시 docs 대비 구현 진행률 확인
- PR 전 구현이 docs 의도와 일치하는지 확인

## Related Files

| File                                             | Purpose                |
| ------------------------------------------------ | ---------------------- |
| `docs/architecture/overview.md`                  | 아키텍처 전체 개요     |
| `docs/architecture/legacy-entities.md`           | 엔티티 모델/관계 문서  |
| `docs/architecture/legacy-commands.md`           | 커맨드 카탈로그 (93개) |
| `docs/architecture/legacy-engine.md`             | 엔진 전체 맵           |
| `docs/architecture/legacy-engine-execution.md`   | 턴 실행 흐름           |
| `docs/architecture/legacy-engine-war.md`         | 전투 엔진              |
| `docs/architecture/legacy-engine-economy.md`     | 경제 엔진              |
| `docs/architecture/legacy-engine-diplomacy.md`   | 외교 엔진              |
| `docs/architecture/legacy-engine-ai.md`          | NPC AI                 |
| `docs/architecture/legacy-engine-events.md`      | 이벤트 엔진            |
| `docs/architecture/legacy-engine-triggers.md`    | 트리거 시스템          |
| `docs/architecture/legacy-engine-constraints.md` | 제약조건 시스템        |
| `docs/architecture/legacy-engine-constants.md`   | 게임 상수/유닛         |
| `docs/architecture/legacy-engine-city.md`        | 도시 엔진              |
| `docs/architecture/legacy-engine-general.md`     | 장수 엔진              |
| `docs/architecture/legacy-engine-items.md`       | 아이템 시스템          |
| `docs/architecture/legacy-scenarios.md`          | 시나리오 시스템        |
| `docs/architecture/legacy-inherit-points.md`     | 유산 포인트            |
| `docs/architecture/postgres-schema.md`           | DB 스키마 제안         |
| `docs/architecture/runtime.md`                   | 런타임/데몬 구조       |
| `docs/architecture/turn-daemon-lifecycle.md`     | 턴 데몬 생명주기       |
| `docs/architecture/game-frontend-spa-plan.md`    | 프론트엔드 SPA 플랜    |
| `docs/architecture/rewrite-plan.md`              | 리라이트 계획          |
| `docs/architecture/rewrite-constraints.md`       | 리라이트 제약조건      |

## Workflow

### Step 1: 엔티티 모델 일치 확인

**파일:** `docs/architecture/legacy-entities.md`

**검사:** 문서에 정의된 핵심 엔티티가 백엔드에 존재하는지 확인합니다.

```bash
# docs에서 엔티티 목록 추출
grep -n "^##\|^###" docs/architecture/legacy-entities.md | head -30

# 백엔드 엔티티 목록
ls backend/src/main/kotlin/com/opensam/entity/ 2>/dev/null
```

**PASS:** docs에 정의된 핵심 엔티티가 백엔드에 존재
**FAIL:** docs에 정의되었으나 백엔드에 없는 엔티티 발견

### Step 2: DB 스키마 일치 확인

**파일:** `docs/architecture/postgres-schema.md`

**검사:** docs에 제안된 테이블 구조가 실제 migration에 반영되었는지 확인합니다.

```bash
# docs에서 테이블 목록 추출
grep -n "CREATE TABLE\|^##.*table\|^###.*table" docs/architecture/postgres-schema.md | head -20

# 실제 migration 테이블 목록
grep "CREATE TABLE" backend/src/main/resources/db/migration/*.sql 2>/dev/null
```

**PASS:** docs 스키마와 실제 migration이 일치 (컬럼 이름/타입 포함)
**FAIL:** 불일치 발견 — 차이점을 상세히 기록

### Step 3: 엔진 로직 문서 반영 확인

**검사:** 각 엔진 문서에 기술된 핵심 로직이 백엔드에 구현되었는지 확인합니다.

각 docs 파일에 대해:

1. 문서의 핵심 섹션(## 단위)을 읽음
2. 해당 기능에 대응하는 백엔드 코드가 존재하는지 검색

```bash
# 엔진 관련 백엔드 코드 검색
ls backend/src/main/kotlin/com/opensam/engine/ 2>/dev/null
grep -rn "class.*Engine\|class.*Service\|class.*Helper" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

검증 대상 docs:

| Doc 파일                     | 핵심 확인 사항                      |
| ---------------------------- | ----------------------------------- |
| `legacy-engine-execution.md` | 턴 루프, 장수별 실행, catch-up 로직 |
| `legacy-engine-war.md`       | 전투 유닛, 공격/방어, 피해 계산     |
| `legacy-engine-economy.md`   | 세수, 인구 증감, 자원 생산          |
| `legacy-engine-diplomacy.md` | 불가침, 선전포고, 종전              |
| `legacy-engine-ai.md`        | NPC AI 판단 로직, 정책 시스템       |
| `legacy-engine-events.md`    | 이벤트 발화, 핸들러                 |
| `legacy-engine-triggers.md`  | 트리거 조건, 효과                   |
| `legacy-engine-city.md`      | 도시 업데이트, 개발도, 인구         |
| `legacy-engine-general.md`   | 장수 상태 변화, 부상/사망           |
| `legacy-engine-items.md`     | 아이템 효과, 장착/해제              |

**PASS:** 문서에 기술된 핵심 로직에 대응하는 코드 존재
**FAIL:** 문서에 기술되었으나 구현되지 않은 로직 발견

### Step 4: 런타임/데몬 구조 확인

**파일:** `docs/architecture/runtime.md`, `docs/architecture/turn-daemon-lifecycle.md`

**검사:** 턴 데몬 생명주기와 API 서버 구조가 docs와 일치하는지 확인합니다.

```bash
# 턴 데몬 관련 코드 검색
grep -rn "@Scheduled\|TurnDaemon\|TurnRunner\|processTurn" backend/src/main/kotlin/com/opensam/ 2>/dev/null

# API 서버 구조 검색
grep -rn "@RestController\|@Controller\|@Service" backend/src/main/kotlin/com/opensam/ 2>/dev/null | head -20
```

핵심 확인 사항:

- 턴 데몬 상태 모델 (Idle → Running → Flushing → Idle)
- 데몬 제어 명령 (run/pause/resume/getStatus)
- API 서버와 데몬 간 통신 채널
- in-memory 상태 관리 및 DB flush

**PASS:** docs의 턴 데몬 구조가 구현에 반영됨
**FAIL:** 구조적 차이 발견

### Step 5: 프론트엔드 SPA plan 반영 확인

**파일:** `docs/architecture/game-frontend-spa-plan.md`

**검사:** SPA plan의 각 Phase가 구현에 반영되었는지 확인합니다.

```bash
# 프론트엔드 구조 확인
ls frontend/src/app/ 2>/dev/null
ls frontend/src/components/ 2>/dev/null
```

Phase별 확인:

- Phase 1: 프론트엔드 스켈레톤 (라우터, 상태관리, 에러처리)
- Phase 2: API 클라이언트 통합
- Phase 3: Public 화면
- Phase 4: Core Auth 화면
- Phase 5: Advanced 화면
- Phase 6: Hardening

**PASS:** 해당 Phase의 산출물이 프론트엔드에 존재
**FAIL:** Phase 산출물 누락

### Step 6: docs 커버리지 보고서

모든 docs 문서에 대한 구현 반영 상태를 종합합니다.

## Output Format

```markdown
## Docs Parity 검증 결과

### docs 문서별 구현 상태

| #   | 문서                         | 핵심 항목 | 구현됨 | 미구현 | 상태      |
| --- | ---------------------------- | --------- | ------ | ------ | --------- |
| 1   | `legacy-entities.md`         | N         | X      | Y      | PASS/FAIL |
| 2   | `postgres-schema.md`         | N         | X      | Y      | PASS/FAIL |
| 3   | `legacy-engine-execution.md` | N         | X      | Y      | PASS/FAIL |
| ... | ...                          | ...       | ...    | ...    | ...       |

### 미구현 항목 상세

| #   | 문서                   | 항목            | 설명                  | 우선순위 |
| --- | ---------------------- | --------------- | --------------------- | -------- |
| 1   | `legacy-engine-war.md` | 전투 공식       | 피해 계산 미구현      | 높음     |
| 2   | `legacy-engine-ai.md`  | NPC 정책 시스템 | 정책 입력 구조 미구현 | 중간     |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **기술스택 차이** — docs는 TypeScript/tRPC/Vue/Prisma 기반이지만 실제는 Kotlin/Spring Boot/Next.js/JPA; 기능적 동등성이 유지되면 PASS
2. **Gateway 모델 차이** — docs의 Gateway + Profile 모델 대신 World 엔티티로 대체 (CLAUDE.md Architecture Decisions 참조); 이는 의도적 설계 변경
3. **Redis 통신 → Spring 내장** — docs의 Redis Stream/pub-sub 대신 Spring 내장 메커니즘(@Scheduled 등) 사용은 허용
4. **docs의 Draft/Outline 섹션** — "(Draft)", "(Outline)" 표시된 섹션은 확정되지 않은 설계; 미구현이 FAIL이 아님
5. **docs의 TODO 항목** — `todo.md`에 나열된 미완성 문서 항목은 구현 누락이 아닌 문서 누락
6. **Deterministic RNG 구현체** — docs의 `LiteHashDRBG` 대신 동등한 결정적 RNG 사용은 허용; 시드 구성이 동일하면 PASS
7. **패키지 구조 차이** — docs의 monorepo(`packages/`, `app/`) 대신 `backend/`/`frontend/` 2-프로젝트 구조는 의도적 변경
