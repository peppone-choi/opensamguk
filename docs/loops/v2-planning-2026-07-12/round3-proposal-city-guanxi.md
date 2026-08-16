# round-3 설계안 — 도시 중심·인맥(꽌시) 플레이

> 대상 채점기: `docs/loops/v2-planning-2026-07-12/GOLDENSET-round3-city-guanxi.md` 10문항
> 상태: 2026-07-25 작성 → 같은 날 **개정 1차**(사용자 결정 2건) → 독립 reviewer **5/10 `fix-required`**(`REVIEW-round3-r1.md`) → **개정 2차** → **6/10 `fix-required`**(`REVIEW-round3-r2.md`) → **개정 3차** → **9/10 `fix-required`**(`REVIEW-round3-r3.md`) → **개정 4차** → **9/10 `fix-required`**(`REVIEW-round3-r4.md`) → **개정 5차** → **9/10 `fix-required`**(`REVIEW-round3-r5.md`, CRITICAL-1 = 문서 내부 DB 토폴로지 자기모순) → **개정 6차**, 재채점 대기
> 개정 1차: ① §4 관계망이 능력치 보정까지 한다(격리 장치 2개 폐기·대체). ② §5-bis 신설 — 국가 임원진·중앙관직·품관.
> 개정 2차(리뷰 대응): ③ **`nation.gold` 미러·총합 불변식 폐기** — 국고와 도시 원장 병존(§2.3). ④ R2+R3 병합, `IncomeTick` 기존 함수 재사용(§2.2). ⑤ 주입 지점 2곳 실측·seam 부재 명시(§4.3). ⑥ 능력치 실효 상한 재계산 ±6/±8(§4.7). ⑦ RTK 사전관계 출처 **UNKNOWN**으로 정정(§4.4). ⑧ diff 게이트 2계층 재작성(§7.2). ⑨ 티켓 재계산 **14 → 20**, 관계망은 오픈 후(§9).
> **개정 3차(리뷰 대응)**: ⑩ **`BAD_STATE_CODES` 라벨 날조 정정** — 개정 2차가 7개 재해명을 지어냈다. `RaiseDisaster.kt:104-127` 실측 표로 교체하고 월 게이트 근거를 `EventStore.kt:171,180,190,197`로 재귀속(§2.4). ⑪ `prev_income` 4번째 산출물 판정 **(a) 유지**(§2.3). ⑫ 국고의 **네 번째 지출**(병종연구·포상·헌납·아이템)과 관측 항목 3종 추가(§2.3). ⑬ `EngineEventConfig.kt`를 T2 목록에 추가하고 12행 재시드 티켓 귀속 명시(§7.1-2). ⑭ **`OPENSAM-35` 범위 조회 완료 — seam 미포함 확정**, R0을 오픈 후 `P0`으로 이동해 총계를 **단일값 20**으로 확정(§4.3·§9.2). ⑮ RIVAL 소급을 **UNKNOWN**으로 강등(COMRADE만 소급 가능, §9.4). ⑯ 표시 경로 divergence — `FrontInfoController.kt:394`가 `GetStatValue`를 우회(§4.3·§9.4).
> **개정 4차(리뷰 대응)**: ⑰ **T2 목록을 확장점→구현자 추적으로 전면 재작성** — R1~R6 전부에 대해 확장점의 프로덕션 구현자를 grep으로 세어 `WorldActionContext.kt`·`InMemoryTurnWorld.kt`·`WorldSnapshotLoader.kt`·`CommandWireMapper.kt`·`TurnDaemonCommandDispatcher.kt`를 추가(6+1 → **11편집+마이그레이션 1**), 그리고 같은 실패가 세 번 반복된 데 대해 **추적 절차를 규율로 일반화**(자기채점 절). ⑱ **R3‖R2 병렬 철회 → 순차(생산자 R2 → 소비자 R3)** — 공유 파일 2건 + 등록 순서 의존(§9.2). ⑲ `ScenarioImporter`의 "항상"은 거짓 — `ignoreDefaultEvents`(`:807`) 두 분기를 재고 **v2는 `true`**로 확정(§7.1-2). ⑳ emergent 수량을 §4.5 실측 **5종 중 4종**으로 통일. ㉑ `CheJeungchuk.kt:35-37` → `:36-38` 정정, 반면 `UpdateNationLevel.kt:145-146`은 **채점 지적이 틀렸음을 코드로 반박**(§2.3). ㉒ §11에 U9(`@Serializable` sealed 서브클래스 파일 분리) 신설.
> **개정 6차(리뷰 대응)**: ㉚ **배포 토폴로지 확정 — "한 프로세스 = 한 월드 = 한 DB"** (5차 채점 CRITICAL-1). 고르지 않고 리포에서 찾았다: `WorldIdConfig.kt:11`·양쪽 `application.yml:8-14`·**`ScenarioSeedCoordinator.kt:37-49`(두 월드가 한 DB에 있으면 부팅 `error`)**·`StreamKeys.kt:16-34`·0A-e/0A-f+ADR-LITE-018(§7.1 "개정 6차 — 배포 토폴로지 확정"). ㉛ 그 결과 5차의 "`JdbcFlushExecutor`는 v1 DataSource에 묶여 있다"가 **거짓으로 판명** — `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` **T2 복귀**, `TurnRunService.kt` **T2 삭제**, 두 번째 Hikari 풀 **폐기**, §11 **U11 철회**(단일 트랜잭션), `command_inbox`는 "v2 프로세스 자기 DB의 표"로 정정 ⇒ **편집 10 + 마이그레이션 1 = 11행**. ㉜ **T2 11행 전부에 "가드 영향" 열 신설**(5차 M1) + `DaemonWriteGuard`/`DaemonNoEntityManagerTest` 판정 절 신설(5차 M2). ㉝ **UNK-C·UNK-D 종결** — `RehydrateService.kt` 무편집 / `engine/redis/**` 무편집을 코드 근거로 확정. ㉞ MINOR 4건 정정 — `GetStatValue.kt:65` 재클램프 실측으로 **없는 결함을 자백한 `ponytail:` 주석 철회**(m1), `ProcessIncomeContext` 멤버 "둘" → **셋**(m2), `ignoreDefaultEvents` 두 번째 읽기 지점 `EngineEventConfig.kt:41-45` 명시(m3), `ReadBarrierDataSourceConfig.kt` 행 범위 `:33-43` 정정(m4). ㉟ §11에 **U12**(`SPRING_FLYWAY_LOCATIONS` env 오버라이드 미실측) 신설, 자기채점에 **0-ter**(제3의 실패 양식 — 물리적 제약의 날조) 신설. **오픈 경로 수량 20 불변.**
> **개정 7차(6차 채점 `cleared` 10/10 후 비차단 MINOR 4건 반영 — 설계 변경 0)**: ㊱ **0A DoD (i)에 `SCENARIO_CODE`·`SCENARIO_DIR` 추가 + R2 DoD에 시드 후 `event` 행 검증 3항목**(m-new-1). 실측: compose가 두 변수에 기본값을 주고(`docker-compose.yml:172-173`, `docker-compose.production.yml:67-68`) 기본 시나리오 `scenario_1010`은 `ignoreDefaultEvents`가 거짓이라(클래스패스 정본에 키 부재 ⇒ `ScenarioJson.kt:69`·`:299` 기본값 `false`), 물려받으면 `DEFAULT_EVENTS` 12행이 적재되고 v2 leaf 0 — **부팅은 성공하는데 도시 원장 수입이 안 도는 조용한 실패**. ㊲ **`event` 행 저작 서술 정정** — `insertEvents`는 `defaults + scenarioRows + deferredRows`(`ScenarioImporter.kt:828`)이므로 "시나리오 JSON이 행 **전체**를 저작"은 거짓, "**시나리오 유래 행 전체**"로 좁힘. 자동 행의 이름 `RegNPC`/`RegNeutralNPC`/`DeleteEvent`는 v1 leaf이고 체인 등록으로 유지되므로 **설계 무변경**(m-new-2). ㊳ **토폴로지 근거 (γ) 범위 축소** — "이미 강제되는 코드 불변식" → "**시드 활성 부팅에서** 강제되는 불변식"(`ScenarioSeedRunner.kt:70-73`이 코디네이터보다 먼저 반환). 시드 비활성 부팅의 담보는 0A DoD (i)의 env 분리. α·β·δ·ε 무조건 성립 ⇒ **갈래 A 확정 유지**(m-new-3). ㊴ **신규 파일 열거에 v2 시나리오 JSON 사유 명시** — 신규 파일이라 T1·T2 계층 밖이고 게이트 ②③⑤가 전부 `--diff-filter=MD`. 위치는 추적되는 클래스패스(`infra/src/main/resources/scenario/`)로 R2 DoD에 못 박음(m-new-4). ㊵ 부수 — `EventAction.kt:61-64` → **`:60-64`**(KDoc 포함으로 범위 확대). **오픈 경로 20 · T2 11행 · 게이트 ①~⑤ 전부 불변.**
> 자기채점은 문서 말미에 붙였으나 **참고용**이다. 채택 판정은 독립 reviewer가 별도로 내린다. 잔여 UNKNOWN 전량은 §11에 모아 두었다.
> 이 문서는 설계안이며 코드·티켓·ADR을 아직 바꾸지 않는다. 채택되면 ADR-LITE-019 개정이 따른다(채점기 §채택 규칙).

---

## 0. 이 설계안이 주장하는 것 한 문단

묘섭의 "도시 중심"은 지도 표현도 거점 수도 아니고 **정기 재정 순환(세수 → 봉록)의 소유 행이 국가에서 도시로 내려간 것**이다. 그 한 번의 이동이 "누가 어느 도시에 있는가"를 회계상의 사실로 만들고, 그 순간 사람 사이의 의존(인사권·배치효과·감시·자원분배)이 관계 수치 없이도 게임 안에서 성립한다. 따라서 도시 중심과 인맥 중심은 두 시스템이 아니라 하나이며, 이 하나는 G0(2,000 거점·3D·도시 4모델) 없이 v2 오픈 경로에서 구현 가능하다.

개정 2차에서 두 가지가 바뀌었다. 첫째, **`nation.gold`를 도시 원장의 미러로 두려던 원안을 폐기한다** — 국가 잔고를 쓰는 지점이 42파일에 흩어져 있고 단일 통로가 없어 구현 불가이며, 미러는 precheck 거짓 통과까지 낳는다(§2.3의 전수 census). 대신 v2는 **국고와 도시 원장을 병존**시키고, 치환하는 leaf를 `ProcessIncome` **하나로 줄인다.** 둘째, **장수↔장수 관계망은 오픈 경로에서 뺀다.** 설계는 §4에 완전한 상태로 남기되 착수는 오픈 후다 — 관계를 낳는 사건(작전 참여·가신 서약)이 오픈 경로의 6·7번(`OPENSAM-56`·`61`)에서야 생기므로 **순서상 넣을 자리가 없다**(§9.4). 부수 근거로 V2-3의 `operation_participants`(`01-backbone-micro.md:190`)가 참여 기록을 남기므로 **COMRADE(전우)는 오픈 후 도입해도 소급 생성이 가능**하다 — 다만 **RIVAL(라이벌) 소급은 UNKNOWN**이며(적대 기록을 남기는 컬럼이 정의된 적 없다, 개정 3차 §9.4) 판정은 순서 논거만으로 성립한다.

그 결과 이 설계안이 오픈 경로에 넣는 것은 **6티켓(14 → 20)**이고, 나머지는 근거를 붙여 오픈 후로 보낸다. 국가 임원진·중앙관직·품관은 서로 경쟁하지 않는 세 층으로 분리되며, **셋 다 오픈 경로 증분 0이다**(§5-bis).

---

## 1. "도시 중심"의 정의 — 자원 소유 주체 (시험지 1)

### 1.1 운영자 자기규정

묘섭 운영자(chjej202)는 묘섭을 체섭·칠랑섭과 나란히 놓고 **제3의 길**로 자기규정했다.

> "기존의 체섭은 Nation-oriented(국가 지향) 삼모전입니다. … 칠랑섭 같은 경우가 대표적인 User-oriented(유저지향) 삼모전이라고 할 수 있을 겁니다. 묘섭은 제 3의 길로 City-oriented(도시지향) 삼모전을 목표로 삼고 있습니다. 도시 지향 삼모전인 만큼, 도시별로 장수가 관리되고, 도시 중심으로 국가가 운영됩니다"
> — `docs/wiki/raw/myosam-help/help__start__peq__peq.md:46` (Q2)

그리고 그 "도시 지향"이 유저에게 실제로 무엇으로 나타나는지를, 같은 문서의 다른 질문에서 **단 두 가지로** 특정했다.

> "체섭과 묘섭의 차이점은 굉장히 많습니다. 하지만, 접하는 유저 입장에서 가장 큰 차이점이라고 하면, 기존의 국가에서 다루던 금과 병량이 도시로 이전된 점과 도시마다 도시병사가 상주한다는 점입니다. 정말 단순한 차이이지만, 플레이 스타일이 완전히 바꿔져 버리는 큰 차이점이죠."
> — `help__start__peq__peq.md:61` (Q5)

같은 내용이 시작 안내 문서의 "묘섭의 특징" 절 첫 두 항목이다.

> "국가의 금, 병량은 국가 단위가 아닌, 도시 단위로 관리가 됩니다." — `help__start__basic__myostart.md:116`
> "도시마다 병사를 주둔시킵니다. 따라서 도적의 공격으로부터 방어하거나, 전쟁시에 유용하게 사용할 수 있습니다." — `help__start__basic__myostart.md:119`

정직하게 덧붙이면, 같은 절에는 전투 5단계(`:122`)와 도시 시설·보조시설·장애물 건설(`:125`)도 함께 있다. 즉 묘섭의 자기규정에서 "도시"는 회계 축과 전투 축 양쪽에 걸쳐 있다. 이 설계안은 **회계 축만 먼저 가져오고 전투 축은 오픈 후로 미룬다** — 이유는 §6·§7에서 밝힌다.

### 1.2 그래서 이 설계안이 채택하는 정의

> **도시 중심 = 정기 재정 순환(세수 → 봉록)과 상주 병력의 소유 행(row)이 `nation`에서 `city`로 내려간 상태.**

**개정 2차의 정정.** 원안은 "금·병량·병력의 소유 행 **전부**"라고 썼다. 그것은 성립하지 않는다 — 국가 잔고를 읽고 쓰는 지점이 42파일에 흩어져 있고 그중 35파일이 직접 산술이며 단일 통로가 없기 때문이다(§2.3 census). v2가 실제로 내리는 것은 **정기 순환의 양 끝(세수 유입·봉록 유출)**이고, 그 둘은 v1에서 `ProcessIncome` 한 leaf 안에 함께 들어 있다(`logic/src/main/kotlin/opensamguk/logic/world/ProcessIncome.kt:124-167`). 헌납·포상·노획·전쟁수입 같은 **비정기 이동은 국고(`nation.gold`/`nation.rice`)에 그대로 남는다.** 이것은 묘섭 대비 명시적 divergence이며 §2.3에 대가를 적었다.

이 좁힌 정의는 세 가지를 의도적으로 배제한다. 첫째, 거점 개수(2,000개)는 이 정의와 무관하다 — 260개짜리 기존 도시 세트에서도 소유 주체 이동은 완전히 성립한다. 둘째, 3D·LOD 표현은 무관하다 — 원장은 표에 숫자로 뜨면 충분하다. 셋째, 도시 4모델 분리(행정단위/물리장소/치소/주변네트워크)도 무관하다 — 금과 병사는 **물리 장소**에 있는 것이고, 4모델 분리는 그 물리 장소에 이름을 붙이는 작업이지 소유 관계를 바꾸지 않는다(§10에서 상술).

그리고 이 정의는 v2 제품 정본이 **이미 선언한 것**이다. `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:32`의 "v2에서 새로 정의" 목록 6번째 항목이 그대로 `도시별 재정·곡물·주둔군·수송과 실시간 formation 전투`다. 즉 이 설계안은 범위를 **늘리는** 제안이 아니라, 이미 선언된 범위 중 회계 절반을 **오픈 경로로 당기고 전투 절반은 그대로 오픈 후에 두는** 일정 제안이다.

### 1.3 현재 opensamguk이 이 정의에서 얼마나 멀리 있는가

`logic/src/main/kotlin/opensamguk/logic/domain/LogicEntities.kt:67-92`의 `City`에는 `commerce/agriculture/security/defense/wall/population/trust/state/trade/region/term/officerSet/conflict`가 있으나 **`gold`도 `rice`도 없다.** 금·쌀은 `Nation`(`LogicEntities.kt:106-107`)과 `General`(`:30-31`)의 필드다. 도시 주둔 병력에 해당하는 필드도 없다 — `population`은 주민이지 병사가 아니다. 같은 파일 `:64`의 주석은 한 걸음 더 나간다: **"There is NO `city.tech` — tech is a NATION stat"**. 이 한 줄이 §6의 도시 특색 판정을 사실상 결정한다.

따라서 거리는 "필드 몇 개"가 아니라 **소유 그래프 한 단계**다. 그리고 그 한 단계가 이 라운드가 여는 전부다.

---

## 2. 도시 소유 금·병량과 도시병사 — v2 스키마와 상태 전이 (시험지 2)

### 2.1 스키마

v2 전용 DB(`opensamguk_v2`, ADR-LITE-018)에 표 하나를 추가한다. v1 `city` 테이블도 `City` 데이터 클래스도 **건드리지 않는다** — 정치·매력이 `General`에 필드를 얹은 선례(`LogicEntities.kt:51-55`)가 있지만 여기서는 그 선례보다 강하게, 별도 표·별도 타입으로 간다.

```
v2_city_ledger
  world_id      bigint  NOT NULL   -- OPENSAM-148 canonical world identity
  city_id       int     NOT NULL
  gold          bigint  NOT NULL DEFAULT 0
  rice          bigint  NOT NULL DEFAULT 0
  garrison      int     NOT NULL DEFAULT 0   -- 도시병사
  PRIMARY KEY (world_id, city_id)
```

컬럼 셋이 전부다. 묘섭에는 도시병사의 훈련·사기(차출 시 "높은 훈련도와 사기치", `help__start__beginner__basicdomestic.md:195`)와 3개월 주기 병량 유지비(`help__start__advanced__controlcity.md:139`, `help__start__advanced__optimizebattle.md:225`)가 더 있으나, 둘 다 **오픈 후**로 보낸다. 훈련·사기는 소비처(차출 지휘)가 오픈 후이고, 유지비는 원장이 실제로 돌아가는 것을 한 기수 관찰한 뒤 붙이는 편이 밸런싱상 안전하다.

쓰기 경로는 예외 없이 `ChangeRecorder` → `JdbcFlushExecutor`다. R1 티켓(§9)이 `ChangeRecorder`의 city-ledger 채널과 `JdbcFlushExecutor`의 flush step, infra flush IT를 함께 만든다 — P6에서 betting 채널을 붙인 것과 동일한 패턴이다. 데몬이 JPA `EntityManager`로 이 표를 쓰는 경로는 만들지 않는다.

### 2.2 수입과 봉록은 이미 한 leaf 안에 있다 — 중복이 아니라 단위 재조립이다 (개정: 원안 철회)

원안은 "해법은 중복이다"라고 썼다. **그 전제는 코드상 거짓이므로 철회한다.**

**(1) 도시별 내역은 이미 공개 API로 존재한다.** `logic/src/main/kotlin/opensamguk/logic/domestic/IncomeTick.kt`의 세 함수는 전부 도시 **하나**를 받아 `Int` 하나를 돌려주는 top-level `fun`이다 — `calcCityGoldIncome`(`:29-44`), `calcCityRiceIncome`(`:47-62`), `calcCityWallRiceIncome`(`:65-79`). 국가 항은 그 셋을 더하고 세율을 곱한 것뿐이다(`IncomeTick.kt:117-122`):

```kotlin
for (c in cityList) cityIncome += calcCityGoldIncome(c, officerCntByCity[c.id] ?: 0, capitalId == c.id, …)
return cityIncome * (taxRate / 20)
```

즉 v2는 **공식을 한 줄도 옮겨 적지 않는다.** 도시별 금 수입 = `calcCityGoldIncome(city, …) * (taxRate/20)`, 병량 = `(calcCityRiceIncome + calcCityWallRiceIncome) * (taxRate/20)`(v1이 `ProcessIncome.kt:128-129`에서 두 함수를 더하는 그대로). 골든 검증된 공식을 두 번째 파일에 옮겨 적는 것은 불필요할 뿐 아니라 이중 진실이다. 원안이 취약점 5로 적은 "v1을 잠깐만 리팩터링하고 싶은 압력"은 **존재하지 않는 압력**이었고, 그 원인이 이 잘못된 전제였다.

**(2) 수입과 봉록은 별개 티켓이 아니다.** `ProcessIncome.kt:124-167`은 한 leaf 안에서 넷을 한다 — 수입 계산(`:125-130`), 봉록 총액 계산(`:132-133`), **잔고 대비 3분기 판정**(`:141-153`), 그 판정이 낳은 `ratio`로 장수별 지급(`:160-166`). 그리고 그 3분기가 §2.3이 원하던 바로 그 규칙이다.

| 분기 | 조건 (`ProcessIncome.kt`) | 결과 |
|---|---|---|
| (a) | `res < base` (`:141-143`) | `ratio = 0` — **아무도 봉록을 못 받는다** |
| (b) | `res - base < outcome` (`:144-147`) | `realOutcome = res - base`, `ratio = realOutcome / originOutcome` — **실효 지급률 하향** |
| (c) | else (`:148-152`) | 전액 지급, `res -= outcome` |

"원장이 봉록을 다 못 대면 실효 지급률이 깎인다"(`controlcity.md:181`)는 v2가 발명할 규칙이 아니라 **v1이 국가 단위로 이미 하고 있는 규칙**이다. v2가 하는 일은 이 판정의 단위를 국가에서 도시로 내리는 것 하나다.

> **판정: 원안의 R2(수입 귀속)와 R3(지출 귀속)을 한 티켓으로 합친다.** 산출물은 `V2ProcessCityIncome` leaf 하나이며, `processIncome`(`ProcessIncome.kt:106-171`)의 구조를 도시 단위로 재조립한다. 공식은 `IncomeTick.kt`의 v1 함수를 그대로 호출한다. **원안의 `Σ V2CityIncome == getGoldIncome` DoD는 철회한다** — v1은 도시 합산 **후** 세율을 1회 곱하고(`IncomeTick.kt:117-122`) v2는 도시마다 곱하므로 반올림 잔차만큼 값이 갈린다. ADR-LITE-018로 v2는 v1과 금액이 같을 의무가 없으므로 이는 결함이 아니라 **선언해야 할 divergence**이며, 맞출 수 없는 등식을 티켓의 증거물로 삼지 않는다.

도시 단위 재조립의 절차 — v1 `processIncome`과 한 줄씩 대응한다.

```
V2ProcessCityIncome(resource)                       # v1 ProcessIncome.kt:106-171 대응
  for nation in nations.sortedBy { it.id }:         # :118 순서 그대로
    for city in nation.cities.sortedBy { it.id }:   # ← 새 루프 (단위 강하)
      income  = calcCity{Gold|Rice}Income(city, officerCntByCity[city.id] ?: 0,
                                          nation.capitalId == city.id, …) * (taxRate/20)
      billers = nation.generals.filter { cityOf(it) == city.id }          # ← 봉록 귀속
      originOutcome = getOutcome(100.0, billers.map { it.dedication })    # IncomeTick.kt:166 재사용
      outcome = phpRound(nation.bill / 100.0 * originOutcome)             # :133 그대로
      3분기 판정 (:141-153) 그대로, 단 res = ledger[city].{gold|rice} + income
      ledger[city].{gold|rice} = phpRound(res)
      for g in billers: pay = phpRound(getBill(g.dedication) * ratio)     # :161 그대로
```

**오픈삼국 결정 3건 (오라클 아님, 그렇게 표기해 커밋한다).**

1. **도시별 `base`.** v1은 `GameConst.basegold`(0) / `GameConst.baserice`(2000)를 국가 단위 하한으로 쓴다(`ProcessIncome.kt:115`, KDoc `:29`). 도시마다 2000을 두면 도시 원장이 구조적으로 마르지 않아 §2.3 전체가 무력해지므로 **v2 도시별 base는 금·병량 모두 0**으로 둔다. 묘섭 원문에 대응 규정 없음 — **UNKNOWN, 오픈삼국 결정.**
2. **봉록 귀속 도시 = 장수의 현재 소재 도시**(`General.cityId`, `LogicEntities.kt:28-31` 근방). 소재 도시가 자국 도시가 아니면(타국 체류·재야) **수도 원장**이 대신 지급한다. 묘섭 미명시 — **오픈삼국 결정.**
3. **잔차 없음.** 세율 곱을 도시마다 적용하므로 v1의 "합산 후 1회 곱"과 부동소수 잔차가 생길 수 있으나, v2는 v1과 금액이 같을 의무가 없다(ADR-LITE-018로 v1은 별도 DB·별도 오리지널). 원안의 "잔차 전액 수도 귀속" 규칙은 **철회한다** — 맞출 대상이 없는 등식을 맞추려고 특수 규칙을 두는 것은 비용만 남는다.

관직자 보정은 새로 만들지 않는다. `ProcessIncome.kt:59-60`의 KDoc대로 `officerCntByCity`는 `SELECT officer_city, count(*) ... WHERE officer_level IN (2,3,4) AND city = officer_city GROUP BY officer_city`, 즉 **관직자가 자기 담당도시에 실제로 체류할 때만** 세는 지도이고, 소비 형태는 `IncomeTick.kt:41` `income *= 1.05.pow(officerCnt)` = **곱**이다. v2는 이 지도와 이 형태를 그대로 쓴다(§3.2).

### 2.3 그래서 `nation.gold`는 무엇이 되는가 — 미러와 총합 불변식을 폐기한다 (개정: 원안 철회)

원안은 `nation.gold == Σ v2_city_ledger.gold` 미러 불변식을 두었다. **구현 불가능이므로 철회한다.** 전수 census를 근거로 적는다.

**(i) `nation.gold`/`nation.rice`를 쓰는 지점 전수.** 직접 산술만 **35파일**, 배관(recorder/mapper/SQL)까지 포함하면 **42파일**이다.

| 군 | 대표 지점 (`path:line`) |
|---|---|
| 개인·사령턴 커맨드 (자원 이동) | `logic/actions/trade/CheHeonnap.kt:81`(헌납) · `logic/actions/nation/ChePosang.kt:89-90`(포상) · `logic/actions/nation/CheMolsu.kt:151-152`(몰수) · `logic/actions/nation/CheChotohwa.kt:173-174`(초토화) · `logic/actions/nation/CheMuljaWonjo.kt:152-153,159`(물자원조) · `logic/actions/nation/CheGamchuk.kt:90-91` / `CheJeungchuk.kt:82-83`(감·증축) · `logic/actions/nation/CrInguIdong.kt:170`(인구이동) · `logic/actions/develop/CheGunryangMaemae.kt:177` · `logic/actions/develop/CheMuljaJodal.kt:123-124` · `logic/actions/trade/CheJangbiMaemae.kt:239` · `logic/actions/military/CheTalchwi.kt:262,283-284` · `logic/actions/personnel/CheHaya.kt:81-82` · `logic/actions/personnel/CheDeungyongSurak.kt:206-207` |
| 병종연구 9종 (동일 2줄 복붙) | `logic/actions/nation/EventGeukbyeongYeongu.kt:78-79` 외 8파일 (`EventSanjeobyeong`·`EventHwasibyeong`·`EventSangbyeong`·`EventWonyungnobyeong`·`EventEumgwibyeong`:78-79, `EventMuhui`·`EventDaegeombyeong`:87-88, `EventHwaryuncha`:92-93) |
| 월간·전쟁 leaf | `logic/world/ProcessIncome.kt:135,156` · `logic/world/ProcessWarIncome.kt:77` · `logic/world/ProcessSemiAnnual.kt:169-177` · `logic/world/UpdateNationLevel.kt:145-146` · `logic/war/ConquerCity.kt:198-199,245-246` · `logic/war/ProcessWar.kt:142,146,148` |
| 엔진 (중복 구현 포함) | `app/game-engine/.../world/WorldActionContext.kt:327-329,365-367,455-457,553-554` · `app/game-engine/.../config/DaemonLoopConfig.kt:614-615,624-625,629-630`(물자원조 **2차 구현**), `:696-697`(병종연구 **10번째 복붙**) · `app/game-engine/.../turn/ReservedTurnHandler.kt:605` · `app/game-engine/.../intake/PersonnelHandler.kt:73` |

**단일 통로(choke point)는 존재하지 않는다.** `spendNationGold` 류 헬퍼는 리포지토리 전체에 없고, `gold = nation.gold - reqGold, rice = nation.rice - reqRice`가 **10곳에 축자 복붙**되어 있다(병종연구 9 + `DaemonLoopConfig.kt:696-697`). 따라서 "한 군데만 고치면 된다"는 경로가 물리적으로 없다.

**(ii) 세 갈래가 전부 막혀 있다.** (a) 35~42파일을 도시 원장 경유로 변경 = v1 프로덕션 편집이자 ADR-LITE-018 위반이며 R티켓 하나의 규모가 아니다. (b) v2가 그 파일들을 포크·복제 = 이중 진실 35배. (c) flush에서 `nation.gold = Σ ledger` 재계산 = 그 턴 v1 커맨드의 델타를 전부 소거(자원 소실).

**(iii) 미러는 precheck 문제를 해결하지도 못한다.** 실제 판정은 두 함수에 모인다 — `logic/constraints/Presets.kt:312-319` `reqNationGold` (`n.gold >= req` else `Deny("국고가 부족합니다.")` — `:317`)와 `:322-329` `reqNationRice` (`Deny("병량이 부족합니다.")` — `:327`). 미러가 총합이면 총액 precheck는 통과하는데 차감은 특정 도시에서 나가므로 **구조적 거짓 통과**가 된다.

> **판정: v2에서 `nation.gold`/`nation.rice`는 도시 원장의 미러가 아니라 국고(國庫)라는 별개 계정이다. 총합 불변식은 폐기한다.**

이것은 이름을 바꾸는 일도 아니다 — v1 금 제약의 거절 문구가 이미 **"국고가 부족합니다"**(`Presets.kt:317`)다. **개정 3차 정정:** 개정 2차는 이 문장을 두 자원 모두에 걸치는 것처럼 썼으나 절반만 참이다. 쌀 제약 `reqNationRice`의 문구는 "국고"가 아니라 **"병량이 부족합니다."**(`Presets.kt:327`)이므로, "거절 문구가 이미 국고라고 말한다"는 논거는 **금에만** 성립한다. 쌀 쪽은 그 논거 없이도 판정이 바뀌지 않는다 — 병량 역시 읽는 값과 차감되는 값이 같은 계정(`nation.rice`)이므로 거짓 통과가 성립할 여지가 없다는 (아래) 근거가 독립적으로 작동한다.

**무엇이 어디로 가는가.**

| 계정 | 유입 | 유출 |
|---|---|---|
| **도시 원장** `v2_city_ledger` | 세수·수확·성벽 병량 (`V2ProcessCityIncome`, §2.2) · 수송(R5) | 봉록 (§2.2 3분기) · 병사보충(R4) · 수송(R5) |
| **국고** `nation.{gold,rice}` | 헌납 · 몰수 · 초토화 · 군량매매 세금 · 물자조달 · 장비매매 · 탈취 · 하야/등용수락 잉여 · 정복 노획(`ConquerCity.kt:198-199`) · 전쟁수입(`ProcessWarIncome.kt:77`) · 작위 상승 grant(`UpdateNationLevel.kt:145-146`) | 포상 · 물자원조 · 인구이동 · 증축 · 병종연구 9종 · 반년 감쇠(`ProcessSemiAnnual.kt:169-177`) |

v2가 치환하는 leaf는 **`ProcessIncome` 하나뿐**이다. 그 하나를 치환하면 국고에서 정기 수입과 정기 봉록이 동시에 사라지고, 남는 유입·유출은 위 표 그대로 **전부 실제 잔고 위에서** 돈다. `ProcessWarIncome`·`ProcessSemiAnnual`·`UpdateNationLevel`은 치환하지 않는다.

#### 그 leaf의 **네 번째** 산출물 — `prev_income` (개정 3차 신설)

개정 2차의 전수 회계는 치환 대상 leaf를 한 개로 좁혔으나 **그 leaf가 무엇을 산출하는지는 전수하지 않았다.** `processIncome`의 반환값은 넷이고(`ProcessIncome.kt:170` `ProcessIncomeResult(resource, nationUpdates, prevIncome, generalPayouts, globalHistory)`), 그중 셋(`nationUpdates`·`generalPayouts`·`globalHistory`)만 위에서 다뤘다. 넷째가 `prevIncome`이다.

```
ProcessIncome.kt:155-156        val grossIncome = phpRound(income); prevIncome[nation.id] = grossIncome
WorldActionContext.kt:332-334   recorder.recordKv("nation_env", nationId.toString(), "prev_income_$resource", value)
AiInstanceState.kt:137-146      val prevIncomeGold = (nationStor["prev_income_gold"] as? Number)?.toDouble() ?: 1000.0
                                val rawMax = maxOf(minimumResourceActionAmount, prevIncomeGold/10.0, prevIncomeRice/10.0,
                                                   nation.gold/5.0, nation.rice/5.0, (year-startYear-3)*1000.0)
```

이 KV의 **유일한 소비자는 NPC 경제**다. `maxResourceActionAmount`(`AiInstanceState.kt:148-157`)가 NPC 헌납·포상·몰수의 **금액**을 정하고, 그 값의 6개 후보 중 둘이 `prev_income_{gold,rice}/10`이다. v2 leaf가 이 KV를 쓰지 않으면 전 국가가 영구히 `?: 1000.0` 폴백으로 떨어져 해당 두 항이 각각 100으로 고정된다. 하필 §2.6이 스스로 최대 리스크로 지목한 **NPC 경제**를 친다. (v1은 별도 DB·별도 프로파일이라 무영향 — 패러티 결함이 아니라 v2 설계 공백이다.)

> **판정: (a) — `V2ProcessCityIncome`이 `prev_income_{gold,rice}` KV를 계속 쓴다. 값은 그 국가 소속 도시 원장 수입의 합계다.**

(b)(폴백 수용)와 (c)(NPC 산식 v2 포크)를 기각한 이유를 적는다.

- **(c)가 먼저 막힌다.** NPC 금액 산식은 `logic/src/main/kotlin/opensamguk/logic/ai/AiInstanceState.kt`에 있고 `logic/src/main/kotlin/**`는 §7.1의 **T1(수정·삭제 0건)**이다. 포크하려면 T1을 열거나 `ai/*`를 v2로 복제해야 하는데, 후자는 §2.3이 이미 기각한 "이중 진실"을 NPC 경제 전체에 대해 다시 만드는 일이다.
- **(b)는 비용이 (a)보다 크지 않은데 손실만 크다.** (a)의 구현 비용은 도시 루프에서 국가별 누적자 하나를 더 돌려 기존 KV 키에 그대로 쓰는 것 — **새 표 0, 새 컬럼 0, 새 소비자 0**이다. 폴백을 수용해서 절약되는 코드가 사실상 없다.
- **의미도 (a)가 맞다.** 이 KV의 뜻은 "지난 정산의 국가 총 세수"이고, v2에서 그 양은 사라진 것이 아니라 **도시 원장으로 위치만 옮겼다.** 국가 단위 합계는 여전히 정의된다.

**정직하게 좁힌다 — (a)는 두 항 중 하나만 되살린다.** `rawMax`의 6개 후보 중 `nation.gold/5.0`·`nation.rice/5.0`은 (a)를 채택해도 작아진다. 국고에 실제로 세수가 들어오지 않는 것이 이 설계의 의도된 divergence이기 때문이다(바로 아래 대가 4). 즉 (a)가 고치는 것은 **폴백으로 인한 정보 손실**이고, **국고 잔고 자체가 얇아지는 효과는 고치지 않는다.** 후자는 결함이 아니라 설계의 대가이며 오픈 전 관측 항목이다.

**세율 곱 위치 divergence가 여기에도 상속된다.** v1의 `grossIncome`은 도시 합산 **후** 세율 1회 곱이고 v2 합계는 도시마다 곱한 값의 합이므로, 같은 월드라도 두 값은 반올림 잔차만큼 다르다(§2.2 결정 3). 이는 이미 선언된 divergence이므로 새로 선언할 것이 없다.

**이 판정이 두 하드 제약을 어떻게 동시에 만족하는가.**

- **v1 프로덕션 파일 0줄** — 35~42개 쓰기 지점은 **하나도 건드리지 않는다.** leaf 치환은 v2 DB의 `event` 행 + 새 파일 등록 체인으로 끝난다(§7.1-2에 경로 상술).
- **precheck 거짓 통과 없음** — `nation.gold`는 국고의 **실제 잔고**이고, 그 잔고를 쓰는 커맨드는 전부 국고에서만 쓴다. `Presets.kt:312-318`이 읽는 값과 차감되는 값이 동일 계정이므로 거짓 통과가 성립할 여지가 없다. (덧: 전수 확인 결과 국고를 **변경하는 커맨드 중 10개는 애초에 `ReqNationGold`/`ReqNationRice`를 선언하지 않는다** — 헌납·몰수·초토화·군량매매·물자조달·장비매매·탈취·하야·등용수락·감축. 이것은 v1의 기존 성질이고 v2가 악화시키지 않는다.)

**대가를 감춘 곳 없이 적는다.**

1. **묘섭 충실도가 내려간다.** 묘섭에는 국고가 아예 없다 — `myostart.md:116` "국가의 금, 병량은 국가 단위가 아닌, 도시 단위로 관리가 됩니다", 헌납도 **도시에** 한다(`help__start__intermediate__othercommands.md:75`), 포상도 **도시의** 금을 쓴다(`positionrole.md:138,233`). v2 오픈판은 국고와 도시 원장이 **병존**한다. 완전 귀속은 포상·헌납을 v2 전용 변형으로 포크할 때 닫히며, 그것은 **오픈 후**다(§9.3).
2. **§1.2의 정의가 좁아진다.** "금·병량의 소유 행 전부가 city로 내려간다"는 성립하지 않는다. 성립하는 것은 "**정기 재정 순환(세수→봉록)의 소유 행이 city로 내려간다**"이다. §1.2를 그렇게 고쳤다.
3. **국력·통계 수치가 v2에서 달라진다.** `PostUpdateMonthly.kt:123`과 `CheckStatisticCalculator.kt:106,109,200`이 `nation.gold + nation.rice`로 국력·통계를 만드는데, v2 국고에는 세수가 없으므로 값이 작아진다. 도시 원장을 합산하도록 고치면 v1 파일 수정이므로 **오픈 후**로 둔다. v2 내부 일관성은 유지된다(모든 국가가 같은 규칙).
4. **국가 지출 전 항목이 세원을 잃고 이전(transfer) 수입에만 의존하게 된다 (개정 3차 신설 — 개정 2차가 세지 않은 네 번째 대가).**

   개정 2차는 유입·유출을 **열거**했으나 **충분성을 한 번도 묻지 않았다.** "남는 유입·유출은 위 표 그대로 전부 실제 잔고 위에서 돈다"는 회계적으로 참이지만 게임적으로는 미검증 단정이다. 실측하면 이렇다.

   **치환 후 국고의 `자동` 유입은 둘뿐이다.**

   | 자동 유입 | 실측 | 성질 |
   |---|---|---|
   | `ProcessWarIncome.kt:70-77` | 국가별 `nationGoldAdds`만 만든다. 반환 구조(`ProcessWarIncomeResult`)에 rice 가산 항이 **없다**. `nation.level <= 0`(방랑군)은 `:71`에서 제외 | **전쟁이 나야 하고, gold만. rice 0** |
   | `UpdateNationLevel.kt:128,145-146` | `val grant = newLevel * 1000` → `gold += grant`, `rice += grant` | 작위 상승 시 **1회성** |

   > **개정 4차 — 3차 채점의 오프바이원 지적 중 하나는 반박한다.** 채점자는 `UpdateNationLevel.kt:145-146`을 `:146-147`로 고치라고 했으나, 파일을 열면 `:143 val updatedNation = nation.copy(` / `:144 level = newLevel,` / **`:145 gold = nation.gold + grant,`** / **`:146 rice = nation.rice + grant,`** / `:147 meta = updatedMeta,`다. **원문이 옳고 지적이 틀렸다** — `:147`은 `meta`이지 자원이 아니다. 같은 지적의 다른 절반(`CheJeungchuk.kt:35-37` → `:36-38`)은 유효해서 위에서 정정했다.

   나머지 유입(헌납·몰수·초토화·군량매매세·물자조달·장비매매·탈취·하야·등용수락·정복노획)은 **전부 장수 개인 잔고를 국고로 옮기는 이전**이다. → **v2 국고 = 세원 없이 이전으로만 사는 계정.**

   **유출은 그대로 남는다.**

   | 유출 | 실측 금액 |
   |---|---|
   | 병종연구 9종 | 100,000+100,000 × **5종**(`EventMuhuiYeongu.kt:46-47` · `EventWonyungnobyeongYeongu.kt:44-45` · `EventHwaryunchaYeongu.kt:46-47` · `EventGeukbyeongYeongu.kt:44-45` · `EventSangbyeongYeongu.kt:44-45`) + 50,000+50,000 × **4종**(`EventHwasibyeongYeongu.kt:44-45` · `EventEumgwibyeongYeongu.kt:44-45` · `EventSanjeobyeongYeongu.kt:44-45` · `EventDaegeombyeongYeongu.kt:46-47`) = **총 700,000 gold + 700,000 rice** |
   | 물자원조 | `CheMuljaWonjo.kt:93-94` `aidLimit = nationLevel * GameConst.coefAidAmount`, `GameConst.kt:158 coefAidAmount = 10000` → 작위 9면 **회당 금·쌀 각 ≤ 90,000** |
   | 증축 | `CheJeungchuk.kt:36-38`(개정 4차 오프바이원 정정 — `:36` KDoc `che_증축.php:82-86`, `:37` `fun getCost`, `:38` 본문) `develCost * expandCityCostCoef + expandCityDefaultCost` = `develcost*500 + 60000` (`GameConst.kt:140-141`) |
   | 포상 | `ChePosang.kt:80-82` `amount = valueFit(argAmount, 0, balance - reserve)`, `reserve = basegold`(0) / `baserice`(2000) (`GameConst.kt:41-42`) → **금은 0까지, 쌀은 2000까지 한 번에 소진 가능** |
   | 반년 감쇠 | `ProcessSemiAnnual.kt:167-177` `res > 100000 → ×0.95` / `> 10000 → ×0.97` / `> 1000 → ×0.99`. **이 leaf의 반환값(`ProcessSemiAnnualResult`)에 nation 가산 항이 0개 = 순수 드레인** |

   즉 v2 국가는 **700,000+700,000짜리 병종연구를 이전 수입만으로 메우는 경제**가 된다. 국가 커맨드가 통째로 죽는 것은 아니다 — NPC는 실제로 헌납한다(`AutorunGeneralPolicy.kt` `canNPC헌납` 기본 true). 그러나 그 전환은 **밸런스 변경이지 회계 항등식이 아니며**, 개정 2차에는 이를 인지·정량화한 흔적이 없었다.

   > **오픈 전 관측 항목으로 등록한다.** 관측 지표는 셋 — (i) 국가별 국고 gold/rice의 월간 추이, (ii) 병종연구 9종의 최초 완료 시점(v1 대비 지연), (iii) `maxResourceActionAmount`의 국가별 분포(위 `prev_income` 판정의 실제 효과). 세 지표가 "지출이 사실상 불가능"을 가리키면 완화안은 §9.3의 **국고 완전 귀속**(포상·헌납의 v2 전용 변형)을 오픈 후에서 앞으로 당기는 것이고, 그때 비용은 v1 커맨드 포크 2건이다.

   **정직하게 남기는 한계.** 이 항목은 정량 *열거*이지 정량 *예측*이 아니다. "이전 수입이 700,000을 언제 메우는가"는 헌납 빈도·NPC 정책·유저 수에 달려 있어 **시뮬레이션 없이는 알 수 없다**(자기채점 취약점 3과 같은 뿌리). 그래서 대가로 선언하고 관측 항목으로 등록할 뿐, 여기서 결론을 내지 않는다.

### 2.4 도시병사와 공백지화 — 정확한 판정 순서

묘섭의 공백지화 규정은 두 곳에 있고 서로 일치한다.

> "빈 성이 되는 유일한 경우는 도시병사가 없거나, 너무 적은 상태에서 도적이 발생할 경우, 해당 도시가 빈 성이 됩니다."
> — `help__start__peq__peq.md:51`
> "도적이 발생할 경우, 도시 병사가 일정수 감소하게 되는데, 만약 도시병사가 너무 적게 남아 있어서 도시 병사가 0이 되어버리면, 해당 도시가 공백지가 됩니다."
> — `help__start__beginner__basicdomestic.md:204`

핵심은 **공백지화가 독립 스캔이 아니라 도적 사건 안의 후속 판정**이라는 점이다. "매턴 garrison==0인 도시를 훑어서 비운다"가 아니라 "도적이 난 도시에서 감소를 적용한 직후 0이면 그 자리에서 비운다"다. 이 순서를 지켜야 인과가 한 트랜잭션에 남고 로그가 읽힌다.

여기서 실측으로 확인한 제약이 하나 있다. **devsam PHP에는 도적 침입 이벤트가 없다.** `che_도적`은 국가 성향(`logic/src/main/kotlin/opensamguk/logic/traits/ActionNationType.kt:107-108`, "계략↑ / 금수입↓ 치안↓ 민심↓")이지 월드 이벤트가 아니다. 월간 재해 leaf는 `RaiseDisaster` 하나이고, 그것은 pop·agri·comm·secu·def·wall·trust를 곱으로 깎을 뿐 병력을 건드리지 않는다(v1에 도시병사가 없으니 당연하다).

그래서 두 갈래가 있었다.

- **(a) v2 전용 도적 이벤트 신설.** 자체 시드 DRBG를 하나 더 만든다. 기술적으로 안전하다 — `RaiseDisaster.kt:13`이 이미 *"Self-seeds its OWN DRBG (it does NOT borrow `$monthlyRng`)"* 라고 증명된 패턴을 쓰고 있어서, 같은 방식이면 v1 스트림과 물리적으로 분리된다. 그러나 새 이벤트·새 시드·새 확률표·새 밸런싱이 붙는다.
- **(b) v1 `RaiseDisaster`가 이미 확정한 재난 상태를 트리거로 재사용.** `RaiseDisaster`는 매월 1/4/7/10월에 돌며(`RaiseDisaster.kt:13`) 대상 도시의 `city.state`에 stateCode를 쓴다(`LogicEntities.kt:78`, `RaiseDisaster.kt:30`). v2 leaf는 그 결과를 **읽기만** 한다. draw 0, 새 시드 0, 새 확률표 0.

**(b)를 채택한다.** 이유는 세 가지다. 첫째, draw가 0이면 v1 draw 순서·횟수에 영향을 줄 물리적 경로 자체가 사라진다(§7). 둘째, `RaiseDisaster`는 `startYear + 3` 이전을 통째로 건너뛰므로(`RaiseDisaster.kt:26,151`) **개막 3년 동안 공백지화가 원천적으로 일어나지 않는다** — 유저가 적은 오픈 직후에 정확히 필요한 유예다(§2.6). 셋째, 1/4/7/10월 4회로 빈도가 고정되어 밸런싱 변수가 하나 줄어든다.

대신 원본과의 divergence를 명시한다: **오픈삼국 v2의 공백지화 트리거는 묘섭의 "도적"이 아니라 v1의 "재난"이다.** 묘섭식 독립 도적 이벤트는 (a) 경로로 오픈 후에 붙인다.

판정 절차는 이렇다.

```
V2CityGarrisonAttrition  — v2 프로파일 전용 월간 leaf
  실행 시점: 1·4·7·10월에만, RaiseDisaster leaf 직후 (← 개정 2차)
  RandUtil 미주입 (생성자에 rng 파라미터 없음 → draw 0이 타입 수준에서 보장)

  if month not in {1,4,7,10}: return                         # EventStore.kt:171,180,190,197
  for city in world.cities  ordered by city_id ASC:          # 순회 순서 고정
      if city.state not in BAD_STATE_CODES: continue         # RaiseDisaster가 쓴 재난 코드만
      loss  = attritionLoss(city.garrison, activeGeneralCount)   # 순수 함수, draw 0
      after = max(0, city.garrison - loss)
      record.dirty(v2_city_ledger[city.id].garrison = after)
      if after == 0 and city.nationId != 0:
          record.dirty(city.nationId = 0)                    # ← 공백지화, 감소 직후 같은 반복 안에서
          record.log(v2 로그 채널, before/after 포함)
```

**개정 2차 — 원안의 두 결함을 고쳤다.**

① **월 게이트와 실행 위치.** 원안은 "매월, v1 leaf 전부 뒤에 마지막으로"라고 썼는데 그러면 재난이 없는 8개월 동안도 `city.state`가 살아 있어 **같은 재난이 최대 3개월 연속으로 감소를 일으킨다.**

근거는 둘이다. 첫째, **leaf 발화 일정.** `RaiseDisaster`가 실행되는 달은 `event` 행이 정한다 — `logic/src/main/kotlin/opensamguk/logic/event/EventStore.kt`의 `DEFAULT_EVENTS` 중 `a("RaiseDisaster")`를 담은 행은 **넷뿐**이고 각각 1월(`:165` 조건 · `:171` 액션)·4월(`:179` · `:180`)·7월(`:184` · `:190`)·10월(`:196` · `:197`)이다. 둘째, **리셋 위치.** `logic/src/main/kotlin/opensamguk/logic/world/RaiseDisaster.kt:146-148`의 `state <= 10 → 0` 리셋이 **leaf 본문 안**에 있고 연도 게이트(`:151`)보다 **앞**이므로, leaf가 돌지 않는 달에는 리셋도 돌지 않는다. 따라서 v2 leaf도 같은 4개월로 게이트하고 `RaiseDisaster` **직후**에 둔다. `city.state`를 쓰는 곳은 `RaiseDisaster` 하나뿐이므로(전수 grep 확인) 중간에 값이 바뀌지 않는다.

**개정 3차 — 근거 파일 오귀속 정정.** 개정 2차는 이 월 게이트의 근거로 `RaiseDisaster.kt:98` `BOOMING_RATE`를 댔으나 **그것은 leaf 발화 일정이 아니라 호황 확률표**다(`mapOf(1 to 0.0, 4 to 0.25, 7 to 0.25, 10 to 0.0)` — 키가 우연히 같은 넷일 뿐, 값은 확률이고 1·10월은 0이라 호황이 아예 없다는 뜻이다). 정본 근거를 위 `EventStore.kt` 행으로 교체했다. **결론(월 게이트 {1,4,7,10} + `RaiseDisaster` 직후 배치)은 그대로 참이다** — 바뀐 것은 근거뿐이다.

② **`BAD_STATE_CODES`가 이제 UNKNOWN이 아니다.** 원안은 "구현자가 읽어 확정"이라고 미뤘으나, 값은 `RaiseDisaster.kt:104-127`의 `DISASTER_TEXT` 항목이 실제로 쓰는 stateCode 집합이다.

> `BAD_STATE_CODES = {3, 4, 5, 6, 7, 8, 9}` (`RaiseDisaster.kt:104-127`).

**개정 3차 — 라벨을 원본 문자열로 교체한다(개정 2차의 라벨 7개는 전부 날조였다).** 개정 2차는 이 자리에 "홍수3·메뚜기4·태풍5·지진6·가뭄7·역병8·전염병9"라고 적었는데 **7개 중 7개가 `DISASTER_TEXT`의 실제 값과 다르고**, 그중 "가뭄"·"전염병"은 원본에 아예 없는 이름이다. 파일을 열어 집합만 세고 이름은 지어냈다 — CLAUDE.md 패러티 규율 5(날조 금지) 직접 위반이다. 집합 `{3..9}`만 참이었다. 실제 매핑은 다음과 같고, 각 stateCode를 쓰는 `LogTriple` 줄을 전부 적는다.

| stateCode | 원본 재난 문구 (`RaiseDisaster.kt`) | 해당 줄 |
|---|---|---|
| 3 | "추위가 풀리지 않아 얼어죽는 백성들이 늘어나고 있습니다." / "혹한으로 도시가 황폐해지고 있습니다." / "눈이 많이 쌓여 도시가 황폐해지고 있습니다." | `:108` · `:122` · `:124` |
| 4 | "역병이 발생하여 도시가 황폐해지고 있습니다." | `:106` |
| 5 | "지진으로 피해가 속출하고 있습니다." | `:107` · `:113` · `:118` · `:123` |
| 6 | "태풍으로 인해 피해가 속출하고 있습니다." | `:114` |
| 7 | "홍수로 인해 피해가 급증하고 있습니다." | `:112` |
| 8 | "메뚜기 떼가 발생하여 도시가 황폐해지고 있습니다." / "흉년이 들어 굶어죽는 백성들이 늘어나고 있습니다." | `:117` · `:119` |
| 9 | "황건적이 출현해 도시를 습격하고 있습니다." | `:109` · `:125` |

> 대칭적으로 `BOOMING_TEXT`(`:130-133`)의 **호황 2**(`:131`)·**풍작 1**(`:132`)은 제외한다. 0은 "재난 없음"이다.

부수로 확인된 사실 하나 — stateCode는 **1:1로 이름에 대응하지 않는다**(3에 셋, 5에 넷, 8에 둘이 붙는다). 따라서 v2 로그가 재난 종류를 표시하려면 `city.state` 정수만으로는 부족하고 `RaiseDisaster`가 고른 `LogTriple`을 알아야 한다. **v2 leaf는 종류를 표시하지 않고 감소량 before/after만 로그에 싣는다**(§8 R3) — 이 한계를 감수하는 대신 v1 leaf의 산출물에 의존하지 않는다.
- `attritionLoss`는 순수 함수이고 인자는 `(garrison, activeGeneralCount)` 둘뿐이다. 장수 수 스케일링은 §2.6.
- 공백지화는 `city.nationId = 0`만 쓴다. 관직·부대 등 부수 정리는 v1 `ConquerCity`가 담당하는 영역이라 **재사용하지 않고 오픈 후로 미룬다** — v1 정복 경로를 v2에서 호출하면 그 경로의 로그·draw를 끌어들이게 되기 때문이다.

### 2.5 도시병사가 증가하는 경로

감소만 있으면 모든 도시는 결국 빈다. 묘섭의 증가 경로는 병사 보충이다.

> "병사 보충 — 병사를 보충합니다. 실제로는 도시 병사의 수가 증가 합니다." — `help__start__beginner__basicdomestic.md:189`

v2 전용 개인턴 커맨드 하나를 만든다(R4). 도시 원장의 금을 소모해 `garrison`을 올린다. v1 징병(`che_징병` 계열)은 장수 개인 병력을 만드는 별개 커맨드이므로 손대지 않는다. 이 커맨드는 인테이크 202를 성공으로 취급하지 않는다 — 엔진 핸들러는 성공·거절 모두 `TurnDaemonCommandResult(ok, reason)`을 돌려주고, FE는 `pollCommandResult(requestId)`로 `RESOLVED`까지 폴링한다(OPENSAM-13/135 규약).

묘섭에는 전투 손실의 20%가 도시병사로 복귀하는 경로도 있으나(`help__start__intermediate__intermediatebattle.md:394`, `help__start__other__etcetera.md:90`), 이는 전투 엔진 접촉이므로 **오픈 후**다.

### 2.6 유저가 적을 때 — 이 설계안의 최대 리스크와 묘섭 자신의 완화책

이 설계안이 지는 가장 큰 리스크를 감추지 않는다. **City-oriented는 유저 수에 정비례해 관리 부담을 만든다.** 운영자 본인이 정확히 그 이유로 도시 지향 기능의 즉시 적용을 유보했다.

> "물론 묘섭 정식 오픈 이후 바로 이러한 기능들이 생긴다고는 보장할 수 없습니다. 묘섭 오픈베타 시절부터 태수, 군사 자리수보다 유저수가 더 적은 상황인 만큼, 바로 적용하기 어렵기 때문이지요." — `help__start__peq__peq.md:46`
> "장수 1명당 관리해야 하는 도시수가 많기 때문이지요." — `help__start__peq__peq.md:51`

opensamguk v2는 오픈 직후 유저가 적다. 그래서 묘섭이 실제로 쓴 완화책 두 가지를 그대로 가져온다.

첫째, **장수 수 기반 스케일링.** 묘섭은 등록 장수 300명을 기준으로 삼아 적을수록 도적 피해를 줄였다.

> "장수수 300명 기준으로 장수수가 적은 경우, 도적 침입시 도시 병사 감소량이 최저 6분의 1까지 감소합니다."
> — `help__start__other__etcetera.md:64` (같은 절 `:58`에는 내정 상승량이 최대 6배까지 증가한다는 대칭 규정이 있다)

`attritionLoss(garrison, activeGeneralCount)`의 두 번째 인자가 바로 이 스케일이다. 300명 기준·최저 1/6이라는 **두 수치는 묘섭 원문에 있는 값**이므로 그대로 쓰고, 그 사이의 보간 곡선은 원문에 없으므로 **UNKNOWN**으로 두고 구현 티켓에서 선형으로 정하되 "묘섭 미명시, 오픈삼국 결정"으로 표기한다.

둘째, **NPC 자동 내정.** 운영자의 답이 그대로다.

> "NPC를 통한 내정으로 테스트 중에는 관리할 수 있도록 하고" — `help__start__peq__peq.md:51`

opensamguk은 NPC 자동 내정 자체를 **이미 갖고 있다** — P5의 `ai/*` GeneralAI 4-layer autorun policy가 그것이다.

**그러나 개정 2차에서 원안의 "오픈 경로 증분 0"을 철회한다.** `ai/*`는 PHP 충실 포팅이고 후보 집합이 `candidateAllowed` 게이트로 고정되어 있으므로, **v2 전용 커맨드(병사보충 R4·수송 R5)를 절대 선택하지 않는다.** 즉 NPC 도시는 `garrison`이 단조 감소만 하고 보충되지 않는다. 이것을 감추지 않고 적는다.

> **알려진 천장 — NPC 도시 병력 고갈.** v2 오픈 시점에 NPC가 소유한 도시는 `garrison`을 보충할 주체가 없다. 완화는 두 겹이다 — (i) `RaiseDisaster.kt:151`이 `startYear + 3` 이전을 통째로 건너뛰므로 **게임 내 3년 동안 감소 자체가 0**이고, (ii) `attritionLoss`의 장수 수 스케일이 유저가 적을수록 감소를 줄인다. 따라서 **마감은 "오픈 후"가 아니라 "게임 내 3년 이내"**다. 오픈 후 티켓 `v2 NPC 도시 정책`(§9.3)이 이 마감을 갖는다. 이 마감을 못 지키면 대체안은 `attritionLoss`에서 NPC 소유 도시를 제외하는 한 줄이며, 그때는 게임성이 그만큼 준다.

이 완화책들이 있어서 "유저가 적으면 도시 지향이 벌이 된다"는 리스크가 **오픈 시점에는** 관리 가능하다. 영구 해결은 아니다.

---

## 3. 묘섭식 인맥 — 관계 수치 없이 4축으로 (시험지 3)

### 3.1 묘섭에는 관계 수치가 없다

전수 확인 결과는 채점기 기준선과 같다. 친밀도·의형제·사제·결의·혼인·일기토·장수→군주 충성도 어느 것도 묘섭 도움말 37페이지에 없다. 상성은 개인↔국가(임관 판정)일 뿐 개인↔개인이 아니다. 그런데도 묘섭은 사람에 대한 의존이 강한 서버로 기억된다. 그 의존을 만드는 것이 네 축이다.

| 축 | 묘섭 근거 | 무엇이 사람을 필요하게 만드나 |
|---|---|---|
| 인사권 | 인사부로 태수·군사·임원진·지휘부를 모두 임명(`help__start__advanced__controlgeneral.md:89`), 발령·추방 권한이 지휘부 위계별로 갈림(`help__start__beginner__position.md:104,107`) | 누가 어느 도시를 맡을지 사람이 정한다 |
| 배치효과 | 태수·군사 담당도시 체류 보정(`help__start__intermediate__positionrole.md:152-171`), 임원진 6종 체류 효과(`positionrole.md:191-201`) | 그 사람이 **거기 있어야** 효과가 난다 |
| 감시 | 감찰부에서 전 장수의 관직·병력·훈련·사기·삭턴·턴시간·턴정보 열람(`help__start__intermediate__inspection.md:57,60`), 이를 근거로 서신·추방·포상 우선순위 결정(`inspection.md:72,78`) | 안 하는 사람이 보인다 |
| 자원분배 | 포상은 도시의 금·병량을 소모(`positionrole.md:138`), 태수·군사는 담당도시에서 담당도시 장수에게만(`positionrole.md:141`), 수송(`intermediatebattle.md:361`), 세율·지급률(`controlcity.md:172,184`) | 나눠줄 것이 유한하고 누가 나눌지 정해져 있다 |

이 넷의 공통 분모가 도시 원장이다. 배치효과는 원장의 수입 계수를 바꾸고, 자원분배는 원장을 옮기고, 감시는 원장과 그 소비자를 보고, 인사권은 원장을 만질 사람을 정한다. **§2의 원장이 없으면 이 넷은 전부 공중에 뜬다.** 이것이 "도시 중심과 인맥 중심은 하나"라는 주장의 실체다.

### 3.2 기존 `officer_level` 축 위에 얹는 경로 — 평행 축 신설 없음

새 축을 만들 필요가 없다는 근거는 코드에 있다. `ProcessIncome.kt:59-60`의 KDoc이 `officerCntByCity`를 이렇게 정의한다.

```
`officerCntByCity` is PHP's
`SELECT officer_city, count(*) ... WHERE officer_level IN (2,3,4) AND city = officer_city GROUP BY officer_city`.
```

`officer_level IN (2,3,4)`는 태수·군사·종사이고(`LogicEntities.kt:46`), `city = officer_city`는 **담당도시에 실제로 체류 중일 때만** 센다는 뜻이다. 그리고 이 지도는 `ProcessIncome.kt:126-129`에서 `getGoldIncome`·`getRiceIncome`·`getWallIncome` 세 함수 모두에 주입된다. 즉 opensamguk은 이미 **"담당 관직자가 담당도시에 있으면 그 도시의 수입이 바뀐다"** 를 구현하고 있다. 묘섭의 태수·군사 체류 보정(`positionrole.md:152-164`, 치안 감소 절반/소멸 + 세금·수확 +10%/+20%)과 **구조가 같다.**

그래서 v2가 하는 일은 계수 신설이 아니라 귀속처 변경 한 가지다.

```
v1:  officerCntByCity ──▶ calcCityGoldIncome(city, cnt, …) ──▶ Σ ──▶ ×(taxRate/20) ──▶ nation.gold
v2:  officerCntByCity ──▶ calcCityGoldIncome(city, cnt, …) ──▶ ×(taxRate/20) ──▶ v2_city_ledger[city].gold
                          (같은 함수, 같은 계수, 다른 귀속처 — Σ만 사라진다)
```

**계수는 손대지 않되, 이제 인용은 한다.** 원안은 "확인하지 않았으므로 숫자를 적으면 날조"라며 형태를 비워 뒀다. 개정 2차에서 확인했다 — `logic/src/main/kotlin/opensamguk/logic/domestic/IncomeTick.kt:41`이 `income *= 1.05.pow(officerCnt)`, 즉 **관직자 1명당 ×1.05의 곱**이다. v2는 이 함수를 **호출**하므로 계수를 옮겨 적을 일 자체가 없다.

네 축이 오픈 경로에서 어디까지 오는지는 이렇다(개정 2차 R-번호 기준, §9.2).

- **인사권** — 증분 0. `officer_level`·`officer_city` 세팅과 임명·발령 커맨드는 v1에 이미 있다(최근 커밋 `a5233aa0 OPENSAM-7 wire personnel controls`). v2는 그대로 쓴다. 다만 v1 임명 커맨드의 완전한 목록·권한 위계가 묘섭 위계(`position.md:104,107`)와 얼마나 일치하는지는 **UNKNOWN**이며, 차이는 오픈 후 대조 대상이다.
- **배치효과** — R2에 포함(귀속처 변경). 임원진 6종 추가 계수는 §5 판정에 따라 **오픈 후**.
- **감시** — 감찰부 전용 화면은 **오픈 후**로 보낸다. 순수 read-only이고 F3의 read 컨트롤러·랭킹 화면이 이미 장수 목록을 제공하므로, 오픈 시점에 이것이 없다고 게임이 성립하지 않는 것은 아니다. 티켓 1개를 아끼는 판단이다.
- **자원분배** — 봉록 도시 차감은 R2에 흡수(§2.2에서 수입과 같은 leaf), 수송은 R5로 오픈 경로에 들어간다. 포상은 v1 커맨드 그대로 두고 재원도 **국고에 남긴다**(§2.3) — 묘섭은 도시 금을 쓰지만(`positionrole.md:138,233`) 그 포크는 오픈 후다.

---

## 4. 장수↔장수 관계망 — 오라클 없는 divergence (시험지 4)

### 4.1 지위 선언

**장수↔장수 관계망은 묘섭에도 devsam PHP에도 없다.** 묘섭 도움말 37페이지 전수에서 친밀도·사제·의형제·결의·혼인·일기토·장수→군주 충성도 어느 것도 나오지 않는다. devsam 쪽 유일한 관계형 신호는 `general.meta`의 `affinity` 스칼라(1~150)인데, 이것도 장수↔장수가 아니라 **개인↔국가**로만 소비된다 — `logic/src/main/kotlin/opensamguk/logic/actions/personnel/CheRandomImgwan.kt:180-183`이 후보 국가와의 원형 거리를 점수화하고, `logic/src/main/kotlin/opensamguk/logic/actions/intake/RulerSuccession.kt:18-21`의 `npcMatch2`가 군주 친화도와의 원형 거리를 계산한다. 장수 A와 장수 B 사이의 어떤 값도 저장되지 않는다.

따라서 관계망은 **PHP 골든 오라클이 존재하지 않는 오픈삼국 독자 divergence**다.

### 4.2 이 라운드의 방침 전환 — `spec:388`의 한 조항이 뒤집힌다

제품 정본은 관계의 소비를 이렇게 못 박아 두었다.

> "장수 관계망: 사제·동료·라이벌·원한·구명 기록이 **능력치 버프가 아니라** 명령 신뢰와 협상 태도에 영향을 준다."
> — `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:388`

2026-07-25 사용자 결정이 이 중 "능력치 버프가 아니라"를 **뒤집는다.**

> "관계는 능력치 버프에도 영향을 줘야지. 예를 들면 유비 관우 장비 의형제제라던지." — 2026-07-25 사용자 결정

두 가지를 분명히 한다.

첫째, **`spec:388` 본문은 지금 고치지 않는다.** 이 문서는 채점 대상인 설계안이지 정본이 아니다. 채점기 §채택 규칙이 "채택되어 오픈 경로가 늘어나는 경우 ADR-LITE-019 개정이 따른다"고 정한 순서를 그대로 적용하면, 정본 개정은 **채택 이후**다. 채택 전에 정본을 고치면 채점되지 않은 설계가 정본이 된다. 따라서 `spec:388`은 **채택 시 개정 대상**으로 등록만 해 둔다 — 개정문은 "능력치 버프가 아니라"를 "능력치 보정과 명령 신뢰·협상 태도 양쪽에"로 바꾸는 한 줄이다.

둘째, **관계 종류가 하나 늘어난다.** 원안은 "`spec:388`이 명명한 5종뿐, 여섯 번째를 만들지 않는다"였는데 이 결정으로 그 문장을 철회한다. 사용자가 예로 든 도원결의는 5종 어디에도 들어가지 않는다 — 사제도 아니고, 동료는 §4.5에서 "같은 작전 참가"로 정의한 emergent 개념이며, 라이벌·원한·구명도 아니다. 그래서 **`SWORN`(결의) 1종을 추가해 6종**으로 간다. 그리고 이 1종은 게임이 만드는 것이 아니라 **시나리오 시작 시점에 이미 존재하는 데이터**다(§4.4).

### 4.3 폐기하는 격리 장치 2개, 살아남는 2개, 그리고 대체 격리축

원안은 "능치 버프 금지"를 네 개의 빌드타임 장치로 강제했다. 그중 **둘은 이 결정으로 성립하지 않는다.** 정직하게 폐기한다.

- **장치 1 폐기 — "공개 타입이 숫자가 아니다"(`TrustGrade` 열거형).** 스탯 보정은 정의상 수치다. "접을 수 있는 것을 주지 않겠다"는 장치는 "접어야 한다"는 요구와 양립할 수 없다. `TrustGrade`는 §4.7의 **비수치 소비(가신 서약 거부 게이트)** 전용 조회 타입으로 남지만, **격리 장치의 지위를 잃는다.**
- **장치 2 폐기 — "`stats/**`가 관계 패키지를 import 금지".** 관계 보정이 `ActionPipeline` fold에 들어가는 순간 스탯 계층이 관계 모듈을 알아야 한다. 금지선을 그 자리에 그을 수 없다.

살아남는 둘은 근거를 다시 검토한 결과 **둘 다 유효하며, 하나는 오히려 강해졌다.**

- **장치 3 유지 — `RandUtil` 미주입.** 능력치에 붙는다고 draw가 생겨야 할 이유가 없다. 보정은 `value + bonus` **가산**이지 확률이 아니다. 그리고 코드가 이미 같은 말을 계약으로 적어 두었다 — `logic/src/main/kotlin/opensamguk/logic/stats/ActionPipeline.kt`의 `getWarPowerMultiplier` KDoc이 **"NO RNG inside the fold"**를 fold 규약으로 못 박고 있다. 관계 모듈은 이 규약의 예외가 아니라 준수자다. 생성자·함수 어디에도 `RandUtil` 파라미터가 없으므로 draw 0이 타입 수준에서 증명된다.
- **장치 4 유지 — v2 프로파일 게이팅. 다만 이제 이것이 격리의 주축이다.** 원안에서는 넷 중 하나였으나 1·2가 빠진 지금 격리의 무게 전부가 여기 실린다. 그래서 "v2 프로파일에서만"을 규율이 아니라 **측정 가능한 명제**로 바꿔야 한다.

#### 대체 격리축 — source 목록 한 자리, 그리고 그것을 증명하는 세 개의 테스트

코드가 이미 자리를 만들어 두었다. `GeneralActionPipeline`은 **생성자로 받은 모듈 리스트**를 왼쪽부터 접고, 그 리스트를 만드는 유일한 곳이 `GeneralActionModuleFactory.build(...)`이며, 그 KDoc이 규약을 이미 명문화했다.

> "the trait/item families … each implement it in THEIR OWN registry file … and the factory only LOOKS THEM UP — **no family ever edits this factory body**."
> — `logic/src/main/kotlin/opensamguk/logic/stats/GeneralActionModuleFactory.kt:16-18`

따라서 v2 관계 보정은 **팩토리를 고치지 않는다.** 팩토리 바깥에 v2 전용 조립자를 새 파일로 두고, 팩토리가 돌려준 리스트 **꼬리에** 모듈 하나를 덧붙인다.

```
// v2 프로파일에서만 호출되는 새 파일.
V2ActionModuleAssembler(factory, bondIndex):
    factory.build(general, …)  +  RelationStatModule(bonuses)   // ← 꼬리에 1개
```

**왜 꼬리인가.** fold는 왼쪽부터 접히므로(`ActionPipeline.kt:89-90`, 단순 좌측 접기) 삽입 위치가 결과를 바꾼다 — 전투특기의 곱과 관직의 `+lbonus` 합(`OfficerLevelModule.kt:46-47`)이 순서에 민감하다. 꼬리에 붙이면 **기존 모듈이 보는 입력 `acc`가 전부 v1과 동일**해진다. 즉 기존 모듈은 자기 계산이 바뀌었는지조차 알 수 없다. 이것이 위치 선택의 유일한 근거이며, v1 fold 순서를 핀으로 박은 `ActionPipeline.MODULE_ORDER`(`:181-185`)는 손대지 않는다.

#### **개정 2차 — 이 조립자를 실제로 어디서 부르는가 (원안이 비워 둔 자리)**

원안은 "새 파일이니 v1 프로덕션 파일 0줄 수정"이라고 썼다. **조립자를 호출하는 지점을 확인하지 않은 채 쓴 문장이며, 확인 결과 그렇지 않다.** 실측한 주입 지점은 **`logic/` 바깥에 둘** 있고 **둘 다 seam이 없다.**

| # | 주입 지점 | 실측 상태 |
|---|---|---|
| 1 | **엔진 턴 경로** `app/game-engine/src/main/kotlin/opensamguk/engine/turn/EngineGeneralActionPipelineBuilder.kt:14-17` | `final class`(open 아님), 생성자 `(world, startYear)`, `:18-29`에서 레지스트리를 **하드코딩 lazy 필드**로 조립, `:43`·`:54` `private fun modulesFor`. **Spring 빈이 아니고** `DaemonLoopConfig.kt:229`에서 직접 `new` 된다 |
| 2 | **API 표시 경로** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt:367-403` `private fun displayStatBonuses` | 팩토리를 `:377`에서 **인라인 생성**, 파이프라인을 `:392`에서 생성. 생성자(`:92-110`)는 읽기 리포지토리만 주입받는다 |

`@ConditionalOnMissingBean`도, 파이프라인 빌더의 빈 오버라이드도 리포지토리 어디에도 없다. 따라서 v2가 자기 조립자를 끼우려면 **둘 중 하나는 반드시 해야 한다** — (A) 두 지점을 인터페이스 뒤로 빼는 **seam 개설**(= v1 파일 수정), 또는 (B) v2 전용 빌더/컨트롤러를 새 파일로 만들고 **두 지점의 조립 코드를 복제**(엔진 ~40줄, API ~25줄 = 이중 진실 2건).

> **판정: (A) seam 개설을 택한다.** 이유는 (B)의 복제가 v1 조립 순서를 두 번째 장소에 베껴 두는 일이고, 그것이야말로 §7이 막으려는 실패 양식이기 때문이다. seam은 "v1 동작 0 변경 + 골든 재실행 green"으로 증명한다(§7.1 방어선 2 재정의).

**개정 3차 — `OPENSAM-35` 범위를 조회했고, UNKNOWN이 해소되면서 귀속과 일정이 둘 다 바뀐다.**

`OPENSAM-35`(V2-0A production 격리 게이트)의 본문은 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:74-81`의 **0A-a~g 7항목**이고, 전문은 이렇다 — 0A-a v2 route `/game/v2-lab/` namespace 제한(`:75`) / 0A-b `V2_ENABLED`+`v2-sandbox` 동시 조건 route·bean 등록 게이트(`:76`) / 0A-c v2 Flyway location 분리(`:77`) / 0A-d v2 catalog loader root `content/v2/` read-only(`:78`) / 0A-e production compose·s1 profile에서 v2 제거(`:79`) / 0A-f production context v2 0개 architecture test(`:80`) / 0A-g 기준선 artifact 저장(`:81`). 공유 Exit는 "production v2 0개·404·diff 0·gate 녹색·review cleared"(`:74`).

> **파이프라인 seam은 `OPENSAM-35`에 없다.** 7항목 전부가 *격리*(무엇을 production에서 빼는가)이고, *확장점 개설*(무엇을 v2가 끼울 수 있게 하는가)은 하나도 없다.

그러면 R0을 어디에 둘 것인가. **개정 2차는 R0을 "조건부 +1, 그러나 R2도 같은 seam을 쓰므로 무시할 수 없다"고 적었는데, 그 두 번째 절이 거짓이다.** 둘은 같은 seam이 아니다.

| 무엇이 필요한가 | 어디를 여는가 | 누가 필요로 하는가 |
|---|---|---|
| **leaf 등록 체인** | `app/game-engine/.../config/EngineEventConfig.kt:79-81` — `@Bean fun eventActionFactory()` 한 줄 (§7.1-2) | **R2·R3** (오픈 경로) |
| **파이프라인 seam** | `EngineGeneralActionPipelineBuilder.kt` · `DaemonLoopConfig.kt:229` · `FrontInfoController.kt:377,392` | **관계 스탯 모듈뿐** (오픈 후) |

첫째는 Spring 빈 하나에 등록 체인을 잇는 T2 편집이고, 둘째는 `new`로 만들어지는 final 클래스를 인터페이스 뒤로 빼는 구조 변경이다. 파일도 기법도 겹치지 않는다.

그리고 **오픈 경로 R1~R6 중 파이프라인 seam을 필요로 하는 티켓은 하나도 없다.** R2가 부르는 `calcCityGoldIncome`은 `GeneralActionPipeline`을 인자로 받지만(`logic/src/main/kotlin/opensamguk/logic/domestic/IncomeTick.kt:29-44`, 6번째 파라미터 `pipeline: GeneralActionPipeline`, 소비는 `:43` `pipeline.nationIncomeFold(...)`), **v1 leaf가 받는 그 파이프라인을 그대로 받으면 된다** — v2가 파이프라인의 *내용*을 바꿔야 하는 것은 관계 보정뿐이고 그것은 오픈 후다.

> **판정: R0(파이프라인 seam)은 오픈 경로에서 뺀다. 관계망 오픈 후 분해의 선행 티켓 `P0`으로 옮긴다(§9.4). 오픈 경로 총계는 조건부가 아니라 단일값 20이다.**
>
> `OPENSAM-35`를 넓혀 seam을 포함시키는 안도 검토했으나 **기각한다.** 오픈 경로의 어떤 티켓도 소비하지 않는 확장점을 오픈 전에 만드는 것이고(소비자 0인 seam), ADR-LITE-019 개정 대상을 늘리면서 "빨리 열기" 1순위와 정면으로 어긋난다. 소비자(P5)와 같은 웨이브에서 만드는 편이 CLAUDE.md의 foundation-first(생산자→소비자 순서) 규율과도 일치한다.

**부수 발견 1 — 두 경로는 v1에서 이미 갈라져 있다.** `FrontInfoController.kt:377`의 인라인 팩토리는 엔진이 넘기는 `scenarioEffectRegistry`를 **넘기지 않는다.** 즉 표시용 스탯과 엔진 실계산 스탯이 v1에서 이미 다를 수 있다. v2 관계 보정이 이 차이를 **만들지는 않지만 상속**하므로, 관계망 착수 시 표시 경로를 따로 배선해야 한다(그래서 §9.4의 오픈 후 분해에 P6이 따로 있다).

**부수 발견 2 (개정 3차 신설) — 표시 경로는 `GetStatValue`를 통째로 우회한다.** 갈라짐은 `scenarioEffectRegistry` 하나가 아니다.

```kotlin
// app/game-api/.../controller/FrontInfoController.kt:393-394
fun bonus(statName: String, base: Int): Int =
    truncate(pipeline.onCalcStat(logicGeneral, statName, base.toDouble())).toInt() - base
```

이 줄은 `GetStatValue`를 **호출하지 않고** 파이프라인을 직접 돌린다. 따라서 표시 경로에는 부상 곱(`GetStatValue.kt:53`)도, 무력↔지력 교차증강(`:54-61`)도, 파이프라인 앞뒤의 `clamp`(`:63`·`:65`)도 **없다.**

> **결과: §4.7의 실효 상한(통솔 ±6 / 무력 ±8 / 지력 ±8)은 엔진 값이고, 화면은 전 스탯 ±6으로 뜬다.** 교차증강이 없으므로 무력·지력의 +2가 표시되지 않는다.

이것은 v2가 만드는 결함이 아니라 v1에서 상속하는 성질이지만, **관계 보정이 붙는 순간 유저가 실제로 보게 되는 차이**이므로 P6(표시 경로) 산출물에 "엔진 실효값과 표시값의 정합" 항목을 명시한다(§9.4). 오픈 경로 증분은 0이다 — 관계망 전체가 오픈 후이기 때문이다.

**부수 발견 2 — "9소스"는 리스트 길이가 아니다.** 원안이 반복해 쓴 "9소스"는 `GeneralActionModuleFactory.kt:14-16` KDoc이 PHP `getActionList()`의 `array_merge` **인자 개수**(단일 슬롯 8 + `itemObjs` 배열 1)를 센 값이다. 실제 조립 결과의 길이는 다르다 — `ActionPipeline.kt:181-185`의 `MODULE_ORDER`는 **이름 12개**(아이템이 4개로 펼쳐짐)이고, `ModuleFactoryOrderTest.kt:78-92`가 핀으로 박은 정규 시퀀스는 **10개 태그**이며, inherit 슬롯이 `InheritBuffGeneralModule`+`InheritBuffWarModule` **쌍**을 넣으므로(`ModuleFactoryOrderTest.kt:127-138`) 최대 **12**가 된다. 꼬리 append 논증은 길이와 무관하므로 결론은 바뀌지 않지만, 이 문서에서 "9소스"라는 표현은 쓰지 않는다.

**그리고 이 구조가 주는 회귀 게이트.** 관계 엣지가 0개면 `bonuses`가 비고 `RelationStatModule`은 identity가 된다.

> **엣지-0 등가 테스트**: 관계 결속이 하나도 없는 v2 월드에서 `getStatValue`의 결과는 **모든 스탯·모든 훅에 대해 v1과 동일하다.**

**정확히 무엇을 증명하는지 좁혀 적는다(원안 과장 정정).** 이 테스트는 *"엣지가 0이면 v1과 같다"*를 증명하며, *"엣지가 있어도 v1이 안 바뀐다"*는 증명하지 않는다 — 후자는 애초에 참이 아니다(v2 보정은 값을 바꾸라고 넣는 것이다). v1 불변의 증명은 별도 DB·별도 프로파일 + 아래 표의 ①②가 담당한다.

| 증명 대상 | 테스트 | 무엇을 잡나 |
|---|---|---|
| **source 목록 불변** | 스냅샷 테스트 — `GeneralActionModuleFactory.build(...)`가 돌려주는 리스트의 클래스명 시퀀스를 문자열로 고정한다. v1 조립 결과에 관계 모듈이 나타나면 실패 | 팩토리 본문에 몰래 끼워 넣기 / fold 순서 변조 |
| **v1 조립 경로의 무지** | 아키텍처 테스트 — `GeneralActionModuleFactory`를 포함한 v1 조립 코드가 관계 패키지를 import하지 않는다. 주입은 `V2ActionModuleAssembler`에서만 일어난다 | 컴파일 단위 수준의 결합 |
| **`getStatValue`·RNG draw·한글 로그·PHP 골든 불변** | ① 엣지-0 등가 테스트 ② `tools/parity/gate.sh backend` 전체 green (출력 tail + test XML로 판정) | 값·draw 순서/횟수·로그 바이트·골든 전부 |

원안 장치 2가 "금지선을 stats 패키지 **바깥**에 긋는다"였다면, 대체안은 **"금지선을 팩토리 본문에 긋고, 선을 넘었는지를 등가 테스트로 측정한다"**이다. 정직하게 평가하면 **격리 강도는 원안보다 약간 약하고(타입 수준 불가능성 → 테스트 기반 측정), 검증 가능성은 강해졌다**(원안에는 "v1 결과가 같다"를 직접 재는 장치가 없었다).

**ADR-LITE-018 준수 — v1 주입은 선택지가 아니다.** v1 프로파일에는 `V2ActionModuleAssembler`가 등록되지 않고, `v2_general_bond` 표는 v1 DB에 마이그레이션되지 않으며, v1 Flyway `V*.sql` 계열에 파일이 추가되지 않는다.

**정직한 부작용 하나.** v2에서는 관계 보정이 스탯을 바꾸므로 **스탯을 입력으로 삼는 하위 판정의 결과가 달라진다**(전투 데미지, 내정 성과 등). 이것은 v2 내부의 의도된 divergence이고, v1은 별도 DB·별도 프로파일이라 영향받지 않는다. v2 자체의 결정성도 유지된다 — 같은 스냅샷 + 같은 결속 집합 → 같은 보정 → 같은 스트림. 원안이 자랑하던 "draw 0"은 **여전히 참**이다(보정은 가산이지 추첨이 아니다).

### 4.4 두 계열 — emergent와 사전 관계

이제 관계는 출처가 둘이다. 채점기 문항 4가 요구한 구분이 여기다.

| 계열 | 정의 | 생성 주체 | 출처 | 소멸 |
|---|---|---|---|---|
| **emergent** | 게임 안에서 실제로 벌어진 일의 기록 | 게임(정산 이벤트의 순수 함수, draw 0) | 게임 자신 | TTL 만료 |
| **사전 관계 PRESET** | 시나리오 시작 시점에 **이미 존재하는** 역사적 사실 | **게임이 만들지 않는다** — 시드 데이터 | 사서·연의 / RTK 원본 / 자체 편성 | 없음(구성원 사망 외) |

이 구분이 중요한 이유는 두 계열의 **검증 대상이 다르기 때문**이다. emergent는 "같은 사건에서 같은 엣지가 나오는가"(결정적 함수)를 검증하고, 사전 관계는 "같은 원본에서 같은 시드가 나오는가"(결정적 빌더)를 검증한다.

#### 사전 관계의 출처 — 셋으로 나눠 밝힌다

`docs/superpowers/specs/2026-07-18-scenario-system.md`가 이미 자리를 비워 두었다.

> "`相性`(affinity, G_AFFINITY) … 정제층에 **없음** … 인물관계(被親愛/被嫌悪)도 정제층 미포함 → **v2**"
> — `:90-92` (그리고 `:210`이 현재 산출물의 `affinity=0` 고정을 확인한다)

**개정 2차 — 원안의 독해를 철회한다.** 원안은 이 인용에서 *"RTK 원본에는 인물관계 필드가 있다"*를 읽어 냈다. 인용문은 그것을 확립하지 않는다. 해당 불릿의 라벨은 `원천에 없는 것`이고 본문은 `정제층에 **없음**`·`정제층 미포함`이며, 이 스펙에서 "원천"은 생성기 입력의 정본인 **정제층**(`:60`)을 가리킨다. 즉 이 문장이 확립하는 것은 **정제층에 없다**뿐이고, 제목은 오히려 반대를 말한다.

전수 확인 결과 `被親愛`/`被嫌悪`는 리포지토리 전체에서 **문서 5곳에만** 나타나고 코드·빌더에는 0건이다 — `tools/rtk14/build_rtk14_stats.py`(291줄)에도 `相性`/`被親愛`/`被嫌悪`/`affinity` 어느 토큰도 없다. 코드의 모든 `affinity`(`infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt:29,150`, `V1__baseline.sql:68`)는 PHP RNG가 뽑는 **개인↔국가 상성 스칼라**이지 인물 간 관계가 아니다.

> **UNKNOWN — RTK14 원본 데이터에 인물관계 필드(`被親愛`/`被嫌悪`)가 존재하는지 이 리포지토리의 어떤 산출물로도 확인하지 못했다.** 확인 방법은 원본 파일 실측뿐이며, 원본은 git-ignore이므로 이 문서가 답할 수 없다.

**그래서 이 UNKNOWN 위에 설계를 세우지 않는다.** 사전 관계 출처는 아래 두 갈래만으로 성립하도록 재작성했고, RTK 갈래는 **조건부 옵션**으로 강등한다.

1. **사서·연의** → 결의(도원결의)·사제·혈연. 유비·관우·장비는 여기서 온다. **이 갈래만으로 §4.4 이후 전부가 성립한다.**
2. **자체 편성** → 1로 매칭되지 않은 나머지. 정치·매력 빌더가 미매칭 장수를 50/50으로 남겨 둔 것과 같은 처리 — **비워 두지 억지로 채우지 않는다.**
3. *(조건부)* **RTK 원본 호오 필드** → 원본 실측으로 존재가 확인되면 같은 빌더의 두 번째 입력으로 붙인다. 확인되지 않으면 이 갈래는 **없는 것으로 하고 설계는 변하지 않는다.**

#### 데이터 취급 규율 — 정치·매력 5스탯 규율을 **문자 그대로** 지키는 배치

CLAUDE.md의 승인된 divergence 문단이 정한 규율은 셋이다: **원본 git-ignore·미커밋 / 생성 산출물도 미커밋 / 빌더 스크립트만 버전 관리**(`tools/rtk14/build_rtk14_stats.py` 선례). 이 규율을 관계 데이터에 그대로 적용하면 문제가 하나 생긴다 — 산출물을 커밋하지 않는데 **손편성 10건은 재생성할 입력이 없어서** 규율을 지키는 순간 데이터가 사라진다.

해법은 배치를 바꾸는 것이지 규율을 깎는 것이 아니다.

> **빌더 하나(`tools/v2/build_preset_bonds.py`), 입력 둘, 산출물 하나.** 손편성분은 빌더 스크립트 **안의 리터럴 표**로 산다. RTK 파생분은 gitignored 추출본을 **있으면 읽고 없으면 건너뛴다.** 산출 JSON은 gitignore된다.

- 커밋되는 것은 **빌더 하나뿐**이다. 규율 위반 0.
- 손편성 10건이 빌더 소스에 있으므로 **오픈 시점에 데이터가 살아남는다.** 그리고 그 10건은 삼국지연의 유래의 공유 서사이지 추출된 원본 자산이 아니므로 소스에 적히는 것이 자연스럽다.
- RTK 추출본은 **한 번도 커밋되지 않는다.** 오픈 시점에는 없으므로 빌더가 10건만 낸다. 오픈 후 추출본이 붙으면 **같은 빌더가** 전면 데이터를 낸다 — 스크립트를 두 번 만들지 않는다.
- 빌더는 `scenario-system.md:212`의 결정성 규칙을 따른다: **같은 입력 → byte-동일 출력.**
- 산출물은 시나리오 JSON **옆의 별도 파일**이다. 관계는 장수 tuple이 아니라 장수 **집합**에 붙으므로 5스탯처럼 tuple 인덱스(14/15)에 넣을 수 없다. `ScenarioImporter`가 JDBC INSERT로 적재한다(F1 경로, one-daemon-write rule 무위반).

#### 그래서 언제 무엇이 있나 — 개정 2차에서 전부 오픈 후로 간다

원안은 손편성 10건을 오픈 경로에 넣고 "티켓 증분 0"이라고 썼다. **철회한다.** 판정 근거는 §9.4에 전부 적었고 요지는 셋이다 — (i) 관계를 낳는 emergent 사건이 오픈 경로의 **마지막 두 티켓**(`OPENSAM-56` 작전 / `61` 가신)에서야 생기므로 관계망은 그 뒤에만 올 수 있고, (ii) 그 참여 기록은 V2-3의 `operation_participants` 표(`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:190`, 티켓 3-b)에 남으므로 **오픈 후 도입해도 COMRADE는 소급 생성이 가능**하며(RIVAL 소급은 **UNKNOWN** — 개정 3차 §9.4), (iii) 손편성 10건은 전 장수 관계망이 아니라 **10행짜리 데모**라서 오픈을 늦출 값어치가 없다. **(i)만으로도 판정은 유지된다.**

- **오픈 경로**: **없음.** 관계망 전체(스키마·시드·모듈·표시)가 오픈 후다.
- **오픈 후 (§9.4에 7티켓으로 분해)**: 파이프라인 seam(P0) → 스키마+flush 채널 → 사전관계 빌더·시드 → emergent 생성 → 소멸·상한 leaf → 스탯 모듈+조립자 → 표시 경로.

이 판정은 **설계 내용에 대한 판정이 아니다.** 사용자 결정("관계는 능력치 버프에도 영향을 줘야 한다")은 §4 전체에 반영되어 완전한 상태로 남아 있고, 바뀐 것은 착수 시점뿐이다.

### 4.5 emergent 5종이 생기는 사건 — 전부 draw 0

각 종류를 **이미 오픈 경로에 있는 티켓 안의 사건**에 붙인다. 새 사건을 만들지 않는다.

| 종류 | 생기는 사건 | 정산 지점 | draw |
|---|---|---|---|
| 사제 MENTOR | 가신 서약이 성립하고 `origin == RECRUITED`(주군이 새로 만들어 들인 가신) | V2-5 `가신서약` 정산 (OPENSAM-61) | 0 |
| 동료 COMRADE | 같은 `Operation`에 함께 참가해 작전이 종료 | V2-3 Operation 정산 (OPENSAM-56) | 0 |
| 라이벌 RIVAL | 같은 `Operation`에서 서로 반대편으로 참가해 종료 | V2-3 Operation 정산 | 0 |
| 원한 GRUDGE | `releasePolicy == MASTER_ONLY`로 주군이 일방 해제(ADR-LITE-017) | V2-5 `가신해제` 정산 | 0 |
| 구명 RESCUE | 전투에서 사망 문턱의 아군을 살려낸 경우 | **오픈 후** — 전투 엔진 접촉 | — |

5종 중 4종이 이미 오픈 경로에 있는 정산 지점에 붙고, 구명 하나만 전투 엔진을 건드려야 하므로 미룬다. 결속 생성은 정산 결과의 순수 함수이므로 draw가 0이고, 따라서 스냅샷 + 순수 함수로 완전히 재현된다. 여섯 번째 종류인 `SWORN`(결의)은 이 표에 없다 — **게임이 만들지 않기 때문이다**(§4.4).

### 4.6 3인 결의를 어떻게 담을 것인가 — 쌍 엣지인가 집단 개체인가

사용자가 예로 든 것이 정확히 이 문제다. 유비·관우·장비는 **둘이 아니라 셋**이고, 원안의 스키마는 `(from_general_id, to_general_id)` **쌍**이었다.

**쌍 엣지로 하면** 유-관, 관-장, 장-유 3행(방향까지 두면 6행)이 되고 세 가지가 깨진다. ① **3인 전원이 모였을 때의 집단 판정**을 표현할 수 없다 — 쌍은 2인 사실만 알기 때문에 "결의가 온전한가"를 물을 주체가 없다. ② **소멸이 집단 단위인데** 쌍은 개별이다(관우가 죽으면 유-관·관-장 두 행이 남긴 것과 결의가 깨졌다는 사실이 별개로 관리된다). ③ n인 결의의 행 수가 O(n²)로 는다.

**집단 개체로만 하면** 위 셋이 자연스러워지지만, emergent 관계(사제·원한)는 본질적으로 **2인 방향 관계**라 별도 표가 하나 더 생긴다.

> **판정: 표 하나로 둘 다 담는다. PK를 `(world_id, bond_id, general_id)`로 두고, 2인 관계는 "구성원이 2명인 결속"으로 표현한다.**

```
v2_general_bond
  world_id      bigint  NOT NULL
  bond_id       bigint  NOT NULL      -- 결속 1건 (2인이든 3인이든)
  general_id    int     NOT NULL      -- 구성원 1명 = 1행
  role          text    NOT NULL      -- MASTER/DISCIPLE/PEER/OFFENDER/VICTIM/RESCUER/RESCUED
  kind          text    NOT NULL      -- MENTOR/COMRADE/RIVAL/GRUDGE/RESCUE/SWORN
  origin        text    NOT NULL      -- EMERGENT | PRESET
  created_turn  int     NOT NULL
  decay_at_turn int     NULL          -- PRESET은 NULL = 무기한
  PRIMARY KEY (world_id, bond_id, general_id)
```

- **방향은 `role`이 담는다.** 사제는 `MASTER`/`DISCIPLE`, 원한은 `OFFENDER`/`VICTIM`, 동료·라이벌·결의는 전원 `PEER`. 원안의 `from`/`to`가 하던 일을 컬럼 하나가 한다.
- **3인 결의 = `bond_id` 하나에 `role=PEER` 3행.** 4인이면 4행(선형). 도원결의는 3행이다.
- **소멸.** 구성원 사망은 그 행만 tombstone하고, 남은 구성원이 **1명 이하가 되면 결속 전체를 tombstone**한다(1인 결속은 의미가 없다). 배신·이탈로 결의가 깨지는지는 묘섭에도 devsam에도 근거가 없는 새 판정이므로 **오픈 후**다.
- **중복 컬럼을 감수한다.** `kind`/`origin`/`created_turn`/`decay_at_turn`이 구성원 행마다 반복된다. `ponytail:` 결속당 구성원이 2~3명이라 이 중복이 메타 표를 하나 더 두는 것보다 싸다. 결속 규모가 커지면 그때 분리한다.
- **재발생 처리 (개정 2차 — 원안 철회).** 원안은 `(world_id, kind, origin, 구성원 집합)` **유일성 인덱스**를 두겠다고 썼다. SQL 인덱스는 컬럼 위에만 걸리고 "구성원 집합"은 컬럼이 아니므로 **그 인덱스는 존재할 수 없다.** 대체안은 강제 지점을 옮기는 것이다.
  - **DB에 유일성 제약을 두지 않는다.** 이 아키텍처에서 진실은 `InMemoryTurnWorld`이고 DB는 그 투영이다(CLAUDE.md 아키텍처 절). 제약을 DB에 걸면 위반이 **flush 시점**에 터지는데, 그때는 이미 턴이 진행된 뒤라 복구가 불가능하다. 판정은 결정이 내려지는 메모리에서 한다.
  - **메모리 `bondIndex`가 강제한다.** `Map<BondKey, BondId>`이고 `BondKey = (kind, origin, sortedSetOf(generalIds))`이다. 생성 경로가 이 지도를 먼저 조회한다 — 있으면 `decay_at_turn`만 갱신, 없으면 새 `bond_id`. 부팅 시 `v2_city_ledger`·`v2_general_bond` 행에서 그대로 재구성되므로 새 컬럼이 필요 없다.
  - **방향 있는 kind는 역할이 뒤집히면 새 결속이다.** `MENTOR`(MASTER/DISCIPLE)와 `GRUDGE`(OFFENDER/VICTIM)에서 A→B와 B→A는 서로 다른 사실이다. 따라서 이 두 kind의 `BondKey`는 집합이 아니라 **역할 순서쌍**을 쓴다. 이것이 상호 원한(A가 B에게, B가 A에게)을 표현 가능하게 만든다 — 원안의 집합 키로는 두 사실이 한 행으로 뭉개졌다.

### 4.7 능력치 보정의 형태 — 어떤 스탯에, 어떤 조건에서, 얼마나, 어떻게 겹치나

**어떤 조건에서 — 같은 도시에 있을 때만.** 세 가지 이유다. ① 그것이 이 라운드 가설의 핵심이다 — "누가 어느 도시에 있는가가 곧 결정"(§0·§1.2). 관계가 **상시** 보정이면 숨은 능력치가 하나 늘 뿐 **배치를 바꾸지 않는다.** ② 판정 축이 이미 있다 — `officerCntByCity`가 `city = officer_city`를 요구하듯(§3.2) 관계는 `A.cityId == B.cityId`를 요구한다. 새 축 0. ③ 데이터 흐름도 이미 있다 — `OfficerLevelModule`이 생성자에서 `currentCity`를 받아 소재-담당 불일치를 판정하고 있다(`OfficerLevelModule.kt:27-36`).

확장 조건 둘은 **오픈 후**다: **같은 작전**(V2-3 이후), **같은 부대**(V2-2 부곡 이후). 조건을 넓히는 것은 상수 하나가 아니라 새 소속 판정이므로 값어치를 따로 따진다.

**어떤 스탯에 — 통솔·무력·지력 3스탯, 가산.** 정치·매력에는 **붙이지 않는다**(별개 divergence의 축을 섞지 않는다). 형태는 v1이 이미 쓰는 문장 그대로다 — `OfficerLevelModule.onCalcStat`이 `if (statName == "leadership") value + lbonus`(`:46-47`)이고, 관계 모듈은 그 문장의 3스탯 판이다. `onCalcDomestic`·`getWarPowerMultiplier`에는 **붙이지 않는다** — 훅 표면을 최소로 유지하고, 필요하면 오픈 후에 넓힌다.

**얼마나 — 오라클이 아니다. 전부 오픈삼국 결정값이며 그렇게 표기해 커밋한다.**

| 계열 | kind | 같은 도시의 상대 1인당 | 근거 |
|---|---|---|---|
| 우호 | SWORN · MENTOR · RESCUE · COMRADE | **+2** | **오라클 없음 — 오픈삼국 결정** |
| 적대 | GRUDGE · RIVAL | **−2** | **오라클 없음 — 오픈삼국 결정** |
| 상한 | (공통) | 축별 합계 **±6** = `RELATION_STAT_CAP` | **오라클 없음 — 오픈삼국 결정** |

**개정 2차 — 선언 상한 ±6은 실효 상한이 아니다.** `getStatValue`가 무력·지력을 서로 증강하기 때문이다.

```kotlin
// logic/src/main/kotlin/opensamguk/logic/stats/GetStatValue.kt:54-64
if (withStatAdjust) { when (statName) {
    "strength"            -> v += phpRound(crossBase("intelligence", withInjury, withIActionObj))
    "intelligence","intel"-> v += phpRound(crossBase("strength",     withInjury, withIActionObj))
} }
v = clamp(v, 0.0, maxLevel.toDouble())
if (withIActionObj) v = pipeline.onCalcStat(general, statName, v)   // ← 관계 모듈이 여기서 실행
// :89-91  crossBase = getStatValue(other, …, withIActionObj = withIActionObj, withStatAdjust = false) / 4.0
```

`crossBase`가 **`withIActionObj`를 그대로 전달**하므로 재귀 호출 안에서도 파이프라인이 실행된다. 따라서 무력을 계산할 때 지력 쪽 관계 보정(+6)이 `/4 → phpRound` 되어 최대 **+2**가 추가로 들어온다.

> **실효 상한: 통솔 ±6, 무력 ±8, 지력 ±8.** (교차 증강이 없는 통솔만 선언값과 같다.)

이 문서는 실효값으로 밸런스를 논한다. 재귀에서 관계 모듈만 끄는 것은 불가능하다 — 파이프라인 훅의 `aux` 채널(`ActionPipeline.kt:24`)은 존재하지만 **통솔·무력·지력을 계산하는 호출부가 그것을 채우지 않고**, 그 호출부(`GetStatValue.kt:64`)는 v1 패러티 파일이라 수정 대상이 아니다.

**개정 3차 — "유일한 호출부"라는 문언을 철회한다.** 개정 2차는 `GetStatValue.kt:64`를 `onCalcStat`의 유일한 호출부라고 썼는데 **거짓이다.** 프로덕션 소스의 `pipeline.onCalcStat(...)` 호출부는 **23곳**이다(`ActionPipeline.kt:90`의 fold 본체와 `ItemHooks.kt:135`의 위임 제외). 다만 나머지 21곳이 넘기는 stat 이름은 전부 통무지가 아닌 훅 키다 — `bonusTrain`·`bonusAtmos`·`dex{armType}`·`warCriticalRatio`·`warAvoidRatio`·`initWarPhase`·`killRice`·`warMagic*`(`WarUnitGeneral.kt:168,179,191,205,216,228,290,375,389,397,405`), `experience`·`dedication`(`CommerceInvestment.kt:105-106`, `StatChange.kt:94,124`), `sabotageDefence`(`CheHwagye.kt:132`·`CheSeondong.kt:113`·`CheTalchwi.kt:125`·`ChePagoe.kt:123`), `injuryProb`(`SabotageInjury.kt:42`), `addDex`(`MilitaryHelpers.kt:42`).

> **통솔·무력·지력 이름을 파이프라인에 넘기는 호출부는 정확히 둘이다 — `GetStatValue.kt:64`(엔진)와 `FrontInfoController.kt:394`(표시).** 그래서 실질 결론(재귀 안에서 관계 모듈만 끌 수 없다)은 그대로 성립하고, 둘째 호출부가 §4.3 부수 발견 2의 표시/엔진 divergence다.

`RelationStatModule`이 `statName`을 `leadership`/`strength`/`intelligence`(+`intel`)로 좁혀 검사해야 하는 이유가 여기 있다 — 좁히지 않으면 위 21개 훅 키에도 보정이 실려 전투 확률까지 흔든다. 이 좁힘은 `OfficerLevelModule.kt:46-47`이 `statName == "leadership"`으로 이미 쓰는 형태 그대로다.

**크기 정당화 — 원안의 비교 근거를 교체한다.** 원안은 "관직 보너스 +14보다 작다"고 썼는데 그 비교는 성립하지 않는다. `OfficerLevelModule`의 `lbonus`는 `:46-47`에서 **`statName == "leadership"`일 때만** 더해지므로 무력·지력에는 0이고, 관계 보정은 3스탯 전부에 붙는다. 축이 다른 값끼리 비교한 것이다.

같은 훅·같은 스탯 위의 비교 대상은 부상이다. `GetStatValue.kt:53`이 `if (withInjury) v *= (100 - general.injury) / 100.0`이므로, 무력 80인 장수의 부상 10%는 **−8**이다. 즉 **관계 실효 상한 ±8 ≈ 부상 10% 한 번**이다. 이것이 이 숫자들의 유일한 근거이며, 그 이상의 정밀도는 오픈 후 관측으로 정한다.

**개정 6차 — 이 자리에 있던 `ponytail:` "알려진 천장"은 없는 결함을 자백하고 있었다. 철회한다.** 개정 5차는 "`:63`의 `clamp(0,255)`가 파이프라인 **앞**에 있으므로 관계 보정이 255를 넘길 수 있다(255 → 263)"고 썼고, "v1 `OfficerLevelModule`의 `+lbonus`도 같은 성질"이라고 부연했다. **둘 다 거짓이다.** `GetStatValue.kt`에는 clamp가 **두 번** 있다 — `:63` `v = clamp(v, 0.0, maxLevel.toDouble())`(PHP `General.php:384`, 파이프라인 **앞**)과 `:65` **같은 문장**(PHP `:394`, 파이프라인 `:64` **직후**). `maxLevel`은 `:25` `private val maxLevel: Int = 255`(`GameConst.maxLevel` 정본)다. 그러므로 파이프라인 꼬리에서 무엇을 더하든 `:65`가 `[0, 255]`로 **재클램프**하며, `OfficerLevelModule`의 `+lbonus`도 같은 재클램프를 받는다.

> **실제 성질은 반대 방향이다.** 관계 보정은 상한을 **넘지 못하고**, 이미 255에 가까운 장수는 보정을 온전히 받지 못한다(예: 무력 252 + 우호 6 → 255에서 잘려 실효 +3). 이것은 결함이 아니라 v1이 이미 갖고 있는 성질이며, v2 모듈에서 별도 재클램프를 할 이유도 없다. 밸런스 논의는 상한 근처 구간에서 보정이 체감된다는 사실만 기억하면 된다.

**어떻게 겹치나 — 상한이 이 설계의 핵심 안전장치다.**

1. **한 상대에게서 우호 1개·적대 1개를 각각 받는다 (개정 2차 — 원안 철회).** 원안은 `SWORN > MENTOR > RESCUE > COMRADE > GRUDGE > RIVAL` 단일 우선순위로 **하나만** 골랐다. 그러면 동료이자 원한 관계인 쌍에서 `COMRADE`가 `GRUDGE`를 순위로 눌러 **원한이 영영 적용되지 않는다** — §4.6이 상호 원한을 표현 가능하게 만든 것과 정면으로 모순된다. 대체안은 축을 나누는 것이다.
   - 우호축 우선순위 `SWORN > MENTOR > RESCUE > COMRADE` 중 최상위 1개, 적대축 `GRUDGE > RIVAL` 중 최상위 1개를 **각각** 취해 더한다.
   - 애증 관계(COMRADE + GRUDGE)는 `+2 + (−2) = 0`이 되어 "복잡한 사이라 서로 상쇄된다"가 자연스럽게 나온다. 원안에서는 `+2`였다.
   - 두 축 모두 고정 순서이므로 결정적 tie-break는 유지된다.
2. **결속당이 아니라 상대당 센다.** 3인 결의에서 유비가 받는 보정은 "같은 도시에 있는 **다른 구성원 수** × 단가"다. 관우·장비가 함께 있으면 +4, 관우만 있으면 +2, 혼자면 **0**. 결속이 존재한다는 사실만으로는 아무 일도 일어나지 않는다 — 이것이 "배치가 결정"이라는 가설의 직접 구현이다.
3. **축별 클램프 후 합산.** 스탯별로 우호 합계를 `[0, +6]`, 적대 합계를 `[−6, 0]`에 각각 클램프한 뒤 더한다(선언 범위 `[−6, +6]`, 실효 `[−8, +8]`). 이것이 없으면 **파벌 전원을 한 도시에 모아 스탯을 무한히 올리는 것**이 최적해가 된다. 축을 나눠 클램프하는 이유는 적대 결속을 많이 쌓아 우호 클램프를 우회하는 경로를 막기 위함이다. 상한의 존재는 계약이고 값만 튜닝 대상이다.
4. **적용 순서 의존 없음.** fold 꼬리에서 3스탯 각각에 `clamp(Σ bonus)`를 더한다. 상한이 스탯별로 걸리므로 결속을 어떤 순서로 훑든 결과가 같다.
5. **계산 위치.** `bonuses`는 **fold 바깥에서 미리 계산해 모듈 생성자에 넣는다.** fold 안에서 월드를 조회하면 그 자체가 성능 사고이자 결정성 사고다.

### 4.8 비수치 소비 — 판정 하나는 그대로 남는다

`spec:388`이 명명한 나머지 소비는 "명령 신뢰와 협상 태도"다. 능치 보정이 추가되었다고 이쪽이 사라지지는 않는다 — 다만 오픈 경로에서는 그중 **가장 싼 것 하나만** 실제 판정으로 만든다.

> **가신 서약 거부 게이트** — 장수 A가 장수 B에게 가신 서약을 제출할 때, B가 `VICTIM`이고 A가 `OFFENDER`인 살아 있는 `GRUDGE` 결속이 있으면 제출을 거절한다.

- 결정: 유저가 `가신서약` 커맨드를 제출한다.
- 판정: `bondIndex.grade(B, A) == DISTRUST` (순수 조회, draw 0). `TrustGrade`는 격리 장치의 지위를 잃었지만(§4.3) **이 비수치 판정의 조회 타입으로는 그대로 살아 있다.**
- 상태 변화: 서약이 생성되지 않고, `TurnDaemonCommandResult(ok=false, reason="원한")`이 durable result에 기록된다. FE는 202가 아니라 `pollCommandResult`의 `RESOLVED`를 보고 거절 사유를 띄운다(OPENSAM-13/135).
- replay/log: deny 로그 한 줄 + durable result 행.

이 게이트의 구현 비용은 V2-5(OPENSAM-61) 안의 precheck **한 줄**이다. 새 티켓이 붙지 않는다. `spec:30`이 이미 "가신 제안과 편견: deterministic score, 근거, confidence, 관계 변화"를 v2 정의에 넣어 두었으므로 계약 위반도 아니다.

나머지 소비 — 작전 참여 수락(명령 신뢰), 외교 협상 태도, `RetainerProposal.score`의 `TrustGrade` 항 — 은 전부 **오픈 후**다.

### 4.9 소멸 조건과 상한 — 관계망이 상태를 무한히 먹지 않게

관계 시스템이 실패하는 전형적 방식은 결속이 영원히 쌓여 상태가 무한 성장하는 것이다. ADR-LITE-013이 "메모리 source of truth는 전체 이력을 무제한 적재하지 않고 bounded hot/cold"를 못 박았으므로, 상한은 선택이 아니라 계약이다. 스키마는 §4.6의 `v2_general_bond` 하나뿐이다.

- **수명(EMERGENT)**: `decay_at_turn = created_turn + RELATION_TTL_TURNS`. v2 월간 leaf가 만료 결속을 tombstone으로 삭제한다(draw 0, 순수 함수).
- **수명(PRESET)**: `decay_at_turn = NULL` = **무기한.** 역사적 사실은 시간으로 흐려지지 않는다. 도원결의는 만료되지 않고, 구성원이 죽어야만 사라진다.
- **재발생은 행을 늘리지 않는다**: 같은 `BondKey`가 다시 발생하면 `decay_at_turn`만 갱신된다 — 강제 지점은 DB 인덱스가 아니라 메모리 `bondIndex`이고, 방향 있는 kind는 역할이 뒤집히면 새 결속이다(§4.6, 개정 2차).
- **장수당 상한**: 한 장수가 속한 EMERGENT 결속은 최대 `RELATION_MAX_PER_GENERAL`개. 초과 시 `decay_at_turn`이 가장 이른 것부터 제거(동률은 `bond_id` 오름차순 — 결정적 tie-break). **PRESET은 상한에 세지 않는다** — 시드 데이터가 게임 기록에 밀려 사라지면 안 된다.
- **소멸 사건**: 장수 사망·삭제 시 그 장수의 구성원 행을 tombstone하고, 남은 구성원이 1명 이하면 결속 전체를 tombstone.

`RELATION_TTL_TURNS`와 `RELATION_MAX_PER_GENERAL`은 **묘섭에도 devsam에도 근거가 없는 오픈삼국 설계 파라미터**다. 초기값은 각각 60턴·20개를 제안하지만, 이 두 숫자는 오라클에서 온 것이 **아니며** 그렇게 표기해 커밋한다. 상한의 존재는 계약이고 값은 튜닝 대상이다.

### 4.10 격리 요약 — 개정 전후와 정치·매력 선례를 나란히

| 격리 항목 | 정치·매력 5스탯 (선례) | 관계망 **원안** (버프 금지) | 관계망 **개정안** (버프 허용) |
|---|---|---|---|
| 오라클 | 없음(RTK14 원본) | 없음 | 없음(오픈삼국 독자) |
| 저장 위치 | `General` 데이터 클래스 필드 | 별도 표 `v2_general_relation` | **별도 표 `v2_general_bond`**, v1 엔티티 무접촉 |
| 기본 상태 | `0`으로 inert | 행 없음 = `NEUTRAL` | 행 없음 = 보정 0 = **identity 모듈** |
| 스탯 경로 진입 | 비-RNG 경로에만, 플래그 뒤 | **진입 자체가 금지** | **v2 프로파일 조립자만 진입** — 팩토리 본문 무수정 |
| 스탯화 차단 수단 | 주석 + 기본값 0 | 타입이 열거형이라 **불가능** | ~~타입 차단 폐기~~ → **엣지-0 등가 테스트 + source 목록 스냅샷 + 아키텍처 테스트** |
| fold 순서 영향 | 해당 없음 | 해당 없음 | **꼬리 append — 기존 모듈 전원의 입력 `acc` 불변**, `MODULE_ORDER` 무수정 |
| RNG draw | 비-RNG 경로에만 주입 | `RandUtil` 미주입 → 0 | **`RandUtil` 미주입 유지** (`ActionPipeline`의 "NO RNG inside the fold" 계약 준수) |
| 로그·골든 | 불변 | 불변 | 불변, v2 전용 로그 채널 |
| 게이팅 | divergence 플래그 | v2 world profile | v2 world profile + **별도 DB**(ADR-LITE-018) |
| v1 결과 동일성 측정 | 없음 | **없음** | **있음 — 엣지-0 등가 테스트가 직접 잰다** |

마지막 두 줄이 이 개정의 실질이다. **격리 강도는 한 칸 내려갔고(타입 불가능성 상실), 검증 가능성은 한 칸 올라갔다(직접 측정 확보).**

---

## 5. 임원진 체류 효과 6종 — 3 채택 / 3 보류 (시험지 5)

원문 표(`help__start__intermediate__positionrole.md:191-201`)와 판정은 다음과 같다.

| 관직 | 묘섭 효과 (원문) | 판정 | 사유 |
|---|---|---|---|
| 사도 | 체류중인 자국 도시의 **세금 수입 20% 증가** | **채택** (오픈 후) | 판정 지점이 §2.2 도시 수입 귀속 그 자체. 새 판정 지점 0 |
| 대사농 | 체류중인 자국 도시의 **병량 수입 20% 증가** | **채택** (오픈 후) | 동일 |
| 거기장군 | 체류중인 자국 도시의 **도적 발생율 50% 감소** | **채택** (오픈 후) | 판정 지점이 §2.4 감소 판정의 입력. 새 판정 지점 0 |
| 사공 | 체류중인 자국 도시의 **병종 기술 레벨 2 증가** | **보류** | 병종 기술 레벨은 v1 징병 비용·전투 수치의 입력. 건드리면 v1 게이트 표면이 넓어지고, v2 전투(V2-4B)는 오픈 후다 |
| 표기장군 | 체류 도시 장수 **훈련 사기 감소 없음**, 사기진작·고양 **비용 20% 감소** | **보류** | 훈련·사기는 v1 전투 입력. 동일 사유 |
| 위장군 | 체류 도시 **계략 회피율 50% 증가** | **보류** | 계략 회피는 v1 RNG draw의 확률 인자. 확률을 바꾸면 v2 계략 스트림이 v1과 달라지고, 이를 검증할 오라클이 없다 |

**판정 기준선은 취향이 아니라 규칙이다**: *§2의 도시 원장·공백지화 판정 위에 계수 하나로 얹히면 채택, v1 전투·계략·병종 수치를 입력으로 삼으면 보류.* 같은 기준선이 §6에도 그대로 적용된다.

채택 3종의 판정 지점과 중첩 규칙:

- **판정 지점**: 사도·대사농은 §2.2의 `V2ProcessCityIncome` 안, `calcCityGoldIncome` 반환값 직후. 거기장군은 §2.4의 `attritionLoss(...)` 결과에 곱해지는 마지막 항.
- **체류 판정**: `officerCntByCity`와 동일한 의미론 — 해당 임원진 장수의 현재 소재 도시가 그 도시일 때만. 담당도시 개념이 아니라 **소재 도시**다(임원진은 담당도시가 없다: `positionrole.md:204` "자국 도시 어디서든 사용할 수 있으며").
- **중첩 규칙 (묘섭 미명시 → 오픈삼국 결정)**: 사도·대사농은 서로 다른 자원에 붙으므로 충돌이 없다. 태수·군사 보정과의 결합 형태는 **곱**이다 — v1이 `IncomeTick.kt:41`에서 `income *= 1.05.pow(officerCnt)`로 관직자 보정을 곱으로 걸기 때문이고(개정 2차 실측), 임원진 +20%도 같은 형태(`× 1.20`)로 이어 붙인다. 같은 도시에 같은 임원진 2명이 체류할 수 있는지는 국가당 관직 정원에 달려 있고 그 정원은 **UNKNOWN**이므로, 구현 시 `count(*)`가 아니라 `exists`로 계산해 정원과 무관하게 최대 1회만 적용한다(보수적 선택).

**임원진에는 효과표가 하나 더 있다 (개정 2차 보강).** `help__start__intermediate__intermediatedomestic.md:219-243`에 별도의 **내정 증가량 보정표**가 있고, 위 §5 표(`positionrole.md:191-201`, 체류 도시 한정)와 **범위가 다르다**.

| 관직 | 자격 요건 | 내정 보정 | 범위 |
|---|---|---|---|
| 태수 `:221` / 군사 `:223` | 7품관 이상 + 무력/지력 70 | 각 내정 +1, 병사보충 +200 | **담당도시만** |
| 위장군 `:225` · 거기장군 `:227` · 표기장군 `:229` · 대사농 `:231` · 사공 `:233` · 사도 `:235` | 6품관 이상 + 해당 스탯 70 | 각 1개 항목 +1 (표기장군은 병사보충 +200) | **자국 모든 도시** |
| 승상 `:237` · 대장군 `:239` | 5품관 이상 + 스탯 60 | 다수 항목 +1, 병사보충 +200 | 자국 모든 도시 |
| 참모 `:241` / 군주 `:243` | 5품관 이상 / — | +1 (군주는 전 내정) | 자국 모든 도시 |

이 표는 **전부 보류(오픈 후)**다. 사유 둘 — ① 효과가 §2 원장이 아니라 **v1 내정 커맨드의 증가량**에 붙으므로 §5 기준선의 "v1 수치를 입력으로 삼으면 보류"에 그대로 걸린다. ② 자격 요건의 "7품관/6품관/5품관"이 v1 30등급 눈금과 같은 눈금인지 **UNKNOWN**이다(§5-bis.3). 두 표가 서로 모순되는 것은 아니다 — `positionrole`는 **체류 도시 한정 자원 효과**, `intermediatedomestic`은 **자국 전역 내정 보정**으로 축이 다르다.

채택 3종도 **오픈 후**다. §3.2에서 밝혔듯 배치효과 축은 태수·군사(`officerCntByCity`)만으로 이미 증명되며, 임원진 3종은 그 축 위의 계수 3개를 더할 뿐이라 오픈을 늦출 값어치가 없다.

---

## 5-bis. 국가 임원진·중앙관직·품관 — 세 층 분리, 오픈 경로 증분 0 (시험지 5 확장 — 사용자 결정 2026-07-25)

> 이 절은 새 시험지 문항이 아니라 문항 5의 확장 답변이다. 사용자 질문 "국가 임원진과 중앙관직은 어떻게 할거지"에 네 갈래로 답한다.
> 근거 자료 한 가지를 먼저 정리한다: 제시된 namu.wiki 삼국지/관직 문서는 **403으로 접근되지 않아 확인하지 못했다.** 따라서 이 절은 그것을 인용하지 않고, 저장소가 이미 확보한 『후한서』 백관지 근거(`docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md:637-648`)에만 기댄다. 같은 spec `:655`가 "namu.wiki를 역사 claim 근거로 단독 사용하지 않는다"고 이미 정해 두었으므로 규율과도 일치한다.

### 5-bis.1 경쟁하는가 다른 층인가 — **다른 층이다. 그리고 둘이 아니라 셋이다.**

| 층 | 무엇을 정하나 | 지금 어디 있나 | 판정 예 |
|---|---|---|---|
| **L1 게임 직책** (`officer_level` 0~12) | 누가 어느 도시를 맡고 어떤 **실무 보정**을 받나 | **v1에 이미 구현되어 있다** | "이 도시의 수입이 얼마인가" |
| **L2 역사 관직** (v2 imperial-court 6모델) | 누가 어떤 관직을 **주장**하고 누가 **인정**하나 (정통성·실권 분리) | v2 spec, 미구현 | "이 자칭을 타국이 인정하는가" |
| **L3 자격 등급** (묘섭 품관 ↔ `dedication`/`dedlevel`) | 누가 L1·L2를 **받을 자격**이 있나 | **v1에 이미 있다** (§5-bis.3) | "이 임명이 가능한가" |

경쟁하지 않는다는 것은 이 설계안의 추론이 아니라 **v2 spec 작성자가 이미 적어 둔 결정**이다.

> "비범위: v1 `officer_level`, 국가 작위, 아이템 효과와 PHP 패리티 변경"
> — `docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md:6`

L2는 설계 단계에서부터 L1을 **대체하지 않기로** 선언되어 있다. 그리고 묘섭 임원진 6종(사도·대사농·사공·표기장군·거기장군·위장군)은 **전원 L1이다** — `positionrole.md:191-201`의 효과가 전부 "체류중인 자국 도시의 수입 / 도적 발생율 / 병종 기술레벨 / 훈련 사기 / 계략 회피율"이고, 이는 정통성 판정이 아니라 실무 보정이다. 따라서 §5의 3채택/3보류 판정은 그대로 유효하며 이 절이 뒤집지 않는다.

정직하게 겹치는 지점이 하나 있다: **이름이 겹친다.** L2 카탈로그 9계층(`:281-302`)에 삼공(태위·사도·사공)·구경(대사농 포함)·중앙무관(대장군·표기장군·거기장군·위장군)이 전부 들어 있어 L1 칭호와 문자열이 충돌한다. 이것은 버그가 아니고 spec이 이미 대비했다.

> "같은 이름의 관직도 후한·조위·촉한·손오에서 권한이 다를 수 있으므로 universal enum의 고정 효과를 금지한다" — `:302`

**UI에서 L1은 "직책", L2는 "관직"으로 라벨을 분리한다.** 그 이상의 조치는 필요 없다.

### 5-bis.2 v1 칭호를 재사용하나 대체하나 — **재사용한다.**

측정부터 한다. `web/game/lib/utilGame/formatOfficerLevelText.ts`의 `OfficerLevelMapByNationLevel`에서 **국가레벨 7(황제국)** 칭호 셋은 황제(12)·승상(11)·표기장군(10)·사공(9)·거기장군(8)·태위(7)·위장군(6)·사도(5)다. 묘섭 임원진 6종과 대조하면:

| 묘섭 임원진 | v1 국가레벨 7 칭호 |
|---|---|
| 사도 | **있음** (level 5) |
| 사공 | **있음** (level 9) |
| 표기장군 | **있음** (level 10) |
| 거기장군 | **있음** (level 8) |
| 위장군 | **있음** (level 6) |
| 대사농 | **없음** |

**6종 중 5종이 이미 v1 칭호로 존재한다.** 없는 것은 대사농 하나이고, 반대로 v1에는 묘섭 임원진에 없는 태위(level 7)가 있다. 그리고 v1은 이 칭호들에 **효과도 이미 주고 있다** — `OfficerLevelModule`이 통솔 보너스(`:39-43`)·내정 ×1.05(`:50-66`)·전투 배율(`:69-77`)을 `officerLevel`로 건다. 즉 "v1에는 이름만 있고 효과가 없다"는 오해이고, 정확한 사실은 **v1의 효과와 묘섭의 효과가 서로 다르다**는 것이다.

그러므로 **재사용**이다. 대체는 두 가지 이유로 기각한다. ① ADR-LITE-018이 v1을 오리지널로 동결했다. ② L1을 걷어내면 `OfficerLevelModule`의 세 훅을 전부 대체해야 하고, 그것은 O0 전체와 맞먹는 작업이다. 빠진 대사농 1종은 **오픈 후** L1 칭호 맵의 v2 전용 변형에서 추가한다(국가레벨 7 슬롯 교체 또는 확장). 오픈 경로 증분 0.

### 5-bis.3 품관 축을 도입하나 보류하나 — **신설하지 않는다. 이미 있기 때문이다.**

먼저 묘섭 품관이 무엇인지 확정한다. 네 갈래 근거가 일치한다.

- **공헌치가 품관을 올리고, 품관이 봉록을 올린다**: "공헌 은 품관을 올리기 위해 필요한 수치이며, 품관이 높을수록 봉록의 양이 증가합니다." — `help__start__beginner__lookinfo.md:166`
- **경험치(레벨)와 별개 축이다**: "경험치(레벨)와 공헌치(품관)" — `help__start__intermediate__intermediatedomestic.md:72`
- **관직 임명의 자격 게이트다**: 태수·군사는 **7품관 이상** + 무(지) 70 이상, 임원진 6종은 **6품관 이상** + 통·무·지 70 이상, 승상·대장군은 **5품관 이상** + 60 이상, 참모는 **5품관 이상** — `intermediatedomestic.md:221-241`
- **전투 상한을 스케일한다**: "징병과 지휘 모두 자신의 통솔 수치와 품관 에 비례하여 최대 징병/지휘 가능한 수가 결정됩니다." — `help__start__beginner__battlebasic.md:85` (개정 2차 경로 정정 — `intermediate` 계열에 `battlebasic`은 **없다**) · 부대 창설은 **6품관 이상** — `help__start__intermediate__squad.md:121`

**전체 품계 수는 UNKNOWN이다.** 도움말에서 확인되는 등급은 5·6·7품관 셋뿐이고, 1~9품 체계인지 다른 눈금인지는 원문에 **없다.** 지어내지 않는다.

그리고 opensamguk에 대응물이 **이미 있다.**

- `General.dedication`(공헌치) — `logic/src/main/kotlin/opensamguk/logic/domain/LogicEntities.kt`의 필드
- `General.dedlevel()` — `logic/src/main/kotlin/opensamguk/logic/domain/GeneralMeta.kt:24-25`, KDoc이 "the dedication level (승급/강등 target)"이라 적고 있으며 `meta` jsonb에 산다
- 승급·강등 판정 — `logic/src/main/kotlin/opensamguk/logic/domestic/StatChange.kt:127,133`이 `nextLevel.compareTo(general.dedlevel())`로 방향을 잡고 `dedication`과 `dedlevel`을 함께 갱신한다
- 소비 — `getBill(dedication) = getBillByLevel(getDedLevel(dedication))`, `getBillByLevel(d) = d * 200 + 400` (`logic/src/main/kotlin/opensamguk/logic/domestic/DomesticHelpers.kt:81-84`, PHP `func_converter.php:664-666`)

마지막 항목이 결정적이다. **`getBillByLevel`은 등급이 높을수록 봉록이 커진다.** 묘섭 `lookinfo.md:166`의 "품관이 높을수록 봉록의 양이 증가합니다"와 **같은 소비**다.

**그리고 이름조차 다르지 않다 (개정 2차 — 근거 보강).** 원안은 "같은 축이며 이름만 다르다"고 썼으나 근거를 대지 않았다. 확인 결과 v1이 화면에 찍는 문자열이 문자 그대로 "품관"이다.

```kotlin
// logic/src/main/kotlin/opensamguk/logic/domestic/DomesticHelpers.kt:74-78 (PHP func_converter.php:602-609)
fun getDedLevelText(dedLevel: Int): String {
    if (dedLevel == 0) return "무품관"
    val dedInvLevel = MAX_DED_LEVEL - dedLevel + 1     // MAX_DED_LEVEL = 30 (:45)
    return "${dedInvLevel}품관"
}
```

즉 v1은 이미 **1품관~30품관 + 무품관**을 쓰고 있고, 등급이 **역순**(내부 level 30 = 표시 1품관)이라는 것까지 같다. `GameConst.maxDedLevel = 30`(`common/src/main/kotlin/opensamguk/common/constants/GameConst.kt:48`)이 정본 상수이며 FE에도 노출된다(`GetConstController.kt:157`). 따라서 판정은 "도입/보류"가 아니다.

> **그리고 이것이 §5-bis.3 말미 "눈금 UNKNOWN"의 내용을 좁힌다.** v1 눈금은 **30등급 역순**으로 확정이다. 남은 UNKNOWN은 *묘섭의 "7품관"이 이 30등급 눈금 위의 7인지*뿐이며, 묘섭 도움말에 전체 품계 수가 없으므로 그 매핑은 여전히 확인 불가다. 30등급 역순에서 "7품관"은 내부 `dedLevel = 24`로 상위권이지만, 묘섭이 1~9품 체계였다면 완전히 다른 위치가 된다. 게이트 상수를 박지 않는 이유가 이것이다.

> **품관 축은 신설하지 않는다. `dedication`/`dedlevel`이 그 축이고 이미 v1에서 돌고 있다. 남은 것은 그 위에 게이트를 얹는 일뿐이며, 그 게이트는 §5의 기준선에 걸려 오픈 후다.**

§5의 기준선은 *"§2의 도시 원장·공백지화 판정 위에 계수 하나로 얹히면 채택, v1 전투·계략·병종 수치를 입력으로 삼으면 보류"*였다. 묘섭 품관의 네 소비에 그대로 적용한다.

| 묘섭의 품관 소비 | 기준선 적용 | 판정 |
|---|---|---|
| 봉록 증가 | 이미 `getBill`/`getBillByLevel`이 한다 | **증분 0** — 도입할 것이 없다 |
| 징병·지휘 최대치 스케일 | **v1 전투·징병 수치를 입력으로 삼는다** | 보류 = 오픈 후 |
| 부대 창설 자격(6품관) | 부대는 V2-2 부곡이며 그 자체가 오픈 경로 티켓 | 보류 = 오픈 후 |
| 관직 임명 자격 게이트 | v1 임명 커맨드의 제약 조건 변경 | 보류 = 오픈 후 (사유는 아래 단서) |

**기준선을 확장할 필요가 없다.** 네 소비 중 셋이 기준선 문언에 그대로 걸리고 하나는 이미 구현되어 있다. 다만 넷째 줄에는 단서를 단다 — 관직 임명 게이트는 전투 수치가 아니라 **인사 판정**이라 기준선 문언에 딱 맞지 않고, §3의 4축 중 "인사권"에 직결되므로 매력적이다. 그럼에도 오픈 후로 보내는 이유는 기준선이 아니라 **날조 금지**다: 묘섭의 "7품관"과 devsam `getDedLevel`의 눈금이 같은 눈금인지가 **UNKNOWN**이고, 눈금을 모르는 채 게이트 상수(`>= 7`)를 박으면 그 숫자는 지어낸 값이 된다. 오픈 후 `getDedLevel`의 실제 구간을 읽고 정한다. 이때도 **새 축을 만들지 않고 기존 `dedlevel` 위에 게이트만 얹는다**는 것을 지금 못 박아 둔다.

### 5-bis.4 이 중 오픈 경로에 들어가는 것이 있는가 — **없다. 셋 다 오픈 후다. 티켓 증분 0.**

단정한다.

- **L1** — 이미 v1에 있다. 남은 것은 대사농 1종 추가와 묘섭식 체류 효과 3종(§5)이고 둘 다 오픈 후. 증분 0.
- **L2** — v2 spec대로 O0/V2-7(OPENSAM-66~69)이며, ADR-LITE-019가 이미 오픈 후로 확정했다(`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md`의 오픈 후 목록). 이 설계안은 그 결정을 되돌리지 않는다.
- **L3** — 신설할 것이 없고(이미 있다), 게이트 3종은 눈금 UNKNOWN + 기준선 보류. 증분 0.

**§9의 티켓 수량에 이 절이 더하는 것은 0이다.** "빨리 열기"가 1순위인 이상, 이미 있는 축(L1·L3) 위에 오픈 전에 손댈 이유가 없고 없는 축(L2)은 오픈 경로 밖에 있기로 이미 결정되어 있다. 사용자 질문에 대한 답은 "**세 층으로 분리해 두되, 오픈 전에는 아무것도 하지 않는다**"이다.

---

## 6. 도시 특색 / 규모 게이트 / 지역병종 — 1 채택 / 나머지 전부 보류 (시험지 6)

### 6.1 도시 특색 — 먼저 "9종"의 정체부터

원문이 두 곳에서 서로 다르게 센다. 정직하게 기록한다.

- `help__start__advanced__optimizedomestic.md`: 표 헤더 `:133`(`이름 설명`), 행 `:135-149` — 금·쌀·군마·활·창검·병기·성벽·특수 = **8종**
- `help__start__advanced__optimizebattle.md:275-291`의 표: 동일한 **8종**
- `help__start__beginner__map.md:165`: "병기, 군마, 창검, 활, 특수, 금, 쌀, **방어**, **없음(無)**" = 이름 9개

즉 실제 특색은 **8종이고, "방어"는 "성벽"의 다른 표기, "없음(無)"은 무특색**이다. 채점기 기준선의 "9종"은 무특색을 포함해 센 값이다. 또한 금 특색의 효력이 두 문서에서 다르다 — `optimizedomestic.md:135`(개정 2차 행번호 정정 — `:133`은 표 헤더다)는 "매년 1월 해당도시의 금 수입이 30% 증가", `optimizebattle.md:277`은 시점 없이 "도시의 세금 수입이 30% 증가". 어느 쪽이 정본인지는 **UNKNOWN**이다.

발현 조건은 두 문서가 일치한다: **도시 기술 900 이상**, 단 특수·성벽(방어) 제외(`optimizedomestic.md:155`, `optimizebattle.md:270`).

여기서 opensamguk의 구조적 장벽이 드러난다. `LogicEntities.kt:64`가 못 박은 대로 **`city.tech`가 없다 — tech은 국가 스탯이다.** 그러므로 "도시기술 900" 게이트를 재현하려면 새 도시 컬럼 + 그 컬럼을 올리는 새 내정 커맨드 + 그 커맨드의 밸런싱이 통째로 필요하다. 게이트 없이 특색만 정적으로 부여하면 원본과 다른 밸런스를 오픈 시점에 도입하게 된다.

| 특색 | 판정 | 사유 |
|---|---|---|
| 성벽(방어) — 성벽 최대 내구도 2배 | **채택** (오픈 후, 시드 데이터) | 게이트가 없고(`optimizebattle.md:270`), 구현이 v2 DB 시드에서 `wall_max`를 2배로 넣는 것뿐. 코드 변경 0, v1 전투 엔진이 그대로 소화 |
| 금 / 쌀 (수입 +30%) | **보류** | `city.tech` 900 게이트 재현 불가. 게이트를 뺀 축소형은 원본과 다른 밸런스 |
| 군마 / 활 / 창검 / 병기 (징병·지휘 비용 -20%, 병종 레벨 +3) | **보류** | 징병 비용·병종 레벨 = v1 패러티 수치. §5와 같은 기준선 |
| 특수 (특수병종 징병 가능) | **보류** | 특수병종 16종은 콘텐츠 카탈로그(C-track, ADR-LITE-019로 오픈 후) 의존 |

**8종 중 1종 채택.** 그리고 그 1종조차 오픈 후 시드 작업이므로 오픈 경로 티켓 증분은 0이다.

### 6.2 규모 5단계와 게이트 2종

규모는 특·대·중·소·이 5단계이고(`help__start__beginner__lookinfo.md:73`), 게이트 두 개가 확인된다.

- 건국: "도시의 규모가 중(中) 혹은 소(小) 도시 인 곳에서만 건국이 가능합니다." — `help__start__advanced__establishnation.md:95`
- 아이템 매매: "아이템은 특 도시 에서만 구입하거나 매각할 수 있으며" — `help__start__beginner__sellandbuy.md:78`

**둘 다 보류.** 사유는 둘로 갈린다. 건국 게이트는 초반 세력 분포를 통째로 바꾸는 큰 밸런스 변경이고, 검증할 오라클이 없는 상태로 오픈 시점에 도입하기에는 리스크가 크다. 아이템 매매 게이트는 v1 `장비매매` 커맨드의 제약 조건 변경이라 v2 전용 커맨드 변형이 필요하고, 얻는 것은 "특 도시로 가야 한다"는 이동 부담 하나다.

규모 값 자체는 새 컬럼을 만들지 않는다. opensamguk `City`에 `level`이 있으나(`LogicEntities.kt:70`) 그것이 묘섭의 "규모"와 같은 축인지는 **UNKNOWN**이다. 오픈 시점에는 인구·성벽 수치에서 유도해 화면에 표시만 하고(티켓 0), 정식 필드화는 오픈 후 RTK 빌더(OPENSAM-104/105) 산출물과 함께 정한다.

### 6.3 지역병종

7지역(서촉·동이·오월·초·서북·중원·하북)에서만 징병 가능한 2.5차 병종 14종이다(`help__start__intermediate__intermediatebattle.md:166,172,193`, `help__start__beginner__map.md:159`). opensamguk에 `City.region`은 **이미 있다**(`LogicEntities.kt:86`).

**전면 보류.** 필드가 이미 있어서 싸 보이지만, 실제 비용은 필드가 아니라 **병종 14종의 수치·상성·기술 요구치 정의**이고 그것은 콘텐츠 카탈로그(C-track)이며 ADR-LITE-019가 이미 오픈 후로 보낸 영역이다. `region` 필드가 준비되어 있다는 사실은 오픈 후 착수 비용이 낮다는 뜻일 뿐, 지금 해야 한다는 뜻이 아니다.

### 6.4 §6 판정 요약

**특색 8종 중 1종 채택(오픈 후·시드) / 7종 보류. 규모 게이트 2종 중 0종 채택. 지역병종 7지역 0종 채택.** 오픈 경로 티켓 증분 0.

---

## 7. v1 패러티 불변 증명 (시험지 7)

### 7.1 방어선 — 개정으로 하나가 바뀌고 둘이 늘었다

**먼저 무엇이 바뀌었는지 밝힌다.** 관계 보정이 `ActionPipeline`에 들어가면서 원안 방어선 6(b)("`stats/**`가 관계 패키지를 import하지 않는다")가 **성립하지 않게 되었다.** 그 자리를 두 개의 새 방어선(7·8)이 메운다. 나머지는 그대로다.

1. **별도 DB.** ADR-LITE-018에 따라 v2는 `opensamguk_v2`를 쓴다. `v2_city_ledger`·`v2_general_bond`는 v1 DB에 마이그레이션되지 않으며, v1 Flyway `V*.sql` 계열에 파일이 추가되지 않는다.
2. **패러티 코어 0 수정 + 경계 가산 전용 (개정 2차 — 원안 재정의).**

   **원안은 자기모순이었다.** §2.1이 "R1이 `ChangeRecorder`·`JdbcFlushExecutor`에 채널과 step을 만든다"고 쓰면서 방어선 2는 "v1 프로덕션 파일 편집 0줄"이라고 선언했다. 두 문장은 동시에 참일 수 없다. §4.3에서 밝힌 두 주입 지점도 seam이 없어 **어떤 형태로든 `app/**` 편집을 요구한다.** 그러므로 방어선을 두 계층으로 나눠 다시 긋는다.

   | 계층 | 대상 | 규칙 |
   |---|---|---|
   | **T1 — 하드** | `logic/src/main/kotlin/**` · `common/src/main/kotlin/**` · `logic/src/test/resources/golden/**` · 기존 v1 테스트 | **수정·삭제 0건.** 신규 파일 추가만 허용. 예외 없음 |
   | **T2 — 경계, 가산 전용** | `app/*/src/main/kotlin/**` · `infra/src/main/kotlin/**` · `infra/.../db/migration/**` | 수정 허용, 단 **(a) 티켓 본문에 파일·지점을 사전 명시**, **(b) v1 경로에서 물리적으로 inert함을 보임**(빈 컬렉션 가드), **(c) 골든 재실행 green** 세 조건 전부 |

   **T1이 실제로 지켜지는가 — 두 치환이 모두 `logic/` 밖에서 끝난다.**
   - **월간 leaf 치환.** leaf 등록은 `logic/src/main/kotlin/opensamguk/logic/event/WorldActions.kt:30-56`의 하드코딩 등록 체인이고 기본 이벤트 행은 `logic/.../event/EventStore.kt:157+`다. 둘 다 T1이므로 열지 않는다. 대신 (i) v2 DB의 `event` 행에서 v1 leaf 이름을 v2 leaf 이름으로 바꾸고, (ii) 새 파일 `V2WorldActions`가 팩토리에 v2 leaf를 **체인 등록**한다. `logic/` 수정 0. **단, 그 체인 등록이 `app/**`의 한 줄을 연다 — 아래에서 파일명으로 단정한다.**
   - **파이프라인 조립자 주입.** §4.3의 seam 개설은 `app/game-engine`·`app/game-api`에서만 일어난다 = T2. `logic/.../stats/GeneralActionModuleFactory.kt`와 `ActionPipeline.kt`는 열지 않는다. **그리고 이 seam은 오픈 경로가 아니라 오픈 후(P0)다**(§4.3 개정 3차).

   #### 개정 3차 — leaf 치환의 실제 편집 지점을 파일명으로 단정한다

   개정 2차는 이 치환이 "`app/**` 편집 0으로 끝난다"고 주장했다. **거짓이다.** `WorldActions.register`의 프로덕션 호출부는 리포지토리 전체에 **하나뿐**이고(나머지 6곳은 전부 테스트 — `ScenarioBlankUnificationIT.kt:85,214` · `LongSimReplayGateTest.kt:169` · `MonthlyWorldEventSeamTest.kt:69` · `RegNpcActionTest.kt:197,226`), 그 하나가 이것이다.

   ```kotlin
   // app/game-engine/src/main/kotlin/opensamguk/engine/config/EngineEventConfig.kt:79-81
   @Bean
   fun eventActionFactory(): EventActionFactory =
       WorldActions.register(EventActionFactory())
   ```

   따라서 `V2WorldActions.register(WorldActions.register(...))` 체인은 **반드시 이 파일 이 빈에 쓰인다** = **T2 편집 1건**. `EngineEventConfig.kt`를 T2 목록에 추가한다.

   **v1 inert 근거 (T2 조건 (b)).** `EventActionFactory.register`는 `builders[name] = builder`로 `LinkedHashMap`에 **키를 추가**할 뿐이고(`logic/.../event/EventAction.kt:61-64`), 조회는 이름 단건이다(`:70-74`). 새 이름을 넣는 것은 기존 이름의 조회 결과를 바꿀 수 없다. **단 하나의 조건이 붙는다 — v2 leaf 이름이 v1 이름과 겹치면 안 된다.** 겹치면 같은 키를 덮어써서 v1 동작이 바뀐다. 그래서 v2 leaf 이름은 `ProcessIncome`이 아니라 **`V2ProcessCityIncome`**(§2.2)이며, 이 비충돌은 아키텍처 테스트로 고정한다(`WorldActions` 등록 이름 집합 ∩ `V2WorldActions` 등록 이름 집합 = ∅). 프로파일 게이팅이 추가로 필요하면 새로 만들지 않고 `OPENSAM-35` 0A-b의 **bean 등록 게이트**(`01-backbone-micro.md:76`)를 소비한다.

   **함께 기재해야 할 세 가지 — 개정 2차가 빠뜨렸다.**

   1. **DB `event` 행 대체는 병합이 아니라 all-or-nothing이다.** `EngineEventConfig.kt:46-68`은 `rows.isEmpty()`면 `EventStore.withDefaults(...)`(`:57`), 아니면 **빈 `EventStore()`에 DB 행만** 적재한다(`:58-68`). 한 행이라도 있으면 `DEFAULT_EVENTS` **12행**(`EventStore.kt:159,164,178,183,195,200,208,216,224,234,239,244`)이 통째로 무시된다. → **v2 행만 따로 INSERT하는 방식은 금지**다. 나머지 10행이 사라진다.
   2. **v2 DB의 `event` 표에 무엇이 들어가는가는 시나리오 플래그 하나가 정한다 (개정 4차 — 개정 3차의 "항상"은 거짓이었다).** 개정 3차는 `ScenarioImporter.kt:810`이 `EventStore.defaultWireRows()`(`EventStore.kt:121`)를 **항상** 적재한다고 썼다. 파일을 다시 열어 보면 그 위 세 줄에 가드가 있다.

      ```kotlin
      // infra/.../seed/ScenarioImporter.kt:807-818
      val defaults = if (scenario.ignoreDefaultEvents) {
          emptyList()
      } else {
          EventStore.defaultWireRows().map { row -> EventRowToInsert(...) }
      }
      // :819  val scenarioRows = scenario.events.map { ... }
      // :827  val deferredRows = deferredGeneralRows(startYear)
      // :828  for (row in defaults + scenarioRows + deferredRows) { jdbc.update("INSERT INTO event ..." :831) }
      ```

      플래그의 기본값은 `false`(`ScenarioJson.kt:299`, 파싱 `:69`)이고, 실제로 `true`를 쓰는 시나리오가 이미 있다 — `infra/src/main/resources/scenario/scenario_910.json:16`. 즉 "항상"이 아니라 **시나리오가 고르는 두 분기**이고, v2가 어느 쪽을 고르는지가 R2의 귀속 논거를 바꾸므로 여기서 고른다.

      | 분기 | 적재되는 것 | R2가 `ProcessIncome`을 치환하려면 |
      |---|---|---|
      | `ignoreDefaultEvents: false` (기본값) | `DEFAULT_EVENTS` 12행 **+** `scenario.events` | v1 1월 행(`EventStore.kt:164-176`, `ProcessIncome("gold")` = `:169`)이 **무조건 INSERT되므로** v2 행을 덧붙이면 v1 leaf와 v2 leaf가 **같은 달에 둘 다 돈다(이중 수입).** 적재된 행을 지우거나 고칠 seam이 `ScenarioImporter`에도 `EngineEventConfig`에도 없다 |
      | `ignoreDefaultEvents: true` | `scenario.events` **+** `deferredGeneralRows` | v2 시나리오 JSON이 **시나리오 유래 행 전체**를 저작한다. 치환은 "그 행을 v2 이름으로 적는 것"이 되고 삭제·수정 seam이 필요 없다 |

      > **판정: v2 시나리오는 `ignoreDefaultEvents: true`로 두고, v2 `event` 행 중 시나리오 유래 행 전체를 v2 시나리오 JSON이 저작한다.** 선례는 `scenario_910.json:16`이다(실측: 그 파일은 `ignoreDefaultEvents: true` + `events` 19행이고 클래스패스에 **추적되는** 파일이다).
      >
      > **"전체"를 "시나리오 유래 전체"로 좁혀 적는다(개정 7차 정정, 6차 채점 m-new-2).** `insertEvents`가 INSERT하는 것은 `defaults + scenarioRows + **deferredRows**`이고(`ScenarioImporter.kt:828`), 세 번째 항 `deferredGeneralRows(startYear)`(`:827` 호출 · `:840` 생성기)는 **시나리오 `events` 배열 밖에서 코드가 만든다** — 생몰년으로 아직 성인이 아닌 장수를 출생연도별로 묶어 `target="Month"`·`priority=1000` 행을 만들고 액션 이름은 `RegNPC`/`RegNeutralNPC`(`:878-879`) + `DeleteEvent`다. 즉 `ignoreDefaultEvents: true`여도 시나리오 JSON이 `event` 표의 **모든** 행을 저작하지는 못한다.
      >
      > **그럼에도 설계는 그대로다.** 그 자동 행이 부르는 세 이름은 전부 v1 leaf이고(`WorldActions.kt:51` `RegNpcAction` · `:52` `RegNeutralNpcAction` · `:54` `DeleteEventAction`), v2는 `V2WorldActions.register(WorldActions.register(...))` **체인**으로 v1 이름을 전부 유지하므로 `EventAction.create`(`:70-74`)의 미등록 예외가 나지 않는다. T2 편집도 늘지 않는다(체인 한 줄은 이미 T2 8행). 바뀌는 것은 서술 정확도뿐이며, R2 DoD의 행 수 대조(위 m-new-1 항목 (c))가 `deferredGeneralRows` 산출 수를 **더해서** 세도록 적은 것도 같은 이유다.

      **대가를 적는다 — v1 `DEFAULT_EVENTS` 12행의 전사(轉寫)가 데이터 이중 진실 1건으로 남는다.** v1이 행을 늘리면 v2 JSON은 따라오지 않는다. 그럼에도 이쪽을 고르는 이유는 반대 분기가 **이중 수입**이라는 조용한 정산 오류를 낳고 그것을 막을 코드 경로가 없기 때문이다. 이중 진실은 보이지만 이중 수입은 보이지 않는다.

      **그리고 이 판정이 티켓 경계를 정한다.** 1월 행의 `ProcessIncome("gold")`(`EventStore.kt:169`)·7월 행의 `ProcessIncome("rice")`(`:188`) 치환도, 1·4·7·10월 네 행(`:171`·`:180`·`:190`·`:197`)에 R3 leaf를 append하는 것도 **같은 파일 하나(v2 시나리오 JSON)를 고쳐 쓰는 일**이다. 따라서 두 작업은 병렬일 수 없다 — 귀속은 §9.2에서 R2 단일 소유로 확정하고 R3는 소비자로 뒤에 세운다. **2026-08-13 OPENSAM-44 계약 정정:** 제품 시드 메커니즘은 OPENSAM-43/44가 이미 구현한 것으로 보지 않는다. OPENSAM-150(R1)이 migration-before-seed 부팅 순서와 설정된 v2 시나리오 source→DB 적재 seam을 개설·실증하고, OPENSAM-151(R2)이 그 seam을 소비해 `ignoreDefaultEvents: true`인 v2 시나리오 JSON과 시나리오 유래 event 전량·재시드 검증을 소유한다. R3는 R2 뒤의 소비자다. 기존 6티켓 안의 소유권 정정이므로 **새 티켓 증분 0**이다.
   3. **DB 행만으론 치환이 성립하지 않는다.** `EventAction.kt:70-74`의 `create`가 미등록 이름에 `IllegalArgumentException("존재하지 않는 Action입니다 :${raw.name}")`를 던진다(`:72`). **팩토리 등록(위 `EngineEventConfig.kt:81`)이 행 수정보다 반드시 선행**해야 하며, 순서가 뒤집히면 v2 월드가 첫 1월에 예외로 죽는다. R2·R3의 DoD에 이 순서를 명시한다.

   **부수 — `event` 로드는 world-scoped가 아니다. 그런데 그럴 필요가 없다(개정 6차 정정).** `EngineEventConfig.kt:47`의 쿼리는 `SELECT ... FROM event ORDER BY id ASC`로 **`world_id` 필터가 없다.** 시드는 `world_id`를 넣는데(`ScenarioImporter.kt:831`) 로드는 무시한다.

   개정 5차는 여기서 "즉 v1/v2 이벤트 분리는 **ADR-LITE-018의 별도 DB 결정에 전적으로 의존한다** … 같은 DB에 두 월드를 올리지 않는다는 전제를 DoD에 적는다"고 썼다. **DoD에 적을 일이 아니라 코드가 이미 강제하고 있다.** `infra/.../seed/ScenarioSeedCoordinator.kt:37-49`가 부팅 시드 경로에서 `world_state`의 id 목록을 통째로 읽어 **정확히 세 갈래로만** 처리한다 — 비었으면 시드, `ids == listOf(설정 world id)`면 skip, **그 외 전부 `error(...)`**(`:46-48` `"Scenario seed requires exactly configured world_state.id=…; found $ids"`). 즉 **한 DB에 두 월드를 올리면 그 DB를 보는 엔진이 부팅에 실패한다.** 이 경로는 `SeedBootstrap.ensureSeeded`(`app/game-engine/.../boot/ScenarioSeedRunner.kt:69-104`)를 통해 세 진입점이 모두 부르므로(`ScenarioSeedRunner.kt:47` · `WorldSnapshotLoader.kt:53` · `EngineEventConfig.kt:40` — grep 전수) 우회로도 없다.

   > **따라서 "한 DB = 한 월드"는 이 설계안이 요청하는 전제가 아니라 리포가 **시드 활성 부팅에서** 이미 강제하는 불변식이다.** `world_id` 필터가 없어도 v1 월드가 v2 `event` 행을 볼 물리적 경로가 존재하지 않는다 — 애초에 같은 DB에 있을 수 없기 때문이다. (`event` SELECT에 `world_id` 필터를 추가하는 것은 이 불변식이 이미 닫은 문제를 다시 닫는 것이므로 하지 않는다.)
   >
   > **범위를 좁혀 적는다(개정 7차 정정, 6차 채점 m-new-3).** 위 세 진입점은 전부 `SeedBootstrap.ensureSeeded`를 지나는데, 그 함수의 첫 문장이 `if (!seedEnabled) { … return false }`(`ScenarioSeedRunner.kt:70-73`)로 **코디네이터를 부르기 전에 반환한다.** 그러므로 `SCENARIO_SEED_ENABLED=false`로 뜬 프로세스에서는 `ScenarioSeedCoordinator`의 검사가 **한 번도 돌지 않는다.** 실측: `docker-compose.yml:171`은 기본 `true`이지만 `docker-compose.production.yml:66`은 기본 **`false`**다 — 즉 프로덕션 기본 스택이 정확히 이 경우다. **시드 비활성 부팅에서 "한 DB = 한 월드"를 담보하는 것은 코드가 아니라 0A DoD (i)의 env 분리 강제**(`GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`를 v1과 다른 값으로 주고 양 스택 모두 `SCENARIO_SEED_ENABLED=true`로 띄운다)다. 나머지 근거 α·β·δ·ε는 이 단서와 무관하므로 **토폴로지 확정(갈래 A) 자체는 그대로다.**

   **부수 2 — `ignoreDefaultEvents`의 두 번째 읽기 지점(개정 6차 신설, 5차 채점 m3).** 위 시드 분기는 시나리오 JSON의 `ignoreDefaultEvents`를 직접 읽지만(`ScenarioImporter.kt:194`가 그 값을 `world_state.config` jsonb에도 함께 적재하고 `:807`이 분기에 쓴다), **런타임에는 두 번째 읽기 지점이 따로 있다** — `EngineEventConfig.kt:41-45`가 `SELECT COALESCE((config->>'ignoreDefaultEvents')::boolean, false) FROM world_state WHERE id = ?`를 **프로세스 월드 id로** 읽는다(`:44` `worldId.value`). 두 지점이 서로 다른 소스를 보므로 명기한다.

   > **판정: v2에서 이 런타임 값은 결과에 영향을 주지 않는다. T2 편집 0.** 그 값이 소비되는 곳은 `:57` `if (rows.isEmpty()) return EventStore.withDefaults(ignoreDefaults)` **한 곳뿐**이고, v2는 위 판정대로 시나리오 JSON이 시나리오 유래 `event` 행을 저작하고 거기에 `deferredGeneralRows`까지 더해지므로 `rows`가 비지 않는다(두 갈래 중 하나만 있어도 비지 않는다 — 개정 7차) ⇒ `ignoreDefaults`는 읽히기만 하고 쓰이지 않는다. 그리고 `ScenarioImporter.kt:194`가 같은 값을 `config`에 실으므로 v2 월드의 `world_state.config`에도 그 키가 실제로 들어간다(적재 경로 확인 완료). 두 지점이 어긋날 수 있는 유일한 경우는 "시나리오가 `event` 행을 하나도 저작하지 않는 것"이고, 그것은 v2 R2·R3의 DoD가 금지한다.

   #### 개정 4차 — T2 전량 목록을 **확장점→구현자 추적 결과**로 다시 작성한다

   개정 3차는 이 자리에서 "**지금 전부 적는다**"고 선언하고 6+1 파일을 적었다. **완전하지 않았다.** 확장점(인터페이스·레지스트리·`when` 분기)의 **이름**까지만 적고 **그 확장점을 프로덕션에서 실제로 구현·소비하는 파일까지 내려가지 않았기** 때문이다. 개정 4차는 R1~R6 각각에 대해 ① 어떤 확장점이 필요한가 ② 그 확장점의 **프로덕션 구현자가 몇 개이고 어느 파일인가(grep 실측)** ③ 따라서 어떤 파일이 열리는가 ④ 그 파일의 v1-inert 근거는 무엇인가를 밟았다. 결과는 **6+1 → 11 편집 + 신규 마이그레이션 1**이다.

   **R1 — 원장 flush 5파일로는 부족하다. 읽는 쪽이 비어 있었다.**
   개정 3차의 6개(`DirtyState.kt` 행 모델 · `ChangeRecorder.kt` 필드·`isDirty`·record·accessor·clear 5지점 · `flush/DatabaseHooks.kt` payload 매핑 · `infra/.../persistence/JdbcFlushExecutor.kt` payload 필드·row 클래스·step 분기·SQL · `flush/TruncateContract.kt` 분류 · 새 마이그레이션 1개)는 전부 **쓰기 경로**다. 그런데 v2 leaf(R2·R3)와 v2 커맨드 핸들러(R4·R5)는 **잔액을 읽어야** 판정한다. 개정 4차는 그 읽기 진리를 `InMemoryTurnWorld` ← `WorldSnapshot` ← `WorldSnapshotLoader.buildSnapshot()`로 잡고 두 파일(`turn/InMemoryTurnWorld.kt`, `boot/WorldSnapshotLoader.kt`)을 T2에 넣었다.

   > **개정 5차 — 읽기 결론은 틀렸다. 그 경로는 구조적으로 막혀 있다.** 아래 "메커니즘 역추적" 절에서 v1 아키텍처 테스트가 그 두 파일을 물리적으로 봉인하고 있음을 파일 근거로 보인다. R1의 **읽기** 경로는 v1 자산을 경유하지 않는 신규 파일로 다시 그었고, `InMemoryTurnWorld.kt`·`WorldSnapshotLoader.kt`는 T2에서 내려갔다.
   >
   > **개정 6차 — 쓰기 쪽은 되돌린다.** 5차는 읽기와 함께 쓰기 3개(`DatabaseHooks.kt`·`JdbcFlushExecutor.kt`·`TruncateContract.kt`)도 내리고 `TurnRunService.kt`를 올렸는데, 그 근거였던 "`JdbcFlushExecutor`는 v1 DataSource에 묶여 있다"가 **거짓**이었다(아래 (3)절·"배포 토폴로지 확정" 절). **v2 프로세스에서 그 실행기는 v2 DB를 가리키므로 v2 step을 더하는 것이 정상 경로다** ⇒ `DatabaseHooks.kt`·`JdbcFlushExecutor.kt` **복귀**, `TurnRunService.kt` **삭제**(이미 있는 두 호출 안쪽만 넓히므로 이 파일을 열 이유가 없다). `TruncateContract.kt`는 별개 이유(프로덕션 소비자 0)로 내려간 채다. 정본 목록은 이 절 말미의 **11행 표**다.

   **R2 — 등록 1줄 + 컨텍스트 구현자 1파일. `WorldActionContext.kt`가 빠져 있었다.**
   ① **왜 필요한가.** v2 leaf는 v1 `ProcessIncome`과 **동형**으로 짓는다 — leaf가 `ctx as? V2CityIncomeContext`로 자기 컨텍스트를 받는 cast-ctx 형태다. v1 `ProcessIncomeContext`를 그대로 쓸 수는 **없다**: 그 인터페이스의 멤버는 `val pipeline`·`incomeNations()`·`applyIncome()` **셋**이고(`logic/.../world/ProcessIncome.kt:215-219` — 개정 5차는 "둘뿐"이라고 썼는데 `pipeline`을 빠뜨린 오기였다. 실질 논거는 아래 `cityId` 부재이므로 판정은 불변), 봉록 대상을 나르는 `data class IncomeGeneral(id, dedication, officerLevel)`(`:51-55`)에 **`cityId`가 없다.** 봉록을 도시 원장에 귀속시키려면 장수의 소속 도시가 필요한데, 그 필드를 넣는 것은 T1 파일 수정이다. 그러므로 R2는 **자기 컨텍스트 인터페이스를 새 파일로** 만든다(T1은 신규 파일을 허용한다).
   ② **그 인터페이스의 프로덕션 구현자는 몇 개인가 — 하나다.** `ProcessIncomeContext`를 구현하는 프로덕션 파일은 grep 전수에서 `app/game-engine/.../world/WorldActionContext.kt` 단 하나다(`:76` import, `:116` 구현 선언, `:300~` 구현부, `:302 incomeNations()`, `:322 applyIncome()`). 월간 컨텍스트를 만드는 프로덕션 지점도 `WorldEventContextFactory.kt:72` 하나이며, 그 팩토리의 프로덕션 호출부는 `DaemonLoopConfig.kt:269` 하나다. **따라서 `WorldActionContext.kt`는 T2 목록에 반드시 들어간다.**
   ③ **기존 leaf 디스패치가 왜 흔들리지 않는가.** 이 클래스의 프로덕션 **생성 지점은 4곳**이다(`WorldEventContextFactory.kt:72` · `WorldActionContext.kt:920` · `MonthlyPostUpdateHook.kt:198` · `:322`). **개정 5차 정정 — 개정 4차는 "생성자를 넓히면 T2가 3파일 더 열린다"고 썼으나 그것은 과장이었다.** 이 생성자는 이미 후행 nullable 기본 파라미터 4개(`auctionRepository`/`auctionBidRepository`/`archiveHistoryReader`/`statisticSnapshotReader` — `WorldActionContext.kt:111-114`)를 갖고 있고, `:920`의 위치인자 호출도 **후행에 기본값 파라미터를 하나 더해도 그대로 컴파일된다.** 실제로 열리는 것은 3파일이 아니라 **그 값을 실제로 넘겨야 하는 경로 1줄기**뿐이다 — `WorldEventContextFactory.kt:72` → 그 팩토리를 부르는 `DaemonLoopConfig.kt:269`. `MonthlyPostUpdateHook.kt:198`·`:322`와 `WorldActionContext.kt:920`은 월간 leaf 경로가 아니거나 기본값으로 충분해 **무편집**이다. 그래서 개정 5차 표는 `WorldEventContextFactory.kt`·`DaemonLoopConfig.kt`를 정직하게 행으로 올렸다(4차는 "넓히지 않는다"고 선언해 두 파일을 목록 밖에 두었는데, 넓히지 않으면 v2 store가 leaf까지 도달할 경로가 없다 — 선언과 설계가 어긋나 있었다). cast-ctx leaf는 자기 인터페이스로만 캐스팅하고(`ProcessIncome.kt:190-191`), env-read leaf는 자기 키만 읽으므로(`WorldEventContextFactory.kt:82-88`), 인터페이스가 하나 더 붙어도 기존 `as?`·`env[...]`의 결과가 달라질 수 없다.
   ④ **v1-inert 증명.** (i) v1 월드의 `event` 표에는 v2 leaf 이름이 없으므로 새 메서드의 **호출자가 0**이다 — 물리적으로 실행되지 않는다. (ii) 새 메서드는 v2 원장 맵만 읽고 v1 필드에 쓰지 않는다. (iii) 이름 비충돌은 아키텍처 테스트로 고정(위). (iv) 골든 재실행 green.
   > **왜 env-read가 아니라 cast-ctx인가.** `WorldEventContextFactory.kt:23-31`이 두 패턴의 실패 양식을 문서화한다 — cast-ctx는 미스가 **크래시**(과거 prod 턴 동결의 직접 원인), env-read는 미스가 **무음 no-op**(재해·특기가 조용히 미실행). v2 도시 수입이 조용히 건너뛰어지면 원장이 틀린 채로 게임이 계속 돌아 **원인 없는 잔액 불일치**가 된다. 시끄럽게 죽는 쪽을 고른다.

   **R3 — 추가로 열리는 T2 파일 0개. 그리고 그 사실이 MAJOR-2의 파일 단위 증거다.**
   R3도 v1 `DisasterWorldView`를 재사용할 수 없다 — `data class DisasterCity(cityId, name, state, secu, secuMax)`(`logic/.../world/RaiseDisaster.kt:56-62`)에 **`garrison`이 없고**, 그 leaf는 `env[ENV_WORLD] as? DisasterWorldView ?: return`(`:227`)의 **env-read 패턴**이라 미스가 무음이다. 그래서 R3도 자기 컨텍스트(신규 파일) + cast-ctx로 간다. **그 컨텍스트의 프로덕션 구현자 역시 `WorldActionContext.kt` 하나다**(`:65` import, `:124` 구현 선언, `:600~` 구현부 — grep 전수에서 프로덕션 구현자 1개). 즉 R2와 R3는 **같은 파일 하나를 함께 넓힌다.** CLAUDE.md의 "병렬 워크트리 family는 disjoint해야 한다"에 정면으로 걸리므로 §9.2에서 순차로 세운다(§9.2 R3 행).

   **R4·R5 — 인테이크 경로. T1 벽이 두 개 있고 둘 다 피해 간다.**
   먼저 **턴-예약(`che_*`) 경로는 기각한다.** 등록 seam이 `logic/.../actions/CommandRegistry.kt:121 fun resolve(actionCode)`의 하드코딩 `when`이고 `:224 else -> RestAction`이라 **미등록 코드는 조용히 휴식이 되어 턴이 소각**된다. 그 파일은 T1이다. 남는 것은 리포 정본 계약인 인테이크(Model B)다 — 5단계 배선 계약이 `logic/.../actions/instant/InstantActionRegistry.kt:28-42`에 못 박혀 있다(1단계 `:31` logic 본체 / 2단계 `:33` 엔진 핸들러 / 3단계 `:35` `CommandWireMapper` / 4단계 `:38` 디스패처 / 5단계 `:39` "새 컨트롤러를 만들지 말 것").
   - **T1 벽 ①: 5단계를 그대로 따를 수 없다.** `InstantActionController`의 분류기가 `InstantActionRegistry.isInstantAction(code)`(`:83`)과 `InheritActionRegistry.isInheritAction(code)`(`:84`)를 읽고 둘 다 아니면 400을 돌려준다(`:85-87`). 두 코드 집합(`InstantActionRegistry.kt:56` · `inherit/InheritActionRegistry.kt:47`)은 **`logic/` = T1**이다. 그러므로 v2는 5단계에서 **의도적으로 divergence**한다 — v2 전용 코드 레지스트리(신규 파일, logic v2 패키지)와 v2 전용 인테이크 컨트롤러(신규 파일, game-api)를 만든다. 신규 파일이므로 T1·T2 어느 규칙도 깨지 않는다. **v1 계약을 어긴 것이 아니라, v1 계약이 T1 레지스트리를 전제하기 때문에 v2에서는 성립하지 않는다는 사실을 기록하는 것이다.**
   - **T1 벽 ②: wire variant를 기존 파일에 넣을 수 없다.** `common/.../wire/TurnDaemonCommand.kt`는 T1인데 74개 variant가 **전부 `sealed class TurnDaemonCommand`(`:14`~`:940`) 본문 안에 중첩 선언**돼 있다(grep 실측: 중첩 74개, 파일 밖 선언 0개, 리포 전체에서 이 sealed 계열의 서브클래스를 다른 파일에 둔 선례 0건). 중첩은 파일을 열지 않고는 추가할 수 없다. → **같은 패키지의 신규 파일에 최상위 서브클래스로 선언**한다(Kotlin 1.5+ sealed 규칙이 허용). 다만 `@Serializable` sealed 직렬화기가 파일 밖 서브클래스를 자동 등록하는지는 **컴파일로 확인하지 않았다 → §11 U9로 남긴다.**
   - **그래서 실제로 열리는 T2는 두 파일이다.** `app/game-api/.../reserve/CommandWireMapper.kt`(`intakeCodes` `:43` + `toCommand` `when` `:140-149`) · `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt`(`dispatch` `when` `:326`, 기본 분기 `:397 else -> null`). 핸들러는 디스패처가 **내부에서 생성**하므로(생성자는 repository seam만 받는다 — `:75-115`) 엔진 설정 파일은 열리지 않는다.
   - **0 편집으로 남는 것을 근거와 함께 적는다.** `CommandReserveService`는 인테이크 분기(`:120-144`)에서 `CommandRegistry`를 **거치지 않는다** — `registry.resolve`는 Model A 분기의 `:177` 한 곳뿐이다. `GameApiSecurityConfig`는 `:47 anyRequest().permitAll()`이므로 새 엔드포인트에 매처가 필요 없다. v2 인테이크는 v1과 같은 자세(널 허용 principal + 소유권 가드 — `InstantActionController.kt:90-92` 형태)를 복사해 **보안 설정 파일을 열지 않는다.** v2 인증을 더 조이려면 그때 티켓이 `GameApiSecurityConfig.kt:42-47`을 **새로 선언**해야 한다(이 티켓 집합에서는 열지 않는다).
   - **`TurnDaemonCommandResult`(성공·거절 양쪽) 반환은 이미 계약이다** — 디스패처 기본 분기가 `else -> null`(무음 드롭)이므로 v2 variant가 `when`에 없으면 FE의 `pollCommandResult`가 영원히 `RESOLVED`를 못 본다. 3·4단계는 원자적으로 같은 커밋에 들어간다(§2.5·§8의 OPENSAM-13/135 규약).

   **R6 — T2 편집 0. 단, 개정 4차가 댄 논거는 절반만 참이고 절반은 스스로를 반증한다(개정 5차 정정).**
   개정 4차는 `GameApiApplication.kt:8`의 `@SpringBootApplication`이 `opensamguk.gameapi`를 전부 스캔하므로 열 파일이 없다고 썼다. `@RestController`·`@Configuration`에 대해서는 참이다. **그러나 바로 아래 두 줄이 JPA 경로에서 그것을 뒤집는다.**

   ```kotlin
   // app/game-api/src/main/kotlin/opensamguk/gameapi/GameApiApplication.kt:8-10
   @SpringBootApplication
   @EntityScan(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
   @EnableJpaRepositories(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
   ```

   `:9-10`은 기본 스캔을 **대체하는 명시적 화이트리스트 3개 패키지**이고, game-api read 계층은 사실상 전부 Spring Data JPA다 — `app/game-api/.../read/` 31개 파일 중 **23개**가 `JpaRepository`/`CrudRepository`/`SpringDataRepository` 계열이고, game-api main 전체에서 `JdbcTemplate`을 쓰는 파일은 **4개**뿐이다(`config/ReadBarrierDataSourceConfig.kt` · `consistency/PrimaryWorldVersionReadRepository.kt` · `controller/SelectPoolController.kt` · `reserve/CommandReserveService.kt`). 0A-a/0A-f의 v2 네임스페이스 격리를 지키면 v2 엔티티·리포지토리는 화이트리스트 **밖**에 떨어진다.

   **택1 — (b) v2 read는 `JdbcTemplate`으로 못 박는다.** 나머지 둘은 선택지가 아니라 **v1을 깨는 길**이다.
   - (a) `GameApiApplication.kt:9-10`에 v2 패키지를 더한다 → `app/game-api/src/main/resources/application.yml:8-10`이 `spring.jpa.hibernate.ddl-auto: validate`다. `@EntityScan`에 v2 엔티티가 들어오면 Hibernate가 **부팅 시 v1 DB를 상대로 v2 테이블을 검증**하고, 방어선 1에 따라 v1 DB에는 그 표가 없으므로 **v1 game-api가 기동에 실패한다.** T2 조건 (b)(v1 경로에서 inert)를 만족할 수 없다.
   - (c) v2 엔티티를 `opensamguk.gameapi.read` 하위에 둔다 → 0A-a/0A-f 격리를 정면으로 깨고, (a)와 **똑같은** `ddl-auto: validate` 부팅 실패를 부른다.
   - **(b)** 선례가 이미 4개 있고(위), 화이트리스트를 건드리지 않으며, `ddl-auto: validate`의 사정권 밖이다. v2 read 컨트롤러·리포지토리·`@Configuration`은 전부 **신규 파일**이며 `opensamguk.gameapi.v2` 패키지에 두면 `@SpringBootApplication`(`:8`)의 컴포넌트 스캔이 등록을 대신한다 ⇒ **T2 편집 0은 유지되지만, 유지되는 이유가 "전부 스캔된다"가 아니라 "JPA를 쓰지 않는다"로 바뀐다.**

   **v2 DataSource 배선 — 개정 6차에서 답이 바뀌었다(더 짧은 쪽으로).** 개정 5차는 4차 채점 UNKNOWN-5에 "서비스마다 신규 `@Configuration` 1개가 **자체 Hikari 풀**을 만들되 `DataSource` 빈을 노출하지 않고 래퍼 타입만 내보낸다"고 답하고 선례로 `ReadBarrierDataSourceConfig.kt`를 들었다. **두 번째 풀은 필요 없다.** 아래 "개정 6차 — 배포 토폴로지 확정" 절이 파일 근거로 보이듯 **v2 월드는 자기 DB를 primary DataSource로 갖는 별도 프로세스**이므로, v2 코드가 오토컨피그 `JdbcTemplate`/`NamedParameterJdbcTemplate`을 그대로 주입받으면 그것이 곧 `opensamguk_v2` 커넥션이다. 풀을 하나 더 세우는 것은 같은 DB에 두 번째 커넥션 풀을 만드는 순수 낭비다.

   > **남는 것은 등록 게이트뿐이다.** v2 빈은 같은 이미지가 v1 프로세스로 뜰 때 **등록되면 안 되므로**(그때의 오토컨피그 DataSource는 v1 DB이고 v2 표가 없다) `OPENSAM-35` 0A-b 게이트(`01-backbone-micro.md:76` — `V2_ENABLED`+`v2-sandbox` 동시 조건 route/bean 등록 게이트)를 `@ConditionalOnProperty`로 건다. **게이트 off ⇒ 빈 미등록 ⇒ SELECT 자체가 존재하지 않는다.** `application.yml`은 여전히 무수정이다 — 접속 정보를 새로 읽을 필요가 없어졌으므로 오히려 더 확실하다.
   >
   > (개정 5차가 인용한 `ReadBarrierDataSourceConfig.kt`의 Hikari 조립 범위 `:37-45`/`:43-45`는 실제 **`:33-43`**이다 — 5차 채점 m4, 범위 오차 정정. 이 선례는 이제 "v2도 이렇게 한다"가 아니라 "`DataSource` 빈을 `@Primary` 없이 하나 더 노출하면 Boot 자동설정이 흔들린다"는 **금지 근거**로만 남는다. v2는 새 `DataSource` 빈을 아예 만들지 않으므로 그 위험 자체가 없다.)

   #### 개정 5차 — **메커니즘에서 역추적한다.** 확장점에서 출발하는 절차는 네 바퀴 연속 실패했다

   4차 채점의 유일한 N은 또 이 목록이었고(`BootstrapConfig.kt`·`GameApiApplication.kt` 누락), 실패 유형은 **네 바퀴 연속** 같았다. 원인은 자기채점 취약점 0번이 자백한 그대로다 — 추적을 **내가 필요하다고 생각한 확장점**에서 시작했으므로 필요한 줄 몰랐던 것은 영영 목록 밖이었다. 그래서 개정 5차는 출발점을 뒤집는다. 확장점이 아니라 **메커니즘**(부팅 등록·게이트·DataSource/마이그레이션·직렬화/wire·아키텍처 테스트)에서 역으로 걸어 내려와 R1~R6에 무엇이 걸리는지 센다. 그 결과 12행 중 **8행이 바뀌었다(5행 삭제·3행 신설).**

   **(0) 선례 파일셋 복제 — P6 betting 채널은 실제로 몇 개 파일을 열었나.** `grep -rli "betting" --include="*.kt"`를 main 소스셋에 돌리면 **63개 파일**이 걸린다(app 30 / infra 6 / logic 15 / common 3 …). 개정 3·4차가 "P6 betting 선례"로 인용한 4개(`ChangeRecorder`·`DatabaseHooks`·`JdbcFlushExecutor`·`TruncateContract`)는 그 부분집합이고, **그 목록에 있으면서 이 설계안이 한 번도 이름을 적지 않은 파일**이 곧바로 나온다 — `turn/RehydrateService.kt`(재시작 재수화), `config/DaemonLoopConfig.kt`(런타임 조립), `logic/memory/HotColdCatalog.kt`(읽기 경계 카탈로그, **`logic/` = T1**), `infra/read/BettingRepository.kt`·`infra/persistence/NgBettingRowMapper.kt`(신규 파일). 즉 "flush 5파일"은 채널 하나의 **일부**였다.

   **(1) 아키텍처 테스트 역추적 — 개정 4차의 T2 6·7행은 물리적으로 불가능하다.**
   `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HotColdWorldCatalogGuardTest.kt`가 `WorldSnapshotLoader.kt`를 **소스 텍스트로 읽어** 봉인한다.
   - `snapshot loader data reads are cataloged` — 로더의 `private fun load[A-Z]…|resolveActiveGame` 이름 집합이 `HotColdCatalog.snapshotMethodNames`와 **`assertEquals`**. 로더에 `private fun loadV2CityLedger()`를 더하면 **즉시 실패**한다.
   - `snapshot loader SQL calls stay inside cataloged helpers` — 로더 안의 모든 `jdbc.query`/`queryForObject` 호출은 **카탈로그에 등재된 private 메서드 안**에 있어야 한다. `buildSnapshot()` 본문에 직접 넣으면 enclosing이 `<top-level>`이라 실패한다.
   두 테스트를 통과시키는 유일한 길은 `HotColdCatalog.snapshotAccesses`에 항목을 더하는 것인데, 그 파일은 `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` = **T1 하드(수정 0건, 예외 없음)**. 게다가 그 가드 테스트 자체가 `app/game-engine/src/test/kotlin/`에 있어 §7.2 게이트 ②가 잠그는 경로다. **⇒ v2 원장 SELECT는 `WorldSnapshotLoader`에 넣을 수 없다.** 4차 채점 MAJOR-A가 지목한 "무조건 등록되는 bean 안의 SELECT" 문제는 (a)(`BootstrapConfig.kt` 추가)로도 (b)(로더 내부 자기판정)로도 닫히지 않는다 — **애초에 그 파일에 코드를 넣을 수 없기 때문이다.** 따라서 T2 6·7행을 삭제하고, `BootstrapConfig.kt`도 **추가하지 않는다**(v2 원장이 `InMemoryTurnWorld`를 경유하지 않으므로 그 조립점을 지날 이유가 사라진다).

   같은 가드가 **신규 파일에도 조건을 건다** — "신규 파일은 어디든 허용"이 무조건 참이 아니다. `HotColdCatalog.runtimeSourceDirectories`(`:135-144`)는 `engine/{auction,intake,redis,run,tournament,turn,war,world}` **8개 디렉터리**를 재귀 스캔하고(+`DaemonLoopConfig.kt` 1개 명시 추가), 그 안의 파일이 (i) `*Repository`/`*Reader`로 끝나는 타입의 필드를 갖고 메서드를 부르면 `runtimeCallKeys`/`runtimeCallCounts` `assertEquals`가, (ii) `JdbcTemplate`/`NamedParameterJdbcTemplate`/`Connection`/`DataSource` 필드를 가지면 `runtimeDirectSqlBoundarySources` `assertEquals`가 깨진다. 둘 다 기대값이 **T1 파일에 박혀 있다.** (iii) **개정 6차 추가 — 세 번째 파괴 양식이 있다.** 그 파일이 `*Repository`/`*Reader` 수신자 **확장 함수**를 하나라도 선언하면(`HotColdWorldCatalogGuardTest.kt:390-391 hasRepositoryExtension`) 그 파일 안의 **모든** `find|read|claim|count|sum|target|mark|load`로 시작하는 호출이 `<implicit>.…` 키로 잡힌다(`:334-340`). 이 규칙은 이미 발화 중이다 — `engine/tournament/ProductionTournamentBettingPort.kt:254`·`:262`·`:272`가 그런 확장을 선언하고 있고, 그래서 가드의 예외 목록(`:394-405`)에 `bettingInfoReader`·`lastBettingIdReader`·`previousPointReader` 세 이름이 박혀 있다. ⇒ **v2는 스캔 대상 디렉터리 안에서 `*Repository`/`*Reader` 확장 함수를 선언하지 않는다.**

   ⇒ **v2 엔진 신규 클래스는 전부 `opensamguk.engine.v2` 한 패키지에 둔다.** 그 디렉터리는 위 8개에 없고, `DaemonWriteGuard.writePathPackages`(`engine/{flush,turn,run,nationbulk}` — `DaemonNoEntityManagerTest`가 클래스 상수풀을 스캔)에도 없다.

   > **개정 6차 — 범위를 좁혀 다시 쓴다.** 개정 5차는 여기서 "이 한 줄 규칙이 가드 위반 가능성 **전체**를 닫는다"고 썼다. **거짓이다** — 그 규칙은 **신규 파일에 한해서만** 닫는다. T2의 **편집** 행은 정의상 v1 파일을 여는 것이므로 `engine.v2` 격리로는 아무것도 닫히지 않고, 그중 여러 행이 위 8개 스캔 디렉터리 **안**에 있다. 5차는 그 사정권을 `DaemonLoopConfig.kt`·`WorldActionContext.kt` **2행만** 이름으로 적었다. 편집 대상 **전 행**에 대한 가드 영향은 아래 T2 표의 **"가드 영향" 열**과 그 뒤의 M2 절에 행마다 한 줄씩 적는다. 부수 제약(v2 타입 이름이 `Repository`/`Reader`로 끝나면 안 된다 — `V2CityLedgerStore`)도 두 파일이 아니라 **스캔 대상 전 행에 적용된다.**

   **(2) 게이트 역추적 — 게이트가 물리적으로 덮는 지점은 어디인가.** `OPENSAM-35` 0A-b는 "`V2_ENABLED`+`v2-sandbox` 동시 조건 **route/bean 등록 게이트**"(`01-backbone-micro.md:76`)다. 이것이 무언가를 막으려면 **막을 대상이 그 자체로 조건부 등록되는 빈**이어야 한다. 개정 4차는 이 게이트를 `BootstrapConfig.kt:45-55`가 **무조건** 등록하는 `WorldSnapshotLoader`의 메서드 본문에 걸겠다고 썼고, 그것은 성립하지 않는다(4차 채점 MAJOR-A — **지적이 옳다**). 개정 5차의 배치는 게이트 정의와 일치한다: v2 원장의 읽기·쓰기는 전부 `opensamguk.engine.v2`/`opensamguk.gameapi.v2`의 **신규 `@Configuration`이 `@ConditionalOnProperty`로 조건부 등록하는 빈** 안에 있고, 게이트 off면 그 빈이 없어 **쿼리도 커넥션 풀도 존재하지 않는다.** 소비 측은 `ObjectProvider<V2CityLedgerStore>.getIfAvailable()` = `null`을 받는다. 등록 자체는 `GameEngineApplication.kt:8`/`GameApiApplication.kt:8`의 컴포넌트 스캔이 하므로 **등록을 위해 열 파일은 없다**(`ReadBarrierDataSourceConfig.kt`가 같은 방식으로 이미 산다).

   **(3) DataSource·트랜잭션·마이그레이션 역추적 — 개정 5차의 이 항목은 틀렸다. 개정 6차에서 전면 교체한다.**
   - **DataSource — 5차의 논거는 거짓이다.** 개정 5차는 이렇게 썼다: "프로덕션 flush 체인은 `TurnRunService.kt:527`(`DatabaseHooks.toFlushPayload`) → `:404`(`flushExecutor.flush(payload)`)이고, 그 `JdbcFlushExecutor`는 `DaemonLoopConfig.kt:108`에서 **v1** `NamedParameterJdbcTemplate`로 생성된다. **그러므로 `JdbcFlushExecutor`에 v2 step을 더하면 v2 원장이 v1 DB에 써진다.**" 인용한 두 행은 정확하지만 **결론이 틀렸다.** `DaemonLoopConfig.kt:104-108`의 그 템플릿에는 한정자가 없다 — **오토컨피그 템플릿**이고, 따라서 그것이 무슨 DB를 가리키는지는 **프로세스가 무슨 env로 떴는지**가 정한다(`app/game-engine/src/main/resources/application.yml` `spring.datasource.url: ${GAME_DATABASE_URL:…}`). 아래 "개정 6차 — 배포 토폴로지 확정" 절이 보이듯 **v2 월드를 도는 프로세스의 오토컨피그 DataSource는 `opensamguk_v2`다.** 즉 그 프로세스에서 `JdbcFlushExecutor`는 처음부터 **v2 템플릿**이다. 5차가 이 두 행을 근거로 내린 `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` 두 행의 삭제, 거기서 파생된 두 번째 Hikari 풀, `TurnRunService` v2 싱크, §11 U11 — **넷 다 무효다.**
   - **그래서 되돌린다 — v2 원장은 v1 flush 기계를 그대로 탄다.** 델타는 `DirtyState`/`ChangeRecorder`에 실리고 → `DatabaseHooks.toFlushPayload`가 `FlushPayload`에 담고 → `JdbcFlushExecutor`가 v2 step에서 쓴다. `FlushPayload`는 `infra/.../persistence/JdbcFlushExecutor.kt:2287`에 사는 **T2 타입**(T1이 아니다)이므로 후행 기본값 필드 1개를 더할 수 있다. v1-inert 근거는 T2 1·2행과 **완전히 같다** — v1 프로세스에서는 v2 빈이 등록되지 않아 v2 컬렉션이 항상 비고, 빈 컬렉션이면 step에 진입하지 않는다(P6 betting 채널 선례). ⇒ `DatabaseHooks.kt`·`JdbcFlushExecutor.kt` **2행 복귀**, `TurnRunService.kt` **1행 삭제**(v2 싱크가 없어졌으므로 이 파일은 열 이유가 없다. `:527`·`:404`는 이미 있는 호출이고 우리는 그 안쪽만 넓힌다).
   - **트랜잭션 — 교차 원자성 문제가 사라진다.** v1 델타와 v2 델타가 **같은 DB의 같은 `TransactionTemplate`**(`DaemonLoopConfig.kt:108`이 `JdbcFlushExecutor(jdbc, TransactionTemplate(transactionManager))`로 만든 그것) 안에서 함께 커밋된다. 5차가 §11 U11로 올린 "두 커밋 사이 크래시 시 한쪽만 남는다"는 **성립하지 않는 상태가 됐다** ⇒ **U11 철회**(§11). 이것이 5차 설계 대비 개정 6차의 가장 큰 실익이며, T2 2행을 되돌리는 값을 치를 이유이기도 하다 — 원장의 찢어짐은 행 수보다 비싸다.
   - **부수 이득 — v2 쓰기 경로가 `DaemonWriteGuard` 안에 도로 들어온다.** 5차 설계의 v2 싱크는 `engine.v2`에 있어 `DaemonWriteGuard.writePathPackages`(`DaemonWriteGuard.kt:29-34`) **밖**이었다 — 즉 "데몬 쓰기 경로는 JDBC-only"라는 CLAUDE.md 하드 룰을 v2에 대해 강제하는 테스트가 **하나도 없는** 병렬 쓰기 경로를 새로 만들고 있었다. 개정 6차는 v2 쓰기를 `ChangeRecorder`(`engine/turn`) → `DatabaseHooks`(`engine/flush`) → `JdbcFlushExecutor`(`infra`, 형제 `InfraNoEntityManagerTest`가 담당)로 되돌리므로 **v2 쓰기 전 구간이 기존 가드 안**이다. 가드 목록을 넓히는 편집(=T2 +1행)도 필요 없다.
   - **마이그레이션.** `app/game-engine/.../application.yml:12-14`와 `app/game-api/.../application.yml:12-14`가 **둘 다** `spring.flyway.locations: classpath:db/migration`이다. `infra/src/main/resources/db/migration/`에 `V*.sql`을 놓으면 **v1 두 서비스가 v1 DB에 적용한다.** Java/Kotlin 마이그레이션도 같다 — `infra/src/main/kotlin/db/migration/V26__npc_lifecycle_phase_units.kt`가 그 자리에 산다. ⇒ v2 마이그레이션은 **다른 location**에 두어야 하고, 이는 이미 존재하는 확장점 `OPENSAM-35` **0A-c "v2 Flyway location을 v1 기본에서 분리"**(`01-backbone-micro.md:77`)를 소비하는 것으로 닫는다(신설 아님). T2 마지막 행의 문구를 그에 맞게 고쳤다.

     > **개정 6차 — 이 판정의 이유를 다시 쓴다(결론은 불변).** 토폴로지 확정 후 `classpath:db/migration`은 "v1 DB에 적용된다"가 아니라 **"그 프로세스가 가리키는 DB에 적용된다"**가 맞다. 그러므로 v2 프로세스는 같은 이미지로 뜨면서 v1 기본 마이그레이션 전량을 **v2 DB에** 적용하고, 이것은 사고가 아니라 **필수**다 — v2는 `world_state`·`command_inbox`·`command_result_outbox`·`general`·`city`·`nation`·`event`·`log_entry` 같은 v1 스키마 위에서 돈다(아래 토폴로지 절). v2 **전용** 표만 0A-c 분리 location에 둔다. 분리가 필요한 이유도 방향이 바뀐다: v2 표를 `db/migration`에 두면 **v1 프로세스가 v1 DB에 v2 표를 만든다** — 부팅이 깨지진 않지만 "production profile의 v2 migration 수 0"(0A-e·0A-f)을 정면으로 위반한다.

   **(4) wire·직렬화 등록점 역추적.** `WireJson`(`common/.../wire/WireJson.kt:11-16`)은 `classDiscriminator = "type"`만 두고 **`serializersModule`에 다형 등록을 하지 않는다** — 서브클래스 등록은 전적으로 `@Serializable` sealed 컴파일러 플러그인 소관이다. 그래서 U9(파일 밖 최상위 서브클래스가 등록되는가)가 **여전히 유효한 UNKNOWN**이다. 반면 **코퍼스 테스트는 v2 variant를 막지 않는다** — `TurnDaemonCommandWireTest`의 `all command type discriminators are covered by the corpus`는 `sealedSubclasses`가 아니라 **테스트 리소스 코퍼스**(`golden/wire/wire_commands_valid.json`, 27종·크기 잠금)를 검사하므로, sealed에 v2 variant를 더해도 코퍼스를 건드리지 않는 한 실패하지 않는다. **⇒ wire 쪽 T2 신설 0.** (v2 variant를 코퍼스에 넣으면 T1 테스트 리소스 수정이 되므로 **넣지 않는다** — v2 왕복 테스트는 별도 신규 테스트로 쓴다.)

   **(5) game-api 등록점 역추적.** 위 R6 절 참조 — `@EntityScan`/`@EnableJpaRepositories` 화이트리스트가 실제 등록 메커니즘이고, `ddl-auto: validate` 때문에 화이트리스트 확장은 v1 부팅을 깬다. ⇒ v2 read는 `JdbcTemplate`, T2 편집 0.

   #### 개정 6차 — **배포 토폴로지를 확정한다.** 개정 5차 T2 표의 축이 자기모순이었다

   5차 채점의 CRITICAL-1은 이 문서가 두 전제를 동시에 주장한다고 지적했다 — `:966`은 "v1/v2 이벤트 분리는 **별도 DB 결정에 전적으로 의존한다**"(⇒ v2 프로세스의 primary = v2 DB), `:1031`·`:1046`은 "`JdbcFlushExecutor`가 **v1** 템플릿이라 v2 원장이 v1 DB에 써진다 / 커맨드는 **v1** `command_inbox`를 탄다"(⇒ primary = v1 DB). 한 프로세스에 DataSource가 하나이므로 둘은 공존할 수 없다는 지적은 **옳다.** 그리고 채점자가 확인한 사실("`app/game-engine/src/main/kotlin/` 전수 grep에 `HikariConfig`/`HikariDataSource`/`DataSource` 빈 정의 0건")도 **재현했다 — 참이다.**

   **그러나 이것은 둘 중 하나를 고르는 문제가 아니었다. 리포에 이미 답이 있었고 찾지 않았을 뿐이다.**

   **증거 1 — 프로세스는 자기 월드를 env로 받고, 그 월드는 정확히 하나다.**
   - `app/game-engine/src/main/resources/application.yml` `opensamguk.world-id: ${OPENSAMGUK_WORLD_ID}` — **기본값 없음**(미설정이면 부팅 불가). `app/game-api/.../application.yml:30`도 같다.
   - `app/game-engine/.../config/WorldIdConfig.kt:11` → `EngineProcessWorld(rawWorldId.toIntOrNull() ?: error("OPENSAMGUK_WORLD_ID must be a positive integer"))`, `EngineProcessWorld.kt:5-6`은 필드가 `val worldId: WorldId` **하나**다. 여러 월드를 도는 구조가 아니다 — **프로세스당 월드 1개**다.

   **증거 2 — DB 접속은 통째로 env 주입이다.** 양 서비스 `application.yml`의 `spring.datasource.url/username/password`가 전부 `${GAME_DATABASE_URL:…}`/`${GAME_DB_USER:…}`/`${GAME_DB_PASSWORD:…}`다. `docker-compose.yml:162-164`(로컬)과 `docker-compose.production.yml:58,96,133`(EC2)이 그 값을 서비스별로 넣는다. **같은 이미지 + 다른 env = 다른 DB를 가리키는 다른 프로세스**가 이미 성립하는 조립이다.

   **증거 3 — 한 DB에 두 월드를 올리는 것은 코드가 금지한다(이것이 결정타다).** `infra/.../seed/ScenarioSeedCoordinator.kt:37-49`가 `world_state`의 id 목록을 읽어 `ids.isEmpty()`(시드) / `ids == listOf(설정 world id)`(skip) / **`else -> error(...)`**(`:46-48`) 세 갈래로만 처리한다. 부팅 경로 `SeedBootstrap.ensureSeeded`(`app/game-engine/.../boot/ScenarioSeedRunner.kt:69-104`)를 통해 세 진입점(`ScenarioSeedRunner.kt:47` · `WorldSnapshotLoader.kt:53` · `EngineEventConfig.kt:40`)이 이것을 부른다. ⇒ **시드 활성 부팅에서 한 DB = 한 월드.** v1 월드와 v2 월드가 같은 DB에 있으면 **양쪽 프로세스가 다 부팅에 실패한다.** **단서(개정 7차, 6차 채점 m-new-3)**: 세 진입점이 공유하는 `ensureSeeded`가 `:70-73`의 `if (!seedEnabled) … return false`로 코디네이터보다 **먼저 반환**하므로, `SCENARIO_SEED_ENABLED=false` 프로세스(프로덕션 compose 기본값 — `docker-compose.production.yml:66`)에서는 이 검사가 돌지 않는다. 그 경우의 담보는 0A DoD (i)의 env 분리 강제이며, 근거 α·β·δ·ε는 무조건 성립하므로 **갈래 A 확정은 유지된다.**

   **증거 4 — Redis 식별자는 이미 world-scoped다.** `common/.../wire/StreamKeys.kt:16-18`이 커맨드/이벤트 스트림 키를 `sammo:{profile}:w{worldId}:turn-daemon:{commands,events}`로, `:26`이 실시간 채널을, `:34`가 per-request 결과 키를 같은 형태로 만든다(`OPENSAM-127` 월드 스코프 트랙의 산출물, 파일 주석 `:5-8`). 두 프로세스 월드가 Redis에서 충돌할 경로도 이미 닫혀 있다.

   **증거 5 — 계획 문서가 같은 것을 요구한다.** `01-backbone-micro.md:79` 0A-e = "**production compose/s1 profile에서 v2 Flyway·catalog·flag 제거**", `:80` 0A-f = "production context v2 0개 architecture test". 즉 v1 운영 스택에는 v2가 **한 조각도 없어야** 한다. ADR-LITE-018(`.ai/decisions.md:182`)도 "두 버전은 … **별도 DB(`opensamguk_v2`)·별도 route/bean/migration으로 분리**하며, 한 코드베이스에서 플래그로 공존시키지 않는다"이고, v2가 상시 운영·v1이 on-demand다.

   > ### 확정 — **갈래 A. v2 월드 = 같은 이미지·다른 env로 뜨는 별도 프로세스이고, 그 프로세스의 primary DataSource가 `opensamguk_v2`다.**
   >
   > | 항목 | v1(오리지널, on-demand) 프로세스 | v2(뉴버전, 상시) 프로세스 |
   > |---|---|---|
   > | 이미지 | 동일 | 동일 |
   > | `GAME_DATABASE_URL` | `…/sammo` | `…/opensamguk_v2` |
   > | `OPENSAMGUK_WORLD_ID` | v1 월드 id | v2 월드 id |
   > | `V2_ENABLED` / profile | off / `-` | on / `v2-sandbox`(0A-b) |
   > | 오토컨피그 `JdbcTemplate`·`NamedParameterJdbcTemplate` | v1 DB | **v2 DB** |
   > | Flyway `classpath:db/migration` | v1 DB에 v1 스키마 | **v2 DB에 v1 스키마**(필수 — v2는 그 위에서 돈다) |
   > | Flyway 0A-c 분리 location | **미적용**(0A-e) | v2 전용 표 |
   > | Redis 키 | `…:w{v1}:…` | `…:w{v2}:…` (`StreamKeys.kt:16-18`) |
   > | `command_inbox` | v1 DB의 것 | **v2 DB의 것**(같은 코드, 다른 DB) |
   >
   > **이 확정이 무효화하는 것 4건.** ① `:1031`의 "v1 템플릿" 논거 ⇒ `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` **T2 복귀**, `TurnRunService.kt` **T2 삭제**. ② 두 번째 Hikari 풀 ⇒ **불필요**(오토컨피그 DataSource가 이미 v2 DB). ③ §11 **U11**(교차 DB 원자성) ⇒ **철회**(단일 DB·단일 트랜잭션). ④ `:1046`의 "v1 `command_inbox`" ⇒ **"v2 프로세스 자기 DB의 `command_inbox`"**로 정정(코드 경로는 동일, DB만 다르다).
   >
   > **이 확정이 되살리는 것 0건.** 개정 5차가 `WorldSnapshotLoader.kt`·`InMemoryTurnWorld.kt`·`TruncateContract.kt` 세 행을 내린 근거는 DB 토폴로지와 무관한 **소스 텍스트 가드**와 **프로덕션 소비자 0**이므로 그대로 유효하다. 5차 전환의 절반(읽기 경로)은 온전히 살아남는다.

   **남는 DoD 강제 사항(확정했으므로 UNKNOWN이 아니다).** 위 표는 아직 compose 파일로 존재하지 않는다 — 오늘 리포에는 game-engine 서비스가 **한 개**뿐이다(`docker-compose.yml:155`, `docker-compose.production.yml:52`). 그러므로 **0A 티켓(`OPENSAM-35`)의 DoD에 다음을 명시한다**: (i) v2 스택은 별도 compose 서비스(또는 별도 스택 파일)로 뜨고 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE`·**`SCENARIO_CODE`·`SCENARIO_DIR`**(개정 7차 추가, 아래 사유)를 v1과 다른 값으로 받고 **양 스택 모두 `SCENARIO_SEED_ENABLED=true`로 뜬다**, (ii) v2 전용 Flyway location은 `SPRING_FLYWAY_LOCATIONS` **환경변수 오버라이드**로만 더한다(`application.yml` 무수정 = 게이트 ⑤ 유지), (iii) 0A-f의 "production context v2 0개" 아키텍처 테스트가 v1 프로세스에서 v2 빈 수 0을 실측한다. **(ii)의 Spring Boot 환경변수 오버라이드는 표준 동작이지만 이 리포에서 실측하지 않았다 — 0A-c 착수 첫 작업으로 확인한다(§11 U12).**

   > **`SCENARIO_CODE`·`SCENARIO_DIR`를 (i)에 넣는 이유 — 물려받으면 조용히 실패한다(개정 7차 신설, 6차 채점 m-new-1).** compose가 두 변수에 **기본값을 준다**: `docker-compose.yml:172` `SCENARIO_CODE: ${SCENARIO_CODE:-scenario_1010}` · `:173` `SCENARIO_DIR: ${SCENARIO_DIR:-/data/scenarios}`(프로덕션은 `docker-compose.production.yml:67`·`:68`, 동일 기본값). 그리고 그 기본 시나리오는 **`ignoreDefaultEvents`가 거짓이다** — 실측: 클래스패스 정본 `infra/src/main/resources/scenario/scenario_1010.json`에는 `ignoreDefaultEvents` **키 자체가 없어** `ScenarioJson.kt:69`(`boolOf(root["ignoreDefaultEvents"], false)`)·`:299`(필드 기본값 `false`)로 `false`가 되고, 마운트본 `data/extracted/scenario/scenario_1010.json`은 **명시적 `false`**다. ⇒ v2 스택이 이 기본값을 물려받으면 `ScenarioImporter.insertEvents`(`:806`)의 `defaults` 분기가 살아나 `EventStore.DEFAULT_EVENTS` **12행이 그대로 적재되고 v2 leaf 행은 0개**가 된다. **부팅·시드·헬스체크는 전부 성공한다 — 도시 원장 수입만 한 달도 돌지 않는다.** §7.1-2가 확정한 R2 치환 전제(`ignoreDefaultEvents: true` + 12행 전사)가 통째로 무력화되는데 아무 예외도 나지 않는 **조용한 실패**이므로, 환경변수 열거만으로는 부족하다.
   >
   > **그래서 R2 DoD에 검증 항목을 하나 못 박는다** — v2 월드를 시드한 직후 `SELECT action FROM event WHERE world_id = <v2>`를 읽어 **(a)** `V2ProcessCityIncome`이 1·7월 행에 실재하고 **(b)** `ProcessIncome`이 **0건**이며 **(c)** 행 수가 v2 시나리오 JSON의 `events` 길이 + `deferredGeneralRows` 산출 수와 일치함을 확인한다. (a)만 보면 이중 수입(§7.1-2 표의 `false` 분기)을 놓치므로 (b)가 본체다. 이 세 줄이 "부팅은 되는데 수입이 안 도는" 양식을 시드 시점에 잡는 유일한 장치다.

   **역추적 결과 요약 — R1~R6 × 메커니즘**

   | 티켓 | 부팅 등록·조립점 | 게이트가 실제로 걸리는 지점 | DataSource·트랜잭션·마이그레이션 | wire·직렬화 | 아키텍처 테스트 충돌 |
   |---|---|---|---|---|---|
   | R1 | 신규 `@Configuration`(`engine.v2`) — 컴포넌트 스캔(`GameEngineApplication.kt:8`), **편집 0**. 소비 조립점 = `DaemonLoopConfig.kt:269`(→`WorldEventContextFactory.create`) | 그 `@Bean`의 `@ConditionalOnProperty`(0A-b, `01-backbone-micro.md:76`). off ⇒ 빈 없음 ⇒ SELECT 없음 | **개정 6차** — 별도 풀 없음. 프로세스의 오토컨피그 템플릿이 곧 v2 DB(토폴로지 절). flush는 `ChangeRecorder`→`DatabaseHooks`→`JdbcFlushExecutor` v2 step **단일 트랜잭션**. 마이그레이션은 0A-c 분리 location(`:77`) — `db/migration`에 두면 v1 프로세스가 v1 DB에 v2 표를 만들어 0A-e/0A-f 위반 | — | **`WorldSnapshotLoader`·`InMemoryTurnWorld` 경유 불가**(`HotColdWorldCatalogGuardTest`), 신규 파일은 `engine.v2`에만 |
   | R2 | `EngineEventConfig.kt:79-81` 빈이 팩토리를 만드는 **유일 프로덕션 지점** | 이름 비충돌 + 0A-b | v2 원장 접근은 R1 store 경유(자체 SQL 없음) | — | `WorldActionContext.kt`는 `engine/world` = 스캔 대상 ⇒ v2 타입 이름이 `Repository`/`Reader`로 끝나면 안 됨 |
   | R3 | R2와 동일(소비자) | R2와 동일 | 동일 | — | 동일 |
   | R4·R5 | `CommandWireMapper.kt:43,140-149` + `TurnDaemonCommandDispatcher.kt:326,397`. 컨트롤러·레지스트리는 신규 파일 + 컴포넌트 스캔 | 인테이크 코드 집합 확대(`:147`)는 v1 코드에 도달 불가 | **개정 6차 정정** — 커맨드는 durable 경로(`command_inbox`/`command_result_outbox`)를 그대로 타되 그것은 **v2 프로세스 자기 DB의** 표다(코드 동일, DB만 다름). 원장 쓰기도 같은 트랜잭션 | `WireJson.kt:11-16` 다형 등록 없음 ⇒ **U9 유효**. 코퍼스 테스트는 무영향 | 핸들러를 `engine/intake`가 아니라 `engine.v2`에 둔다 |
   | R6 | `GameApiApplication.kt:8` 컴포넌트 스캔(컨트롤러·`@Configuration`) | v2 `@Configuration`의 `@ConditionalOnProperty` | **`:9-10` JPA 화이트리스트가 진짜 등록점** ⇒ v2 read는 `JdbcTemplate`(`ddl-auto: validate` 때문) | — | 없음 |

   **T2 전량 (편집 10 + 신규 마이그레이션 1 = 11행 · 개정 6차) — 티켓 본문 선언은 이 표를 그대로 옮긴다.**

   | # | 파일 | 티켓 | 편집 내용 | v1-inert 근거 | **가드 영향 (개정 6차 신설 — 5차 채점 M1·M2)** |
   |---|---|---|---|---|---|
   | 1 | `app/game-engine/.../turn/DirtyState.kt` | R1 | v2 원장 델타 행 모델 | 새 컬렉션이 비면 소비자가 미진입(P6 betting 선례) | **스캔 대상**(`engine/turn`) **+ writePath.** 새 타입은 순수 data class ⇒ `*Repository`/`*Reader` 수신자 0·jdbc 수신자 0 ⇒ 세 `assertEquals` 무영향. JPA 내부이름 미유입 ⇒ 상수풀 clean |
   | 2 | `app/game-engine/.../turn/ChangeRecorder.kt` | R1 | 필드·`isDirty`·record·accessor·clear 5지점 | 〃 | 〃. 추가 제약: 새 메서드 이름이 `find|read|claim|count|sum|target|mark|load`로 시작해도 이 파일이 `*Repository`/`*Reader` **확장 함수를 선언하지 않는 한** implicit 규칙(`GuardTest:334-340`)은 발화하지 않는다 — 선언하지 않는다 |
   | 3 | `app/game-engine/.../flush/DatabaseHooks.kt` | R1 **(개정 6차 복귀)** | `toFlushPayload`(`TurnRunService.kt:527`이 부르는 것)에 v2 컬렉션 매핑 1줄 | 빈 컬렉션이 payload에 실릴 뿐이고 v2 step이 미진입 ⇒ SQL 0 (1·2행과 **동일** 논거) | `engine/flush`는 8개 스캔 디렉터리에 **없다** ⇒ 카탈로그 세 `assertEquals` 무관. **writePath에는 있다** ⇒ 상수풀 규칙만 적용(JPA 타입 미참조) |
   | 4 | `infra/.../persistence/JdbcFlushExecutor.kt` | R1 **(개정 6차 복귀)** | `FlushPayload`(`:2287`) 후행 기본값 필드 1개 + v2 step 1개 + row 매핑 | 〃 | 8개 밖이지만 `HotColdCatalog.runtimeDirectSqlBoundaries`(`:156-165`)에 **이미 등재**돼 `directSqlSourceFiles()`에 포함된다. 다만 `assertEquals` 대상은 **파일 경로 집합**(`GuardTest:204`가 `substringBeforeLast(":")`)이므로 등재된 파일에 SQL 호출을 더해도 집합 불변 ⇒ inert. `runtimeCallKeys`/`runtimeCallCounts`는 `runtimeSourceFiles()`만 보므로(`:158`) 이 파일에 적용되지 않는다. **제약 1개**: `S5 T2 boot snapshot SQL uses bounded projections`(`GuardTest:120-127`)가 `privateMethodBody(flushExecutor, "historyRows")`를 뽑아 SQL 문자열 6개를 검사한다 ⇒ **`historyRows`의 이름·본문을 건드리지 않는다.** `InfraNoEntityManagerTest`가 JPA 금지를 담당 |
   | 5 | `app/game-engine/.../config/DaemonLoopConfig.kt` | R1·R2 | `ObjectProvider<V2CityLedgerStore>` 주입 + `WorldEventContextFactory.create(`:269`)` 인자 1개 | `getIfAvailable()` = `null`. v1 인자 순서·값 불변 | 가드 테스트가 **명시 추가한** 유일한 config 파일(`GuardTest:41-44`). 세 제약 전부 걸린다 — (i) 타입명이 `Repository`/`Reader`로 끝나면 `runtimeCallKeys`/`runtimeCallCounts` 파손, (ii) `JdbcTemplate`/`NamedParameterJdbcTemplate`/`Connection`/`DataSource`로 선언하면 `runtimeDirectSqlBoundarySources` 파손, (iii) 이 파일에는 이미 `reservedTurnRepository.readReserved`×2·`readReservedNationTurn`·`messageRepository.findMaxId`·`auctionRepository.findAll`의 **정확한 호출 수**가 카탈로그에 박혀 있으므로(`HotColdCatalog.kt:170-190`) 기존 수신자 호출을 **한 번도 늘리지 않는다.** 추가 발견: `:104-108`의 `jdbc` 파라미터는 오늘 **메서드 호출 없이 생성자에 전달만** 되기에 이 파일이 `runtimeDirectSqlBoundarySources`에 없는 것이다 ⇒ **jdbc 수신자에 메서드를 부르는 순간 파손**한다 |
   | 6 | `app/game-engine/.../world/WorldEventContextFactory.kt` | R2 | `create(...)`에 기본값 `null` 파라미터 1개 + `WorldActionContext(...)`로 전달 | 기본값이라 기존 호출부 무편집, `null`이면 v2 메서드가 실행 불가 | **스캔 대상**(`engine/world`). 5행의 (i)(ii) 동일 적용. 이 파일에는 기존 Repository/Reader 수신자가 없으므로 (iii)은 무관 |
   | 7 | `app/game-engine/.../world/WorldActionContext.kt` | R2 (R3 소비) | **후행** nullable 기본 파라미터 1개 + v2 컨텍스트 인터페이스 구현 + 메서드 | 호출자 0(같은 DB에 v2 `event` 행이 존재할 수 없다 — 토폴로지 절 증거 3) + 기존 `as?` 결과 불변 + 생성 4지점(`:920` 위치인자 포함) 무편집 | **스캔 대상**(`engine/world`). (i)(ii) 적용. **(iii)이 가장 빡빡한 행이다** — 이 파일은 이미 `auctionRepository`·`auctionBidRepository`·`archiveHistoryReader`·`statisticSnapshotReader` 네 수신자(`:111-114`)로 `runtimeCallCounts`에 기여 중이므로 v2 메서드는 **그 넷 중 무엇도 부르지 않는다**(v2 원장만 읽는다) |
   | 8 | `app/game-engine/.../config/EngineEventConfig.kt` | R2 | `eventActionFactory` 빈(`:79-81`) 등록 체인 1줄 | 이름 비충돌 + `LinkedHashMap` 키 추가는 기존 조회 불변(`EventAction.kt:60-64`, `:70-74`) | **가드 영향 0.** `engine/config` 중 스캔 집합에 명시 추가된 것은 `DaemonLoopConfig.kt` **하나뿐**이고, writePath 4개에도 없다. (이 파일이 `:41`·`:46`에서 `jdbc.query`를 부르면서도 `runtimeDirectSqlBoundarySources`에 없는 이유가 그것이다) |
   | 9 | `app/game-api/.../reserve/CommandWireMapper.kt` | R4·R5 | `intakeCodes`(`:43`) + `toCommand` `when`(`:140-149`) | 새 코드는 v1 코드의 분기에 도달하지 않는다(`:147 if (code !in intakeCodes) return null`은 **집합 확대만**) | **가드 영향 0.** 스캔 집합·writePath 모두 `app/game-engine/` 전용 경로다 |
   | 10 | `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt` | R4·R5 | `dispatch` `when`(`:326`) 분기 추가 | v1 variant의 분기 순서·본문 불변, 신규 분기는 v2 variant에만 매치 | **스캔 대상**(`engine/run`) **+ writePath.** (i)(ii) 적용 — v2 핸들러 타입명은 `Repository`/`Reader` 금지. (iii) 생성자가 repository seam을 받으므로(`:75-115`) 이미 `runtimeCallCounts` 기여 중 ⇒ **v2 분기에서 기존 seam을 부르지 않는다.** 상수풀에 JPA 내부이름 미유입 |
   | 11 | v2 Flyway location(**0A-c**, `01-backbone-micro.md:77`)의 마이그레이션 1개 | R1 | 신규 파일. `infra/src/main/resources/db/migration/`·`infra/src/main/kotlin/db/migration/` 어느 쪽에도 두지 않는다 | 그 자리에 두면 **v1 프로세스가 v1 DB에 v2 표를 만든다**(양쪽 `application.yml:14`) ⇒ 0A-e/0A-f 위반 | 가드 영향 0(신규 파일, 스캔 대상 밖) |

   **M2 — `DaemonWriteGuard`와 `DaemonNoEntityManagerTest` 판정(개정 6차 신설).** T2 편집 행 중 `DaemonWriteGuard.writePathPackages`(`app/game-engine/.../flush/DaemonWriteGuard.kt:29-34` = `opensamguk/engine/{flush,turn,run,nationbulk}`) 안에 있는 것은 **1·2·3·10행 네 개**다. `DaemonNoEntityManagerTest`(`app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt:70-79`)는 그 패키지의 **컴파일된 클래스 상수풀**을 바이트 부분문자열로 훑어 `DaemonWriteGuard.forbiddenInternalNames`(`jakarta/persistence/EntityManager`·`EntityManagerFactory`·`org/springframework/data/jpa/repository/JpaRepository` …)가 나오면 실패한다.

   > **v2 추가가 걸리지 않는 이유는 한 줄이다 — v2 원장 경로에 JPA 타입이 한 개도 없기 때문이다.** 네 행이 새로 참조하는 타입은 `opensamguk/engine/v2/…`(순수 Kotlin + JDBC)와 `opensamguk/infra/persistence/FlushPayload`뿐이고 어느 것도 금지 목록에 없다. 이 판정은 **v2 store·핸들러가 JPA를 쓰지 않는다**는 전제 위에 서므로 R1·R4·R5 DoD에 "v2 엔진·인테이크 코드는 JDBC-only"를 못 박는다.
   >
   > **개정 5차 설계였다면 이 판정이 성립하지 않았다.** 5차의 v2 싱크는 `engine.v2`에 있어 writePath **밖**이었다 — 즉 v2 쓰기 경로 전체가 "데몬 쓰기는 JDBC-only"라는 CLAUDE.md 하드 룰의 **테스트 사각지대**였다. 개정 6차가 v2 쓰기를 v1 flush 기계로 되돌리면서 그 사각지대가 사라졌고, `writePathPackages`에 `engine/v2`를 더하는 **가드 확장 편집(=T2 +1행)도 불필요**해졌다.

   **개정 4차 표에서 내려간 행과 그 이유 (개정 6차 갱신 — 5행 중 2행이 되돌아왔다).**

   | 내려간 행 | 이유 | 개정 6차 판정 |
   |---|---|---|
   | `flush/DatabaseHooks.kt` | (5차) v2 델타를 `FlushPayload`에 태우지 않기로 했다 ⇒ 매핑 지점이 생기지 않는다 | **복귀(T2 3행).** 전제였던 "`FlushPayload`는 v1 DB의 계약"이 거짓이었다 — v2 프로세스에서 그 payload는 v2 DB로 간다 |
   | `infra/.../JdbcFlushExecutor.kt` | (5차) `DaemonLoopConfig.kt:108`이 **v1** 템플릿으로 만든다 ⇒ v2 step은 방어선 1 위반 | **복귀(T2 4행).** 그 템플릿은 한정자 없는 **오토컨피그** 템플릿이고, v2 프로세스에서는 v2 DB를 가리킨다. 5차의 이 한 문장이 CRITICAL-1 자기모순의 진원지였다 |
   | `flush/TruncateContract.kt` | **프로덕션 소비자가 0이다**(리포 전수 grep — 참조는 `TruncateContractTest.kt`와 주석뿐). 4차가 붙인 이유("빈 컬렉션이면 step 미진입")는 이 파일에 적용되지 않는다(4차 채점 MINOR-C — **지적이 옳다**). 게다가 `every baseline CREATE TABLE is classified` 테스트는 `baseline − classified = ∅`만 보고 역방향을 보지 않으므로 v2 항목을 더할 필요도 이유도 없다 | **유지(내려간 채로).** 근거가 DB 토폴로지와 무관하다 |
   | `turn/InMemoryTurnWorld.kt` | v2 원장이 `WorldSnapshot`을 경유하지 않는다(아래 행 때문) | **유지.** 아래 행과 함께 성립 |
   | `boot/WorldSnapshotLoader.kt` | `HotColdWorldCatalogGuardTest`가 이 파일의 메서드 이름 집합과 SQL 위치를 T1 카탈로그와 `assertEquals`로 묶어 놓았다 ⇒ **v2 SELECT를 넣을 방법이 없다** | **유지.** 소스 텍스트 가드는 어느 DB를 쓰든 그대로다. 5차 전환의 읽기 절반은 온전히 살아남는다 |
   | `run/TurnRunService.kt` **(개정 6차 신규 삭제)** | — | **내린다.** v2 싱크가 사라졌으므로 이 파일을 열 이유가 없다. `:527`(`toFlushPayload`)·`:404`(`flushExecutor.flush`)는 **이미 있는 호출**이고 개정 6차는 그 안쪽만 넓힌다 |

   **UNK-C 판정 — v2 원장의 재시작 재수화. `turn/RehydrateService.kt`는 열지 않는다(개정 6차 신설).** 5차 채점이 UNKNOWN으로 남긴 항목이다. 그 파일은 `engine/turn`(스캔 대상) + `NamedParameterJdbcTemplate` 보유 + `HotColdCatalog.runtimeDirectSqlBoundaries`(`:147-155`) 등재라 열면 비싼 파일이 맞다.

   > **판정: T2 추가 없음.** v2 원장의 재적재는 `engine.v2` store가 **자기 부팅 시점에 자기 표를 직접 읽어** 수행한다 — v1 재수화(`select_pool`·`game_kv`·`ng_auction`·`ng_auction_bid`·`ng_betting`·`message`)와 겹치는 표가 하나도 없고, v2 store는 `WorldSnapshot`·`InMemoryTurnWorld`를 경유하지 않으므로 `RehydrateService`가 복원해야 할 v2 상태 자체가 없다. store를 lazy 적재로 두면(U10의 완화안과 동일) 부팅 순서 의존도 사라진다. **R1 DoD에 "v2 원장 재적재는 `engine.v2` 안에서 끝난다 — `RehydrateService.kt` 무편집"을 명시한다.** 다만 `OPENSAM-149`(restart-rehydrate lossless 게이트)는 ADR-LITE-019대로 **v2 착수 전 선행**이므로 v2는 고쳐진 경로 위에서 포크된다.

   **UNK-D 판정 — Redis 스트림·컨슈머 그룹·SSE·헬스체크. `engine/redis/**` 무편집(개정 6차 신설).** 5차 채점이 "누락 없어 보이나 단정하지 않는다"로 남긴 항목이며, `engine/redis`는 스캔 대상 8개에 포함되므로 판정이 필요하다.

   > **판정: T2 추가 없음. 근거는 키가 이미 world-scoped라는 것이다.** `common/.../wire/StreamKeys.kt:16-18`이 커맨드/이벤트 스트림 키를 `sammo:{profile}:w{worldId}:turn-daemon:{commands,events}`로, `:23-27`이 실시간 채널을 `sammo:{profile}:w{worldId}:realtime:events`로, `:34`가 결과 키를 `sammo:{profile}:w{worldId}:turn-daemon:result:{requestId}`로 만든다(파일 주석 `:5-8`이 `OPENSAM-127` 월드 스코프 산출물임을 밝힌다). `RedisCommandStream.kt:37`이 `TurnDaemonStreamKeys.of(profileName, worldId)`로 그 키를 받고, `:81-126`의 XREAD/XACK/XCLAIM·컨슈머 그룹이 전부 그 키 위에서만 돈다. ⇒ **v2 프로세스는 다른 `OPENSAMGUK_WORLD_ID`를 갖는 것만으로 완전히 분리된 스트림·그룹·채널을 얻는다.** 커맨드 payload는 `readEnvelopes`(`:81`)가 `TurnDaemonCommandEnvelope`를 **variant 열거 없이** 통째로 역직렬화하므로(`:165-167`) v2 variant 추가에도 편집 지점이 생기지 않는다. `RealtimePublisher.kt`는 `publishCommandResultPayload`(`:25`)·`publishTurnCompleted`(`:33`) 두 함수뿐이고 둘 다 이벤트 종류를 열거하지 않는다.

   **신규 파일(편집 아님) — 단, 위치 제약이 붙는다.** v2 leaf 2개 + v2 컨텍스트 인터페이스 2개 + `V2WorldActions` + v2 커맨드 본체 2개 + `common/.../wire/` v2 variant 파일 1개 + v2 코드 레지스트리 1개는 종전과 같다. **엔진 측 신규 클래스**(v2 원장 store, v2 커맨드 핸들러 2개, v2 `@Configuration`)는 전부 `app/game-engine/.../v2/` 한 패키지에 둔다 — `HotColdCatalog.runtimeSourceDirectories`(8개)와 `DaemonWriteGuard.writePathPackages`(4개) 어디에도 속하지 않아 v1 가드 테스트를 물리적으로 건드릴 수 없기 때문이다. **개정 6차 두 가지 정정**: (i) "v2 flush 싱크"는 목록에서 **빠진다** — v2 델타는 v1 flush 기계를 그대로 타므로 별도 싱크 클래스가 없다(§7.1-(3)). (ii) v2 `@Configuration`은 이제 **DataSource·풀을 만들지 않는다** — 하는 일은 `@ConditionalOnProperty(V2_ENABLED)`(0A-b) 뒤에서 store·핸들러 빈을 등록하고 오토컨피그 `NamedParameterJdbcTemplate`을 주입받는 것뿐이다. **game-api 측**(v2 read 컨트롤러, v2 인테이크 컨트롤러, v2 `@Configuration`)은 `app/game-api/.../v2/`에 두고 **JPA를 쓰지 않는다**(`ddl-auto: validate`).

   **이 열거에 없는 신규 산출물 하나 — v2 시나리오 JSON(개정 7차 신설, 6차 채점 m-new-4).** §9.2 R2가 이 파일을 전제하는데 위 목록에도 T1·T2 표에도 없다. **의도된 공백이고 사유는 셋이다.** ① **계층 정의상 밖이다** — T1(수정·삭제 금지)과 T2(수정 허용 + v1-inert 논증 의무)는 둘 다 *기존 파일의 편집*을 분류하는 축이고, 이것은 신규 파일이다. 게이트 ②③⑤가 전부 `--diff-filter=MD`이므로 신규 파일은 구조적으로 걸리지 않는다(게이트 ⑤ 주석이 v2 Flyway location에 대해 이미 같은 말을 한다). ② **v1 데이터를 물리적으로 건드릴 수 없다** — 시나리오 JSON은 `readScenarioJson()`(`ScenarioSeedRunner.kt:121-127`)이 `SCENARIO_CODE`로 **이름을 지정해** 한 파일만 읽고, 그 결과는 v2 프로세스가 자기 DB를 시드할 때만 쓰인다. v1 시나리오 파일은 열리지도 않는다. ③ **리포 규약과 충돌하지 않는다** — gitignore가 덮는 시나리오 JSON은 `data/scenarios/scenario_*.json` · `**/scenario_*.local.json` · `**/rtk14-scenarios.local/`(`.gitignore:93-95`), 즉 **마운트 디렉터리(`SCENARIO_DIR`)와 RTK14 생성물**이다. 클래스패스 정본 `infra/src/main/resources/scenario/`는 **추적된다**(`scenario_910.json`·`scenario_1010.json` 등 실측).

   > **따라서 R2 DoD에 위치를 못 박는다 — v2 시나리오 JSON은 `infra/src/main/resources/scenario/scenario_<v2code>.json`(추적 파일)로 커밋한다.** `readScenarioJson()`은 `SCENARIO_DIR`의 동명 파일을 클래스패스보다 **우선**하므로(`:122-125`) 마운트로만 배달하면 그 파일이 gitignore 영역에 남아 **리뷰·이력·재현이 전부 사라진다**(전사한 `DEFAULT_EVENTS` 12행이 코드리뷰를 통과하지 않는다는 뜻이다). 5스탯 RTK14 생성물이 gitignored인 것은 원본 IP 때문이고 이 파일에는 그 사유가 없다.

   **계층에 속하지 않는 것 — `web/**`.** R6과 R4·R5의 제출 화면은 `web/game`을 건드리는데 이 경로는 **T1도 T2도 아니고 §7.2의 게이트 ②③ 어느 쪽에도 잡히지 않는다.** 의도된 공백이다 — `web/**`에는 패러티 표면(RNG·라운딩·로그 바이트)이 없고 JVM 컴파일 결합도 없어 v1 골든을 물리적으로 움직일 수 없기 때문이다. 대신 v1 화면 회귀는 별개 문제로 남으므로, v2 프론트는 **기존 페이지를 고치지 않고 새 라우트만 추가**한다는 조건을 R4~R6 DoD에 적는다.

   - **테스트 범위:** `src/test/kotlin` 아래 **신규 테스트 추가는 허용**한다 — 방어선 7·8이 바로 그 테스트이고, 증거물을 금지하면 증명할 수단이 사라진다. **기존 v1 테스트의 수정은 T1에 속해 금지**한다(테스트를 고쳐 통과시키는 것은 골든을 고치는 것과 같은 위반이다).
   - `City`·`General` 데이터 클래스에 v2 필드를 **추가하지 않는다**(둘 다 T1). v2 도시 자원은 별도 표·별도 타입(`V2CityLedger`)이다.
3. **RNG draw 0.** §2.4·§4.3에서 밝힌 대로 v2 월간 leaf와 관계 모듈은 `RandUtil`을 주입받지 않는다. draw를 뽑을 능력이 스코프에 없으므로 v1 draw 순서·횟수에 영향을 줄 물리적 경로가 존재하지 않는다. 능력치 보정이 붙어도 이 방어선은 흔들리지 않는다 — 보정은 가산이지 추첨이 아니고, `ActionPipeline`의 fold 자체가 "NO RNG inside the fold"를 계약으로 갖고 있다. 오픈 후 도적 이벤트를 신설할 때는 `RaiseDisaster`가 이미 증명한 self-seeded DRBG 패턴(`RaiseDisaster.kt:13`, `:215`)을 따른다.
4. **로그 채널 분리.** v2 전용 로그 채널을 쓴다. `common/log/*`의 `Josa`·`ConvertLog`·토큰 정의는 편집하지 않는다.
5. **골든 무수정.** `logic/src/test/resources/golden/**` 어느 파일도 바뀌지 않는다.
6. **아키텍처 테스트 2종.** (a) v1 패키지가 v2 패키지를 import하지 않는다. **(b) 개정 — `GeneralActionModuleFactory`를 포함한 v1 조립 코드가 관계 패키지를 import하지 않는다**(원안의 "stats 패키지 전체 금지"를 대체). 관계 모듈 주입은 v2 전용 `V2ActionModuleAssembler`에서만 일어난다.
7. **(신설) source 목록 스냅샷.** `GeneralActionModuleFactory.build(...)` 결과 리스트의 클래스명 시퀀스를 문자열로 고정한다. v1 조립 결과에 관계 모듈이 나타나거나 fold 순서가 흔들리면 실패한다. 채점기가 요구한 "v1의 source 목록이 한 바이트도 바뀌지 않음"에 정확히 대응하는 증거물이다.
8. **(신설) 엣지-0 등가 테스트.** 결속이 하나도 없는 v2 월드에서 `getStatValue`가 모든 스탯·모든 훅에 대해 v1과 동일한 값을 낸다. 이것이 유일하게 **결과를 직접 재는** 방어선이며, 나머지 일곱이 전부 구조 논증인 데 반해 이것만 측정이다.

### 7.2 증명 절차 — 모든 티켓의 공통 DoD

**개정 2차 — 원안 게이트는 두 가지로 고장나 있었다.** ① `logic/src/main/kotlin/opensamguk/logic/` 아래 패키지는 **24개**인데 6개만 열거해 `actions/`·`ai/`·`constraints/`·`event/`·`tick/`·`items/`·`log/`·`util/` 등 **18개가 게이트 밖**이었다 — 즉 `ActionPipeline`은 감시하면서 `logic/event/WorldActions.kt`(월간 leaf 등록 체인)와 `logic/constraints/Presets.kt`(precheck 판정)는 감시하지 않았다. ② `git diff --stat`은 **신규 파일도 출력하므로** "출력이 비어 있어야 한다"는 조건이 v2 새 파일 하나만 있어도 실패한다. 두 결함을 함께 고친다.

**개정 (2026-08-17, OPENSAM-188) — 아래 코드블록은 결함 3건을 닫은 판이다.** ①
`'app/*/src/main/kotlin/'`처럼 **와일드카드 + 트레일링 슬래시** pathspec은 git wildmatch에서
**항상 빈 출력**이라(git 2.50.1 실측) 게이트 ③의 `app/` 절반이 아무것도 검사하지 않았다 —
"빈 출력 = PASS"가 공허하게 참이었다. 와일드카드가 든 pathspec은 **반드시 `:(glob)` 접두 +
`/**` 접미**로 쓴다. ② 게이트 ⑤가 `infra/src/main/resources/` 전체를 얼려 v2 소유 문서
(`db/migration_v2/README.md`·`content/v2/README.md`)가 영구 갱신 불가였다 — `README.md`만
제외한다(어떤 로더도 읽지 않는다: Flyway는 `V*.sql`, `V2ContentCatalog`는 `*.json`만 스캔).
③ 기준선이 `origin/main`이면 분기 후 머지된 타 브랜치가 섞여 **거짓 위반**이 뜬다 —
기준선은 **merge-base 고정**이다.

**실행은 문서 복붙이 아니라 `scripts/agent/v2-isolation-gate.sh`로 한다.** 그 스크립트가
merge-base 계산과 pathspec을 고정해 사람이 틀릴 여지를 없앤다(fail-closed, exit 1). 아래
블록은 그 스크립트가 실제로 실행하는 명령의 문서판이다.

```bash
# ① v1 게이트 green (출력 tail + test XML로 확인, exit code 아님)
tools/parity/gate.sh backend

# 기준선 — origin/main 직접 사용 금지 (분기 후 전진분이 거짓 위반을 만든다)
MB=$(git merge-base HEAD origin/main)

# ② T1 — 패러티 코어 수정·삭제 0건 (신규 파일 추가는 허용 → --diff-filter=MD)
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)logic/src/main/kotlin/**' \
  ':(glob)common/src/main/kotlin/**' \
  ':(glob)logic/src/test/resources/golden/**' \
  ':(glob)logic/src/test/kotlin/**' ':(glob)common/src/test/kotlin/**' \
  ':(glob)infra/src/test/kotlin/**' ':(glob)app/*/src/test/kotlin/**'
# → 출력이 비어 있어야 한다. 한 줄이라도 나오면 설계 위반

# ③ T2 — 경계 수정 목록이 티켓 본문 선언과 일치하는지
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/kotlin/**' ':(glob)infra/src/main/kotlin/**' \
  ':(glob)infra/src/main/resources/db/migration/**'
# → 티켓 본문이 사전 명시한 파일 집합과 정확히 일치해야 한다(초과 = 위반)
#   각 파일의 diff는 티켓 본문에 전량 인용하고, v1 inert 근거를 한 줄씩 붙인다

# ④ 관계 보정 티켓(R8, 오픈 후) 전용
#    - source 목록 스냅샷 테스트 green (기존 ModuleFactoryOrderTest.kt 확장)
#    - 엣지-0 등가 테스트 green
#    - v1 조립 코드의 관계 패키지 import 0 (아키텍처 테스트)
```

②가 이 설계안의 실질적 게이트다. **패키지를 열거하지 않고 `logic/src/main/kotlin/` 전체를 잠그는 것**이 원안 대비 핵심 변경이며, 이렇게 하면 "어느 패키지를 빠뜨렸나"라는 질문 자체가 사라진다. ③은 T2가 슬금슬금 넓어지는 것을 막는 유일한 장치다 — 게이트가 "0건"을 요구할 수 없는 영역이므로 대신 **선언과의 일치**를 요구한다. **비교 대상 정본은 §7.1-2 개정 6차의 T2 11행 표**(편집 10 + 마이그레이션 1)이며, 티켓 본문은 그 표에서 자기 행만 옮겨 적는다 — **"가드 영향" 열을 포함해서** 옮긴다(그 열이 티켓 착수 시 확인해야 할 아키텍처 테스트 제약을 담고 있다). ④는 R8에만 붙는 추가 DoD다(오픈 후).

**②③이 덮지 못하는 경로를 두 개 밝힌다(개정 5차 — 하나는 신설).**
- **`web/**`** — 의도된 공백이고 사유는 §7.1-2 말미에 적었다(패러티 표면·JVM 결합 부재). 대신 "v2 프론트는 기존 페이지를 고치지 않고 새 라우트만 추가한다"를 R4~R6 DoD 문장으로 대신한다.
- **`app/*/src/main/resources/**` (개정 5차 신설)** — ②는 `**/src/main/kotlin/`만, ③은 `app/*/src/main/kotlin/`·`infra/src/main/kotlin/`·`infra/.../db/migration/`만 본다. 그래서 **`application.yml` 수정은 두 게이트 어디에도 걸리지 않는다.** 이 공백은 실재하는 위험이다 — `spring.flyway.locations`·`spring.jpa.hibernate.ddl-auto`·`spring.datasource.*`가 전부 거기 있고, 그 한 줄이 v1 부팅을 깰 수 있다(§7.1-2 R6·(3) 참조). 그래서 이 설계안은 **v2가 `app/*/src/main/resources/**`를 한 글자도 고치지 않는다**를 설계 제약으로 선언하고(v2 프로퍼티는 신규 `@Configuration`의 `@Value` 기본값으로만 읽는다), 게이트에 다음 한 줄을 더한다.

```bash
# ⑤ 설정 리소스 무수정 (개정 5차 신설 — ②③의 사각지대 / 개정 2026-08-17 OPENSAM-188)
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/resources/**' ':(glob)infra/src/main/resources/**' \
  ':(glob,exclude)app/*/src/main/resources/**/README.md' \
  ':(glob,exclude)infra/src/main/resources/**/README.md'
# → 출력이 비어 있어야 한다. v2 Flyway location은 신규 디렉터리이므로 --diff-filter=MD에 걸리지 않는다
# → README.md 제외 사유: Flyway는 V*.sql, V2ContentCatalog는 *.json만 스캔하므로 문서는 v1 런타임을
#   바꿀 수 없다. 제외하지 않으면 v2 소유 문서가 영구 갱신 불가가 된다(OPENSAM-188 결함 ②).
#   yml·json·sql·map·scenario는 전부 동결 유지 — 좁히기가 원래 막던 대상을 하나도 놓치지 않음을
#   mutation 10종으로 실증했다: docs/superpowers/reviews/2026-08-17-opensam-188-gate-defects-review.md
```

`traits/`가 원안에서 특별 취급된 이유(관계 보정을 `OfficerLevelModule`에 한 줄 얹고 싶어지는 유혹)는 여전히 유효하고, 이제 `logic/` 전체 잠금에 자연히 포함된다.

---

## 8. 항목별 결정 → deterministic 판정 → 상태 변화 → replay/log (시험지 8)

LEDGER 판정 규칙 4(`docs/loops/v2-planning-2026-07-12/LEDGER.md:11`)를 오픈 경로 6항목 전부에 적용한다(개정 2차 R-번호).

| # | 요소 | 결정 (누가 무엇을) | deterministic 판정 (draw 수) | 다음 상태 변화 | replay / log |
|---|---|---|---|---|---|
| R1 | 도시 원장 기반 | — (기반 표 + flush 채널) | — | `v2_city_ledger` 행 생성 | flush delta + infra flush IT |
| R2 | 수입·봉록 도시 귀속 | 지휘부가 세율(5~35%)·지급률(100~500%) 결정, 관직자가 담당도시 체류, 장수가 어느 도시에 있는지 | `V2ProcessCityIncome` — `calcCityGoldIncome`(`IncomeTick.kt:29`) + `ProcessIncome.kt:141-153` 3분기, **draw 0** | 도시별 `gold`/`rice` 증감, 장수 `gold`/`rice` 증가, 도시 잔고 부족 시 그 도시만 실효 지급률 하향 | 스냅샷 + 순수 함수 재실행 + 도시별 봉록 로그 라인 |
| R3 | 도시병사 감소·공백지화 | (자동) `RaiseDisaster`가 1·4·7·10월에 확정한 재난 상태 | `city_id ASC` 순회, `state ∈ {3..9}`, `attritionLoss(garrison, 장수수)`, **draw 0** | `garrison` 감소, 0이면 같은 반복 안에서 `city.nationId = 0` | before/after garrison을 담은 v2 로그 라인 + 스냅샷 재실행 |
| R4 | 병사보충 커맨드 | 장수가 개인턴에 제출 | 도시 원장 금 잔액·상한 검사, **draw 0** | 도시 `gold` 감소, `garrison` 증가 | `TurnDaemonCommandResult(ok/reason)` durable result + 로그 |
| R5 | 수송 커맨드 | 장수가 개인턴에 제출 (금·병량·도시병사, 인접 1홉) | 인접 판정은 기존 `CalcCityDistance`(`CalcCityDistance.kt:22-33`)의 `CityConst.path` BFS 재사용, 한도 검사, **draw 0** | 출발 도시 원장 감소, 도착 도시 원장 증가 | 동일 |
| R6 | 도시 원장 열람 | — (read-only) | — | 없음 | 규칙 4 **비대상**(아래) |

R1~R5 모두 draw가 0이므로 **replay는 시드 재현조차 필요 없다** — 턴 N의 월드 스냅샷과 순수 함수만 있으면 결과가 유일하게 결정된다. 이는 seed 기반 replay보다 강한 성질이다.

**규칙 4 비대상 3건**을 감추지 않고 밝힌다. R6(도시 원장 열람 화면)과 §3.2의 감찰부 화면은 **read-only 정보**이지 상태 변화를 만들지 않고, §6.1의 성벽 특색은 **시드 데이터**이지 결정을 만드는 콘텐츠가 아니다. 셋 다 규칙 4를 적용할 대상이 아니다. R6을 그럼에도 티켓으로 세는 이유는 §9.2에 적었다 — 보이지 않는 원장은 결정을 낳지 못하므로, R6이 없으면 **R1~R5 전체가 규칙 4의 첫 항("결정")을 잃는다.**

**관계망(구 R7·R8)은 이 표에서 빠졌다 — 오픈 후이기 때문이다(§4.4·§9.4).** 판정 근거를 남긴다. 구 R8(능력치 보정)은 스스로 행을 쓰지 않는 **파생값**이고 상태 변화는 스탯을 소비하는 쪽에서 난다. 원안은 이를 "결정 자체가 이미 v1 이동 커맨드로 기록되므로 규칙 4를 만족한다"고 변호했으나, 규칙 4는 *신규 콘텐츠가 스스로* 네 항을 만들 것을 요구한다(`LEDGER.md:11`). 남의 결정과 남의 로그를 빌려 채우는 것은 문언에 맞지 않는다. **원안의 변호를 철회하고 "구 R8은 규칙 4를 단독으로 만족하지 못한다"로 판정한다.** 구 R7(결속 기록)은 규칙 4를 만족하지만 구 R8 없이는 사용자 결정을 절반만 이행하므로 둘은 함께 움직인다.

한 가지 남는 부채는 명시한다. R4의 공백지화는 파괴적 전이(`nationId → 0`)이므로 나중 스냅샷만으로는 이전 소유자를 복원할 수 없다. 오픈 경로에서는 **로그 라인이 before/after를 함께 싣는 것**으로 감당하고, 구조화된 이벤트 소싱은 V2-4A replay spine(오픈 후)이 담당한다.

---

## 9. 오픈 경로 최소 부분집합과 티켓 수량 (시험지 9)

### 9.1 현재 오픈 경로 = 14 티켓

ADR-LITE-019(`.ai/decisions.md:194`, 표는 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:55-65`)가 고정한 순서다.

| 단계 | 티켓 | 수 |
|---|---|---|
| 0 | OPENSAM-31·32·33·34 (v1 선행) | 4 |
| 1 | OPENSAM-149 (restart-rehydrate) | 1 |
| 2 | OPENSAM-35 (V2-0A 격리) | 1 |
| 3 | OPENSAM-43·44 (V2-0B runtime/isolation + persistence ownership decomposition; product SQL/flush 0) | 2 |
| 4 | OPENSAM-45·46·47 (V2-1 lifecycle·패널) | 3 |
| 5 | OPENSAM-48 (V2-2 부곡) | 1 |
| 6 | OPENSAM-56 (V2-3 작전) | 1 |
| 7 | OPENSAM-61 (V2-5 가신) | 1 |
| | **합계** | **14** |

### 9.2 이 설계안이 추가하는 것 = 6 티켓 (개정 2차 재계산 · 개정 3차 단일값 확정)

원안은 8이었다. 재계산하면 6이다 — **줄어든 것과 늘어난 것이 둘 다 있고, 둘 다 적는다.** 개정 2차가 달아 둔 조건부 R0(+1)은 개정 3차에서 `OPENSAM-35` 범위 조회로 해소돼 오픈 후 `P0`이 됐으므로, 아래 표가 **오픈 경로 증분의 전부**다.

| # | 티켓 | 산출물 | 삽입 단계 (선행 조건) |
|---|---|---|---|
| R1 | `v2_city_ledger` 기반 | **개정 6차 재작성** — 0A-c 분리 location의 마이그레이션 + `DirtyState`/`ChangeRecorder` 채널 + `DatabaseHooks.toFlushPayload` 매핑 1줄 + `JdbcFlushExecutor`의 `FlushPayload`(`:2287`) 후행 필드·v2 step + **`engine.v2` 신규 파일**(v2 원장 store · 판정 로직) + v2 flush IT. **v1 델타와 같은 트랜잭션에서 커밋된다**(두 번째 DataSource·풀 없음). **`TruncateContract`·`InMemoryTurnWorld`·`WorldSnapshotLoader`·`TurnRunService`·`RehydrateService`는 경유하지 않는다**(§7.1-2 개정 6차, T2 11행 전량 + 가드 영향 명시) | **3b, 3단계 runtime/isolation·ownership decomposition 직후** — OPENSAM-150 자신이 첫 product DB migration과 entity flush extension을 만들며, 선행으로 요구하는 것은 OP43 runtime/isolation, OP44 crosswalk, OP128→130→131→132 complete shared-flush foundation이다. 제품 v2 DB table/flush path의 선존재를 요구하지 않는다. |
| R2 | 수입·봉록 도시 귀속 **(생산자 티켓)** | `V2ProcessCityIncome` leaf(`IncomeTick` 3함수 호출 + `ProcessIncome` 3분기 재조립) + **`prev_income_{gold,rice}` KV 유지**(§2.3) + 새 파일 `V2WorldActions` + **`EngineEventConfig.kt:79-81` 등록 체인 1줄** + **`WorldActionContext.kt` v2 컨텍스트 구현**(둘 다 T2, §7.1-2) + v2 시나리오 JSON에 `DEFAULT_EVENTS` 12행 전사 및 1·7월 행 `ProcessIncome`→`V2ProcessCityIncome` 치환 + 도시별 봉록 로그 | 3단계 직후, R1 뒤 |
| R3 | 도시병사 감소·공백지화 | v2 월간 leaf(draw 0, 월 게이트 {1,4,7,10} = `EventStore.kt:171,180,190,197`) + `attritionLoss` 장수수 스케일 + v2 시나리오 JSON 1·4·7·10월 `event` 행에 leaf append + before/after 로그 | 3단계 직후, **R2 뒤 — 병렬 아님**(개정 4차, 아래) |
| R4 | 병사보충 커맨드 | v2 개인턴 resolver + intake 배선 + `pollCommandResult` 규약 | **4단계(V2-1, `OPENSAM-45`·`46`·`47`) 이후** — command result lifecycle이 선행 |
| R5 | 수송 커맨드 | 금·병량·도시병사, 인접 1홉, 각 5만·최소 2000 + intake 배선 | 4단계 이후, R4 뒤 |
| R6 | 도시 원장 열람 | game-api read 엔드포인트 + `web/game` 도시 원장 패널(gold/rice/garrison) | **4단계와 동시** — `OPENSAM-46`·`47`의 조작 대상 패널 위에 필드를 얹는다 |

**14 → 20 티켓 (+6, +43%). 조건부 항목 없음 — 단일 수량이다.**

> **개정 4차 — R3를 R2와 병렬로 둔 것을 철회한다(생산자→소비자 순차).** 개정 3차는 R3 행에 "R2와 병렬"이라고 적었다. **CLAUDE.md 위반이다** — "병렬 워크트리 family는 **disjoint**해야 하며, 교차 영역 공유 산출물은 **생산자 먼저, 소비자 나중**으로 순차 빌드한다". 두 티켓은 파일 단위로 **두 곳에서** 겹친다.
> 1. **v2 시나리오 JSON 한 파일.** §7.1-2 판정에 따라 v2는 `ignoreDefaultEvents: true`이므로 **시나리오 유래 `event` 행 전체**를 v2 시나리오 JSON이 저작한다(나머지 `deferredGeneralRows`는 코드 생성이고 v1 leaf 이름을 쓰며 체인 등록으로 그대로 산다 — 개정 7차). R2의 1·7월 행 치환(`ProcessIncome` → `V2ProcessCityIncome`)과 R3의 1·4·7월 행 leaf append는 **같은 파일의 같은 행 3개**를 고친다(1·7월은 완전 중복, 4·10월만 R3 단독).
> 2. **`app/game-engine/.../world/WorldActionContext.kt` 한 파일.** R2의 v2 수입 컨텍스트와 R3의 v2 도시병사 컨텍스트는 **프로덕션 구현자가 같은 파일 하나**다(§7.1-2 개정 4차 추적, grep 전수). 두 티켓이 같은 클래스 본문을 동시에 넓힌다.
>
> 여기에 등록 순서 의존이 더해진다 — R3 leaf도 `V2WorldActions` 체인(`EngineEventConfig.kt:79-81`, R2 산출물)을 통해 등록되므로, R3 행이 먼저 들어가면 `EventAction.kt:70-74`가 미등록 이름에 `IllegalArgumentException`을 던져 v2 월드가 첫 1월에 죽는다. **판정: R2가 생산자(시나리오 JSON 소유 + 컨텍스트 파일 소유 + 등록 체인 개설), R3는 소비자로 뒤에 선다.**
>
> **부작용 — R2가 가장 큰 티켓이 됐다.** R2 산출물은 이제 (i) v2 수입·봉록 leaf, (ii) `prev_income` KV 유지, (iii) `V2WorldActions` + 등록 체인 1줄, (iv) `DEFAULT_EVENTS` 12행 전사 + 1·7월 치환, (v) `WorldActionContext.kt` 컨텍스트 구현이다. 반나절 단위 분해 규율을 적용하면 **(iii)+(iv) 시드/등록**과 **(i)+(ii)+(v) 정산 로직**으로 갈라 **20 → 21**이 될 수 있다. 그러나 이것은 **동일 산출물의 분해이지 범위 추가가 아니고**, 분해 여부는 착수 시점 티켓 분해 규율의 판단이다. **권고 수량은 20 단일값을 유지한다** — 조건부 항목을 다시 만들지 않기 위해서다(개정 3차가 조건부 R0을 없앤 것과 같은 이유).

**무엇이 줄었나.** 원안 R2(수입)+R3(지출)이 **한 티켓으로 합쳐졌다.** v1 `ProcessIncome`이 수입·봉록 총액·3분기 판정·장수별 지급을 **한 leaf 안에서** 하기 때문이며(`ProcessIncome.kt:124-167`), 이를 도시 단위로 재조립하는 것은 두 번 할 수 있는 일이 아니다. 그리고 원안이 "중복해서 옮겨 적겠다"던 수입 공식은 `IncomeTick.kt:29,47,65`의 **도시 단위 공개 함수를 호출**하는 것으로 대체돼 산출물이 더 줄었다.

**무엇이 늘었나 (원안이 빠뜨린 것).** **R6 — 도시 원장을 볼 화면이 원안에 없었다.** R1~R5는 전부 백엔드이고, 유저는 자기 도시의 금·병량·도시병사를 **어디서도 볼 수 없다.** 보이지 않는 원장 위에서는 "어느 도시에 누구를 둘까"라는 결정이 성립하지 않으므로, R6이 없으면 이 설계안의 가설 자체가 유저에게 도달하지 않는다. 원안의 8에는 이 티켓이 없었다.

**관계망(구 R7·R8)이 빠졌다** — 사유는 §9.4. 오픈 후 **7티켓**으로 분해했다(§9.4, 개정 3차에서 구 R0이 P0으로 편입되며 6 → 7).

> **개정 3차 — 개정 2차의 "조건부 R0 +1"을 철회한다.** `OPENSAM-35` 본문을 조회한 결과 그 범위는 0A-a~g 7항목(`01-backbone-micro.md:74-81`)이고 **파이프라인 seam은 포함되지 않는다**(§4.3). 동시에 개정 2차가 조건부의 근거로 삼은 "R2의 leaf 등록 체인도 같은 seam을 쓴다"는 **거짓임이 확인됐다** — R2가 여는 것은 `EngineEventConfig.kt:79-81`의 빈 한 줄이고(§7.1-2, R2 산출물에 포함), 파이프라인 seam은 `EngineGeneralActionPipelineBuilder`/`DaemonLoopConfig`/`FrontInfoController`의 구조 변경으로 파일도 기법도 겹치지 않는다.
>
> **따라서 R0은 오픈 경로에서 빠지고 관계망 오픈 후 분해의 선행 티켓 `P0`이 된다(§9.4).** 오픈 경로 총계는 **20**이며 조건부가 아니다. `OPENSAM-35`를 넓히는 대안은 소비자 0인 확장점을 오픈 전에 만드는 일이라 기각했다(§4.3).

R5의 한도 수치는 원문 그대로다: "수송의 최대치는 금, 병량이 각각 5만 씩 가능하며 수송에 필요한 최소병사량은 2000명 입니다"(`help__start__intermediate__intermediatebattle.md:364`), "인접 도시로 도시의 금과 병량, 그리고 도시 병사를 수송할 수 있습니다"(`:361`). 주민은 수송 대상이 아니다. 도시병사 수송의 상한은 원문에 **없으므로 UNKNOWN**이고, 구현 시 금·병량과 같은 5만을 임시 적용하되 "묘섭 미명시"로 표기한다.

### 9.3 오픈 후로 보내는 것 — 그리고 그 이유

| 항목 | 사유 |
|---|---|
| 감찰부 화면 (§3.2) | 순수 read-only. F3 read 화면이 부분 대체. 티켓 1개를 아낀다 |
| 임원진 3종 계수 (§5) | 배치효과 축은 태수·군사만으로 이미 증명됨. 계수 3개가 오픈을 늦출 값어치 없음 |
| 도시 특색 7종·규모 게이트 2종·지역병종 (§6) | `city.tech` 부재 / v1 병종·전투 수치 접촉 / C-track 의존 |
| 성벽 특색 (§6.1) | 채택했으나 시드 작업이므로 오픈 후 배치, 티켓 증분 0 |
| 도시병사 전투 참여·차출 지휘·전투 손실 복귀 | 전투 엔진 접촉 = 이 설계안 최대 리스크 지점 |
| 도시병사 3개월 병량 유지비 | 원장이 한 기수 도는 것을 본 뒤 붙이는 편이 안전 |
| 묘섭식 독립 도적 이벤트 | §2.4의 (a) 경로. self-seeded DRBG 패턴은 준비돼 있음 |
| 구명 관계 (§4.5) | 전투 엔진 접촉 |
| 관계 소비 확대 (작전 신뢰·외교 태도·`RetainerProposal.score`) | 기록이 먼저, 소비는 나중 |
| **관계망 전체 (§4)** | §9.4. 아래에 **7티켓**으로 분해(개정 3차에서 구 R0 = P0 편입) |
| **관계 보정 조건 확대** (같은 작전 / 같은 부대, §4.7) | 조건 하나마다 새 소속 판정이 붙는다. V2-3·V2-2 완료 후 값어치를 따로 따진다 |
| **v2 NPC 도시 정책** (§2.6) | `ai/*`는 v2 전용 커맨드를 선택하지 않으므로 NPC 도시의 `garrison`이 보충되지 않는다. **마감이 "오픈 후"가 아니라 "게임 내 3년 이내"**인 유일한 항목 |
| **국고 완전 귀속** (포상·헌납의 v2 전용 변형, §2.3) | 묘섭은 도시 금을 쓰지만(`positionrole.md:138,233`, `othercommands.md:75`) v2 오픈판은 국고에서 쓴다. v1 커맨드 42지점을 건드리지 않기 위한 의도된 divergence |
| **국력·통계의 도시 원장 합산** (`PostUpdateMonthly.kt:123`, `CheckStatisticCalculator.kt:106,109,200`) | 고치려면 T1(패러티 코어) 파일을 열어야 한다 |
| **국가 임원진·중앙관직·품관 3층 전부** (§5-bis) | L1·L3은 이미 v1에 있고, L2는 O0/V2-7로 이미 오픈 후 확정. 대사농 1종·품관 게이트 3종 모두 오픈 후 |
| `spec:388` 본문 개정 (§4.2) | 채택 **이후**의 정본 작업. 채점 전에 정본을 고치지 않는다 |
| 도시 4모델 분리 (G0) | ADR-LITE-019가 이미 오픈 후로 확정 |

### 9.4 관계망을 오픈 경로에서 빼는 판정 — 원안 권고를 뒤집는다

원안은 R7·R8(관계 기록 + 보정)을 오픈 경로에 **넣기를 권했다.** 개정 2차에서 **철회하고 오픈 후로 판정한다.** 원안의 두 논거를 하나씩 검증한 결과다.

**논거 ① "기록은 소급할 수 없다" — 부분적으로 거짓이다.**

이 논거는 확인 없이 쓰였고, 확인해 보면 세 층으로 갈린다.

| 기록 | 실측 | 소급 가능성 |
|---|---|---|
| 커맨드 이력 | `command_inbox`(`infra/src/main/resources/db/migration/V34__command_inbox.sql`)는 **insert 후 status UPDATE만** 한다(`JdbcFlushExecutor.kt:2182`). `command_result`·`command_outbox`(`V35__command_result_outbox.sql:18-49`)는 insert-only. **purge/TTL/retention 잡이 리포지토리 전체에 없다** — `app/*/src/main`·`infra/src/main`에 `@Scheduled` 0건, `DELETE FROM command_*`는 테스트 teardown뿐 | **현재 구현상 무기한 보존.** 단 보존 정책 자체는 명시적 미정 — `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md:502`가 "retention duration은 **UNKNOWN**"이라고 적어 두었다 |
| 작전 **참여** | V2-3이 `operation_participants` 표를 만든다 — `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:190` 티켓 3-b (`operations`/`operation_routes`/`operation_events`도 같은 줄). V2-3 = `OPENSAM-56` 매핑은 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:63` | **COMRADE는 가능, RIVAL은 UNKNOWN** — 아래 |
| 작전 **정산** | 스펙·계획 어디에도 정산 기록의 영속 테이블이 없다. 정산은 커맨드/반환 경로로만 나타난다(`2026-07-12-v2-command-catalog-and-rollout.md:187`, `01-backbone-micro.md:215`) | **UNKNOWN** |

**개정 3차 — 소급 가능성을 COMRADE와 RIVAL로 갈라 다시 판정한다.** 개정 2차는 "이 표를 리플레이해 COMRADE·RIVAL을 소급 생성할 수 있다"고 썼는데, **확인된 절반을 전부인 것처럼 말한 확대해석이다.**

- `01-backbone-micro.md:190`은 표 **이름**만 나열하고 **컬럼을 하나도 정의하지 않는다.** 리포지토리 전체에서 `operation_participants`는 표 이름 나열로만 나타나고 DDL·컬럼 목록은 **0건**이다.
- 스키마에 가장 가까운 근거인 `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:170-176`의 `Operation` 블록은 `targetCityId` / `arrivalWindow` / `participants: generals, retainers, bugok, subfaction forces` / `roles: MAIN | SUPPORT | SCOUT | SUPPLY | RESERVE` / `route` / `rules`다. **`participants`도 `roles`도 전부 자기편 구성이고 적군 참가자를 담는 자리가 없다.** 목표는 `targetCityId`, 즉 도시이지 상대 작전이 아니다.
- §4.5는 RIVAL을 "같은 `Operation`에서 **서로 반대편으로** 참가해 종료"로 정의했다. 위 구조에는 그 사실이 기록되지 않는다.

> **판정: COMRADE 소급 = 가능(같은 `Operation`의 공동 참가자는 `participants`로 복원된다). RIVAL 소급 = UNKNOWN — `operation_participants`의 컬럼이 확정되면 재판정한다.** 이 UNKNOWN 위에 결론을 세우지 않는다.

**그래서 −2(관계망 제외) 판정이 흔들리는가 — 흔들리지 않는다. 다만 근거 하나가 빠진다.** 개정 2차는 소급 가능성을 관계망 제외의 "결정적 근거"라고 썼는데, 그 근거는 이제 **절반(COMRADE)만** 성립한다. 그러나 제외 판정은 세 논거 위에 서 있고 나머지 둘은 그대로다 — (i) 순서상 넣을 자리가 없다(emergent **5종 중 4종**이 오픈 경로 **마지막 두 티켓**에 붙는다, 아래 — 개정 4차에서 §4.5 실측값으로 통일), (ii) 오픈 시점 사전관계 10건은 차별점이 아니라 데모다. **특히 (i)은 단독으로 결정적이다** — 소급이 전혀 불가능하더라도 관계망을 오픈 경로에 넣는다는 것은 오픈 직전에 7티켓을 더 얹는다는 뜻이고, 1순위가 "빨리 열기"인 이상 방어할 수 없다.

즉 **관계를 낳는 주 소스의 소급 가능성은 절반만 확인됐고**, 나머지는 무기한 보존(정책 미정) 또는 UNKNOWN이다. "그때 만들지 않으면 영원히 없다"는 **적어도 COMRADE에 대해서는** 성립하지 않는다.

`ponytail:` 남는 천장 하나 — 커맨드 보존 정책이 나중에 짧은 TTL로 정해지면 가신 서약/해제(V2-5)의 소급 창이 닫힌다. 대응은 관계망 도입을 그 정책 결정보다 앞세우는 것이고, 그 순서는 오픈 후 일정 안에서 정한다.

**논거 ② "유저가 첫 턴부터 눈으로 보는 차별점" — 실제로는 첫 턴에 거의 보이지 않는다.**

오픈 시점의 사전 관계는 손편성 **10건 내외**이고(§4.4), 보정은 **구성원이 같은 도시에 모였을 때만** 발동한다(§4.7-2). 174명 시나리오에서 10건의 결속이, 그것도 동일 도시 조건을 만족할 때만 ±2를 준다. 이것은 차별점이 아니라 **데모**다. 진짜 차별점은 전면 사전관계 데이터가 붙은 뒤에 생기고, 그 데이터의 한 갈래(RTK 원본 호오 필드)는 존재 여부부터 **UNKNOWN**이다(§4.4).

**그리고 원안이 검토하지 않은 세 번째 사실이 있다 — 순서상 넣을 자리가 없다.**

emergent 관계 **5종 중 4종**이 **`OPENSAM-56`(V2-3 작전)과 `OPENSAM-61`(V2-5 가신)에 붙는다**(§4.5의 표 — MENTOR·GRUDGE는 V2-5, COMRADE·RIVAL은 V2-3, 나머지 하나인 구명 RESCUE는 전투 엔진 접촉이라 오픈 후). 이 둘은 오픈 경로의 **6번과 7번, 즉 마지막 두 티켓**이다(`README.md:63-64`). 관계망은 그 뒤에만 올 수 있으므로, 오픈 경로에 넣는다는 것은 곧 **오픈 직전에 7티켓을 더 얹는다**는 뜻이다. 1순위가 "빨리 열기"인 이상 이 자리는 방어할 수 없다.

**추가로, §8에서 판정한 대로 구 R8은 LEDGER 규칙 4를 단독으로 만족하지 못한다.** `LEDGER.md:11`은 "신규 콘텐츠는 `결정 → deterministic 판정 → 다음 상태 변화 → replay/log`를 만들지 못하면 첫 출시에서 제외한다"이다. 파생값은 자신의 상태 변화를 만들지 않는다. 규칙 문언이 곧바로 제외를 지시한다.

> **판정: 오픈 경로 = 14 + 6(R1~R6) = 20. 관계망은 오픈 후.**
>
> 이것은 독립 reviewer의 긴축 결론과 같은 숫자지만 **구성이 다르다.** reviewer의 20은 원안 R1~R6(수입·지출 분리, 열람 티켓 없음)을 그대로 둔 20이고, 이 문서의 20은 **R2+R3 병합(−1)과 R6 열람 신설(+1)**을 거친 20이다. 우연히 같은 값이 된 것이지 숫자를 맞춘 것이 아니다.

**관계망 설계는 축소되지 않았다.** 사용자 결정("관계는 능력치 버프에도 영향을 줘야 한다")은 §4 전체에 반영되어 완전한 상태로 남아 있고, 개정 2차는 오히려 실효 상한(§4.7)·재발생 처리(§4.6)·주입 지점(§4.3)을 코드 근거로 채웠다. **바뀐 것은 착수 시점 하나뿐이다.** 설계 내용의 판단과 일정의 판단을 섞지 않는다.

#### 오픈 후 관계망 분해 — 7 티켓 (개정 3차: 6 → 7, R0 편입)

원안은 이 전부를 R7·R8 **2티켓**으로 셌다. 실측 후 재계산하면 6이었고, 개정 3차에서 R0(파이프라인 seam)이 오픈 경로에서 내려오면서 **7**이 된다.

| # | 산출물 | 근거 |
|---|---|---|
| **P0** | **파이프라인 seam 개설** (`EngineGeneralActionPipelineBuilder.kt` · `DaemonLoopConfig.kt:229` · `FrontInfoController.kt:377,392`) | **개정 3차 신설 = 구 R0.** `OPENSAM-35` 범위 밖임이 조회로 확정됐고(`01-backbone-micro.md:74-81`), 소비자가 P5·P6뿐이므로 소비자와 같은 웨이브에 둔다(§4.3). P5·P6의 **선행 필수** |
| P1 | `v2_general_bond` 스키마 + flush 채널 | R1과 동일한 6파일 패턴(§7.1-2) — 그 자체로 1티켓 규모임이 R1로 증명된다 |
| P2 | 사전관계 빌더(`tools/v2/build_preset_bonds.py`) + `ScenarioImporter` 적재 | 별도 산출 파일 + 임포트 경로. 5스탯 빌더 선례와 같은 규모 |
| P3 | emergent 생성 4종 | V2-3 정산 2종(COMRADE·RIVAL) + V2-5 정산 2종(MENTOR·GRUDGE). 서로 다른 두 phase의 정산 지점에 붙으므로 실제로는 2티켓으로 더 갈릴 수 있다 |
| P4 | 소멸·상한 월간 leaf + 메모리 `bondIndex` | TTL 만료·장수당 상한·사망 정리 + 부팅 재구성(§4.6·§4.9) |
| P5 | `RelationStatModule` + `V2ActionModuleAssembler` + 테스트 3종 | 원안이 "1티켓"이라 주장한 부분. 조건을 "같은 도시" 하나로 좁힌 덕에 실제로 1티켓이 맞다 |
| P6 | 표시 경로 | `TrustGrade` 거부 게이트 + 장수정보 화면 + **game-api `displayStatBonuses` 경로 별도 배선**(§4.3 부수 발견 1) + **엔진 실효값과 표시값의 정합**(§4.3 부수 발견 2 — `FrontInfoController.kt:393-394`가 `GetStatValue`를 우회해 부상·교차증강·clamp가 없으므로, 조치하지 않으면 엔진 ±8이 화면엔 ±6으로 뜬다) |

원안이 2로 센 것이 7이 된 주된 이유는 넷이다 — seam이 오픈 경로에서 내려온 것(P0), flush 채널이 별도 티켓 규모라는 것(R1이 증명), 빌더·시드가 코드가 아니라 별도 산출물이라는 것, 그리고 **표시 경로가 두 개**라는 것.

### 9.5 ADR-LITE-019에 미치는 영향

채점기 §채택 규칙 마지막 줄대로, 채택되면 ADR-LITE-019 개정이 따른다.

- **오픈 경로 14 → 20**(단일값, 조건부 없음). 개정 2차의 조건부 R0은 `OPENSAM-35` 범위 조회로 해소돼 오픈 후 `P0`이 됐다(§4.3).
- **삽입 위치는 단일하지 않다.** R1은 3단계의 runtime/isolation·ownership decomposition 뒤 3b에서 첫 product DB/flush를 직접 만들고, R2·R3은 R1 뒤에 순차 실행한다. R4·R5는 4단계(V2-1, `OPENSAM-45`~`47`) 이후, R6은 4단계와 동시다. 3단계 OP43/44가 product 적재·영속화를 이미 제공한다는 과거 전제는 supersede됐으며, R1을 자기 산출물로 block하지 않는다.
- `V2-G0`·`C-track`·`O0/V2-7`(임원진·중앙관직, §5-bis)을 오픈 후에 두는 기존 결정과 `OPENSAM-149` 선행은 **그대로 유지**된다. 이 설계안은 ADR-LITE-019가 뺀 것을 되돌리지 않는다.
- **`spec:388` 개정은 오픈 후로 미룬다.** 관계망 착수가 오픈 후이므로 정본 문장도 그때 함께 고치는 것이 맞다. 지금 고치면 구현이 없는 선언만 남는다. 채택 시 등록만 해 둔다(§4.2).
- **`docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:307`과의 관계.** 그 줄은 "기존 개인턴·사령턴 커맨드의 코드, 예약 위치, 패리티 로그와 v1 결과를 유지한다"이다. 이 설계안은 v1 커맨드를 **한 줄도 고치지 않고**(§2.3의 42지점 무접촉, §7.1의 T1) 월간 leaf 하나만 v2 프로파일에서 치환하므로 이 조항과 충돌하지 않는다.

---

## 10. G0와의 관계 — 대체가 아니라 선행 (시험지 10)

### 10.1 직교성

G0는 *도시가 무엇인가*를 정의한다 — 지도 위 개체의 정체성, 개수(2,000), 4모델 분리(행정단위/물리장소/치소/주변네트워크), 3단계 LOD 표현. 이 설계안은 *도시가 무엇을 소유하는가*를 정의한다 — 금·병량·주둔군. 전자는 **행(row) 집합**을 정하고 후자는 **열(column) 의미**를 정한다. 열은 행 집합이 바뀌어도 살아남는다.

### 10.2 키 안정성 — G0가 왔을 때 원장이 깨지지 않는 이유

`v2_city_ledger`의 PK는 `(world_id, city_id)`다. G0가 도시를 4모델로 분리하면 `city_id`는 그중 **치소(물리 장소)** 로 귀속된다 — 금·병량·주둔군은 물리적 장소에 있는 것이지 행정 단위에 있는 것이 아니기 때문이다. 따라서 G0 착수 시 필요한 작업은 `city_id → seat_id` **1:1 재매핑 마이그레이션 한 건**이고, 데이터 손실은 0이다.

**이 귀속 규칙을 지금 명시해 두는 것 자체가 이 설계안이 G0에 주는 선물이다.** 나중에 4모델을 만들 때 "금은 어디에 두지"를 다시 논의하지 않아도 된다.

### 10.3 대체하지 않음의 증명

이 설계안은 GOLDENSET(round-2) 4번(모든 현·읍·도·후국 치소의 지도 참여, 대표 도시 축약 금지)과 8번(120/380/1,500 3D LOD)이 요구하는 것 중 **어느 하나도 충족하지 않으며, 충족한다고 주장하지도 않는다.**

- 거점 수를 늘리지 않는다. ADR-LITE-019대로 기존 도시 세트 또는 RTK 빌더(OPENSAM-104/105) 산출물을 그대로 쓴다.
- 3D를 만들지 않는다. 원장은 표에 숫자로 뜬다.
- 도시를 4모델로 분리하지 않는다. `city_id` 하나를 계속 쓴다.

충족 주장 자체가 없으므로 ADR-LITE-019의 **적용 시점 유예(오픈 시점 → 오픈 후 G0 착수 시점)와 충돌할 여지가 없다.** 4·8번은 폐기되지 않았고 이 설계안이 그것을 대신했다고 말하지도 않는다.

### 10.4 선행 가치

순서가 이쪽이 유리한 이유는 관측 대 추측의 차이다. 도시가 게임에서 실제로 무엇을 하는지(금을 담고, 병사를 두고, 굶으면 비고, 인접으로 수송된다)가 먼저 정해져 있으면, G0의 4모델 분리는 **어느 필드를 어느 모델에 붙일지를 관측에 근거해** 결정할 수 있다. 반대 순서라면 4모델을 먼저 만들고 무엇을 담을지 나중에 정하게 되며, 그때 필드 재배치가 발생한다.

### 10.5 이 설계안이 지는 부채

정직하게 하나 남긴다. **G0가 올 때 `city_id → seat_id` 재매핑 마이그레이션 1건이 필요하다.** 상한은 2개 표(`v2_city_ledger`, `v2_general_bond`는 장수 키라 도시 재매핑과 무관하므로 실제로는 1개 표) × 1개 컬럼이다. 이 부채는 명시적이고 유계이며, ADR-LITE-019가 이미 "데이터 모델을 오픈 후 교정하는 비용은 감수한다"고 기각 사유에 적어 둔 범위 안에 있다.

---

## 11. 잔여 UNKNOWN — 이 목록 위에는 설계를 세우지 않았다

개정 2차의 최대 실패는 **UNKNOWN을 해소했다고 보고하려다 근거를 지어낸 것**이었다(§2.4 ②, 자기채점 취약점 1). 그래서 개정 3차는 남은 UNKNOWN을 숨기지 않고 한자리에 모으고, 각 항목에 **(확인 방법) / (확인 실패 시 설계가 어떻게 되는가)** 를 붙인다. 어느 항목도 결론의 전제로 쓰이지 않는다.

| # | UNKNOWN | 확인한 것 (`path:line`) | 확인 방법 | 확인 실패 시 |
|---|---|---|---|---|
| U1 | **RIVAL(적대) 관계를 오픈 후에 소급 생성할 수 있는가** — 개정 3차 신설 | `operation_participants`는 컬럼 정의가 리포 전체에 **0건**이고 존재하는 것은 티켓 문장뿐(`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:190`). 스펙의 `Operation` 블록(`docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:170-176`)은 `participants`/`roles`가 **자기편만** 기록하며 교전 상대를 남기는 필드가 없다 | `OPENSAM-56` 착수 시 실제 마이그레이션 컬럼 확정 | **COMRADE만 소급**하고 RIVAL은 도입 시점부터 전방 축적한다. §9.4 −2 판정은 **순서 논거(i) 단독으로 성립**하므로 불변 |
| U2 | RTK 원본의 인물 호오(好惡) 필드 존재 여부 | `docs/superpowers/specs/2026-07-18-scenario-system.md:90-92`는 *정제층에 없다*만 확립. `tools/rtk14/build_rtk14_stats.py` 전수에 관계 토큰 **0건** | 원본 바이너리 실측(gitignore 대상이라 이 문서 작성 중 미열람) | §4.4의 세 번째 갈래(조건부 옵션)를 **없는 것으로 하고 설계는 변하지 않는다** |
| U3 | Operation 정산 기록의 영속 여부 | 커맨드 이력은 insert-only로 확인됨(`infra/.../db/migration/V34__command_inbox.sql`·`V35__command_result_outbox.sql:18-49`, purge 잡 0건 — `JdbcFlushExecutor.kt:2182`는 status UPDATE만) | `OPENSAM-56` 착수 시 | COMRADE 소급 근거가 U1과 함께 약해진다. 판정은 여전히 순서 논거로 성립 |
| U4 | 커맨드 이력 보존 **정책**(물리 보존이 아니라 운영 규정) | `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md:502`가 명시적 UNKNOWN | 운영 정책 결정 | 소급 창(window)이 유한해진다. 전방 축적으로 대체 |
| U5 | 묘섭 품관 눈금의 원문 수치 | v1은 `common/.../constants/GameConst.kt:48` `maxDedLevel = 30` 역순으로 확정이고 `logic/.../domestic/DomesticHelpers.kt:74-78`이 `"${30-dedLevel+1}품관"`을 찍는다 | `docs/wiki/raw/myosam-help/` 추가 정독 | §5-bis의 L3 신설 불필요 판정은 v1 실측만으로 성립하므로 **불변** |
| U6 | 도시병사 수송 상한 | 없음 | v2 밸런싱 시 결정 | R5(수송) 티켓 본문에서 상수로 확정. 설계 구조는 불변 |
| U7 | **국고 4대 지출이 도시 원장 이관 후 어떻게 기우는가** — 개정 3차 신설 | 유입·유출 표는 §2.3에 실측 인용으로 확정했으나 **균형은 시뮬레이션 없이 알 수 없다** | 오픈 전 관측 3종(§2.3) | 관측 항목으로 등록된 상태이며 설계 판정(`prev_income` **(a)** 유지)은 이 값에 의존하지 않는다 |
| U8 | `ActionPipeline` 조립 횟수 상한 | 장수당 최소 턴당 1회는 확인, 상한 미측정 | 프로파일링 | 개정 2차 반박 2(§"v2 로그 한 줄이 가장 싸지 않다")의 **보조** 근거일 뿐이고, 주 근거(변화분만 남기려면 이전 보정 상태 영속 = 새 컬럼)는 독립적으로 성립 |
| U9 | **`@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두어도 wire 직렬화가 성립하는가** — 개정 4차 신설 | `common/.../wire/TurnDaemonCommand.kt`는 `sealed class`(`:14`)~`:940` 본문 안에 74개 variant를 **전부 중첩 선언**한다(grep 실측: 중첩 74, 파일 밖 선언 0). 리포 전체에서 `@Serializable` sealed 계열(`TurnDaemonCommand`·`TurnDaemonCommandResult`·`TurnDaemonEvent`·`RealtimeEvent`)의 서브클래스를 다른 파일에 둔 **선례 0건** | 같은 패키지 신규 파일에 최상위 서브클래스 1개 + `@SerialName` + 왕복 직렬화 테스트 1개로 **컴파일 확인**(R4 착수 첫 작업) | 성립하지 않으면 R4·R5는 (a) v2 전용 wire sealed 타입을 새로 만들고 디스패처에 어댑터 분기 1개를 더 여는 안, 또는 (b) `common/.../wire/TurnDaemonCommand.kt` **T1 예외**를 사람 승인으로 받는 안 중 하나를 택한다. **(b)는 이 설계안이 스스로 금지한 경로이므로 (a)가 기본값이고, 어느 쪽이든 오픈 경로 수량 20은 변하지 않는다**(T2 파일이 0~1개 늘 뿐) |

| U10 | **v2 마이그레이션·시드가 `SeedBootstrap`/`ScenarioSeedRunner` 부팅 순서와 어떻게 맞물리는가** — 개정 5차 신설 · **개정 6차 축소** | v1의 순서는 확정 — `BootstrapConfig.kt:29-43`이 `seedBootstrap` 빈을 만들고 `WorldSnapshotLoader.buildSnapshot()`이 읽기 **전에** `seedBootstrap.ensureSeeded(jdbc)`를 부른다(`WorldSnapshotLoader.kt:51-53`). v2 원장은 `WorldSnapshot`을 경유하지 않으므로 **이 보장 밖**이다. **개정 6차 추가 확인:** v2 프로세스는 자기 DB(`opensamguk_v2`)를 가리키므로 그 프로세스의 `ensureSeeded`도 **v2 DB에** 시나리오를 심는다 — 즉 v1 시드와의 간섭은 없고, 남은 질문은 "v2 store가 처음 읽는 시점에 0A-c location의 Flyway가 이미 돌았는가" **한 가지로 좁혀진다**(Flyway는 Spring Boot에서 DataSource 초기화 직후·`ApplicationRunner` 이전에 도는 것이 표준이나 이 리포에서 v2 location으로 실측한 바 없다) | R1 착수 시 v2 `@Configuration`에 `@DependsOn`/`ApplicationRunner` 순서를 실측으로 확정 | v2 store를 **lazy 초기화**(첫 접근 시 적재)로 두면 순서 의존이 사라진다. **T2 목록·수량 불변**(신규 파일 안의 문제) |
| ~~U11~~ | ~~**v1 DB와 v2 DB에 걸친 flush의 원자성** — 개정 5차 신설~~ → **개정 6차 철회** | **철회 사유(코드 근거).** 이 UNKNOWN은 "v2 싱크는 다른 DataSource·다른 트랜잭션"이라는 개정 5차의 전제 위에 서 있었고, 그 전제는 `JdbcFlushExecutor`가 v1 DataSource에 묶여 있다는 **거짓 명제**에서 나왔다(§7.1-(3)·"개정 6차 — 배포 토폴로지 확정"). 확정된 토폴로지에서 v2 프로세스는 **DataSource가 하나**이고(`app/game-engine/src/main/resources/application.yml:8-11`, 오토컨피그 단일 `HikariDataSource`), v1 델타와 v2 델타는 `DaemonLoopConfig.kt:104-108`이 만든 **같은 `JdbcFlushExecutor`·같은 `TransactionTemplate`**(`JdbcFlushExecutor.kt:47-48`) 안에서 함께 커밋된다 ⇒ **걸칠 DB가 없다** | — | — (교차 원자성 항목 자체가 소멸. 다만 "v2 델타를 멱등 UPSERT로 쓴다"는 완화안은 **일반적 재시작 안전성 측면에서 유익하므로 R1 DoD에 남긴다** — UNKNOWN의 근거가 아니라 설계 선택으로) |
| U12 | **`SPRING_FLYWAY_LOCATIONS` 환경변수 오버라이드로 0A-c location을 더할 수 있는가** — 개정 6차 신설 | 양쪽 서비스의 `spring.flyway.locations`는 `application.yml:14`에 **하드코딩된 `classpath:db/migration`**이고, 이 리포의 어떤 compose/워크플로/스크립트도 그 키를 env로 덮은 **선례가 없다**(`docker-compose.yml`·`docker-compose.production.yml`·`.github/workflows/deploy.yml`·`scripts/deploy.sh` 확인). Spring Boot의 relaxed binding상 `SPRING_FLYWAY_LOCATIONS`가 리스트 프로퍼티를 대체하는 것은 **표준 동작이지만 실측하지 않았다** | 0A-c 착수 첫 작업으로 v2 프로세스를 그 env로 띄워 `flyway_schema_history`에 v2 마이그레이션이 기록되는지 확인 | 대체 경로가 둘 다 저비용이다 — (a) v2 전용 Spring 프로파일 파일(`application-v2.yml`) **신규 추가**(기존 `application.yml` 무수정 ⇒ 게이트 ⑤ 유지), (b) v2 `@Configuration`이 자기 `Flyway` 빈을 만들어 `migrate()` 호출. **어느 쪽이든 T2 편집 0·오픈 경로 수량 20 불변** |

**규율.** 위 **11건**(개정 4차 U9 · 개정 5차 U10 · 개정 6차 U12 신설, U11은 개정 6차 철회) 중 어느 것도 오픈 경로 **20**이라는 수량이나 §9.4의 관계망 제외 판정을 떠받치지 않는다. U1이 최악으로 판명돼도(적대 소급 완전 불가) 결론은 바뀌지 않으며, 그것이 개정 3차가 소급 논거를 "결정적"에서 "부수적"으로 강등한 이유다.

> **개정 6차 — UNKNOWN을 하나 지우고 하나 새로 열었다. 그 교환은 대칭이 아니다.** U11은 "설계가 만든 결함이 아니라 ADR-LITE-018에서 나오는 **필연**"이라고 썼는데, 필연이 아니라 **내가 잘못 읽은 코드에서 나온 유령**이었다. U12는 반대로 "표준 동작이라 확인할 필요를 못 느낀 것"이다. 5차 채점의 UNK-C·UNK-D는 이 절이 아니라 §7.1 본문에서 코드 근거로 **닫았다**(각각 `RehydrateService.kt` 무편집 / `engine/redis/**` 무편집).

---

## 자기채점 (참고용 — 독립 reviewer가 별도 채점한다)

> 이 표는 작성자 자기평가이며 채택 판정이 아니다. 채점기 §채택 규칙대로 **10/10 + 독립 reviewer `cleared`** 를 모두 받아야 채택된다. 한 항목이라도 N이거나 reviewer가 `fix-required`면 같은 바퀴에서 수정 후 동일 시험지로 재채점한다.

> **개정 2차 규율: 모든 Y의 근거는 이 문서 바깥의 `path:line`이어야 한다.** 문서 내부 절 참조(§x.y)는 근거가 아니라 위치 표시로만 쓴다. 1차 채점이 5/10을 받은 원인이 "문서 안에서만 일관된 주장"이었기 때문이다.

> **개정 5차 규율 추가 — 확장점에서 출발하지 말고 *메커니즘에서 역추적*한다.** 개정 4차 규율(아래)은 옳았지만 **출발점이 여전히 "내가 필요하다고 생각한 확장점"이었고, 그래서 네 바퀴째도 뚫렸다.** 4차 채점자는 다른 출발점(P6 betting 파일셋 복제 / 게이트 메커니즘 역추적 / 등록 메커니즘 역추적)에서 같은 절차를 돌려 즉시 2건을 더 찾았다. 그러므로 목록을 닫기 전에 **다섯 개의 메커니즘 질문**을 티켓마다 한 번씩 돌린다 — ① 이 코드가 **부팅 시 등록·조립되는 지점**은 어디인가(`@Bean`/`@Configuration`/컴포넌트 스캔/`@EntityScan` 같은 **화이트리스트**), ② 선언한 **게이트가 그 지점을 물리적으로 덮는가**(무조건 등록되는 빈의 메서드 본문은 bean 등록 게이트로 덮이지 않는다), ③ **DataSource·트랜잭션·마이그레이션**이 어느 DB를 향하는가, ④ **직렬화·wire 등록점**이 컴파일러 플러그인인가 명시 등록인가, ⑤ **소스를 텍스트로 읽는 아키텍처 테스트**가 이 파일을 이미 봉인하고 있는가. ⑤가 개정 5차의 최대 수확이다 — `HotColdWorldCatalogGuardTest`가 `WorldSnapshotLoader.kt`를 T1 카탈로그와 `assertEquals`로 묶어 놓았다는 사실은 어떤 확장점 추적으로도 나오지 않고, **"이 파일을 고치면 무엇이 깨지나"를 반대 방향에서 물어야만** 나온다. 그리고 그 결과 "신규 파일 추가는 어디서나 허용"이라는 **T1 규칙의 전제 자체가 조건부**임이 드러났다(스캔 대상 디렉터리 안의 신규 파일은 v1 가드 테스트를 깬다).
>
> **개정 4차 규율 추가 — 확장점을 이름으로 적었으면 반드시 그 확장점의 *프로덕션 구현자·소비자까지 grep으로 세어* 파일명으로 적는다.** 이름만 적고 멈추면 편집해야 할 파일이 목록에서 빠지고, 그 누락은 "T2 목록이 완전하다"는 선언과 결합해 **거짓 완전성**이 된다. 이것이 1차(`app/**` 편집 0 주장) · 3차(`EngineEventConfig.kt` 누락) · 4차(`WorldActionContext.kt`·`InMemoryTurnWorld.kt`·`WorldSnapshotLoader.kt` 누락)에서 **연속 세 번** 반복된 실패 양식이고, 그때마다 "확장점 이름은 맞았다"는 것이 위안이 되지 못했다. 적용 절차는 셋이다 — ① 인터페이스면 `grep -rn "<Interface>" --include="*.kt" app infra logic common | grep -v /test/`로 **프로덕션 구현자 수를 센다**, ② 레지스트리·`when` 분기면 그 분기를 **누가 만들고 누가 호출하는지** 양방향으로 센다, ③ 세어 본 수를 문서에 **숫자로 적는다**(“유일하다”는 셌다는 뜻이고, 세지 않았으면 §11 UNKNOWN이다).
>
> **개정 3차 규율 추가: `path:line`이 있는 것으로는 부족하고, 그 줄이 *실제로 그 내용인지*가 근거다.** 2차 채점이 6/10에 머문 원인은 인용 형식은 갖췄으나 내용을 지어낸 행(아래 2번 행)이 있었기 때문이다. 3차에서 새로 쓰거나 고친 인용은 전부 파일을 직접 열어 재확인했고, reviewer가 준 줄 번호도 재확인해 어긋난 것은 정정했다(예: 개정 2차의 `Presets.kt:315` → 금 `:317`·쌀 `:327`, `ProcessSemiAnnual.kt:169-177` → `:167-177`).

| # | 문항 | 자기평가 | 근거 (`path:line`) |
|---|---|---|---|
| 1 | 도시 중심 = 자원 소유 주체, 운영자 자기규정 근거 | **Y** | `docs/wiki/raw/myosam-help/help__start__peq__peq.md:46`(City-oriented 자기규정)·`:61`(최대 차이 = 금·병량 이전 + 도시병사 상주)·`help__start__basic__myostart.md:116,119`. 정의를 "정기 재정 순환"으로 좁힌 근거는 `logic/src/main/kotlin/opensamguk/logic/world/ProcessIncome.kt:124-167`(수입·봉록이 한 leaf) |
| 2 | 도시 소유 자원·도시병사 스키마 + 공백지화 deterministic 전이 | **Y** | `BAD_STATE_CODES = {3..9}`가 `logic/src/main/kotlin/opensamguk/logic/world/RaiseDisaster.kt:104-127`(`DISASTER_TEXT`의 `LogTriple.stateCode`)로 **확정** — 코드별 출현행 3(`:108`·`:122`·`:124`)/4(`:106`)/5(`:107`·`:113`·`:118`·`:123`)/6(`:114`)/7(`:112`)/8(`:117`·`:119`)/9(`:109`·`:125`), 호황 계열은 `BOOMING_TEXT`의 2(`:131`)·1(`:132`)로 배타적. **개정 3차 정정 — 개정 2차가 이 자리에 적었던 7개 재해 명칭은 날조였고 실측 표로 교체했다(§2.4 ②).** 월 게이트 `{1,4,7,10}`의 근거는 `logic/.../event/EventStore.kt:171,180,190,197`의 `a("RaiseDisaster")` 4행(개정 2차의 `RaiseDisaster.kt:98` `BOOMING_RATE`는 **호황 확률표 오귀속**이라 철회). 게이트가 필요한 이유는 `RaiseDisaster.kt:146-148`(`state<=10 → 0` 리셋이 연도 게이트 **앞**·leaf **안**), 개막 유예는 `:151`(`startYear + 3`). `city.state` 필드는 `logic/.../domain/LogicEntities.kt:78`이고 쓰는 곳은 `RaiseDisaster` 하나(전수 grep) |
| 3 | 4축을 기존 `officer_level`·`officerCntByCity` 위에 얹기, 평행 축 신설 없음 | **Y** | `logic/.../world/ProcessIncome.kt:59-60`(`city = officer_city` 조건), 소비 형태는 `logic/.../domestic/IncomeTick.kt:41` `income *= 1.05.pow(officerCnt)`(1차에서 "확인 못 함"으로 비워 둔 자리). 귀속처만 바꾸면 되는 이유는 `IncomeTick.kt:29,47,65`가 이미 도시 단위 공개 함수라는 것 |
| 4 | 관계망 독자 divergence + **능력치 보정** + emergent/사전 관계 구분 + v1 불변 증명 + 사전 데이터 규율 | **Y** | (a) 오라클 없음 — `logic/.../actions/personnel/CheRandomImgwan.kt:180-183`·`actions/intake/RulerSuccession.kt:18-21`의 `affinity`가 개인↔**국가**뿐 (b) **실효 상한 ±6/±8** — `logic/.../stats/GetStatValue.kt:54-64`+`:89-91`(교차 증강이 `withIActionObj`를 전달), 크기 야드스틱은 `:53`(부상 10% = −8) (c) 주입 지점 실측 — `app/game-engine/.../turn/EngineGeneralActionPipelineBuilder.kt:14-17`·`app/game-api/.../controller/FrontInfoController.kt:367-403`, 둘 다 seam 없음(1차가 비워 둔 자리). **개정 3차 — 이 seam 개설(구 R0)은 `OPENSAM-35` 범위 밖임이 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:74-81`(0A-a~g 7항목 전부가 *격리*, 확장점 0) 조회로 확정돼 오픈 후 `P0`이 됐다** (c′) **표시 경로 divergence** — `app/game-api/.../controller/FrontInfoController.kt:393-394`의 `bonus()`가 `pipeline.onCalcStat`을 직접 부르고 `GetStatValue`를 우회하므로, 교차 증강·부상·클램프가 없는 **±6만 화면에 뜬다**(무력/지력 실효 ±8과 불일치) (d) 꼬리 append 안전성 — `logic/.../stats/ActionPipeline.kt:89-90`(단순 좌측 fold)·`:116`("NO RNG inside the fold")·`:181-185`(MODULE_ORDER) (e) 팩토리 무편집 규약 — `logic/.../stats/GeneralActionModuleFactory.kt:16-18`, 기존 순서 핀은 `logic/src/test/kotlin/opensamguk/logic/stats/ModuleFactoryOrderTest.kt:78-92` (f) **사전 관계 출처는 UNKNOWN으로 정정** — `docs/superpowers/specs/2026-07-18-scenario-system.md:90-92`는 *정제층에 없다*만 확립하며 `tools/rtk14/build_rtk14_stats.py`에 관련 토큰 0건 |
| 5 | 임원진 6종 도입 여부 결정 + 판정 지점·중첩 규칙 | **Y** | 체류 효과표 `docs/wiki/raw/myosam-help/help__start__intermediate__positionrole.md:191-201`, **두 번째 표(내정 보정·자격 요건)** `help__start__intermediate__intermediatedomestic.md:219-243`(위장군 `:225` ~ 사도 `:235`). 중첩 형태가 곱인 근거 `IncomeTick.kt:41`. L1↔L2 비경쟁은 `docs/superpowers/specs/2026-07-13-v2-imperial-court-office-reform-equipment-design.md:6`. **L3(품관) 신설 불필요** — `logic/.../domestic/DomesticHelpers.kt:74-78`이 이미 `"${30-dedLevel+1}품관"`을 찍고 `common/.../constants/GameConst.kt:48` `maxDedLevel = 30`이 정본 |
| 6 | 특색·규모 게이트·지역병종 각각 채택/보류 판정 + 발현 조건·수량 | **Y** | 특색 8종 — `help__start__advanced__optimizedomestic.md:133`(표 헤더)·`:135`(금 특색 행, 1차 행번호 오기 정정)·`:155`(도시기술 900), `optimizebattle.md:270,275-291`. 보류 사유 `logic/.../domain/LogicEntities.kt:64` "There is NO `city.tech`". 규모 게이트 `help__start__advanced__establishnation.md:95`·`help__start__beginner__sellandbuy.md:78` |
| 7 | v1 패러티 불변 증명 | **Y** | **T1/T2 2계층으로 재작성**(1차의 "0줄"은 `ChangeRecorder` 편집을 인정한 §2.1과 자기모순이었다). T1이 성립하는 근거 — leaf 등록은 `logic/.../event/WorldActions.kt:30-56`이지만 DB `event` 행이 있으면 기본 행을 대체하므로(`app/game-engine/.../config/EngineEventConfig.kt:46-57`) `logic/` 수정 0으로 치환 가능. **개정 3차 — 그 대체는 all-or-nothing이다**(`:57` `rows.isEmpty()`일 때만 `EventStore.withDefaults`, 아니면 `:58-68`이 DB 행만으로 store를 짓는다). 따라서 v2 world는 `EventStore.DEFAULT_EVENTS` 12행(`logic/.../event/EventStore.kt:157-248`, `SeedRow(`는 `:159,164,178,183,195,200,208,216,224,234,239,244`)을 **전량 재시드**해야 하고, 그 재시드와 v2 leaf 등록 한 줄(`EngineEventConfig.kt:79-81` — `WorldActions.register(EventActionFactory())`가 프로덕션에서 팩토리를 만드는 **유일한 지점**)은 **R2 산출물**로 귀속한다(§7.1-2·§9.2). 미등록 이름은 `logic/.../event/EventAction.kt:70-74`가 `IllegalArgumentException`으로 터뜨리므로 부분 시드는 런타임에서 잡힌다. R1의 flush 쓰기 5파일은 P6 betting 채널 선례(`app/game-engine/.../turn/ChangeRecorder.kt`, `flush/DatabaseHooks.kt`, `infra/.../persistence/JdbcFlushExecutor.kt`, `flush/TruncateContract.kt`)로 규모가 실증됨. 게이트는 `--diff-filter=MD` + `logic/src/main/kotlin/` **전체**(1차는 24개 패키지 중 6개만 열거). **개정 4차 — T2 목록을 확장점→구현자 추적으로 전면 재작성해 6+1 → 11편집+마이그레이션 1로 늘렸다.** 새로 들어온 근거: `ProcessIncomeContext`(`logic/.../world/ProcessIncome.kt:215-219`, `IncomeGeneral`에 `cityId` 없음 `:51-55`)와 `DisasterWorldView`(`logic/.../world/RaiseDisaster.kt:253-259`, `DisasterCity`에 `garrison` 없음 `:56-62`)의 **프로덕션 구현자가 `app/game-engine/.../world/WorldActionContext.kt` 하나뿐**(grep 전수, `:116`·`:124`), 그 클래스의 프로덕션 **생성 지점은 4곳**(`WorldEventContextFactory.kt:72`·`WorldActionContext.kt:920`·`MonthlyPostUpdateHook.kt:198`·`:322`)이라 **생성자를 넓히지 않는 편집만** 허용, 읽기 경로는 `InMemoryTurnWorld.kt:10,42` + `WorldSnapshotLoader.kt:51`이고 프로덕션 조립점은 `BootstrapConfig.kt:55` 하나, 인테이크 경로는 `InstantActionRegistry.kt:28-42` 계약을 따르되 분류기가 T1 집합을 읽으므로(`InstantActionController.kt:83-84`) v2는 자기 레지스트리·컨트롤러를 **신규 파일로** 만든다. cast-ctx/env-read 실패 양식 대조는 `WorldEventContextFactory.kt:23-31`. **개정 5차 — 메커니즘 역추적으로 4차 표 12행 중 8행을 교체했다(9편집+마이그레이션1 = 10행).** 새 근거: (i) `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HotColdWorldCatalogGuardTest.kt`가 `WorldSnapshotLoader.kt`의 `private fun load*` 이름 집합과 `jdbc.query` 위치를 `logic/.../memory/HotColdCatalog.kt`(**T1**)의 `snapshotMethodNames`와 `assertEquals`로 묶어 **v2 SELECT 삽입이 불가능**, (ii) ~~`JdbcFlushExecutor`는 `DaemonLoopConfig.kt:108`에서 **v1** `NamedParameterJdbcTemplate`으로 생성되므로 v2 step은 방어선 1 위반~~ → **개정 6차 철회(거짓 명제였다)**: `:104-108`의 템플릿에는 한정자가 없어 **오토컨피그**이고 어느 DB를 가리키는지는 프로세스 env가 정한다(`app/game-engine/src/main/resources/application.yml:8-11`) — v2 월드 프로세스에서 그것은 처음부터 v2 템플릿이다, (iii) 프로덕션 flush 호출점은 `TurnRunService.kt:527`+`:404`이고 v2 싱크 주입점은 `DaemonLoopConfig.kt:440`, (iv) `TruncateContract`는 **프로덕션 소비자 0**(리포 전수 grep, 참조는 `TruncateContractTest.kt`뿐), (v) `HotColdCatalog.runtimeSourceDirectories`(`:135-144`) 8개 디렉터리 안에서는 **신규 파일도** 가드를 깨므로 v2 엔진 클래스는 `engine.v2`에 격리, (vi) R6는 `GameApiApplication.kt:9-10`의 `@EntityScan`/`@EnableJpaRepositories` 화이트리스트가 진짜 등록점이고 `application.yml:8-10`의 `ddl-auto: validate` 때문에 화이트리스트 확장이 **v1 부팅을 깬다** ⇒ v2 read는 `JdbcTemplate`(선례 4파일), (vii) v2 마이그레이션은 `OPENSAM-35` **0A-c**(`01-backbone-micro.md:77`) 분리 location — 양 서비스 `application.yml:14`가 `classpath:db/migration`을 v1 DB에 적용하기 때문. **개정 6차 — 배포 토폴로지를 실측해 확정하고 그 위에서 T2를 재판정했다(편집 10 + 마이그레이션 1 = 11행).** 5차 채점의 CRITICAL-1(문서가 "v2 프로세스 = v2 DB"와 "flush 실행기 = v1 DB"를 **동시에** 주장)은 고르는 문제가 아니라 찾는 문제였고, 답은 **한 프로세스 = 한 월드 = 한 DB**다. 근거 다섯: (α) `app/game-engine/.../config/WorldIdConfig.kt:11`이 `OPENSAMGUK_WORLD_ID`(양쪽 `application.yml`에 **기본값 없음**)로 `EngineProcessWorld` 하나를 만들고 그 값이 프로세스 전역 상수다, (β) `application.yml:8-11`의 DataSource·`:14`의 Flyway가 전부 env 주입이라 프로세스마다 다른 DB를 향할 수 있다, (γ) **`infra/.../seed/ScenarioSeedCoordinator.kt:37-49`가 `world_state`에 설정된 월드 **하나만** 있을 것을 요구하고 다르면 `error(...)`로 부팅을 막는다 — "한 DB = 한 월드"는 DoD 약속이 아니라 **시드 활성 부팅에서 강제되는 코드 불변식**이다(개정 7차 정정: 세 진입점이 공유하는 `SeedBootstrap.ensureSeeded`가 `ScenarioSeedRunner.kt:70-73`에서 `seedEnabled=false`면 코디네이터 호출 전에 반환하므로, 시드 비활성 부팅의 담보는 0A DoD (i)의 env 분리다 — α·β·δ·ε는 무조건 성립하므로 확정 자체는 불변)**, (δ) 모든 Redis 정체성이 world-scoped다(`common/.../wire/StreamKeys.kt:16-18,23-27,33-34`, OPENSAM-127), (ε) 0A-e/0A-f(`01-backbone-micro.md:79-81`)와 ADR-LITE-018(`.ai/decisions.md:178-187`)이 별도 DB·프로덕션 v2 0을 이미 못박았다. 이 확정으로 (ii)가 무효화돼 `JdbcFlushExecutor.kt`·`DatabaseHooks.kt` **2행 복귀**·`TurnRunService.kt` **1행 삭제**, 두 번째 Hikari 풀 **불필요**, §11 **U11 철회**(단일 트랜잭션), `:1046`의 "v1 `command_inbox`" → "**v2 프로세스 자기 DB의** `command_inbox`" 정정. 아울러 5차 채점 M1·M2에 답해 **11행 전부에 "가드 영향" 열**을 붙였고(각 행이 `runtimeCallKeys`/`runtimeCallCounts`/`runtimeDirectSqlBoundarySources` 세 `assertEquals` 중 무엇을 깰 수 있는지 + `DaemonWriteGuard.writePathPackages`(`DaemonWriteGuard.kt:29-34`) 소속 4행의 JPA 상수풀 판정), UNK-C(`RehydrateService.kt` 무편집)·UNK-D(`engine/redis/**` 무편집)를 코드 근거로 닫았다 |
| 8 | 항목별 결정→판정→상태변화→replay | **Y** | `docs/loops/v2-planning-2026-07-12/LEDGER.md:11`을 오픈 경로 6항목에 적용. 3분기 판정의 실체는 `ProcessIncome.kt:141-153`, 공백지화 판정 입력은 `RaiseDisaster.kt:104-127`(재해 stateCode 3~9) + 발화 월은 `EventStore.kt:171,180,190,197`, 인접 판정은 `logic/.../CalcCityDistance.kt:22-33`. **구 R8(능력치 보정)은 규칙 4를 단독 충족하지 못한다고 판정을 뒤집고** 오픈 후로 내렸다 |
| 9 | 오픈 경로 최소 부분집합 + 티켓 수량 | **Y** | 기준 14는 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:55-65`. 재계산 **14 → 20**(R2+R3 병합 −1, R6 열람 신설 +1, 관계망 제외 −2) — **개정 3차에서 단일값 확정**, 조건부 항목 없음. 개정 2차의 조건부 R0은 `01-backbone-micro.md:74-81`(`OPENSAM-35` = 0A-a~g 격리 7항목, 파이프라인 seam 미포함) 조회로 해소돼 오픈 후 `P0`이 됐고, 조건부의 두 번째 근거였던 "R2도 같은 seam" 주장은 거짓으로 확인됐다(R2가 여는 것은 `app/game-engine/.../config/EngineEventConfig.kt:79-81` 한 줄, seam은 `EngineGeneralActionPipelineBuilder.kt:14-17`+`DaemonLoopConfig.kt:229`+`FrontInfoController.kt:367-403` — 파일도 기법도 불일치). 관계망 제외의 **결정적 근거는 순서** — emergent **5종 중 4종**이 `README.md:63-64`의 마지막 두 티켓(`OPENSAM-56`·`61`)에 붙는다(§4.5 표 기준, 개정 4차 통일). 소급 논거는 **절반만** 성립한다: COMRADE는 `01-backbone-micro.md:190`(V2-3 `operation_participants`)로 소급 가능하나 **RIVAL 소급은 UNKNOWN**(그 표는 컬럼 정의가 리포 전체에 0건이고, `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:170-176`의 `Operation`은 자기편 참여자만 기록). 커맨드 이력은 `infra/.../db/migration/V34__command_inbox.sql`·`V35__command_result_outbox.sql:18-49`가 insert-only(purge 잡 0건, `JdbcFlushExecutor.kt:2182`가 status UPDATE만)라 무기한 보존, 정책만 `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md:502`에서 UNKNOWN. **개정 4차 — R3의 "R2와 병렬"을 철회하고 순차(생산자 R2 → 소비자 R3)로 고쳤다.** 근거는 파일 단위 중복 두 건(v2 시나리오 JSON의 1·7월 행 / `WorldActionContext.kt`)과 등록 순서 의존(`EngineEventConfig.kt:79-81` 체인이 R2 산출물, 미등록 이름은 `EventAction.kt:70-74`가 예외)이며 CLAUDE.md "병렬 family disjoint · 공유 산출물은 생산자→소비자 순차"에 정면으로 걸렸다. R2가 최대 티켓이 되어 분해하면 **20 → 21**이 될 수 있으나 그것은 동일 산출물의 분해이지 범위 추가가 아니므로 **권고 수량은 20 단일값 유지**(조건부 항목을 되살리지 않는다) |
| 10 | G0 선행 증명 + round-2 4·8번 유예와 무충돌 | **Y** | `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:52`가 `V2-G0`·`C-track`을 오픈 후로 이미 확정. 이 설계안은 거점 수·3D·4모델 어느 것도 요구하지 않으므로 round-2 GOLDENSET 4·8번과 접점이 없다. 부채는 `city_id → seat_id` 재매핑 마이그레이션 1건(표 1개 × 컬럼 1개) |

**자기평가 10/10 (6차).** 1·2·3·4·5차 자기평가도 전부 10/10이었고 독립 reviewer는 5/10·6/10·9/10·9/10·9/10을 줬으므로, **이 표의 값은 신뢰하지 말고 위 근거 열의 검증 가능성만 채점 대상으로 보라.** 스스로 판단한 잔여 취약점을 남긴다. reviewer가 여기부터 공격하기를 권한다.

0-ter. **개정 6차 — 5차의 "불가능" 두 건 중 하나는 내가 만든 유령이었다. 이것은 누락도 불가능도 아닌 *제3의 실패 양식*이다.** 5차는 `JdbcFlushExecutor.kt`를 T2에서 내리면서 "그 실행기는 v1 DataSource에 묶여 있어 v2 step을 더하면 방어선 1을 위반한다"고 썼다. 인용한 두 행(`DaemonLoopConfig.kt:104-108`)은 **정확했다.** 틀린 것은 그 두 행에서 "v1"이라는 **수식어를 읽어낸 것**이다 — 소스에는 없는 단어이고, 나는 `application.yml`을 열지 않은 채 "v1 데몬의 코드니까 v1 DB겠지"를 사실로 승격시켰다. 그 한 문장에서 **두 번째 Hikari 풀·`TurnRunService` 신규 편집행·§11 U11**이 파생됐고, 5차 채점은 그것을 문서 내부 자기모순(CRITICAL-1)으로 정확히 잡아냈다. 세 바퀴 동안의 실패 양식이 "빠뜨렸다"(1·3·4차) → "불가능한 줄 몰랐다"(5차)였다면, 6차가 드러낸 것은 **"물리적 제약을 지어냈다"**이다. 앞의 둘은 목록을 넓혀 고치지만 이것은 넓힐수록 나빠진다 — 없는 제약을 피하려고 설계를 우회시키기 때문이다. 실제로 5차의 우회는 v2 쓰기 경로를 `DaemonWriteGuard.writePathPackages` **밖**에 두어 CLAUDE.md 하드 룰의 테스트 사각지대를 만들 뻔했다(§7.1 M2 절). **일반화한 규율: "이 코드는 X에 묶여 있다"고 쓸 때, X가 소스 텍스트에 있는 이름인지 내가 붙인 해석인지 매번 구분한다. 후자면 설정 파일까지 열거나 §11 UNKNOWN이다.** reviewer는 이 문서에 남은 다른 "묶여 있다/불가능하다" 진술 — 특히 §7.1의 `WorldSnapshotLoader` 봉인 판정 — 을 같은 잣대로 공격하기를 권한다(그쪽은 `HotColdWorldCatalogGuardTest.kt:163-206`의 `assertEquals` 세 개를 직접 읽어 확인했고 env가 개입할 여지가 없다는 점에서 성격이 다르지만, 그 구분 자체가 채점 대상이다).

0-bis. **개정 5차 — 출발점을 바꿨더니 4차 표의 12행 중 8행이 틀려 있었다(5행 삭제·3행 신설).** 그리고 그중 두 건은 *누락*이 아니라 **불가능**이었다 — `WorldSnapshotLoader.kt`는 `HotColdWorldCatalogGuardTest`가 T1 카탈로그와 `assertEquals`로 봉인해 두어 v2 SELECT를 넣을 방법이 아예 없고, ~~`JdbcFlushExecutor.kt`는 v1 DataSource에 묶여 있어 v2 step을 더하면 방어선 1을 위반한다~~(**개정 6차 철회 — 위 0-ter**). 4차 채점의 MAJOR-A는 "`BootstrapConfig.kt`를 표에 넣든지 로더 자기판정으로 바꾸든지"를 요구했는데 **둘 다 답이 아니었다.** 네 바퀴 동안 나는 "빠뜨렸다"만 고쳐 왔고, **설계가 물리적으로 성립하지 않는다**는 가능성은 검사한 적이 없다. 남은 위험은 여전히 대칭이다: 개정 6차가 그린 경로(v1 flush 기계에 v2 step 1개 · `engine.v2` 신규 패키지 · 후행 기본값 파라미터)도 **컴파일·부팅으로 확인한 것이 아니라 소스 독해로 추론한 것**이고, U9·U10·U12가 그 추론의 세 구멍이다.
0. **"전부 적는다"는 선언이 세 바퀴 연속으로 거짓이었다 — 이번에도 거짓일 수 있다.** 1차는 `app/**` 편집 0을 주장했고, 3차는 `EngineEventConfig.kt` 하나를 빠뜨린 채 "지금 전부 적는다"고 썼으며, 4차는 거기서 다시 네 파일(`WorldActionContext.kt`·`InMemoryTurnWorld.kt`·`WorldSnapshotLoader.kt` + 인테이크 2파일)을 찾아냈다. 매번 **확장점 이름은 맞았고 구현자를 안 셌다.** 4차는 R1~R6 전부에 grep 추적을 돌렸지만, 추적은 **내가 필요하다고 생각한 확장점**에서만 시작했으므로 **필요한 줄 몰랐던 확장점은 여전히 목록 밖**이다. 구조적으로 이 취약점을 없앨 방법은 티켓 착수 시 §7.2 게이트 ③(선언 집합과 실제 diff 일치)이 초과분을 **실행 시점에** 잡는 것뿐이고, 설계 단계에서는 닫히지 않는다. reviewer는 이 목록을 완전하다고 가정하지 말고 R4·R5의 신규 파일 계획부터 되짚기를 권한다.
1. **개정 2차가 이 표의 2번 행에서 근거를 날조했다** — `RaiseDisaster.kt:104-127`을 열어 stateCode 집합 `{3..9}`는 맞게 셌으면서 7개 재해 명칭은 지어냈고("가뭄"·"전염병"은 소스에 없다), 월 게이트 근거로는 무관한 `:98` `BOOMING_RATE`를 댔다. 둘 다 **UNKNOWN을 해소했다고 보고하려는 압력**에서 나왔다. 개정 3차에서 코드별 출현행까지 붙여 교체했으나(§2.4 ②), **같은 실패가 다른 절에도 남아 있을 수 있다** — reviewer는 이 문서의 `path:line`을 표본이 아니라 전수로 열어보기를 권한다. 특히 개정 2차가 새로 추가한 인용 전부가 대상이다.
2. **T2(경계 가산 전용)는 T1만큼 강하지 않다.** `app/**`·`infra/**` 수정은 "티켓 선언과 일치"로만 통제되고, 선언 자체를 넓히면 게이트가 따라 넓어진다. 구조적 불가능성이 아니라 규율이다. 대안은 없다 — flush 채널을 추가하려면 그 파일들을 열어야 하고, 이는 P6 betting이 이미 통과시킨 경로다.
3. **`ProcessIncome` 치환이 `ProcessSemiAnnual`·`PostUpdateMonthly`와 만드는 상호작용을 끝까지 추적하지 않았다.** 국고에서 세수가 빠지면 국가 반년 감쇠(`ProcessSemiAnnual.kt:167-177` — `>100000 ×0.95` / `>10000 ×0.97` / else `×0.99`)의 대상 금액과 국력 계산(`PostUpdateMonthly.kt:123`)의 입력이 함께 작아진다. v2 내부 일관성은 유지되지만 **밸런스가 어떻게 기우는지는 시뮬레이션 없이 알 수 없다.** 오픈 전 관측 항목으로 등록한다.
4. **NPC 도시 병력 고갈에 게임 내 3년의 마감이 붙었다**(§2.6). 오픈 후 티켓 중 유일하게 **시한부**이며, 이것을 놓치면 NPC 도시가 순차적으로 공백지가 된다. 1차 원안은 이 문제를 "증분 0"으로 오판했다.
5. **`getGoldIncome`의 세율 곱 위치가 v1과 달라진다.** v1은 합산 후 1회 곱(`IncomeTick.kt:122`), v2는 도시마다 곱이다. ADR-LITE-018로 v2는 v1과 금액이 같을 의무가 없으므로 결함은 아니지만, "합은 같다"고 말할 수 없다는 뜻이고 1차 원안의 Σ 불변식 DoD는 그래서 철회했다.

*(1차의 취약점 1·2·3·5는 개정으로 해소되거나 판정이 뒤집혀 목록에서 내려갔다. 4번 공백지화 divergence는 §2.4에 그대로 남아 있으며 여전히 유효한 공격 지점이다. 2차의 취약점 1(`OPENSAM-35` 범위 미확인)은 조회로 해소돼 내려갔고, 그 자리에 **날조 사실 자체**를 1번으로 올렸다 — 해소된 항목보다 위험한 항목이기 때문이다. 개정 4차는 3차 채점이 유일한 N으로 지목한 **목록 불완전성**을 0번으로 새로 올렸다. 1번(날조)보다 위에 둔 이유는, 날조는 3차에서 재발이 확인되지 않은 반면 목록 불완전성은 **세 바퀴 연속으로 재발**했기 때문이다.)*

*(UNKNOWN 11건은 §11에 따로 있다 — 개정 6차에서 U11이 철회되고 U12가 신설돼 수량은 그대로다. 이 목록은 "확인은 됐으나 설계가 약한 곳"이고, §11은 "확인 자체를 못 한 곳"이다 — 섞지 않는다. **오픈 경로 수량은 개정 3차 이래 20 단일값으로 불변이며, 개정 6차의 T2 재판정은 티켓 *내부* 편집 목록만 바꿨을 뿐 티켓 수·범위·순서를 건드리지 않았다.**)*
