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

## 백로그 (이번 바퀴 외 — 가설 단위)

- 10-area 패러티 버그-헌트 워크플로(`wf_de48944f-a6c`) 확정 버그 → 각 1바퀴로 소비.
- 실DB flush 게이트(`:infra:test :app:game-engine:test`)는 Docker 필요 → 유저가 Docker 기동 시 baseline 보강 + city.state V17 회귀 확인.
- bbae(scenario_1030) 보급 동결 근본원인(doNPC구출발령 empty supplyCities → RandUtil.choice throw; 상류 보급계산 발산) — 별도 바퀴.
- 프론트 버그/패러티 갭 헌트 워크플로(`wf_644eafb5-0f1`) 확정 버그 → 각 1바퀴. FE 채점 = `tsc --noEmit`(web/game·web/gateway) + `web/game` pnpm test (page-parity 루프 동결 시험지 공유, 결정적).
