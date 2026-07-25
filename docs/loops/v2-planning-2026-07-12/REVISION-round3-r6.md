# round-3 설계안 개정 6차 — 처리 기록

> 일시: 2026-07-25 · 입력: `REVIEW-round3-r5.md`(9/10 `fix-required`, N = 문항 7, **새 실패형: 문서 내부 자기모순**) · 대상: `round3-proposal-city-guanxi.md` 제자리 수정 (1394 → 1478줄, 새 파일 0[본 기록 제외], 미커밋, 코드 무수정, `docs/wiki/raw/**` 무수정)
> 상태: **재채점 대기** (동일 시험지 + 독립 reviewer, 6번째)

## 한 문단 요약

CRITICAL-1은 **고르는 문제가 아니라 찾는 문제였고, 리포에 답이 있었다.** 배포 토폴로지는 **한 프로세스 = 한 월드 = 한 DB**(분기 A)이며, 가장 강한 근거는 compose도 ADR도 아닌 **`ScenarioSeedCoordinator.kt:37-49`** — `world_state`에 설정된 월드가 아닌 것이 하나라도 있으면 `error(...)`로 **부팅을 막는다.** "한 DB = 한 월드"는 이 설계안이 DoD로 얻어내야 할 약속이 아니라 **이미 강제되고 있는 코드 불변식**이다(채점자가 열어 둔 분기 C는 코드가 이미 닫아 놓았다). 그 결과 개정 5차가 CRITICAL-1의 진원지로 쓴 한 문장 — "`JdbcFlushExecutor`는 `DaemonLoopConfig.kt:108`에서 **v1** 템플릿으로 생성된다" — 은 **거짓**으로 확정됐다(그 템플릿은 한정자 없는 오토컨피그이고, 어느 DB를 가리키는지는 프로세스 env가 정한다). 파생물 넷이 함께 무효화됐다: T2에서 내렸던 **`JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 2행 복귀**, **두 번째 Hikari 풀 폐기**, **`TurnRunService.kt` 1행 삭제**, **§11 U11 철회**(단일 DB·단일 트랜잭션이므로 걸칠 DB가 없다). T2는 **11행**(편집 10 + 마이그레이션 1)이 됐고 **모든 행에 "가드 영향" 열**을 붙였다(M1·M2). UNK-C·UNK-D는 UNKNOWN이 아니라 **코드 근거로 닫았다**. MINOR 4건 전부 파일을 열어 정정했고, 그중 m1은 **없는 결함을 자백하던 `ponytail:` 주석**이었다. 오픈 경로는 **20 불변**.

---

## 0. 토폴로지 확정 (이 라운드의 축 — 지시된 산출물)

### 0-1. 무엇을 열었나

| 파일 | 열어서 확인한 것 |
|---|---|
| `app/game-engine/src/main/resources/application.yml:8-11,14,30` | `spring.datasource.url: ${GAME_DATABASE_URL:jdbc:postgresql://localhost:5432/sammo}` · `spring.flyway.locations: classpath:db/migration` · `opensamguk.world-id: ${OPENSAMGUK_WORLD_ID}` — **월드 ID에 기본값이 없다**(뜨려면 반드시 주입) |
| `app/game-api/src/main/resources/application.yml:8-14,30` | 동일 구조. DataSource·Flyway·world-id 전부 env 주입 |
| `app/game-engine/.../config/EngineProcessWorld.kt` (전문) | 클래스 본문이 `val worldId: WorldId = WorldId(configuredWorldId)` 한 줄 — **프로세스당 월드 하나**, 컬렉션도 맵도 없다 |
| `app/game-engine/.../config/WorldIdConfig.kt:11` | `EngineProcessWorld(rawWorldId.toIntOrNull() ?: error("OPENSAMGUK_WORLD_ID must be a positive integer"))` |
| **`infra/.../seed/ScenarioSeedCoordinator.kt:37-49`** | `worldIds()`가 `emptyList` 또는 `listOf(expectedWorldId.value)`가 **아니면** `error("Scenario seed requires exactly configured world_state.id=…; found $ids")` — **한 DB에 두 월드 = 부팅 실패** |
| `app/game-engine/.../boot/ScenarioSeedRunner.kt:69-104` | 위 코디네이터를 부르는 `SeedBootstrap.ensureSeeded` 실경로(부팅·`WorldSnapshotLoader` 양쪽에서 호출) |
| `docker-compose.yml:155-185` · `docker-compose.production.yml:52-53,58,63,96,101,133` | game-engine 서비스는 **한 개**이고 `OPENSAMGUK_WORLD_ID: ${OPENSAMGUK_WORLD_ID:?… required}`로 필수 주입. **v2 스택은 아직 파일에 없다** |
| `common/.../wire/StreamKeys.kt:16-18,23-27,33-34` | 커맨드/이벤트 스트림·실시간 채널·결과 키가 전부 `w{worldId}` 세그먼트를 포함(주석 `:5-8`이 OPENSAM-127 산출물임을 명시) |
| `.ai/decisions.md:178-198` | ADR-LITE-018(v2 = 별도 DB `opensamguk_v2`) · ADR-LITE-019 |
| `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:73-81` | 0A-a~0A-g. **0A-e** "프로덕션 compose에 v2 0", **0A-f** "프로덕션 컨텍스트 v2 빈 0" 아키텍처 테스트 |
| `app/game-engine/.../config/DaemonLoopConfig.kt:104-108` | 문제의 그 줄 — `NamedParameterJdbcTemplate` 파라미터에 **한정자(`@Qualifier`)가 없다** = 오토컨피그 단일 DataSource |
| `app/game-engine/src/main/kotlin/` 전수 grep | `HikariConfig`/`HikariDataSource`/`DataSource` 빈 정의 **0건** (채점자 관측 재현 — 참이다) |

### 0-2. 확정된 것 — **분기 A**

| | v1 프로세스 | v2 프로세스 |
|---|---|---|
| `OPENSAMGUK_WORLD_ID` | v1 월드 id | **다른** 월드 id |
| `GAME_DATABASE_URL` | `…/sammo` | **`…/opensamguk_v2`** (ADR-LITE-018) |
| 오토컨피그 DataSource | v1 DB | **v2 DB** |
| `DaemonLoopConfig.kt:104-108`의 `NamedParameterJdbcTemplate` | v1 DB | **v2 DB** |
| 그 템플릿으로 만들어진 `JdbcFlushExecutor` | v1 원장 | **v2 원장** |
| `command_inbox`/`command_result_outbox` | v1 DB의 표 | **v2 DB의 같은 이름 표** (코드 동일, DB만 다름) |
| Redis 키 | `…:w{v1}:…` | `…:w{v2}:…` (충돌 불가) |
| `world_state` 행 수 | 1 | 1 (**둘 이상이면 부팅 실패**) |

**분기 C(한 DB에 두 월드)는 이 설계안이 배제한 것이 아니라 코드가 이미 배제해 놓았다.** 채점자는 분기 C가 저자의 DoD 산문으로만 막혀 있다고 봤는데, `ScenarioSeedCoordinator.kt:46-48`이 `error(...)`로 막는다.

### 0-3. 확정 못 한 것 → **UNKNOWN이 아니라 DoD 강제로 적었다**

위 표의 v2 열은 **오늘 compose 파일에 존재하지 않는다**(game-engine 서비스는 한 개뿐). 지시대로 UNKNOWN으로 남기지 않고 `OPENSAM-35` 0A DoD에 **강제 사항**으로 적었다(제안서 §7.1 "남는 DoD 강제 사항"):

1. v2 스택은 **별도 compose 서비스**(또는 별도 스택 파일)로 뜨고 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE`를 v1과 다른 값으로 받는다.
2. v2 전용 Flyway location은 **`SPRING_FLYWAY_LOCATIONS` 환경변수 오버라이드**로만 더한다(`application.yml` 무수정 = 게이트 ⑤ 유지). ← 이 한 가지는 표준 동작이나 이 리포에서 실측 선례가 없어 **§11 U12**로 신설하고 대체 경로 둘(v2 프로파일 파일 신규 추가 / v2 `@Configuration`이 자기 `Flyway` 빈 생성)을 함께 적었다. 어느 쪽이든 T2 편집 0.
3. 0A-f의 "프로덕션 컨텍스트 v2 빈 0" 아키텍처 테스트가 v1 프로세스에서 실측한다.

---

## 1. 항목별 처리

| 항목 | 처리 | 위치 (제안서) |
|---|---|---|
| **CRITICAL-1** DB 토폴로지 자기모순 | **분기 A 확정.** 위 §0. `:966`은 "별도 DB 결정에 의존" 대신 **`ScenarioSeedCoordinator` 코드 불변식**으로 근거를 바꿨고, `:1031`의 "v1 템플릿"은 **거짓으로 명시 철회**, `:1046`은 "**v2 프로세스 자기 DB의** `command_inbox`"로 정정 | `:966` 블록 · `(3) DataSource·트랜잭션·마이그레이션` 전면 재작성 · `#### 개정 6차 — 배포 토폴로지를 확정한다` 신설 절 · 메커니즘 요약표 R1·R4·R5 행 |
| **CRITICAL-1 파생 ①** `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 재판정 | **둘 다 T2 복귀.** v1-inert 논거가 T2 1·2행과 **동일**해진다(빈 컬렉션 ⇒ step 미진입, P6 betting 선례). `FlushPayload`는 `infra/.../JdbcFlushExecutor.kt:2287`에 사는 **T2 타입**이라 후행 기본값 필드 추가가 가능하고, `FlushPayloadConvergenceTest`는 명명 필드만 검사해 필드 추가에 무영향 | T2 표 3·4행 |
| **CRITICAL-1 파생 ②** 두 번째 Hikari 풀 | **폐기.** 오토컨피그 템플릿이 곧 v2 커넥션이다. `ReadBarrierDataSourceConfig.kt`는 "이렇게 한다"가 아니라 "`DataSource` 빈을 `@Primary` 없이 더 노출하면 Boot 자동설정이 흔들린다"는 **금지 근거**로만 남겼다 | `v2 DataSource 배선` 문단 |
| **CRITICAL-1 파생 ③** `TurnRunService.kt` | **T2에서 삭제.** v2 싱크가 없어졌으므로 이 파일을 열 이유가 없다 — `:527`(`toFlushPayload`)·`:404`(`flushExecutor.flush`)는 **이미 있는 호출**이고 6차는 그 안쪽만 넓힌다 | 내려간 행 표 |
| **M3 / U11** | **철회.** "다른 DataSource·다른 트랜잭션"이라는 전제가 거짓이므로 교차 원자성 항목 자체가 소멸. 멱등 UPSERT는 **UNKNOWN의 근거가 아니라 재시작 안전성 설계 선택**으로 R1 DoD에 남겼다 | §11 U11 행(취소선 + 철회 사유) |
| **M1** 가드 영향 누락 | **T2 11행 전부에 "가드 영향" 열 신설.** 각 행이 `runtimeCallKeys`/`runtimeCallCounts`/`runtimeDirectSqlBoundarySources` 세 `assertEquals` 중 무엇을 깰 수 있는지 한 줄씩. `:1026`의 "가드 위반 가능성 **전체**를 닫는다"는 **신규 파일에 한해서**로 좁혔고, 세 번째 파손 양식(implicit `hasRepositoryExtension` 규칙, `GuardTest:334-340`)을 추가했다 | T2 표 6번째 열 · `:1026` 개정 6차 블록 |
| **M2** `DaemonWriteGuard` 미언급 | **판정 절 신설.** writePath(`DaemonWriteGuard.kt:29-34` = `engine/{flush,turn,run,nationbulk}`) 소속은 **4행**(1·2·3·10). `DaemonNoEntityManagerTest`는 상수풀 부분문자열 스캔이고 v2 경로에 JPA 타입이 0개라 발화하지 않는다 ⇒ R1·R4·R5 DoD에 "v2 엔진·인테이크는 JDBC-only" 명시. `Repository`/`Reader` 접미 금지는 **스캔 대상 전 행**으로 확대 적용 | `M2 — DaemonWriteGuard와 DaemonNoEntityManagerTest 판정` 신설 절 |
| **UNK-C** v2 원장 재수화 | **닫았다 — `RehydrateService.kt` 무편집.** v2 원장은 `engine.v2` store가 자기 표를 직접 읽어 복원하고, v1 재수화 대상 표(`select_pool`·`game_kv`·`ng_auction`·`ng_auction_bid`·`ng_betting`·`message`)와 겹치는 것이 없다. lazy 적재면 부팅 순서 의존도 없다 | `UNK-C 판정` 신설 절 |
| **UNK-D** Redis·SSE·헬스체크 | **닫았다 — `engine/redis/**` 무편집.** 근거는 키가 이미 world-scoped(`StreamKeys.kt:16-34`, OPENSAM-127)라는 것. 커맨드 역직렬화(`RedisCommandStream.kt:165-167`)와 `RealtimePublisher.kt:25,33`은 종류를 열거하지 않아 v2 variant 추가에 편집 지점이 없다 | `UNK-D 판정` 신설 절 |
| **m1** `ponytail:` 주석 | **주석 철회.** `logic/.../stats/GetStatValue.kt`를 열어 실측 — `:63` 클램프(PHP 384) → `:64` `pipeline.onCalcStat` → **`:65` 재클램프(PHP 394)**. 문서가 자백하던 "255 초과 가능"은 **일어나지 않는다.** 실제 성질은 정반대(상한 근처에서 보너스가 잘린다: 무력 252 + 우호 6 → 실효 +3)이고 그렇게 바꿔 적었다 | `:654` 블록 |
| **m2** `ProcessIncomeContext` 멤버 | "둘뿐" → **셋**(`val pipeline`·`incomeNations()`·`applyIncome()`, `ProcessIncome.kt:215-219`). 실질 논거는 `IncomeGeneral`의 `cityId` 부재이므로 **판정 불변** | `:978` |
| **m3** `ignoreDefaultEvents` 두 번째 읽기 지점 | **명시.** `EngineEventConfig.kt:41-45`가 `world_state.config->>'ignoreDefaultEvents'`를 읽고, 그 값은 `ScenarioImporter.kt:194`가 `config` jsonb에 넣어 `:201-210` INSERT로 심는다. **판정: v2에서 이 런타임 값은 결과에 영향을 주지 않는다**(`:57`에서 `rows.isEmpty()`일 때만 쓰이는데 v2 월드는 12행을 전량 재시드하므로 비어 있지 않다) ⇒ **T2 편집 0** | `:966` 블록의 "부수 2" |
| **m4** 행 범위 오차 | `ReadBarrierDataSourceConfig.kt` `:37-45`/`:43-45` → **`:33-43`** | `v2 DataSource 배선` 문단 괄호 |

---

## 2. 채점자 반박 — **없다. 5차 채점은 전부 옳았다**

CRITICAL-1은 **정확한 지적이고 이 설계안 여섯 바퀴 중 가장 값진 지적이다.** 저자는 다섯 바퀴 동안 "묶여 있다"는 자기 해석을 코드 사실로 착각한 채 그 위에 설계를 얹고 있었다. M1·M2·m1~m4도 파일을 열어 확인한 결과 전부 맞았다.

**한 가지만 보탠다(반박 아님).** 채점자는 분기 C(한 DB 다중 월드)를 "저자가 DoD로 배제했을 뿐 코드로는 열려 있다"고 봤는데, **`ScenarioSeedCoordinator.kt:46-48`이 이미 부팅을 막는다.** 채점자가 그 파일을 열지 않았을 뿐이고, 이 사실은 채점자의 결론(분기를 확정하라)을 약화시키지 않고 오히려 **분기 A를 저자 선언이 아닌 코드 사실로 만든다**.

---

## 3. 설계가 실제로 바뀐 지점 (문구 정정과 구분)

1. **v2 원장의 쓰기 경로가 v1 flush 기계로 돌아왔다.** `DirtyState`/`ChangeRecorder` → `DatabaseHooks.toFlushPayload` → `JdbcFlushExecutor` v2 step. 5차의 "별도 싱크"는 없어졌다.
2. **v1 델타와 v2 델타가 같은 트랜잭션에서 커밋된다.** 원장이 찢어질 창이 사라졌다 — 5차 대비 **가장 큰 실익**이며, T2 2행을 되돌리는 값을 치를 이유다.
3. **v2 쓰기 경로가 `DaemonWriteGuard` 안으로 들어왔다.** 5차 설계는 v2 쓰기 전체가 "데몬 쓰기는 JDBC-only" 하드 룰의 **테스트 사각지대**였다. 그 사각지대가 사라졌고, `writePathPackages`를 넓히는 편집(T2 +1행)도 불필요해졌다.
4. **v2 `@Configuration`의 역할이 축소됐다.** DataSource·풀을 만들지 않고 `@ConditionalOnProperty(V2_ENABLED)` 뒤에서 빈만 등록한다.
5. **T2 표에 "가드 영향" 열이 생겼다.** 티켓 본문은 이 열까지 옮겨 적는다(§7.2 게이트 ③ 비교 정본도 11행 표로 갱신).
6. **`:654`의 능력치 상한 서술이 뒤집혔다** — "255를 넘을 수 있다"(거짓) → "상한 근처에서 보너스가 잘린다"(실측).

*(1·2·3은 5차 설계의 되돌림이지 4차로의 복귀가 아니다. 읽기 경로 판정 — `WorldSnapshotLoader`/`InMemoryTurnWorld` 봉인 — 은 5차 그대로 유효하다.)*

---

## 4. 최종 수량

| 항목 | 5차 | 6차 |
|---|---|---|
| **T2 행 수** | 10 (편집 9 + 마이그레이션 1) | **11 (편집 10 + 마이그레이션 1)** |
| 그중 복귀 | — | **2** (`DatabaseHooks.kt` · `JdbcFlushExecutor.kt`) |
| 그중 삭제 | — | **1** (`TurnRunService.kt`) |
| T2 표 열 수 | 5 | **6** (가드 영향 열 신설) |
| writePath 소속 행 | (미판정) | **4** (`DirtyState` · `ChangeRecorder` · `DatabaseHooks` · `TurnDaemonCommandDispatcher`) |
| 8개 스캔 디렉터리 소속 행 | (미판정) | **5** (`DirtyState` · `ChangeRecorder` · `WorldEventContextFactory` · `WorldActionContext` · `TurnDaemonCommandDispatcher`) **+ 명시 추가 1**(`DaemonLoopConfig.kt`) |
| 추가 DataSource·커넥션 풀 | 1 (서비스마다) | **0** |
| flush 트랜잭션 | 2개(v1·v2 별개) | **1개** |
| **오픈 경로** | 20 단일값 | **20 단일값 (불변)** — 티켓 수·범위·순서 무변경, 바뀐 것은 R1 내부 편집 목록뿐 |
| §11 UNKNOWN | 11 | **11** (U11 철회 · U12 신설) |
| 채점자 UNKNOWN | UNK-A~E 미해결 2건 | **UNK-C·UNK-D 코드 근거로 종결** |
| 게이트 | ①②③④⑤ | ①②③④⑤ (불변, ③의 비교 정본만 11행 표로 갱신) |

---

## 5. 잔여 UNKNOWN

| # | 상태 |
|---|---|
| U9 (sealed 서브클래스 파일 분리) | **유효** — `WireJson.kt:11-16`에 다형 등록이 없어 컴파일로만 확인된다 |
| U10 (v2 마이그레이션·시드 부팅 순서) | **축소** — v2 프로세스의 `ensureSeeded`도 v2 DB를 대상으로 하므로 v1과의 간섭은 없다. 남은 질문은 "v2 store 첫 읽기 시점에 0A-c Flyway가 돌았는가" 하나 |
| ~~U11~~ (교차 DB 원자성) | **철회** — 걸칠 DB가 없다 |
| U12 (`SPRING_FLYWAY_LOCATIONS` env 오버라이드) | **신설** — 표준 동작이나 이 리포에 선례 0건. 대체 경로 둘 다 T2 편집 0 |

---

## 6. 저자가 남긴 주의 (오케스트레이터 확인 필요)

1. 개정 3·4·5차와 동일 — `git status`에 이 세션이 손대지 않은 파일 수정이 다수 있고 그중 일부는 이 설계안이 인용한다. **커밋 전 diff와 인용 줄번호 재검증 필요.**
2. 이번 세션이 수정한 파일은 `round3-proposal-city-guanxi.md` **하나**, 새로 만든 것은 이 기록 하나. 코드·골든·`docs/wiki/raw/**` 무수정, 커밋 없음.
3. **0-2 표의 v2 열은 아직 compose 파일에 없다.** 코드 불변식(월드 스코프·시드 코디네이터)은 확인했으나 **v2 스택을 실제로 그렇게 띄우는 것은 0A 티켓의 책임**이며 이 문서는 그것을 DoD로 강제만 한다. 오케스트레이터가 0A 티켓 본문에 §0-3의 세 항목이 들어갔는지 확인해 주기 바란다.
4. **이번 라운드에서 배운 실패형을 자기채점 0-ter로 신설했다 — "물리적 제약을 지어냈다".** 1·3·4차의 "빠뜨렸다", 5차의 "불가능한 줄 몰랐다"와 다르다. 앞의 둘은 목록을 넓혀 고치지만 이것은 **넓힐수록 나빠진다**(없는 제약을 피하려 설계를 우회시키므로). 실제로 5차의 우회는 v2 쓰기를 하드 룰 테스트 사각지대에 놓을 뻔했다. 일반화한 규율 — **"이 코드는 X에 묶여 있다"고 쓸 때 X가 소스에 있는 이름인지 내가 붙인 해석인지 매번 구분하고, 후자면 설정 파일까지 열거나 UNKNOWN이다.**
5. 개정 6차의 새 경로 역시 **소스 독해 추론이며 컴파일·부팅 확인이 아니다.** U9·U10·U12가 그 세 구멍이다.
