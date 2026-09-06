# 작전(Operation) 수직 절편(Phase 4X-B) 스펙 교차 비평 — 통합본 (v2 판정)

- Date: 2026-09-06 (v1 비평 → 같은 날 v2 재판정)
- Target: `docs/superpowers/specs/2026-09-06-operation-vertical-slice.md` (**v2**, REVISED)
- Inherits: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (**v3**) §3 규약. 그 스펙의 비평 파일(`2026-09-06-retinue-spec-critique.md`)은 아직 **v2 판정**(fix-required N1·N2)까지만 실려 있고 v3 재판정은 없다 — 4X-A 의 결정 사항(F1 dirty-free+diff, F3 열 지정 SET NULL, N1 DELETE→CREATE→UPDATE, N2 `removeGeneral` 즉시 가지치기, N3 네 지점, N4 두 벌 프로브, N7 8g)은 여기서 다시 다투지 않는다.
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-B (335-344행), 티켓 표 53행
- Verdict: **fix-required 2건** — v1 의 F1–F4·S1–S12 는 코드로 확인해 F1–F4 전부 **cleared**, S 항목은 S10·S11 두 건만 should-fix 로 남는다(아래 표). 남은 두 건은 v2 가 새로 정한 규칙 안에서 생긴 신규 결함이다: 4X-A v3 가 자기 채널을 **8g(8f 뒤)** 에 두었는데 4X-B 는 자기 채널을 **8d 앞** 에 두면서 「bugok 부모는 그 앞에서 끝난다」 고 적어, 같은 틱 「부곡 편성 → 그 부곡으로 작전 참여」 가 `operation_unit.bugok_id` FK 위반으로 틱 flush 를 영구 차단하고(N1); `UpdateCitySupply` 가 공백지(nation=0)를 **강제 보급(supply=1)** 으로 두므로 공백지를 목표로 선언한 `cut_supply` 는 플레이로 절대 달성되지 않아 기한 실패(사기 −5)만 남는다 — v1 F1 과 같은 부류다(N2). 둘 다 스펙 문장 몇 줄로 닫힌다. 근거는 전부 이 워크트리에서 직접 연 파일:줄이다.

---

## 0. v2 판정표 (v1 항목별)

| 항목 | 판정 | 근거(코드) |
|---|---|---|
| **F1** 생산자 없는 3종 → `DECLARABLE_KINDS` 3종으로 한정 | **cleared** (잔여 구멍 → N2) | 강역·수역·장수 위치의 유일한 쓰기 경로 `apply*Assessment` 호출자는 여전히 `ChangeRecorder.kt`(정의, `:733` 「Explicit daemon assessment only」) + 테스트 5파일뿐, `app/*/src/main`·`logic/src/main`·`infra/src/main` 0건(이번에 `grep --include='*.kt'` 로 재실측). 남은 3종의 술어가 읽는 필드는 실재한다 — `TurnGeneral.cityId :57`·`nationId :56`·`atmos :70`, `City.nationId :82`·`supplyState :94`(`TurnWorldModel.kt`). 게이트 축을 집합으로 바꾼 것(§0 F1·§4 게이트 3)도 맞다. 단 `cut_supply` 의 목표 검증이 「아군 도시」 만 막아 **공백지** 목표를 통과시키고, 그 목표는 `UpdateCitySupply.kt:222` 의 강제 보급 때문에 영원히 `supplyState == 1` 이다 → N2. |
| **F2** 선언자 수명 (`NULL` + `SET NULL (col)` + `removeGeneral` 즉시 NULL) | **cleared** | 열 `INTEGER NULL` 로 정정(§2). `removeGeneral :267-280` 이 `generalPosition.withoutGeneral(id)` `:270` 로 제거 시점에 다른 집합을 즉시 가지치기하는 선례가 있고, `markGeneralDeleted`(`ChangeRecorder.kt:1158`)가 `:1179` 에서 곧장 `world.removeGeneral` 을 부르므로 인테이크·드레인 양쪽의 장수 삭제가 L10·flush 전에 여기를 지난다. 5단계 general DELETE(`JdbcFlushExecutor.kt:150-152`)가 채널보다 앞이지만 DB 가 `SET NULL (declared_by_general_id)` 로 지우고 채널 UPDATE 가 NULL 을 다시 쓰므로 `requireExactlyOneAffected` 도 통과. §8 `OperationFlushIT` 케이스가 이를 단언한다. |
| **F3** board FK vs flush 순서 → 「7단계 UPDATE 뒤 · 8d 앞」 | **cleared** (board 부모) — **단 v2 가 덧붙인 「bugok 부모는 그 앞에서 끝난다」 는 거짓 → N1** | 실행기 순서: 3 `generalCreateMany`/`nationCreateMany` `:131-132` → 5 general DELETE `:150-152` → 6 nation cascade `:159-161` → 7 UPDATE `:164-184` → 8 rank `:188-193` → 8b 경매/베팅/아이콘 `:197-210` → **8d board_post INSERT `:215-217`** → 8e vote `:230-243` → 8c mailbox `:247-252` → 8f 서신 `:257-262`. 「7 뒤 · 8d 앞」 이면 general/nation 부모는 끝났고 `board_post.operation_id` FK 는 같은 틱 「선언 → 글 연결」 에서 만족한다 — 이 부분은 맞다. 그러나 `general_bugok` 은 4X-A v3 §3 「`JdbcFlushExecutor` **8g**(8e vote·8f 뒤; N7)」 에서 INSERT 되므로 이 채널 **뒤** 다(N1). |
| **F4** 순 단위 기한 → 월 단위 + `CHECK (deadline_phase = 1)` + `absoluteTurn` | **cleared** (올림 규칙 미명시 → N4 should-fix) | L10 의 `now`: `runMonthWhen = boundaryDate(nextTurn).phase == 1`(`TurnRunService.kt:363`) 이라 월 파이프라인은 새 날짜가 상순일 때만 돌고, L7 `clock.turnDate`(`MonthlyPipeline.kt:115`) 의 구현 `v1MonthlyClock` 이 `world.setCurrentDate` 를 쓰며(`DaemonLoopConfig.kt:84-92`), 훅은 `world.getState().currentYear/Month/Phase` 를 읽는다(`MonthlyPostUpdateHook.kt:195-205`) → L10 에서 `now.phase == 1` 보장. `absoluteTurn = year×36 + (month−1)×3 + (phase−1)` 은 `ServerClock.advance :82-88`·`dateFromAbsoluteTurn :126-132` 가 쓰는 식과 동일하고 상수는 `GameConst.kt:175-176`(`phasesPerMonth = 3`, `turnsPerYear = 36`). `GameDate` 는 여전히 `Comparable` 아님(`ServerClock.kt:135-141`) — 그래서 `OperationRules.absoluteTurn` 이 필요하다는 스펙 판단 맞음. |
| **S1** 필드명 | **cleared** | `City.nationId :82`·`supplyState :94`, `TurnGeneral.cityId :57`; `supplyState != 0` 선례 `MonthlyPostUpdateHook.kt:108`. 부록 A: `ProvinceControlState.nationId`(`ProvinceControlState.kt:10`)·`stateFor` nullable(`:58`), `WaterControlState.controllingNationId: Long?`·`blockadeState`(`WaterControlState.kt:12,14`). |
| **S2** `UpdateCitySupply` = L5 PRE_MONTH | **cleared** | 시드 행 `pre_month / 9000 → [UpdateCitySupply, ProcessWarIncome]`(`EventStore.kt:158-162`), L5 는 옛 날짜(`MonthlyPipeline.kt:108-109`), 결과는 `world.updateCity(supplyState = …)` 로 즉시 반영(`WorldActionContext.kt:676-689`). 「전월 기준 BFS · 한 달 지연」 을 §5 에 명문화했다. |
| **S3** 권한 원천 | **cleared** (주석 하나) | 엔진 `SecretPermission.check(general)` `:44` 는 `checkSecretLimit = false`, game-api `SecretPermissionReader.of` `:41-48` 는 `checkSecretLimit = true` 로 **같은 raw 함수** `:63-97` 를 부른다. 두 값은 `officerLevel == 1` 에서만 0/1 로 갈릴 수 있는데(`:89-94`) 이 스펙의 두 게이트(`== -1`, `< 2`)는 둘 다 같은 결정을 낸다(`BoardActions.kt:13-17` 이 이미 같은 논증). `myPermission` 표시값이 엔진값과 1 다를 수 있음을 §6 에 한 줄 적어 두면 좋다(차단 아님). 문자열 「국가에 소속되어있지 않습니다.」 `BoardActions.kt:61`, 「권한이 부족합니다. 수뇌부가 아닙니다.」 `:62` 일치. |
| **S4** 로그 채널 | **cleared** | 읽기 `NationLogReadRepository.kt:14` 는 `scope = 'NATION' AND category = 'HISTORY'`; flush 가 `scopeLiteral(draft.scope)`·`category.uppercase()` 로 enum 리터럴로 올린다(`DatabaseHooks.kt:803-804`, `:831-836`) → `"nation"/"history"` 가 그대로 도달. `log_category` enum 에 `ACTION` 존재(`V1__baseline.sql:4`) → `"general"/"action"` 도 유효. |
| **S5** mapCapabilities 삭제 → `rules.kinds` | **cleared** | 지도 판단원 자체를 없앴다(§0 S5·§6). |
| **S6** 계획표 | **cleared** | 계획 `:53` 「56 은 닫지 않고 코멘트 … OPENSAM-228/#494 … 밖(spec v2 §10)」, `:352` 「`battle_plan.operation_id`·`battle_replay.operation_id` 에 작전 키 … 만 기록 … 작전 행은 쓰지 않는다」 로 고쳐져 있다(직접 확인). |
| **S7** 역할 라벨 | **cleared** | §0 S7·§6 `roleLabel` 대응표. |
| **S8** ADR 관계 | **cleared** | §3 마지막 문장 + §10 pin 유보. |
| **S9** 지어낸 수치 | **cleared** (잔여 → N3) | `milestoneDisplayPct` 이름·k/4 1차·22% 행 미그리기(§6·§7). 다만 `supplied` 술어가 **본거지에서도 참**이라 아무 이동 없이 25% 가 붙는다 — N3. |
| **S10** id 미러 네 지점 · 값 > 0 일 때만 | **should-fix → N7** | 접점이 스펙의 「네 지점」 보다 하나 많다: `TurnRunService` 에 키 공급이 **두 곳**(`:404-405` runTick, `:582-583` `currentWorldStateUpdate` = 보류 flush 재시도 경로). 실행기 SQL 은 고정 `jsonb_build_object`(`:537-541`, `:558-562`)라 「값 > 0 일 때만」 은 조건식 SQL 이 필요하다. 세부는 N7. |
| **S11** 국가를 바꾼 참여자 | **should-fix → N5** | 정산 시작 프룬은 맞다. 그러나 S12-d 「진행 중 작전 하나에만」 게이트와 맞물려, 임관·망명한 장수가 **다음 상순까지** 새 국가 작전에 못 들어가고 옛 작전에서 나올 수 있는지도 미정 — N5. |
| **S12** 빈칸 4개 | **cleared** | §5 kind×이정표 표, declared+unit 0 규칙, `removeTroop` 규칙(`InMemoryTurnWorld.kt:482-491`), 한 작전 규칙 모두 적혔다. |
| **Q1** 병합 순서 | 해소 (nit: N9-d) | §9 폴백 명시. |
| **Q2** 4X-C 인터페이스 | 해소 | 계획 `:352` + §10. |
| **Q3** 08 라우트 | 해소 | `web/game/app/game/my-nation/page.tsx`(152행, `:2` 「세력 정보(08 국가 운영 아트보드 …)」, `:18` `MyNationPage`) 존재. 「작전」 문자열 0건 = 새 패널 자리. |
| **Q4** han `cut_supply` | **UNKNOWN 유지 — 다만 범위가 좁아졌다** | `applyCitySupply :215-219` 는 han 에서 `evaluateSupplyReachability(...).suppliedCityIds` 를, 그 외에서 도시 그래프 BFS 를 쓰지만 **1단계 규칙(`isSupplied`) 과 열 의미(0/1)는 동일**하다(`:221-243`). 즉 han 과 che 의 차이는 「어느 도시가 보급되는가」 뿐이고 `supplyState` 의 뜻은 같다. fixture 없음은 그대로 UNKNOWN. |
| **Q5** 잠정 상수 | 판단하지 않음 | `MIN/MAX_DEADLINE_MONTHS = 1/12` 로 단위 바뀜. |
| **Q6** 프로브 범위 | 해소 | `worldState` 행까지 deep-equal(§0 Q6·§8). |

---

## 1. 신규 fix-required

### N1. 4X-A 채널은 8g(8f 뒤)인데 4X-B 채널은 8d 앞이다 — 같은 틱 「부곡 편성 → 그 부곡으로 작전 참여」 가 `operation_unit.bugok_id` FK 위반으로 틱 flush 를 영구 차단한다

- 스펙: §0 F3·§3 「`JdbcFlushExecutor` 위치는 **제약으로**: 『7단계 UPDATE 뒤 · 8d board_post INSERT **앞**』 … general/nation/**bugok** 부모는 그 앞에서 끝난다」; §2 `operation_unit.bugok_id … REFERENCES general_bugok(world_id, id)`; §4 `operationJoin(operationId, role, bugokId?)` 게이트 6 「bugokId 있으면 내 부곡 아님」 은 **메모리** 조회.
- 4X-A v3 §3: 「`JdbcFlushExecutor` **8g**(8e vote·8f 뒤; N7): 표마다 DELETE → CREATE → UPDATE — `retainerDeleteMany → retainerCreateMany → retainerUpdate → bugokDeleteMany → bugokCreateMany → bugokUpdate`」. 즉 `general_bugok` INSERT 는 8f(`JdbcFlushExecutor.kt:257-262`) **뒤** 에 온다.
- 시나리오: 한 틱에 `bugokForm`(메모리에 부곡 id 즉시) → `operationJoin(op, main, bugokId=그 id)` 가 함께 도착한다(인테이크는 클레임한 봉투를 한 번에 디스패치한다 — `TurnRunService.kt:300-301`; 4X-A §3 「편성 → 지휘관 배정(id 즉시 있음)」 이 바로 이 규약이다). 메모리 게이트 6 은 통과. flush: 4X-B 채널이 8d 앞에서 `operation_unit(bugok_id = X)` 를 INSERT → `general_bugok` X 는 8g 에서야 INSERT → FK 위반 → 트랜잭션 예외 → 다음 틱도 같은 payload 로 같은 자리에서 터진다(같은 계열의 기록: `DatabaseHooks.kt:660-663`, 4X-A N1).
- 고침(둘 중 하나를 §3 에 적고 근거를 남겨라):
  1. **두 채널을 모두 「7 뒤 · 8d 앞」 에 나란히**(4X-A → 4X-B 순). 4X-A 표의 부모는 general(3단계 INSERT `:131` / 5단계 DELETE `:150`)뿐이라 8g 라는 자리는 N7 의 라벨 신선도 때문이지 제약이 아니었다. 이 경우 4X-A v3 §3 의 「8g」 를 한 줄 고쳐야 한다(4X-A 재판정 항목으로 넘긴다 — Q7).
  2. 또는 4X-A 는 8g 에 두고 4X-B 를 **그 바로 뒤** 에 두되, `board_post.operation_id` FK 를 `DEFERRABLE INITIALLY DEFERRED` 로(선례: `general_turn`·`nation_turn`·`troop`·`diplomacy` FK, `V32WorldScopeCompletionMigrationTest.kt:505-512`). 같은 틱 「선언 → 글 연결」 은 커밋 시점 검사로 만족한다. 4X-A 변경 0.
  - 어느 쪽이든 `OperationFlushIT` 에 「같은 payload 안에서 부곡 INSERT + 그 부곡을 가리키는 unit INSERT」 케이스를 넣고, `OperationIntakeTest` 같은 틱 시나리오에 「편성 → 참여(bugokId)」 를 추가해라. 「부모는 그 앞에서 끝난다」 는 문장은 표 이름을 **실행기 단계 번호와 함께** 적어라(글자가 아니라 제약으로 — v1 F3 의 취지 그대로).

### N2. 공백지는 강제 보급(supply=1)이다 — 공백지를 목표로 선언한 `cut_supply` 는 플레이로 절대 달성되지 않고 기한 실패(사기 −5)만 남는다

- 스펙: §4 `operationDeclare` 게이트 5 「capture_city·cut_supply 인데 **아군 도시** 『이미 아군 도시입니다.』」 — cut_supply 의 목표에 대한 검증은 이것뿐(공백지 통과). §5 cut_supply `objective = city(target).nationId != nationId && city(target).supplyState == 0`.
- 코드: `applyCitySupply` 1단계 `fun isSupplied(c) = if (c.nationId == 0) true else c.id in suppliedSet`(`UpdateCitySupply.kt:222`, 문서 `:182-184` 「`supply=1 WHERE nation=0` (neutral force-supplied)」, `:246-247` 「Neutral (nation=0) cities are force-supplied … generals there never decay」). 방랑군 해산 뒤 점령 이벤트도 공백지를 `supplyState = 1` 로 만든다(`WorldActionContext.kt:1240`). 점령 시에도 `supplyState = 1`(`ConquerCity.kt:359,410`). 즉 `nationId == 0` 인 도시의 `supplyState` 는 매달 L5 에서 1 로 덮인다.
- 결과: 수뇌부가 공백지를 골라 「보급로 차단」 을 선언하면 `objective` 는 영원히 false → 기한에 `failed` + 참여 장수 전원 `atmos −5`. v1 F1(도달 불가 목표 + 실패만 남음)과 같은 부류이며 §1.2 「생산자가 없는 것은 만들지 않는다」 를 스펙 스스로 어긴다. UI 목표 select 는 도시 전체를 보여주므로 실제로 일어난다.
- 고침: 게이트 5 를 kind 별로 갈라라 — `cut_supply` 는 **적국 도시만**(`nationId ∉ {0, me.nationId}` — 「적국 도시가 아닙니다.」 새 카피, ADR-042 규칙 3 기록), `capture_city` 는 「아군이 아님」(공백지 허용), `relieve` 는 「아군」(현행). §8 `OperationRulesTest` 게이트 순서 표에 「cut_supply + 공백지」 행을 추가해라. 덧붙여 결정할 것: cut_supply 진행 중 목표 도시가 **아군이 되면**(다른 부대가 점령) `objective` 는 `nationId != me` 로 영원히 false 다 — `achieved`(차단의 상위 달성)로 볼지 `closed(nation_gone 류 새 사유)` 로 닫을지 한 줄 적어라. 지금은 기한까지 기다렸다가 실패 벌점이 난다.

---

## 2. 신규 should-fix

- **N3. `supplied` 이정표가 본거지에서 참이다 — 아무것도 안 해도 한 달 뒤 1/4(25%) 가 붙는다.** §5 세 kind 공통 `supplied = unit 중 하나라도 city(general.cityId).nationId == nationId && supplyState != 0`. 참여 장수가 수도에 서 있으면 첫 정산에서 바로 true 이고 단조라 계속 남는다. `departed` 가 false 인 채로 `supplied` 만 true 인 2×2 가 화면에 「이정표 1/4 · 25%」 로 나온다 — §1.4 가 표시 규칙이라 해도 술어 자체가 공허하면 그 숫자는 §1.2 가 금하는 「지어낸 진척」 이다. 고침(하나 고르고 §5 표를 고쳐라): (a) `supplied` 를 **목표 기준**으로 — `arrived` 인 unit 의 도시(=target) 가 `nationId == me && supplyState != 0`(점령 뒤 보급 연결 — 미보급 점령지는 10%/월 감쇠·trust<30 이면 공백화 `UpdateCitySupply.kt:225-243, 264-268` 라 실제 의미가 있다), 또는 (b) unit 의 현재 도시가 target 과 **인접**(`cityConst.byId(id).path.keys`, `:134`)하고 아군·보급. 어느 쪽이든 「이정표 순서 departed → arrived → supplied → objective」 가 실제 진행 순서와 맞게 된다. `OperationRulesTest` 표에 「본거지 대기 → supplied=false」 행을 넣어라.
- **N4. `deadlineFor` 의 올림 규칙과 `remainingMonths` 의 나눗셈 방향이 없다.** §2 「선언 순 + N개월을 다음 상순으로 정규화」 — (Y,3,중순)+1개월 = (Y,4,중순) → 「다음 상순」 은 (Y,5,상순)인가 (Y,4,상순)인가? 전자면 하순 선언은 N+1 개월에 가깝고 후자면 1개월이 한 순이 된다. `CHECK (deadline_phase = 1)` 은 어느 쪽이든 만족하므로 DB 가 잡아 주지 않는다. `remainingMonths(now, deadline)` 도 `now` 가 중순·하순일 때(game-api 는 `world_state.current_*` 를 읽는다) `(abs(deadline) − abs(now)) / 3` 의 floor/ceil 에 따라 0 과 1 이 갈린다. 규칙을 못박아라: 예) `deadlineFor = advance(declaredAt, N×3)` 뒤 `phase != 1` 이면 다음 달 상순으로 **올림**(= `(월 + 1, 1)`, 연 경계는 `ServerClock.advance :82-88` 가 처리), `remainingMonths = floor((abs(deadline) − abs(now)) / 3)`(0 = 「다음 상순 정산」). §8 `OperationRulesTest` 의 `deadlineFor`/`remainingMonths` 표에 상순·중순·하순 선언 × 12월 경계 × `now` 3순 을 넣어라.
- **N5. S11 정산 프룬과 S12-d 「한 작전」 게이트가 맞물려 국가를 바꾼 장수가 다음 상순까지 갇힌다.** 임관·망명(`che_임관` 경로, `ReservedTurnHandlerTest.kt:492`)으로 `nationId` 가 바뀐 장수의 unit 은 다음 L10 까지 남는다(§5). 그 사이 `operationJoin` 게이트 4 「다른 진행 작전에 참여 중」 이 새 국가 작전 참여를 거부하고, `operationLeave` 게이트 1 은 「작전 없음」 만 적혀 있어 **타국** 작전에서 나올 수 있는지 미정이다(`operationJoin` 게이트 1 은 「작전 없음·타국」 이라 대칭이면 못 나온다). 고침: 게이트 4 를 「`operation.nationId == me.nationId` 인 진행 작전」 으로 한정하거나, `operationLeave` 는 국가를 보지 않는다고 적어라(둘 다 권장). `OperationRulesTest` 게이트 표에 한 행.
- **N6. `boardArticle.operationId` 의 접점을 열거하지 않았다 — 「내 국가 작전」 검사 위치가 빈칸이다.** §4 는 인자 추가와 거부 문구만 적었다. 실제 접점: 와이어 `TurnDaemonCommand.BoardArticle`(`TurnDaemonCommand.kt:109-123`, `voteId` 옆), 매퍼 `CommandWireMapper.kt:315-323`(`args.int("operationId")`), 순수 게이트 `BoardActions.addArticle`(`BoardActions.kt:47-65` — `kind == operation` 일 때만 허용, `vote`/`voteId` 대칭), 핸들러 `BoardHandler.handleArticle`(`:43` 호출·`:47-57` columns 에 `operation_id`), 실행기 `boardPostInsertMany` 열 목록(`JdbcFlushExecutor.kt:1653-1656` — 명시 열이라 반드시 넓혀야 한다), 읽기 `BoardReadRepository.kt:44-50` 엔티티·`F4Dto.BoardArticle :695-697`, FE `board/page.tsx:372` select. 디스패처(`TurnDaemonCommandDispatcher.kt:415`)는 불변. **「내 국가의 작전이어야 함」 은 world 조회라 `BoardActions`(순수, world 없음 `:3-7`)에 둘 수 없다** — `BoardHandler` 에서 `addArticle` 의 `Insert` 분기 뒤·`recordBoardPostInsert` 앞에 두고(댓글의 「게시물 읽기」 위치 선례 `:72-73`), 순서를 §4 표에 적어라. `voteId` 처럼 `kind != operation` 이면 `operationId` 를 버린다(`:64` 선례).
- **N7. id 미러 접점은 다섯이고 「값 > 0 일 때만」 은 조건식 SQL 이다.** `TurnRunService` 키 공급이 두 곳(`:404-405` 정규 틱, `:582-583` `currentWorldStateUpdate` — 보류 flush 재시도가 쓴다) + 실행기 두 곳(`:537-541` CAS, `:558-562` 비CAS) + 로더 허용 키(`WorldSnapshotLoader.kt:74-87`) + 시드(`InMemoryTurnWorld.kt:150-165`). 실행기는 고정 `jsonb_build_object` 라 「> 0 일 때만 키」 를 지키려면 `meta || CASE WHEN :max_operation_id > 0 THEN jsonb_build_object('maxOperationId', :max_operation_id) ELSE '{}'::jsonb END` 꼴이 필요하다(또는 `jsonb_strip_nulls`). 메모리 쪽도 같다 — 기존 `recordMaxGeneralId()` 는 init 에서 0 이어도 `state.meta` 에 키를 쓰므로(`:162-165`) 새 키는 `> 0` 가드를 시드에서도 적용해야 `worldStateUpdate` 맵(Q6 프로브 비교 대상)이 오늘과 같다. `WorldSnapshotLoaderOperationTest` 는 4X-A N3 와 같이 flush→재적재 왕복.
- **N8. 「관리자 개입 0 시뮬레이션」 은 3종 중 `capture_city` 하나만 증명한다.** §8 의 che fixture 는 `che_이동`/`che_출병` → 점령 → achieved 와 기한 실패뿐이다(하네스 자체는 있다: `ReservedTurnHandlerTest.kt:96-98, 503-516`, `ReservedTurnWarDrainTest.kt:21-56` ConquerCity 경로, `CheIdong.kt:56-57` destCityID). `cut_supply` 의 목표는 **L5 `UpdateCitySupply` 가 돌아야** 바뀌는데 그 leaf 는 `worldContextFactory` 가 있을 때만 이벤트로 돈다(`TurnRunService.kt:353-359`). 고침: 3도시 che fixture(적 수도 A — B — C, B 점령 → 다음 L5 에서 C `supplyState = 0` → L10 objective)를 하나 더 두거나(`applyCitySupply` 를 직접 호출해도 된다 — `WorldActionContext.kt:665-689` 경로), 「cut_supply·relieve 의 플레이 도달은 순수 표로만 증명, 시뮬 UNKNOWN」 을 §10 에 적어라. 안 적으면 「관리자 개입 0」 관문이 1/3 만 덮은 채 전부 덮은 것처럼 읽힌다.
- **N9. 소소한 빈칸.** (a) `updatedOperations/Units` 는 이번 틱 생성 id 를 제외한다고 적어라 — `DatabaseHooks.kt:126-139, 181-189` 「7. updates — exclude ids created this tick」 규약(같은 틱 declare → join 이 declared→active UPDATE 를 만든다). (b) `removeGeneral` 의 unit 가지치기는 DELETE 를 **내지 않는다**(DB CASCADE, 4X-A N2 「map 과 dirty/created/deleted 집합에서 제거」 와 동일) 고 적어라 — 내면 5단계 뒤 0행 DELETE 가 `lastOps` 에 남는다. (c) `GET /api/operations` 재야 응답 `{operations: [], myPermission}` 에도 `rules` 를 넣어라(선언 폼을 disabled 로 그리려면 `rules.kinds` 가 필요). (d) Q1 폴백 「`bugok_id` 를 V57 로 이월」 은 계획 `:329` 「V57 = 4X-C」 와 충돌 — 「다음 자유 번호」 로 써라. (e) S3 주석: `myPermission` 은 `officerLevel == 1` 에서 엔진값과 1 다를 수 있다(reader `checkSecretLimit = true` `SecretPermissionReader.kt:47`) — 게이트 결정은 같다.

---

## 3. 질문 / UNKNOWN (신규)

- **Q7. 4X-A v3 재판정 의존.** N1 고침 1 은 4X-A v3 §3 「8g」 한 줄을 바꾸는 일이라 4X-A 비평 파일(현재 v2 판정)의 v3 재판정에 함께 실려야 한다. 고침 2 를 고르면 의존이 없다 — 사용자 결정.
- **Q4(유지).** han fixture 없음. 단 위 표대로 열 의미는 동일하므로 「han 에서 다를 수 있다」 는 문장은 「보급 집합의 원천이 다르다(공간 그래프)」 로 좁혀 적을 수 있다.

---

## 4. v2 주장 중 코드로 확인한 것(맞음)

| 스펙 주장 | 확인한 근거 |
|---|---|
| 툼스톤 전파의 자리(`removeGeneral`/`removeNation` 즉시) | `InMemoryTurnWorld.kt:267-280`(`:270` generalPosition 프룬) · `:493-514`(`:501-509` diplomacy 프룬, `:512` nation_turn) · 진입점 `ChangeRecorder.kt:1158/1179`, `:1191/1232` |
| L10 = `MonthlyPostUpdateHook.run` 마지막, 생성자 nullable 의존 | `MonthlyPostUpdateHook.kt:56-66`, `:218-229`(`postUpdateMonthlyTail` 이 마지막 문장) · 배선 `DaemonLoopConfig.kt:397-406` · `MonthlyPipeline.kt:126` |
| 같은 `run()` 안에서 국가 멸망(방랑군 해산)이 정산보다 먼저 | `MonthlyPostUpdateHook.kt:269-270` `markNationDeleted` → `removeNation` 프룬 → 정산은 그 뒤(마지막) |
| `atmos` 변경은 F1 경로(diff+dirty-free) | `:271-272` 선례, `ChangeRecorder.diffCol("atmos")`(v1 표) |
| PG16 열 지정 `ON DELETE SET NULL (col)` | 4X-A 비평 F3 cleared(`docker-compose.yml:25`, Testcontainers `postgres:16-alpine`) — 재검토 안 함 |
| V32 인벤토리 요구 | `V32WorldScopeCompletionMigrationTest.kt:67-71`(모든 물리 표 분류) · `:73-81`(world_id NOT NULL·world_state FK·PK world 선행·인덱스 world 선행) · `:499-503`(UNIQUE world 선행) · `:533-536`(world_state FK 는 3표 외 무액션) · `:654-662` `postV32WorldTables` · `:683-712` `serialIdentityColumns` = `nextval(` 요구 → 엔진 할당 INTEGER 미등록 맞음 |
| `TruncateContract` 등록 | `TruncateContract.kt:46-75` · `TruncateContractTest.kt:42,56`(분류 필수) |
| `kind=operation` 이미 허용, `board_post` nullable 열 추가 안전 | `V53__board_post_kind.sql:5-8`(kind/vote_id), `:18` 복합 FK 선례 · `BoardActions.kt:38-40` |
| 결과 타입 sealed 등록·직렬화기 왕복 | `TurnDaemonCommandResult.kt:104-110` `BoardActionResult` 꼴 (4X-A N8) |
| 읽기 API `.authenticated()` 나열식 | `GameApiSecurityConfig.kt:42-50` |
| 14 회의실 「작전」 탭·글쓰기 select 이미 존재 | `web/game/app/game/board/page.tsx:40-43`, `:372` |
| `Portrait icon-40` 프리셋 존재 | `web/shared/src/Portrait.tsx:25,37` |
| `GeneralResolver.derivePermission` 은 officerLevel 파생(UI 금지 대상) | `GeneralResolver.kt:87-91` |
| `apply*Assessment` main 호출자 0 | `grep -rn --include='*.kt'` 실측: `ChangeRecorder.kt` 6건(정의) + 테스트 5파일 |

---

## 5. 읽은 파일(근거 경로)

`CLAUDE.md` · `docs/superpowers/specs/2026-09-06-{operation,retinue-buqu}-vertical-slice.md`(v2·v3) · `docs/superpowers/reviews/2026-09-06-retinue-spec-critique.md`(v2 판정) · `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`(:43-56, :318-356) · `.ai/decisions.md`(ADR-LITE-049 `:786-815`) · `logic/src/main/kotlin/opensamguk/logic/{actions/intake/SecretPermission,actions/intake/BoardActions,actions/military/CheIdong,tick/MonthlyPipeline,tick/ServerClock,world/UpdateCitySupply,world/ProvinceControlState,world/WaterControlState,event/EventStore,war/ConquerCity}.kt` · `common/src/main/kotlin/opensamguk/common/{constants/GameConst,wire/TurnDaemonCommand,wire/TurnDaemonCommandResult}.kt` · `app/game-engine/src/main/kotlin/opensamguk/engine/{turn/InMemoryTurnWorld,turn/ChangeRecorder,turn/TurnWorldModel,run/MonthlyPostUpdateHook,run/TurnRunService,run/TurnDaemonCommandDispatcher,config/DaemonLoopConfig,boot/WorldSnapshotLoader,world/WorldActionContext,intake/BoardHandler,flush/DatabaseHooks,flush/TruncateContract}.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/{turn/ReservedTurnHandlerTest,turn/ReservedTurnWarDrainTest,flush/TruncateContractTest}.kt` · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/resources/db/migration/{V1,V53}__*.sql` + 디렉터리 목록(최신 V54) · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/{read/SecretPermissionReader,read/NationLogReadRepository,read/BoardReadRepository,security/GameApiSecurityConfig,owner/GeneralResolver,reserve/CommandWireMapper,dto/F4Dto}.kt` · `web/game/app/game/{my-nation,board}/page.tsx` · `web/shared/src/Portrait.tsx`. gradle 실행·`.env*` 열람 없음.

---

<details>
<summary><b>부록 — v1 비평(2026-09-06, 역사 기록·원문 보존)</b></summary>

# 작전(Operation) 수직 절편(Phase 4X-B) 스펙 교차 비평

- Date: 2026-09-06
- Target: `docs/superpowers/specs/2026-09-06-operation-vertical-slice.md` (v1 DRAFT)
- Inherits: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (v2) §3 엔진 상태·flush 규약. 그 스펙의 1차 비평(`2026-09-06-retinue-spec-critique.md`)에서 정리된 항목(F1 dirty-free+diff 경로, F2 메모리 세계 상태, F3 열 지정 SET NULL, F5 L10 배치+적색 프로브, S2 V32 등록, S9 상수 미단언, S10 결과 타입)은 여기서 다시 다투지 않고 그대로 적용한다.
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-B (335-344행), 티켓 표 53행
- Verdict: **fix-required 4건** — 이대로 구현하면 han 지도에서 선언 가능한 4종 중 3종의 목표가 플레이로는 영원히 도달할 수 없어 기한 실패만 남고(F1), 선언자 장수가 사라지는 순간 general DELETE 또는 다음 작전 UPDATE 가 제약 위반으로 틱 flush 를 터뜨리며(F2), 같은 틱 「선언 → 회의실 글 연결」 이 board_post INSERT 순서 때문에 FK 위반으로 flush 를 터뜨리고(F3), 순(旬) 단위 기한을 월 경계에서만 평가해 기한 정산이 최대 2순 늦고 「남은 순」 이 0/음수로 표시된다(F4). 근거는 전부 이 워크트리에서 직접 읽은 파일:줄이다.

---

## 0. 스펙 주장 중 코드로 확인한 것(맞음)

| 스펙 주장 | 확인한 근거 |
|---|---|
| 인테이크 명령 → 엔진 핸들러 → `ChangeRecorder` 채널 → `JdbcFlushExecutor`, 게이트 순서 「장수 없음 → 접속 제한 → 입력 → 상태」 | `BoardHandler.kt:36-60` (장수 없음 `:37` → `AccessLogThrottle` `:39-41` → 입력/권한은 `BoardActions.addArticle`), `AccessLogThrottle.kt:12-29` |
| `kind=operation` 은 이미 허용된다 | `BoardActions.kt:40` `KINDS = setOf(general, vote, operation, notice)`, `V53__board_post_kind.sql:8` CHECK |
| 와이어: `TurnDaemonCommand` + `CommandWireMapper` allowlist + 디스패처 | `CommandWireMapper.kt:81-83`(allowlist) · `:315-335`(boardArticle/Comment/Read 매핑) · `TurnDaemonCommandDispatcher.kt:415-417` · `TurnDaemonCommand.kt:111-123`(`BoardArticle` 에 `kind`/`voteId` 선례) · `TurnDaemonCommandResult.kt:30-32`(sealed, `(type, ok)` 키 폴리모픽 직렬화기) `:105-110`(`BoardActionResult`) |
| PostgreSQL 16 → 열 지정 `ON DELETE SET NULL (col)` 사용 가능 | `docker-compose.yml:25`, `docker-compose.production.yml:7` `postgres:16-alpine`; Testcontainers 도 `postgres:16-alpine`(`WorldStateRepositoryIT.kt:71` 등), 라이브러리 `gradle/libs.versions.toml:5` 1.20.4 |
| `nation(world_id,id)` / `city(world_id,id)` / `general(world_id,id)` 복합 PK 존재 | `V32__complete_world_scope_expand.sql:184,188,192` |
| V32 인벤토리: `postV32WorldTables` 등록, world_state FK 무액션, identity 아님 → `serialIdentityColumns` 미등록 | `V32WorldScopeCompletionMigrationTest.kt:654-662`(목록) · `:533-535`(world_state FK 는 3표만 CASCADE, 나머지 무액션) · `:683`(`serialIdentityColumns`) |
| `board_post` 에 nullable `operation_id` 를 더해도 기존 INSERT 경로는 깨지지 않는다 | flush INSERT 는 명시 열 목록(`JdbcFlushExecutor.kt:1651-1656`), 핸들러 columns 는 map(`BoardHandler.kt:44-55`), `world_id` 는 executor 주입(`:1638`). 읽기 쪽은 `BoardReadRepository.kt:45-46`(`kind`) · `F4Dto.kt:695-697`(`BoardArticle.kind/voteId`) 에 `operationId` 를 더하면 된다 |
| 툼스톤 `deletedNationIds`/`deletedGeneralIds` 는 drain 시점에 보인다 | `InMemoryTurnWorld.kt:604-605`(`consumeDirtyState`) · `removeGeneral` 이 `generalPosition.withoutGeneral(id)` 로 다른 집합에 전파하는 선례 `:267-270` · `markNationDeleted` 는 `ReservedTurnHandler.kt:458,732`·`RulerSuccessionHandler.kt:138`·`MonthlyPostUpdateHook.kt:270` 에서 호출되어 플레이 중 멸망이 툼스톤을 만든다 |
| che 지도는 `provinceControlSnapshot() == null` | `WorldSnapshot` init 이 spatial 스냅샷을 `han-world-v3` 에만 허용(`InMemoryTurnWorld.kt:39-43`), 로더는 그 외 지도에서 topology null(`WorldSnapshotLoader.kt:176`) |
| `general.cityId` 는 메모리 필드, `atmos` 는 diff 대상 | `TurnWorldModel.kt:57`(`cityId`) `:70`(`atmos`) · `ChangeRecorder.kt:363`(`diffCol("atmos")`) |
| 틱 순서 인테이크 → 월 경계 → 단일 flush, L10 = `MonthlyPostUpdateHook`, 월 경계는 상순 1회 | `TurnRunService.kt:299-300` → `:302-364` → `:383-414`; `:358` `runMonthWhen = phase == 1`; 훅 배선 `DaemonLoopConfig.kt:397-406`(생성자 nullable 의존 → 테스트에서 직접 구성 가능) |
| `ServerClock.advance(date, turns)` 존재, L10 에서 `now` 는 `world.getState()` 의 새 날짜 | `ServerClock.kt:82-88`; L7 clock 람다가 `world.setCurrentDate` 를 쓴다(`DaemonLoopConfig.kt:88-90`), 훅도 그 값을 읽는다(`MonthlyPostUpdateHook.kt:195-198`) |
| 「관리자 개입 0 시뮬레이션」 은 기존 하네스로 만들 수 있다 | `ReservedTurnHandlerTest.kt:96-102`(che 소형 `WorldSnapshot` + `ReservedTurnHandler.handle`) · `:503-516`(`ReservedTurn("che_이동", {"destCityID":9})` 실제 예약 경로) · `ReservedTurnWarDrainTest.kt:21-69`(`che_출병` → `ConquerCity` 점령). 적색 프로브도 4X-A 와 같은 방식으로 훅을 직접 두 번 돌려 만들 수 있다 |
| 로드맵·티켓 원칙 인용 | `docs/design/roadmap.md:49-60`(「작전 목표와 교전」 흐름·「기한, 군량, 사기와 기회」), `:21`(「작전 시작부터 종료·점령·정산까지 관리자 개입 0」); Jira OPENSAM-56(상태 「할 일」) 체크리스트 3-a~3-l 및 3-e 「주공·조공·정찰·보급·예비」, 2026-08-27 코멘트 7원칙 — 스펙 헤더 인용과 일치 |
| 08·14 아트보드 문구 | `Nation.body.html`: 「작전 진행 1 · 계획 1 / 낙양 공략 진행 중 기한 6순 · 남은 4순 / 목표 · 낙양 점령. 대체 목표 · 호뢰관 도로 확보 및 보급로 차단 / 진척 38% / 통제권 호뢰관 도로 22% / 하후돈 본대 · 악진 별동 · 순욱 호송 / 작전 흐름 목표 선언 → WEGO 봉인 → 이동·통제 → 접촉 시 전투 → 정산」; `Council.body.html`: 탭 「전체 표결 작전 공지」, 글 「작전 낙양 공략 · 3月 중순 봉인 전 부대 편성 마감」 |
| 스냅샷 shape | `ProvinceControlSnapshot.knownProvinceIds/stateFor` (`ProvinceControlState.kt:37,58`), `WaterControlSnapshot.knownWaterZoneIds/stateFor` (`WaterControlState.kt:58,73`), `GeneralPositionSnapshot.statesByGeneralId` (`GeneralPositionState.kt:39`) |
| V55/V56/V57 번호 | 계획 `:329`, 이 브랜치 최신 마이그레이션 V54(`infra/src/main/resources/db/migration/`, V52 없음) |
| 읽기 API 는 `GameApiSecurityConfig` 등록이 필요하다(스펙이 이미 명시) | `GameApiSecurityConfig.kt:41-49` 나열 경로만 `.authenticated()`, 나머지 `permitAll` |

---

## 1. fix-required

### F1. han 지도에서 선언 가능한 `secure_route`·`pass_through`·`blockade` 는 플레이로 도달할 수 없다 — 선언하면 기한 실패(사기 −5)만 남는다

- 스펙: §1.2·§4 게이트 3 「`provinceControlSnapshot()==null` 이면 4종 거부」 — 즉 han-world-v3 에서는 4종 선언 허용. §5 `arrived`/`objective` 가 `provinceControlSnapshot().stateFor(...)`, `waterControlSnapshot().stateFor(...)`, `generalPositionSnapshot()?.statesByGeneralId[...]` 를 읽는다.
- 코드: 이 세 상태의 **유일한 쓰기 경로** `applyWaterControlAssessment`/`applyProvinceControlAssessment`/`applyGeneralPositionAssessment` 는 문서부터 「Explicit daemon assessment only; no boot/scenario/shore ownership producer」(`ChangeRecorder.kt:733`) 이고, 저장소 전체에서 호출자는 **테스트 5파일뿐**이다(`WaterControlPersistenceIT`·`SpatialStatePersistenceIT`·`SpatialStateRecorderTest`·`WaterControlRecorderTest`·`TurnRunServiceFlushRecoveryTest`; `app/*/src/main`·`infra/src/main`·`logic/src/main` 0건). 이동(`che_이동`)·출병·점령 어느 명령도 강역 통제·수역 통제·장수 위치 행을 만들지 않는다. 로더는 `province_control`/`water_zone_control`/`general_spatial_position` 행을 읽어 스냅샷에 싣지만(`WorldSnapshotLoader.kt:180-206`) 그 행을 플레이가 쓰지 않으므로 항상 비어 있다.
- 결과: han 지도에서 `secure_route`/`pass_through`(`arrived` = 장수 위치 노드, `objective` = 강역 통제)·`blockade`(수역 통제) 를 선언하면 `arrived`/`objective` 가 **절대 true 가 되지 않고** 기한에 `failed` + 참여 장수 전원 `atmos −5` 만 남는다. 스펙 §1.2 「지도가 못 주는 것은 만들지 않는다」 의 의도와 정반대이고 로드맵 `:21` 「관리자 개입 0」 관문을 스펙 스스로 어긴다(관리자가 assessment 를 넣어야만 진척한다). 반면 `cut_supply` 의 술어는 도시 필드(`nationId`·`supplyState`)만 읽는데 게이트 3 이 이것까지 지도 미지원으로 막는다 — 술어와 게이트가 서로 다른 축을 본다.
- 고침: 이 절편의 선언 가능 종류를 **살아 있는 생산자가 있는 것**으로 한정하라 — `capture_city`·`relieve`·`cut_supply`(도시 그래프 보급, 모든 지도). `secure_route`/`pass_through`/`blockade` 는 열·CHECK 는 남기되 선언 게이트에서 「이 지도에는 통제권 데이터가 없습니다」 가 아니라 「통제권 생산자(beyond-che W2/W3, `2026-08-22-beyond-che-...plan.md:126-133`)가 붙기 전」 사유로 거부하고, 게이트를 「스냅샷 != null」 이 아니라 「생산자 존재」 로 정의하라. 게이트 3 에서 `cut_supply` 를 빼라. §8 `OperationRulesTest` 6 kind 표는 도달 가능 3종 + 예약 3종(거부 사유)으로 갈라라.

### F2. `declared_by_general_id` 의 수명이 DDL·메모리 양쪽에서 깨진다 — 선언자 삭제가 틱 flush 를 터뜨린다

- 스펙: §2 「`declared_by_general_id INTEGER NOT NULL` — FK general CASCADE 아님(`ON DELETE SET NULL (declared_by_general_id)` 로 NULL 허용)」, §3 툼스톤 전파는 `deletedNationIds` 의 작전·`deletedGeneralIds` 의 **unit** 만 다룬다. 채널은 4X-A §3 상속 「행 단위 — 메모리 최종 상태를 통째로 UPDATE」.
- 코드: (a) PostgreSQL 은 NOT NULL 열에 `ON DELETE SET NULL` DDL 을 받아들이지만 실제 부모 DELETE 시 not-null 위반을 던진다 → 선언자 장수의 5단계 `general` DELETE(`JdbcFlushExecutor.kt:149`)가 실패 → **틱 전체 flush 실패**(FLUSH_RETRY). (b) 열을 NULL 허용으로 고쳐도, 스펙은 `deletedGeneralIds` 를 받아 `operation.declaredByGeneralId` 를 메모리에서 NULL 로 만들지 않는다. 다음 정산에서 이정표 하나가 바뀌어 작전 행이 통째로 UPDATE 되면 DB 가 SET NULL 로 지운 자리에 **삭제된 장수 id 를 다시 쓴다** → `(world_id, declared_by_general_id) REFERENCES general` 위반 → flush 실패. 5단계 DELETE 가 8x 채널보다 먼저 실행되므로(`:149` vs `:195-254`) 같은 틱에도 난다. 엔진 모델은 이미 `declaredByGeneralId?` 로 nullable 이라(§3) DDL 만 어긋난 상태다.
- 고침: 열을 `INTEGER NULL` 로. §3 툼스톤 규칙에 「`deletedGeneralIds` 의 장수가 선언자인 작전은 `declaredByGeneralId = null` 로 바꾸고 UPDATE 를 기록한다」 를 더하라(`removeGeneral` 의 `generalPosition.withoutGeneral` 선례 `InMemoryTurnWorld.kt:270` 처럼 제거 시점에 하거나, 4X-A 처럼 `consumeDirtyState` 에서). `OperationFlushIT` 에 「선언자 DELETE 와 같은 틱 작전 UPDATE → `world_id` 보존·`declared_by_general_id` NULL·flush 성공」 케이스를 넣어라. 읽기 API `declaredBy` 는 null 을 허용해 표시하라.

### F3. `board_post.operation_id` FK 와 flush 순서가 충돌한다 — 같은 틱 「선언 → 회의실 글 연결」 이 FK 위반이다

- 스펙: §3 「`JdbcFlushExecutor` **8f**(8e 뒤)」 에 작전 INSERT, §2 `board_post.operation_id → operation(world_id,id)` FK, §4 `boardArticle.operationId` 는 「내 국가 작전이어야 함」(메모리 map 조회), §8 「같은 틱 선언→참여(id 즉시)」.
- 코드: board_post INSERT 는 **8d**(`JdbcFlushExecutor.kt:212`)이고 스펙의 작전 채널은 그 뒤다. 같은 틱에 `operationDeclare`(메모리에 id 즉시) → `boardArticle(kind=operation, operationId=그 id)` 가 들어오면 flush 는 8d 에서 `operation_id = X` 를 INSERT 한 뒤 8f 에서 `operation` X 를 INSERT 한다 → FK 위반 → 틱 flush 실패. 4X-A 의 결정(같은 틱 연쇄를 메모리에서 허용, F2 해결)이 바로 이 경로를 열어 놓는다. 덧붙여 라벨이 이미 쓰인다: **8e = 투표 채널**(`:226`), **8f = 외교 서신 채널**(`:254`) — 4X-A 의 「8e」 와 이 스펙의 「8f」 는 둘 다 기존 단계와 이름이 겹친다.
- 고침: 작전 채널의 **위치를 글자가 아니라 제약으로** 적어라 — 「7단계 UPDATE 뒤·8d board INSERT **앞**」(general/nation/bugok 부모는 그 앞에서 이미 INSERT/DELETE 끝남). 대안은 FK 를 `DEFERRABLE INITIALLY DEFERRED` 로 두거나 핸들러가 「이번 틱에 만든 작전은 글에 연결할 수 없습니다」 로 거부하는 것인데, 첫 번째가 4X-A 규약과 가장 잘 맞는다. `OperationFlushIT` 에 「같은 틱 선언 + 연결 글」 케이스를 넣고, 4X-A 재발행 때 8e/8f 라벨을 함께 바로잡아라(비평 범위 밖이라 여기서는 지적만).

### F4. 기한은 순(旬) 단위인데 정산은 월 경계(상순)에서만 돈다 — 기한 정산이 최대 2순 늦고 「남은 순」 이 0/음수로 표시된다

- 스펙: §2 「기한 순 = 선언 순 + N순(`ServerClock.advance`)」, `MIN_DEADLINE_TURNS = 3`; §5 「`now >= deadline`(연·월·순 비교) → failed」, relieve 「기한 순에 도달했을 때 아군이면 true」; §6 `remainingTurns` 는 「`ServerClock` 순 산술」; 배치는 L10.
- 코드: L10 은 `runMonthWhen = { phase == 1 }`(`TurnRunService.kt:358`) 인 월 경계에서만 돈다(`MonthlyPipeline.kt:126`). 중순·하순 경계는 `advanceNonMonthlyBoundary`(`:363-367`)로 날짜만 올린다. `GameDate` 에는 비교 연산이 없고(`ServerClock.kt:135-141` `Comparable` 아님) 「순 산술」 헬퍼도 없다(`advance` 만 `:82`).
- 결과: 선언 (Y,3,중순)+3순 → 기한 (Y,4,중순). (Y,4,상순) 정산에서는 `now < deadline`, 다음 정산 (Y,5,상순)에서야 failed — 2순 지연. 그 사이 UI 는 `remainingTurns` 0 → −1 → −2 를 「진행 중」 상태로 보여 준다(아트보드 「남은 4순」 의 원천이 거짓이 된다). relieve 의 「기한 순에 도달했을 때」 는 기한이 상순이 아니면 관측 자체가 안 된다.
- 고침: 둘 중 하나를 골라 §5·§6 에 적어라. (i) 기한을 **월 단위**로 두고 `deadline.phase = 1` 로 정규화(선언 순 + N순 → 다음 상순으로 올림, `MIN/MAX_DEADLINE` 을 월로 표기) — 정산 위치 불변. (ii) 기한 전이만 매 틱 평가(`advanceNonMonthlyBoundary` 옆 seam)하고 이정표 재계산은 L10 유지 — 이 경우 적색 프로브가 틱 경로까지 덮어야 한다. 어느 쪽이든 `GameDate` 비교자(절대 순 = `year*36 + (month-1)*3 + (phase-1)`, `GameConst.kt:175-176`)와 `remainingTurns` 정의를 `logic`(`OperationRules` 또는 `ServerClock`)에 명시하고 순수 함수 표에 넣어라.

---

## 2. should-fix

- **S1. 필드명·타입이 실제 모델과 다르다.** 도시 소유는 `city.nation` 이 아니라 `nationId`(`TurnWorldModel.kt:82`, `LogicEntities.kt:69`), 보급은 `city.supply` 가 아니라 `supplyState: Int`(`TurnWorldModel.kt:94`; 참/거짓은 `!= 0`, 선례 `MonthlyPostUpdateHook.kt:108`). `WaterControlState` 에는 `nationId` 가 **없다** — `controllingNationId: Long?`·`contestingNationIds`·`blockadeState(OPEN/CONTESTED/BLOCKED)`(`WaterControlState.kt:8-15`)이고 `stateFor` 는 nullable(`:73`, `ProvinceControlState.kt:58` 도). `GeneralPositionState.node` 는 sealed `StrategicNodeRef`(`LandProvince(id)`/`WaterZone(id)`, `StrategicTopology.kt:37-48`)라 `(node as? LandProvince)?.id == targetProvinceId` 로 비교해야 한다. §5 blockade 「통제 = 달성」 은 `controllingNationId == nationId.toLong()` 인지 `blockadeState == BLOCKED` 까지 요구하는지 정해라(F1 로 이 절편 밖이 되더라도 술어 표는 남는다). §10 의 UNKNOWN 은 이 줄로 닫힌다.
- **S2. `UpdateCitySupply` 는 L9 가 아니라 L5(PRE_MONTH, 옛 날짜) 다.** 시드 행 `pre_month / 9000 → [UpdateCitySupply, ProcessWarIncome]`(`EventStore.kt:158-161`), L5 는 `PRE_MONTH`(`MonthlyPipeline.kt:109`), 행은 DB `event` 표에서 읽고 비어 있으면 기본값(`EngineEventConfig.kt:44-60`). 결과는 `world.updateCity(supplyState=…)` 로 메모리에 즉시 반영(`WorldActionContext.kt:669-681`)되므로 「L10 마지막 단계에서 이미 갱신돼 있다」 는 결론은 맞다. §5·§10 문구를 L5 로 고치고, 핀 테스트는 「L5 → L10」 을 단언하라. 의미상 주의: `supplied` 는 **옛 날짜 기준 BFS** 이고 같은 달 L10 안의 소유 변화(`checkWander` 해산 → 점령 이벤트 `MonthlyPostUpdateHook.kt:269-288`)를 보지 않는다 — 한 달 지연을 규칙으로 적어라.
- **S3. 권한 게이트의 원천이 엔진과 UI 에서 다르다.** `SecretPermission.check` 는 −1/0..4 를 돌려주며(`SecretPermission.kt:61-97`) 대사(ambassador)/감찰(auditor)은 낮은 관직도 4/3(통과), `noTopSecret`/`noChief` 벌점은 수뇌부도 1 로 깎는다(`:103-108`). game-api `GeneralResolver.derivePermission` 은 officerLevel 만 본다(`GeneralResolver.kt:87-91`; `BoardController.kt:107` 이 수뇌부 수를 이걸로 센다). ADR-049 (7) 「비활성은 점선 + 사유」 를 지키려면 UI 의 disabled 판정이 엔진과 같은 원천(`SecretPermission` raw 진입점 `:63`, game-api 읽기 헬퍼 `SecretPermissionReader` `:10-11`)을 읽어야 한다 — 스펙에 명시. 재야 게이트 1 은 `check == -1` 과 같으니 board 문자열 「국가에 소속되어있지 않습니다.」(`BoardActions.kt:61`, 띄어쓰기 없음)·「권한이 부족합니다. 수뇌부가 아닙니다.」(`:62`) 를 재사용하거나 새 문구를 ADR-042 규칙 3 대로 「의도된 새 카피」 로 기록하라.
- **S4. 「국가 기록」 채널이 UI 에 안 뜬다.** 국가 로그 읽기는 `scope='NATION' AND category='HISTORY'` 뿐이다(`NationLogReadRepository.kt:14`). 엔진 선례도 `scope="nation", category="history"`(`ProcessNationCommand.kt:768`). 스펙 §4·§5 의 `category="action"` 국가 기록은 아무 화면에도 나오지 않는다 → `history` 로 바꾸거나 읽기 경로를 추가하라. 「참여 장수 개인 기록」 도 4X-A S4 대로 `scope="general", category="action", generalId` 를 적어라.
- **S5. `mapCapabilities` 판단원 후보가 틀렸다.** 「`province_control`/`water_zone_control` 행 > 0」 은 han 지도에서도 0 이다(F1: 생산자 없음) — 반면 엔진 스냅샷은 지도가 `han-world-v3` 이면 행 0 이어도 존재한다(`WorldSnapshotLoader.kt:176`, `InMemoryTurnWorld.kt:39-43`). 행 수로 판단하면 UI 는 막고 엔진은 허용하는 불일치가 생긴다. 판단원은 세계의 지도 이름(`ActiveWorldMap.requireName(config, meta) == "han-world-v3"`, game-api 가 `world_state.config/meta` 로 읽을 수 있다)이고, F1 채택 뒤에는 「생산자 존재」 플래그로 바뀐다. §10 두 번째 UNKNOWN 은 이 줄로 닫힌다.
- **S6. 계획표와 스펙이 어긋난다.** 계획 `:53` 은 4X-B 가 「OPENSAM-56 닫기」 와 「OPENSAM-228/#494 회의 thread·결정 기록도 여기서 닫는다」 인데 스펙은 56 코멘트만(헤더)·표결 결정 기록 밖(§7·§10). 4X-A S1 선례대로 계획표를 스펙에 맞춰 고쳐라. 또 계획 `:352`(4X-C) 「캠페인 정산을 … 4X-B 작전 진척에 반영」 은 「진척은 읽기다」(§1.1) 와 맞물릴 자리가 없다 — 4X-C 가 무엇을 쓸 수 있는지(이정표 추가? 이벤트?) 인터페이스를 한 줄 적거나 계획 문구를 고쳐라.
- **S7. 역할 라벨.** Jira 3-e 는 「주공·조공·정찰·보급·예비」(스펙 일치), 아트보드 08 은 「본대 · 별동 · 호송」 이다(ADR-049 UI 정본). `roleLabel` 대응(main=본대? flank=별동? convoy=호송?)을 §6 응답에 못박고 S2 대조표에 기록하라 — 안 하면 화면과 티켓이 서로 다른 말을 한다.
- **S8. 관련 ADR 과의 관계를 적지 않았다.** ADR-LITE-032/035/037(`.ai/decisions.md:372-449`)은 `operationId` 를 작전층 리플레이 키로, `phases[]` 7값(APPROACH…AFTERMATH)을 「한 작전 안의 순차 단계 축」 으로 동결했다. 이 스펙의 이정표 4개는 그 축이 아니다 — 「이정표 ≠ `phases[]`, `operation.id` = ADR-032 `operationId`」 를 §3 에 한 줄 적어라(4X-C `battle_plan.operation_id` 가 그 키를 쓴다). ADR-LITE-045(`:710`, `:723-724`)는 「진행 중 작전은 network revision 을 pin」 한다고 했는데 `target_province_id`/`target_water_zone_id` 에는 topology pin 이 없다(스냅샷은 `topologyRevision/topologyHash` 를 갖는다 `ProvinceControlState.kt:32-33`). F1 로 이 절편 밖이 되면 「pin 은 생산자와 함께 온다」 를 §10 에 남겨라.
- **S9. 지어낸 수치가 화면·API 로 나가는 자리.** `progressPct = k×25` 는 표시 규칙이라고 §1.4 가 밝혔지만 §6 은 이를 숫자 필드로 내보내고 아트보드는 「진척 38%」 로 그린다 — 응답 이름을 `milestoneDisplayPct` 같이 파생임이 드러나게 바꾸거나 UI 1차 표기를 「이정표 k/4」 로 고정하고 %는 보조로 두어라. 아트보드의 「통제권 호뢰관 도로 22%」 행은 §1.2 대로 **그리지 않는다** 를 §7 에 명시하라. `remainingTurns` 는 F4 결정 전까지 정의가 없다. 기한 범위·상한 게이트(③ 입력)는 `rules` 로 노출되는 잠정 상수라 허용되지만, 테스트는 상수 값을 단언하지 말고 입력으로만 써라(4X-A S9 그대로).
- **S10. id 미러의 영속 경로가 세 파일이다.** 「`maxGeneralId` 미러(meta 키)」 는 메타 jsonb 통째 쓰기가 아니다 — 페이로드 키(`TurnRunService.kt:404-405` `max_general_id`), SQL 의 고정 `jsonb_build_object('maxNationId', 'maxGeneralId')`(`JdbcFlushExecutor.kt:515,540,561`), 로더의 `snapshotKeys`(`WorldSnapshotLoader.kt:86`) 세 곳을 같이 넓혀야 재기동 뒤 고수위가 산다. 안 넓히면 4X-A §3 「삭제된 최대 id 재사용 없음」 이 재기동에서 깨진다. 넓히면 행 0 세계의 `world_state.meta` 에 `maxOperationId` 키가 생겨 오늘과 바이트가 달라진다 — ADR-049 「바이트 동일」 의 명시 예외로 적거나 값 > 0 일 때만 쓰도록 하라. (4X-A 도 같은 세 파일이 필요하다 — 재발행 시 함께.)
- **S11. 국가를 바꾼 참여자.** 장수가 하야·임관(`che_임관` 경로 `ReservedTurnHandlerTest.kt:478`)으로 `nationId` 를 바꿔도 unit 은 남는다. 적국으로 넘어간 장수가 목표 도시에 서 있으면 옛 국가의 `arrived`/`supplied` 가 true 가 되고, 기한 실패 시 남의 나라 장수 `atmos` 가 깎인다. 정산 시작에 `general.nationId != operation.nationId` 인 unit 을 제거(DELETE 기록)하거나 술어에서 제외하는 규칙을 §5 에 넣고 순수 함수 표에 한 행을 더해라.
- **S12. 술어·같은 틱 규칙의 빈칸.** (a) `departed` 의 「또는 이미 목표 도시에 있음」 은 목표 도시가 없는 3종에 정의가 없다; blockade 는 `arrived` 가 위치가 아니라 통제라 `departed=false, arrived=true` 가 가능하다 — kind × 이정표 4 표를 §5 에 직접 그려라(그 표가 `OperationRulesTest` 의 입력이다). (b) `declared` 에 unit 0 이면 이정표는 건너뛰지만 전이 블록은 돌아 기한에 `failed` 가 된다(사기 효과 0) — 의도면 적어라. (c) 같은 틱 `operationJoin → operationLeave` 는 4X-A `removeTroop` 규칙(이번 틱 생성 행은 DELETE 기록 없음, `InMemoryTurnWorld.kt:482-491`)을 따른다고 §3 에 적어라. (d) 한 장수가 여러 작전에 동시에 참여할 수 있는지(UNIQUE 는 작전당 1회만 막는다) 정해라.

---

## 3. 질문 / UNKNOWN

- **Q1. 4X-A 병합 순서 의존.** §5 「`retainerMonthly?.settle` 뒤」 와 §8 「순서 핀」 은 4X-A 가 먼저 머지돼야 성립한다(V56 도 `general_bugok` FK 로 V55 뒤). 4X-B 가 먼저 갈 경우의 대체(훅 인자 없이 마지막 단계, FK 는 V57 로 이월)를 §9 에 적을지 — 사용자 결정.
- **Q2. 4X-C 인터페이스.** 계획 `:352` 「작전 진척에 반영」 이 무엇을 쓰는지(S6). 4X-C 스펙 전까지 UNKNOWN.
- **Q3. 08 패널의 실제 페이지.** `/game/nation/page.tsx` 에는 「작전」 문자열이 0건이고 `/game/my-nation` 도 있다 — 어느 라우트가 08 국가 운영인지 스펙 §7 「`/game/nation` 또는 08 대응 화면」 은 미정이다(UNKNOWN, 구현 시 S1 매핑표로 확인).
- **Q4. han 지도의 도시 보급.** `DaemonLoopConfig.kt:265` 는 `hanSpatialSupplyProvider.network(..., world.waterControlSnapshot())` 로 han 보급망을 만든다 — `cut_supply` 가 han 에서 무엇을 읽는지(도시 그래프 BFS 와 공간 그래프의 판정 결합 `WorldActionContext.kt:628-664`)는 확인하지 않았다(UNKNOWN). che 에서는 도시 그래프뿐이라 §5 술어가 그대로 맞는다.
- **Q5. 잠정 상수의 규모.** `MAX_DEADLINE_TURNS = 36`(1년)·`MAX_UNITS = 12`·`FAIL_ATMOS_LOSS = 5` 는 스펙이 잠정으로 표시했으므로 판단하지 않는다. F4 채택안에 따라 단위(순/월)가 바뀐다.
- **Q6. 적색 프로브의 비교 범위.** §8 「행 0 산출물 동일」 이 `DirtyState`·recorder 패치·로그만 보는지 `world_state` 행(S10 의 meta 키)까지 보는지 미정 — 4X-A 의 프로브 정의를 그대로 쓴다면 같은 빈칸이다.

---

## 4. 읽은 파일(근거 경로)

`CLAUDE.md` · `docs/superpowers/specs/2026-09-06-{operation,retinue-buqu}-vertical-slice.md` · `docs/superpowers/reviews/2026-09-06-retinue-spec-critique.md` · `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`(:7-22, :43-53, :320-352) · `docs/superpowers/plans/2026-08-22-beyond-che-world-map-and-game-loop-plan.md`(:64, :126-133) · `docs/design/roadmap.md`(:8-24, :49-69) · `docs/design/ui-redesign-2026-09/src/{Nation,Council}.body.html`(텍스트 추출) · `.ai/decisions.md`(ADR-LITE-032/035/037/045/049) · Jira OPENSAM-56(설명·코멘트, MCP 조회) · `logic/src/main/kotlin/opensamguk/logic/domain/LogicEntities.kt` · `logic/.../tick/{MonthlyPipeline,ServerClock}.kt` · `logic/.../world/{ProvinceControlState,WaterControlState,GeneralPositionState,StrategicTopology,UpdateCitySupply}.kt` · `logic/.../event/EventStore.kt` · `logic/.../actions/intake/{SecretPermission,BoardActions}.kt` · `common/src/main/kotlin/opensamguk/common/wire/{TurnDaemonCommand,TurnDaemonCommandResult}.kt` · `common/.../constants/GameConst.kt` · `app/game-engine/src/main/kotlin/opensamguk/engine/turn/{TurnWorldModel,InMemoryTurnWorld,ChangeRecorder}.kt` · `engine/run/{MonthlyPostUpdateHook,TurnRunService,TurnDaemonCommandDispatcher}.kt` · `engine/config/{DaemonLoopConfig,EngineEventConfig}.kt` · `engine/boot/WorldSnapshotLoader.kt` · `engine/world/WorldActionContext.kt` · `engine/intake/{BoardHandler,AccessLogThrottle}.kt` · `engine/flush/{DatabaseHooks,TruncateContract}.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/{turn/ReservedTurnHandlerTest,turn/ReservedTurnWarDrainTest,golden/LongSimReplayGateTest,run/TurnRunServiceIT}.kt` + 테스트 디렉터리 목록 · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/resources/db/migration/{V32,V49,V50,V53}__*.sql` + 디렉터리 목록 · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/{security/GameApiSecurityConfig,owner/GeneralResolver,reserve/CommandWireMapper,controller/BoardController,read/BoardReadRepository,read/NationLogReadRepository,dto/F4Dto}.kt` · `web/game/components/CommandModal.tsx` · `web/game/app/game/` 목록 · `web/shared/src/{Gauge,Portrait}.tsx` · `docker-compose.yml` · `docker-compose.production.yml` · `gradle/libs.versions.toml`.

</details>
