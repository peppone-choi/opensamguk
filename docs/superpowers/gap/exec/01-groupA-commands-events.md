# 실행계획 — 그룹 A: 명령 + 이벤트 액션 포팅

> 데이터 소스: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스), legacy PHP grand truth grep. 날조 없음 — 모든 RNG-bearing 판정·file:line 근거는 실제 PHP grep 결과.
> PHP=grand truth(`legacy/devsam-core/hwe/sammo`). RNG draw 순서/개수/메서드 인자 = 패러티 타깃. 골든은 `tools/php-golden` 실제 캡처에서만, 날조 금지.
> 빌드 금지(읽기 전용 계획). 게이트는 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`로 실행할 것(실행은 별도 세션).

## 모듈 매핑 규칙 (그룹 A 공통)

- **명령 포팅** = `logic/.../actions/<family>/<Cmd>.kt`(ActionDefinition: parseArgs/buildConstraints/resolve) + `logic/.../actions/CommandRegistry.kt`(when 분기 1줄) + `app/game-api/.../reserve/CommandWireMapper.kt`(intake 식별자, turn-reserved는 기본 미포함이 정책) + `app/game-api/.../web/AvailableCommandsController.kt`(GENERAL_COMMAND_CODES 카탈로그 노출) + `app/game-engine/.../turn/ReservedTurnHandler.kt`(registry.resolve 디스패치 — 이미 범용 배선됨) + golden.
- **이벤트 액션 포팅** = `logic/.../world/<Action>.kt`(EventAction leaf) + `logic/.../world/A3EventActions.kt`(`.register("<Key>") { ... }`) — `WorldActions.register`가 `A3EventActions.register`를 이미 체이닝(`WorldActions.kt:24`)하므로 A3 키 추가만으로 엔진 노출됨.
- **골든필요(RNG-bearing Y)** 기준: PHP run() 본문이 `$rng->next*/choice*/pick*`를 실제로 draw하면 Y → `tools/php-golden` 캡처 + draw-for-draw GoldenTest. draw 0이면 N → deterministic effect/log byte-parity GoldenTest로 충분(draw 시퀀스 게이트 불요, 0-draw 명시 assert).
- 등록 지점 근거: `CommandRegistry.kt:93-132`(when actionCode), `AvailableCommandsController.kt`(GENERAL_COMMAND_CODES), `ReservedTurnHandler.kt:259`(resolve 호출), `A3EventActions.kt:23-25`, `WorldActions.kt:20-36`.
- 계략 카탈로그는 **이미 존재**: `GameConst.kt:437-441` "계략" to [che_선동, che_탈취, che_파괴, che_화계], `GameConst.kt:430`(che_첩보). sabotage 상수 `GameConst.kt:34-35`(sabotageDamageMin=100/Max=800).

---

## A1 — 미포팅 명령 (29 command)

RNG-bearing 정밀 판정(legacy grep, type-hint 제외 본문 draw만):

| 파일 | 본문 draw | 골든 |
|------|-----------|------|
| che_화계 | nextRangeInt×2(피해) + nextBool(성공판정) + nextRangeInt 부상 + exp/ded nextRangeInt(성공/실패 각각) | **Y** |
| che_파괴 | nextRangeInt×2(def/wall) | **Y** |
| che_탈취 | nextRangeInt×2(gold/rice) | **Y** |
| che_선동 | nextRangeInt(secu) + nextRange(trust)/50 | **Y** |
| che_첩보 | nextRangeInt(1,100) exp + nextRangeInt(1,70) ded | **Y** |
| che_단련 | choiceUsingWeightPair + choiceUsingWeight | **Y** |
| che_접경귀환 | choice(nearestCityList) ×1 | **Y** |
| che_강행 | 본문 draw 0 (grep -c=1은 RandUtil type-hint) | **N** |
| che_숙련전환 | 0 | **N** |
| che_전투태세 | 0 | **N** |
| che_모반시도 | 0 | **N** |
| che_전투특기초기화 | 0 | **N** |
| che_내정특기초기화 | 0 (23줄, 박형 위임) | **N** |
| che_등용수락 | 0 (accept-trigger) | **N** |
| cr_인구이동 | 0 | **N** |

### A1 실행표

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| che_화계 | command | Command/General/che_화계.php:223,224,288,292,293,325,326 | logic/.../actions/military/CheHwagye.kt(신규) + CommandRegistry "che_화계" + AvailableCommandsController 카탈로그(GameConst 계략 이미 등록) + golden | sabotage 상수(GameConst:34-35, 존재), 계략 아이템 hook(ItemHooks.kt 존재), 부상($injuryGeneral) | **Y** | CheHwagyeGoldenTest(draw 순서: 피해 nextRangeInt×2 → 성공 nextBool → [실패분기: exp/ded nextRangeInt(1,100)/(1,70)] / [성공분기: 부상 + 아이템소비 + exp/ded nextRangeInt(201,300)/(141,210)]) byte-match + after-state | 🔴 미포팅 | resolve()에 run()(che_화계.php:55-352) 두 분기 draw-순서 그대로 이식: prob=sabotageDefaultProb+calcSabotageAttackProb-calcSabotageDefence, /dist, valueFit(0,0.5). agri/comm nextRangeInt valueFit(null,city). nextBool(prob) 실패→exp/ded+statType_exp+1+로그; 성공→부상+계략아이템 tryConsumeNow+exp/ded(201-300/141-210)+statType_exp. PhpRound·valueFit·JosaUtil. CheMuljaJodal.kt:58-94가 draft/rng/log API 레퍼런스 |
| che_파괴 | command | Command/General/che_파괴.php:33,34 | logic/.../actions/military/ChePagoe.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, 도시 def/wall 변이 seam | **Y** | ChePagoeGoldenTest(nextRangeInt×2 → city def/wall valueFit 감소 + 로그) draw-for-draw | 🔴 미포팅 | resolve(): defAmount/wallAmount = valueFit(nextRangeInt(min,max), null, city[def/wall]); city.def/wall 차감 draft 적재; secuAmount/번호서식 로그. 60줄 소형 — 화계 prob/부상 없음(피해+로그만) |
| che_탈취 | command | Command/General/che_탈취.php:39,40 | logic/.../actions/military/CheTalchwi.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, yearCoef, commRatio/agriRatio, 국가 gold/rice 적립 seam | **Y** | CheTalchwiGoldenTest(gold/rice nextRangeInt×2 + level·yearCoef·ratio 곱 + PhpRound + 본인/국가 자원 이동) | 🔴 미포팅 | resolve(): gold=nextRangeInt(min,max)*city.level*yearCoef*(0.25+commRatio/4); rice 동형(agriRatio). PhpRound(half-away) 적용, toInt 절단 구분. 탈취 자원 본인/국가 적립 draft + 로그 |
| che_선동 | command | Command/General/che_선동.php:34,36 | logic/.../actions/military/CheSeondong.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, city secu/trust 변이, 부상 count | **Y** | CheSeondongGoldenTest(secu nextRangeInt → trust nextRange/50 → injuryCount 로그) draw 순서 | 🔴 미포팅 | resolve(): secuAmount=valueFit(nextRangeInt,null,city.secu); trustAmount=valueFit(nextRange(min,max)/50,...); city.secu/trust 차감; injuryCount + number_format 로그("치안 …, 민심 …, 장수 …명 부상"). nextRange(실수) vs nextRangeInt(정수) draw 구분 |
| che_첩보 | command | Command/General/che_첩보.php:205,206 | logic/.../actions/military/CheCheobo.kt + CommandRegistry + 카탈로그(GameConst:430 존재) + golden | spy KV(nation.spy json), dist 분기, 첩보 fog 가시성(이미 구현 6a2b1f9) | **Y** | CheCheoboGoldenTest(dist 분기 로그 + spy[destCity]=3 KV + exp nextRangeInt(1,100) + ded nextRangeInt(1,70)) | 🔴 미포팅 | resolve(): dist별 정보로그(0/2/else 분기), nation.spy json[destCityID]=3 KV write, exp/ded nextRangeInt, increaseInheritancePoint(active_action, **0.5** — 첩보만 예외 주석 php:213), gold/rice 차감, leadership_exp+1, StaticEventHandler, checkStatChange. exportJSVars(cities/distanceList)는 read DTO |
| che_단련 | command | Command/General/che_단련.php:89,117 | logic/.../actions/develop/CheDanryeon.kt + CommandRegistry + 카탈로그 + golden | choiceUsingWeightPair/choiceUsingWeight RNG, 무병사 가드 | **Y** | CheDanryeonGoldenTest(choiceUsingWeightPair → choiceUsingWeight 2-draw 순서 + incStat) | 🔴 미포팅 | resolve(): [pick,multiplier]=choiceUsingWeightPair([...]); incStat=choiceUsingWeight([leadership,strength,intel]). 병사 없는 단련(crew==0 가드). 능력경험치 증가 + 로그. CheGyeonmun(A2)과 SightseeingMessage 가중치 패턴 공유 |
| che_접경귀환 | command | Command/General/che_접경귀환.php:92 | logic/.../actions/military/CheJeopgyeongGwihwan.kt + CommandRegistry(미등록 — missing-port) + 카탈로그 + golden | nearestCityList(국경 인접) 계산, CheGwihwan.kt 형제 | **Y** | CheJeopgyeongGwihwanGoldenTest(choice(nearestCityList) 1-draw → city 이동 + 로그) | 🔴 미포팅 | che_귀환(CheGwihwan.kt)과 **별개 커맨드**(접경=인접 적/공백 도시로 귀환). nearestCityList 구성(php:75-92) 후 destCityID=rng.choice(list). general.city 변경 draft + "{도시}로 귀환" 로그. choice 인자(list 순서)가 draw 패러티 핵심 |
| che_강행 | command | Command/General/che_강행.php | logic/.../actions/military/CheGanghaeng.kt + CommandRegistry + 카탈로그(GameConst:434 존재) + golden(deterministic) | 이동거리/소모 계산, CheIdong.kt 형제 | **N** | CheGanghaengGoldenTest(0-draw 명시 + 이동 경로/추가소모 effect/log byte) | 🔴 미포팅 | 강행군(무리한 이동) — 일반 이동 대비 더 먼 거리·추가 gold/rice 소모. draw 없음. CheIdong.kt 경로계산 재사용 + 강행 추가비용. deterministic — 캡처 후 effect/log 고정 |
| che_숙련전환 | command | Command/General/che_숙련전환.php:159-173 | logic/.../actions/military/CheSukryeonJeonhwan.kt + CommandRegistry + 카탈로그 + golden(det) | dex{armType} 변수, getDexLevelList | **N** | CheSukryeonJeonhwanGoldenTest(0-draw + dex 이전 + 로그) | 🔴 미포팅 | srcDex=dex{srcArmType}, cutDex 차감, addDex를 dex{destArmType}에 가산(php:159-166). "{src}숙련 {cut}을 {dest}숙련 {add}로 전환" 로그(JosaUtil 을/로). exportJSVars(armType/dexLevelList)는 read. draw 없음 |
| che_전투태세 | command | Command/General/che_전투태세.php:53-55 | logic/.../actions/military/CheJeontuTaese.kt + CommandRegistry + 카탈로그 + golden(det) | crew, train/atmos margin, techCost | **N** | CheJeontuTaeseGoldenTest(0-draw + train/atmos 변이 + cost) | 🔴 미포팅 | cost=[round(crew/100*3*techCost),0](php:55, PhpRound). 전투태세 전환 시 훈련/사기 변동. constraints(php:39-47): NotBeNeutral/NotWanderingNation/OccupiedCity/ReqGeneralCrew/ReqGeneralGold/ReqGeneralRice/ReqGeneralTrainMargin(max-10)/ReqGeneralAtmosMargin(max-10) 정확 이식. draw 없음 |
| che_모반시도 | command | Command/General/che_모반시도.php:69-96 | logic/.../actions/nation/CheMobanSido.kt + CommandRegistry + 카탈로그 + golden(det) | officer_level 12=군주 쿼리, 군주 강등 | **N** | CheMobanSidoGoldenTest(0-draw + officer_level swap + 로그 4종) | 🔴 미포팅 | WAVE_7.md 신규계획 항목. lordID=군주(officer_level=12) 쿼리; general.officer_level=12, lord.officer_level=1(php:81-83). 【모반】 globalHistory + generalAction "모반 성공" + generalHistory + lordLogger history(박탈) 4종 로그(JosaUtil 이/가). draw 없음 |
| che_전투특기초기화 | command | Command/General/che_전투특기초기화.php | logic/.../actions/personnel/CheJeontuTeukgiChogihwa.kt + CommandRegistry + 카탈로그 + golden(det) | 전투특기 슬롯 reset, 비용 | **N** | CheJeontuTeukgiChogihwaGoldenTest(0-draw + 특기 클리어 + cost/log) | 🔴 미포팅 | InheritResets.kt(유산포인트 reset)와 **별개 서브시스템**. 전투특기(specialWar 류) 슬롯을 초기화. 비용 차감 + 로그. draw 없음. che_내정특기초기화와 형제 |
| che_내정특기초기화 | command | Command/General/che_내정특기초기화.php(23줄) | logic/.../actions/personnel/CheNaejeongTeukgiChogihwa.kt + CommandRegistry + 카탈로그 + golden(det) | 내정특기(specialDomestic) reset; 전투특기초기화 위임형 | **N** | CheNaejeongTeukgiChogihwaGoldenTest(0-draw + 특기 클리어) | 🔴 미포팅 | 23줄 박형 — che_전투특기초기화 로직 위임/공유(대상 특기 종류만 내정으로 교체). 전투특기초기화 포팅 후 파생. draw 없음 |
| che_등용수락 | command | Command/General/che_등용수락.php(217줄) | logic/.../actions/personnel/CheDeungyongSurak.kt + CommandRegistry + (intake: accept-trigger 경로) + golden(det) | 등용 메시지(DiplomaticMessage 류 scout) 소비, CheDeungyong.kt 짝 | **N** | CheDeungyongSurakGoldenTest(0-draw + 국가 이적 effect + 로그) | 🔴 미포팅 | P6 deferred(CheDeungyong.kt 주석). non-reservable **accept-trigger**(예약커맨드 아님) — 등용 제의 메시지 수락 시 장수 belong/nation 이적. che_불가침수락(A2)과 동일하게 메시지-수락 intake 경로 배선 필요. draw 없음 |
| cr_인구이동 | command | Command/Nation/cr_인구이동.php(197줄) | logic/.../actions/nation/CrInguIdong.kt + CommandRegistry "cr_인구이동" + AvailableCommandsController 국가카탈로그 + golden(det) | 도시 pop 이동(src→dest), 국가 커맨드(cr_) | **N** | CrInguIdongGoldenTest(0-draw + 두 도시 pop 변이 + 로그) | 🔴 미포팅 | cr_ = 국가 커맨드 패밀리(NationCommand). src 도시 인구를 dest 도시로 이동(거리·상한 제약). cr_건국(CrGeonguk.kt) 패턴 참조. draw 없음 |

#### Area4 흡수분 (Tier-0 Area4 wire 전제붕괴 → A1로 흡수)

> 정정 근거: DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 가 logic에 부재. **이들은 `Command`가 아니라 `legacy/.../API/` 핸들러**(instant/inherit-action). 명령 포팅과 wire 시드를 분리하지 말고 함께 처리.

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| DieOnPrestart | command(API/instant) | API/General/DieOnPrestart.php(extends BaseAPI) | logic/.../actions/instant/DieOnPrestart.kt + instant-action registry + game-api intake(instant) | 프리스타트(개전 전) 게이트, general 사망 처리 | **N** | DieOnPrestartGoldenTest(0-draw + 사망 effect/로그) | 🔴 미포팅 | 개전 전(prestart) 장수 사망 instant API. Command 아님 — BaseAPI 핸들러를 logic ActionDefinition + game-api instant intake로 모델링. draw 없음 |
| DropItem | command(API/instant) | API/General/DropItem.php(extends BaseAPI) | logic/.../actions/instant/DropItem.kt + instant registry + game-api intake | 보유 아이템 제거(item KV), 본인 effect | **N** | DropItemGoldenTest(0-draw + item 슬롯 clear + 로그) | 🔴 미포팅 | 아이템 버리기 instant. 장수 item 슬롯 비우기 draft + 로그. draw 없음 |
| InstantRetreat | command(API/instant wrapper) | API/General/InstantRetreat.php:64(commandObj->run(new RandUtil(...))) | logic/.../actions/instant/InstantRetreat.kt + instant registry + game-api intake | **내부에서 다른 Command를 RandUtil로 실행**(wrapper) — 대상 즉시퇴각 커맨드 의존 | **Y** | InstantRetreatGoldenTest(wrapped command draw-stream 보존) | 🔴 미포팅 | 즉시 퇴각 instant — 내부에서 commandObj.run(RandUtil(seed))로 퇴각 커맨드 실행(php:64). wrapped 커맨드의 draw가 그대로 노출되므로 **골든 Y**. RandUtil 시드 구성·draw 위임이 패러티 핵심 |
| ResetStat | command(API/InheritAction) | API/InheritAction/ResetStat.php:148(nextRangeInt(3,5)),149(choiceUsingWeight) | logic/.../actions/instant/inherit/ResetStat.kt + inherit-action registry + game-api intake | 유산포인트 reset 서브시스템, 능력치 재배분 | **Y** | ResetStatGoldenTest(nextRangeInt(3,5) → choiceUsingWeight 루프 draw-for-draw) | 🔴 미포팅 | 능력치 초기화 inherit-action. `foreach range(nextRangeInt(3,5)) { choiceUsingWeight([leadership,strength,intel]) }`(php:148-149) — 가변길이 루프 draw. 골든 Y. InheritResets.kt 인접 |
| CheckOwner | command(API/InheritAction) | API/InheritAction/CheckOwner.php(extends BaseAPI) | logic/.../actions/instant/inherit/CheckOwner.kt + inherit-action registry + game-api intake | 유산 소유권 검증(read-ish), 0-draw | **N** | CheckOwnerGoldenTest(0-draw + 소유권 판정/응답) | 🔴 미포팅 | 유산 아이템 소유권 확인 inherit-action. draw 없음. 대부분 검증/응답 — read seam에 가까우나 instant intake로 모델링 |

---

## A2 — 부분포팅 명령 run()/resolve() 마감 (등록O, 본체 스텁/누락 + 골든 부재)

> 공통: 등록·constraint·argTest는 이미 충실. **resolve() 본체 + 골든이 핵심 갭.** 외교 4종(종전/불가침)은 **DiplomaticMessage 발송 effect + message intake/flush가 공통 인프라 갭** — 한 번에 넓혀라(che_불가침수락/파기수락/종전수락은 instant-nation registry 미배선까지 공통).

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| che_견문 | command | Command/General/che_견문.php:55-122 | logic/.../actions/develop/CheGyeonmun.kt:39(스텁 resolve) + SightseeingMessage 테이블(신규) + golden | SightseeingMessage 17버킷 테이블 포팅, tryUniqueItemLottery/checkStatChange/StaticEventHandler(존재) | **Y** | Che견문GoldenTest(pickAction **정확히 2-draw**: choiceUsingWeightPair → choice, type 비트마스크별 exp/gold/rice/stat_exp, Wounded nextRangeInt(10,20)/(20,50) cap80) draw-for-draw + after-state(exp/gold/rice/injury/*_exp) | 🟡 스텁(35) | resolve()에 run() 전체 이식: (1)SightseeingMessage.pickAction 2-draw(choiceUsingWeightPair=버킷, choice=텍스트, php SightseeingMessage.php:106-111 순서). (2)type 비트마스크 exp/leadership_exp/strength_exp/intel_exp/gold/rice 증감 + DecGold/DecRice floor-0(increaseVarWithLimit). (3)Wounded/HeavyWounded nextRangeInt(10,20)/(20,50) cap80. (4):goldAmount:/:riceAmount: 치환 로그 → addExperience→checkStatChange→StaticEventHandler→tryUniqueItemLottery(php:111→117 순서). CheMuljaJodal.kt:58-94 레퍼런스 |
| che_해산 | command | Command/General/che_해산.php:62-119 + func.php:1713-1805(deleteNation) | logic/.../actions/founding/CheHaesan.kt:41(스텁) + golden | deleteNation cascade(GAP-WORLD seam), OccupyCity 이벤트, alternative 표현(GeneralActionDraft) | **N** | Che해산GoldenTest(0-draw 명시 + gold/rice 절삭 + cascade set + 로그 3종 + makelimit=12) byte/델타 | 🟡 스텁(35) | resolve() 충실 이식: (1)<init-turn: yearMonth<=init이면 "다음 턴부터 해산" 로그 + alternative=che_인재탐색 + early-return(GeongukTest.kt:253 lastAlternative 패턴). (2)국가 전 장수 gold>defaultGold→절삭, **gold>defaultRice→rice 절삭(legacy 버그 byte-동일 재현, php:90-95)**. (3)deleteNation cascade: belong/troop/officer_level/officer_city/nation=0, permission=normal, max_belong aux, PLAIN 멸망 로그+history, 【멸망】 global history, 도시 nation=0/front=0, troop/nation/nation_turn/diplomacy 삭제, ng_old_nations 보존. (4)군주 makelimit=12. (5)로그 3종(세력해산/global/history). (6)OccupyCity는 엔진 seam 위임 명기. draw 0 |
| che_인재탐색 | command | Command/General/che_인재탐색.php:55-(실패/성공 분기) | logic/.../actions/personnel/CheInjaeTamsaek.kt:47(스텁) + golden | NPC-pool 생성(P6 write-seam), pickGeneralFromPool, foundProp 월드쿼리 시드, fillRemainSpecAsRandom | **Y** | Che인재탐색GoldenTest(실패+성공 두 시드: foundProp nextBool → [실패: choiceUsingWeight, exp+100/ded+70/stat+1] / [성공: age nextRangeInt(20,25), deathYear delta nextRangeInt(10,50), pickGeneralFromPool 이름 picking, exp+200/ded+300/stat+3]) draw-for-draw | 🟡 스텁(35) | resolve() 두 분기 draw-순서: foundProp=calcFoundProp(maxgeneral, npc count들)→totalGen/totalNpcCnt는 월드쿼리 시드 ctx 주입(ReservedTurnHandler.kt:252-253 패턴). foundNpc=nextBool(foundProp). 실패: choiceUsingWeight(경험가중)+gold-=req(0하한)+exp+100+ded+70+incStat1+checkStatChange+tryUniqueItemLottery(genGenericUnique '인재탐색'). 성공: age nextRangeInt(20,25), deathYear delta nextRangeInt(10,50), pickGeneralFromPool(0,1) 이름(CheUibyeongMojip.kt:89), fillRemainSpecAsRandom, increaseInheritancePoint(P6 seam no-write 주석), 자원차감+exp200/ded300/stat3+StaticEventHandler+tryUniqueItemLottery. 로그 3종. NPC row insert는 P6 write-seam 위임(draw 순서만 정확히 소비) |
| che_종전제의 | command | Command/Nation/che_종전제의.php(run, draw 0) | logic/.../actions/nation/CheJongjeonJeui.kt(resolve 로그만) + **DiplomaticMessage 발송 effect**(공통) + golden | message:send effect kind(신규), message store/mailbox flush, validUntil=max(30,turnterm*3) | **N** | Che종전제의GoldenTest(0-draw + DiplomaticMessage(TYPE_STOP_WAR) payload/validUntil/title + 장수로그 byte) + intake IT(메일박스 적재) | 🟡 부분(35) | (1)message:send effect 추가 → resolve()가 src(장수/국가 color/image)·dest(destNation id0/name/color)·title='{국명}의 종전 제의 서신'·validUntil=now+max(30,turnterm*3)분·option{action:STOP_WAR,deletable:false} emit. (2)ReservedTurnHandler/instant 경로에서 message store flush 배선. (3)dest ActionLogger(빈 flush 확인). (4)StaticEventHandler 훅(외교 공통). che_종전수락 짝 |
| che_불가침제의 | command | Command/Nation/che_불가침제의.php(run, draw 0) | logic/.../actions/nation/CheBulgachimJeui.kt(로그만) + DiplomaticMessage effect(공통) + golden | message:send effect, mailbox(9000+destNationId), turnterm | **N** | Che불가침제의GoldenTest(0-draw + DiplomaticMessage(TYPE_NO_AGGRESSION) year/month payload/validUntil + 로그) + intake IT | 🟡 부분(35) | constraints(beChief/notBeNeutral/existsDestNation/differentDestNation/reqMinimumTreatyTerm 6개월/disallowDiplomacyBetweenStatus) 이미 충실. (1)외교 메시지 effect emit: title '{국명}와 {year}년 {month}월까지 불가침 제의 서신', action='no_aggression', payload{year,month}, validUntil=max(30,turnterm*3). (2)메일함 insert flush. (3)setResultTurn(LastTurn), StaticEventHandler. che_불가침수락이 소비할 페이로드 |
| che_불가침파기제의 | command | Command/Nation/che_불가침파기제의.php(run, draw 0) | logic/.../actions/nation/CheBulgachimPagiJeui.kt(로그만) + DiplomaticMessage effect(공통) + golden | message:send effect, custom actionContextBuilder(turnterm 주입), che_불가침파기수락 짝 | **N** | Che불가침파기제의GoldenTest(0-draw + DiplomaticMessage(TYPE_CANCEL_NA, deletable=false) + 로그) + intake IT | 🟡 부분(35) | (1)message-send effect: msgType=diplomacy, mailbox=9000+destNationId, validUntil=now+max(30,turnterm*3), option{action:cancel_na, deletable:false}. (2)turnterm 주입 위해 custom actionContextBuilder(che_불가침제의 패턴). (3)setResultTurn/StaticEventHandler. (4)che_불가침파기수락이 키로 찾아 소비. FE submit(reqArg destNationId) |
| che_불가침수락 | command(instant-nation) | Command/Nation/che_불가침수락.php | logic/.../actions/instant/nation/che_불가침수락(현 ActionDef 실재, orphaned) → opensamguk logic 포팅 + **instant-nation registry**(공통) + message-accept intake | instant-nation registry/loader(신규, 형제 공통), recv_assist/resp_assist KV, destNation 이름 조회 | **N** | Che불가침수락GoldenTest(0-draw + diplomacy:patch state=7/term + resp_assist KV + 로그 4종) + accept intake IT | 🟡 orphaned(35) | (1)instant-nation registry/loader 신설(키 che_불가침수락 +형제). (2)message-accept intake: 수락 시 ActionDef load→constraint(hasFullConditionMet)→resolve, 실패시 INVALID. (3)resp_assist["n{id}"]=[id, recv[..][1]??0] KV effect. (4)로그 4종(actor action+history, dest general+history "{국명}와 {year}년 {month}월까지 불가침에 성공"). (5)destNationName=실제 국명+JosaUtil('와')(현재 숫자 id). (6)constraint 추가 ReqDestNationValue('nation','소속','==',destGeneral.nationID). (7)argTest: destGeneralId≠self, year>=startYear |
| che_불가침파기수락 | command(instant-nation) | Command/Nation/che_불가침파기수락.php | logic/.../actions/instant/nation/che_불가침파기수락(ActionDef 실재, orphaned) → opensamguk 포팅 + instant-nation registry(공통) + intake | instant-nation registry(공통), 양방향 diplomacy patch, destNation 이름 | **N** | Che불가침파기수락GoldenTest(0-draw + diplomacy 양방향 state=2/term=0 + 로그 6종 + StaticEventHandler) | 🟡 orphaned(35) | (1)registry/intake 공통 배선(형제와 함께). (2)resolve() 누락 effect: 로그 6종(현재 1건) + destGeneral 로그 + StaticEventHandler 등가. (3)diplomacy 양방향 state=2,term=0. (4)destNationName 국명 조회(숫자 id 수정). (5)constraint 소속검증(level>0 치환 → nation==destGeneral.nationID) |
| che_종전수락 | command(instant-nation) | Command/Nation/che_종전수락.php | logic/.../actions/instant/nation/che_종전수락(ActionDef 실재, resolve 2 patch+9 log) → opensamguk 포팅 + instant-nation registry(공통) + message-accept intake | instant-nation registry/loader(공통), SetNationFront effect, diplomacy:patch(엔진 지원 inMemoryWorld.applyDiplomacyPatch) | **N** | Che종전수락GoldenTest(0-draw + diplomacy 양방향 state=2/term=0 + 9 log 순서 + SetNationFront×2) | 🟡 orphaned(45) | (1)INSTANT_NATION_COMMAND_KEYS + loader 신설(종전수락+불가침수락+불가침파기수락 공통). (2)diplomacy 종전 메시지 수락 intake: load→constraint→resolve→flush(diplomacy:patch 엔진 지원). (3)누락 effect: SetNationFront(nationId+destNationId, patch 후), StaticEventHandler. (4)constraint 치환 복원(level>0 → ReqDestNationValue nation==destGeneral.nationID). (5)dest-side 로그 flush 순서 |

#### A2 공통 인프라 (외교 4종 + accept 3종이 공유 — 1회 넓힘)

| 인프라 | 대상 | 소비처 | 노트 |
|--------|------|--------|------|
| **message:send effect kind** | logic actions engine effect 유니온 + ReservedTurnHandler/instant flush 분기 + message store/mailbox(diplomacy=9000+destNationId) | che_종전제의/불가침제의/불가침파기제의 | validUntil=now+max(30,turnterm*3)분 공통 공식. turnterm 주입 위해 custom actionContextBuilder 필요 |
| **instant-nation registry/loader** | INSTANT_NATION_COMMAND_KEYS + 동적 importer + loadInstantNationActionSpecs + 엔진 definition map | che_종전수락/불가침수락/불가침파기수락(현 orphaned) | turn 커맨드의 NATION_TURN_COMMAND_KEYS 대응물. 메시지-수락 intake가 이걸로 dispatch |
| **message-accept intake** | game-api 외교 메시지 accept 엔드포인트(router/diplomacy respondLetter agree 분기) → ActionDef load→constraint→resolve→flush | accept 3종 + che_등용수락(A1) | 현재 agree 분기는 letter를 ACTIVATED로만 flip하고 커맨드 미실행 → 수락이 무효 |
| **StaticEventHandler 외교 훅** | 외교 커맨드 실행 후 정적 이벤트 핸들러 호출 공통 메커니즘 | 외교 7종 공통 | StaticEventHandler.kt 존재 — 외교 커맨드 resolve 후 호출 지점 배선 |

---

## A3 — 이민족/NPC 이벤트 액션 (12 event)

RNG-bearing 정밀 판정(legacy Event/Action grep):

| 파일 | 본문 draw | 골든 |
|------|-----------|------|
| RaiseInvader | choice(capital) + nextRangeInt(leadership/mainStat 등) ×다수 | **Y** |
| InvaderEnding | 0 | **N** |
| AutoDeleteInvader | 0 | **N** |
| RaiseNPCNation | choice(cities) + choice(colors) + pickGeneralFromPool 등 | **Y** |
| RegNPC | 0 (외부 주입 NPC 등록만) | **N** |
| RegNeutralNPC | 0 | **N** |
| CreateManyNPC | pickGeneralFromPool + nextRangeInt(age 등) ×다수 | **Y** |
| CreateAdminNPC | 0 (13줄) | **N** |
| BlockScoutAction | 0 | **N** |
| UnblockScoutAction | 0 | **N** |
| ChangeCity | 0 | **N** |
| LostUniqueItem | nextBool(lostProb) | **Y** |

### A3 실행표

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| RaiseInvader | event | Event/Action/RaiseInvader.php:76,240,241(+다수) | logic/.../world/RaiseInvaderAction.kt(신규) + A3EventActions.register("RaiseInvader") | Tier-0 Area2(NPC/도시 생성 seam), capital 후보 계산, findNextCapital(BFS — PHP wins), 스펙 nextRangeInt | **Y** | RaiseInvaderActionTest + Golden(choice(capitalCandidates) → 장수 leadership/mainStat nextRangeInt(specAvg*1.2~1.4) 루프) draw-for-draw | 🔴 미포팅 | 게임 후반 핵심 이벤트(이민족 침략자 발생). newCapital=choice(capitalCandidates)(php:76). 침략 NPC 다수 생성: leadership/mainStat=nextRangeInt(toInt(specAvg*1.2), toInt(specAvg*1.4))(php:240-241) 등. NPC row 생성은 Area2 seam 위임, draw 순서만 정확. A3EventActions.kt:23-25 register 패턴 |
| InvaderEnding | event | Event/Action/InvaderEnding.php(73줄) | logic/.../world/InvaderEndingAction.kt + A3EventActions.register | 침략자 종료 정리, 보상/로그 | **N** | InvaderEndingActionTest(0-draw + 종료 effect/로그) | 🔴 미포팅 | 침략자 이벤트 종료(보상/정리). draw 없음. RaiseInvader 짝 |
| AutoDeleteInvader | event | Event/Action/AutoDeleteInvader.php(45줄) | logic/.../world/AutoDeleteInvaderAction.kt + A3EventActions.register | 침략자 자동 삭제(조건부 NPC/국가 제거) | **N** | AutoDeleteInvaderActionTest(0-draw + 삭제 cascade) | 🔴 미포팅 | 침략자 자동 삭제(만료 조건). draw 없음 |
| RaiseNPCNation | event | Event/Action/RaiseNPCNation.php:60,158(+pickGeneralFromPool) | logic/.../world/RaiseNPCNationAction.kt + A3EventActions.register | Area2 NPC/국가 생성 seam, GetNationColors, pickGeneralFromPool | **Y** | RaiseNPCNationActionTest + Golden(choice(cities) → choice(GetNationColors) → pickGeneralFromPool 이름 picking) draw-for-draw | 🔴 미포팅 | NPC 국가 발생. target=choice(cities)(php:60), color=choice(GetNationColors())(php:158), 군주/장수 pickGeneralFromPool. draw 순서가 패러티 핵심. 국가/도시/장수 row는 Area2 seam |
| RegNPC | event | Event/Action/RegNPC.php(61줄, draw 0) | logic/.../world/RegNPCAction.kt + A3EventActions.register | Area2 장수 등록 seam(외부 주입 NPC) | **N** | RegNPCActionTest(0-draw + general row 적재) | 🔴 미포팅 | NPC 장수 등록(사전 정의 NPC를 월드에 주입). 본문 draw 없음(pickGeneralFromPool도 미사용). general 생성 seam만 |
| RegNeutralNPC | event | Event/Action/RegNeutralNPC.php(59줄, draw 0) | logic/.../world/RegNeutralNPCAction.kt + A3EventActions.register | Area2 장수 등록 seam(nation=0 중립) | **N** | RegNeutralNPCActionTest(0-draw + 중립 general row) | 🔴 미포팅 | 중립 NPC 장수 등록(nation=0). RegNPC 변형. draw 없음 |
| CreateManyNPC | event | Event/Action/CreateManyNPC.php:38,39(+다수) | logic/.../world/CreateManyNPCAction.kt + A3EventActions.register | Area2 NPC 생성 seam, pickGeneralFromPool, fillRemainSpecAsRandom | **Y** | CreateManyNPCActionTest + Golden(pickGeneralFromPool(0,cnt) → age nextRangeInt(20,25) 루프 + spec draw) draw-for-draw | 🔴 미포팅 | 다수 NPC 일괄 생성. pickGeneralFromPool(db,rng,0,cnt)(php:38), 각 age=nextRangeInt(20,25)(php:39) + fillRemainSpecAsRandom. CheUibyeongMojip NPC 패턴. draw 순서/개수(cnt 루프) 핵심 |
| CreateAdminNPC | event | Event/Action/CreateAdminNPC.php(13줄, draw 0) | logic/.../world/CreateAdminNPCAction.kt + A3EventActions.register | Area2 장수 생성 seam(행정 NPC) | **N** | CreateAdminNPCActionTest(0-draw + admin general) | 🔴 미포팅 | 행정 NPC 생성(13줄 박형). draw 없음. CreateManyNPC/Reg* 위임 가능성 — legacy 확인 |
| BlockScoutAction | event | Event/Action/BlockScoutAction.php(24줄, draw 0) | logic/.../world/BlockScoutAction.kt + A3EventActions.register | 정찰/임관 차단 플래그(env/global KV) | **N** | BlockScoutActionTest(0-draw + block 플래그 set) | 🔴 미포팅 | 정찰(스카웃/임관) 차단 플래그 set. draw 없음. env/global KV 토글 |
| UnblockScoutAction | event | Event/Action/UnblockScoutAction.php(25줄, draw 0) | logic/.../world/UnblockScoutAction.kt + A3EventActions.register | 차단 해제 플래그 | **N** | UnblockScoutActionTest(0-draw + block 해제) | 🔴 미포팅 | 정찰 차단 해제. BlockScoutAction 짝. draw 없음 |
| ChangeCity | event | Event/Action/ChangeCity.php(189줄, draw 0) | logic/.../world/ChangeCityAction.kt + A3EventActions.register | 도시 속성(level/region/supply 등) 변경 effect | **N** | ChangeCityActionTest(0-draw + 도시 속성 patch) | 🔴 미포팅 | 도시 속성 변경 이벤트(189줄 — 여러 속성 분기). draw 없음. 도시 row patch draft |
| LostUniqueItem | event | Event/Action/LostUniqueItem.php:61(nextBool(lostProb)) | logic/.../world/LostUniqueItemAction.kt + A3EventActions.register | 유니크 아이템 KV, 확률 분실 | **Y** | LostUniqueItemActionTest + Golden(nextBool(lostProb) → item clear/로그) draw-for-draw | 🔴 미포팅 | 유니크 아이템 확률 분실. nextBool(lostProb)(php:61) 단일 draw 후 분실 시 item 슬롯 clear + 로그. 1-draw 골든 |

---

## 실행 순서 권고 (의존·foundation-first)

1. **A2 공통 인프라 먼저**(message:send effect, instant-nation registry/loader, message-accept intake, StaticEventHandler 외교훅) — 외교 7종이 전부 consume. 단일 creator-then-consumer 시퀀스.
2. **A3 Area2 NPC/도시 생성 seam** — RaiseInvader/RaiseNPCNation/CreateManyNPC/Reg* 가 공유. seam 먼저, 액션은 disjoint 병렬.
3. **A1 계략 5종**(화계/파괴/탈취/선동/첩보) — sabotage 상수·카탈로그 이미 존재, 서로 disjoint 파일 → 병렬. 각 골든 Y.
4. **A1 deterministic 명령**(강행/숙련전환/전투태세/모반시도/특기초기화 2종/등용수락/cr_인구이동) — draw 0, 병렬. 등용수락은 A2 message-accept intake 의존.
5. **A1 Area4 흡수**(InstantRetreat/ResetStat 골든 Y; DieOnPrestart/DropItem/CheckOwner det) — instant/inherit-action registry(신규) 의존.
6. **A2 부분포팅 본체**(견문/해산/인재탐색/외교 4종) — 1·2 인프라 위에서.

> 골든 Y 총: A1 계략5 + 단련 + 접경귀환 + InstantRetreat + ResetStat = 9; A2 견문 + 인재탐색 = 2; A3 RaiseInvader + RaiseNPCNation + CreateManyNPC + LostUniqueItem = 4. **합 15건이 `tools/php-golden` 실제 캡처 필요.** 나머지는 deterministic effect/log byte 캡처(0-draw 명시 assert).
> 모든 골든은 PHP 실제 캡처에서만 뱅킹. 캡처 불가 시 quarantine + 백로그(sibling byte-match 증명) — 날조·게이트 약화·골든 편집 금지.
