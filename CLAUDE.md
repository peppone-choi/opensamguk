# 오픈삼국 (OpenSamguk)

Web-based Three Kingdoms strategy game.

## Tech Stack

- Backend: Spring Boot 3 (Kotlin)
- Frontend: Next.js 15
- Database: PostgreSQL 16
- Cache: Redis 7

## Project Structure

- `backend/` - Spring Boot backend
- `frontend/` - Next.js frontend

## Image CDN

Base URL: https://storage.hided.net/gitea/devsam/image/raw/branch/main/

## Commands

### Backend

```bash
cd backend && ./gradlew :gateway-app:bootRun
# game instance
cd backend && ./gradlew :game-app:bootRun
```

### Frontend

```bash
cd frontend && pnpm dev
```

### Docker (DB services)

```bash
docker-compose up -d
```

## Parity Target

- **패러티 대상: 레거시 PHP (`legacy/` 폴더 참조)**
- 장수 스탯: **5-stat 시스템** (통솔/무력/지력/정치/매력) - 삼국지14 기준
  - `leadership`, `strength`, `intel`, `politics`, `charm` (all Int 0-100)

## Officer Rank System

관직은 국가 레벨에 따라 결정됨 (`officer_ranks.json` 참조):

| 국가 레벨  | 칭호        | 문관 예시    | 무관 예시         |
| ---------- | ----------- | ------------ | ----------------- |
| 7 (황제)   | 승상/태위   | 사도, 사공   | 대도독, 표기장군  |
| 6 (왕)     | 태상/광록훈 | 위위, 태복   | 정동/남/서/북장군 |
| 5 (공)     | 정위/대홍려 | 종저, 대사농 | 진동/남/서/북장군 |
| 0 (주자사) | 기본        | 종사좨주     | 호위장군, 비장군  |

특수 국가: 황건(천공장군 체계)

## Architecture Decisions

- **Multi-Process**: Split into `gateway-app` + versioned `game-app` JVMs
- **World = Profile**: `World` entity replaces core2026's Profile/Gateway model
- **Logical Isolation**: Game entities use `world_id` FK (no schema-per-profile)
- **Version Pinning**: `world_state.commit_sha` + `world_state.game_version` pin each world to a game build
- **Turn Engine**: Runs inside `game-app` per-version JVM and processes attached worlds
- **Field Naming**: Follow core2026 conventions (`intel` not `intelligence`, `crew`/`crewType`/`train`/`atmos`)

## Reference

- **Legacy PHP source**: `legacy/` (parity target)
- **Core2026 docs**: `docs/` (extracted from core2026)
- Image CDN: `https://storage.hided.net/gitea/devsam/image/raw/branch/main/`

### Docs Index (MUST read before implementing any feature)

| Domain                             | Doc File (`docs/architecture/`) |
| ---------------------------------- | ------------------------------- |
| Commands (80+ general, 30+ nation) | `legacy-commands.md`            |
| Entity model & relationships       | `legacy-entities.md`            |
| Battle/War                         | `legacy-engine-war.md`          |
| Turn execution                     | `legacy-engine-execution.md`    |
| Economy                            | `legacy-engine-economy.md`      |
| Diplomacy                          | `legacy-engine-diplomacy.md`    |
| NPC/AI                             | `legacy-engine-ai.md`           |
| Events                             | `legacy-engine-events.md`       |
| General entity                     | `legacy-engine-general.md`      |
| Items                              | `legacy-engine-items.md`        |
| City                               | `legacy-engine-city.md`         |
| Triggers                           | `legacy-engine-triggers.md`     |
| Constraints                        | `legacy-engine-constraints.md`  |
| Constants/unit sets                | `legacy-engine-constants.md`    |
| Scenarios                          | `legacy-scenarios.md`           |
| Inheritance points                 | `legacy-inherit-points.md`      |
| DB schema                          | `postgres-schema.md`            |
| Frontend SPA plan                  | `game-frontend-spa-plan.md`     |
| Runtime/daemon                     | `runtime.md`                    |

### Legacy PHP (`legacy/`)

| File                 | Content                         |
| -------------------- | ------------------------------- |
| `Scenario.php`       | 시나리오 초기화, 국가/장수 생성 |
| `GeneralBuilder.php` | 장수 빌더, 스탯, NPC, 특기      |
| `Nation.php`         | 국가 빌더, 수도/도시 관리       |
| `CityConstBase.php`  | 100+ 도시 상수, 지역/레벨       |
| `scenario/*.json`    | 시나리오 24종                   |
| `scenario/map/*.php` | 맵 8종                          |

## Game Data

- `backend/game-app/src/main/resources/data/game_const.json` - 도시 레벨/지역 매핑, 초기값
- `backend/game-app/src/main/resources/data/officer_ranks.json` - 관직 체계
- `backend/game-app/src/main/resources/data/maps/` - 맵 데이터 (che, miniche, cr 등 8종)

## Skills

- `verify-implementation` - 구현 검증 + 패러티 체크 (docs 대비)
- `build-and-test` - 백엔드/프론트엔드 빌드 실행
- `add-backend-endpoint` - 백엔드 엔드포인트 추가 가이드 (docs 참조 필수)
- `add-frontend-page` - 프론트엔드 페이지 추가 가이드 (SPA plan 참조 필수)
- `legacy-compare` - 레거시 vs 현재 구현 비교 (docs + legacy PHP 전체 참조)
- `reference-docs` - 기능별 docs/legacy 참조 파일 조회
- `manage-skills` - 스킬 관리 및 업데이트
- `verify-entity-parity` - Entity/Type/Schema 정합성, 5-stat, 필드 네이밍 검증
- `verify-command-parity` - 레거시 PHP 93개 커맨드(55 장수 + 38 국가) 구현 상태 추적
- `verify-resource-parity` - 레거시 게임 리소스(시나리오, 맵, 도시, 관직, 병종) 존재 확인
- `verify-logic-parity` - core2026/레거시 대비 게임 로직 동일 결과 확인
- `verify-game-tests` - 백엔드 게임 엔진 테스트 존재/통과 확인
- `verify-frontend-parity` - 레거시/SPA plan 대비 프론트엔드 페이지, 출력 정보, UI 확인
- `verify-docs-parity` - docs/architecture 문서 의도 대비 구현 반영 확인
- `verify-daemon-parity` - NPC AI + 턴 데몬 레거시/docs 대비 동작 확인
- `verify-npc-data` - 시나리오 NPC 장수 삼국지14 기준 5-stat(통무지정매) 최신화 확인
- `verify-architecture` - 백엔드 TDD/DDD/클린-레이어드 아키텍처, 레포지토리 패턴 준수 검증
- `verify-api-parity` - 풀스택 1:1 메서드-레벨 패러티 (Controller-Service-Repository 체인, FE API-BE 엔드포인트 매칭, 데드 코드, 타입 호환성)
- `verify-type-parity` - FE TypeScript 타입 ↔ BE Kotlin DTO/Entity strict 타입 매칭 (loose 타입, 인라인 DTO, 필드 불일치 탐지)
- `find-skills` - 스킬 검색/설치 도우미
- `frontend-design` - 프로덕션급 프론트엔드 UI 생성
- `vercel-react-best-practices` - React/Next.js 성능 최적화 가이드라인
- `vercel-composition-patterns` - React 컴포지션 패턴 (compound components, render props)
- `vercel-react-native-skills` - React Native/Expo 모범 사례
- `web-design-guidelines` - Web UI 접근성/디자인 감사

### Skill Usage Guidelines

프론트엔드 작업 시 다음 스킬을 적극 활용:

- **React/Next.js 코드 작성/리뷰 시** → `vercel-react-best-practices` 참조
- **컴포넌트 설계/리팩터링 시** → `vercel-composition-patterns` 참조
- **UI 페이지/컴포넌트 생성 시** → `frontend-design` 사용
- **UI 접근성/디자인 리뷰 시** → `web-design-guidelines` 사용

---

## Development Guidelines (Karpathy Method)

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria require constant clarification.
