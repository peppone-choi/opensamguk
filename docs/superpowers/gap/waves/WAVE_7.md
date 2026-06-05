# WAVE 7 — per-command port long tail (PARITY_LEDGER PORT_MISSING 24)

> 실행 스펙. grand truth = `legacy/devsam-core` (PHP). 모든 divergence는 PHP가 이긴다.
> 케이던스 = `/parity-wave` foundation-first: (1) 공유 확장점(registry/intakeCodes/wire/catalog)을
> **단 1회** 넓히고, (2) per-command golden→port→gate를 **disjoint 파일**로 병렬화.

## 목표

PARITY_LEDGER `PORT_MISSING` 24개 명령(General 14 + Nation 10)을 PHP draw-for-draw로 포팅하고
registry/intake/FE-catalog에 연결해 player-reachable + golden-gated 상태로 만든다.

## 출처

- 인벤토리: `docs/superpowers/PARITY_LEDGER.md` (General PORT_MISSING 14, Nation PORT_MISSING 10).
- 마스터: `docs/superpowers/GAP_AUDIT.md` §3 WAVE 7 (7a/7b/7c/7d).
- PHP grand truth: `legacy/devsam-core/hwe/sammo/Command/General/`, `.../Command/Nation/`.
- 골든 하네스: `tools/php-golden/capture_command_c3.php` + `manifest_c3.json` (C3 패턴),
  `tools/php-golden/capture_command_args.php` (arg-gated General 패턴),
  `tools/php-golden/RandUtilDrawRecorder.php` (draw 기록).

### PORT_MISSING 24 정확한 명령코드 (PARITY_LEDGER L51-64, L125-134)

| # | code | area | PHP file | RNG-bearing | golden 캡처 대상 |
|---|---|---|---|---|---|
| 1 | che_화계 | General | `Command/General/che_화계.php` | YES (nextRangeInt×2 + nextBool + SabotageInjury) | `golden/p2/che_화계-fixtures.json` |
| 2 | che_파괴 | General | `Command/General/che_파괴.php` (extends che_화계) | YES | `golden/p2/che_파괴-fixtures.json` |
| 3 | che_탈취 | General | `Command/General/che_탈취.php` (extends che_화계) | YES | `golden/p2/che_탈취-fixtures.json` |
| 4 | che_선동 | General | `Command/General/che_선동.php` (extends che_화계) | YES | `golden/p2/che_선동-fixtures.json` |
| 5 | che_첩보 | General | `Command/General/che_첩보.php` | YES (nextRangeInt×2 exp/ded) | `golden/p2/che_첩보-fixtures.json` |
| 6 | che_단련 | General | `Command/General/che_단련.php` | YES (choiceUsingWeightPair + choiceUsingWeight) | `golden/p2/che_단련-fixtures.json` |
| 7 | che_강행 | General | `Command/General/che_강행.php` | NO (deterministic) | `golden/p2/che_강행-fixtures.json` |
| 8 | che_접경귀환 | General | `Command/General/che_접경귀환.php` | YES (rng->choice) | `golden/p2/che_접경귀환-fixtures.json` |
| 9 | che_숙련전환 | General | `Command/General/che_숙련전환.php` | NO (deterministic) | `golden/p2/che_숙련전환-fixtures.json` |
| 10 | che_전투태세 | General | `Command/General/che_전투태세.php` | NO (term-stack) | `golden/p2/che_전투태세-fixtures.json` |
| 11 | che_모반시도 | General | `Command/General/che_모반시도.php` | NO (deterministic) | `golden/p2/che_모반시도-fixtures.json` |
| 12 | che_전투특기초기화 | General | `Command/General/che_전투특기초기화.php` | NO main draw (trailing lottery seed only) | `golden/p2/che_전투특기초기화-fixtures.json` |
| 13 | che_내정특기초기화 | General | `Command/General/che_내정특기초기화.php` (extends 전투특기초기화) | NO | `golden/p2/che_내정특기초기화-fixtures.json` |
| 14 | che_등용수락 | General | `Command/General/che_등용수락.php` | NO (betray draw 가능) | (7d, trigger — golden 보류, OQ 참조) |
| 15 | cr_인구이동 | Nation | `Command/Nation/cr_인구이동.php` | TBD (포팅 시 확정) | `golden/p2/cr_인구이동-fixtures.json` |
| 16 | event_극병연구 | Nation | `Command/Nation/event_극병연구.php` | NO (deterministic) | `golden/p2/event_극병연구-fixtures.json` |
| 17 | event_무희연구 | Nation | `Command/Nation/event_무희연구.php` | NO | (대표 1종만 골든; 나머지 7종은 byte-동형 sibling) |
| 18 | event_상병연구 | Nation | `Command/Nation/event_상병연구.php` | NO | sibling |
| 19 | event_대검병연구 | Nation | `Command/Nation/event_대검병연구.php` | NO | sibling |
| 20 | event_화시병연구 | Nation | `Command/Nation/event_화시병연구.php` | NO | sibling |
| 21 | event_음귀병연구 | Nation | `Command/Nation/event_음귀병연구.php` | NO | sibling |
| 22 | event_산저병연구 | Nation | `Command/Nation/event_산저병연구.php` | NO | sibling |
| 23 | event_화륜차연구 | Nation | `Command/Nation/event_화륜차연구.php` | NO | sibling |
| 24 | event_원융노병연구 | Nation | `Command/Nation/event_원융노병연구.php` | NO | sibling |

> **golden 캡처 정책.** RNG-bearing 명령(화계 family·첩보·단련·접경귀환)은 draw-for-draw 골든 필수.
> deterministic 명령(강행·숙련전환·전투태세·모반시도·특기초기화 2종·연구 family·cr_인구이동)은
> log-byte + row-delta 골든(draw_count=0). 8개 `event_*연구`는 **단일 uniform 형태**(`event_극병연구.php`
> 와 byte-동형, `actionName`/`auxType`만 다름) — 대표 1종(극병)만 PHP 골든을 뜨고 나머지 7종은
> 같은 GoldenTest 클래스에서 파라미터화하여 검증한다(8회 fresh-DB 캡처 방지).

## 완료/제외 (코드 검증 결과)

검증 명령: `grep -rliE "Chehwagye|Checheobo|Chedanryeon|...|ResearchUnit" logic/src/main app web` → **0 hits.**
24개 모두 Kotlin 포트 부재 확인. 제외할 "이미 완료"는 없으나, 아래는 **혼동 금지(별개 시스템)**:

- **`che_화계`/`파괴`/`탈취`/`선동`** — Kotlin의 `화계`/`파괴`/`탈취`/`선동` 문자열은 **전투-계략 시스템**
  (`logic/.../war/trigger/triggers/CheGyeryakSido.kt` + `logic/.../actions/items/ItemHooks.kt`의 scheme-buff
  설명 텍스트)에만 존재. **reservable 계략 command는 미포팅** (PARITY_LEDGER L51-54 확인).
- **`che_전투특기초기화`/`내정특기초기화`** — `logic/.../actions/inherit/InheritResets.kt`의 intake
  (`inheritResetSpecialWar` 등)는 **유산-포인트 reset 서브시스템(별개 mechanism)**. reservable 특기-reset
  command는 미포팅 (PARITY_LEDGER L63-64 확인).
- **`che_접경귀환`** — 이미 포팅된 `che_귀환`(`CheGwihwan.kt`)과 **별개 command** (PARITY_LEDGER L58).
- **`event_*연구`** — `event_` prefix지만 monthly-pipeline event 아님. **FULL reservable NationCommand**
  (`getPreReqTurn()=23` 등 turn-reserve, `auxType` unlock). `MonthlyPipeline`/`EventDispatcher`와 무관.

**기존 foundation (재사용, 신규 작성 금지):**
- `logic/.../actions/GeneralActionDefinition.kt` — 단일 command iface (Nation도 이걸 구현; `NationActionDefinition`
  **없음** 확인). category/argsSchema/parseArgs/rawClassName/lotteryActionName/buildConstraints 슬롯 보유.
- `logic/.../actions/nation/NationCommand.kt` — Nation-scope base (category="국가", expDedMagnitude,
  next_execute KV, newState). event_*연구·cr_인구이동의 base.
- `logic/.../actions/CommandRegistry.kt:84-162` — `resolve(actionCode)` when-식. 신규 24 entry 추가 지점.
- `app/game-api/.../web/AvailableCommandsController.kt:147-167` — `GENERAL_COMMAND_CODES` (FE catalog).
- `app/game-api/.../reserve/CommandWireMapper.kt:84-87` — `turnReservedC3Codes` (ring-intake 마커).

## foundation-first 빌드 순서

**Tier-0 공유 확장점 (단 1회, sequential — co-widen 충돌 방지).** 아래 4개 파일은 24개 모두가
consume하므로 **family 분기 전에 한 번에** 확장한다. 이후 어떤 family도 이 파일을 다시 건드리지 않는다.

- **F0-A `CommandRegistry.kt`** — `resolve()` when-식에 24개 entry 추가
  (`"che_화계" -> CheHwagye(pipeline)` … `"event_원융노병연구" -> eventGeukbyeongYeongu(pipeline)` 등) +
  import 24줄. **처음엔 stub 클래스로 가리키게 두고**, family가 각자 stub→real 구현. (registry = single
  consumer-of-all; 분기 후 수정하면 24-way merge conflict.)
- **F0-B `AvailableCommandsController.kt:GENERAL_COMMAND_CODES`** — General 13종(등용수락 제외) +
  research/cr_인구이동을 catalog에 추가할지 결정. **계략 4 + 군사/개인 9 = 13 General**은 catalog 노출
  (modal submit). `event_*연구`·`cr_인구이동`은 **Nation-scope → catalog 비노출**, chief-center ring으로
  (WAVE 5와 동일 정책). 등용수락은 **trigger(non-reservable) → 비노출**.
- **F0-C `CommandWireMapper.kt:turnReservedC3Codes`** — `event_*연구` 8 + `cr_인구이동` 1을 추가
  (Nation-scope ring-intake 마커; intakeCodes에는 **절대** 넣지 않음 — L84-87 주석의 silent-no-op 함정).
  General 13종은 일반 `che_*` ring 경로라 마커 불필요.
- **F0-D 공유 base/헬퍼** — 계략 family base `SabotageCommand` (abstract, che_화계 공통 calc:
  `calcSabotageAttackProb`/`calcSabotageDefenceProb`/prob fold/실패-경로/공통 로그) +
  `SabotageInjury` 헬퍼(PHP `func.php:2169`) 포팅. 특기초기화 base `SpecialResetCommand` (전투/내정
  공유). research family base `ResearchUnitCommand` (event_*연구 8종 uniform: auxType unlock +
  exp/ded + 3-line 로그). **이 3개 base는 creator→consumer 순차** (F0-D 먼저, 그 다음 leaf family).

빌드 순서: **F0-A→D (sequential) → [7a, 7b, 7c, 7d] family 병렬.**

## 태스크 분해 표

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처 file:line) | 게이트 (테스트 클래스 + 골든 Y/N) | 선행 |
|---|---|---|---|---|
| **F0-A** | `logic/.../actions/CommandRegistry.kt` | 24 entry + import 추가 (stub 지향) | `CommandRegistryResolveTest` (24 코드 resolve≠RestAction); 골든 N | — |
| **F0-B** | `app/game-api/.../web/AvailableCommandsController.kt` | `GENERAL_COMMAND_CODES`에 General 13 추가 | `AvailableCommandsControllerTest`; 골든 N | — |
| **F0-C** | `app/game-api/.../reserve/CommandWireMapper.kt` | `turnReservedC3Codes`에 event_*연구 8 + cr_인구이동 1 | `CommandWireMapperTest` (intakeCodes 불변·ring 라우팅); 골든 N | — |
| **F0-D1** | `logic/.../actions/military/SabotageCommand.kt` (신규) + `logic/.../war/SabotageInjury.kt` (신규) | che_화계.php:48-107(prob calc)·func.php:2169(SabotageInjury)·che_화계.php:248-341(run skeleton) | `SabotageBaseTest`; 골든 N (base만) | F0-A |
| **F0-D2** | `logic/.../actions/personal/SpecialResetCommand.kt` (신규) | che_전투특기초기화.php:73-110 (특기 reset 공통) | `SpecialResetBaseTest`; 골든 N | F0-A |
| **F0-D3** | `logic/.../actions/nation/ResearchUnitCommand.kt` (신규) | event_극병연구.php:16-107 (auxType unlock 공통) | `ResearchUnitBaseTest`; 골든 N | F0-A |
| **7a-1** | `logic/.../actions/military/CheHwagye.kt` (신규) | che_화계.php (전체; statType=intel, injuryGeneral=true, affectDestCity agri/comm) | `Che화계GoldenTest`; 골든 **Y** | F0-D1 |
| **7a-2** | `logic/.../actions/military/ChePagoe.kt` (신규) | che_파괴.php (statType=strength, def/wall 감소) | `Che파괴GoldenTest`; 골든 **Y** | F0-D1 |
| **7a-3** | `logic/.../actions/military/CheTalchwi.kt` (신규) | che_탈취.php:13-101 (statType=strength, injury=false, gold/rice 탈취 + 본국 0.7 회수) | `Che탈취GoldenTest`; 골든 **Y** | F0-D1 |
| **7a-4** | `logic/.../actions/military/CheSeondong.kt` (신규) | che_선동.php (statType=leadership, secu 감소) | `Che선동GoldenTest`; 골든 **Y** | F0-D1 |
| **7b-1** | `logic/.../actions/military/CheCheobo.kt` (신규) | che_첩보.php (dist별 3-tier 로그 + spy KV write + active_action 0.5 예외) | `Che첩보GoldenTest`; 골든 **Y** | F0-A |
| **7b-2** | `logic/.../actions/personal/CheDanryeon.kt` (신규) | che_단련.php:79-131 (choiceUsingWeightPair + choiceUsingWeight) | `Che단련GoldenTest`; 골든 **Y** | F0-A |
| **7b-3** | `logic/.../actions/military/CheGanghaeng.kt` (신규) | che_강행.php:113-161 (이동 + train/atmos -5 + 방랑군 알림) | `Che강행GoldenTest`; 골든 **Y** (deterministic) | F0-A |
| **7b-4** | `logic/.../actions/military/CheJeopgyeongGwihwan.kt` (신규) | che_접경귀환.php:56-105 (rng->choice nearest 아국도시; **setResultTurn 미설정** quirk) | `Che접경귀환GoldenTest`; 골든 **Y** | F0-A |
| **7b-5** | `logic/.../actions/personal/CheSungnyeonJeonhwan.kt` (신규) | che_숙련전환.php:146-182 (dex src→dest 전환) | `Che숙련전환GoldenTest`; 골든 **Y** (deterministic) | F0-A |
| **7b-6** | `logic/.../actions/military/CheJeontuTaese.kt` (신규) | che_전투태세.php:66-126 (term-stack 3턴 + train/atmos cap) | `Che전투태세GoldenTest`; 골든 **Y** | F0-A |
| **7b-7** | `logic/.../actions/personnel/CheMobanSido.kt` (신규) | che_모반시도.php:57-100 (officer_level 12↔1 swap + 군주 exp×0.7 + global/national history) | `Che모반시도GoldenTest`; 골든 **Y** (deterministic) | F0-A, **WAVE1 의존(OQ1)** |
| **7b-8** | `logic/.../actions/personal/CheJeontuTukgiChogihwa.kt` (신규) | che_전투특기초기화.php (special2 reset + prev_types aux + speicalAge) | `Che전투특기초기화GoldenTest`; 골든 **Y** | F0-D2 |
| **7b-9** | `logic/.../actions/personal/CheNaejeongTukgiChogihwa.kt` (신규) | che_내정특기초기화.php (special reset, 전투 sibling) | `Che내정특기초기화GoldenTest`; 골든 **Y** | F0-D2 |
| **7c-1** | `logic/.../actions/nation/EventGeukbyeongYeongu.kt` (신규) | event_극병연구.php (auxType can_극병사용=1, gold/rice -100000, 3-line 로그, preReqTurn 23) | `ResearchUnitGoldenTest` (극병 캡처 + 8종 파라미터화); 골든 **Y** (극병만) | F0-D3 |
| **7c-2** | `logic/.../actions/nation/ResearchUnitDefs.kt` (신규; 무희/상병/대검병/화시병/음귀병/산저병/화륜차/원융노병 7 factory) | 각 event_*연구.php (actionName/auxType만 상이, run 동형) | `ResearchUnitGoldenTest` (sibling 7종); 골든 **N** (극병 골든 재사용) | F0-D3, 7c-1 |
| **7c-3** | `logic/.../actions/nation/CrInguIdong.kt` (신규) | cr_인구이동.php (포팅 시 동작 확정 — pop 이동) | `CrInguIdongGoldenTest`; 골든 **Y** | F0-A, F0-D3 |
| **7d-1** | `logic/.../actions/personnel/CheDeungyongSuak.kt` (신규) | che_등용수락.php:105-180 (망명 + betray + dest-nation 이동; trigger) | `Che등용수락Test` (unit; trigger-model); 골든 **N** (OQ2) | F0-A |
| **G-1** | (테스트 전용) `app/game-api/.../web/*Test.kt` | F0-B/C catalog·ring 라우팅 통합 | `Wave7CatalogIntakeIT`; 골든 N | F0-B, F0-C, 7a~7d |

> 골든 캡처는 각 7a/7b/7c GoldenTest의 fixture가 `logic/src/test/resources/golden/p2/<code>-fixtures.json`
> 에 존재해야 green. 캡처는 `capture_command_args.php`(General arg-gated) / `capture_command_c3.php`
> (Nation) 하네스를 **확장**(신규 코드의 PLAN entry 추가)하여 1-shot 호스트에서 실행 — `manifest_c3.json`
> /신규 `manifest_args` entry에 rawClassName·lotteryActionName·logLines·rngDraws 기재 후 캡처.

## 병렬화 그룹 (disjoint worktree family)

**전제:** F0-A~D 완료 후 분기. 각 family는 **자기 디렉터리/파일만** 작성하므로 co-widen 없음.

- **GROUP-7a (계략 family)** — `military/CheHwagye.kt`·`ChePagoe.kt`·`CheTalchwi.kt`·`CheSeondong.kt`.
  공유 `SabotageCommand.kt`는 F0-D1에서 이미 생성(read-only consume). 4 파일 disjoint.
- **GROUP-7b (military/personal)** — 9개 leaf, 각 1 파일. `military/`와 `personal/`·`personnel/`로 분산.
  단 `CheJeontuTukgiChogihwa`/`CheNaejeongTukgiChogihwa`는 F0-D2 `SpecialResetCommand` consume(별 파일).
- **GROUP-7c (research + cr_인구이동)** — `nation/EventGeukbyeongYeongu.kt`·`ResearchUnitDefs.kt`·
  `CrInguIdong.kt`. F0-D3 `ResearchUnitCommand` consume. **7c-1→7c-2 내부 순차**(7c-2가 극병 골든 재사용).
- **GROUP-7d (trigger)** — `personnel/CheDeungyongSuak.kt` 단일. 독립.

**병렬 family 수 = 4 (7a/7b/7c/7d).** Tier-0(F0-A~D)는 family 밖, sequential.
**creator→consumer 순차 쌍:** F0-D1→7a, F0-D2→7b(특기 2종), F0-D3→7c, 7c-1→7c-2.
**같은 파일 co-widen 위험:** `CommandRegistry.kt`/`GENERAL_COMMAND_CODES`/`turnReservedC3Codes`는 F0에서
한 번 닫고 family는 절대 재수정(24-way conflict 방지). registry stub→real은 **각 family가 자기 클래스
본문만** 채우므로 registry 파일 자체는 안 건드림(import는 F0-A에 미리 다 추가).

## 패러티 주의점

- **RNG draw 순서/개수 (계략 family).** che_화계.php:284-292: 실패 분기는 `nextBool(prob)` → 실패 시
  `nextRangeInt(1,100)`(exp) → `nextRangeInt(1,70)`(ded). 성공 분기는 `nextBool` → `SabotageInjury(rng,…)`
  (내부 draw) → `affectDestCity`(agri/comm 각 `nextRangeInt(sabotageDamageMin,Max)` 2 draw) →
  `nextRangeInt(201,300)`(exp) → `nextRangeInt(141,210)`(ded). **draw 순서·count는 byte-target**. 탈취는
  injuryGeneral=false라 SabotageInjury draw 생략 — sibling별 draw stream이 다르다.
- **RNG (단련).** `choiceUsingWeightPair([[['success',3],0.34],[['normal',2],0.33],[['fail',1],0.33]])` 1 draw →
  이후 `addDex` → `choiceUsingWeight(leadership_exp/strength_exp/intel_exp weight)` 1 draw. 2-draw 순서 고정.
- **RNG (접경귀환).** `searchDistance(cityID,3,true)` BFS-순회로 nearest 아국도시 후보 수집 → `rng->choice`
  1 draw. 후보 **삽입 순서**(distanceList 순회 = BFS layer 순)가 choice 결과를 좌우 — LinkedHashMap/순서 보존.
- **trailing unique-item-lottery seed (단련/강행/숙련전환/전투태세/특기초기화).** `tryUniqueItemLottery(
  genGenericUniqueRNGFromGeneral(general, static::$actionName), …)`는 **메인 turn rng와 별개 seed**
  (`lotteryActionName` 토큰). 메인 draw stream에 섞지 말 것. GeneralActionDefinition.lotteryActionName 사용.
- **rounding.** 탈취 본국 회수 `Util::round($gold*0.7)` = half-away → `PhpRound`. valueFit(comm - gold/12,0)
  의 나눗셈은 PHP float, round 아님 — `Util::valueFit` 의미 그대로.
- **로그 byte-parity.** 화계 성공 PLAIN 라인 `"도시의 농업이 <C>{agri}</>, 상업이 <C>{comm}</>만큼 감소하고,
  장수 <C>{injuryCount}</>명이 부상 당했습니다."`, 첩보 dist 3-tier(`정보를 많이/어느 정도/소문만`) +
  cityBrief/cityDevel RAWTEXT + 병종 RAWTEXT, 모반 `<Y><b>【모반】</b></>…찬탈` global-history. Josa(`이`/`을`/`로`)
  PHP `JosaUtil::pick` 결과와 일치. **로그 순서 = 실행 순서** (global→general 순, exp/ded 적용 전후 위치 보존).
- **insertion-order.** 첩보 spy KV(`spyInfo[destCityID]=3`) + nation `spy` jsonb는 LinkedHashMap.
  연구 family aux(`aux[auxType->value]=1`)도 기존 키 순서 보존(Json::encode 순서).
- **flush-delta.** 모든 mutation은 ChangeRecorder dirty/created로만 — 계략은 destCity(dirty) +
  destNation(dirty, 탈취) + actor(dirty); 모반은 actor+lord 둘 다 dirty(officer_level swap); 연구는
  nation(dirty, aux/gold/rice). inline DB write 금지 (one-daemon-write-rule).
- **모반시도 ↔ ruler succession (WAVE 1).** 모반은 `officer_level=12↔1` swap만 — `nextRuler`/`deleteNation`
  cascade는 호출 안 함(군주 교체이지 멸망 아님). 단 dest(lord) general을 second-general로 mutate해야 하므로
  `GeneralActionDraft.destGeneral` carrier 사용. WAVE1과 **파일 disjoint**(WAVE1은 DaemonLoopConfig/succession).

## 오픈 질문

- **OQ1 (모반시도 ↔ WAVE 1).** 모반 자체는 officer_level swap만이라 WAVE1 succession 포트와 독립이지만,
  daemon-seam에서 lord general의 second-write가 ChangeRecorder dirty로 정상 flush되는지(2-general dirty)는
  WAVE1의 dest-general flush 배선과 겹칠 수 있다 — 통합 시점 확인 필요(논리 포트는 선행 가능).
- **OQ2 (등용수락 trigger model).** che_등용수락은 reservable=false(non-reservable accept-trigger). 발신측
  che_등용(이미 DONE, send-only)이 P6에서 mailbox/accept를 deferred했다. 수락 trigger의 실 호출 경로
  (DiplomaticMessage.accept 류 vs general-turn 직접)와 golden 캡처 가능 여부(1010에서 reachable한가)를
  /parity-close 단계에서 확정 — reachable 아니면 sibling-code-path byte-match로 quarantine + backlog.
- **OQ3 (cr_인구이동 동작).** PARITY_LEDGER가 "포팅 시 동작 확정"으로 남긴 유일 항목. cr_인구이동.php run()
  의 pop-이동 알고리즘(출발/도착 도시·이동량·RNG 유무)을 포트 직전 정독 후 RNG-bearing 여부 최종 확정
  (현재 표는 TBD). golden 캡처 대상 여부가 여기서 갈린다.
- **OQ4 (catalog 노출 정책).** F0-B에서 General 13종을 catalog(modal)에 넣되, 계략 4종은 reqArg(destCityID)
  이고 NotOccupiedDestCity 등 적국-도시 타게팅이라 modal arg-picker(argType="city")가 적국 도시를 고를 수
  있어야 한다 — argTypeOf("destCityID")="city" 기존 매핑으로 충분한지(아국 vs 적국 필터) FE 확인 필요.
- **OQ5 (research 8종 골든 1-vs-8).** 극병 1종만 캡처 + 7종 파라미터화 검증을 제안했으나, auxType별
  preReqTurn/cost가 정말 동일한지(getPreReqTurn 23·cost 100000 고정인지)를 8개 PHP 파일 cross-check 후
  확정 — 하나라도 다르면 그 종만 별도 캡처.
