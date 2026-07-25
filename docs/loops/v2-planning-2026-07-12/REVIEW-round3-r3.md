# round-3 독립 reviewer 재채점 #3

> 일시: 2026-07-25 · 대상: `round3-proposal-city-guanxi.md`(개정 3차, 1230줄) · 시험지 10문항, 부분 충족 = N
> **VERDICT: `fix-required` · 총점 9/10** (5 → 6 → **9**)
> 1차·2차 채점자와 다른 reviewer. **날조 재발 없음.**

## 채점표

| # | 판정 | 요지 |
|---|---|---|
| 1 도시중심 정의 | **Y** | 자원 소유 주체 정의 + 묘섭 근거 + 3D·거점 수 명시 배제 |
| 2 도시 자원·공백지화 | **Y** | **2차의 날조 지점이 실물 대조로 교정됨** — stateCode→라벨 7건, 출현행 12개 전부 일치. `prev_income` 소절 신설 + (a) 채택. 네 번째 대가 정량화 |
| 3 4축을 기존 축 위에 | **Y** | 평행 축 신설 명시적 금지, 4축 대응표 |
| 4 관계망·능력치 보정 | **Y** | 오라클 없음 명시 + `product-spec.md:388` 뒤집기 기록(축자 확인) + emergent/PRESET 분리 + v2 tail-append 모듈 + 테스트 3종. **주입점 3파일이 P0 티켓 본문에 파일명으로 확정**(1차 C2(ii)가 요구한 형태). **설계 자체는 축소·희석되지 않았다** — §4.1~4.10 전량 잔존, 착수 시점만 이동 |
| 5 임원진 6종 | **Y** | 도입/보류 판정 + 판정 지점·중첩 규칙 |
| 6 특색·규모·지역병종 | **Y** | 각각 채택/보류 + 발현 조건·수량 |
| 7 v1 패러티 불변 증명 | **N** | **MAJOR-1** — T2 사전 명시 집합에 `WorldActionContext.kt` 누락 |
| 8 규칙 4 적합성 | **Y** | 4열 대조표, 공백지화 draw 0·`city_id ASC` |
| 9 오픈 경로 티켓 수량 | **Y** | 기준 14 실물 대조(`README.md:55-65`, `:63`=56, `:64`=61) + **단일값 20**, 조건부 0. 2차의 조건부 R0이 `01-backbone-micro.md:74-81` 조회로 해소되고 그 근거(**OPENSAM-35 = 격리 7항목·확장점 0**)를 실물 대조해 **참** 확인. §11 UNKNOWN 8건 어느 것도 20의 전제가 아니다 |
| 10 G0 관계 | **Y** | 대체 아님·선행, ADR-LITE-019 유예 조항과 정합 |

## 날조 재발 점검 — **재발 없음**

### `RaiseDisaster.kt:104-127` 한 줄씩 대조

stateCode 3=`:108`·`:122`·`:124` / 4=`:106` / 5=`:107`·`:113`·`:118`·`:123` / 6=`:114` / 7=`:112` / 8=`:117`·`:119` / 9=`:109`·`:125` — **7건 전부 일치.** 라벨 원문 7종도 `DISASTER_TEXT` 값과 **바이트 일치**. 2차가 지적한 창작 라벨("가뭄"·"전염병")은 전량 제거됐고, **날조 사실 자체를 §2.4와 잔여 취약점 1번에 명기**했다.

### 나머지 인용 전수 대조 — 전부 참

`RaiseDisaster.kt:98 BOOMING_RATE` 1·10월 = **0**(`mapOf(1 to 0.0, 4 to 0.25, 7 to 0.25, 10 to 0.0)`, 오귀속 교정 정당) · 월 게이트 `EventStore.kt:171,180,190,197` · **병종연구 700,000 금·700,000 쌀 — 9파일 전수 확인, 2차 reviewer의 650,000이 틀렸다** · `GameConst.kt:41-42` basegold=0/baserice=2000 · `Presets.kt:327` `Deny("병량이 부족합니다.")` · `EngineEventConfig.kt:57` all-or-nothing(`:47` `SELECT … FROM event`에 `world_id` 필터 없음도 확인 — 저자 자진 기재) · `01-backbone-micro.md:74-81` · `prev_income` 3단 체인 · `operation_participants` 컬럼 정의 **0건** · `product-spec.md:170-176`(자기편만)·`:388`(축자) · `README.md:63`/`:64`/`:55-65` · `FrontInfoController.kt:393-394` 우회 · `GetStatValue.kt:53,54-61,63-65,89-91` · `IncomeTick.kt:41,122` · `ProcessSemiAnnual.kt:167-177` · `CheMuljaWonjo.kt:93-94` · `ProcessWarIncome.kt:70-77` · `ChePosang.kt:80-82` · `DaemonLoopConfig.kt:229` · `EventAction.kt:70-74` · `docs/wiki/raw/**` 무수정

**부정확 3건 (날조 아님)**: `ScenarioImporter.kt:810` "**항상**"은 거짓(`:807` `if (scenario.ignoreDefaultEvents)` 가드) · `UpdateNationLevel.kt:145-146` → 실제 `:146-147` · `CheJeungchuk.kt:35-37` → 실제 `:36-38`

**결론: 3차 개정에 날조 없음.** 인용 밀도가 크게 올랐고 검증 가능한 형태가 됐으며, **저자가 reviewer의 수치 오류(650,000)를 실물 재계산으로 정정**했다.

## 2차 지적 9건 반영 — 8 해소 / 1 부분

C1 라벨·근거 **해소** · C2 `prev_income` **해소**((c)·(b) 기각 사유가 T1 규칙·폴백 고정으로 구체적) · M1 네 번째 대가 **해소(초과 달성)** · **M2 `EngineEventConfig` 부분**(추가·재시드 귀속·순서 제약은 됐으나 `WorldActionContext.kt` 여전히 없음 + "항상" 과장) · M3 조건부 수량 **해소** · M4 RIVAL 소급 **해소** · m1·m2·m3 **해소**

**"보고에는 있는데 문서에 없는 것"·"말만 바꾼 것"은 발견되지 않았다** — 보고서 9행 전부 문서 실물에서 확인됐다.

## fix-required

### MAJOR-1 — T2 사전 명시 집합 불완전 (`:940` §7.1-2) ← 문항 7의 유일한 N 사유

`:940`은 "**지금 전부 적는다**"는 완전성 단정과 함께 7개를 열거하지만 `app/game-engine/.../world/WorldActionContext.kt`가 없다.

```kotlin
// logic/.../world/ProcessIncome.kt:215-219
interface ProcessIncomeContext : EventActionContext {
    val pipeline: GeneralActionPipeline
    fun incomeNations(): List<IncomeNation>
    fun applyIncome(result: ProcessIncomeResult)
}
```
`WorldActionContext.kt:106`은 **final class**이고 이 인터페이스의 **유일한 구현체**다. R2 leaf가 도시 원장을 읽고 `recordKv`에 닿으려면 동형 인터페이스가 필요하고 그 구현은 반드시 이 파일을 연다. `LightActionWorld`(`EventAction.kt:100-142`)에는 도시 원장 표면이 없고 `stageCity`는 금·쌀·주둔병 없는 v1 `City`를 스테이징한다 — **범용 seam이 존재하지 않는다.** §7.2 게이트 ③("초과 = 위반")이 R2 착수 즉시 자동 실패한다.

**이것이 같은 실패형의 3연속이다** — 1차 C2(ii) "파이프라인 조립부를 누가 부르는가", 2차 F5 "`WorldActions.register`를 누가 부르는가", 3차 "leaf의 컨텍스트를 누가 구현하는가". 저자는 **지목된 인스턴스만 고쳤을 뿐 자기 산출물을 소비자·구현자까지 밟아 내려가는 절차를 일반화하지 않았다.**

**요구**: `WorldActionContext.kt` 추가 + ① 왜 필요한지(`ProcessIncomeContext` 동형) ② 기존 leaf 디스패치 무영향 근거(메서드 추가가 기존 `as?` 캐스트 경로를 바꾸지 않음) ③ v1-inert 증명 방식. **아울러 R3·R4·R5까지 동일 추적을 돌린 결과를 함께 적을 것** — 요구하는 것은 한 줄이 아니라 그 절차다.

> **사용자 질문에 대한 답 — v2 leaf가 v1 KV 채널(`nation_env`)에 쓰는 것은 one-daemon-write rule을 위반하지 않는다.** 경로가 `ChangeRecorder.recordKv` → `JdbcFlushExecutor` JDBC 배치이고 JPA `EntityManager`가 개입하지 않는다. **world 격리도 깨지 않는다** — `nation_env`는 `(world_id, namespace, key, value)` 키이며(`NationEnvReadIT.kt:43-50`) ADR-LITE-018로 DB 자체가 분리된다. (a) 채택은 이 축에서 문제없다. 문제는 오직 T2 선언 누락이다.

### MAJOR-2 — R2‖R3 병렬 선언인데 같은 산출물을 함께 넓힌다 (`:935`, `:1035`)

`:1035`는 R3를 "R2와 **병렬**"로 적는다. 그런데 `:935`대로 R2는 1·7월 `event` 행의 `ProcessIncome`을 치환하고, R3는 **같은 1·7월 행**을 포함한 4개 행에 leaf를 append한다. 나아가 R3의 등록도 R2가 만드는 `V2WorldActions` 체인(`EngineEventConfig.kt:79-81`)을 경유해야 한다.

CLAUDE.md: *"Parallel worktree families must be **disjoint** — never co-widen the same file … cross-area shared artifacts build **sequentially, creator-then-consumer**."*

**요구**: R2를 생산자·R3를 소비자로 **순차 배치**하거나, `event` 행 편집과 등록 체인을 R2에 단일 귀속시키고 R3는 leaf 구현만 갖게 분리할 것. 병렬 표기를 유지하려면 두 티켓의 파일 집합이 겹치지 않음을 파일 단위로 보일 것.

**부수 — 티켓 크기**: R2가 leaf + 컨텍스트 인터페이스 + 엔진 구현 + 등록 파일 + `EngineEventConfig` 편집 + `event` 2행 치환 + `prev_income` KV + 도시별 봉록 로그로 커져 오픈 경로 최대 티켓이 됐다. **티켓 수 20은 불변인 채 작업량만 늘었다.** P6 베팅 선례가 있어 단독 N 사유로는 보지 않으나, **분해 시 20 → 21이 될 수 있음을 §9.2에 명기할 것.**

### MINOR

- **MINOR-1** 순서 논거 수량이 자기 문서와 불일치 — `:17`·`:1110`·`:1217`은 "emergent 4종 중 3종", §4.5(`:572`)는 **5종 중 4종**, §9.4 P3(`:1129`)은 **4/4**. 세 곳이 전부 다르다. 다행히 오기가 **자기 논거를 약화시키는 방향**이라 −2 판정은 a fortiori 유지. §4.5 기준으로 통일할 것
- **MINOR-2** `ScenarioImporter.kt:810` "항상" 제거 — `:807` `if (scenario.ignoreDefaultEvents)` 가드. v2 시나리오가 이 플래그를 어떻게 두는지가 R2의 12행 재시드 귀속 논거의 전제이므로 **실질 영향 있음**
- **MINOR-3** 오프바이원 — `UpdateNationLevel.kt:145-146` → `:146-147`, `CheJeungchuk.kt:35-37` → `:36-38`

## 확인 불가

- OPENSAM-35 Jira 본문 미조회 — 문항 9 판정은 `01-backbone-micro.md:74-81`(실물 확인)에 근거
- `plans/README.md`·`product-spec.md`·`LEDGER.md`가 미커밋 상태 — 저자와 **동일 워킹트리**에서 대조했으므로 인용은 유효하나 **HEAD 기준 유효성은 미검증**. 커밋 전 재검증 필요
- `attritionLoss` 보간 곡선 밸런스 — 시뮬 없이 판정 불가, §11 U7 및 관측 3종으로 등록됨

## 총평

**5 → 6 → 9.** 3차 개정은 2차의 유일한 치명 결함(날조)을 실물 대조로 완전히 닫았고, reviewer의 오류까지 코드로 정정했으며, *"`path:line`이 있는 것으로는 부족하고 그 줄이 실제로 그 내용인지가 근거다"*를 자기 규율로 문서에 새겼다. **관계망 설계는 축소되거나 희석되지 않았다** — 사용자 결정(능력치 보정)은 §4.6~4.7에 완전한 상태로 남았고 착수 시점만 이동했으며, 그 이동은 순서 논거만으로 독립 성립한다.

남은 N은 하나이고 원인도 하나다 — **자기 산출물을 구현자까지 밟아 내려가지 않는 습관**이 세 번째 자리에서 재현됐다. 수정 자체는 티켓 본문 몇 줄이지만 요구하는 것은 그 줄이 아니라 **R3·R4·R5까지 같은 추적을 돌린 결과**다. 그것과 MAJOR-2를 닫으면 다음 바퀴에서 10/10 · `cleared`가 가능하다.
