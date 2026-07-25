# REVIEW — round-3 도시 중심·인맥(꽌시) 설계안 4차 채점

> 채점자: reviewer4 (독립 4차, 1~3차 채점자와 별개 인격)
> 채점 대상: `round3-proposal-city-guanxi.md` (개정 4차, 1320행)
> 시험지: `GOLDENSET-round3-city-guanxi.md` (10문항, 부분 충족 = N)
> 저자 보고서: `REVISION-round3-r4.md`
> 이전 채점: r1 5/10 · r2 6/10 · r3 9/10
> 채점일: 2026-07-25

## VERDICT: `fix-required` · 총점 **9/10**

문항 7만 N이다. 3차의 N도 문항 7이었고, 3차 지적(MAJOR-1)의 **개별 사례는 닫혔으나 실패 유형이 닫히지 않았다.** 채점자가 저자의 절차를 다른 출발점에서 다시 돌려 **T2 선언 목록 누락 2건을 새로 찾았다.** 나머지 9문항은 전부 Y이고, **날조 재발은 없다.**

---

## 1. 문항별 채점

| # | 판정 | 근거 (한 줄) |
|---|------|-------------|
| 1 | **Y** | §1.1이 "도시 중심 = 자원 소유 주체"로 정의하고 `help__start__peq__peq.md:46`(City-oriented) · `:61`(금·병량 국가→도시 이전, 도시병사 상주) · `help__start__basic__myostart.md:116,119`를 인용한다. 세 인용 모두 파일을 열어 바이트 대조 완료. |
| 2 | **Y** | §2.1~2.4가 `v2_city_resource`·`v2_city_garrison` 스키마와 병력 0 → 공백지화 전이를 정의하고, `:231-299`가 소유권 이전 순서·타이브레이커·재판정 금지까지 deterministic하게 규정한다. RNG draw 0. |
| 3 | **Y** | 인사권·배치효과·감시·자원분배 4축이 전부 기존 `officer_level` 2~4 위에 얹히고, `ProcessIncome.kt:59-60`의 `officerCntByCity`를 확장점으로 지목한다(파일 확인 — 해당 행에 담당도시 관직자 집계 존재). 평행 축 신설 없음. |
| 4 | **Y** | §4.1이 "묘섭에도 devsam PHP에도 없는 opensamguk 독자 추가 · 골든 오라클 없음"을 명시하고, §4.5가 emergent 5종 중 4종 채택, §4.6이 사전 관계(`v2_general_bond`, 유비·관우·장비 SWORN)를 분리 정의한다. **사용자 결정(능력치 버프)이 §4.7에 원형 그대로 살아 있다** — 동석 파트너당 ±2, 선언 상한 ±6, `GetStatValue` 교차증폭 때문에 실효 상한 통솔 ±6 / 무력 ±8 / 지력 ±8까지 자기고발했다. v2 world profile 한정 tail-append `RelationStatModule`로 v1 source 목록·draw·로그 불변을 논증. 원본 데이터 git-ignore·빌더만 버전관리 규정 있음. |
| 5 | **Y** | 임원진 6종 도입/보류 판정 + 채택분 판정 지점·중첩 규칙 명시. 근거 `help__start__intermediate__positionrole.md:191+`(사도 세금 20% / 사공 병종기술 +2) 바이트 일치. |
| 6 | **Y** | 도시 특색 9종·규모 게이트·지역병종 7지역 각각 채택/보류 판정. 발현 조건 근거 `help__start__advanced__optimizedomestic.md:133,155`(도시기술 900) 바이트 일치. |
| 7 | **N** | §7.1-2의 T2 12행 선언 목록이 **여전히 불완전**하다 — `BootstrapConfig.kt`(R1), `GameApiApplication.kt:9-10`(R6) 2건 누락. 상세는 §4. |
| 8 | **Y** | §8 표가 신규 요소별로 `결정 → deterministic 판정 → 다음 상태 → replay/log` 4열을 채우고, 전 항목 RNG draw 0을 명시한다. |
| 9 | **Y** | 오픈 경로 증분 **14 + 6 = 20** 단일값이 문서 전체에서 일관된다. 기준선 14는 `README.md:55-65`에서 재확인. 부활한 조건부 항목 없고, U9는 수량의 전제가 아님을 §11이 명시한다. "분해 시 21" 주석은 3차가 **요구한** 것이므로 감점 사유 아님. |
| 10 | **Y** | §10이 G0 선행·비대체를 논증하고 GOLDENSET round-2 4·8번 유예(ADR-LITE-019)와 무충돌임을 보인다. `.ai/decisions.md:178-198` 대조 완료. |

**9 / 10**

---

## 2. 날조 재발 점검 — **재발 없음**

개정 2차에서 존재하지 않는 문자열을 인용한 전례가 있어, 이번 채점은 `path:line` 인용을 표본이 아니라 **광범위 전수**로 열어 대조했다. 아래는 실제로 파일을 열어 확인한 목록이다.

**코드 인용 (전부 일치)**

- `logic/.../world/ProcessIncome.kt:214-219` `interface ProcessIncomeContext` · `:50-55` `data class IncomeGeneral`(**`cityId` 없음 — 저자 주장 확인**) · `:59-60` `officerCntByCity`
- `logic/.../world/RaiseDisaster.kt:55-62` `data class DisasterCity`(**`garrison` 없음 — 확인**) · `:227` `env[ENV_WORLD] as? DisasterWorldView ?: return`(env-read 무성 실패) · `:253-258` `interface DisasterWorldView`
- `app/game-engine/.../world/WorldActionContext.kt` — `:76` import · `:116` `ProcessIncomeContext,` · `:300/302/322` · `:65` import · `:124` `DisasterWorldView,` · `:600`. **두 인터페이스의 프로덕션 구현자는 이 파일 1개뿐**임을 `app infra logic common` 전수 grep(테스트 제외)으로 독립 확인. 프로덕션 생성 지점 정확히 4개(`WorldEventContextFactory.kt:72`, `WorldActionContext.kt:920`, `MonthlyPostUpdateHook.kt:198`, `:322`), 팩토리의 유일 프로덕션 호출자 `DaemonLoopConfig.kt:269`. **저자가 보고한 숫자 전부 정확.**
- `common/.../wire/TurnDaemonCommand.kt` — `:14` sealed class, 본문 `:940` 종료, 파일 1002행. 중첩 서브클래스 **74개** 정확히 일치, 파일 밖 서브클래스 **0**(U9의 근거 성립).
- `logic/.../actions/instant/InstantActionRegistry.kt:28-42` 5단계 계약(`:31/:33/:35/:38/:39`, `:41` "새 컨트롤러를 만들지 말 것") · `:56` `INSTANT_ACTION_CODES`
- `logic/.../actions/instant/inherit/InheritActionRegistry.kt:47` `INHERIT_ACTION_CODES` — 채점자가 처음 동명이인 파일(`logic/inheritance/`)을 잡아 저자가 틀린 것처럼 보였으나, `InstantActionController.kt:7` import로 **저자 경로가 옳고 채점자 조회가 틀렸음**을 확인.
- `InstantActionController.kt:83-84` 분류기 · `:85-87` 400
- `logic/.../actions/CommandRegistry.kt:121` `fun resolve` · **`:224 else -> RestAction`**
- `CommandWireMapper.kt:43` `intakeCodes` · `:140-149` `toCommand` · `:147` `if (code !in intakeCodes) return null`
- `TurnDaemonCommandDispatcher.kt:326` `fun dispatch(...) = when` · `:397 else -> null` · 생성자 `:75`
- `CommandReserveService.kt:120-144`(Model B, `CommandRegistry` 미사용) · `:177 registry.resolve`(Model A 한정) → **0편집 주장 성립**
- `app/game-engine/.../config/BootstrapConfig.kt:55` — 존재 확인(다만 §4 지적 참조)
- `infra/.../seed/ScenarioImporter.kt:806-832` — `:807 if (scenario.ignoreDefaultEvents)` / `:808 emptyList()` / `:810 EventStore.defaultWireRows()` / `:818` / `:819 scenarioRows` / `:827 deferredRows` / `:831 INSERT INTO event` **인용 블록 바이트 일치**
- `infra/.../seed/ScenarioJson.kt:299` `val ignoreDefaultEvents: Boolean = false,` · 파싱 `:69` · `scenario_910.json:16 "ignoreDefaultEvents": true,`(레포 내 유일 사용처)
- `EventStore.kt:164/169/171/180/188/190/197` · `EngineEventConfig.kt:46-57`(all-or-nothing, `:47`에 `world_id` 필터 없음) · `:79-81` bean
- `EventAction.kt:61-64` register / `:70-74` create + `IllegalArgumentException("존재하지 않는 Action입니다 :…")`
- `GameApiSecurityConfig.kt:47 .anyRequest().permitAll()`
- `logic/.../actions/nation/CheJeungchuk.kt:36-38` — `:36` KDoc(`che_증축.php:82-86`) / `:37 fun getCost(develCost: Int): Int =` / `:38` 본문. **저자의 `:35-37` → `:36-38` 정정이 옳다.**
- `logic/.../world/UpdateNationLevel.kt:128/143-147` — 저자 반박 대상, §5에서 별도 판정.

**문서 인용 (전부 일치)**

- `README.md:55-65` = 14티켓 · `product-spec.md:388` 축자 일치 · `.ai/decisions.md:178-198` ADR-LITE-018/019 · `plans/.../01-backbone-micro.md:74-81`(0A-a~g 전부 isolation, 확장점 0) · `:190`(`operation_participants` 이름만)

**묘섭 위키 인용 (전부 바이트 일치)**

- `help__start__peq__peq.md:46`(City-oriented) · `:51`(빈 성 조건 + 관리 도시수) · `:61`(금·병량 이전 + 도시병사 상주)
- `help__start__basic__myostart.md:116,119`
- `help__start__other__etcetera.md:64`(장수수 300명 기준, 최저 1/6)
- `help__start__intermediate__positionrole.md:191+`(사도 세금 20% / 사공 병종기술 +2)
- `help__start__advanced__optimizedomestic.md:133,155`(도시기술 900)
- `help__start__intermediate__intermediatebattle.md:364`(수송 5만 / 최소병사 2000)

**허위로 오인할 뻔한 1건 — 날조 아님.** `CqrsBaselineMain.kt:180`은 `InMemoryTurnWorld(snapshot)`가 아니라 `val snapshot = loader.buildSnapshot()`이고 world 생성은 `:182`다. 그러나 저자 문장이 스냅샷 빌드와 world 생성을 함께 서술하므로 `:180`은 방어 가능한 앵커다. 감점하지 않는다.

**결론: 개정 4차에 날조는 하나도 없다.** 3차까지의 인용 규율 문제는 해소된 것으로 판정한다.

---

## 3. 3차 지적 반영 여부

| 지적 | 판정 | 확인 내용 |
|------|------|-----------|
| **MAJOR-1** (확장점→구현자 추적을 R2~R5 전체에 적용) | **부분 닫힘 → 문항 7 N 유지** | 3차가 지목한 `WorldActionContext.kt`는 §7.1-2 `:968-1021`과 T2 12행 표에 들어왔고 숫자도 전부 정확하다. 그러나 **같은 실패 유형이 R1·R6에서 2건 재발**(§4). |
| **MAJOR-2** (R2‖R3 병렬 → 순차) | **닫힘** | 산문뿐 아니라 **§9.2 표 행 `:1113`이 "R2 뒤 — 병렬 아님"으로 실제 변경**되었고 본문 `:1120-1126`, §7.1-2 `:963`/`:987`과 일관된다. 문서 어디에도 잔존 병렬 주장이 없다. CLAUDE.md "cross-area shared artifacts build sequentially, creator-then-consumer" 요구를 충족한다. (3차 지적 3번 유형 — 말만 바꾸고 표는 그대로 — 을 표를 직접 열어 배제했다.) |
| **MINOR-1** (emergent 수량 통일) | **닫힘** | "5종 중 4종"이 `:575`(§4.5 표) · `:1184` · `:1196` · `:1306`에서 동일. 구버전 표현 잔존 0건. |
| **MINOR-2** (`ignoreDefaultEvents: true` 판정 근거) | **닫힘** | `ScenarioImporter.kt:807-818` + `ScenarioJson.kt:299` + `scenario_910.json:16` 실제 파일 근거로 뒷받침되며 인용 블록이 바이트 일치. |
| **MINOR-3** (행 번호 오류 2건) | **1건 정정 · 1건 저자 반박 인용** | `CheJeungchuk` `:35-37`→`:36-38` 정정은 옳다. `UpdateNationLevel.kt:145-146`은 §5 참조. |

---

## 4. `fix-required` 잔여 항목

### MAJOR-A — R1의 T2 선언 목록에 `BootstrapConfig.kt`가 없다 (문항 7 N의 주된 근거)

**사실.** T2 12행 표의 7번 행은 v2 원장 SELECT를 `WorldSnapshotLoader.buildSnapshot()`(`app/game-engine/.../boot/WorldSnapshotLoader.kt:51`) 안에 넣고, 그 게이트를 **OPENSAM-35 0A-b의 "bean 등록 게이트"**(`plans/.../01-backbone-micro.md:76`)로 지정한다.

그런데 실제 파일은 이렇다.

```kotlin
// app/game-engine/src/main/kotlin/opensamguk/engine/config/BootstrapConfig.kt:45-55
@Bean
fun worldSnapshotLoader(
    jdbc: JdbcTemplate,
    seedBootstrap: SeedBootstrap,
    processWorld: EngineProcessWorld,
): WorldSnapshotLoader = WorldSnapshotLoader(jdbc, seedBootstrap, processWorld.worldId)

@Bean
@Lazy
fun inMemoryTurnWorld(loader: WorldSnapshotLoader): InMemoryTurnWorld =
    InMemoryTurnWorld(loader.buildSnapshot())
```

`WorldSnapshotLoader`는 Spring 애노테이션이 없는 평범한 클래스이며(`WorldSnapshotLoader.kt:44-51`, 생성자 3인자, 플래그 없음) `BootstrapConfig`가 **무조건** 등록한다. **bean 등록 게이트는 무조건 등록되는 bean의 메서드 본문 안에 있는 SELECT를 게이팅할 수 없다.** 플래그를 그 안까지 내리려면 생성자 인자 추가든 `ObjectProvider` 주입이든 `BootstrapConfig.kt` 편집을 반드시 경유한다. 즉 문서가 스스로 선언한 두 사실("SELECT는 loader 안", "게이트는 0A-b bean 등록")을 동시에 만족하는 구현 경로는 **전부 `BootstrapConfig.kt`를 통과한다.**

**왜 치명적인가.** §7.2 게이트 ③은 `git diff --name-only --diff-filter=MD origin/main -- app/*/src/main/kotlin/ …`가 선언 목록과 **정확히** 일치할 것을 요구하고 초과를 위반으로 규정한다. 지금 상태로 R1을 구현하면 게이트 ③이 R1에서 자동 실패한다. 더구나 저자는 **같은 추적 문단에서 `BootstrapConfig.kt:55`를 "프로덕션 조립점 1개"로 이름을 적어놓고** 12행 선언 표에는 넣지 않았다 — 3차가 지적한 실패 유형("확장점을 이름으로 적었으면 그 구현자까지 파일명으로 적는다")과 정확히 동형이다.

**요구사항 (택1, 문서에 명시):**
- (a) `BootstrapConfig.kt`를 T2 선언 목록에 행으로 추가하고 편집 지점·비활성 논증·골든 재실행을 기술한다. 또는
- (b) 게이트 메커니즘을 bean 등록이 아닌 **loader 내부 자기판정**으로 바꾸고(선례: `RehydrateService.kt:143-156`의 `information_schema` 존재 프로브) 그 메커니즘을 문서에 적는다. 현재 문서에는 이 메커니즘이 어디에도 없으므로, 선택하려면 **새로 써야 한다.**

### MAJOR-B — R6의 "T2 편집 0" 논거가 인용한 파일이 그 논거를 반증한다

**사실.** `:997`은 R6가 T2 편집 0인 이유로 `GameApiApplication.kt:8 @SpringBootApplication`이 `opensamguk.gameapi` 패키지 전체를 스캔한다는 점을 든다. `@RestController`에 대해서는 참이다. 그러나 바로 아래 두 줄이 JPA 경로를 뒤집는다.

```kotlin
// app/game-api/src/main/kotlin/opensamguk/gameapi/GameApiApplication.kt:8-10
@SpringBootApplication
@EntityScan(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
@EnableJpaRepositories(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
```

`:9-10`은 기본 스캔을 **대체하는 명시적 화이트리스트 3개 패키지**다. 그리고 game-api read 계층은 사실상 전부 Spring Data JPA다(`*ReadRepository.kt` 약 20개가 `SpringDataRepository<…>` + `@Repository`, 예 `CityReadRepository.kt:142,165`; `JdbcTemplate`을 쓰는 game-api 파일은 4개뿐). 0A-a/0A-f가 요구하는 v2 네임스페이스 격리를 지키면 v2 read repository는 화이트리스트 밖에 떨어지고, `GameApiApplication.kt:9-10` 편집이 필요해진다.

**요구사항 (택1, 문서에 명시):** (a) `GameApiApplication.kt`를 T2 목록에 추가, (b) v2 read를 `JdbcTemplate`으로 못박기(선례 존재), (c) v2 엔티티를 `opensamguk.gameapi.read` 하위에 두겠다고 선언. 어느 쪽이든 **현재 논거("`:8`이 전부 스캔하므로 편집 0")는 틀렸으므로 교체해야 한다.**

### MINOR-C — `TruncateContract` 비활성 논증이 느슨하다

`TruncateContract.kt`는 `SURVIVE`/`TRUNCATED` 분류와 `isExcludedFromTruncate`만 있는 순수 분류 object로 DB 실행이 없어 **결론(비활성)은 옳다.** 다만 문서가 붙인 이유("빈 컬렉션이면 step 미진입")는 이 파일에는 적용되지 않는다. 결론이 맞으므로 문항 판정에는 반영하지 않았다. 이유만 정정하면 된다.

---

## 5. 저자 반박에 대한 채점자 판정 — **저자가 옳고 3차 채점자가 틀렸다**

3차는 `UpdateNationLevel.kt:145-146` 인용이 틀렸다고 지적했고 저자는 반박했다. 파일을 열었다.

```
:128  val grant = newLevel * 1000
:143  val updatedNation = nation.copy(
:144      level = newLevel,
:145      gold = nation.gold + grant,
:146      rice = nation.rice + grant,
:147      meta = updatedMeta,
```

`:145-146`이 정확히 gold·rice 동시 지급이다. 3차가 자원 항목으로 본 `:147`은 `meta`다. **저자의 반박을 인용한다. 이 건은 저자 무과실이며 MINOR-3의 절반은 애초에 성립하지 않았다.**

이 판정을 별도로 밝히는 이유는, 저자가 채점자를 상대로 근거를 들어 반박한 것이 이번 라운드에서 처음이고 그것이 **옳았기** 때문이다. 반박이 옳으면 옳다고 적는 것이 채점자의 의무다.

---

## 6. 확인 불가 (UNKNOWN — 추측으로 채우지 않음)

1. **OPENSAM-35 / OPENSAM-149의 Jira 본문**을 조회하지 않았다. 문항 9의 판정은 레포 내 `plans/.../01-backbone-micro.md:74-81`과 `README.md:55-65`에만 근거한다. Jira 본문이 레포와 다르면 판정이 흔들릴 수 있다.
2. **`plans/README.md` · `product-spec.md` · `LEDGER.md`가 워킹트리 미커밋 상태다.** 인용은 이 트리 기준으로 유효하나 **HEAD 기준 검증이 아니다.**
3. **U9의 컴파일 거동** — `@Serializable` sealed 서브클래스를 별도 파일에 두었을 때의 동작을 실제로 컴파일해보지 않았다. 문서가 UNKNOWN으로 남긴 것이 타당하다.
4. **`attritionLoss` 보간 곡선의 밸런스**는 시뮬레이션 없이는 판정 불가다. 수치의 deterministic 성립 여부만 확인했고 게임성은 확인하지 않았다.
5. **v2 game-api가 `opensamguk_v2` DB에 어떻게 접속하는지**를 제안서가 어디에도 쓰지 않았다. ADR-LITE-018은 별도 DB를 요구하고 R6는 game-api에서 v2 원장을 읽는다. game-api에 `ReadBarrierDataSourceConfig.kt`가 자체 `HikariDataSource` bean(`:25`, `:42`)을 만드는 다중 DataSource 선례는 있으나, **v2 DataSource 배선 계획은 문서에 없다.** MAJOR-B와 인접하지만 별개 공백이며, 확실한 결함이라 단정할 근거가 없어 UNKNOWN으로 남긴다.
6. MAJOR-A·MAJOR-B 모두 **문서에 적혀 있지 않은 탈출 경로**(각각 존재 프로브 / `JdbcTemplate`)가 존재한다. 따라서 두 건은 "반드시 이 파일이 편집된다"는 확정이 아니라 **"선언된 메커니즘으로는 이 파일 편집을 피할 수 없고, 피할 메커니즘은 문서에 없다"**는 미선언 공백으로 제기한다.

---

## 7. 총평

개정 4차는 실질적으로 좋아졌다. 3차가 요구한 확장점→구현자 추적이 §7.1-2에 실제로 들어왔고, 그 안의 **숫자가 하나도 틀리지 않았다** — 구현자 1개, 생성 지점 4개, 중첩 74·파일 밖 0, 이 전부를 채점자가 독립 grep으로 재현했다. 2차에서 문제였던 날조는 광범위 전수 대조에서 **한 건도 나오지 않았다.** MAJOR-2는 산문뿐 아니라 §9.2 표 행까지 실제로 바뀌었고, MINOR 3건도 닫혔다. 저자의 반박은 옳았다. 문항 4의 사용자 결정(능력치 버프)은 축소·희석 없이 §4.6~4.7에 살아 있으며, 오히려 실효 상한 ±8을 자기고발한 점은 감점이 아니라 가점 사유다.

그럼에도 `cleared`를 줄 수 없는 이유는 하나다. **3차의 N은 "그 한 줄"이 아니라 "자기 산출물을 구현자까지 걸어 내려가지 않는 습관"에 대한 것이었고, 그 습관이 R1·R6에서 다시 나왔다.** 저자 스스로 취약점 #0에서 "필요하다고 생각한 확장점에서만 추적을 시작했다"고 고백했고, 채점자가 다른 출발점(P6 betting 채널 파일셋 복제, R1 게이트 메커니즘 역추적, R6 등록 메커니즘 역추적)에서 절차를 재실행하자 곧바로 2건이 나왔다. §7.2 게이트 ③이 "초과 = 위반"인 이상 선언 목록의 완전성은 장식이 아니라 하중을 받는 부품이다.

남은 일은 크지 않다. 표에 행 2개를 더하거나(또는 메커니즘 2개를 문서에 적거나) MINOR-C의 이유 한 줄을 고치면 된다. 다만 이번에는 **저자가 지목받은 파일만 고치지 말고**, 20티켓 전부에 대해 "이 확장점의 프로덕션 구현자·조립점·등록점을 grep으로 세었는가"를 한 번 더 돌린 결과를 함께 제출하기를 요구한다. 같은 유형이 4바퀴 연속 재발했다.

**VERDICT: `fix-required` · 9/10.** 다음 개정에서 MAJOR-A·MAJOR-B가 닫히면 문항 7은 Y이고 10/10 `cleared`가 된다.
