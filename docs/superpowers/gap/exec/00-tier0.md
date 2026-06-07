# exec/00-tier0 — Tier-0 공유 토대 (4 Area)

> 데이터 정본: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스) + legacy `legacy/devsam-core` grand-truth 직접 grep. **날조 금지** — 모든 단위·file:line은 raw JSON 또는 실제 파일에서 확인됨.
>
> **세션 정정 반영(MASTER_GAP §5의 aspirational 항목 교정):**
> - **Area 1 utilGame 16종 = ✅ 완료+커밋(c2293ba)**, `web/game/lib/utilGame/`. 잔여 = 보류 3종 배선뿐.
> - **Area 4 wire 시드 = 🔴 전제붕괴**: DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 가 logic에 부재 → 명령 포팅(그룹 A)에 흡수. Tier-0 독립 단위 아님.
> - **Area 3 trigger base = 단독포팅 YAGNI** → 첫 구체 트리거와 함께 포팅.
> - 맵 정합 = ✅ 완료(b3d0ff1).
>
> Tier-0의 **유일한 실질 잔여 작업 = Area 2 Scenario 도메인**(B1–B3 장수/국가 생성). 나머지 3 Area는 ✅완료(잔여 배선만) 또는 흡수.

모듈 매핑 규칙:
- **명령** = `logic/actions/*` + `CommandRegistry` + intake(`CommandWireMapper`) + game-engine dispatcher + golden
- **FE** = `web/game`(또는 `web/gateway`)
- **read** = `app/game-api` controller + DTO
- **admin** = `app/gateway-api` + `web/gateway/app/admin`

---

## Area 1 — utilGame (✅ 완료, 잔여 = 보류 3종 배선)

`legacy/devsam-core/hwe/ts/utilGame/` 19파일 중 **16종이 `web/game/lib/utilGame/`로 포팅 완료**(커밋 c2293ba). `index.ts`에서 전부 re-export. 감사 fidelity: getNPCColor=100, formatVoteColor=100, formatOfficerLevelText=95 (raw JSON `comparisons[]`).

### 포팅 완료 16종 (배선 불요, 참조용)

| # | 포팅 파일 | legacy 출처 | 비고 |
|---|---|---|---|
| 1 | `calcInjury.ts` | `utilGame/calcInjury.ts` | 부상 계산 |
| 2 | `formatDefenceTrain.ts` | `utilGame/formatDefenceTrain.ts` | 수비/훈련도 표기 |
| 3 | `formatDexLevel.ts` | `utilGame/formatDexLevel.ts` | 숙련 등급(`DexLevelMap`) |
| 4 | `formatGeneralTypeCall.ts` | `utilGame/formatGeneralTypeCall.ts` | 통/무/지 비율 호칭(31L) |
| 5 | `formatHonor.ts` | `utilGame/formatHonor.ts` | 명성 표기 |
| 6 | `formatInjury.ts` | `utilGame/formatInjury.ts` | 부상 한글명 |
| 7 | `formatLog.ts` | `utilGame/formatLog.ts` | 로그 마크업 렌더 |
| 8 | `formatOfficerLevelText.ts` | `utilGame/formatOfficerLevelText.ts` | 관직명(BE F4StateText와 byte-동치, fid 95) |
| 9 | `formatRefreshScore.ts` | `utilGame/formatRefreshScore.ts` | 새로고침 점수 |
| 10 | `formatTournament.ts` | `utilGame/formatTournament.ts` | 토너먼트 type/step (fid 55 — 헤더 step 라벨/HH:MM은 별도 갭) |
| 11 | `formatVoteColor.ts` | `utilGame/formatVoteColor.ts` | 투표 색(fid 100) |
| 12 | `getNPCColor.ts` | `utilGame/getNPCColor.ts` | NPC 색(fid 100) |
| 13 | `isValidObjKey.ts` | `utilGame/isValidObjKey.ts` | 키 검증 |
| 14 | `nextExpLevelRemain.ts` | `utilGame/nextExpLevelRemain.ts` | 경험치 잔여 |
| 15 | `techLevel.ts` | `utilGame/techLevel.ts` | 기술 등급(`convTechLevel` 등) |
| 16 | `tournament.ts` | `utilGame/tournament.ts` | `calcTournamentTerm` |

보조: `_helpers.ts`(내부 헬퍼), `index.ts`(barrel export).

### 보류 3종 — 잔여 배선 서브태스크

`index.ts:2` 주석에 명시된 보류 항목. legacy 직접 확인 결과:

| id | kind | legacy(file) | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| **formatCityName** | fe-ts | `hwe/ts/utilGame/formatCityName.ts` (14L) | `web/game/lib/utilGame/formatCityName.ts` | GetConst cityConst | N | tsc + 단위(cityID→name) | 🔴 보류 | **배선 흐름 거의 완성**: legacy `formatCityName(target, gameConst): gameConst.cityConst[cityID].name`. BE는 이미 `GetConstResponse.cityConst: List<CityConstItem{id,name,...}>` 제공(`app/game-api/.../dto/GetConstDto.kt:43,72-74`), FE는 `api.gameConst() → '/api/const'`(`web/game/lib/api.ts:69`) + `GameConstResponse`(`types.ts:319`) 보유. **서브태스크**: (1) `cityConst` 배열을 `{[id]: name}` 룩업으로 인덱싱(legacy는 obj, BE는 array — adapter 1줄), (2) `formatCityName.ts` 추가 + `index.ts` export, (3) city 미존재 시 legacy는 `throw`이나 FE는 graceful 권장(divergence 주석). RNG 무관 → 골든 불요. |
| **postFilterNationCommandGen** | fe-ts | `hwe/ts/utilGame/postFilterNationCommandGen.ts` | `web/game/lib/utilGame/postFilterNationCommandGen.ts` | formatCityName(↑), JosaUtil, defs `TurnObj` | N | tsc + 단위(che_발령 brief 치환) | 🔴 보류 | che_발령 turnObj의 brief/tooltip을 `《부대명》【도시명】로 발령`으로 후처리(`gameConst.cityConst[destCityID].name` + JosaUtil `로`). **선결**: formatCityName 배선 + JosaUtil 포팅 여부 확인(`common`의 `Josa` 커널이 BE에 있으나 FE TS josaPick 별도 필요). **현 FE가 che_발령 brief를 어디서 만드는지 먼저 확인**(SelectGeneralField/CommandModal). 발령 명령 자체가 FE 미배선이면 그룹 A/C 발령 작업과 동시 처리. |
| **getNewMsgToast** | fe-ts | `hwe/ts/utilGame/getNewMsgToast.ts` | (Vue 전용 — React 재설계 필요) | MessagePanel, ReadLatestMessage API | N | — | 🔴 보류(blocked 일부) | raw fidelity=5. legacy = 새 메시지 토스트 팩토리(title/body/액션2 '보러가기'·'이미읽음'/variant warning/10분 delay/채널 dedup/읽음처리). 현 `web/game/components/Toast.tsx`는 무관한 범용 토스트(3초 success/error/info). **Vue Toast 시스템 전제** → React 재설계 작업이며 Tier-0 포매터 시드 범위 밖. 읽음처리 API(`ReadLatestMessage`) game-api 엔드포인트 미발견 → 해당 동작 **blocked**. **이 항목은 Tier-0에서 제외하고 메시지 FE 패밀리(그룹 C)로 이관 권장.** |

---

## Area 2 — Scenario 도메인 (★ Tier-0 핵심 잔여 — B1/B2/B3 장수·국가 생성)

월드 부트스트랩(턴 명령 아님). legacy 총 ~1567L, **RNG 빌드 파이프라인 전무**. raw `comparisons[]` fidelity:
- `Scenario(world bootstrap)` = **18** ("핵심 RNG 빌드 파이프라인은 전혀 미포팅")
- `MakeGeneral` = **55** (draw 순서 byte-parity ✅ 골든 강함, 그러나 **production caller 0개**, GeneralBuilder 전체 미포팅)

### 레거시 규모 (직접 wc -l)

| legacy 파일 | 라인 | 역할 |
|---|---|---|
| `Scenario/GeneralBuilder.php` | **737** | 장수 빌더 — fillRandomStat/fillRemainSpecAsRandom/build + general·general_turn·rank_data INSERT |
| `Scenario.php` | 630 | 월드 빌드 오케스트레이터(getGameConf 조각만 포팅됨) |
| `Scenario/Nation.php` | **200** | 국가 빌더 — build(availableNationType choice) + postBuild(군주선출 + nation_turn 시드) |
| `AbsGeneralPool.php` | **111** | 장수 풀 추상 기반 |
| `GeneralPool/RandomNameGeneral.php` | **101** | 랜덤 장수명 풀(성/중간이름/끝이름 3 draw) |
| `GeneralPool/SPoolUnderU30.php` | — | U30 풀(JSON: `GeneralPool/Pool/UnderS30.json`) |

### GeneralBuilder.php RNG draw 종류 (★ 골든 필수 — 직접 grep)

draw-for-draw 패러티 대상. file:line은 `legacy/devsam-core/hwe/sammo/Scenario/GeneralBuilder.php`:

| draw | 메서드 호출 | line | 비고 |
|---|---|---|---|
| 특기(전특) | `SpecialityHelper::pickSpecialWar($rng, $general)` | :117, :124 | setSpecialOption '랜덤전특'/'랜덤' |
| 특기(내특) | `SpecialityHelper::pickSpecialDomestic($rng, $general)` | :120, :127 | setSpecialOption '랜덤내특'/'랜덤' |
| 랜덤 분기 | `$rng->nextBool(2/3)` | :123 | '랜덤' 시 전특/내특 택1 |
| 친화 | `$rng->nextRangeInt(1, 150)` | :177, :353, :413 | setAffinity / fillRemainSpecAsZero / fillRemainSpecAsRandom |
| 스탯 타입 | `$rng->choiceUsingWeight($pickTypeList)` | :314, :455 | fillRandomStat 통/무/지 가중선택 |
| 주스탯 | `defaultStatNPCMax - $rng->nextRangeInt(0, defaultStatNPCMin)` | :317 | mainStat |
| 부스탯 | `minStat + $rng->nextRangeInt(0, toInt(defaultStatNPCMin/2))` | :318 | otherStat |
| 성격(ego) | `$rng->choice(GameConst::$availablePersonality)` | :383, :489 | |
| 생몰 | `$env['year'] + $rng->nextRange(-5, 5)` / `birth + $rng->nextRangeInt(60, 80)` | :417, :418 | fillRemainSpecAsRandom birth/death |
| dex 분배 | `$rng->choice([[...],[...],[...]])` | :475 | 무 타입 dex 배열 택1 |
| 도시 배치 | `$rng->choice(CityHelper::getAllCities())` / `getAllNationCities($nationID)` | :640, :643 | cityID 미지정 시 |
| 턴타임 | `\sammo\getRandTurn($rng, $env['turnterm'], turntime)` | :656 | |
| killturn | `($death-$year)*12 + $rng->nextRangeInt(0, 11) + $month - 1` | :662 | birth 기반 |

→ **MakeGeneral.kt(entrance Join.php 경로)는 다른 draw 시퀀스**라 GeneralBuilder 시나리오 mint를 대체 불가(raw `comparisons` 명시). 별도 골든 필요.

### RandomNameGeneral.php draw (직접 grep, file:line `GeneralPool/RandomNameGeneral.php`)

| draw | 호출 | line |
|---|---|---|
| 성 | `$rng->choice(GameConst::$randGenFirstName)` | :35 |
| 중간이름 | `$rng->choice(GameConst::$randGenMiddleName)` | :36 |
| 끝이름 | `$rng->choice(GameConst::$randGenLastName)` | :37 |

`pickGeneralFromPool(db, rng, owner, pickCnt, prefix)` (:65) → `pickGeneral1FromPool` (:30) 루프.

### Nation.php draw (직접 grep, file:line `Scenario/Nation.php`)

| draw | 호출 | line | 비고 |
|---|---|---|---|
| 국가 타입 | `$rng->choice(GameConst::$availableNationType)` | :83 | type 미지정 시. **주의**: AI 건국(`GenFoundFamily`)에는 있으나 시나리오 시점 생성엔 없는 draw(raw 명시) |
| postBuild 군주선출 | `ORDER BY leadership+strength+intel DESC LIMIT 1` | :171 | RNG 아님 — 결정론 정렬 |
| postBuild nation_turn | 12..chiefLevel × maxChiefTurn '휴식' 시드 | :180-194 | RNG 아님 |

### DB write 형상 (build :668-734)

`general` INSERT(:668) → `general_turn` INSERT(:723, 30행 ring) → `rank_data` INSERT(:734). `GeneralCreateFlushIT`가 이미 (general + 30 general_turn + 37 rank_data) 형상 검증(MakeGeneral 경로). **JDBC-only — `infra/seed` 또는 `engine.boot` 패키지(write-path scan 밖, one-daemon-write-rule 비위반).** 기존 `ScenarioImporter.kt`(infra/seed) 옆에 위치 가능.

### 단위 표

| id | kind | legacy(file) | 대상 파일(제안) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| **B1 GeneralBuilder** | domain | `Scenario/GeneralBuilder.php` (737L) | `logic/world/GeneralBuilder.kt` (순수 draw) + `infra/seed/GeneralBuilderFlush.kt` (INSERT) | GameConst, SpecialityHelper(`pickSpecialWar/Domestic`), CityHelper, getRandTurn(✅ MakeGeneral 재사용) | **Y (필수)** | `GeneralBuilderGoldenTest` draw-for-draw + cursor + `GeneralBuilderFlushIT`(general+general_turn+rank_data) | 🔴 미포팅(MakeGeneral=다른 경로 55%) | (a) **골든 캡처**: `tools/php-golden` Docker(시나리오 1010) GeneralBuilder build draw 시퀀스 dump → `golden/scenario/장수빌더-*.json`. (b) 순수 draw 포팅: fillRandomStat(:313)·fillRemainSpecAsZero(:347)·fillRemainSpecAsRandom(:400)·build(:542) draw 순서 13종(위 표) line-for-line. (c) flush 형상은 MakeGeneral IT 패턴 재사용. (d) **/parity-close 급** — 단일 단위로 full draw+flush gate. |
| **B2 Scenario/Nation** | domain | `Scenario/Nation.php` (200L) | `logic/world/ScenarioNation.kt` + `infra/seed/ScenarioNationFlush.kt` | GameConst $availableNationType, B1(generals 카운트), KVStorage(nation_env) | **Y** (build :83 type choice) | `ScenarioNationGoldenTest`(type choice) + flush IT(nation update + nation_turn insertIgnore) | 🔴 미포팅 | (a) build(:70) — cities/capital 해석 + type choice(:83, 단 type 지정 시 draw 없음). (b) postBuild(:156) — 군주선출(결정론 정렬 :171) + nation_turn 휴식 시드(:180). (c) **현 ScenarioImporter.kt(infra/seed)가 이미 nation 행 INSERT 수행** → 중복/책임 경계 확인 필수(F1 시드와 충돌 회피). |
| **B3 GeneralPool / RandomNameGeneral** | domain | `AbsGeneralPool.php`(111L) + `GeneralPool/RandomNameGeneral.php`(101L) + `SPoolUnderU30.php` + `Pool/UnderS30.json` | `logic/world/pool/AbsGeneralPool.kt` + `RandomNameGeneral.kt` (+ JSON 리소스) | GameConst $randGen{First,Middle,Last}Name, B1(builder 위임) | **Y** (이름 3 draw + builder draw) | `RandomNameGeneralGoldenTest`(성/중간/끝 choice 순서 + builder) | 🔴 미포팅 | (a) `AbsGeneralPool` 추상(builder/info/uniqueName/validUntil 필드). (b) `RandomNameGeneral`: pickGeneral1FromPool 3 choice(:35-37) + occupyGeneralName(:14) 중복회피 루프. (c) `UnderS30.json`은 리소스로 커밋. (d) **NPC 생성 경로(RaiseNPCNation·CreateManyNPC) 소비처** → 그룹 A event 작업의 선결 토대. |
| **RaiseNPCNation (소비처, 참고)** | event | `Event/Action/RaiseNPCNation.php` | `logic/event/RaiseNPCNation.kt` | **B1+B3 선결** | Y | event golden | 🔴 미포팅 | 공백지 NPC 국가 자동생성(거리필터 + 평균도시/장수 산출 + `pickGeneralFromPool`). **B1/B3 완료 후 그룹 A에서 처리** — Tier-0 아님, 의존관계 명시용. |

### 의존관계 (B1-B3 → NPC/이민족 event)

```
B1 GeneralBuilder ──┐
                    ├─→ B3 GeneralPool/RandomNameGeneral ──→ (그룹A) RaiseNPCNation / CreateManyNPC / RegNPC
B2 Scenario/Nation ─┘                                    └─→ (그룹A) 이민족 event (RaiseInvader 계열)
```

B1이 모든 장수 mint의 draw 루트. B3는 B1을 위임 사용. B2는 B1 카운트 의존. **NPC/이민족 event(그룹 A)는 B1+B3가 선결**되어야 골든 가능.

### /parity-close 급 서브태스크 분해 (B1 예시)

1. `tools/php-golden` Docker로 GeneralBuilder build draw 캡처 → `golden/scenario/장수빌더-*.json` (시나리오 1010, fiction/non-fiction 분기 포함).
2. `logic/world/GeneralBuilder.kt` 순수 draw 포팅 — 위 13 draw line-for-line, `RandUtil` 1개 thread-by-reference.
3. `GeneralBuilderGoldenTest` — seed 재구성 + draw-for-draw byte parity + inner-DRBG cursor(stateIdx/bufferIdx) + 모든 outcome 필드.
4. `infra/seed/GeneralBuilderFlush.kt` — general/general_turn/rank_data INSERT (`GeneralCreateFlushIT` 패턴 재사용).
5. `GeneralBuilderFlushIT` — 영속 형상 검증(real Postgres).
6. 한 logical commit (`Co-Authored-By: Claude Opus 4.8 (1M context)`).

---

## Area 3 — trigger base (단독포팅 YAGNI → 첫 구체 트리거와 함께)

### 현황 대비 (직접 확인)

**현 logic = battle trigger 100% 완료** (raw `comparisons`):
- `logic/war/trigger/TriggerCaller.kt` — 추상 base(constructor/insert/sortOuterAscending=ksort/merge=array_merge/fire=draw order). fidelity 95.
- `logic/war/trigger/WarUnitTriggerCaller.kt` — battle subclass(`instanceof BaseWarUnitTrigger` 게이트). fidelity **100**.
- `logic/war/trigger/BaseWarUnitTrigger.kt` — battle trigger 추상.
- 골든: `BattleReplayGateTest`(draw+cursor parity) + `TriggerCallerOrderTest`(fire-order/dedup/merge).

**미포팅 = general(pre-turn-execute) 측 trigger** (각 9L 추상, 직접 cat 확인):
- `GeneralTriggerCaller.php` (9L 전문): `class GeneralTriggerCaller extends TriggerCaller` + `checkValidTrigger($t instanceof BaseGeneralTrigger)`.
- `BaseGeneralTrigger.php` (9L 전문): `abstract class BaseGeneralTrigger extends ObjectTrigger { __construct(General $general){$this->object=$general;} }`.

### 단위 표

| id | kind | legacy(file) | 대상 파일(제안) | 의존 | 골든 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| GeneralTriggerCaller | domain | `GeneralTriggerCaller.php` (9L) | `logic/war/trigger/GeneralTriggerCaller.kt` | TriggerCaller.kt(✅), BaseGeneralTrigger | N(추상, 자체 draw 없음) | 첫 구체 트리거의 fire-order test | 🔴 미포팅 | **단독포팅 YAGNI** — checkValidTrigger = `trigger is BaseGeneralTrigger` 1줄. TriggerCaller.kt 추상 이미 존재. |
| BaseGeneralTrigger | domain | `BaseGeneralTrigger.php` (9L) | `logic/war/trigger/BaseGeneralTrigger.kt` (또는 general 패키지) | `Trigger`/`ObjectTrigger` 추상(현 war/trigger) | N | — | 🔴 미포팅 | `object = general` 만 보유하는 추상. WarUnit이 아닌 General을 object로 갖는 변형. |
| GeneralTrigger/che_도시치료 외 | domain | `GeneralTrigger/{che_도시치료,che_병력군량소모,che_부상경감,che_아이템치료}.php` | `logic/war/trigger/general/*.kt` | ↑ base 2종 | Y(트리거 발동 draw — 발동 시) | 트리거별 골든 | 🔴 미포팅 | **포팅 방침: 첫 구체 트리거(예: che_도시치료) 작업 시 base 2종을 함께 생성**(creator-then-consumer 단일 PR). 추상만 단독 포팅 금지(소비처 없는 dead abstract). |

**방침 요약**: GeneralTriggerCaller/BaseGeneralTrigger는 각 9L 추상 — 단독 PR이면 소비처 0개 dead code. WarUnitTriggerCaller 포팅 패턴(decision #2 append() 제외, fire=draw order, instanceof 게이트)을 그대로 따라 **첫 구체 GeneralTrigger와 묶어서 포팅**.

---

## Area 4 — wire 시드 (🔴 전제붕괴 → 그룹 A 흡수)

### 전제붕괴 설명

MASTER_GAP §5 / FOUNDING_SEAM_FIX가 가정한 "wire 시드"(intakeCodes + CommandWireMapper wire variants + TurnDaemonCommand variant를 Tier-0에서 1회 widening)는 **DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 의 logic resolver가 존재한다는 전제** 위에 섰다. 실측(raw `missingPages[]` intake-api):

- `General/DieOnPrestart` — "TurnDaemonCommand.DieOnPrestart 정의는 common에 있으나 CommandWireMapper intakeCodes 미등록, dispatcher 미바인딩. 엔드포인트 없음."
- `General/DropItem` — 동일(common 정의만, intake/dispatcher/resolver 부재).
- `General/InstantRetreat` — 동일.

즉 **common에 enum variant만 있고 logic actions/* resolver·dispatcher 분기·intake가 전부 없다.** wire만 시드해도 dispatch 대상이 없어 deny/throw. ResetStat/CheckOwner도 logic 부재 동일.

→ **wire는 명령 자체의 일부**(resolver + registry + intake + dispatcher + golden이 한 단위). 따라서 **그룹 A 명령 포팅에 흡수**하며, 각 명령 /parity-close 시 자기 wire variant를 함께 widening한다. **단, 공유 파일(`CommandWireMapper.kt`, `TurnDaemonCommandDispatcher.kt`, intakeCodes)은 co-widen 금지** → 그룹 A 진입 직전 **Tier-0 마지막 단계로 "스텁 widening 1회"**(빈 케이스 추가)만 수행하고, 각 명령이 disjoint하게 자기 케이스 본문을 채운다.

### 그룹 A 흡수 매핑

| id | kind | legacy(file) | 흡수 대상(그룹 A 명령) | 의존 | 골든 | 상태 | 핵심 fixSpec |
|---|---|---|---|---|---|---|---|
| General/DieOnPrestart | intake-api | `API/General/DieOnPrestart.php` | 해당 명령 /parity-close (resolver+registry+intake+dispatcher) | common enum(✅), logic resolver(🔴) | Y(있으면) | 🔴 흡수 | logic resolver 신설 → CommandWireMapper intakeCodes 등록 → dispatcher 바인딩 → endpoint. wire는 이 단위 내부. |
| General/DropItem | intake-api | `API/General/DropItem.php` | 동일 | 동일 | Y | 🔴 흡수 | 아이템 드롭 resolver + flush(아이템 삭제 delta). |
| General/InstantRetreat | intake-api | `API/General/InstantRetreat.php` | 동일 | 동일 | Y | 🔴 흡수 | 즉시 퇴각 resolver. |
| ResetStat | intake-api | `API/General/ResetStat.php`(추정) | 동일 | 동일 | Y | 🔴 흡수 | 스탯 초기화 resolver. |
| CheckOwner | intake-api | (precheck/constraint 계열) | 해당 명령 precheck | Presets/Constraint | N | 🔴 흡수 | 명령별 owner 제약 — `Constraint`로 포팅. |

**Tier-0 잔여 = 그룹 A 진입 직전 공유 widening 1회뿐**:
1. `CommandWireMapper.kt` intakeCodes에 그룹 A 명령 코드 빈 케이스 추가(creator).
2. `TurnDaemonCommandDispatcher.kt` 빈 dispatch 케이스 추가(creator).
3. 이후 각 명령(consumer)이 disjoint 본문 채움 — co-widen 충돌 회피.

---

## Tier-0 종합 — 실질 잔여 작업

| Area | 상태 | 잔여 작업 |
|---|---|---|
| **1 utilGame** | ✅ 16종 완료(c2293ba) | formatCityName 배선(GetConst cityConst 이미 제공, 1 adapter), postFilterNationCommandGen(formatCityName+Josa 선결), getNewMsgToast → 메시지 FE 패밀리(그룹 C)로 이관 |
| **2 Scenario** | 🔴 핵심 잔여 | **B1 GeneralBuilder(737L, 13 draw, 골든+flush IT)** · B2 Scenario/Nation(200L) · B3 GeneralPool/RandomNameGeneral(212L, 3 draw) — /parity-close 급. NPC/이민족 event(그룹 A)의 선결 토대 |
| **3 trigger base** | 🔴 YAGNI 대기 | GeneralTriggerCaller+BaseGeneralTrigger(각 9L) — 첫 구체 트리거(che_도시치료 등)와 단일 PR |
| **4 wire 시드** | 🔴 흡수 | 전제붕괴 — 그룹 A 명령 포팅에 흡수, Tier-0은 공유 stub widening 1회만 |

**순서**: Area 1 보류 배선(경량) → **Area 2 B1→B3→B2**(★ 진짜 토대) → Area 4 공유 stub widening → (그룹 A 진입) → Area 3는 그룹 A 첫 트리거와 동행.
