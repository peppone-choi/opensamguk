# REVIEW round-3 r6 — 도시 중심·인맥(꽌시) 설계안 6차 개정 독립 채점

> **VERDICT: `cleared` · 총점 10/10**
> 채점 대상: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` (개정 6차)
> 시험지: `docs/loops/v2-planning-2026-07-12/GOLDENSET-round3-city-guanxi.md` (부분 충족 = N)
> 저자 보고: `REVISION-round3-r6.md`
> 채점자: reviewer6 (독립 · 저자 아님 · 앞선 5명과 별개)
> 이전 채점: r1 5/10 · r2 6/10 · r3 9/10 · r4 9/10 · r5 9/10 (r3~r5의 유일한 N = **문항 7**)
> 채점일: 2026-07-25

---

## 0. 판정 한 문단

**날조는 없다.** 약 75개 `path:line` 인용을 열어 대조했고 **한 건도 날조가 아니었다** — 어긋난 것은 범위 시작이 1행 밀린 인용 하나와 PHP 참조가 두 함수 중 하나만 커버한 것 하나뿐, 둘 다 내용은 정확하다. 그리고 **문항 7이 네 바퀴 만에 닫혔다.** 개정 6차는 앞선 다섯 바퀴와 종류가 다르다 — 5차 채점의 CRITICAL-1을 "둘 중 하나를 고르는" 방식으로 봉합하지 않고, **5차 채점(내 앞 채점자)의 전제 문장이 거짓이었음을 코드로 반박**한 뒤 그 위에 배포 토폴로지를 실측 확정했다. 나는 그 반박이 **옳다는 것을 파일 두 개를 열어 확인했다.** `DaemonLoopConfig.kt:104-108`의 `NamedParameterJdbcTemplate` 파라미터에는 **`@Qualifier`가 없다.** 오토컨피그 단일 DataSource이고, 어느 DB를 가리키는지는 프로세스 env가 정한다. 5차의 "v2 step은 v1 DB에 쓴다"는 명제는 성립하지 않았고, 거기서 파생된 4건(2행 강등·두 번째 Hikari 풀·`TurnRunService.kt` T2 등재·U11)이 전부 무효라는 저자의 결론도 옳다.

동시에 이번 채점의 핵심 임무를 적는다. **나는 저자가 쓰지 않은 출발점 세 곳에서 T2 누락을 독립 재수색했고 — flush 패키지 상태기계 / 시나리오 시드·임포터 / wire 직렬화·코퍼스 — 하나도 찾지 못했다.** 문항 7을 세 바퀴 연속 무너뜨린 것이 "목록 누락"이었으므로 이번에는 목록을 믿지 않고 다시 셌다. 저자가 적지 않은 것으로 내가 찾아낸 것은 **v1 패러티를 건드리지 않는 v2측 완결성 결함 4건**뿐이고, 전부 MINOR다. 문항 7이 요구하는 것은 "T2 목록이 길다"가 아니라 "v1 패러티가 안 깨진다는 증명"이며, 그 증명은 이번에 성립한다.

---

## 1. 10문항 채점

| # | 요구 | 판정 | 근거 (직접 확인) |
|---|---|---|---|
| 1 | "도시 중심" = 자원 소유 주체, 운영자 자기규정 근거 | **Y** | `help__start__peq__peq.md:46` 원문 대조 — "묘섭은 제 3의 길로 City-oriented(도시지향) 삼모전을 목표로 삼고 있습니다" **바이트 일치**. `:61` "기존의 국가에서 다루던 금과 병량이 도시로 이전된 점과 도시마다 도시병사가 상주한다는 점" **일치**. `myostart.md:116`·`:119` **일치**. 파일명도 정확(`help__start__peq__peq.md`, `help__start__basic__myostart.md`) |
| 2 | 도시 소유 자원·도시병사 상주 스키마·판정, 병사 0 → 공백지화 deterministic | **Y** | §2. `LogicEntities.kt:67-92`의 `City`에 `gold`/`rice` 부재, `:64` "There is NO `city.tech`" **일치**. `IncomeTick.kt:122 return cityIncome * (taxRate / 20)` = "합산 후 세율 1회 곱" **확인** ⇒ Σ 불변식 DoD 철회는 옳은 판정이며 divergence 선언이 정직하다 |
| 3 | 인사권·배치효과·감시·자원분배 4축, `officer_level`+`officerCntByCity` 위에 얹기 | **Y** | §3. 평행 신축 0, 기존 축 위 가산. 5차 대비 변경 없고 변경할 이유도 없다 |
| 4 | 관계망 = 독자 추가 명시 · **능력치 보정** · emergent/PRESET 구분 · v2 전용 stat source · 출처 규율 | **Y** | §4.6-4.7. **사용자 결정(유비·관우·장비 의형제 = 능력치 버프)이 희석되지 않았다** — 통무지 3스탯 가산, ±2/선언 ±6, 축별 클램프 후 fold 꼬리 적용이 그대로다. m1 정정(아래 §4)이 **상한 근처에서만** 실효를 깎으므로 결정 자체를 약화시키지 않는다 |
| 5 | 임원진 체류 효과 6종 도입 여부 판정 + 판정 지점·중첩 규칙 | **Y** | §5 + §5-bis. `positionrole.md:191-201` 6종 전문 대조 — 사도(세금 20%)·사공(병종기술 +2)·대사농(병량 20%)·표기장군(사기)·거기장군(도적 50%)·위장군(계략회피 50%) **바이트 일치**. L1/L2/L3 3층 분리의 근거 `imperial-court…design.md:6`("비범위: v1 `officer_level`…")·`:302`("universal enum의 고정 효과를 금지") **일치**. 국가레벨 7 칭호 8종이 `formatOfficerLevelText.ts`에 실재하고 **대사농만 없다**는 대조표도 정확 |
| 6 | 도시 특색 9종·규모 게이트·지역병종 채택/보류 판정 | **Y** | §6. **"9종은 실제 8종 + 무특색"이라는 정정이 원문에서 확인된다** — `map.md:165`(이름 9개, 방어·없음 포함) vs `optimizedomestic.md:135-149`(8행). 발현 조건 `optimizedomestic.md:155`·`optimizebattle.md:270`(도시기술 900, 특수·성벽 제외) **일치**. 게이트 2종 `establishnation.md:95`·`sellandbuy.md:78` **일치**. 지역병종 `intermediatebattle.md:166,172,193` **일치**. 금 특색 효력이 두 문서에서 어긋난다는 지적도 실제로 어긋난다(`:135` "매년 1월" vs `:277` 시점 없음) — UNKNOWN 처리 정직 |
| 7 | **v1 패러티 불변 증명 — v2 전용 스키마·경로** | **Y** | **네 바퀴 만에 충족.** 근거는 §2·§3·§5에 전부 적었다. 요약: (a) 토폴로지 확정이 실측으로 성립(§2), (b) T2 11행 표가 실재하고 "가드 영향" 열이 **11행 전부** 채워져 있다(직접 세었다), (c) `writePathPackages` 소속 행을 **내가 독립 재계수해 4개**(1·2·3·10행)로 저자와 일치, (d) T1 벽 두 개(`CommandRegistry` 하드코딩 `when`·`TurnDaemonCommand` 74 중첩 variant)를 저자가 **스스로 찾아** 각각 기각/U9로 처리, (e) 내 독립 재수색 3회에서 패러티를 건드리는 누락 **0건** |
| 8 | 각 신규 요소가 LEDGER 규칙 4를 만족함을 항목별로 | **Y** | §8 표가 R1~R6 전부에 결정·판정(draw 수)·상태 변화·replay/log를 채운다. R4 행의 `TurnDaemonCommandResult(ok/reason)` + `pollCommandResult` 규약(OPENSAM-13/135)이 인테이크 202 위조 금지를 정확히 반영 |
| 9 | 오픈 경로 최소 부분집합 + 티켓 수량 | **Y** | 기준선 14를 `plans/2026-07-17-v2-ticket-backlog/README.md:55-65`에서 **직접 재계수** — 4+1+1+2+3+1+1+1 = **정확히 14**. 제안서는 **20 단일값**이며 §9.2·§9.4·§9.5·자기채점 9행이 전부 20으로 일치한다. "20→21" 언급은 R2 분해 가능성일 뿐 권고 수량은 20 단일값으로 못 박혀 있고, U9 disposition도 "어느 쪽이든 오픈 경로 수량 20은 변하지 않는다"로 조건부 부활을 차단한다. **조건부 항목 0** |
| 10 | G0 대체 아닌 선행 · ADR-LITE-019 유예와 무충돌 | **Y** | §10 + §9.5. `.ai/decisions.md:178-198` 대조 — ADR-LITE-018의 별도 DB(`opensamguk_v2`)·플래그 공존 금지, ADR-LITE-019의 G0/C-track 오픈 후·`OPENSAM-149` 선행·GOLDENSET 4·8 유예가 **문서에 실재**하고 제안서가 그중 무엇도 되돌리지 않는다 |

**총점 10/10 · `cleared`.**

---

## 2. 토폴로지 확정이 정당한가 — 별도 판정

**판정: 정당하다. 다섯 근거 중 넷은 무조건 성립하고, 하나(γ)는 조건이 하나 붙지만 확정을 흔들지 않는다.**

| 근거 | 저자 주장 | 내 대조 결과 |
|---|---|---|
| **α** | `WorldIdConfig.kt:11`이 `OPENSAMGUK_WORLD_ID`로 `EngineProcessWorld` 하나를 만들고 그 값이 프로세스 전역 상수 | **참.** `EngineProcessWorld.kt`는 전문 7행, 필드 하나(`val worldId: WorldId`), 컬렉션·맵 없음. `application.yml`에 **기본값 없음**을 양쪽에서 확인(game-engine `:36`, game-api `:30`) |
| **β** | DataSource·Flyway가 전부 env 주입이라 프로세스마다 다른 DB를 향할 수 있다 | **참.** `GAME_DATABASE_URL` 주입(`:5`), `flyway.locations: classpath:db/migration`(`:12-14`) |
| **γ** | `ScenarioSeedCoordinator.kt:37-49`가 "한 DB = 한 월드"를 **코드로 강제**한다 | **참, 단 조건부.** `ids.isEmpty()` → 시드 / `ids == listOf(expectedWorldId.value)` → skip / **그 외 전부 `error("Scenario seed requires exactly configured world_state.id=…; found $ids")`**. 문장까지 일치. **단서**: 이 경로는 `SeedBootstrap.ensureSeeded`(`ScenarioSeedRunner.kt:69-104`)를 통과해야 도달하는데 `:70-73`의 `if (!seedEnabled) { … return false }`가 코디네이터보다 **먼저 반환**한다. `SCENARIO_SEED_ENABLED=false`인 프로세스는 검사 자체를 하지 않는다 (⇒ m-new-3) |
| **δ** | 모든 Redis 정체성이 world-scoped | **참.** `StreamKeys.kt:16-19`(`sammo:$profile:w{id}:turn-daemon:{commands,events}`)·`:23-27`(realtime)·`:33-34`(result) 전부 world-id를 키에 넣는다 |
| **ε** | 0A-e/0A-f와 ADR-LITE-018이 별도 DB·프로덕션 v2 0을 이미 못 박았다 | **참.** `01-backbone-micro.md:79`(production compose/s1에서 v2 제거)·`:80`(production context v2 0개 architecture test), `.ai/decisions.md:178-187` |

**그리고 5차 채점을 무너뜨린 반박이 옳다.** `DaemonLoopConfig.kt:104-108`:

```kotlin
@Bean
fun jdbcFlushExecutor(
    jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
): JdbcFlushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(transactionManager))
```

**`@Qualifier`가 없다.** 5차의 "v1 템플릿으로 생성된다"는 명제는 거짓이었고, 그것이 CRITICAL-1 자기모순의 진원지였다는 저자의 진단도 맞다. 파생 판정도 전부 검증했다 — `JdbcFlushExecutor.flush`는 `transactionTemplate.execute { … }` **하나**로 전부를 감싸므로 단일 트랜잭션(U11 철회 근거 성립), `FlushPayload`는 후행 기본값 필드가 즐비한 data class라 필드 1개 추가가 기존 호출부를 건드리지 않으며, `TurnRunService.kt:527`(`DatabaseHooks.toFlushPayload`)·`:404`(`flushExecutor.flush`)는 **이미 존재하는 호출**이라 "안쪽만 넓힌다"는 서술이 정확하다.

**결정적 교차검증 하나.** 5차 채점이 경고한 함정 — "`FlushPayloadConvergenceTest`가 필드 **집합**을 `assertEquals`한다면 6차는 T1/T2 위반" — 을 직접 열었다. 이 테스트는 `payload.updatedNations.size`·`rankWrites`·`kvWrites`·`updatedDiplomacy`·`logEntries`·`statisticInserts`·`yearbookInserts`를 **이름으로 지목해** 검사할 뿐 리플렉션으로 필드 집합을 훑지 않는다. **후행 필드 추가는 inert. 저자가 옳다.**

---

## 3. 날조 재검사 — 대조한 인용

**결과: 날조 0건.** 아래는 실제로 파일을 열어 대조한 목록이다(요약).

**묘섭 위키 15개 파일·20여 인용 — 전부 바이트 일치**
`peq.md:46,51,61` · `myostart.md:116,119` · `positionrole.md:138,191-201,233` · `lookinfo.md:73,166` · `intermediatedomestic.md:72,221-241` · `battlebasic.md:85` · `squad.md:121` · `optimizedomestic.md:133,135,149,155` · `optimizebattle.md:270,275,277,291` · `map.md:159,165` · `establishnation.md:95` · `sellandbuy.md:78` · `intermediatebattle.md:166,172,193` · `othercommands.md:75`. 인용문 문자열·행번호·파일명 모두 정확. 특히 §5-bis.3의 품관 게이트 수치(태수·군사 7품관 / 임원진 6품관 / 승상·대장군 5품관 / 참모 5품관)는 `:221-241` 원문과 **한 글자도 다르지 않다.**

**"세면 틀리는" 인용 3건 — 전부 정확**
- `WorldActions.register` 프로덕션 호출부 **1개**(`EngineEventConfig.kt:81`) + 테스트 **6개**. 저자가 나열한 여섯(`ScenarioBlankUnificationIT.kt:85,214` · `LongSimReplayGateTest.kt:169` · `MonthlyWorldEventSeamTest.kt:69` · `RegNpcActionTest.kt:197,226`)이 **파일·행 단위로 전량 일치**. 리포 전체 grep으로 재확인.
- `EventStore.DEFAULT_EVENTS` **12행**의 위치 `:159,164,178,183,195,200,208,216,224,234,239,244` — `SeedRow(` 발생 위치를 독립 추출한 결과 **12개, 행번호 전량 일치**.
- `TurnDaemonCommand.kt` 중첩 variant **74개** — 4칸 들여쓴 `@Serializable` 74건 / `TurnDaemonCommand()` 상위호출 74건. `sealed class`는 `:14`. **일치**.

**아키텍처 가드 (문항 7 판정의 축)**
`HotColdCatalog.kt:135-144`(스캔 8디렉터리) · `:146-165`(`runtimeDirectSqlBoundaries` — `RehydrateService.kt`의 표 목록 `select_pool,game_kv,ng_auction,ng_auction_bid,ng_betting,message`가 **카탈로그 원문 그대로**) · `HotColdWorldCatalogGuardTest.kt:35-44`(`DaemonLoopConfig.kt` 명시 추가) · `:120-127`(`historyRows` bounded projection) · `:202-206`(집합이 `substringBeforeLast(":")` = **파일 경로 집합** ⇒ 등재 파일에 SQL 추가는 inert, 4행 논거 성립) · `:258-267`(`directSqlSourceFiles` 범위 ⇒ `engine.v2`는 스캔 밖) · `:334-340`/`:390-391`(`hasRepositoryExtension` 암묵 규칙 — **개정 6차가 새로 찾아낸 세 번째 파괴 양식은 실재한다**) · `:394-405`(예외 3종 `bettingInfoReader`·`lastBettingIdReader`·`previousPointReader`) · `DaemonWriteGuard.kt:29-34`(writePath 4패키지).

**기타 코드** `ScenarioSeedCoordinator.kt:37-49` · `ScenarioSeedRunner.kt:47,69-104` · `EngineProcessWorld.kt` 전문 · `WorldIdConfig.kt:10-11` · 양쪽 `application.yml` · `DaemonLoopConfig.kt:104-108,440` · `JdbcFlushExecutor.kt:48,56-57,~2287` · `FlushPayloadConvergenceTest.kt` 전문 · `TurnRunService.kt:404,527` · `DeltaGenerationSession.kt` 전문 · `FlushRecoveryGate.kt` 전문 · `TruncateContract` 소비자 전수 · `RedisCommandStream.kt:165-167` · `RealtimePublisher.kt:25,33` · `WireJson.kt:11-16` · `CommandWireMapper.kt:43,140-149` · `TurnDaemonCommandDispatcher.kt:326,397` · `InstantActionRegistry.kt:28-42` · `CommandRegistry.kt:121,224` · `EventAction.kt:60-64,70-74` · `EngineEventConfig.kt:41,46,57,79-81` · `ScenarioImporter.kt:194,807-818,828-835` · `GetStatValue.kt:25,63,64,65` · `ProcessIncome.kt:215-219` · `RaiseDisaster.kt:56-62,253-259` · `IncomeTick.kt:117-122` · `OfficerLevelModule.kt:39-43,50-66,69-77` · `DomesticHelpers.kt:45,74-78,81,84` · `GameConst.kt:48` · `GetConstController.kt:157` · `GeneralMeta.kt:24-25` · `StatChange.kt:127,133` · `LogicEntities.kt:64,70,86` · `GameApiApplication.kt:8,9-10` · `GameApiSecurityConfig.kt:42-47`(`:47 anyRequest().permitAll()` 확인) · `formatOfficerLevelText.ts` 국가레벨 7 블록 · `docker-compose.yml:155,162-167,170-172` · `docker-compose.production.yml:52,58,63` · `.ai/decisions.md:178-198` · `01-backbone-micro.md:74-81` · `v2-ticket-backlog/README.md:55-65` · `imperial-court…design.md:6,281,302`.

**어긋난 것 2건 — 둘 다 날조가 아니고 무해**
1. `EventAction.kt:61-64`는 실제 `:60-64`(함수 시그니처가 `:60`). 인용 범위 안에 `builders[name] = builder`가 들어 있어 내용은 정확.
2. `getBillByLevel`에 붙은 PHP 참조 `func_converter.php:664-666`은 코드 주석상 `getBill`의 행이고 `getBillByLevel`은 `:668-670`이다. 코드가 둘 다 적고 있으므로 인용을 좁게 딴 것.

**참고(제안서 아님).** `REVISION-round3-r6.md`의 요약표가 game-engine world-id를 `application.yml:30`으로 적었으나 실제는 `:36`(`:30`은 game-api). **제안서 본문에는 이 오류가 없다** — 본문은 game-api `:30`을 맞게 인용한다. 보고서 오식이므로 채점에 반영하지 않는다.

---

## 4. r5 지적별 대응 판정

| r5 지적 | 6차 대응 | 내 판정 |
|---|---|---|
| **CRITICAL-1** DB 토폴로지 자기모순 | 고르지 않고 **실측 확정**(branch A: 한 프로세스 = 한 월드 = 한 DB). 5차의 전제 명제를 거짓으로 반박 | **닫힘.** 반박이 옳다(§2). 자기모순의 두 항 중 하나가 애초에 거짓이었으므로 모순 자체가 소멸 |
| **M1** 편집 9행 중 6행이 스캔 디렉터리 안인데 가드 영향 분석이 2행분 | T2 표에 **"가드 영향" 6번째 열 신설, 11행 전부 기재** | **닫힘.** 직접 세었다 — **11행 × 6열, 빈 칸 0**. 각 행이 `runtimeCallKeys`/`runtimeCallCounts`/`runtimeDirectSqlBoundarySources` 셋 중 무엇을 깰 수 있는지 개별 판정되어 있고, 5·7행처럼 기존 수신자 호출 수가 카탈로그에 박힌 파일은 "기존 seam을 한 번도 더 부르지 않는다"까지 제약이 내려가 있다 |
| **M2** `DaemonWriteGuard`/`DaemonNoEntityManagerTest` 판정 부재 | §7.1-2에 M2 절 신설, writePath 소속 4행 지목 + 상수풀 판정 | **닫힘.** `writePathPackages = {engine/flush, engine/turn, engine/run, engine/nationbulk}`를 열고 T2 11행을 **독립 재계수한 결과 정확히 4행**(1 `DirtyState.kt`·2 `ChangeRecorder.kt`·3 `DatabaseHooks.kt`·10 `TurnDaemonCommandDispatcher.kt`). 저자 계수와 일치. "v2 원장 경로에 JPA 타입 0"이라는 논거도 상수풀 부분문자열 스캔의 성질에 정확히 대응 |
| **M3** `ignoreDefaultEvents`의 두 번째(런타임) 읽기 지점 미명명 | §7.1-2에 `EngineEventConfig.kt:57` 단일 소비 + `ScenarioImporter.kt:194`가 `config`에 싣는 적재 경로 명시 | **닫힘.** `:41`·`:46`이 `jdbc.query` 두 개, `:57`이 `rows.isEmpty()` 분기임을 확인. v2는 `event` 행이 비지 않으므로 값이 읽히고 쓰이지 않는다는 판정도 성립 |
| **m1** `GetStatValue` 클램프 위치 서술 | **뒤집었다** — clamp가 `:63`과 `:65` **두 번**이고 파이프라인은 `:64` | **정정이 옳다.** `v = clamp(v, 0.0, maxLevel.toDouble())`(`:63`) → `pipeline.onCalcStat`(`:64`) → `clamp` 재적용(`:65`), `maxLevel = 255`(`:25`). 관계 보정은 상한을 넘지 못한다. **사용자 결정을 약화시키지 않는다** — 상한 근처(예 무력 252)에서만 실효가 깎이고 일반 구간에서는 선언값 그대로 붙는다 |
| **m2** `ProcessIncomeContext`는 2멤버가 아니라 3멤버 | 정정 반영 | **옳다.** `:215-219` = `val pipeline` + `incomeNations()` + `applyIncome()` **3멤버** |
| **m3** `EngineEventConfig` 런타임 읽기 | M3와 동일 처리 | **닫힘** |
| **m4** `ReadBarrierDataSourceConfig` Hikari 범위 오차 | `:33-43`으로 정정 | **옳다.** `HikariConfig().apply {`가 `:33`, `HikariDataSource(config)`가 `:43` |
| **UNK-C** v2 원장 재수화 판정 부재 | "`RehydrateService.kt` 무편집" 명시 + R1 DoD 문장화 | **닫힘.** `HotColdCatalog.runtimeDirectSqlBoundaries`에 박힌 `RehydrateService`의 표 목록(`select_pool`·`game_kv`·`ng_auction`·`ng_auction_bid`·`ng_betting`·`message`)을 원문 대조 — **저자 인용이 카탈로그 원문 그대로이고 v2 원장 표와 겹치는 것이 하나도 없다** |
| **UNK-D** Redis·SSE 등록점 | "`engine/redis/**` 무편집" + 근거 | **닫힘.** `RedisCommandStream.parseEnvelope`(`:165-167`)가 `TurnDaemonCommandEnvelope.serializer()`로 **variant 열거 없이** 통째 역직렬화, `RealtimePublisher`는 함수 2개뿐이고 이벤트 종류를 열거하지 않는다 |

---

## 5. 독립 T2 누락 재수색 (저자가 쓰지 않은 출발점)

문항 7이 세 바퀴 연속 "목록 누락"으로 무너졌으므로 표를 신뢰하지 않고 **다른 출발점 세 곳**에서 다시 훑었다.

**출발점 ① — flush 패키지의 상태기계·계약 파일이 채널을 열거하는가**
`DeltaGenerationSession.kt`(IDLE/PREPARED 순수 상태기계, 채널 열거 0) · `FlushRecoveryGate.kt`(`retainedPayload: FlushPayload?`를 **불투명하게** 보관, 모드 READY/FLUSH_RETRY/RELOAD_REQUIRED) · `WorldVersionCas`/`WorldWriterFence`(버전만 다룸) — **전부 v2 채널 추가 시 편집 불필요.** `TruncateContract`는 프로덕션 소비자 0(참조는 `TruncateContractTest.kt`와 `VerticalSliceE2EIT.kt` 주석뿐) — **저자의 "내려간 채로 유지" 판정이 옳다.** ⇒ 누락 0

**출발점 ② — 시나리오 시드·임포터가 v2 `event` 12행 저작을 지원하는가**
가장 유력한 후보였다. v2는 `ignoreDefaultEvents = true` + 12행 자체 저작을 전제하는데, 시나리오 JSON에 `events` 배열이 없다면 `ScenarioImporter.kt` 편집이 강제되고 그것은 **표에 없는 T2 12행**이 된다. 열어 보니 `ScenarioJson.kt:67`가 `root["events"]`를 이미 파싱하고 `:297`에 `val events: List<ScenarioEvent> = emptyList()`가, `:299`에 `ignoreDefaultEvents`가 이미 있으며, `ScenarioImporter.insertEvents`(`:806-835`)가 `defaults + scenarioRows + deferredRows`를 그대로 INSERT한다. **⇒ 임포터 편집 0. 누락 없음.**

**출발점 ③ — wire/직렬화 T1 벽**
`common/**`이 T1인데 74개 variant가 전부 `TurnDaemonCommand.kt` 본문 안에 중첩돼 있다 — 이것이 발견되면 T1 정면 위반이다. **저자가 이미 찾아 §7.1-(2) "T1 벽 ②"로 적었고**, 해법(같은 패키지 신규 파일 최상위 서브클래스)의 미검증분을 **U9로 남기고 실패 시 대안 (a)까지 준비**해 두었다. `WireJson.kt:11-16`에 다형 등록이 없다는 것도 사실이고, 코퍼스 테스트가 `sealedSubclasses`가 아니라 테스트 리소스를 본다는 판정도 맞다. 같은 성격의 T1 벽인 `CommandRegistry.kt:121`의 하드코딩 `when` + `:224 else -> RestAction`(미등록 코드 = 조용한 턴 소각)도 저자가 스스로 찾아 **턴-예약 경로를 기각**했다. ⇒ 누락 0

**결론: v1 패러티를 건드리는 T2 누락은 찾지 못했다.** 아래 4건이 내가 찾은 전부이고, 전부 v2측 완결성 문제라 문항 7을 무너뜨리지 않는다.

---

## 6. 잔여 지적 — MINOR 4건 (전부 비차단 · 착수 전 반영 권고)

- **m-new-1. 0A DoD 환경변수 열거(`:1100`)에 `SCENARIO_CODE`·`SCENARIO_DIR`가 없다.** 열거된 것은 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE` 넷이다. 그런데 `docker-compose.yml:171`이 `SCENARIO_CODE: ${SCENARIO_CODE:-scenario_1010}`로 **기본값을 준다.** v2 스택이 이 기본값을 물려받으면 v1 시나리오(`ignoreDefaultEvents` = false)로 시드되어 `DEFAULT_EVENTS` 12행이 그대로 적재되고 v2 leaf 행은 하나도 생기지 않는다 — **부팅은 성공하는데 도시 원장 수입이 아예 돌지 않는 조용한 실패**다. §7.1-2가 확정한 R2 치환 전제가 통째로 무력화되므로 두 변수를 DoD (i)에 추가할 것.
- **m-new-2. `event` 행 저작 서술이 한 갈래 좁다.** `insertEvents`는 `defaults + scenarioRows + **deferredRows**`를 INSERT한다(`ScenarioImporter.kt:827-828`). `deferredGeneralRows(startYear)`는 시나리오 `events` 배열 밖에서 자동 생성되므로 "시나리오 JSON이 `event` 행 집합 **전체**를 저작한다"는 정확히는 참이 아니다. **설계는 무너지지 않는다** — v2가 `V2WorldActions.register(WorldActions.register(...))` 체인으로 v1 leaf 이름을 전부 유지하므로 미등록 예외가 나지 않고 T2 편집도 발생하지 않는다. 서술 정확도만 조정할 것.
- **m-new-3. 토폴로지 근거 (γ)의 범위를 좁혀 적을 것.** `ScenarioSeedCoordinator`의 강제는 `SeedBootstrap.ensureSeeded`를 통과할 때만 발화하고, 그 함수는 `ScenarioSeedRunner.kt:70-73`에서 `seedEnabled`가 false면 코디네이터 호출 **전에** 반환한다. 세 진입점(`ScenarioSeedRunner.kt:47`·`WorldSnapshotLoader.kt:53`·`EngineEventConfig.kt:40`)이 모두 이 한 게이트 뒤에 있으므로 `SCENARIO_SEED_ENABLED=false`면 "한 DB = 한 월드" 검사가 전혀 돌지 않는다. "이미 강제되는 코드 불변식"을 **"시드 활성 부팅에서 강제되는 불변식"**으로 적고, 0A DoD에 v1/v2 양 스택 모두 시드 활성으로 뜬다는 조건을 붙이면 닫힌다. 나머지 네 근거는 이 단서와 무관하므로 **확정 자체는 유효하다.**
- **m-new-4. 신규 파일 열거(`:1153`)에 v2 시나리오 JSON이 없다.** §9.2 R2가 그 산출물을 전제하는데 T1도 T2도 아니고 게이트 ②③ 어디에도 잡히지 않는다. `web/**`처럼 **의도된 공백이라면 사유를 한 줄** 적을 것(파일이 `SCENARIO_DIR` 외부일 수도 있어 계층이 애매하다).

---

## 7. 확인하지 못한 것 (UNKNOWN — 추측하지 않는다)

- **UNK-1. U9 자체.** `@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두었을 때의 컴파일·직렬화 결과. **컴파일을 돌리지 않았으므로 나도 모른다.** 저자가 UNKNOWN으로 남기고 대안 (a)를 준비한 것은 정직하며, 수량 20이 어느 쪽에서도 불변이라는 disposition도 확인했다.
- **UNK-2. U12 자체.** `SPRING_FLYWAY_LOCATIONS` 환경변수 오버라이드가 이 리포에서 실제로 먹는지. Spring Boot 표준 동작이지만 **실측하지 않았다** — 저자도 실측하지 않았다고 명시했다.
- **UNK-3. T2 5행의 메커니즘 설명 한 줄.** "`:104-108`의 `jdbc`가 메서드 호출 없이 생성자에 전달만 되기에 이 파일이 `runtimeDirectSqlBoundarySources`에 없다"는 인과를 가드 구현으로 역추적하지 않았다(파라미터 vs 필드 판정 여부 미확인). **다만 그 설명에서 도출한 제약("jdbc 수신자에 메서드를 부르지 않는다")은 실제 필요보다 엄격한 쪽**이므로 설명이 부정확해도 위험은 생기지 않는다.
- **UNK-4. `TurnDaemonCommand` sealed 본문의 끝 행.** 인용 `:14~:940`에서 시작 `:14`는 확인했으나 종료 `:940`은 대조하지 않았다(파일 1002행). 중첩 74개는 독립 계수로 일치.
- **UNK-5. 게이트 실측.** 이 설계안은 코드를 바꾸지 않으므로 백엔드 게이트·골든 replay를 돌리지 않았다. 게이트 green은 착수 시점 DoD의 몫이다.

---

## 8. 총평

**통과시킨다.** 이유는 세 가지다.

첫째, **저자가 채점자를 반박했고 그 반박이 옳다.** 5차 채점의 CRITICAL-1은 "문서가 두 명제를 동시에 주장한다"였고 그중 하나는 **채점자가 근거로 인용한 문장**이었다. 6차는 그 문장을 파일로 열어 거짓임을 보였고, 나는 같은 파일을 열어 저자가 옳다는 것을 확인했다. `@Qualifier` 한 개의 부재가 다섯 바퀴의 논쟁을 끝냈다. 이 프로젝트에서 요구하는 "증거 기반"의 모범이다.

둘째, **문항 7의 실패 양식이 이번에는 재발하지 않았다.** 세 바퀴 동안 실패는 항상 "표에 없는 파일이 하나 더 있었다"였다. 이번에 저자는 표를 늘리는 대신 **역추적 절차 자체를 규율로 못 박고**(자기채점 절, 실패 모드 0-ter "물리적 제약을 지어냈다" 신설), 자기 설계의 T1 벽 두 개를 **채점자보다 먼저** 찾아 각각 기각/UNKNOWN으로 처리했다. 그리고 나는 저자가 쓰지 않은 출발점 세 곳에서 독립 재수색해 **패러티를 건드리는 누락을 0건** 찾았다. 두 번의 독립 탐색이 같은 집합으로 수렴한 것이 이 라운드에서 얻을 수 있는 최선의 증거다.

셋째, **인용 정확도가 이례적이다.** 75개 가까운 `path:line`에서 어긋난 것이 범위 1행 밀림 하나와 PHP 참조 좁힘 하나뿐이다. 특히 "세면 틀리는" 세 인용 — `WorldActions.register` 테스트 호출부 6곳, `DEFAULT_EVENTS` 12행의 개별 행번호, 중첩 variant 74개 — 이 **전량 일치**했다. 2차 개정의 문자열 날조 전례는 완전히 청산됐다고 본다.

남은 MINOR 4건은 전부 **v2가 제대로 뜨는가**의 문제이지 **v1이 깨지는가**의 문제가 아니다. 그중 m-new-1(`SCENARIO_CODE`)은 조용한 실패를 만들 수 있으므로 0A 착수 전에 DoD 한 줄로 닫기를 권한다. 이들을 이유로 문항 7을 다시 N으로 두는 것은 시험지가 묻는 것("v1 패러티 불변 증명")을 묻지 않는 것으로 바꾸는 일이고, 그것은 채점 실패다.

**VERDICT: `cleared` · 10/10 · 문항 7 충족 (4바퀴 만에 최초).**
