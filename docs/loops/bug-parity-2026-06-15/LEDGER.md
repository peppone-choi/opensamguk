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

## 진행 현황

- **Wave 1 완료**(바퀴 1–12, 커밋 520ab0e4·a8a83432·6648bbfc·a42304dc): FE 표시/패러티 + 로비맵 회귀 + troop 계약 + diplomacy 가드. 전부 채택(green).
- **Wave 2 진행중**(Docker on): 바퀴 13 turn-loop CRITICAL(turnTime/killturn 미진행 + dueGenerals strict-<), 바퀴 14 RaiseDisaster trust, 바퀴 15 flush 3컬럼(power/statisticInserts/officer_city). 게이트 후 행 추가.
- 헌트 워크플로: BE `wf_de48944f-a6c`(17확정), FE `wf_644eafb5-0f1`(23확정). 전체 출력 /private/tmp/.../wnebzposs.output·wnanb7z4g.output.

## 최상위 이니셔티브 — 장기-시뮬 패러티 게이트 (공백지→천하통일)

"완벽한 게임"의 진짜 완성 게이트. 계획: `docs/superpowers/plans/2026-06-15-long-sim-parity-gate-plan.md`.
- **Phase 1 (진행중)**: 천하통일 탐지 포팅 — checkEmperior(func_gamerule.php:696-769, :430 호출) 국가수==1 && 전도시소유 → isunited=2 + 전토통일 로그. 엔진 월틱 Q14. (port-unification 에이전트)
- Phase 2: PHP 풀게임 캡처 하네스 run_long_sim.php (TimeUtil mock + executeAllCommand 루프 + 턴별 draw/상태/로그). 비결정 차단원 중립화.
- Phase 3: Kotlin LongSimReplayGateTest (시나리오 부팅 → N턴 → turn-for-turn byte-compare).
- Phase 4: bounded 결정적 윈도 green → 차단원 중립화하며 천하통일까지 확장(각 1바퀴).
- 비결정 차단원: TimeUtil::now(PHP wall-clock), ORDER BY RAND GeneralAI.php:3324/3345(do선양/do국가선택, Q1격리), event shuffle, tournament rand. 양측 동일 deterministic 대체 필요.

## 백로그 (Docker 게이트 필요 — 골든 신규캡처 / 실DB IT)

- BE 명령-패러티(골든캡처 후 1바퀴씩): #7 선동 trustAmount 로그 소수1자리(number_format), #3 급습/#12 이호경식 외교 term 가산식(state IF), #13 약탈발동 float 포맷, #14 감축 2번째 제약, #15 집합 ReqTroopMembers 스텁.
- RNG-draw 경로(draw-for-draw 골든 필수): #4 do선전포고 officer_level<12+TechLimit 게이트 누락(여분 draw desync), #5 preprocessCommand(부상회복+병량소비) 데몬 루프 미실행.
- #16 C3Strategic 비교 역전 — unreachable/latent(주입처 0), P7 staging seam 배선 시 1바퀴.
- FE BE-coupled: #1 외교 서신 승인/거부(respond 엔드포인트 신설), #9 my-boss b_myBossInfo 전 섹션(read 컨트롤러 확장), #19 vote voteReward 안내(DTO 확장).
- bbae(scenario_1030) 보급 동결 근본원인(doNPC구출발령 empty supplyCities → RandUtil.choice throw; 상류 보급계산 발산) — 별도 바퀴.
- FE 채점 = `tsc --noEmit`(web/game·web/gateway) + `web/game` vitest(65). BE = gate.sh 모듈 XML(결정적).
