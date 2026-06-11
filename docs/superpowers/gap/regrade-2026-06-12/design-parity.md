# 디자인/구조 패러티 재채점 — web/game 인게임 페이지 (2026-06-12)

대상: `web/game/app/game/` 인게임 페이지 vs **legacy grand truth** (`legacy/devsam-core/hwe/*.php` + `hwe/ts/*.vue`).
범위: 메인(/game), auction, betting, board, chief-center, city, diplomacy, generals, global-diplomacy, history,
inherit, mailbox, my-boss, my-cities, my-generals, my-nation, nation, nation-finance, npc-control, simulator,
tournament, troop, vote, world-log (admin1/2/5/7/8 · join · map · select-pool · rankings 상세는 표면 점검만).

## 판정 기준

- **구조 truth = legacy** (`hwe/*.php` 페이지 + `hwe/ts/*.vue`): 필드 set · 섹션 · **순서** · 테이블 컬럼/라벨이 기준.
  비주얼 스킨은 Peppone-UI-KIT(프로젝트 메모리)이므로 색상/폰트/카드화 자체는 위반 아님.
- **P0** = 발명/위조 UI: 레거시에 없는 섹션을 만들었거나, 레거시와 다른 **사실(상태/데이터)을 사실처럼 표시**.
- **P1** = 섹션/컬럼군/페이지 단위 누락.
- **P2** = 라벨/순서/단일 요소 드리프트, 페이지 간 시각 일관성 결함. (의도가 문서화된 파생 표면도 P2로 분류하고 비고 표기.)
- 모든 finding은 실제로 읽은 file:line 인용. READ-ONLY 측정 — 소스 무수정.

---

## P0 — 발명/위조 UI (4건)

### P0-A. nation(국가 정보) 페이지: 발명 섹션 + 위조 버프 라벨/키
- 현재: `web/game/app/game/nation/page.tsx:12-21` — `INHERIT_BUFFS`가 라벨 `'전투 회피율'`, 설명 `'자신의 전투 회피율 +1% per level'`, 키 `success`/`fail`을 자체 정의. 같은 파일 `:234` `유산 버프 구매`, `:285` `기타 유산 구매` 섹션을 "국가 정보" 페이지에 렌더.
- 레거시: 유산 버프는 **유산 관리 페이지에만** 존재 — `hwe/ts/PageInheritPoint.vue:381-420` `inheritBuffHelpText` = `"회피 확률 증가"` / `"전투 시 회피 확률이 1%p ~ 5%p 증가합니다."`, 키 `domesticSuccessProb`/`domesticFailProb`. 국가 정보 페이지(`hwe/b_myKingdomInfo.php:95-160`)에는 유산 구매 UI가 전혀 없음.
- 비고: 올바른 바이트-패러티 문자열이 `inherit/page.tsx:63-70`에 **이미 존재** — nation 페이지가 별도의 날조 사본을 들고 있음. 키 불일치(`success`/`fail`)는 명령 인자 오류 가능성도 있음(태스크 P0-50과 연관).

### P0-B. betting 목록: 마감된 베팅을 '진행 중'으로 위조 + 발명 표시
- 현재: `web/game/app/game/betting/page.tsx:112-118` — `b.finished ? '종료' : '진행 중'` 2-상태 배지 + `총액 N` 칩.
- 레거시: `hwe/ts/PageNationBetting.vue:15-19` — 3-상태 `(종료)` / `(Y년 M월까지)` / `(베팅 마감)`. 마감(closeYearMonth 경과·미결산) 베팅은 `(베팅 마감)`인데 현재 구현은 **'진행 중'으로 표시** = 게임 상태 위조. `총액` 칩·'진행 중' 배지는 레거시 목록에 없는 발명 표시.

### P0-C. history(연감) 지도: 과거 월 조회에도 현재 라이브 맵 렌더
- 현재: `web/game/app/game/history/page.tsx:208-212` — "세계 지도" 섹션이 공유 `<MapViewer />`(라이브 `/api/map` self-fetch)를 렌더. `record.map` 미사용(파일 상단 `:15-17`에 self-doc).
- 레거시: `hwe/ts/PageHistory.vue:23-37` — `:map-data="history.map"` 선택 월 스냅샷을 렌더.
- 영향: 과거 연월 선택 시 **그 달의 지도가 아닌 현재 지도**가 연감 페이지에 표시 = 사실과 다른 정보 제시. (백로그 self-doc 있으나 사용자-가시 위조이므로 P0 유지.)

### P0-D. diplomacy(외교부): 레거시에 없는 '외교 빠른 명령' 섹션 발명
- 현재: `web/game/app/game/diplomacy/page.tsx:20-25` — `DIPLO_QUICK_ACTIONS` (종전 제의/불가침 제의/불가침 파기 제의/선전 포고) 버튼 4종을 외교 서신 페이지에 렌더.
- 레거시: `hwe/t_diplomacy.php:67-156` — 외교부는 **서신(새 외교문서 작성 + 국가별 문서 테이블) 전용**. 해당 제의류는 사령부(국가 명령 예약) 경로에만 존재.
- 비고: 명령 자체는 실재(P6 CommandRegistry)이므로 데이터 위조는 아니나, 레거시에 없는 UI 표면 발명 = 루브릭상 P0.

---

## P1 — 섹션/컬럼군/페이지 누락 (8건)

### P1-1. 세력 장수 페이지가 공개 7컬럼으로 폴드 — GeneralList 컬럼군 누락
- 레거시: `hwe/ts/PageNationGeneral.vue:55` title `"세력 장수"`, 본체 `GeneralList.vue` 컬럼군 — 아이콘(:500), 장수명(:516), 통|무|지(:555-586), 관직(:596), 명성/계급(:626), **자금 금/쌀(:680-720), 도시(:731), 부대(:751), 보유 병력 병종/병력(:802-839), 훈/사(:850-875), 수비(:884), 특성 요약/성격/내특/전특(:895-951), 명령(:970-1014), 턴(:1053), 최근전투(:1068), 연도(:1083-1110), 삭/벌(:1126-1153), 전과(:1171-1207)**.
- 현재: `web/game/app/game/generals/page.tsx:54-62` — 장수명/국가/통솔/무력/지력/명성/계급 7컬럼 공개 surface + 국가 필터 폴드(:11-15 self-doc). 타이틀도 `'전체 장수'`(:245)로 드리프트.

### P1-2. 암행부(b_genList) 페이지 미구현 + 죽은 라우팅
- 레거시: `hwe/b_genList.php:60` title `암행부`, `:253-269` 집계표(전체 금/쌀 · 평균 금/쌀 · 전체 병력/장수 · 훈사 90/80/60 병력/장수) + generalList 템플릿 렌더(:275).
- 현재: `web/game/lib/control-bar-config.ts:47`이 `암 행 부` 버튼을 `/game/generals?secret=1`로 보내지만, `generals/page.tsx`에는 `secret`/`searchParams` 처리가 **0건**(grep 무히트) — 암행부 콘텐츠 전체 누락, 버튼은 공개 장수 목록으로 떨어짐.

### P1-3. 내무부 '외교관계' 섹션 통째 누락 + 섹션 순서 드리프트
- 레거시: `hwe/ts/PageNationStratFinan.vue:4-45` — 첫 섹션 `외교관계` 테이블(국가명/국력/장수/속령/상태/기간/종료 시점). 순서: 외교관계(:4) → 국가 방침 & 임관 권유 메시지(:47) → 예산&정책(:85) → 추가 설정(:233).
- 현재: `web/game/app/game/nation-finance/page.tsx:184-312` — 국가 방침부터 시작, **외교관계 섹션 부재**.

### P1-4. 메인 MessagePanel '외교 메시지' 채널 누락
- 레거시: `hwe/ts/components/MessagePanel.vue:39/74/109/149` — 전체/국가/개인/**외교** 4채널.
- 현재: `web/game/components/game/MessagePanel.tsx:44-48` — public/national/private 3채널만 구성(주석 `:4`는 4채널이라 주장하나 외교 채널 코드 없음). 외교 서신 수발신이 메인 화면에서 불가(mailbox 페이지로만 우회).

### P1-5. 전투 시뮬레이터 설정 폼 누락
- 레거시: `hwe/battle_simulator.php:228-368` — 성벽(:228) · 수비자 설정 블록(:236,249) · 병종(:343) · 훈련(:365) · 사기(:368) 입력 폼, 결과표(:494-502 수비자 군량 소모/공격자 스킬/수비자1 스킬).
- 현재: `web/game/app/game/simulator/page.tsx:30-37` — 공격/수비 장수 select 2개만; 병종/훈련/사기/성벽 직접 입력 불가.

### P1-6. 내 상관(my-boss): 수뇌부 표 + 오호장군/건안칠자 누락
- 레거시: `hwe/b_myBossInfo.php:160-195` — 직위 12→내 레벨 2열 페어 표(직위/64px 아이콘/이름(belong년)) + `:188` `오호장군【승전】` + `:192` `건안칠자【계략】` 행.
- 현재: `web/game/app/game/my-boss/page.tsx:68-78` — bossName + `'{officerLevel}급'` 배지 단일 카드뿐. ('N급' 표기도 레거시 `getOfficerLevelText` 직위 텍스트와 불일치.)

### P1-7. 토너먼트 규칙 안내 블록 누락
- 레거시: `hwe/b_tournament.php:466-473` — `ㆍ예선은 홈&어웨이 풀리그로 진행됩니다…ㆍ참가비는 금20~140이며…` 8줄 규칙 안내.
- 현재: `web/game/app/game/tournament/page.tsx` — `예선은`/`참가비는` grep 무히트(섹션 헤더 16강 승자전/조별 본선 순위/조별 예선 순위는 verbatim 존재 :34).

### P1-8. 연감 '중원 정세'/'장수 동향' 데이터 공급 부재(항상 빈 섹션)
- 레거시: `hwe/ts/PageHistory.vue:38-53` — `history.global_history`/`global_action`을 formatLog로 렌더.
- 현재: `web/game/app/game/history/page.tsx:12-13` self-doc — `yearbook_history`에 컬럼 부재로 두 배열이 항상 빈 값(서버 BLOCKED, 날조 없음). 섹션 껍데기만 존재. (BE 갭이지만 페이지가 상시 빈 채 출고되므로 P1 기록.)

---

## P2 — 라벨/순서 드리프트 + 시각 일관성 (16건)

| # | 페이지 | 내용 | 레거시 근거 | 현재 근거 |
|---|--------|------|-------------|-----------|
| P2-1 | betting | 타이틀 `'국가 베팅장'`→`'베팅'`, `'베팅 목록'` 헤더 누락, 목록 **역순(.reverse) 미적용**(최신순→오래된순) | `PageNationBetting.vue:77,7,9` | `betting/page.tsx:83,58,99-122` |
| P2-2 | vote | 타이틀 `설문 조사({voteReward}금과 추첨으로 유니크템 증정!)`의 보상 문구 누락 | `PageVote.vue:4` | `vote/page.tsx:259` |
| P2-3 | global-diplomacy | 분쟁 현황 도시명 → `도시 {cityId}` 숫자 id 노출 | `PageGlobalDiplomacy.vue:68` (`cityConst[cityID].name`) | `global-diplomacy/page.tsx:236-250` |
| P2-4 | global-diplomacy | 중원 지도 국가표 `장수` 컬럼 누락(국명/국력/속령만) | `components/SimpleNationList.vue:5-8` (국명/국력/장수/속령) | `global-diplomacy/page.tsx:310-312` |
| P2-5 | troop | 부대 카드: 도시명 대신 `cityId` 노출, 부대장 64px 아이콘 부재, 라벨 `부대 탑승`→`부대 가입`·`부대 창설`→`부대 결성` | `PageTroop.vue:8,12,58,113-118` | `troop/page.tsx:161,165,328-338` |
| P2-6 | 메인(/game) | info 카드 순서 역전: 레거시 City→Nation→General, 현재 General→Nation→City | `PageFront.vue:68-74` | `components/game/GameChrome.tsx:114-117` |
| P2-7 | city | 게이지 순서 드리프트: 레거시 주민·농업·상업·치안·수비·성벽 后 민심·시세, 현재는 민심이 주민 직후 | `b_currentCity.php:455-471` | `city/page.tsx:149-156` |
| P2-8 | my-nation | 셀 배치 드리프트(8열 표→6열 grid, 장수/기술력/작위 행 이동; 라벨 19종 자체는 일치) | `b_myKingdomInfo.php:99-143` | `my-nation/page.tsx:108-167` |
| P2-9 | nation-finance | 예산 표 발명 헤더 `항목/금`·`항목/쌀`(레거시는 무헤더 라벨 grid), `정책` h2 분리 발명, `추가 설정` placeholder 누락 | `PageNationStratFinan.vue:89-134,233` | `nation-finance/page.tsx:237,244,249` |
| P2-10 | board | 회의실/기밀실 in-page 토글(레거시는 `isSecretBoard` prop 별도 인스턴스) + 게시물 author_icon 64px 부재(self-doc) | `PageBoard.vue:129`; `components/BoardArticle.vue`(generalIcon) | `board/page.tsx:166,229-250,87-90` |
| P2-11 | history | 발명 섹션 헤더 `세계 지도`/`세력 일람`(레거시는 무제 map+국가표 병치) + side-by-side→세로 스택 | `PageHistory.vue:22-37` | `history/page.tsx:209,215` |
| P2-12 | history | `isBrightColor` 임계 불일치 사본: 레거시 >140, history 사본은 >=128 (global-diplomacy 사본은 >140로 정확) | `hwe/ts/util/isBrightColor.ts` (>140) | `history/page.tsx:39-48` vs `global-diplomacy/page.tsx:35-42` |
| P2-13 | 전 페이지 | h1 타이틀 스타일 2계열 혼재: inline `var(--text-2xl)` 계열(chief-center:203, generals:245 등) vs `.page-content` bare h1 계열(nation-finance:181, my-nation:90, my-boss:71, city:114) | — (시각 일관성) | 좌측 인용 |
| P2-14 | 전 페이지 | 토스트 2계열 혼재: 우상단 `.toast` fixed(auction:47, betting:93, chief-center:216) vs 하단 중앙 fixed div(board:349-364, nation-finance:334, troop) | — (시각 일관성) | 좌측 인용 |
| P2-15 | mailbox | 레거시에 없는 별도 서신함 페이지(레거시는 메인 MessagePanel 전용). 데이터는 패러티(P0-28 마스킹) — 의도 문서화된 파생 표면 | `MessagePanel.vue`(front 전용) | `mailbox/page.tsx:1-60` |
| P2-16 | world-log | 레거시에 없는 별도 전황 페이지(self-doc: "헤더 셀에만 노출되던 전황을 전용 페이지로 분리"). 내용은 패러티 로그 원문 | — | `world-log/page.tsx:3-13` |

---

## 닫힌 항목 검증 결과 (양호 — 바이트/구조 일치 확인)

| 페이지 | 확인 내용 | 근거 |
|--------|-----------|------|
| auction | 셸 구조(경매장/유니크 경매장 토글 + 금/쌀·유니크 버튼) 일치 | `PageAuction.vue:3-9` ↔ `auction/page.tsx:28-54` |
| chief-center | 직책 순서 `[12,10,8,6,11,9,7,5]` verbatim, 공석 `'-'`, turnTime `.slice(-5)`, NPC색 | `PageChiefCenter.vue:10` ↔ `chief-center/page.tsx:33,46-48,65` |
| inherit | `inheritanceViewText` 13항목·`inheritBuffHelpText` 8종 제목/설명 바이트 일치, 섹션(상점→버프→소유자 확인→능력치 초기화→변경 내역) 순서 일치 | `PageInheritPoint.vue:322-420,31,228` ↔ `inherit/page.tsx:40-51,63-70,294,553` |
| npc-control | 타이틀 `NPC 정책`, 섹션 3종(국가 정책/NPC 사령턴 우선순위/NPC 일반턴 우선순위)+비활성/활성 서브바 일치 | `PageNPCControl.vue:425,9,198,278` ↔ `npc-control/page.tsx:269,276,317,324` |
| nation-finance(예산) | 자금/군량 예산 라벨 5종(현 재/단기수입·둔전수입/세 금/수입·지출/국고 예산) + 세율(5~30%)/지급률(20~200%)/기밀 권한(1~99년)/전쟁 금지 문구 일치 | `PageNationStratFinan.vue:89-134,144,166,189` ↔ `nation-finance/page.tsx:154-176,253-283` |
| my-cities | 라벨 16종(주민/인구율/자금·군량·둔전 수입/농업…태수/군사/종사/장수) 순서·문자열 일치 | `b_myCityInfo.php:221-257` ↔ `my-cities/page.tsx:70-125` |
| my-generals | b_myGenInfo 15컬럼 set/순서/정렬 셀렉터, 벌점은 BLOCKED 문서화(미날조) | `my-generals/page.tsx:3-12,41-52` |
| global-diplomacy(매트릭스) | 외교 현황 기호/색(★red ▲magenta ㆍ @green, self ＼, #660000 배경)·범례 footer 바이트 일치 | `PageGlobalDiplomacy.vue:41-58` ↔ `global-diplomacy/page.tsx:44-64,211-219` |
| board(구조) | 새 게시물 작성(제목/내용/등록, maxlength 250) → 게시물 목록 순서, 댓글 행 [작성자/본문/날짜 slice(5,16)] 일치 | `PageBoard.vue:5-40` ↔ `board/page.tsx:252-291,44-70` |
| troop(구조) | 【턴】 `.slice(14,19)`, 예약 brief `${idx+1}: `, 부대원 명단+타도시 표기, 추방/부대명 변경 동작 존재 | `PageTroop.vue:9-50` ↔ `troop/page.tsx:48-50,216-230,234-248` |
| vote(본문) | 설문 제목/게시자/[SYSTEM]/(N개 선택 가능)/투표/결산/투표율/이전 설문 조사/새 설문 조사 열기 라벨 verbatim | `PageVote.vue:14-153` ↔ `vote/page.tsx:34-36,279-530` |
| tournament | 상태/타입 텍스트·섹션 헤더(16강 승자전/조별 본선 순위/조별 예선 순위) verbatim, 16/8/4/결승 레이어 | `b_tournament.php:405,434` ↔ `tournament/page.tsx:30-76,271-273` |
| 메인(/game) | PageFront 척추(GlobalMenu→GameInfo→Map→예약→카드→ControlBar→MessagePanel→하단 GlobalMenu) 구성요소 전부 존재 | `PageFront.vue:12-164` ↔ `GameChrome.tsx:82-145` (순서 결함은 P2-6) |

## 한계

- `rankings/*` 7페이지(a_*), `join`(v_join), `select-pool`, `admin*`(_admin*.php), `tournament-admin`(c_tournament.php), `map`은 표면 점검만 수행(본 재채점 미포함) — 후속 재채점 권장.
- 동적 렌더(런타임 데이터)는 미실행 — 정적 코드 대조만.

## 집계

- **P0: 4건** · **P1: 8건** · **P2: 16건**
