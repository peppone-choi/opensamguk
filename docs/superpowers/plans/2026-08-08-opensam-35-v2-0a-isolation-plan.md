# OPENSAM-35 — V2-0A production 격리 게이트 실행 계획

- Status: **ADOPTED · S0~S6 구현 완료 · final CodeRabbit 8 dispositions terminal dirty-tree review `cleared` · post-final-8 remote exact-commit CI pending**.
  `ASSET_PREFIX=/game` Compose remediation, its contract test, and the `agent-system` CI invocation are implemented.
  PR head `70492bcc` green CI included the original contract step, while the current final-8 permission/active-matcher/docs
  remediation remains remote-unobserved. The Round 3 and terminal final-8 dirty-tree re-reviews are `cleared` evidence;
  PR merge/release/deploy 및 OPENSAM-177 consumer 실행은 미수행이다.
- 작성: 2026-08-08
- 티켓: OPENSAM-35 (Highest, `할 일`), 부모 에픽 OPENSAM-16
- 정본: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §7.1·§7.1-2·§7.2·§11 ·
  `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:74-81` ·
  `.ai/decisions.md` ADR-LITE-021
- 소비자: OPENSAM-150(R1, 0A-c location에 `v2_city_ledger` 마이그레이션) · OPENSAM-151(R2, 시나리오 env 분리 전제)

---

## 0. 착수 전 확정 사실 (read-only 조사 결과)

### 0.1 이 티켓은 "격리"가 아니라 "게이트 선설치"다

**구현 착수 전** 리포 전역 grep 결과 v2 런타임 코드가 **0건**이었다. `V2_ENABLED`·`v2-lab`·`v2-sandbox`·`v2_city_ledger`는
전부 `docs/loops/v2-planning-2026-07-12/**` 기획 문서에만 존재하고 `app/`·`common/`·`logic/`·`infra/`
어디에도 실행 코드가 없다. 코드에서 잡히는 "V2"는 전부 무관하다 —
PHP 원본 스키마 세대 표기(`GeneralTurnReadRepository.kt:22`, `NationTurnRowMapper.kt:9-10`),
baseline 스키마 버전 문자열(`CqrsBaselineMain.kt:33`), devsam PHP 버전 표기(`GlobalMenuController.kt:15`).

따라서 0A-a~g 7항목 중 **"제거"로 읽히는 0A-e는 baseline에 뺄 대상이 없었다.** 실질은
"v2가 앞으로도 production으로 새지 않음을 강제하는 가드 + DoD 고정"이다. 현재 브랜치에는
그 0A gate-only 구현(`V2SandboxGate`, 두 configuration, content catalog, `v2-lab` route/middleware)만
있고 v2 product leaf·schema·persistence는 없다. 이 해석을 계획의 전제로 삼는다.

### 0.2 티켓 본문의 path:line 인용 5건이 부정확 (내용은 유효)

| 티켓 인용 | 실제 | 판정 |
|---|---|---|
| `docker-compose.yml:172` SCENARIO_CODE | **`:177`** (172는 `GAME_ENGINE_PORT`) | 행번호 오류 |
| `docker-compose.yml:173` SCENARIO_DIR | **`:178`** (173은 `OPENSAMGUK_WORLD_ID`) | 행번호 오류 |
| `ScenarioJson.kt:69` 기본값 false | **`:78` + `boolOf` `:293-298` + data class `:323`** | 행번호 오류, 주장은 참 |
| `ScenarioJson.kt:299` | 빈 줄 | 무관 |
| `ScenarioImporter insertEvents :806` | **`:887`** (defaults 분기 `:888-899`) | 행번호 오류, 주장은 참 |

정확했던 근거: `docker-compose.production.yml:67`·`:68` / `EventStore.DEFAULT_EVENTS` 정확히 12행
(`EventStore.kt:157-251`) / `WorldIdConfig.kt:11` / `ScenarioSeedCoordinator.kt:46-48` `error(...)` /
`StreamKeys.kt:16-34`.

**티켓이 경고한 "조용한 실패" 메커니즘 자체는 실재한다** — v2가 `SCENARIO_CODE`/`SCENARIO_DIR`을
물려받으면 `ignoreDefaultEvents=false` → `ScenarioImporter.kt:888` defaults 분기 → v1 기본 이벤트
12행 적재 + v2 leaf 0행. 부팅·시드·헬스체크는 전부 성공한다.

### 0.3 U12(핵심 미지) — 확인됨: 선례 0

- game-api `app/game-api/src/main/resources/application.yml:14` = `locations: classpath:db/migration`
- game-engine `app/game-engine/src/main/resources/application.yml:14` = 동일
- compose 3종 · `.github/workflows/**` · `scripts/**` · `tools/**` · Dockerfile 어디에도
  `SPRING_FLYWAY_LOCATIONS` 오버라이드 **선례 없음**. 이 문자열은 `docs/**` 계획 문서에만 등장.

→ **티켓 지시대로 U12 실측이 착수 첫 작업**이며, 그 결과가 0A-c 설계를 가른다.

### 0.4 리포 최초 도입이 되는 것 (선례 0)

| 항목 | 현황 | 영향 |
|---|---|---|
| `@ConditionalOnProperty` 등 조건부 빈 게이팅 | `app/`·`common/`·`logic/`·`infra/` 전역 **0건** | 0A-b가 최초 도입. 기존 조건부는 `ObjectProvider.getIfAvailable()`뿐(`TurnDaemonRunner.kt:53-58`) |
| `getBeansOfType`/`getBeanNamesForType` 실측 assertion | 테스트 전역 **0건** | 0A-f가 최초. 부팅 뼈대는 `GameEngineApplicationTests`/`EmptyWorldBootIT`(@SpringBootTest+Testcontainers) 재사용 가능 |
| `content/` 루트 디렉터리 | **없음** | 0A-d 신설. read-only classpath 로더 선례는 `ScenarioCatalogService.kt:15`, env 오버라이드 선례는 `SCENARIO_DIR` |
| `v2-lab` 라우트 네임스페이스 | **없음** | 0A-a 신설. `web/game/middleware.ts` 존재하나 env 조건부 404 선례는 미확인 |
| v2 전용 Flyway location 디렉터리 | **없음** (현재 최고 버전 V38) | 0A-c 신설 |

### 0.5 기존 아키텍처 테스트 패턴 (0A-f 참고)

전부 **정적 스캔**이며 Spring 컨텍스트를 띄우지 않는다 —
`DaemonNoEntityManagerTest`(클래스파일 상수풀 스캔, SSoT는 `flush/DaemonWriteGuard.kt`),
`HotColdWorldCatalogGuardTest`(소스 텍스트 정규식 파싱),
`WorldScopedReadRepositoryArchitectureTest`, `WorldScopedSideReadArchitectureTest`.

0A-f는 ADR-LITE-021 (iii)이 "선언이 아니라 **실측**"을 요구하므로 이 패턴으로는 불충분하다.
**부팅 컨텍스트 + 빈 카운트 assertion**이라는 새 조합이 필요하다.

---

## 1. 문서 미명시 3건 — 계획이 제안하고 승인받아야 할 것

`01-backbone-micro.md:3`이 밝히듯 이 phase는 **phase 공유 Exit만 존재하고 개별 티켓 Exit는 문서 미명시**다.
아래 3건은 어느 정본에도 없다. 추측으로 메우지 않고 제안 + 승인 대상으로 올린다.

| # | 미명시 항목 | 제안 |
|---|---|---|
| M1 | 0A-a~g **개별 수용 기준** | §3 각 단계의 "판정" 열을 그대로 개별 AC로 채택 |
| M2 | Exit "**404**"의 판정 명령 | `web/game` 라우트 테스트 + 실행 중 production-shape 스택에 `/game/v2-lab/` 요청 → 404 관측. 두 층 모두 요구 |
| M3 | 0A-g **artifact 저장 위치 규약** | `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/` 아래 4종 저장 + `MANIFEST.md`에 sha256 기록. 대용량 덤프는 gitignore하고 해시만 커밋 |

---

## 2. 충돌 2건 — **2026-08-08 사용자 결정 완료**

### C1. `SCENARIO_SEED_ENABLED` 값 충돌

- `docs/agent/coding-rules.md:12` — **production compose는 `SCENARIO_SEED_ENABLED=false`**가
  `tools/agent-system/check.py` strict 불변식(CI 강제).
- proposal §7.1 (i) (`round3…:1107`) — "**양 스택 모두 `SCENARIO_SEED_ENABLED=true`로 뜬다**".

v2 스택을 `docker-compose.production.yml`에 추가하면서 `true`를 주면 strict check가 깨진다.
**결정 (2026-08-08 사용자): (a) 별도 스택 파일로 분리.**

- `docker-compose.production.yml` — **무수정**, `SCENARIO_SEED_ENABLED=false` 불변식 유지
- `docker-compose.v2-sandbox.yml` — **신규 파일**, `SCENARIO_SEED_ENABLED=true`
- `tools/agent-system/check.py` — **수정 0** (불변식을 약화하지 않는다)

신규 파일이므로 게이트 ⑤의 `--diff-filter=MD`에도 걸리지 않는다.

### C2. 0A-e "s1 profile"의 정의체가 이 리포에 없다

- compose 3종에 `profiles:` 키 **0건**. `s1`은 compose profile이 아니라 **배포 대상 게임 서버 인스턴스**다.
- 서버 정의(`servers/<id>.env`의 `IMAGE_TAG` 핀)는 sibling `opensamguk-docker` 리포 소관
  (`docs/agent/architecture.md:48`).

**결정 (2026-08-08 사용자): (a) 이 리포로 한정.**

0A-e의 범위는 이 리포의 `docker-compose.production.yml`까지다. sibling `opensamguk-docker`의
`servers/<id>.env` 서버 정의 몫은 **별도 티켓으로 분리**하며 이 티켓에서 손대지 않는다
(sibling은 별도 ownership·별도 PR·별도 승인 체계).

→ sibling 리포용 연결 consumer 티켓 [OPENSAM-177](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-177)로
분리했다. shared account/JWT/profile live integration과 sibling 변경·release·deploy는 이 티켓에서
수행하지 않았고 완료로 주장하지 않는다.

---

## 3. 실행 단계

각 단계는 독립 커밋 1개. 앞 단계 판정이 green이어야 다음 단계로 간다.

### S0. U12 실측 (착수 첫 작업, 코드 변경 0)

티켓·ADR-LITE-021 (ii)가 명시한 선행 작업.

- 임시 v2 마이그레이션 디렉터리를 **워킹 트리 밖**(scratchpad)에 만들고,
  `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:<tmp>` 로 game-engine을
  Testcontainers Postgres에 띄운다.
- **판정**: `flyway_schema_history`에 임시 v2 마이그레이션 행이 기록되면 PASS.
- PASS → 0A-c는 env 오버라이드 경로(게이트 ⑤ 유지, `application.yml` 무수정).
- FAIL → 대체 경로 택1: (a) `application-v2.yml` **신규 추가**(기존 파일 무수정 ⇒ 게이트 ⑤ 유지),
  (b) v2 `@Configuration`이 자기 `Flyway` 빈 생성 + `migrate()`. **둘 다 T2 편집 0.**
- 산출: `docs/loops/opensam-35-v2-0a-2026-08-08/u12-flyway-locations-measurement.md`

#### S0 실측 결과 (2026-08-08) — **historical PASS; S1 classpath pair로 대체됨**

방법 A(진짜 OS 환경변수, `env -i`로 셸 격리 + `java -jar`)로 측정. 리포 추적 파일 변경 0
(격리 worktree 사용 후 제거), 잔여 컨테이너 0.

| Run | `SPRING_FLYWAY_LOCATIONS` | `flyway_schema_history` | 부팅 |
|---|---|---|---|
| A | `classpath:db/migration,filesystem:<v2mig>` | V1~V38 + V900 = **39행 전부 `success=t`** (V26·V38 `.kt` JDBC 포함) | 정상 |
| B | `filesystem:<v2mig>` 단독 | **V900 1행뿐** | **실패** — JPA `ddl-auto: validate`가 `missing table [banned_member]` 검출 |

**확정 사실 — 오버라이드는 "추가"가 아니라 "치환"이다.** 리스트 프로퍼티가 병합되지 않고 통째로
교체되므로 v1 location을 env 값에 **명시적으로 포함해야만** V1~V38이 유지된다. 누락은
조용히 통과하지 않고 **fail-closed로 부팅을 깨뜨린다**(Run B 실증) — 이는 방어에 유리한 성질이다.

→ **0A-c는 env 오버라이드 경로로 확정.** `application.yml` 무수정, 게이트 ⑤ 유지.

**Historical boundary:** S0의 `filesystem:<v2mig>`는 env 치환 semantics를 측정한 probe일 뿐이다. S1은
이후 jar classpath sibling pair **`classpath:db/migration,classpath:db/migration_v2`**를 채택했고
`filesystem:` 운영 경로를 abandoned했다. 따라서 container filesystem mount는 더 이상 이 티켓의 운영
UNKNOWN이 아니다.

**미측정(정직하게 남김)**: ① game-api에서의 동작 ② v1 프로세스에서 v2 미적용(= S1 판정 항목)
③ 같은 DB 공유 시 히스토리 충돌 ④ v2 버전 번호 정책 (V900은 충돌 회피용 임의 프로브값이지 정책 결정이 아니다).

### S1. 0A-c v2 Flyway location 분리

S0 결과에 따라 **env 오버라이드 경로로 확정**. v2 전용 location 디렉터리 신설(신규 파일이므로
게이트 ⑤의 `--diff-filter=MD`에 걸리지 않는다).

S0가 만든 구속 조건 2개:

1. **치환 semantics** — v2 스택의 `SPRING_FLYWAY_LOCATIONS`는 v1 location을 **반드시 포함**해야
   한다(`classpath:db/migration,<v2>`). 누락 시 v1 스키마가 통째로 빠지지만 fail-closed로
   부팅이 깨지므로 조용히 통과하지는 않는다.
2. **재귀 스캔 위험(S1에서 실측)** — Flyway는 location을 재귀 탐색한다. v2 location을
   `classpath:db/migration/v2`처럼 **v1 location의 하위**에 두면 v1 프로세스가 v2 마이그레이션까지
   집어삼켜 0A-c의 목적과 정반대가 된다. 따라서 v2 location은 **형제 경로**여야 한다.
   S1의 첫 작업은 이 재귀 동작을 실측해 디렉터리 이름을 확정하는 것이다 — 추측 금지.

- **판정**: v2 프로세스에서 v2 마이그레이션이 적용되고, **v1 프로세스에서는 적용되지 않음**을
  두 컨텍스트 각각의 `flyway_schema_history`로 실측.
- **미측정 이월(S0)**: 컨테이너 배포에서 `filesystem:` 경로 도달 가능성. `classpath:` 형제 경로가
  jar에 함께 구워지므로 더 단순하나, 이 역시 S1에서 실측해 택한다.

#### S1 실측 결과 (2026-08-08) — **완료**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s1-flyway-location-measurement.md` (5 run, 격리
worktree + `env -i` + `java -jar`, 프로브 3종은 측정 후 소멸).

| 미지 | 판정 |
|---|---|
| U1 재귀 스캔 | **재귀한다.** `classpath:db/migration` 단독인 v1이 하위 `db/migration/v2/V901`을 적용(`success=t`). **하위 경로 금지.** |
| U2 형제 격리 | **양방향 정확.** v1 = 38행·프로브 테이블 0개 / v2 = 40행(v1 38 + 프로브 2), V26·V38 JDBC 유실 없음. |
| U3 classpath 도달성 | `BOOT-INF/lib/infra-*.jar` 안에 `db/migration_v2/` 실재 확인. 마운트·베이크·Dockerfile 수정 불필요 ⇒ **classpath 채택**, S0의 `filesystem:` 미측정 항목 **소멸**. |
| U4 히스토리 | **`public.flyway_schema_history` 단일 공유.** 버전 순서대로 v1 행 사이에 끼어 정렬. |

**확정: v2 location = `classpath:db/migration_v2`** (리포 경로
`infra/src/main/resources/db/migration_v2/`, README에 규약 고정). v2 스택 env는 **반드시**
`SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2`.

**U4 버전 번호 정책 — P1 잠정 확정 (S6 리뷰에서 확인받는다).**
DB 분리(S5가 이미 `GAME_DATABASE_URL` 명시 주입으로 요구) + v2는 **V900 대역**. 근거:
낮은 버전을 기존 DB에 붙이면 `Validate failed`로 **fail-closed**(DB 무변경)이나 높은 버전은
안전하게 append 됨을 실측. P2(V39~ 이어붙이기)는 v1이 V39에 도달하는 순간 같은 버전 두 개가 되고,
P3(`spring.flyway.table` 분리)는 **미측정**이라 근거가 없다. 추가 비용 0인 P1을 택한다.

**⚠️ S1이 발견한 silent 경로 (U4-d) — S5의 DB 분리가 더 강한 요구가 된다.**
v2 행이 있는 DB에 **v1 스택이 붙으면 WARN만 남기고 정상 부팅한다**
(`Schema "public" has a version (902) that is newer than the latest available migration (38) !`,
`ERROR` 0건). 즉 Flyway는 v2→v1 방향으로 게이트가 아니다. v2 스키마가 production DB로 새면
부팅이 잡아주지 못하므로 **`GAME_DATABASE_URL` 분리가 유일한 1차 방어선**이다.

### S2. 0A-b `V2_ENABLED` + `v2-sandbox` 동시 조건 빈 게이트

리포 최초의 `@ConditionalOnProperty` 도입. v2 빈은 **두 조건이 모두 참일 때만** 등록.

- **판정**: 조건 미충족 컨텍스트에서 v2 빈 0개(S4의 테스트가 실측), 충족 컨텍스트에서 등록됨.

#### S2 실측 결과 (2026-08-08) — **완료**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s2-conditional-bean-gate.md`.
독립 재실행 확인: `BUILD SUCCESSFUL`, engine `tests="6"` + api `tests="5"`, 양쪽 `failures="0" errors="0"`.

| `V2_ENABLED`(`v2.enabled`) | 프로파일 `v2-sandbox` | v2 빈 |
|---|---|---|
| 미설정 | 미활성 | **0** |
| `true` | 미활성 | **0** |
| 미설정 | 활성 | **0** |
| `false` | 활성 | **0** |
| `true` | 활성 | **1** |

**비공허성 검증(뮤테이션 프로브 2회).** "전부 PASS"가 조건이 실제로 닫혀서인지 테스트가 아무것도
재지 않아서인지를 갈랐다 — `@Profile` 제거 시 `property only`가 `expected: <0> but was: <1>`로 실패,
`@ConditionalOnProperty` 제거 시 2케이스 실패. **두 조건이 각각 독립적으로 게이트를 닫고 있음**이
실측됐다. 프로브는 복원 확인됨.

**산출물(전부 신규 파일, 기존 파일 수정 0건)** — `infra/.../v2/V2SandboxGate.kt`(이름 정본 상수 +
마커 타입 `V2SandboxMarker`) · `app/game-engine/.../v2/V2SandboxConfiguration.kt` ·
`app/game-api/.../v2/V2SandboxConfiguration.kt` + 각 테스트.

**확정된 규약 2가지 (이후 모든 v2 작업이 따른다):**
1. **v2 빈은 전부 각 앱의 `V2SandboxConfiguration` 안 `@Bean`으로 들어온다.** 게이트 밖에
   `@Component`로 v2 빈을 만들면 0A-b 위반.
2. **조건부 `@Configuration`은 각 앱의 컴포넌트 스캔 루트 안에 있어야 한다.** infra의
   `@Configuration`은 스캔되지 않고 앱 쪽 명시 `@Import`가 필요한데(실측: `SideReadRepositoryConfiguration`
   ↔ `SideReadWorldScopeConfiguration`, `META-INF/spring/**` 0건), 그것은 T2 기존 파일 수정 = 제약 위반이다.
   그래서 game-engine·game-api **양쪽**에 두고(round3 proposal `:1164`가 양쪽 v2 빈을 명시),
   이름 상수만 infra에 공유한다(철자가 갈리면 한쪽 게이트가 조용히 열리므로).

**백엔드 ↔ 프론트 값 해석 비대칭 (의도된 것).** 두 계층이 같은 env `V2_ENABLED`를 다르게 읽는다.
백엔드 `@ConditionalOnProperty(havingValue = "true")`는 `equalsIgnoreCase` 비교라 `TRUE`·`True`·`tRuE`에도
빈이 등록되고(실측: `V2SandboxConfigurationTest`의 `property value is matched case-insensitively`),
프론트 `web/game/middleware.ts`는 `=== 'true'` strict라 그 값들에 404를 낸다. 프론트를 느슨하게 맞추지
**않는다** — 백엔드에는 `@Profile("v2-sandbox")`가 2차 조건으로 함께 걸려 production에서 `V2_ENABLED=TRUE`
오타 하나로는 열리지 않지만, 프론트에는 2차 조건이 없어 느슨하게 맞추는 쪽이 실제 노출을 만든다.
즉 비대칭의 근거는 **프론트에 2차 조건이 부재**하다는 것이고, 양쪽 동작은 각각
`V2SandboxConfigurationTest`와 `web/game/__tests__/v2-lab-route.test.tsx`가 테스트로 고정한다.

`application.yml` 무수정, `application-v2-sandbox.yml` **미생성**(프로파일 전용 설정값이 아직 없어 불필요).
`matchIfMissing = false`는 기본값이지만 **명시**했다 — 게이트 방향이 load-bearing이라 코드에 드러낸다.

### S3. 0A-d `content/v2/` read-only catalog loader + 0A-a `/game/v2-lab/` 라우트 네임스페이스

- 0A-d: `ScenarioCatalogService.kt:15`의 `PathMatchingResourcePatternResolver` read-only 패턴 재사용.
  **classpath scan·startup seed 금지**를 코드와 테스트로 강제.
- 0A-a: `web/game/app/game/v2-lab/` 네임스페이스로 제한 + `V2_ENABLED` 미설정 시 404.
- **판정(M2)**: 라우트 테스트 + production-shape 스택 실요청 404.

#### S3-a 실측 결과 (2026-08-08) — **완료**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s3a-content-v2-loader.md`.
`infra` `tests="7"` + engine `V2ContentCatalogBeanTest tests="3"` + S2 회귀 `tests="6"`, 전부
`failures="0" errors="0"`.

**위치 = `infra/src/main/resources/content/v2/`** (클래스패스 루트 기준 `content/v2/`).
리포 루트 `content/v2/`는 S1 U3와 같은 이유로 기각(컨테이너 경로 실재성 부담). jar 도달 확인:
`infra` jar와 `BOOT-INF/lib/infra-*.jar` 양쪽에서 `content/v2/README.md` 실재.

**S1 교훈의 방향이 여기서는 반대였다.** 이 로더는 스스로 재귀하지 않으므로(`**` 없음) "v2가 v1을
삼키는" 위험이 없다. 남는 위험은 그 반대 — v1 `ScenarioCatalogService`가
`classpath*:scenario/scenario_*.json`을 스캔하므로 v2 콘텐츠를 `scenario/` 하위에 두면 **v1 시나리오
목록에 섞인다.** 그래서 형제 경로 `content/`가 맞다.

**"scan·seed 금지"를 4겹으로 강제했다** — ① 코드 형태(`@Component` 없음, `ApplicationRunner`/
`CommandLineRunner` 미구현, 쓰기 메서드 부재, 패턴에 `**` 없음) ② 함정 픽스처 실측(재귀 `nested/deep.json`
미포착, 형제 `content/v2-decoy/` 미포착, 비-JSON 미포착, 경로 탈출 `null`) ③ **클래스파일 상수풀 스캔**으로
DB 쓰기 타입·startup runner 타입 참조 0건 판정(기존 `DaemonNoEntityManagerTest` 패턴 재사용) ④ 게이트가
열린 컨텍스트에서도 `ApplicationRunner`/`CommandLineRunner` 빈 수 0 assert.

**비공허성 프로브 2회** — 패턴을 재귀로 바꾸면 2케이스 실패, `CommandLineRunner`를 구현하게 하면
infra 2 + engine 1 실패. 재귀 금지와 seed 금지가 각각 독립적으로 실측되고 있다. (P-2 최초 실행에서
engine이 이전 infra jar로 돌아 `failures="0"`이 나온 순서 아티팩트를 발견하고 단독 `--rerun-tasks`로
재실행해 `failures="1"` 확인 — 문서에 재실행 결과를 실었다.)

**S4에 넘기는 요구:** 실측 조회 타입에 `V2SandboxMarker`뿐 아니라 **`V2ContentCatalog`도 포함**할 것.
로더는 game-engine에만 등록했다(game-api에 v2 콘텐츠 소비자가 0건이라 등록 근거 없음 — 게이트 자체는
S2가 양쪽에 설치済).

#### S3-b 중간 발견 (2026-08-08) — **soft 404 결함 + middleware 승인**

`/game/v2-lab`에서 `notFound()`를 호출해도 **HTTP status 200이 나간다.** `app/game/layout.tsx:13`이
`AuthGate`(client component)를 렌더해 `/game/**` 서브트리가 클라이언트 경계 안에서 스트리밍되고,
HTML 셸이 flush된 **뒤에** `notFound()`가 해소되기 때문이다. 바디에
`{"digest":"NEXT_HTTP_ERROR_FALLBACK;404"}`가 실려 브라우저는 404를 그리지만 **v2 콘텐츠가 RSC
페이로드에 그대로 나간다** — 계획이 금지한 "리다이렉트·빈 페이지" 부류와 같은 soft 404다.
대조군: `/game` 밖 동일 코드는 404. 라우트 그룹·중첩 위치를 바꿔도 `app/game/layout.tsx`는
`app/game/**` 전체에 적용되므로 **컴포넌트 레벨로는 이 경계를 벗어날 수 없다.**

⇒ 렌더 이전에 도는 유일한 층인 `web/game/middleware.ts` 수정을 **승인**했다(백엔드 T2 공집합 밖,
파일 1개, 게이트 early-return만 추가, 기존 서버선택 로직 무수정).
`app/game/v2-lab/layout.tsx`의 `notFound()`는 심층방어로 유지.

**승인 시 붙인 조건 — 우회 구멍.** 같은 파일의 경로 기반 serverId rewrite 분기 때문에
`/game/<SERVER_ID>/v2-lab`은 `pathname.startsWith('/game/v2-lab/')`에 걸리지 않고 rewrite를 거쳐
그대로 렌더된다. early-return을 앞에 두면 이 경로가 새고 뒤에 두면 `?server=` 분기가 먼저 return해
또 샌다. **원시 pathname이 아니라 실효 경로로 판정**하고 `/game/<serverId>/v2-lab` 실요청 404를
관측할 것을 요구했다. 함께 요구: `process.env.V2_ENABLED`가 middleware에서 **런타임에 읽히는지**
(빌드 타임 인라인이면 같은 이미지로 v1/v2 스택을 나눌 수 없어 S5 설계에 직접 영향).

#### S3-b stage 결과 (2026-08-08) — **프론트 층 충족, M2는 부분 충족**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s3b-v2lab-route.md`.
이 stage transcript는 `pnpm typecheck` 오류 0 · `pnpm test` **54 files / 284 tests**를 기록한다.
이는 이후 테스트가 더 추가되기 전의 단계 기록이며 final record가 아니다. A4 XML/log의
post-remediation historical 값은 `v2-lab route` 17 tests, `middleware` 8 tests, 합계 **54 files / 288 tests**다.
The historical A4 value is not reused as current frontend acceptance. A later direct-pnpm frontend typecheck is
green and Vitest JSON reports 132 suites / 288 tests / 0 failures. The current dirty-tree backend gate then ran
once with Java 21 `--rerun-tasks` (601 suites / 5,050 tests / failures·errors 0). Round 3 P2 source remediation
now adds the v2 Compose `ASSET_PREFIX=/game` contract, its red-before/green-after regression test, and the
`agent-system` CI invocation of that test. The original contract step was observed in green CI at `70492bcc`; the
current final-8 permission/active-matcher/docs remediation is locally validated only. The terminal independent final-8
dirty-tree re-review is `cleared` with no findings. Post-final-8 remote CI for an exact commit remains pending before
any separately authorized release action.

**우회 구멍 폐쇄 실측.** 동일 빌드(`BUILD_ID=V75C2XFKp2JmjIY6b1Qt2`), 양 run `SERVER_ID=pep`:

| 경로 | `V2_ENABLED` 미설정 | `V2_ENABLED=true` |
|---|---|---|
| `/game/v2-lab` | **404** | 200(렌더 확인) |
| **`/game/pep/v2-lab`** (rewrite 우회) | **404** | 200 |
| `/game/pep/rankings` (rewrite 생존 대조군) | 200 | 200 |
| `/game/rankings` (정상 라우트 대조군) | 200 | 200 |

`/game/pep/rankings` 200이 **rewrite 분기가 실제로 살아있다는 대조군**이다 — 즉 `/game/pep/v2-lab`의
404는 "serverId가 안 맞아 라우트가 없어서"가 아니라 게이트가 잡은 것이다. RUN A에서 v2 콘텐츠
유출 0건(`v2 실험 네임스페이스` 문자열 0회; soft 404에서는 페이로드에 실려 나갔다).

**`V2_ENABLED`는 런타임에 읽힌다 — 빌드 타임 인라인 아님.** 두 run이 `V2_ENABLED` 미설정 상태에서
만든 **동일 빌드 산출물**을 쓰는데 RUN B가 200 + 실제 렌더를 냈다. ⇒ **같은 이미지로 env만 바꿔
v1/v2 스택을 나눌 수 있다.** 게이트가 배포 구조를 제약하지 않는다(S5 설계 제약 해소).

**404 바디는 0바이트 = 브라우저 흰 화면.** M2 판정 기준은 status이므로 범위를 넓히지 않았다.
사용자 대면 404 페이지가 필요하면 별도 티켓.

**⚠️ M2 미충족 잔여 — S5/S6의 구속 요구로 승격한다.**
§4.1 측정은 **Next 프론트 단독(`next start`)**이다. 그런데 `web/game/next.config.*:5`가
`output: 'standalone'`이고 `next start`는 그 모드에서 동작하지 않는다는 경고를 낸다 —
**실제 배포는 `node .next/standalone/server.js`로 뜬다.** middleware가 두 서버 모두에서 라우팅
앞단에 도는 것은 맞으나 **standalone 서버에서 위 404 표를 재현하지 않았다.**
따라서 M2("production-shape 스택 실요청 404")는 현재 **프론트 층 부분 충족**이다.
**S5 이후 standalone + nginx 경유로 최소 `/game/v2-lab`·`/game/pep/v2-lab` 두 경로의 404를
재관측해야 하며, 이것 없이는 0A-a를 종결로 보지 않는다.**

### S4. 0A-f production context v2 빈 0개 **실측** 테스트

`@SpringBootTest` + Testcontainers로 v1 프로세스 컨텍스트를 실제로 띄우고
`getBeansOfType(...)`로 v2 빈 수 0을 assert. 정적 스캔으로 대체하지 않는다(ADR-LITE-021 (iii)).

- **판정**: XML `failures="0" errors="0"`. Docker 없으면 skip(≠fail)이나, **본 티켓 종결에는 실행 필수**.

#### S4 실측 결과 (2026-08-08) — **완료**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s4-production-context-bean-gate.md`.
`@SpringBootTest` + Testcontainers PostgreSQL로 **두 앱 × 4칸 = 8개 컨텍스트를 실제 부팅**.
8칸 전부 `tests="1" skipped="0" failures="0" errors="0"` — **`skipped="0"`이 Docker 실행 증거**다
(skip은 증거가 아니라는 요구 충족). 같은 실행에서 S2·S3-a 회귀도 green.

| 컨텍스트 | engine `Marker`/`Catalog`/패키지 | api `Marker`/`Catalog`/패키지 |
|---|---|---|
| production shape | 0 / 0 / **{}** | 0 / 0 / **{}** |
| `v2.enabled=true`만 | 0 / 0 / **{}** | 0 / 0 / **{}** |
| 프로파일만 | 0 / 0 / **{}** | 0 / 0 / **{}** |
| 둘 다 (**양성 대조군**) | 1 / 1 / 3개 | 1 / **0**(의도) / 2개 |

game-api ④의 `V2ContentCatalog` 0은 결함이 아니라 S3-a의 결정(소비자 0건)이며, 테스트가
"게이트가 열려도 여기엔 없다"를 명시적으로 고정한다. **양성 대조군이 ①~③의 0을 "게이트가 닫혀서
0"으로 확정**한다 — 없으면 컨텍스트가 안 떠도 통과한다.

**타입 하드코딩에 의존하지 않는 2차 방어 — 가능했고 넣었다.**
`beanDefinitionNames` + `getType(name, allowFactoryBeanInit = false)`(빈을 만들지 않고 타입만 해석)로
`opensamguk.*.v2.*` 패키지 출신 빈이 0개임을 assert한다. 탐지력 근거: ④에서 이 스캔이
`V2SandboxConfiguration` **자신**까지 잡는다 — 설정 클래스는 하드코딩 목록의 조회 대상이 아니므로
스캔이 타입 목록보다 넓게 본다는 증거다. 오탐 위험 확인: 리포에서 `.v2.` 패키지는 S2·S3-a가 만든
것뿐이고 v1 코드에는 없다(직접 재확인). **한계(정직하게):** 패키지 명명 규약에 의존하므로 누가 v2 빈을
v2가 아닌 패키지에 만들면 못 잡는다 — 그 방어는 S2 규약 1과 리뷰의 몫이다.

**비공허성 프로브 3회.** P-1(engine `@Profile` 제거) → ② 1칸 실패 `expected: <0> but was: <1>`.
P-2(api `@ConditionalOnProperty` 제거) → ③ 1칸 실패. **P-3(게이트 밖 `@Component`를 `engine.v2`에
추가 — 타입 목록에 없는 새 타입)** → ①②③ **3칸 실패**, 메시지에
`v2ProbeLeakedComponent=opensamguk.engine.v2.V2ProbeLeakedComponent`.
P-3이 핵심이다 — 하드코딩 타입 3종은 전부 0이었는데도 실패했으므로 그 실패는 **패키지 스캔이 만든
것**이고, 2차 방어가 공허하지 않음이 증명된다. 프로브 3종 전부 복원 확인(`diff` 무출력 / `git status` 무흔적).

기존 IT 관례를 그대로 승계했다 — `@Testcontainers(disabledWithoutDocker = true)`,
`webEnvironment = MOCK`(임베디드 서버 미기동 ⇒ 포트 점유 0, 병렬 세션과 충돌 없음), 랜덤 포트,
`tasks.test`에 이미 배선된 Docker 설정. 새로 발명한 설정 0건. 잔여 컨테이너 0.

### S5. 0A-e production 격리 + v2 스택 env 분리 (ADR-LITE-021 (i))

C1/C2 결정에 따름. v2 스택은 v1과 **다른 값**으로 아래 6개를 명시 주입 —
`GAME_DATABASE_URL` · `OPENSAMGUK_WORLD_ID` · `SCENARIO_CODE` · `SCENARIO_DIR` ·
`V2_ENABLED` · `SPRING_PROFILES_ACTIVE`.

`SCENARIO_CODE`/`SCENARIO_DIR`을 반드시 명시하는 이유는 §0.2의 조용한 실패다.
(`SPRING_PROFILES_ACTIVE`는 현재 어느 compose/워크플로에서도 주입되지 않으므로 이 역시 신규다.)

- **판정**: v2 스택 부팅 후 v2 전용 probe 이벤트 행이 존재하고 v1 기본 12행이 **적재되지 않음**을
  DB로 실측. 실제 v2 leaf 행은 OPENSAM-150의 필수 수용 기준으로 이관한다(ADR-LITE-029).

#### S5 실측 결과 (2026-08-08) — **판정 충족**

증거: `docs/loops/opensam-35-v2-0a-2026-08-08/s5-v2-stack-env-separation.md`.
최종 산출물은 `docker-compose.v2-sandbox.yml`, `v2-sandbox.env.example`,
`infra/nginx/shared-gateway-relay.conf.template`이다. 기존 production/local compose·`check.py`·
`application.yml`은 전부 무수정이라 C1을 유지한다. relay만 shared gateway network에 붙고
v2 web/nginx/game 서비스는 v2 network에만 남는다.

**DB 실측 — 동일 `game-engine` 이미지 1개를 두 스택이 공유하고 차이는 env뿐이다.**

| 항목 | v1 스택 | v2 스택 | 판정 |
|---|---|---|---|
| `current_database()` | `sammo` | `sammo_v2` | DB 분리 ✅ |
| `world_state.id` | `1` | `9001` | world_id 분리 ✅ |
| **`v1_default_rows`** | **12** | **0** | **핵심 방어 — v2는 v1 기본 12행을 적재하지 않는다** ✅ |
| `probe_event_rows` | 0 | 2 | v2는 자기 시나리오 이벤트만 적재 |
| `destroy_nation`(v1 고유) | 1 | 0 | v1 이벤트가 v2로 새지 않음 |
| `event` 총행수 | 90 (12+1+77) | 79 (0+2+77) | 산술 일치 |

`v1_default_rows` 지문은 `EventStore.kt:157-251`의 `DEFAULT_EVENTS`와 1:1로 맞춘 쿼리다.
양쪽 공통 `Month/1000 = 77`(deferred 장수 등장)이 같다는 사실이 **차이가 이벤트 병합 분기에서만
나왔음**을 보인다.

**수용 기준 정정(2026-08-08 사용자 승인, ADR-LITE-029).** 이 티켓은 격리 게이트 선설치이고
OPENSAM-150의 실제 v2 스키마·leaf는 명시적 비범위다. 따라서 존재하지 않는 v2 leaf를 만들어
통과시키지 않고, v2 스택 전용 probe 이벤트 2행이 적재되면서 v1 기본 12행은 0임을 현재 판정으로
삼는다. 실제 v2 leaf 행 존재는 OPENSAM-150의 필수 수용 기준이며 그 티켓에서 같은 DB 쿼리를
실제 schema/content로 재측정한다.

**v1 회귀 없음** — 같은 이미지·현재 브랜치 소스(v2 신규 파일 전부 포함)로 v1 스택 `healthy`,
`SPRING_FLYWAY_LOCATIONS` 미설정 → 기본값 유지, `flyway_schema_history` 38행, `ERROR` 0건,
U4-d의 "newer version" WARN 0건, 시드 결과 v2 도입 전과 동일.

**최종 설계 규약 — game world 값만 `V2_`-접두 치환 변수.** v1과 같은 호스트·같은 `.env`에서
나란히 뜨므로 world DB·world id·scenario가 v1 값을 조용히 상속하지 않게 한다. 반면 ADR-LITE-023에
따라 계정·JWT issuer·profile writer는 분리하지 않는다. 최종 compose는 local gateway-api를 제거하고,
필수 external network/profile volume과 shared `JWT_SECRET`으로 기존 v1 gateway 하나를 재사용한다.
따라서 v2 Flyway location은 game-api·game-engine에만 주입되며 gateway에는 절대 주입되지 않는다.
초기 local-gateway 측정과 위 문단의 종전 "크리덴셜 3종 분리" 설계는 이 보정으로 superseded됐다.

**부수 발견(후속 티켓 구속 조건):** v2 시나리오 코드도 `scenario_<숫자>` 정규형이어야 한다
(`ScenarioSeedRunner.kt:150` — `scenario_s5v2probe`가 부팅을 깨뜨렸다). **OPENSAM-151(R2)이 이 제약을 받는다.**

#### M2 잔여 폐쇄 (2026-08-08) — **충족**

S3-b가 남긴 standalone 구속 요구를 S5가 닫았다. `docker inspect` → `[node server.js]` =
`.next/standalone/server.js` 확인(‌`next start` 경고 소멸), `printenv`로 컨테이너 안
`V2_ENABLED`/`SERVER_ID` 도달 확인(S3-b UNKNOWN 2도 함께 폐쇄), 프로파일 활성 로그
`The following 1 profile is active: "v2-sandbox"`.

| 경로 | v1 스택(nginx, `V2_ENABLED` 미설정) | v2 스택(nginx, `V2_ENABLED=true`) |
|---|---|---|
| `/game/v2-lab` | **404** | **200**(렌더 확인) |
| `/game/pep/v2-lab` (v1 rewrite 우회) | **404** | 404(라우트 부재) |
| `/game/v2s/v2-lab` (v2 rewrite 우회) | 404(라우트 부재) | **200**(렌더 확인) |
| `/game/pep/rankings` (**v1 rewrite 생존 대조군**) | **200** | 404 |
| `/game/v2s/rankings` (**v2 rewrite 생존 대조군**) | 404 | **200** |

nginx 없이 standalone 포트 직접 호출도 동일 ⇒ **nginx가 상태코드를 바꾸지 않는다.**
콘텐츠 유출 0건(`v2 실험 네임스페이스` 출현 0회, 404 바디 0바이트).
⇒ **M2("production-shape 스택 실요청 404") 충족, 0A-a 잔여 폐쇄.**

**컨테이너 안 v2 빈 0/1은 재측정하지 않았다** — `/actuator/beans`가 미노출이고 노출하려면
`application.yml` 수정 = 게이트 ⑤ 위반이다. 빈 게이트는 S4가 실측했고 S5는 **게이트를 여는 두 env가
컨테이너에 실제로 도달함**까지만 확인했다.

정리: 컨테이너·볼륨·네트워크·측정용 이미지 전부 0, 프로브 시나리오는 스크래치패드에만 존재하고
리포에 흔적 0건, 비표준 포트만 사용.

### S6. 0A-g 기준선 artifact + 게이트 + 리뷰

- artifact 4종(v1 schema dump · seed hash · PHP golden inventory · backend/web gate)을 M3 규약대로 저장.
  A3은 T1/parity diff 및 golden inventory의 **scope/inventory proof**다. PHP capture 또는
  draw-for-draw replay가 실행·통과했다는 claim이 아니며 A4 backend gate를 대체하지 않는다.
  이 isolation/build-only ticket은 T1/parity code를 바꾸지 않았으므로 replay가 acceptance가 아니다.
  후속 T1/parity 변경 ticket은 별도의 PHP capture/replay를 수행해야 한다.
- 게이트 ①~③·⑤ 전량 실행. **diff 게이트는 아래 §4-1의 정본 명령만 쓴다** —
  wildcard pathspec과 기준선 두 가지 결함이 실제로 있었다(§4-1).
  - ① `tools/parity/gate.sh backend` — 출력 tail + XML로 판정(exit code 금지)
  - ② T1 diff 0 — 빈 출력
  - ③ T2 diff = 티켓 본문 사전 선언 목록(= 공집합)과 **정확히 일치**(초과 = 위반)
  - ⑤ 설정 리소스 무수정 — 빈 출력
- `web/game` `pnpm typecheck && pnpm test`.
- PR #370 Round 1의 23개 disposition 및 source remediation은 dirty-tree independent review에서
  `cleared`되었다(no findings; fingerprint `3c1b357c…`). Subsequent Round 3 P2 source remediation is implemented
  across the approved three-file scope and its independent dirty-tree re-review is historical terminal `cleared`
  evidence. Terminal independent final-8 dirty-tree re-review cleared all eight CodeRabbit findings with no findings;
  post-final-8 remote exact-commit CI remains separate and pending.

---

## 4. T2 사전 선언 (게이트 ③ 대상)

게이트 ③은 "티켓 본문이 **사전 명시**한 파일 집합과 정확히 일치"를 요구한다.

**확정 (2026-08-08, S0 이후) — T2 수정 대상은 공집합이다.**

S0가 env 오버라이드 경로를 PASS시켰으므로 `application.yml`을 건드릴 이유가 사라졌고,
S1~S6의 산출물은 전부 신규 파일이다:

| 단계 | 산출물 | 분류 |
|---|---|---|
| S1 | v2 Flyway location 디렉터리 + 마이그레이션 | 신규 |
| S2 | v2 조건부 `@Configuration`/빈 | 신규 |
| S3 | v2 catalog loader · `web/game/app/game/v2-lab/**` | 신규 + `web/game/middleware.ts` 기존 파일 수정(하드 404) |
| S4 | 아키텍처 테스트 클래스 | 신규 + `app/game-engine/build.gradle.kts` 기존 파일 수정(6개 raw source root를 test input으로 선언) |
| S5 | `docker-compose.v2-sandbox.yml` · `v2-sandbox.env.example` · `infra/nginx/shared-gateway-relay.conf.template` | 신규(T2 아님) |
| S6 | `docs/loops/**` artifact | 신규(T2 아님) + `tools/parity/gate.sh` 기존 파일 수정(gateway-api 실행·XML 채점 포함) |
| Round 3 P2 | `docker-compose.v2-sandbox.yml` existing-file build arg + `tools/ops/v2_sandbox_compose_contract_test.sh` new contract test + `.github/workflows/ci.yml` existing-file `agent-system` invocation | explicitly approved P2 remediation scope; all three outside T2 |

### 4.0a Round 3 P2 approved canonical existing-file list

Round 3 P2 remediation에 승인된 source scope는 정확히 다음 세 항목이다. 이는 Codex의 두 P2 finding을
구현하는 three-file scope이며, remote CI green claim이 아니다.

1. **Existing file:** `docker-compose.v2-sandbox.yml` — `web-game` build args에
   `ASSET_PREFIX: /game`을 추가한다. Production Compose/C1 immutable paths는 여전히 무수정이다.
2. **New contract test:** `tools/ops/v2_sandbox_compose_contract_test.sh` — rendered Compose가
   `web-game.build.args.ASSET_PREFIX == "/game"`임을 fail-closed로 검사한다.
3. **Existing CI workflow:** `.github/workflows/ci.yml` — `agent-system` job의
   `Verify v2 sandbox compose contract` step이 `bash tools/ops/v2_sandbox_compose_contract_test.sh`를 실행한다.
   Original step은 `70492bcc` green CI에서 관측됐지만, final-8 permission/active-matcher/docs remediation의 remote
   PR CI run은 아직 관측하지 않았다.

세 항목 모두 T2(`app/*/src/main/kotlin/**`, `infra/src/main/kotlin/**`,
`infra/src/main/resources/db/migration/**`) 밖이다. 그러므로 게이트 ③의 T2 empty result를 P2 전체
scope proof로 오용하지 않는다. 이 canonical existing/approved-file list와 전체 diff가 P2 범위를
판정하며, 이 세 항목 밖의 source 변경은 별도 ownership/approval 없이는 통과로 기록하지 않는다.

따라서 원 구현의 게이트 ③ 기대값은 **빈 출력**이다. 명령 정본은 §4-1에 있다. Round 3 P2 source
remediation은 위 explicit scope로만 추가됐고 independent dirty-tree re-review is historical `cleared`; terminal
final-8 CodeRabbit source/documentation re-review is `cleared`, while final-8 remote exact-commit CI observation은 아직 없다.

이 목록이 비지 않으면 **초과 = 위반**이다. 어느 단계에서든 T2 기존 파일 수정이
불가피해지면 **구현을 멈추고** 이 절을 개정해 사람 승인을 받은 뒤에만 진행한다.
발견 즉시 우회하지 않는다.

T1(`logic/src/main/kotlin/**`·`common/src/main/kotlin/**`·golden·기존 테스트)은 **수정·삭제 0건,
신규 파일 추가만 허용, 예외 없음.**

### 4.0b 승인된 T1/T2 예외 — OPENSAM-184 · OPENSAM-189 (2026-08-17)

**위 "예외 없음"은 이 절로 한 번 개정된다.** 개정 절차는 §4.0가 요구한 그대로다 — 우회하지 않고
이 절을 열어 범위를 명시하고 승인을 받는다. 승인 요청자는 OPENSAM-151(R2) 차단 해제를 지시한
팀 리드이고, 승인 근거는 아래 두 결함이 **동결 목록 자체 때문에 닫히지 못하는** 종류라는 점이다
(동결이 결함을 보존하는 상태 = 동결의 목적과 반대). 승인 범위는 **정확히 아래 5파일**이며,
이 목록 밖의 T1/T2 파일 수정은 여전히 위반이다.

| # | 파일 | 분류 | 무엇을 왜 |
|---|---|---|---|
| 1 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt` | T1 "기존 테스트" | **OPENSAM-184.** ④ 양성 대조가 모든 `opensamguk.*.v2.*` 빈 이름을 인라인 리터럴 4개와 `assertEquals`해서, 합법적인 신규 v2 빈(R2 도시 원장)이 **구조적으로 등록 불가**였다(OPENSAM-150 리뷰 §2 B1). 리터럴을 `APPROVED_V2_BEAN_NAMES` allowlist + 부분집합 단언으로 바꾸고 allowlist 자기검증 테스트를 추가했다. 게이트 성질은 불변 — allowlist에 없는 v2 빈은 여전히 실패한다(§4.0b 증명 1). `assertNoV2Beans()`(프로덕션 0)는 무수정 |
| 2 | `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` | **T1** | **OPENSAM-189.** `engine/v2`가 `runtimeSourceDirectories`·`runtimeDirectSqlBoundaries` 어디에도 없어 `V2CityLedgerStore.load()`의 `jdbc.query`가 **어떤 `assertEquals`에도 묶이지 않았다**(같은 리뷰 §4 D-2). R1은 이 파일이 T1이라 닫지 못하고 R2 선행 조건으로 넘겼다. 디렉터리 1줄 + `DirectSqlBoundary` 1건 **추가**(기존 항목 무수정·무삭제) |
| 3 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerReadBoundGuardTest.kt` | T1 "기존 테스트" | #2의 등재로 케이스 ③(미등재 사실 고정)이 **설계대로** 빨개져 삭제했다. 그 테스트의 KDoc이 "등재되면 이 테스트를 지우고 카탈로그 단언에 넘겨라"라고 명시한 계획된 수명 종료다. 케이스 ①②는 유지 — 카탈로그는 SQL **본문**을 보지 않는다(§4.0b 증명 3) |
| 4 | `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt` | T2 아님(v2 신규 파일) | KDoc이 #1·#2가 닫은 블로커와 미등재를 여전히 사실로 진술하고 있어 갱신. 로직 무변경 |
| 5 | `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt` · `V2SandboxConfiguration.kt` | T2(전자) | `writePathPackages`에 `opensamguk/engine/v2` 1줄 추가 — v2는 `ChangeRecorder`로 데몬 쓰기 경로에 닿으므로 JPA 금지 불변식이 똑같이 적용돼야 한다. 후자는 "이 패키지는 두 목록 밖"이라는 거짓이 된 KDoc 문장 정정 |

**증명(요약; 실측 출력은 `docs/superpowers/reviews/2026-08-17-r2-unblock-bean-gate-and-catalog-review.md`).**

1. allowlist 밖 v2 빈(`v2MutationProbe`)을 게이트 안에 등록 → `V2BothConditionsBeanGateIT` FAILED.
2. allowlist를 `emptySet()` / `setOf("v2*")`로 각각 무력화 → `V2BeanAllowlistSelfCheckTest` FAILED.
3. `HotColdCatalog`에서 `V2CityLedgerStore` 경계를 제거 → `HotColdWorldCatalogGuardTest > direct SQL
   calls stay in cataloged cold boundaries` FAILED (등재가 실효적임 + 디렉터리 등재가 탐지원임을 동시 증명).
4. store에 `SELECT *` / `jdbc.update` 주입 → `V2CityLedgerReadBoundGuardTest` FAILED, 같은 실행에서
   `HotColdWorldCatalogGuardTest`는 green(케이스 ①②가 중복이 아니라는 증거).

**여전히 R2 범위 밖:** `DaemonLoopConfig` 배선, v2 store의 빈 등록, 원장 초기 적재. 이 개정은
**차단 해제만** 한다.

## 4-1. diff 게이트 명령 정본 (2026-08-08 개정 — 결함 2건 수정)

GATE-f 적대적 리뷰가 잡고 실측으로 재현한 **blocker 2건**. 개정 전 명령은
게이트 ③·⑤의 `app/**` 부분을 **아무것도 재지 못했다** — 즉 "빈 출력" 보고가
공허하게 참이었고, `application.yml` 수정도 통과시켰을 것이다.
0A-c 설계 전체가 게이트 ⑤ 위에 얹혀 있으므로 이건 계측기 고장이지 사소한 오타가 아니다.

**결함 1 — wildcard pathspec.** git 2.50.1 기본 pathspec에서
`'app/*/src/main/kotlin/'`은 매치 0건이다. 실측:

| pathspec | 출력 |
|---|---|
| `'app/*/src/main/kotlin/'` | (빈 출력) |
| `'app/*/src/main/kotlin'` | (빈 출력) |
| `':(glob)app/*/src/main/kotlin/**'` | `app/game-engine/.../flush/DatabaseHooks.kt` |
| `app/` | `DatabaseHooks.kt` + `FlushPayloadConvergenceTest.kt` |

→ 와일드카드가 든 pathspec은 **반드시 `:(glob)` 접두 + `/**` 접미**로 쓴다.
wildcard 없는 `infra/...` 세그먼트와 게이트 ②는 영향 없었다.

**결함 2 — 기준선 드리프트.** `origin/main`은 이 브랜치 분기 후 전진했다.
`origin/main` 기준으로 재면 이 브랜치가 건드린 적 없는
타 세션 커밋(`DatabaseHooks.kt` M, `FlushPayloadConvergenceTest.kt` D)이 섞여
**거짓 T1/T2 위반**이 뜬다. 기준선은 **merge-base 고정**이다.

**개정 (2026-08-17, OPENSAM-188) — 게이트 실행은 스크립트가 정본이다.**
아래 명령을 사람이 복붙하면 기준선(merge-base)과 pathspec 두 곳에서 계속 틀린다. 실행은
`scripts/agent/v2-isolation-gate.sh [<ref>]`로 한다 — merge-base를 스스로 계산하고,
`:(glob)` pathspec을 고정하고, 위반 시 exit 1로 fail-closed한다. 아래 블록은 그 스크립트가
실행하는 명령의 문서판이며 스크립트와 어긋나면 **스크립트가 정본**이다.
게이트 ⑤의 `README.md` 제외 사유와 그 좁히기가 아무것도 놓치지 않는다는 mutation 증명은
`docs/superpowers/reviews/2026-08-17-opensam-188-gate-defects-review.md` 참조.

**개정 (2026-08-17, OPENSAM-190) — 게이트 ②에서 테스트 루트의 `**/v2/**` 디렉터리를 제외한다.**
⑤의 README 결함과 동형이었다: v2 소유 테스트가 통째로 동결돼 v2 후속 티켓이 OPENSAM-35가 만든
자기 테스트를 고칠 구조적 방법이 없었다. 특히 `V2ProductionContextBeanGateIT`는 "production에
v2 빈 0개"를 **v2 빈 타입을 하나씩 열거해** 증명하므로, 얼려 두면 v2가 자랄수록 격리 증명이
낡는다 — 동결이 격리를 지키는 게 아니라 좀먹는다. v1 패러티 코어·골든·v1 가드 테스트는 전부
동결 유지이며, 무엇이 보호를 잃고 무엇이 남는지의 전수 열거와 mutation 증명은
`docs/superpowers/reviews/2026-08-17-opensam-190-gate2-narrowing-review.md`에 있다.

```bash
MB=$(git merge-base HEAD origin/main)

# ② T1 — 기대: 빈 출력 (2026-08-17 OPENSAM-190: 테스트 루트의 v2 디렉터리 제외)
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)logic/src/main/kotlin/**' ':(glob)common/src/main/kotlin/**' \
  ':(glob)logic/src/test/resources/golden/**' \
  ':(glob)logic/src/test/kotlin/**' ':(glob)common/src/test/kotlin/**' \
  ':(glob)infra/src/test/kotlin/**' ':(glob)app/*/src/test/kotlin/**' \
  ':(glob,exclude)logic/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)common/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)infra/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)app/*/src/test/kotlin/**/v2/**'

# ③ T2 — 사전선언 = 공집합, 기대: 빈 출력
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/kotlin/**' ':(glob)infra/src/main/kotlin/**' \
  ':(glob)infra/src/main/resources/db/migration/**'

# ⑤ 설정 리소스 무수정 — 기대: 빈 출력 (2026-08-17 OPENSAM-188: README.md 제외)
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/resources/**' ':(glob)infra/src/main/resources/**' \
  ':(glob,exclude)app/*/src/main/resources/**/README.md' \
  ':(glob,exclude)infra/src/main/resources/**/README.md'

# C1 스택 파일 분리 — 기대: 빈 출력
git diff --name-only --diff-filter=MD "$MB" -- \
  docker-compose.production.yml docker-compose.yml tools/agent-system/check.py

# 전체 M/D — live output은 PR Round 1 source/doc remediation 뒤 재검토한다
git diff --name-only --diff-filter=MD "$MB"
```

**historical remeasurement result (2026-08-08, old `MB=fb90eac1`):** ②③⑤는 당시 빈 출력이었고,
전체 M/D는 `.ai/current-state.md` · `.ai/decisions.md` · `.ai/ownership.md` · `.ai/task.md` ·
`app/game-engine/build.gradle.kts` · `tools/parity/gate.sh` · `web/game/middleware.ts`였다.
현재 canonical base는 `b847c351ff7f574c744e1f4f3da7c0410a1cbe38`이며, 이 문서의 current result는
S6 §15.2의 merge-base glob remeasurement가 정본이다. historical result를 current exact-SHA pass로
승격하지 않는다.
`build.gradle.kts` 수정은 cross-module naming guard가 읽는 6개 raw source root를 Gradle test input으로
등록해 UP-TO-DATE false-green을 막는다. `gate.sh` 수정은 S4 후속 gateway-api
아키텍처 테스트가 표준 backend 게이트에서 실제 실행되고 XML 채점되도록 하는 게이트 자체의
폐쇄이며 T2 production source 수정이 아니다. 제약 자체는 실제로 지켜지고 있었다 — 고장난 건
게이트였다. 이 결함 이력은 통과했다고 지우지 않는다.

---

## 4-2. 소비자 티켓이 지켜야 할 규약 — **v2 런타임 코드는 `opensamguk.*.v2.*` 패키지에 둔다**

대상: **OPENSAM-150(R1)** `v2_city_ledger` 마이그레이션 · **OPENSAM-151(R2)** 시나리오 env 분리.
0A가 넘기는 것은 게이트뿐이고, 게이트가 유효하려면 소비자가 아래 두 규약을 지켜야 한다.

1. **패키지 규약(신규, GATE-f Q4).** v2 런타임 코드(`@Component`/`@Bean`/설정 클래스 포함)는
   반드시 `opensamguk.<module>.v2.*` 패키지에 둔다.
   `V2ProductionContextBeanGateIT`의 누출 탐지는 **빈의 타입 이름에 `.v2.`가 들어 있을 때만**
   동작한다(`app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt:44-48`,
   game-api·gateway-api 동형). 따라서 `@Component class V2CityLedgerStore`를
   `opensamguk.engine.ledger`에 두면 **하드코딩 타입 목록과 패키지 스캔을 둘 다 빠져나가고
   게이트는 조용히 통과한다.**

   **이름 휴리스틱 탐지 테스트를 만들었다(GATE-f2 F1, 실측 후 결론 반전).** 이전 판본은
   "오탐만 늘리므로 만들지 않았다"고 단정했는데 측정한 적이 없었고, 실측하면 거짓이다.
   2026-08-08 실측:

   ```text
   $ grep -rn "class V2\|object V2\|interface V2" app infra common logic --include="*.kt" | grep /src/main/
   app/game-api/src/main/kotlin/opensamguk/gameapi/v2/V2SandboxConfiguration.kt:26:class V2SandboxConfiguration {
   app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt:29:class V2SandboxConfiguration {
   infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt:12:object V2SandboxGate {
   infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt:34:class V2SandboxMarker
   infra/src/main/kotlin/db/migration/V26__npc_lifecycle_phase_units.kt:13:class V26__npc_lifecycle_phase_units : ...
   infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt:29:class V2ContentCatalog(...)
   ```

   6건 중 5건은 이미 `opensamguk.*.v2.*` 패키지 안이고, 나머지 1건은 Flyway `V26__`
   (`V2` 뒤가 숫자라 `V2[A-Z]` 패턴에 비매치). 즉 **오탐 0건** — 안 만들 이유가 없었다.
   가드: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2NamingConventionGuardTest.kt`
   (`gate.sh backend`의 `:app:game-engine:test`에 포함). **천장:** `V2`로 시작하지 않는 이름의
   v2 코드는 여전히 못 잡으므로 이 규약은 여전히 게이트의 **전제**이고, 그 절반만 자동 강제된다.
2. **게이트 규약(S2 규약 1, 기존).** v2 빈은 전부 각 앱 `V2SandboxConfiguration` 안 `@Bean`으로만
   등록한다. `@Component` + 컴포넌트 스캔으로 등록하지 않는다.

규약 1을 어기면 규약 2 위반이 **자동 판정되지 않는다**(규약 1이 무너지면 탐지 층이 통째로
공허해진다). 두 규약은 함께 지켜져야 하며, 위반은 리뷰가 잡아야 한다.

부수 제약: v2 시나리오 코드도 `scenario_<숫자>` 정규형이어야 한다(§3 S5 — `ScenarioSeedRunner.kt:150`).

## 5. 비범위

- v2 파이프라인 seam 개설 — proposal `round3…:459`가 명시하듯 0A 7항목은 **전부 격리이고 확장점
  개설은 하나도 없다.** seam은 오픈 후 P0.
- OPENSAM-150(R1) `v2_city_ledger` 스키마 — 0A-c가 만든 location을 **소비**하는 후속 티켓.
- sibling `opensamguk-docker` 리포 변경(C2에서 (a) 채택 시).
- production 배포·cutover·서버 승격.

## 6. 사람 승인 필요 지점

1. **`.ai/task.md` 계약 갱신** — 현재 계약은 OPENSAM-34다. OPENSAM-35 계약으로 교체 승인.
2. **`.ai/ownership.md` foundation owner 1행 등록** — compose·공용 스키마는 fence 대상이라
   ([Shared-file ownership fence](../../../.ai/ownership.md#shared-file-ownership-fence)) 소유자 등록 전 read-only다.
3. ~~C1·C2~~ — **2026-08-08 결정 완료** (§2 참조).
4. **M1·M2·M3 확정** (문서 미명시 3건) — 계획 채택과 함께 승인 대상.
5. 커밋·푸시·PR·머지·배포는 각각 별도 승인. 골든/테스트 약화, legacy 쓰기, `.env*` 접근 금지.
