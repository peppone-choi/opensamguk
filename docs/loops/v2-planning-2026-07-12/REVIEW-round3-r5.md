# REVIEW round-3 r5 — 도시 중심·인맥(꽌시) 설계안 5차 개정 독립 채점

> **VERDICT: `fix-required` · 총점 9/10**
> 채점 대상: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` (개정 5차)
> 시험지: `docs/loops/v2-planning-2026-07-12/GOLDENSET-round3-city-guanxi.md` (부분 충족 = N)
> 저자 보고: `REVISION-round3-r5.md`
> 채점자: reviewer5 (독립 · 저자 아님 · 앞선 4명과 별개)
> 이전 채점: r1 5/10 · r2 6/10 · r3 9/10 · r4 9/10 (r3·r4의 유일한 N = 문항 7)
> 채점일: 2026-07-25

---

## 0. 판정 한 문단

**날조는 없다.** 약 50개 `path:line` 인용을 전부 열어 대조했고 **한 건도 어긋나지 않았다** — 2차 개정의 문자열 날조 전례는 완전히 청산됐다. 문항 7을 뺀 9문항은 실질로 충족한다. 그러나 문항 7은 **다시 N**이다. 사유는 앞선 네 바퀴와 같은 "목록 누락"이 아니라 더 깊은 곳에 있다 — **개정 5차의 전환을 떠받치는 DB·배포 토폴로지 전제가 문서 안에서 서로 모순이고, 두 갈래 중 어느 쪽을 택해도 T2 표의 서로 다른 부분이 무너진다.** 이것은 트집이 아니라 파일 근거로 닫히는 모순이며, 아래 CRITICAL-1에 증거 사슬을 전부 적었다.

동시에 분명히 적는다. **개정 5차가 개정 4차 설계를 뒤엎은 것은 읽기 경로에 한해 정당하다.** `HotColdWorldCatalogGuardTest`가 `WorldSnapshotLoader`를 T1 카탈로그와 `assertEquals`로 봉인한다는 저자의 주장은 **파일 전문을 읽어 확인했고 사실이다.** 4차 설계의 T2 6·7행은 실제로 물리적으로 불가능했다. 저자는 이 점에서 옳고, r4 채점을 뒤집을 자격이 있다.

---

## 1. 10문항 채점

| # | 요구 | 판정 | 근거 (직접 확인) |
|---|---|---|---|
| 1 | "도시 중심" = 자원 소유 주체, 운영자 자기규정 근거 | **Y** | §1.1의 인용 3건을 원문 대조 — `help__start__peq__peq.md:46`(City-oriented 선언)·`:61`(금·병량 이전 + 도시병사 상주)·`help__start__basic__myostart.md:116/119/122/125` **전부 행 번호까지 정확**. §1.2가 정의를 "정기 재정 순환의 소유 행 이동"으로 좁히고 원안의 "전부"를 명시 철회 |
| 2 | 도시 소유 자원·도시병사 상주 스키마·판정, 병사 0 → 공백지화 deterministic | **Y** | §2.4의 `BAD_STATE_CODES = {3..9}`를 `RaiseDisaster.kt:104-127` 실측 표에서 재확인(등장 state = 3,4,5,6,7,8,9). 월 게이트 `{1,4,7,10}`의 근거 `EventStore.kt:171·180·190·197` **네 행 모두 정확**. §8 R3 행이 `city_id ASC` 순회 + `garrison` 0 → 같은 반복 안 `nationId = 0`, draw 0으로 규정 |
| 3 | 인사권·배치효과·감시·자원분배 4축, `officer_level`+`officerCntByCity` 위에 얹기 | **Y** | §3.2가 기존 축 위에 얹는 형태를 유지. 평행 신축 0. `ProcessIncome`의 도시 귀속 재조립은 §9.2 R2 단일 티켓 |
| 4 | 관계망 = 독자 추가 명시 · **능력치 보정** · emergent/PRESET 구분 · v2 전용 stat source · 출처 규율 | **Y** | §4.4가 emergent/PRESET을 검증 대상까지 갈라 정의하고 출처를 사서·연의 / 자체 편성 / (조건부)RTK 셋으로 밝힌다. RTK 호오 필드는 **UNKNOWN(U2)으로 정직하게 강등**하고 그 위에 설계를 세우지 않는다. §4.7이 사용자 결정을 **희석 없이** 구현 — 통무지 3스탯 가산, 같은 도시 조건, ±2, 선언 ±6 / 실효 ±8, 축별 클램프 후 합산, fold 꼬리 적용. 실효 상한 재계산의 근거 `GetStatValue.kt:53·54-64·89-91`, `OfficerLevelModule.kt:27-36·46-47`, `ActionPipeline.kt:24·90`, `FrontInfoController.kt:394` **전부 행 단위 정확**. `crossBase`가 `withIActionObj`를 그대로 전달한다는 핵심 주장도 `:89-91`에서 확인 |
| 5 | 임원진 체류 효과 6종 도입 여부 판정 + 판정 지점·중첩 규칙 | **Y** | §5-bis가 임원진·중앙관직·품관을 세 층으로 분리하고 오픈 경로 증분 0으로 판정 |
| 6 | 도시 특색 9종·규모 게이트·지역병종 채택/보류 판정 | **Y** | §6. `LogicEntities.kt:64` "There is NO `city.tech`" 인용 정확 — 도시기술 900 발현 조건이 성립하지 않는다는 판정의 근거로 올바르게 소비됨 |
| 7 | **v1 패러티 불변 증명 — v2 전용 스키마·경로** | **N** | **CRITICAL-1**(DB 토폴로지 자기모순 — 어느 갈래를 택해도 T2 표의 일부가 무너진다) + **MAJOR-1**(편집 9행 중 6행이 가드 스캔 디렉터리 안인데 가드 영향 분석은 2행분만). §2 참조 |
| 8 | 각 신규 요소가 LEDGER 규칙 4를 만족함을 항목별로 | **Y** | §8 표가 오픈 경로 6항목 전부에 결정·판정(draw 수)·상태 변화·replay/log를 채운다. 비대상 3건(R6·감찰부·성벽 특색)을 감추지 않고 밝히고, **구 R8의 규칙 4 단독 만족을 스스로 철회**한 것은 `LEDGER.md:11` 문언에 비춰 옳은 판정이다. R4 공백지화의 파괴적 전이 부채도 명시 |
| 9 | 오픈 경로 최소 부분집합 + 티켓 수량 | **Y** | 기준선 14를 `plans/2026-07-17-v2-ticket-backlog/README.md:55-65`에서 대조 — 행 합계 정확히 **14**. §9.2가 **+6 → 20 단일값**, 조건부 항목 0. R2 분해(20→21) 가능성을 적되 **권고 수량은 20 단일값 유지**로 못 박아 조건부 부활을 막았다. U10·U11 둘 다 disposition에 **"수량·T2 목록 불변"**을 명시 — 수량의 전제가 아니다 |
| 10 | G0 대체 아닌 선행 · ADR-LITE-019 유예와 무충돌 | **Y** | §10 + §9.2 말미. `V2-G0`·`C-track`·`O0/V2-7` 오픈 후 유지, `OPENSAM-149` 선행 유지를 명시 |

**총점 9/10 · `fix-required`.**

---

## 2. 문항 7이 N인 사유

### CRITICAL-1 — v2 월드가 어느 DB 위에서 도는지가 문서 안에서 모순이고, 그 모순이 T2 표를 양쪽에서 무너뜨린다

**증거 사슬 (전부 직접 확인).**

1. `app/game-engine/.../config/EngineEventConfig.kt:30` — `@Bean fun eventStore(jdbc: JdbcTemplate, bootstrap: SeedBootstrap, processWorld: EngineProcessWorld)`. 한정자 없는 **오토컨피그 기본 `JdbcTemplate`**이다.
2. 같은 파일 `:46-47` — `SELECT id, target_code, priority, condition::text …, action::text … FROM event ORDER BY id ASC`. **`world_id` 필터 없음.** `:57`이 `if (rows.isEmpty()) return EventStore.withDefaults(ignoreDefaults)`, `:58-68`이 행이 있으면 DB 행만 적재 — 저자가 §7.1-2에서 쓴 **all-or-nothing** 서술 그대로다.
3. `app/game-engine/.../config/DaemonLoopConfig.kt:104-108` — `@Bean fun jdbcFlushExecutor(jdbc: NamedParameterJdbcTemplate, transactionManager: PlatformTransactionManager): JdbcFlushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(transactionManager))`. 이것도 **한정자 없는 오토컨피그 템플릿**이다.
4. `app/game-engine/src/main/kotlin/` 전수 grep — `HikariConfig`/`HikariDataSource`/`DataSource` **빈 정의 0건**. 즉 game-engine 프로세스에는 오늘 **DataSource가 정확히 하나**이고, 2와 3의 두 템플릿은 **같은 `spring.datasource`에서 나온다.**

**모순.**

- 제안서 `:966` — "즉 v1/v2 이벤트 분리는 `world_id`가 아니라 **ADR-LITE-018의 별도 DB 결정에 전적으로 의존한다.** … v2 티켓은 같은 DB에 두 월드를 올리지 않는다는 전제를 DoD에 적는다."
  ⇒ v2 월드를 도는 엔진 프로세스의 **primary DataSource = `opensamguk_v2`**여야 한다. 아니면 v2 `event` 행이 v1 `event` 표에 들어가고, 그 표는 필터 없이 통째로 읽힌다.
- 제안서 `:1031`(및 삭제 사유 표 `:1069`) — "그 `JdbcFlushExecutor`는 `DaemonLoopConfig.kt:108`에서 **v1 `NamedParameterJdbcTemplate`**로 생성된다. **그러므로 `JdbcFlushExecutor`에 v2 step을 더하면 v2 원장이 v1 DB에 써진다.**"
  ⇒ **같은 프로세스의 primary DataSource = v1 DB**여야 한다.
- 제안서 `:1046` R4·R5 행 — "커맨드는 **v1 `command_inbox`** durable 경로를 그대로 탄다" ⇒ 역시 primary = v1 DB.

**한 프로세스에 DataSource가 하나이므로 이 둘은 동시에 참일 수 없다.**

**갈래별 귀결.**

| 갈래 | 성립하는 것 | 무너지는 것 |
|---|---|---|
| **A. v2 월드 = 자기 DB(`opensamguk_v2`)를 primary로 갖는 별도 프로세스** | `:966`의 이벤트 분리, R2 leaf 치환이 성립 | `:1031`의 전제가 **거짓** ⇒ `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 두 행을 내린 **유일한 근거가 사라진다**(v1 프로세스에서는 v2 컬렉션이 비어 step 미진입 — 1·2행에 쓴 것과 **같은** inert 논거가 그대로 적용된다). 두 번째 Hikari 풀도 불필요해지고, **U11(교차 원자성 부재)은 "ADR-LITE-018에서 나오는 필연"이 아니라 이 설계가 스스로 만든 결함**이 된다. `:1046`의 "v1 command_inbox"도 오기 |
| **B. primary = v1 DB, v2 원장 표만 `opensamguk_v2`** | `:1031`·`:1046`·두 번째 Hikari 풀·U11이 성립 | `:966`의 분리 전제가 **거짓** ⇒ v2 leaf의 `event` 행이 v1 `event` 표에 들어가고, `:46-47`이 **필터 없이** 읽어 v1 월드가 v2 leaf를 실행한다. 이는 **T2 6행(`WorldActionContext.kt`)의 v1-inert 근거 (i) "v1 월드의 `event` 표에는 v2 leaf 이름이 없으므로 호출자가 0"을 정면으로 반증**하고, v1 월간 실행 순서·로그를 바꾼다 = 방어선 1 위반 |

**즉 문항 7의 요구("v2 전용 스키마·경로로 v1 패러티 불변을 증명한다")가 증명되지 않았다.** A면 개정 5차 전환의 절반이 근거를 잃고, B면 T2 표의 inert 논거 하나가 거짓이다. 문서는 어느 쪽인지 어디에서도 확정하지 않는다.

> 참고 — 갈래 C(v1/v2가 같은 DB의 `world_id`로 공존)는 저자 자신이 `:966`의 DoD 문장으로 배제했다. 재검토 대상이 아니다.

### MAJOR-1 — "이 한 줄 규칙이 가드 위반 가능성 **전체**를 닫는다"(`:1026`)는 성립하지 않는다

`HotColdCatalog.kt:135-144`의 `runtimeSourceDirectories` 8개는 `engine/{auction,intake,redis,run,tournament,turn,war,world}`이고, `HotColdWorldCatalogGuardTest.kt:34-44`의 `runtimeSourceFiles()`가 이 8개를 재귀 스캔한 뒤 `DaemonLoopConfig.kt`를 **명시적으로 하나 더** 넣는다. 저자의 확인은 정확하다.

문제는 그 규칙이 **`engine.v2`의 신규 파일**만 덮는다는 점이다. T2 편집 9행 중 스캔 대상 안에 있는 것은:

| T2 행 | 파일 | 스캔 사유 |
|---|---|---|
| 1 | `turn/DirtyState.kt` | `engine/turn/` |
| 2 | `turn/ChangeRecorder.kt` | `engine/turn/` |
| 3 | `run/TurnRunService.kt` | `engine/run/` |
| 4 | `config/DaemonLoopConfig.kt` | 가드 테스트가 명시 추가 |
| 5 | `world/WorldEventContextFactory.kt` | `engine/world/` |
| 6 | `world/WorldActionContext.kt` | `engine/world/` |
| 9 | `run/TurnDaemonCommandDispatcher.kt` | `engine/run/` |

**9행 중 7행이 스캔 대상 안이다.** 그런데 `:1026`의 부수 제약("타입 이름이 `Repository`/`Reader`로 끝나면 안 된다")은 그중 **`DaemonLoopConfig.kt`·`WorldActionContext.kt` 두 개만** 이름을 적는다. 나머지 5행 — 특히 v2 싱크 필드를 실제로 들게 되는 `TurnRunService.kt`(3행) — 은 같은 제약의 사정권인데 문서에 없다.

여기에 하나 더: `DaemonWriteGuard.kt:29-34`의 `writePathPackages` = `opensamguk/engine/{flush,turn,run,nationbulk}`. **T2 1·2·3·9행이 전부 이 4개 패키지 안**이고, `DaemonNoEntityManagerTest`가 이 클래스들의 상수풀을 스캔한다. v2 싱크 호출을 `TurnRunService.flushWithGeneration`(`:402-408`) 안에 두면 **v2 쓰기 경로의 진입점이 one-daemon-write 가드가 감시하는 패키지 안에 놓인다.** 실제 위반이 되리라는 뜻은 아니다(가드는 `EntityManager` 참조를 본다) — **문서가 그 사실을 한 번도 언급하지 않는다**는 것이 지적이다. 개정 5차의 자기 진단("확장점에서 출발해 네 바퀴 실패했으니 메커니즘에서 역추적한다")을 그대로 적용하면, 아키텍처 테스트 메커니즘의 역추적은 **신규 파일 쪽만 끝났고 편집 파일 쪽은 2/7만 끝났다.**

부분 충족은 N이므로 문항 7은 N이다.

---

## 3. 날조 재검증 — 열어서 대조한 인용 전량

**결과: 날조 0건.** 아래는 이번 채점에서 파일을 열어 행 단위로 대조한 인용이다. `~` 표시만 범위가 다소 느슨하고, 내용은 일치한다.

**아키텍처 테스트·카탈로그 (블록 A)**
- `HotColdWorldCatalogGuardTest.kt` 421행 전문 — `snapshot loader data reads are cataloged`가 `Regex("""private fun (load[A-Z][A-Za-z0-9]*|resolveActiveGame)\b""")`로 뽑은 메서드 이름 집합을 `assertEquals(loaderMethods, HotColdCatalog.snapshotMethodNames)`로 봉인 ✅ · `snapshot loader SQL calls stay inside cataloged helpers`가 `jdbc.(query|queryForObject)`의 enclosing private 메서드를 카탈로그 소속으로 강제 ✅ · `:163-164` `runtimeCallKeys`/`runtimeCallCounts` `assertEquals` ✅ · `:206` `runtimeDirectSqlBoundarySources` `assertEquals` ✅ · `:34-44` `runtimeSourceFiles()`가 8개 디렉터리 + `DaemonLoopConfig.kt` ✅
- `HotColdCatalog.kt:135-144` = `runtimeSourceDirectories` **정확히 그 행, 정확히 8개** ✅ (`logic/` = T1 확인)
- `DaemonWriteGuard.kt:29-34` = `writePathPackages` 4개 ✅ · **`engine/v2`는 8개·4개 어디에도 없다 — 저자의 격리 주장 참** ✅
- `TruncateContract` 리포 전수 참조 — main 소스셋은 정의 파일 `TruncateContract.kt:33` 하나뿐, 소비자는 전부 테스트 ✅ **프로덕션 소비자 0 = 참, r4의 MINOR-C가 옳았다**

**부팅·설정 (블록 B)**
- `app/game-api/.../application.yml:8-10` = `jpa:` / `hibernate:` / `ddl-auto: validate` ✅ · `:14` `locations: classpath:db/migration` ✅
- `app/game-engine/.../application.yml` 동일 행에 동일 값 ✅ ("양쪽 `:14`" 참)
- `GameApiApplication.kt:8-10` `@SpringBootApplication` / `@EntityScan([infra, gameapi.read, gameapi.owner])` / `@EnableJpaRepositories(동일)` **축자 일치** ✅
- 인구조사 재현: `read/` 파일 **31**, Spring Data 계열 **23**, game-api main의 `JdbcTemplate` 사용 파일 **정확히 4개**(`config/ReadBarrierDataSourceConfig.kt`·`consistency/PrimaryWorldVersionReadRepository.kt`·`controller/SelectPoolController.kt`·`reserve/CommandReserveService.kt`) ✅ **전부 일치**
- `ReadBarrierDataSourceConfig.kt` — 래퍼 `ReadBarrierJdbcTemplate` `:10-21` ✅, `@Configuration` `:23` ✅, Hikari 조립 `:33-43` (제안서 `:37-45`/`:43-45`는 ~2행 느슨, 내용 일치), **`DataSource` 빈 미노출 = 선례 주장 참** ✅

**설계 변경 6건 (블록 C)**
- `TurnRunService.kt:401-408` `flushWithGeneration`(prepare→`flushExecutor.flush(payload)` `:404`→commit) ✅ · `:527` `DatabaseHooks.toFlushPayload` ✅ · `:440` 생성 지점 ✅ · 후행 nullable 기본 파라미터 선례 다수 ✅
- `WorldActionContext.kt:111-114` = 후행 nullable 기본 파라미터 **정확히 4개**(`auctionRepository`·`auctionBidRepository`·`archiveHistoryReader`·`statisticSnapshotReader`) ✅ · 생성 4지점(`WorldEventContextFactory.kt:72` · `WorldActionContext.kt:920` · `MonthlyPostUpdateHook.kt:198`·`:322`) ✅ **"3/4 무편집" 참** — `:72`가 실제로 4개 중 2개만 named로 넘기고 있어 후행 기본값 추가가 안전함을 직접 확인
- `WorldEventContextFactory.kt:23-31`(cast-ctx 크래시 / env-read 무음 no-op 두 실패 양식) ✅ · `:72` `val wctx = WorldActionContext(` ✅ · `:82-88` env-read 키 7줄 ✅
- `01-backbone-micro.md:76`(0A-b `V2_ENABLED`+`v2-sandbox` route/bean 게이트) ✅ · **`:77`(0A-c v2 Flyway location 분리) = 실재하는 선행 확장점, 신설 아님** ✅ · `:190` `3-b operation_participants` ✅
- 게이트 ⑤(`:1122-1126`, `app/*/src/main/resources/` 무수정) — **문서에 실제로 신설돼 있다** ✅ (산문만이 아니라 게이트 블록 자체)
- `DaemonLoopConfig.kt:108` `JdbcFlushExecutor(jdbc, TransactionTemplate(transactionManager))` ✅ · `:229` · `:269` `WorldEventContextFactory.create(` ✅ · `:440` `return TurnRunService(` ✅

**T2 표 자체 (블록 D)**
- `:1051-1062` = **10행** ✅ · `:1066-1072` 내려간 행 = **5행**(`DatabaseHooks.kt`·`JdbcFlushExecutor.kt`·`TruncateContract.kt`·`InMemoryTurnWorld.kt`·`WorldSnapshotLoader.kt`) ✅ · 신설 3행(`TurnRunService.kt`·`DaemonLoopConfig.kt`·`WorldEventContextFactory.kt`) ✅ **저자 보고와 일치**

**나머지 인용**
- `EventStore.kt:169`(`ProcessIncome","gold"`)·`:171`·`:180`·`:188`(`"rice"`)·`:190`·`:197` — **여섯 행 전부 정확** ✅
- `EventAction.kt:61-64`(register)·`:70-74`(create)·`:72`(`존재하지 않는 Action입니다 :${raw.name}`) ✅
- `EngineEventConfig.kt:79-81` `@Bean fun eventActionFactory(): EventActionFactory = WorldActions.register(EventActionFactory())` **축자 일치** ✅
- `ScenarioImporter.kt:831` `INSERT INTO event (world_id, target_code, priority, condition, action)` ✅
- `ProcessIncome.kt:51-55` `IncomeGeneral(id, dedication, officerLevel)` — **`cityId` 없음 확인** ✅ · `:215-219` `ProcessIncomeContext` ✅
- `RaiseDisaster.kt:56-62` `DisasterCity(cityId,name,state,secu,secuMax)` — **`garrison` 없음 확인** ✅ · `:104-127` ✅ · `:227` `env[ENV_WORLD] as? DisasterWorldView ?: return` ✅
- `InstantActionRegistry.kt:28-42` 5단계 계약 — **`:31`·`:33`·`:35`·`:38`·`:39` 다섯 단계 행 번호 전부 정확** ✅
- `CommandWireMapper.kt:43`(`intakeCodes`)·`:127`·`:147`(`if (code !in intakeCodes) return null`)·`:140-149`(`toCommand` 진입~`when`) ✅
- `TurnDaemonCommandDispatcher.kt:326`(`fun dispatch(...) = when (command)`)·`:397`(`else -> null`) ✅
- `WireJson.kt:11-16` — `serializersModule` 다형 등록 **없음 확인**, U9 유효 ✅
- `GameApiSecurityConfig.kt:47` `.anyRequest().permitAll()` ✅ (파일 실제 경로는 `gameapi/security/`, 제안서는 클래스명만 인용해 불일치 아님)
- `GetStatValue.kt:53`·`:54-64`·`:89-91` ✅ · `OfficerLevelModule.kt:27-36`·`:46-47` ✅ · `ActionPipeline.kt:24`(aux)·`:90`(fold) ✅ · `FrontInfoController.kt:394` ✅
- `LogicEntities.kt:64`("There is NO `city.tech`")·`:67`(`data class City(`)·`:106-107`(Nation gold/rice) ✅
- `help__start__peq__peq.md:46`·`:61`, `help__start__basic__myostart.md:116`·`:119`·`:122`·`:125` ✅
- `plans/2026-07-17-v2-ticket-backlog/README.md:55-65` — 행 합계 **14** ✅
- `§11` UNKNOWN 행 = **U1~U11 = 11개** ✅ (보고의 "9 → 11" 일치)

---

## 4. 4차 채점 지적별 대응 판정

| r4 지적 | 저자 대응 | 내 판정 |
|---|---|---|
| **MAJOR-A** — v2 원장 SELECT를 `BootstrapConfig`가 무조건 등록하는 `WorldSnapshotLoader` 안에 두는 것은 게이트로 막을 수 없다 | 제3의 길 — `WorldSnapshotLoader`·`InMemoryTurnWorld`·`BootstrapConfig`를 **경유하지 않는** 신규 경로로 재설계, T2 2행 삭제 | **닫힘.** 그리고 저자의 근거가 r4의 지적보다 강하다 — 그 경로는 게이트로 막을 수 없는 정도가 아니라 **가드 테스트가 물리적으로 봉인해 애초에 코드를 넣을 수 없다.** 파일 전문으로 확인했다 |
| **MAJOR-B** — `@EntityScan` 화이트리스트 확장 시 부팅 실패 | (b) v2 read는 `JdbcTemplate`으로 못 박고 T2 편집 0 유지 | **닫힘.** `ddl-auto: validate`(`:10`) + 화이트리스트(`:9-10`) + 선례 4개를 전부 실측 대조했고 추론이 타당하다. (a)·(c)가 v1 부팅을 깬다는 판정도 옳다 |
| **MINOR-C** — `TruncateContract` 행의 inert 논거가 그 파일에 적용되지 않는다 | 행 삭제 + "프로덕션 소비자 0" 실측 | **닫힘.** 리포 전수 grep으로 재현했다. r4가 옳았고 저자가 그것을 인정한 것도 옳다 |
| **UNKNOWN-5** — v2 DataSource 계획이 문서에 없다 | 서비스별 신규 `@Configuration` + 자체 Hikari + 래퍼 타입만 노출, `@ConditionalOnProperty` 0A-b 게이트 | **형식은 닫혔으나 정당성이 미결.** 선례(`ReadBarrierDataSourceConfig`)는 실재하고 "`DataSource` 빈을 하나 더 노출하면 v1이 깨진다"는 경고도 옳다. 그러나 **두 번째 풀이 필요한지 자체가 CRITICAL-1의 갈래에 달려 있다** — 갈래 A라면 불필요하고, 그때 U11도 함께 사라진다 |
| **절차 재실행 요구** — 다른 출발점에서 T2 누락을 다시 훑어라 | 메커니즘 5축 역추적(등록·게이트·DataSource/트랜잭션/마이그레이션·wire/직렬화·아키텍처 테스트) | **절차는 실질적으로 개선됐고 결과도 크다**(12행 중 8행 교체). 그러나 **역추적이 신규 파일 쪽에서 멈췄다** — 편집 9행 중 7행이 가드 스캔 대상 안인데 그 영향은 2행분만 분석됐다(MAJOR-1) |

**저자가 r4를 반박한 대목의 판정 — 저자가 옳다.**
- "개정 4차는 '생성자를 넓히면 T2가 3파일 더 열린다'고 썼으나 과장이었다"(`:980`) — **참.** `:111-114`의 후행 nullable 기본값 4개와 `:920`의 위치인자 호출을 직접 봤고, 후행 기본 파라미터 추가로 3지점이 무편집으로 남는다.
- "T1은 신규 파일을 허용한다"가 **무조건 참은 아니다**(`:1026`) — **참.** 8개 스캔 디렉터리 안의 신규 파일은 `assertEquals` 기대값(= T1 파일)을 깬다. 이 반전은 저자가 스스로 찾아낸 것이고 정확하다.
- `CommandReserveService` 무편집(`:992`) — **참.** `reserveInternal`의 인테이크 분기가 `CommandWireMapper.toCommand`만 보고 `CommandRegistry`를 거치지 않음을 확인했고, `command_inbox`의 CHECK 제약은 `command_kind`(IMMEDIATE/RESERVED_TURN/QUEUE_MUTATION)에만 걸리며 `action_code`는 자유 문자열이다(`V34__command_inbox.sql:17-18`) — 새 v2 코드에 v1 DB 마이그레이션이 필요 없다.
- `TurnDaemonCommandWireTest` 코퍼스가 v2 variant를 막지 않는다(`:1035`) — **참.** 리포 전체에 `sealedSubclasses` 사용 0건이고, 코퍼스 검사는 테스트 리소스 기반이다.

---

## 5. 개정 5차의 설계 전환은 정당했는가 — 별도 판정

**판정: 읽기 경로에 대해서는 정당하고, 쓰기 경로에 대해서는 정당성이 아직 성립하지 않는다.**

**정당한 부분 (3/5 삭제).** `WorldSnapshotLoader.kt`·`InMemoryTurnWorld.kt`·`TruncateContract.kt` 세 행의 삭제는 **파일 근거로 강제된 것**이다. 가드 테스트 전문을 읽었고, 로더에 `private fun loadV2CityLedger()`를 더하면 `assertEquals`가 즉시 깨지며, 그것을 통과시키는 유일한 길이 `logic/`(T1) 수정이라는 사슬에 빈틈이 없다. 개정 4차의 T2 6·7행은 **실제로 물리적으로 불가능했다.** 저자가 "4차 설계는 틀렸다"고 선언한 것은 정확하고, 이 정도 근거를 들고 온 자기부정은 채점자가 지지해야 한다. 특히 `TruncateContract` 삭제는 r4 자신의 지적을 받아들인 것이므로 이견이 없다.

**정당성이 미결인 부분 (2/5 삭제 + 파생 3건).** `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 두 행의 삭제는 오직 `:1031`의 한 문장("`DaemonLoopConfig.kt:108`이 **v1** 템플릿으로 만든다") 위에 서 있고, 그 문장은 **v2 월드가 v1 DB를 primary로 갖는다는 미선언 전제**를 요구한다. 그 전제는 같은 문서 `:966`이 부정한다. 그리고 이 두 삭제에서 (i) 두 번째 v2 Hikari 풀, (ii) U11(교차 원자성 부재), (iii) "v1 command_inbox를 그대로 탄다"가 파생됐으므로, 전제가 갈래 A로 확정되면 **셋 다 함께 무효가 된다.**

**요약하면 — 이번 전환은 "4차가 보지 못한 벽을 찾아낸 것"으로서는 성공했고, "그 벽을 우회하는 새 경로를 증명한 것"으로서는 아직 미완이다.** 읽기 쪽은 벽도 우회로도 증명됐고, 쓰기 쪽은 벽의 존재 자체가 미확정이다.

---

## 6. `fix-required` 요구사항

### CRITICAL

- **C1. v2 월드의 프로세스·DataSource 토폴로지를 한 문장으로 확정하고, 그 확정에 맞춰 §7.1-2를 다시 정합화하라.**
  - 갈래 A(권장 — ADR-LITE-018 문언 및 `:966`과 일치)를 택하면: `:1031`·`:1069`의 "v1 템플릿" 논거를 철회하고 `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 두 행을 **T2 표로 되돌릴지** 재판정하라(되돌릴 경우 inert 논거는 1·2행과 동일한 "빈 컬렉션 ⇒ step 미진입"). 두 번째 Hikari 풀과 **U11을 철회**하거나, 남긴다면 "ADR-LITE-018의 필연"이 아닌 **선택으로서의 근거**를 새로 대라. `:1046`의 "v1 `command_inbox`"도 정정하라.
  - 갈래 B를 택하면: `:966`의 이벤트 분리 전제가 무너지므로 **T2 6행의 v1-inert 근거 (i)를 폐기하고**, v1 월드가 v2 leaf를 실행하지 않음을 다른 메커니즘으로 증명하라(예: `event` SELECT에 `world_id` 필터 추가 — 단 저자 자신이 `:966`에서 이를 "v1 동작 변경 위험, 범위 밖"으로 배제했다). 증명이 서지 않으면 갈래 B는 방어선 1 위반으로 기각된다.

### MAJOR

- **M1. 편집 대상 9행 전부에 대해 아키텍처 가드 영향을 적어라.** 최소한 1·2·3·5·6·9행이 `HotColdCatalog.runtimeSourceDirectories` 8개 안(또는 명시 추가 대상)임을 표에 명시하고, 각 행이 `runtimeCallKeys`/`runtimeCallCounts`/`runtimeDirectSqlBoundarySources` 세 `assertEquals` 중 무엇을 건드릴 수 있는지 한 줄씩 붙여라. `:1026`의 "이 한 줄 규칙이 가드 위반 가능성 전체를 닫는다"는 **신규 파일에 한정**한다고 범위를 좁혀 쓰라.
- **M2. `TurnRunService.kt`·`TurnDaemonCommandDispatcher.kt`가 `DaemonWriteGuard.writePathPackages`(`engine/{flush,turn,run,nationbulk}`) 안이라는 사실을 명시하고**, v2 싱크 호출이 `DaemonNoEntityManagerTest`(상수풀 스캔)에 걸리지 않는 이유를 한 줄로 적어라. 아울러 v2 싱크·store 타입 이름이 `Repository`/`Reader`로 끝나지 않아야 한다는 제약을 **두 파일이 아니라 스캔 대상 전 행**에 적용된다고 다시 쓰라.
- **M3. U11의 disposition을 C1 확정 후 다시 판정하라.** 현재 문언("ADR-LITE-018에서 나오는 **필연**이고 이 설계안이 만든 결함이 아니다")은 갈래 A에서 **거짓**이 된다.

### MINOR

- **m1.** `:654`의 `ponytail:` "알려진 천장" — "`:63`의 `clamp(0,255)`가 파이프라인 앞에 있으므로 관계 보정은 255를 넘길 수 있다(255 → 263)"는 **사실이 아니다.** `GetStatValue.kt:65`에 파이프라인 **직후** 두 번째 `v = clamp(v, 0.0, maxLevel.toDouble())`(PHP `:394`)가 있어 재클램프된다. "v1 `OfficerLevelModule`의 `+lbonus`도 같은 성질"이라는 부연도 같은 이유로 틀렸다. 설계를 더 안전한 쪽으로 정정하는 항목이므로 위험은 없으나, 문서가 없는 결함을 자백하고 있다.
- **m2.** `:978`의 "그 인터페이스는 `incomeNations()`/`applyIncome()` 둘뿐"은 부정확하다 — `ProcessIncomeContext`(`:215-219`)에는 `val pipeline`도 있다. 실질 논거(`IncomeGeneral`에 `cityId`가 없다)는 그대로 성립한다.
- **m3.** `ignoreDefaultEvents`의 **두 번째 읽기 지점**을 문서가 명명하지 않는다. §7.1-2 ⑲는 `ScenarioImporter`/`ScenarioJson` 시드 분기만 다루지만, 런타임은 `EngineEventConfig.kt:41-45`가 **`world_state.config->>'ignoreDefaultEvents'`**를 읽는다. v2 월드의 `world_state.config`에 그 값이 실리는 경로를 한 줄 적어 두는 편이 안전하다(T2 편집은 발생하지 않을 것으로 보이나 확인 필요).
- **m4.** `ReadBarrierDataSourceConfig.kt`의 Hikari 조립 범위 인용 `:37-45`/`:43-45`는 실제 `:33-43`보다 ~2행 밀려 있다. 날조가 아니라 범위 오차다.

---

## 7. 확인하지 못한 것 (UNKNOWN — 추측하지 않는다)

- **UNK-A. 어느 갈래가 저자의 실제 의도인지.** 문서에 진술이 없어 판정 불가. CRITICAL-1은 "둘 다 참일 수 없다"까지만 증명하고 어느 쪽이 옳은지는 말하지 않는다.
- **UNK-B. `@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두었을 때의 컴파일 결과(제안서 U9).** 컴파일을 돌리지 않았으므로 나도 확인하지 못했다. 저자가 UNKNOWN으로 남긴 것은 정직하다.
- **UNK-C. v2 원장이 재시작 시 어떻게 재수화되는가.** `RehydrateService.kt`는 `engine/turn/`(스캔 대상) + `NamedParameterJdbcTemplate` 보유 = `runtimeDirectSqlBoundaries` 등재 파일이다. v2 store가 자기 DB에서 스스로 재적재하면 이 파일은 열리지 않겠지만, **문서가 그 판정을 적지 않았다.** T2 누락으로 단정하지 않고 UNKNOWN으로 남긴다.
- **UNK-D. Redis 스트림/컨슈머 그룹·SSE(`RealtimePublisher`)·헬스체크·메트릭 등록점.** `engine/redis/`는 스캔 대상 8개에 포함되나 v2가 이 경로를 편집한다는 근거를 찾지 못했다(커맨드는 기존 envelope를 그대로 탄다). **누락 없음으로 보이나 단정하지 않는다.**
- **UNK-E. `logic/`·`common/` 골든 게이트 실측.** 이 설계안은 코드를 바꾸지 않으므로 게이트를 돌리지 않았다. 게이트 green 여부는 착수 시점 DoD의 몫이다.

---

## 8. 총평

이 개정은 **앞선 네 바퀴와 질적으로 다르다.** 이전 실패는 "목록을 짧게 적었다"였고 대응은 "몇 개 더 적었다"였다. 이번에는 **절차의 출발점 자체를 뒤집었고**(확장점 → 메커니즘), 그 결과 자기 설계의 절반을 스스로 폐기했다. 그리고 그 폐기의 근거가 산문이 아니라 **`assertEquals` 한 줄과 그 기대값이 사는 파일의 티어**라는 점에서, 이것은 반박 불가능한 종류의 증거다. 인용 정확도도 이례적으로 높다 — 50개 가까운 `path:line`을 열었는데 어긋난 것이 범위 2행 오차 하나뿐이다. 2차 개정의 날조 전례는 완전히 청산됐다고 본다.

그럼에도 통과시키지 않는다. 이 설계안은 **"v2 원장이 v1 DB에 써진다"는 한 문장을 축으로** 다섯 행 중 두 행을 내리고, 두 번째 커넥션 풀을 세우고, 새 UNKNOWN 하나를 만들었다. 그런데 같은 문서가 **28행 위에서** "v1/v2 이벤트 분리는 별도 DB 결정에 전적으로 의존한다"고 쓴다. 한 프로세스에 DataSource가 하나인 이상(직접 확인했다) 이 둘은 공존할 수 없고, 어느 쪽을 살리든 T2 표의 다른 쪽이 무너진다. 문항 7이 요구하는 것은 "T2 목록이 길다"가 아니라 **"v1 패러티가 안 깨진다는 증명"**이고, 전제가 자기모순인 논증은 증명이 아니다.

고칠 것은 많지 않다. C1은 **한 문장을 정하는 일**이고, 그 문장이 정해지면 M1~M3와 파생 3건이 기계적으로 따라온다. 지금 문서의 정밀도라면 한 바퀴로 충분하리라고 본다.

**VERDICT: `fix-required` · 9/10 · 문항 7 미충족.**
