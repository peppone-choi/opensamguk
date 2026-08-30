# Imperial Succession Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 국가 군주와 분리된 황통·황제·섭정·후계자, 국가별 황실 관계 및 사망·폐위·선양 전이를 구현한다.

**Architecture:** `imperial_line`이 현재 제위 상태를, `imperial_transition`이 변경 이력을, `imperial_allegiance`가 국가별 인정·책봉·조공·적대 관계를 보유한다. 시나리오가 초기 황통과 역사 사건을 시드하고, 엔진의 사망 훅과 명시적 선양·폐위 명령이 하나의 `ImperialSuccessionService`를 호출한다. 표시 계층은 활성 황통 보유자를 조회해 `♛`와 황제 색을 파생하며 장수 이름이나 `npc_state`에 황제 상태를 영구 저장하지 않는다.

**Tech Stack:** Kotlin, Spring Data JDBC, PostgreSQL/Flyway, Jackson scenario JSON, JUnit 5, Vitest/TypeScript

**Spec:** `docs/design/imperial-succession.md`

## Global Constraints

- 기존 `officer_level=12` 국가 군주 승계와 `che_선양`의 의미는 유지한다.
- 장수 정본 이름은 개인명이며 `♛` 접두사를 DB에 저장하지 않는다.
- 역사 시나리오는 지정 사건 우선, 자유 진행은 지정 후계자·혈통 후보 순의 결정적 폴백을 사용한다.
- 한 세계에 복수 활성 황통을 허용한다.
- 일반 외교와 황실 관계를 분리하고, 국가는 황통마다 서로 다른 관계를 가질 수 있다.
- 황제 사망 시 제위 승계를 국가 군주 승계보다 먼저 처리한다.

---

### Task 1: 황통 도메인과 결정적 승계 규칙

**Files:**
- Create: `logic/src/main/kotlin/opensamguk/logic/imperial/ImperialSuccession.kt`
- Test: `logic/src/test/kotlin/opensamguk/logic/imperial/ImperialSuccessionTest.kt`

**Interfaces:**
- Produces: `ImperialLineState`, `ImperialCandidate`, `ImperialTransitionType`, `ImperialAllegianceRelation`, `ImperialRecognition`, `ImperialSuccession.chooseSuccessor(...)`

- [ ] **Step 1: Write failing tests** for scripted successor precedence, designated-heir fallback, dynastic-order fallback, dead-candidate skipping, and vacancy.
- [ ] **Step 2: Run** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests '*ImperialSuccessionTest'` and confirm missing-type failures.
- [ ] **Step 3: Implement** immutable domain types and a draw-free `chooseSuccessor` that returns the first living eligible candidate in the specified precedence.
- [ ] **Step 4: Run** the focused test and confirm all cases pass.
- [ ] **Step 5: Commit** domain and tests with `feat: add imperial succession domain rules`.

### Task 2: 세계별 황통 영속성

**Files:**
- Create: `infra/src/main/resources/db/migration/V48__imperial_succession.sql`
- Create: `infra/src/main/kotlin/opensamguk/infra/entity/ImperialLineEntity.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/entity/ImperialTransitionEntity.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/entity/ImperialAllegianceEntity.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/imperial/ImperialLineRepository.kt`
- Test: `infra/src/test/kotlin/opensamguk/infra/imperial/ImperialLineRepositoryIT.kt`

**Interfaces:**
- Consumes: Task 1 domain enums and state
- Produces: world-scoped current-state CRUD, append-only transition writes, and per-line/per-nation allegiance CRUD

- [ ] **Step 1: Write a failing repository IT** proving two worlds can reuse `later_han`, one world can hold three active lines, one holder cannot own two active lines, and one nation can recognize one line while rejecting another.
- [ ] **Step 2: Run** the focused infra IT and confirm the missing migration/repository failure.
- [ ] **Step 3: Add migration** with `world_id` foreign keys, `(world_id, code)` uniqueness, status checks, holder partial uniqueness, transition indexes, and JSONB meta defaults.
- [ ] **Step 4: Add entities/repository** using the project world-scoped Spring Data JDBC pattern.
- [ ] **Step 5: Run** the focused IT and `V2MigrationConventionTest`.
- [ ] **Step 6: Commit** with `feat: persist imperial lines and transitions`.

### Task 3: 시나리오 황통 시드

**Files:**
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt`
- Modify: `tools/scenario/apply_han_world.py`
- Modify: `tools/scenario/han_ownership.json`
- Modify: `infra/src/main/resources/scenario/scenario_1010.json`
- Modify: `infra/src/main/resources/scenario/scenario_1020.json`
- Modify: `infra/src/main/resources/scenario/scenario_1041.json`
- Modify: `infra/src/main/resources/scenario/scenario_1100.json`
- Test: `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioImporterIT.kt`
- Test: `tools/scenario/tests/test_apply_han_world.py`

**Interfaces:**
- Produces: root `imperialLines[]` and per-line `allegiances[]` scenario contracts resolved from personal/nation names to seeded IDs

- [ ] **Step 1: Replace the temporary `imperialGenerals` test** with failing `imperialLines` assertions for 1010, 1020, 1041, and 1100.
- [ ] **Step 2: Run** Python scenario tests and the focused importer IT; confirm schema/import failures.
- [ ] **Step 3: Implement JSON decoding/import** for line code, dynasty name, holder, designated heir, regent, court nation, legitimacy, dynastic candidates, and nation allegiances.
- [ ] **Step 4: Seed historical states** including 1010 `후한/유굉/유변/하진`, 1041 parallel 후한·원술 claims, and 1100 조위·촉한 lines.
- [ ] **Step 5: Remove fixed `npc_state=7` and stored `♛` name behavior** from `ScenarioJson`/`ScenarioImporter`; keep canonical personal names.
- [ ] **Step 6: Run** focused Python/JVM tests and commit with `feat: seed scenario imperial lines`.

### Task 4: 사망 시 제위 승계 연결

**Files:**
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/imperial/ImperialSuccessionService.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/imperial/ImperialSuccessionServiceTest.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/KillTombstoneTest.kt`

**Interfaces:**
- Consumes: Task 1 selector and Task 2 repositories
- Produces: `onGeneralDeath(generalId, date)`; atomically updates the line and appends `DEATH_SUCCESSION` or `VACANCY`

- [ ] **Step 1: Write failing tests** for 유굉→유변 with 하진 regency retained, no-candidate vacancy, and an emperor who is also ruler.
- [ ] **Step 2: Run** focused tests and confirm missing service/hook failures.
- [ ] **Step 3: Implement service** with a fresh living-candidate lookup immediately before transition and history log drafts.
- [ ] **Step 4: Add an `imperialDeath` hook** before `nextRuler` in `ReservedTurnHandler.kill` and wire it from `DaemonLoopConfig`.
- [ ] **Step 5: Run** service, tombstone, and ruler succession tests; verify imperial succession precedes ruler succession.
- [ ] **Step 6: Commit** with `feat: transfer imperial office on death`.

### Task 5: 제위 선양·폐위·왕조 창설

**Files:**
- Create: `logic/src/main/kotlin/opensamguk/logic/actions/imperial/ImperialTransitionCommand.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/imperial/ImperialTransitionHandler.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/imperial/ImperialTransitionHandlerTest.kt`

**Interfaces:**
- Produces: explicit `imperial_abdicate`, `imperial_depose`, `imperial_found`, and `imperial_allegiance_change` transitions; does not alter the legacy `che_선양`

- [ ] **Step 1: Write failing tests** for 유변→유협 deposition and 유협→조비 abdication that ends 후한, creates 조위, and records `산양공` in transition meta.
- [ ] **Step 2: Run** the focused tests and confirm missing handlers.
- [ ] **Step 3: Implement validation** requiring an active line, living target, authorized scripted event/admin actor, non-duplicate target line code, and legal allegiance transitions.
- [ ] **Step 4: Implement atomic transitions and logs** without automatically moving `officer_level=12`.
- [ ] **Step 5: Run** tests and commit with `feat: add imperial abdication and deposition transitions`.

### Task 6: 황제 표시·API 정책

**Files:**
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/read/GeneralReadRepository.kt`
- Modify: relevant game API DTO assembler returning general names
- Modify: `web/game/lib/utilGame/getNPCColor.ts`
- Modify: `web/game/app/game/chief-center/page.tsx`
- Test: `web/game/__tests__/utilGame.test.ts`
- Test: focused game-api controller test for emperor display

**Interfaces:**
- Produces: `isEmperor`, `imperialLineName`, display name `♛유굉`, gold presentation, and nation-facing allegiance/investiture DTOs derived from active line state

- [ ] **Step 1: Write failing API/web tests** proving the holder is marked, a deposed former holder is not, and a nation can display distinct relations to parallel lines.
- [ ] **Step 2: Run** focused API/Vitest tests and confirm missing derived fields.
- [ ] **Step 3: Join active imperial lines in the read model** and derive display fields without mutating canonical `general.name` or `npc_state`.
- [ ] **Step 4: Replace the temporary `npc_state==7` gold branch** with `isEmperor` presentation.
- [ ] **Step 5: Run** focused tests and commit with `feat: derive emperor presentation from imperial office`.

### Task 7: 통합 회귀 검증

**Files:**
- Modify: `docs/design/imperial-succession.md` only if verified behavior differs from the spec
- Modify: task report under `reports/opensamguk/tasks/`

**Interfaces:**
- Consumes: Tasks 1–6
- Produces: verified scenario/death/abdication/display system and report evidence

- [ ] **Step 1: Regenerate all scenario JSON** with `python3 tools/scenario/apply_han_world.py`.
- [ ] **Step 2: Run scenario tests** with `python3 -m unittest discover -s tools/scenario/tests`.
- [ ] **Step 3: Run JVM tests** with `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test :infra:test :app:game-engine:test :app:game-api:test`.
- [ ] **Step 4: Run web tests** from `web/game` with `pnpm test -- --run`.
- [ ] **Step 5: Verify scenario contracts**: every active line holder/heir/regent exists, no canonical name begins with `♛`, and all intended parallel lines are active.
- [ ] **Step 6: Record results, commits, verification, and remaining risks** in the task report.
