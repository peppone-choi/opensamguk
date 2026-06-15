# LEDGER — bug-parity-2026-06-15 루프

목적: "ulw 버그 확인 및 수정 및 QA 및 패러티 확보" — opensamguk 패러티/로직/크래시/와이어링
버그를 하나씩(1바퀴=1버그) 잡고 고정 게이트 시험지로 회귀 0을 확인하며 닫는다.

행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`
(0바퀴 = 베이스라인. 채점자 칸 공란/"본인" = 무효. 폐기 가설 변형은 원가설 바퀴 링크.)

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | (베이스라인) | BE `:common:test`+`:logic:test` `--rerun-tasks`=BUILD SUCCESSFUL (전 스위트 green) + FE `tsc --noEmit` web/game·web/gateway 둘다 EXIT=0/0에러 | gate(결정적) — ctx_execute gradle grep BUILD SUCCESSFUL + tsc 직접 | 기준선 | branch loop-parity-2026-06-14-c @ cde6abca, 2026-06-15 KST. Docker down → infra/engine/api IT skip(채점 제외) |
| 1 | 로비맵 재난아이콘 회귀: MapPreviewController `state=city.frontState`→`city.state` + DTO doc + 신규테스트 (disaster-wave 07b3badb가 WorldMapController만 고치고 누락) | green→green + MapPreviewControllerTest 5/5(신규 'surfaces city.state not front_state' 포함)·WorldMapControllerTest 4/4 | fresh: :app:game-api:test XML(결정적) | 채택 | func_map.php:145-147 tuple[2]=state. 두 맵뷰어 불변식 복원. BE헌트#8/FE헌트#2 |
| 2 | troop 페이지 계약 정합: BE TroopsResponse에 myGeneralId/permission/멤버 cityName·npc/리더헤더 추가 + FE TroopInfo 실와이어 정렬 (크래시 + 모든 부대 mutation 버튼 dead 수정) | green→green + F4ReadControllersTest 29/29 + tsc green | fresh: game-api XML + tsc(결정적) | 채택 | FE가 BE 미발신 shape 소비→reservedCommandBrief.map undefined 크래시·myGeneralId 항상0. 멤버 cityName(#11)·부대명변경 permission≥4(#18) 동반. FE헌트#3/#4/#6/#11/#18 |
| 3 | diplomacy 서신 발송/회수/파기 인테이크 가드: isIntakeQueued/isIntakeDenied 적용 + 죽은 2단계 state 분기 제거 | green→green + tsc + 65 vitest green | fresh: tsc + vitest(결정적) | 채택 | 무조건 성공토스트(P0-04/06류) 제거. IntakeOutcome에 state 없어 파기 2단계 분기 dead. FE헌트#5/#10 |
| 4 | join 장수등록 4프리셋(랜덤/통무/통지/무지형) generalStats.ts 포팅 + 전콘(pic) 체크박스 | green→green + tsc green | fresh: tsc(결정적) | 채택 | legacy PageJoin.vue+generalStats.ts. pic=Join.php rule(boolean). 폼편의=RNG게이트 밖. FE헌트#13 |
| 5 | 맵 도시 툴팁 포맷 두 뷰어 동시정정: `【region|level】 name` + 국가명 2줄 (불변식) + types.ts state 주석 | green→green + tsc 양쪽 green | fresh: tsc web/game+gateway(결정적) | 채택 | legacy CityBasicCard.vue:10. MapViewer↔MapPreview byte-동일 유지. FE헌트#20/#12 |
| 6 | generals 페이지 컬럼 라벨 정정(명성→레벨, 명성칭호→명성) + 무국적 '무소속'→'-' | green→green + tsc green | fresh: tsc(결정적) | 채택 | sibling rankings/generals 라벨 + legacy '-' 표기. FE헌트#14/#16 |
| 7 | city 통솔보너스(+N) 색 limegreen→cyan | green→green + tsc green | fresh: tsc(결정적) | 채택 | legacy GeneralBasicCard.vue:32 style color:cyan. FE헌트#15 |
| 8 | rankings/npcs 종능/통무지/명성/계급 천단위콤마 제거→raw int | green→green + tsc green | fresh: tsc(결정적) | 채택 | legacy a_npcList.php:122-127 raw. FE헌트#17 |
| 9 | history 연감 드롭다운 라이브월(현재) 옵션 추가 + 다음달 가드 완화 | green→green + tsc green | fresh: tsc(결정적) | 채택 | legacy PageHistory.vue generateYearMonthList. FE헌트#21 |
| 10 | inherit ResetStat 검증 하드코딩 165→currentStat 합계 derive | green→green + tsc green | fresh: tsc(결정적) | 채택 | gameConst FE 미노출→currentStat sum 사용(GameConst defaultStatTotal=165 동치). FE헌트#23 |
| 11 | login next 파라미터 open-redirect 차단(내부 '/' 만 허용, '//' 차단) | green→green + tsc green | fresh: tsc(결정적) | 채택 | 보안: 프로토콜-상대 '//evil.com' 리다이렉트 차단. FE헌트#22 |
| 12 | lobby '입장' 행 장수 초상(picture) 64x64 렌더 (BE가 주는데 드롭) | green→green + tsc green | fresh: tsc(결정적) | 채택 | legacy getIconPath.ts(imgsvr→d_pic/d_shared). 표시-only. FE헌트#8 |
| 14 | RaiseDisaster city.trust 곱 누락 (재난=trust*ratio 무캡 / 호황=least(trust*ratio,100)) — 커밋 11b9ad75 | green→green + DisasterTrustApplyTest 3/0 + RaiseDisasterTest 10/0 | fresh: :app:game-engine:test+:logic:test XML(결정적) | 채택 | RaiseDisaster.php:129/154. trust=FLOAT raw 곱(round 없음). 엔진 applyDisaster postLogic+meta. BE헌트#2/#11 |
| 15 | flush-delta 3컬럼 누락: nation.power(#9)/general.officer_city(#17)/statisticInserts(#10) — 커밋 5be70e13 | green→green + JdbcFlushExecutorIT 6/0(power 1000→1234·officer_city 5→0·statistic step-12) | fresh: :infra:test XML(Docker IT, 결정적) | 채택 | process_war.php:705-708·func_gamerule.php:322-333. RowMapper는 방출하나 SET/payload 누락. BE헌트#9/#10/#17 |
| 13 | turn-loop CRITICAL: runTick 꼬리(applyKillturnDecrement+updateTurnTime) 미실행 + dueGenerals strict-< (PHP TurnExecutionHelper.php:153-230/:237 turntime<date) — 커밋 e8576473 | green→green + :app:game-engine:test 372/0(신규 DrainTailAdvance 5 + 기존 8 fixture 재정합) | fresh: :app:game-engine:test XML(결정적, Docker ITs 포함) | 채택 | 꼬리 미배선=killturn 미감소·kill/환생/유체이탈·lived_month·turntime advance 미발동. 8 fixture가 inclusive<=·killturn0 가정 → strict-<·killturn>0·E2E at=runTime·meta 결정적순서로 재정합(약화 0). AiSelectionGate 불변. BE헌트#1/#6 |
| 20 | 로비 미등록 진입 3-버튼 패러티(라이브 버그 1+2 UI) — 단일 '장수등록'→`/game/` 덤프를 entrance.ts 게이팅으로 교체: canCreate=`!(blockGeneralCreate&1)`→장수생성/game/join · canSelectNpc=`npcMode==1`→장수빙의/game(CharacterClaim) · canSelectPool=`npcMode==2`→장수선택/game/select-pool. gameUrl `/game` 베이스 정규화 | web/gateway tsc EXIT=0/0err | fresh: tsc(결정적) + 배치 적대리뷰(ship 전) | 채택(조건부) | entrance.ts:51-58,274-276. **라이브 s1 config npcmode 미시드(=0)라 빙의 버튼 실제표시는 config 의존** → 버그2 완결엔 npcmode 시드/어드민편집(버그4) 필요(백로그) |
| 19 | che_감축 level 제약 패러티 — 누락된 2번째 `ReqDestCityValue(level>origCityLevel)`(origCityLevel=`CityConst.byId(capital).level` 정적레벨) 추가 + **커널갭** `cityIntField "level"→c.level` 수정(미지원이라 감축 `>4,>orig`·증축 `>3,<8` 4 제약 전부 런타임 throw=latent-broken이었음) | logic 2154→2157 green (CheGamchukConstraintTest 3/0, GamchukJeungchuk 3/0, AI/selection 골든 불변) | fresh: **parity-reviewer**(적대적 che_감축.php byte + XML) | 채택 | che_감축.php:63-71. 정적레벨 출처 정확(dynamic 아님), errMsg byte-exact, op `>`. cityIntField 단일 case 추가(타 key 불변)로 증축도 co-fix. 행동 테스트(deny@level==orig / allow@level>orig). #14 |
| 18 | InvaderEnding 엔진배선 — dispatched no-op 닫음: `env[InvaderEndingContext.ENV_KEY]=wctx` 공급 + `WorldActionContext`가 `InvaderEndingContext`(10메서드) 구현 + `InMemoryTurnWorld.multiplyRefreshLimit`. (leaf 등록은 이미 logic `WorldActions`에 존재 — 실갭은 env 미공급) | engine 375→382 green (신규 WorldInvaderEndingContextTest 7/0), logic 2154 불변, 기존테스트 0 손상 | fresh: **parity-reviewer**(적대적 InvaderEnding.php byte-parity + XML, 제안 컨텍스트 0) | 채택 | sammo/Event/Action/InvaderEnding.php. 10메서드 전부 MATCH, 가드(isunited∈{0,2}/nationCount≥2/!needStop)+승(한족)·패(이민족 ⓞ)분기 정확. 격리 CITED: refreshLimit meta-only(컬럼flush 백로그)·setIsunited meta(Q14 선례). **RaiseInvader 미배선→LATENT live**(별도 백로그). 로그 YEAR_MONTH 접두=선존재 갭(바퀴17). off-by-one PHP cite 정정 |
| 16 | 장기-시뮬 게이트 Phase 1 — 천하통일 탐지(checkEmperior) 포팅: Q14 detection(level>0 국가수==1 && 전도시소유 → isunited=2 + 전토통일 국가사 로그). logic `CheckEmperior.kt`(pure)+`postUpdateMonthlyTail` Q14 콜백 + 엔진 `WorldCheckEmperiorContext`+`InMemoryTurnWorld.setIsunited`. 격리(전부 CITED): 1회성 부수효과 5종+checkStatistic+DB영속(컬럼flush/boot-load)+로그 YEAR_MONTH 접두 | logic 2148→2154·engine 372→375 green (신규 CheckEmperiorTest 6/0 + WorldCheckEmperiorContextTest 3/0) | fresh: **parity-reviewer**(적대적 PHP byte-parity + XML 직독, 제안 컨텍스트 0) | 채택 | func_gamerule.php:696-769(:430 호출). no-rng Q14(draw 스트림 불변). 인바리언트 1-6 전부 MATCH, 격리 전부 CITED, 회귀 0. 장기-시뮬 Phase 1 닫음(이전 세션 un-gated 원복분 재포팅+게이트화). 스코어러 P1 2건=선존재 엔진-광역 로그포맷 갭(아래 백로그) |

## 진행 현황

- **Wave 1 완료**(바퀴 1–12, 커밋 520ab0e4·a8a83432·6648bbfc·a42304dc): FE 표시/패러티 + 로비맵 회귀 + troop 계약 + diplomacy 가드. 전부 채택(green).
- **Wave 2 진행중**(Docker on): 바퀴 13 turn-loop CRITICAL(turnTime/killturn 미진행 + dueGenerals strict-<), 바퀴 14 RaiseDisaster trust, 바퀴 15 flush 3컬럼(power/statisticInserts/officer_city). 게이트 후 행 추가.
- 헌트 워크플로: BE `wf_de48944f-a6c`(17확정), FE `wf_644eafb5-0f1`(23확정). 전체 출력 /private/tmp/.../wnebzposs.output·wnanb7z4g.output.

## 최상위 이니셔티브 — 장기-시뮬 패러티 게이트 (공백지→천하통일)

"완벽한 게임"의 진짜 완성 게이트. 계획: `docs/superpowers/plans/2026-06-15-long-sim-parity-gate-plan.md`.
- **Phase 1 ✅ (바퀴 16)**: 천하통일 탐지 포팅 완료 — checkEmperior(func_gamerule.php:696-769, :430 호출) 국가수==1 && 전도시소유 → isunited=2 + 전토통일 로그. 엔진 월틱 Q14(no-rng). logic `CheckEmperior.kt` + 엔진 `WorldCheckEmperiorContext`. detection+in-memory 전이+로그까지 닫음(영속/부수효과/로그접두는 아래 백로그). 다음=Phase 2(PHP run_long_sim.php 캡처 하네스).
- Phase 2: PHP 풀게임 캡처 하네스 run_long_sim.php (TimeUtil mock + executeAllCommand 루프 + 턴별 draw/상태/로그). 비결정 차단원 중립화.
- Phase 3: Kotlin LongSimReplayGateTest (시나리오 부팅 → N턴 → turn-for-turn byte-compare).
- Phase 4: bounded 결정적 윈도 green → 차단원 중립화하며 천하통일까지 확장(각 1바퀴).
- 비결정 차단원: TimeUtil::now(PHP wall-clock), ORDER BY RAND GeneralAI.php:3324/3345(do선양/do국가선택, Q1격리), event shuffle, tournament rand. 양측 동일 deterministic 대체 필요.

## 백로그 (Docker 게이트 필요 — 골든 신규캡처 / 실DB IT)

- BE 명령-패러티(골든캡처 후 1바퀴씩): #7 선동 trustAmount 로그 소수1자리(number_format), #3 급습/#12 이호경식 외교 term 가산식(state IF), #13 약탈발동 float 포맷, #14 감축 2번째 제약, #15 집합 ReqTroopMembers 스텁.
- RNG-draw 경로(draw-for-draw 골든 필수): #4 do선전포고 officer_level<12+TechLimit 게이트 누락(여분 draw desync), #5 preprocessCommand(부상회복+병량소비) 데몬 루프 미실행.
- #16 C3Strategic 비교 역전 — unreachable/latent(주입처 0), P7 staging seam 배선 시 1바퀴.
- FE BE-coupled: #1 외교 서신 승인/거부(respond 엔드포인트 신설), #9 my-boss b_myBossInfo 전 섹션(read 컨트롤러 확장), #19 vote voteReward 안내(DTO 확장).
- **(라이브 버그 2+4 연결) 서버 config npcmode/block_general_create 미시드 + 어드민 편집 부재:** legacy ResetHelper.php:294-295 는 npcmode/block_general_create 를 게임생성 어드민 파라미터로 config 에 기록. opensamguk `ScenarioImporter` 는 config 에 startyear/turnterm 만 쓰고 npcmode/block_general_create 미기록 → `ServerBasicInfoController:74` 기본 0 → 로비 빙의/선택 버튼 영구 비활성(라이브 버그 2의 config 근본). **버그4(어드민 서버설정 편집)** = legacy `_admin1.php` 7개 설정 편집 + write 엔드포인트 부재(read-only). 둘 묶음: (a) ScenarioImporter 가 config 에 npcmode/block_general_create 시드(기본값 결정 필요), (b) 어드민 config write 엔드포인트+폼. 라이브 s1 은 이미 시드됨 → 어드민 편집으로만 npcmode=1 설정 가능(재시드=게임 리셋). 별도 plan.
- bbae(scenario_1030) 보급 동결 근본원인(doNPC구출발령 empty supplyCities → RandUtil.choice throw; 상류 보급계산 발산) — 별도 바퀴.
- **(바퀴 16 파생) 로그 formatText 접두 갭 — 선존재 엔진-광역 + FE 2계층 divergence (바퀴 17 측정결과: 단일 바퀴 부적합, 별도 계획 필요):** PHP `ActionLogger`는 push 시점에 push-site별 formatType 으로 접두를 stored-text 에 굽는다 — pushNationalHistoryLog/pushGlobalHistoryLog=YEAR_MONTH `<C>●</>{y}년 {m}월:`(`ActionLogger.php:241-243`), pushGeneralActionLog/pushGlobalActionLog=MONTH `<C>●</>{m}월:`(:249-251), battle=RAWTEXT/PLAIN 등(:233-238). opensamguk 엔진은 `LogEntryDraft.text` verbatim 저장(`DatabaseHooks.toLogRow`, `LogEntryDraft.format` 미소비) → 월 파이프라인 history 로그(개전/종전 Q6/Q7 `func_gamerule.php:360,384` + 전토통일 :733) 접두 누락. **추가로 FE 도 divergence**: legacy `PageHistory.vue:41`는 `v-html=formatLog(item)`로 접두-포함 text 를 인라인 렌더(행별 날짜컬럼 없음)인데, opensamguk `web/game/.../world-log/page.tsx:95`는 `{year}년 {month}월`을 **별도 컬럼**으로 렌더. ⇒ 백엔드만 접두 추가 시 **날짜 이중표시 회귀**. 패러티-정답=stored-text에 formatType별 접두 굽기 + FE는 인라인 렌더(날짜컬럼 제거). **단일 바퀴 부적합**(엔진 formatText 포팅 + push-site별 formatType 배선 + 접두-없음 가정 기존 엔진 테스트 일괄 정정 + FE 4-surface + PHP stored-bytes 골든). **spec 작성 완료**: `docs/superpowers/specs/2026-06-15-log-format-prefix-parity.md`(9 formatType 인벤토리 + push-site 맵 + 단계 P0-P3). **§3 패러티-계약 결정(A: byte-match stored text+FE 인라인 / B: 구조 divergence+정규화 게이트, 추천 A) = 유저 승인 대기.** 장기-시뮬 Phase 4 full-stored-bytes 게이트 전제.
- **(바퀴 16 파생) isunited DB 영속 갭:** `JdbcFlushExecutor` world_state UPDATE(:222)에 `isunited` 컬럼 미포함(turn-rewind/flush-3col 동류) + `WorldSnapshotLoader` 컬럼→meta 미적재. in-memory 전이가 재기동 시 유실(미영속 시 재탐지로 전토통일 로그 중복 위험). 별도 바퀴 + 실DB IT.
- **(바퀴 18 파생) 침략자-이벤트 family START 미배선 — InvaderEnding 도달성 prereq:** `RaiseInvader`(isunited=1 세팅)/`RaiseNPCNation` start-event + 트리거 조건이 엔진에 미배선(P3 "9 leaves" 밖). 바퀴 18이 InvaderEnding **종료 seam**(dispatched no-op)은 닫았으나, START가 없어 live 에선 isunited 가 1이 되지 않아 종료가 **발동 불가(LATENT)**. family START 배선 = 별도 이니셔티브(reachability). 배선 후 InvaderEnding 라이브 검증 + 장기-시뮬에서 이민족 엔딩 경로 게이트화.
- **(바퀴 16 격리, Phase 4) checkEmperior 1회성 부수효과:** checkStatistic(:725)·유니크경매 종료(:735-743)·상속 unifier+2000(:745-753)·United 이벤트(:755)·상속 merge/apply(:757-760)·refreshLimit*100(:763)·CheckHall(:765-767). 천하통일 최종턴에서만 발동 — 장기-시뮬 Phase 4 윈도 확장 시 PHP 라인대로 포팅.
- FE 채점 = `tsc --noEmit`(web/game·web/gateway) + `web/game` vitest(65). BE = gate.sh 모듈 XML(결정적).
