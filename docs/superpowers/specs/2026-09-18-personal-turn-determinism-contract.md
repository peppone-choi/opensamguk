# 개인 턴 결정론 계약 — 같은 시각 처리 순서와 시드

> 작성일: 2026-09-18
> 상태: **초안(제안).** 교차 비평·사용자 승인 전. 이슈 #787 / OPENSAM-267. 로드맵 3단계.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §3·§5.1·§5.2·§17, [입력 registry 계약](./2026-09-17-input-registry-contract.md) §2(ruleProfile)·§4(timing), [장수 위치의 정본](./2026-09-18-general-position-authority.md), ADR-LITE-057, ADR-LITE-042(동결 회귀 기준선).
> 범위: 계약만 정한다. 코드·수치 없음. **다시 열지 않는 결정:** 장수별 개인 턴 유지(전역 순서 재작업 없음), 시즌 이월 없음, 정사·연의 월드 분리 없음.
> 표기: `ENG` = `app/game-engine/src/main/kotlin/opensamguk/engine`, `LOGIC` = `logic/src/main/kotlin/opensamguk/logic`, `INFRA` = `infra/src/main/kotlin/opensamguk/infra`. 줄 번호는 `origin/main` `43ed3872` 기준.

## 1. 현행 실측

### 1.1 처리 순서

| 사실 | 근거 |
|---|---|
| 마감된 장수는 `turnTime` 오름차순, 같으면 **장수 id 오름차순**으로 처리한다. 재설계 §5.2 의 「안정 ID 순」은 **이미 구현돼 있다** | `ENG/turn/TurnDaemonLifecycle.kt:105-108` (`compareBy({ it.turnTime }, { it.id })`) |
| 마감 판정은 strict `<` 다 — `runTime` 과 정확히 같은 `turnTime` 은 아직 마감이 아니다 | `ENG/turn/TurnDaemonLifecycle.kt:101-107` |
| 원본 목록(`listGenerals`)은 맵 값 순서라 순서 보장이 없다. 순서는 위 정렬 한 곳에만 기댄다 | `ENG/turn/InMemoryTurnWorld.kt:273` |
| 마감 집합은 처리 시작 전에 한 번 굳힌다(장수 identity 토큰 + 예약 명령 payload). 처리 중 새로 생기거나 교체된 장수는 그 묶음에 끼지 못한다 | `ENG/turn/TurnDaemonLifecycle.kt:114-148`, 재검사 `:161-166` |
| 한 장수 안의 순서: 전처리 → 차단 검사 → AI 결정 창 → **사령턴(국가 명령) 먼저** → 장수 명령 → killturn → 예약 링 당김 → `updateTurnTime` | `ENG/turn/TurnDaemonLifecycle.kt:170-272` |
| 개인 턴 마감 묶음은 「즉시 인테이크 먼저 → 마감 장수 전부 → **flush 1회**」다. 세계 경계 틱도 flush 1회다 | `ENG/run/TurnRunService.kt:263-278`, `:291-302`, `:420` |
| 데몬은 세계 경계가 밀려 있으면 그것을 먼저 돌리고, 아니면 **벽시계 `now`** 로 개인 턴 마감을 부른다 | `ENG/run/TurnDaemonRunner.kt:267-286` |
| 시드 장수의 `turnTime` = `installTime` + 지터(초 `0‥60·turnTerm−1`, 마이크로초 `0‥999999`). 지터는 「시작 시점에 활동 중」인 장수에게만 뽑히고, 나머지 행은 자리표시값 0 으로 남는다. 0 으로 남은 행이 실제로 `general` 에 INSERT 되는지(→ 전원 같은 `turnTime`)는 **UNKNOWN** — 끝까지 추적하지 못했다. 지터를 받은 장수끼리의 동률은 가능하나 빈도는 재지 않았다 | `INFRA/seed/ScenarioImporter.kt:695-705`(자리표시 0), `:661,677-680,720`(지터 조건), `:730-734`(지터), `:503-507`(적용) |
| `turn_time` 열은 `timestamptz`(마이크로초). 메모리는 `Instant`(나노초) | `infra/src/main/resources/db/migration/V1__baseline.sql:90`. 재적재 때 나노초 절삭이 동률 관계를 바꾸는지는 **UNKNOWN**(시드는 마이크로초 단위로만 쓴다 — `ScenarioImporter.kt:506`) |
| 같은 `turnTime` 에서 id 순으로 도는지 **직접 단언하는 테스트는 찾지 못했다.** `dueGenerals` 를 부르는 테스트는 재선택 방지·건수 확인뿐이다 | `app/game-engine/src/test/.../turn/DrainTailAdvanceTest.kt:293-312`, `.../boot/ScenarioBootIT.kt:186`. 부재는 이 두 표기(`dueGenerals`, `compareBy`) grep 한정이다 |

### 1.2 시드

| 사실 | 근거 |
|---|---|
| 난수 원시는 `RandUtil(LiteHashDrbg(serializeSeed(...)))` 하나다(엔진·logic main 31개 파일). `java.util.Random`·`kotlin.random`·`Math.random`·`ThreadLocalRandom`·`randomUUID` 는 같은 범위에서 0건(import 제외 grep). `SecureRandom` 은 1곳 — 전투 시뮬 미리보기의 시드 생성이고 턴 처리 경로가 아니다. 같은 grep 이 `LiteHashDrbg`·`SecureRandom` 은 잡았으므로 조회는 살아 있다 | `common/src/main/kotlin/opensamguk/common/rng/LiteHashDrbg.kt`, `LOGIC/war/BattleSimPreview.kt:338` |
| 장수 명령 시드 = `(hiddenSeed, "generalCommand", year, month, generalId, definition.key)` — **순(phase) 없음, 월드 id 없음** | `ENG/turn/ReservedTurnHandler.kt:360-362` |
| 사령턴 시드 = `(hiddenSeed, "nationCommand", year, month, generalId, actionCode)` | `ENG/turn/ProcessNationCommand.kt:183` |
| 전처리 시드 = `(hiddenSeed, "preprocess", year, month, generalId)` | `ENG/turn/ReservedTurnHandler.kt:941-942` |
| AI 결정 시드 = `(hiddenSeed, "GeneralAI", year, month, generalId)` | `LOGIC/ai/AiSeed.kt:45-46` |
| 출병 전투 시드 = `(hiddenSeed, "war", year, month, genId, destCityId)`, 점령 = `(…, "ConquerCity", year, month, attNationId, attId, cityId)`, 유니크 = `(…, "unique", year, month, generalId, reason)` | `LOGIC/war/WarSeed.kt:37`, `LOGIC/war/ConquerCitySeed.kt:44`, `LOGIC/domestic/UniqueItemLottery.kt:36` |
| **순이 1년 36개(월 3순)인데 위 시드들은 `(year, month)` 까지만 넣는다.** 장수는 순마다 한 번 행동하므로, 같은 달의 상·중·하순에 같은 장수가 같은 명령을 돌리면 **세 번 모두 같은 시드 문자열**에서 출발한다 | `common/.../constants/GameConst.kt:175`(`phasesPerMonth = 3`), `LOGIC/tick/ServerClock.kt:126-131`(턴 1개 = 순 1개), 시드 식은 위 행들. 실제 결과가 같아지는지는 난수 소비가 상태에 따라 갈릴 수 있어 **UNKNOWN**(실행 금지 조건이라 재현하지 않았다). 시드 문자열이 같다는 것만 코드로 확인 |
| 예외로 V59 전장 진입은 이미 재설계 §5.2 모양이다: `(hiddenSeed, "battlefield", worldId, year, month, currentPhase, actorId, siteId, catalog.contentHash, expected)` | `ENG/war/BattlefieldTurnHandler.kt:73-74` |
| 시드의 `year`·`month` 는 장수의 `turnTime` 이 아니라 **세계 시계**(`state.currentYear/currentMonth`)에서 온다. 명령 로그의 시각 문자열만 장수의 `turnTime` 에서 온다 | `ENG/turn/TurnDaemonLifecycle.kt:160,168,221-227` |
| `hiddenSeed` 는 월드마다 뽑지 않는다. `ScenarioImporter` 의 기본값 상수(`8ebfeb6f…`, PHP `UniqueConst::$hiddenSeed` 캡처)이고, 운영 시드 경로는 그 인자를 넘기지 않는다 → **모든 월드·모든 초기화가 같은 hiddenSeed** | `INFRA/seed/ScenarioImporter.kt:77-83`, `ENG/boot/ScenarioSeedRunner.kt:117-131`(인자 없음), 런타임 읽기 `ENG/config/DaemonLoopConfig.kt:228` |
| 월 경계 시드는 `(hiddenSeed, 이름, year, month[, nationId…])` | `LOGIC/tick/MonthScopedRng.kt:40-52`, `LOGIC/world/RaiseDisaster.kt:237` |
| 벽시계가 시드에 들어가는 곳: 장수 생성 `(…, "MakeGeneral", userId, nowStr)`, 풀 선택 `(…, "selectPool", ownerUserId, seedTime)` | `ENG/intake/MakeGeneralHandler.kt:118`, `ENG/intake/SelectPoolHandler.kt:43` |

### 1.3 리플레이

| 사실 | 근거 |
|---|---|
| 봉인 계획이 있는 城 강공만 리플레이 행을 남긴다: `war_seed`, `input_hash`, `replay_hash = SHA-256(페이즈 JSON + 정산)` | `ENG/turn/ReservedTurnHandler.kt:728-752`, `LOGIC/war/plan/BattleReplayCodec.kt:23-30` |
| 「같은 시드·같은 월드 → 같은 명령 순서·같은 상태 해시」를 메모리에서 두 번 돌려 비교하는 IT 가 있다(해시는 테스트 안에서 계산) | `app/game-engine/src/test/.../turn/HanAiLifecycleReplayIT.kt:77-85`, `:543-555` |
| 장수 턴 하나·처리 묶음 하나 단위의 **제품 측 해시는 없다** — `command_result` 에는 `committed_world_version` 만 실린다 | `ENG/run/TurnRunService.kt:273-277`, `infra/.../migration/V35__command_result_outbox.sql:25` |
| 묶음 경계는 재현 대상이 아니다: 어느 장수들이 한 flush 에 묶이는지, 즉시 인테이크가 어느 장수 턴 앞에 끼는지는 벽시계 `now` 와 데몬 지연에 달려 있다 | `ENG/run/TurnDaemonRunner.kt:268,285`, `ENG/run/TurnRunService.kt:266-269` |

## 2. 계약(제안)

`ruleProfile = HWIHA` 월드에만 적용한다(입력 registry 계약 §2). SAMMO 는 §4.

### 2.1 순서 키

- 장수 턴의 전역 순서 키는 **`(turnTime, generalId)`** 다. 현행 `TurnDaemonLifecycle.kt:108` 을 그대로 계약으로 올린다 — 새 정렬을 만들지 않는다.
- 「안정 ID」 = `general.id`. 시즌 이월이 없으므로 월드 수명 안에서만 안정하면 된다. 장수 교체는 identity 토큰이 막는다(`:114-148`).
- 무명 공용 카드는 자기 순서 키가 없다. 소속 장수의 턴 안에서 처리한다(재설계 §3). 그 안의 순서 키는 미결 Q3.
- 한 장수 턴 안의 단계 순서는 재설계 §5.1 표(1 드로우·재검사 → … → 8 기록)이고, 조정 결정은 2단계에 든다 — 현행 「사령턴 먼저, 장수 명령 다음」(`:196-226`)과 방향이 같다.
- 즉시 인테이크(배치·방침·공사 예약 수정 등 턴 밖 입력)는 장수 턴 **사이**에만 낀다. 장수 턴 하나는 끊기지 않는다(현행 단일 스레드·묶음 구조 그대로).

### 2.2 시드 파생

```text
seed = serializeSeed(hiddenSeed, domain, worldId, year, month, phase, generalId, ...scope)
```

- `phase`(순)를 넣는다 — §1.2 의 「월 3순이 같은 시드」를 HWIHA 에서 없앤다. `worldId` 를 넣는다 — hiddenSeed 가 모든 월드에 공통이라(§1.2) 월드 구분을 시드가 직접 진다. 둘 다 `BattlefieldTurnHandler.kt:73-74` 가 이미 쓰는 모양이다.
- `domain` 은 난수 흐름의 이름이고 흐름마다 다르다(현행 `"generalCommand"`·`"GeneralAI"`·`"war"` 방식 계승). `...scope` 는 그 흐름을 가르는 식별자(입력 id, 상대 장수, 省 등)다. HWIHA 의 domain·scope 목록은 입력 원장 행의 `replayContract` 에 적는다(입력 registry 계약 §3).
- `year·month·phase` 는 **그 장수 턴이 속한 순**이다. HWIHA는 실행 시 현재 세계 시계에서 읽는다. 지연된 예약도 현재 세계 순의 행동으로 처리하며, 개인별 마지막 처리 순을 저장하여 같은 세계 순에는 하나만 소비한다(2026-09-21 기획 위임 결정, 아래 Q2).
- 벽시계·스레드·맵 순회 순서·DB 반환 순서는 시드와 판정에 들어가지 않는다. 순회가 결과를 바꾸는 곳은 명시적 정렬 키를 가진다.
- hiddenSeed 를 월드마다 새로 뽑을지는 이 계약이 정하지 않는다(미결 Q1). `worldId` 가 들어가므로 어느 쪽이든 계약은 성립한다.

### 2.3 재현 대상

| 대상 | 재현 보장 | 비고 |
|---|---|---|
| 장수 턴 하나: (턴 직전 월드 상태, 그 턴의 입력, 시드) → (상태 변화, 로그, 리플레이) | **보장** | 단위 계약 |
| 장수 턴 열: 순서 키대로 늘어놓은 (장수 턴 + 그 사이 인테이크) 기록을 같은 시작 상태에 다시 적용 | **보장** | 인테이크가 어느 장수 턴 앞에 끼었는지는 **기록**에서 읽는다. 벽시계에서 다시 계산하지 않는다 |
| 순 경계(세계 처리) | **보장** | 시드는 `(…, year, month, phase)` — 현행 월 경계 시드에 phase 추가 |
| 묶음 경계(어느 턴들이 한 flush 에 묶였나) | **보장하지 않음** | 벽시계·지연에 달렸다(§1.3). 결과가 묶음 나누기에 의존하면 안 된다 — 게이트 G4 |
| 조우 전투 리플레이 | **보장** | 현행 `replay_hash` 형식 재사용(재설계 §5.1 「기존 구현과의 거리」) |

- 리플레이 해시: 장수 턴마다 `turn_hash = H(직전 해시, 순서 키, 입력 지문, 변화 지문)` 을 잇는 사슬을 제안한다. 저장 자리(새 열/표)와 지문 범위는 미결 Q4 — 여기서는 「같은 입력이면 같은 해시」가 검사 가능해야 한다는 것만 정한다.
- 처리 묶음마다 단일 flush(재설계 §5.2)는 현행 그대로다(`TurnRunService.kt:278,420`).

### 2.4 선후 이익 관측 지표

이슈 완료 조건의 두 번째다. 턴 시각의 선후 이익은 **없애지 않고 잰다**(재설계 §5.2·§17). 임계값은 두지 않는다 — 첫 시뮬레이션 실측을 기준선으로 삼는다.

- 조우 전투마다 기록: 공격 측(턴 주인)의 순 안 순번(그 순에서 몇 번째 턴이었나), 방어 측의 순번, 승패, 설치·대응 계책 발동 여부, 요격·회피 방침 발동 여부.
- 같은 목표(같은 省·縣·인물)를 같은 순에 두 장수가 노린 경우: 누가 먼저였고 누가 얻었나.
- 집계 축: 순번 분위별 승률·획득률, 같은 시각 동률 묶음 안에서의 id 순위별 값(동률이 id 작은 쪽에 이익을 주는지. 동률이 실제로 얼마나 생기는지도 같이 센다 — §1.1 UNKNOWN).
- 자리: `observeHandledTurn` 훅(`TurnDaemonLifecycle.kt:75,231`)이 이미 턴마다 불린다. 지표 저장 형식은 미결 Q5.

## 3. 게이트(적색 프로브 필수)

| # | 게이트 | 적색 프로브(이렇게 깨면 빨개져야 한다) |
|---|---|---|
| G1 | 같은 `turnTime` 의 장수 N명이 id 오름차순으로 처리된다(삽입 순서를 뒤섞어 넣고 처리 순서를 단언). 현행에 직접 단언이 없다(§1.1) — SAMMO·HWIHA 공통으로 먼저 넣는다 | `compareBy` 에서 `{ it.id }` 를 빼거나 내림차순으로 바꾼다 |
| G2 | HWIHA 시드 문자열은 `worldId`·`phase` 를 포함한다: 같은 장수·같은 입력으로 상순과 중순의 시드가 다르고, worldId 만 다른 두 월드의 시드가 다르다 | 시드 생성에서 phase(또는 worldId) 인자를 뺀다 |
| G3 | 결정론 재현: 같은 시작 상태 + 같은 입력 기록을 두 번 돌리면 장수 턴별 해시 사슬과 최종 상태 해시가 같다(`HanAiLifecycleReplayIT` 방식, HWIHA 월드로) | 판정 경로 한 곳에 `System.nanoTime()` 나 정렬 없는 맵 순회를 넣는다. 해시가 같게 나오면 게이트가 가짜다 |
| G4 | 묶음 무관성: 같은 장수 턴 열을 (가) 한 묶음으로, (나) 장수마다 한 묶음으로 나눠 돌려도 최종 상태 해시가 같다 | flush 직후에만 갱신되는 값을 다음 장수 판정이 읽게 만든다 |
| G5 | 벽시계 금지: HWIHA 판정·시드 경로의 main 소스에 `Instant.now`·`System.currentTimeMillis`·`nanoTime`·`java.util.Random`·`kotlin.random` 이 없다(정적 검사). 허용 목록은 데몬 루프·관측·인테이크 수신 시각뿐 | 판정 경로에 `Instant.now()` 한 줄을 넣는다. 검사는 반드시 걸릴 표기를 같이 걸어 조회가 살아 있음을 보인다 |
| G6 | 선후 이익 지표가 실제로 기록된다: 조우 전투 1건을 돌리면 순번·승패 행이 1건 생긴다 | 훅 호출을 뺀다 |
| G7 | SAMMO 바이트 불변(§4) | HWIHA 시드 분기를 SAMMO 월드가 타게 만든다 — 기존 골든이 빨개져야 한다 |

모든 게이트는 exit 0 이 아니라 **프로브로 빨개지는 것을 본 기록**을 PR 에 남긴다.

## 4. SAMMO 무변경

- SAMMO 월드는 아무것도 바뀌지 않는다. 시드 식(§1.2 의 5·6성분, phase·worldId 없음), `hiddenSeed` 상수, 처리 순서, flush 경로, 월 3순 같은 시드 성질까지 **그대로**다. 이 식들은 PHP 동결 기준선(ADR-LITE-042)의 골든 입력이라(`ReservedTurnHandler.kt:119-123`, `AiSeed.kt` 머리말) 고치면 기준선이 깨진다.
- 라이브 pep 은 S3 전환 전까지 SAMMO 로 돈다(ADR-LITE-049 개정). 이 계약은 pep 의 현재 동작에 닿지 않는다.
- G1 은 SAMMO 에도 넣지만 **현행 동작을 고정하는 테스트**일 뿐 동작을 바꾸지 않는다.
- 선행 의존: `ruleProfile` 은 아직 코드에 없다([장수 위치의 정본](./2026-09-18-general-position-authority.md) §3-6 이 함께 구현한다). 이 계약의 구현은 그 뒤다.

## 5. 회귀 기준선 영향

스키마·SAMMO 경로 무변경. HWIHA 는 새 시드 식을 쓰므로 PHP 골든과 비교하지 않는다 — HWIHA 의 오라클은 G3(자기 재현)와 G4(묶음 무관성)다. 해시 사슬을 저장하면 새 열/표가 생기고 HotColdCatalog 등록 대상이 된다(미결 Q4).

## 6. 미결 — 사용자 결정이 필요한 것

- **Q1. hiddenSeed 를 월드·초기화마다 새로 뽑을 것인가.** 현행은 모든 월드가 같은 상수다(§1.2). `worldId` 를 시드에 넣으면 월드끼리는 갈리지만, **같은 worldId 로 초기화한 다음 시즌은 같은 난수 운명**을 받는다. 새로 뽑으면 시즌마다 달라지고, 재현에는 그 값을 기록해 두면 된다. SAMMO 는 어느 쪽이든 상수 유지.
- **Q2 결정(2026-09-21, 사용자 기획 위임): HWIHA는 현재 세계 순을 사용한다.** `turnTime`은 실행 가능 시각과 동일 순 내부 순서를 정한다. 늦게 실행된 예약을 과거 순으로 소급하지 않으며, 같은 세계 순에 예약을 연속 소진하지 않는다. 처리한 `year/month/phase`를 `general.meta.hwihaLastPersonalTurn`에 저장하고 같은 순 또는 이전 순에서는 핸들러·난수·예약 조회/소비 전에 제외한다. 성공과 실행 거절 모두 한 번의 직접 행동으로 세며, 다음 세계 순에 밀린 다음 예약을 처리한다. 처리 표식·시간·상태 변경·슬롯·결과는 동일 flush로 저장한다. 기존 SAMMO 처리와 시드는 유지한다.
- **Q3. 무명 공용 카드·휘하 NPC 인물의 턴 안 순서 키.** 후보: 카드 id 오름차순. 카드 스키마가 아직 없어 여기서 못 정한다.
- **Q4. 장수 턴 해시 사슬을 제품에 저장할 것인가(새 열/표), 테스트 안 계산으로 둘 것인가.** 저장하면 운영 중 분기 탐지·기록 화면(재설계 「기록」 묶음)에 쓸 수 있고, 안 하면 스키마가 그대로다.
- **Q5. 선후 이익 지표의 저장 자리와 공개 범위.** 운영자 전용 관측인가, 플레이어에게 보이는 통계인가.
- **Q6. 동률의 이익.** 같은 `turnTime` 이면 id 작은 장수가 매 순 먼저 돈다(턴 간격이 같아 동률은 계속 동률이다). 동률이 실제로 얼마나 생기는지는 UNKNOWN(§1.1). 「턴 시각 배정 규칙은 현행 유지」(재설계 §3)대로 두고 지표(§2.4)로만 볼지, 실측 뒤 다시 정할지.
- 확인 못 한 것(UNKNOWN 모음): 지터 없는 시드 행의 INSERT 여부와 동률 빈도, 월 3순 같은 시드가 실제로 같은 결과를 내는 빈도, `timestamptz` 마이크로초 절삭이 동률 관계를 바꾸는지. 지연된 개인 턴의 HWIHA 처리 정책은 Q2 결정에 따른다.
