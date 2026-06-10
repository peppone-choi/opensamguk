# 페이지 패러티 감사 백로그 — 2026-06-10

> **범위**: `web/game/app/game/*` 인게임 페이지 20종 × legacy(devsam-core PHP/Vue) 대조 감사.
> **판정 기준**: PHP = grand truth. 위조 표시(silent fabrication)·정보 누출·항상-실패 액션 = P0, 데이터/액션/콘텐츠 결손 = P1, 구조·표기 드리프트 = P2.
> **주의**: `/game/nation-finance`는 감사 원본 데이터가 P1 5번째 finding 중간에서 잘림(truncated) — 해당 페이지 집계는 하한값. 본 문서는 감사 데이터에 있는 항목만 기록하며 어떤 값도 새로 만들지 않음.

---

## 1. 요약표 (Executive Summary)

| # | 페이지 | 판정 | P0 | P1 | P2 | 계 |
|---|--------|------|----:|----:|----:|----:|
| 1 | `/game` (메인) | GAPS | 3 | 7 | 2 | 12 |
| 2 | `/game/auction` | GAPS | 1 | 5 | 3 | 9 |
| 3 | `/game/betting` | GAPS | 3 | 4 | 2 | 9 |
| 4 | `/game/board` | GAPS | 1 | 1 | 4 | 6 |
| 5 | `/game/chief-center` | GAPS | 3 | 6 | 3 | 12 |
| 6 | `/game/city` | GAPS | 3 | 4 | 5 | 12 |
| 7 | `/game/diplomacy` | GAPS | 3 | 5 | 4 | 12 |
| 8 | `/game/generals` | GAPS | 1 | 5 | 4 | 10 |
| 9 | `/game/global-diplomacy` | GAPS | 1 | 2 | 4 | 7 |
| 10 | `/game/history` | GAPS | 3 | 3 | 4 | 10 |
| 11 | `/game/inherit` | GAPS | 6 | 3 | 1 | 10 |
| 12 | `/game/join` | GAPS | 3 | 8 | 1 | 12 |
| 13 | `/game/mailbox` | GAPS | 4 | 5 | 1 | 10 |
| 14 | `/game/map` | GAPS | 1 | 4 | 5 | 10 |
| 15 | `/game/my-boss` | GAPS | 7 | 2 | 2 | 11 |
| 16 | `/game/my-cities` | GAPS | 4 | 4 | 3 | 11 |
| 17 | `/game/my-generals` | GAPS | 0 | 7 | 2 | 9 |
| 18 | `/game/my-nation` | GAPS | 2 | 2 | 3 | 7 |
| 19 | `/game/nation` | GAPS | 1 | 6 | 3 | 10 |
| 20 | `/game/nation-finance` ⚠truncated | GAPS | 4 | 1+ | 0+ | 5+ |
| | **합계** | **20/20 GAPS** | **54** | **84+** | **56+** | **194+** |

반복 패턴(전 페이지 공통 근원):
- **위조 성공/위조 데이터**: 202 intake 수락을 성공 토스트로 표시(엔진 deny 무음 삼킴) — auction/betting/diplomacy/mailbox. BLOCKED(200)도 `res.ok`라 성공 처리(`web/game/lib/api.ts:212-220`).
- **권한 스케일 단절**: legacy `checkSecretPermission`(0..4) vs `GeneralResolver.derivePermission`(max 2) — diplomacy/mailbox/board/chief-center/nation-finance.
- **stale BLOCKED 주석**: log_entry에 데이터가 이미 있는데 "원천 부재"로 emptyList 하드와이어 — 메인 RecordZone/연감/국가열전/경매 recentLogs.
- **refresh-score(벌점/접속제한) 시스템 전체 미포팅**: general_access_log 부재 — generals/my-generals/board 등 (문서화된 격리, P8 백로그).

---

## 2. P0 — 페이지별 (위조·누출·항상-실패·핵심 플로우 불능)

### 2.1 `/game` (메인) — P0 ×3

**P0-01 [actions] 예약명령 패널이 전 슬롯을 '휴식'으로 위조 표시**
- 문제: FE가 이미 존재하는 `GET /api/reserved-commands`를 호출하지 않고 전 슬롯 하드코딩 '휴식' 렌더. 패널 플래그 문구("조회 API가 아직 없어…")는 stale. 실제 예약 명령이 화면에 절대 안 보임(silent 위조).
- legacy: `hwe/ts/PartialReservedCommand.vue:493-551` + `hwe/sammo/API/Command/GetReservedCommand.php:38-53`
- 현재: `web/game/components/game/PartialReservedCommand.tsx:42-56` (:47 stale 플래그) vs 존재하는 `app/game-api/src/main/kotlin/opensamguk/gameapi/web/ReservedCommandsController.kt:48-69`
- 수정: `lib/api.ts`에 reservedCommands() 신설 → refreshKey/onReserved마다 재조회, turnIdx→{action,brief,arg} 매핑, 빈 슬롯만 '휴식'.

**P0-02 [actions] 예약 링 조작 액션 전면 부재 (순수 FE 미구현)**
- 문제: 반복(1~12턴)/당기기/미루기, 고급 모드(범위 선택 5종), '선택한 턴을' 9종, 보관함, 최근 실행 전부 없음. BE(`/api/command/push·repeat·bulk`)는 모두 존재.
- legacy: `hwe/ts/PartialReservedCommand.vue:19-24, 27-103, 243-263, 437-467, 553-585, 673-884`
- 현재: `web/game/components/game/PartialReservedCommand.tsx:71-78` vs `app/game-api/.../web/CommandController.kt:123-158` (bulk/push/repeat 구현됨)
- 수정: 반복/당기기/미루기 드롭다운 + 고급 모드(다중 선택→bulk) 단계 포팅, 보관함/최근은 StoredActionsHelper(localStorage) 포팅.

**P0-03 [content] 메인 RecordZone(3컬럼 피드) 전무 + MyInfoLogPanel은 fetch 0**
- 문제: 장수 동향/개인 기록/중원 정세 15행 증분 피드가 통째로 없고, 대신 들어간 MyInfoLogPanel은 데이터 fetch가 없어 항상 '기록이 없습니다'. BE recentRecord는 emptyList 고정 — 단 world history급은 log_entry(SYSTEM)에 이미 존재(WorldLogController 30건 서빙) → BLOCKED 사유 부분 stale.
- legacy: `hwe/ts/PageFront.vue:113-135` + `hwe/sammo/API/General/GetFrontInfo.php:65-156`
- 현재: `web/game/app/game/page.tsx:23` + `MyInfoLogPanel.tsx:89-95` + `FrontInfoController.kt:116-119` (부분 소스: `WorldLogController.kt:24-29`)
- 수정: recentRecord를 log_entry 기반 3피드(증분 lastID + 15행 cap + flush 플래그)로 채우고, children을 RecordZone 3컬럼으로 교체.

또한 누락 액션(요약): 갱 신/로비로/명령으로 버튼, 버전 정보 모달, 새 설문 토스트 — P1/P2에서 다룸.

### 2.2 `/game/auction` — P0 ×1

**P0-04 [actions] 입찰/등록 실패가 절대 보이지 않음 — 무조건 성공 토스트(위조)**
- 문제: (a) precheck deny가 200 BlockedResponse인데 FE post()는 res.ok면 resolve → BLOCKED여도 '입찰했습니다.' 토스트. (b) 202 큐잉 후 엔진 비동기 deny(잔액 부족, 최저가 미달, '시작 후 3개월…', 동시 3건 제한)가 어떤 채널로도 미도달. legacy는 동기 처리로 에러 문자열을 danger 토스트.
- legacy: `hwe/ts/components/AuctionResource.vue:192-215,285-310` + `OpenBuyRiceAuction.php:77-96` + `BidUniqueAuction.php:46-52`
- 현재: `web/game/components/auction/AuctionResource.tsx:116-151`, `AuctionUniqueItem.tsx:139-158`, `web/game/lib/api.ts:212-220`, `app/game-api/.../web/CommandController.kt:69-89`
- 수정: FE에서 status==='BLOCKED'/'UNKNOWN' 검사→reason danger 토스트(성공은 202 AVAILABLE만). BE는 경매 bid/open 동기 검증 결과 회신 또는 requestId 결과 조회/SSE deny 채널.

### 2.3 `/game/betting` — P0 ×3

**P0-05 [actions] b_betting 토너먼트 베팅장 페이지 전체 부재 + 컨트롤바 오라우팅**
- 문제: 컨트롤바 20번 '베 팅 장'(legacy 타깃 b_betting.php)이 /game/betting(국가베팅)으로 잘못 연결. 16강 브래킷·슬롯별 배당/내베팅/환수금 3행·tournament==6 게이트 16슬롯 베팅 제출·토너 랭킹 4표·안내문·갱신 전부 누락.
- legacy: `hwe/b_betting.php:138-626` + `hwe/ts/betting.ts:10-33` + `MainControlBar.vue:59`
- 현재: `web/game/lib/control-bar-config.ts:77-85`, `web/game/app/game/tournament/page.tsx:21-23`
- 수정: `/game/betting-arena`(가칭) 신설 — /api/tournament + ng_betting 집계 + rank_data tt/tl/ts/ti 4표, 컨트롤바 재지정.

**P0-06 [actions] 베팅 제출 의미론 위조 — intake 수락=성공 토스트**
- 문제: legacy Bet.php는 동기 즉시 실행+검증 메시지 반환. 현재는 202 intake 직후 '베팅했습니다' 토스트+재조회(flush 전이라 안 보임). 엔진이 나중에 거부해도 성공 표시.
- legacy: `hwe/sammo/API/Betting/Bet.php:43-63`; `BettingDetail.vue:420-459`
- 현재: `web/game/components/betting/BettingDetail.tsx:233-251`, `web/game/lib/api.ts:416-422`, `CommandWireMapper.kt:128`
- 수정: PlaceBetOk/Fail을 requestId 폴링/SSE로 회신받아 결과 확정 후 토스트, 실패 reason legacy 문자열 동일 노출(또는 동기 API 승격).

**P0-07 [backend] PlaceBetHandler가 Betting::bet 검증·부수효과 9종 누락**
- 문제: finished/마감/미시작 검사 없음(마감 베팅에도 차감+INSERT), purifyBettingKey 없음(미정렬 키 → 당첨 판정 영구 누락), 누적 1000 한도·min 10·minGoldRequiredWhenBetting(500) 예치 검사 없음, reqInheritancePoint 분기 전무(유산포인트 베팅에서 금 차감 = 데이터 오염), rank_data betgold 누락, insertUpdate 대신 INSERT-only(행 중복), 비패러티 로그 push.
- legacy: `hwe/sammo/Betting.php:56-74,100-183`; `Bet.php:22-30`; `GameConstBase.php:231`
- 현재: `app/game-engine/.../betting/PlaceBetHandler.kt:33-84`; `infra/.../persistence/JdbcFlushExecutor.kt:889-907`; `common/.../constants/GameConst.kt:311`(상수 미사용)
- 수정: Betting::bet 검증 체인을 순서대로 포팅(finished→마감→미시작→selectCnt→purify→1000한도→min10→자원검사→유산포인트 분기), rank_data 갱신, upsert 경로, 비패러티 로그 제거.

### 2.4 `/game/board` — P0 ×1

**P0-08 [backend] 재야/익명에게 전국가 글로벌 게시물 노출 (회의실 내용 누출)**
- 문제: legacy는 checkSecretPermission<0이면 '국가에 소속되어있지 않습니다.' 즉시 deny + nation 스코프 쿼리. 현재는 nationId==0이면 `findByIsSecretOrderByCreatedAtDescIdDesc`로 모든 국가 board_post 반환. 기밀실 차단 문구 우선순위도 역전.
- legacy: `hwe/j_board_get_articles.php:33-46,50-55` + `hwe/func.php:390-403`
- 현재: `app/game-api/.../controller/BoardController.kt:49-69` (글로벌 폴백 :64-69)
- 수정: permission<0 게이트를 secret 게이트보다 먼저, 글로벌 폴백 제거 — read는 항상 caller nation 스코프만.

### 2.5 `/game/chief-center` — P0 ×3

**P0-09 [backend] 사령부 예약이 nation_turn이 아닌 general_turn에 기록 (wrong-ring silent no-op)**
- 문제: CommandModal → `/api/command/{code}` → ReservedTurnRepository(general_turn INSERT). 엔진 chief 실행과 사령부 read는 nation_turn을 읽음 → 예약 202 성공 후 그리드는 영원히 '휴식', 명령은 장수 개인 슬롯 점유. 올바른 경로(`POST /api/command/nation/bulk`)는 이미 존재.
- legacy: `hwe/sammo/API/NationCommand/ReserveCommand.php:55` → `func_command.php:402` setNationCommand
- 현재: `web/game/app/game/chief-center/page.tsx:254-268` + `web/game/lib/api.ts:345-351` + `CommandReserveService.kt:94-96` + `ReservedTurnRepository.kt:65`
- 수정: 사령부 슬롯 제출을 `/api/command/nation/bulk`로 교체(또는 ?ring=nation 분기), 예약 후 chief-reserved 재조회 E2E 검증.

**P0-10 [actions] 당기기/미루기/반복 버튼 전무 (BE 존재, FE 0건)**
- legacy: `ChiefReservedCommand.vue:40-44, 94-103` → `PushCommand.php:56`, `RepeatCommand.php:55`
- 현재: page.tsx 버튼 부재; BE 존재 `CommandController.kt:173-203` (nation/push -12..12, nation/repeat 1..12)
- 수정: 반복(1..6)/당기기/미루기(±1..±6) 드롭다운 + api.ts nationPush/nationRepeat 래퍼. 한계 = maxChiefTurn/2=6.

**P0-11 [actions] 고급 모드(다중 턴 일괄 편집) 전체 부재 (BE bulk 존재 → 면제 불성립)**
- 문제: 토글·드래그 멀티선택·범위 5종·'선택한 턴을' 9동작·보관함·최근·타 수뇌 칸 드래그 복사 전부 없음. 현재 슬롯 1개 단건 예약만.
- legacy: `ChiefReservedCommand.vue:35-92,219-251,699-787` + `ChiefCenter/TopItem.vue:51-62` → `ReserveBulkCommand.php`
- 현재: `web/game/components/game/ChiefCommandReserve.tsx:107-156`; BE `CommandController.kt:160-170`
- 수정: 멀티선택+9동작 메뉴 → `/api/command/nation/bulk` 일괄 제출, 지우고 당기기/뒤로 밀기는 legacy 클라 합성 로직 이식.

### 2.6 `/game/city` — P0 ×3

**P0-12 [backend] 기본 진입(현재 도시) 경로 붕괴 — /api/city/0 → 404 에러 화면**
- 문제: 컨트롤바 '현재 도시'·글로벌 메뉴 '도시'(id 없음) 모두 에러. legacy는 citylist 미지정/무효 시 내 소재 도시 fallback. page.tsx:18 주석("서버가 0을 현재 도시로 해석")은 미구현 — 허위 주석.
- legacy: `hwe/b_currentCity.php:77-79,174-178`
- 현재: `web/game/app/game/city/page.tsx:54,64` + `app/game-api/.../web/CityDetailController.kt:170` (진입: `control-bar-config.ts:61`, `constants.ts:34`)
- 수정: id<=0/미존재 시 resolver의 general.cityId로 해석, 허위 주석 제거.

**P0-13 [data] 부상 장수 통/무/지 수치 위조**
- 문제: legacy formatWounded는 `intdiv(value*(100-wound),100)` 감산값을 빨간색으로. 현재는 원값+단계색만 → 부상 30% 통솔80이 legacy 빨강56 vs 현재 80(틀린 숫자).
- legacy: `hwe/func_template.php:151-158` ← `b_currentCity.php:293-295`
- 현재: `web/game/app/game/city/page.tsx:33-39` (StatCell)
- 수정: injury>0이면 `Math.trunc(value*(100-injury)/100)` red 고정 렌더.

**P0-14 [data] 守 컬럼·수비○ 집계 위조 (defenceTrain=0 하드코딩)**
- 문제: general.defence_train이 read 체인에 미배선이라 항상 0 → 守 전원 '△', 수비○ 과집계. 마스킹이 아닌 wrong data.
- legacy: `b_currentCity.php:304-305,434-438` + `func_template.php:160-172` + `templates/cityGeneral.php:31`
- 현재: `CityDetailController.kt:273-277` (0 하드코딩) + `page.tsx:278`; GeneralReadEntity에 defence_train 컬럼 미존재
- 수정: GeneralReadEntity 매핑 추가→배선→FE formatDefenceTrain(실값). 배선 전엔 '-' 마스킹(위조 금지).

### 2.7 `/game/diplomacy` — P0 ×3

**P0-15 [actions] 쓰기 표면(폼·회수·파기) 영구 비표시 — 권한 스케일 불일치**
- 문제: canWrite = permission>=4인데 frontInfo permission은 derivePermission 최대 2(군주도 2) → 군주조차 폼을 못 봄(죽은 UI). legacy는 checkSecretPermission 0..4를 주입, ==4에서 개방.
- legacy: `hwe/t_diplomacy.php:50-51`, `hwe/ts/diplomacy.ts:427-430`, `hwe/func.php:390-435`
- 현재: `web/game/app/game/diplomacy/page.tsx:113` vs `owner/GeneralResolver.kt:71-75`, `FrontInfoController.kt:102,146`
- 수정: logic SecretPermission.check 기반 secretPermission(0..4)을 응답에 노출, canWrite를 그 값>=4로.

**P0-16 [actions] 승인/거부(respond letter) 플로우 FE·wire·엔진 전부 부재 — 조약 성립 불가**
- legacy: `hwe/ts/diplomacy.ts:121-153,285-302`; `t_diplomacy.php:153-154`; `j_diplomacy_respond_letter.php:45-135`
- 현재: 버튼 없음; `CommandWireMapper.kt:279-295`(Send/Rollback/Destroy만); `DiplomacyLetterHandler.kt`(handleRespond 없음)
- 수정: diploRespondLetter wire 코드+TurnDaemonCommand+handleRespond(isAgree/이유 50자/activated+서명+replaced 체인+메시지 2채널) 포팅 후 FE 버튼 배선.

**P0-17 [actions] '추가 문서 작성'(btnRenew)·'이전 문서' selector 부재 — 항상 prevNo:null**
- 문제: 대체 문서(갱신 조약) 생성 불가. BE prevLetterNo 경로는 이미 구현 → FE-only 갭.
- legacy: `t_diplomacy.php:73-74,157`; `diplomacy.ts:333-337,370-417`
- 현재: `page.tsx:132-138` (prevNo:null 고정); BE 존재 `CommandWireMapper.kt:284`, `DiplomacyLetterHandler.kt:91-124`
- 수정: 폼에 이전 문서 select(-새 문서-+활성 서신, 수신국 잠금+프리필) + 카드별 '추가 문서 작성' 버튼.

### 2.8 `/game/generals` — P0 ×1

**P0-18 [data] 병력(crew) 전 장수 공개 노출 — legacy 정보 모델 위반(누설)**
- 문제: legacy 장수일람은 병력 컬럼이 없고 Global/GeneralList SQL도 crew 미선택. 현재 미인증 공개 /api/generals가 crew를 내려보내고 '병력' 컬럼 렌더.
- legacy: `a_genList.php:124-142`; `API/Global/GeneralList.php:68-69`
- 현재: `web/game/app/game/generals/page.tsx:63,243`; `GeneralsController.kt:78`; `F4Dto.kt:50`
- 수정: PublicGeneral에서 crew 제거 + FE 컬럼 삭제. 병력은 인증 세력 표면(/api/nation/general-list)에만.

### 2.9 `/game/global-diplomacy` — P0 ×1

**P0-19 [backend] FE↔BE 필드명 계약 불일치로 페이지 전체 silent 붕괴**
- 문제: BE는 PHP-verbatim `nations[].nation`/`myNationID` 직렬화(테스트로 증명), FE 타입은 `nationId`/`myNationId` → 전 셀 self-diagonal '＼'·관계 0건(위조), 분쟁 행 국명/색 유실, 본인 state 7이 '에러'로 렌더.
- legacy: `GetDiplomacy.php:98-104` + `PageGlobalDiplomacy.vue:36-49`
- 현재: `web/game/types/game.ts:738-756` + `page.tsx:113-117,180-204,251-255` vs `F4Dto.kt:203-240`; 증명 `F4ReadControllersTest.kt:373-376`
- 수정: FE 측을 PHP-verbatim 계약으로 리네임(nation/myNationID). Kotlin DTO는 유지(그것이 패러티명).

### 2.10 `/game/history` (연감) — P0 ×3

**P0-20 [backend] 연감 데이터 영구 공백 — LogHistory 월별 writer 미구현**
- 문제: 엔진 PreUpdateMonthly가 `PreUpdateMonthly { true }` 스텁 → yearbook_history 0행, prod에서 항상 '기록이 없습니다.'
- legacy: `hwe/func_history.php:436-448` (LogHistory → ng_history INSERT)
- 현재: `app/game-engine/.../config/DaemonLoopConfig.kt:220`; 스키마 존재 `V1__baseline.sql:227-237`
- 수정: 월틱 P1에서 getCurrentHistory 동등 스냅샷(map/nations/global_history/global_action) INSERT. global_* jsonb 컬럼 마이그레이션 추가 필요.

**P0-21 [content] 중원 정세·장수 동향 2섹션 영구 빈 배열 — BLOCKED 주석 거짓**
- 문제: HistoryController가 emptyList 하드와이어. 글로벌 로그는 log_entry(scope=SYSTEM, category HISTORY/ACTION, year/month)에 실제 영속화됨.
- legacy: `func_history.php:328-341,369-382`; `PageHistory.vue:36-53`
- 현재: `HistoryController.kt:78-80`; 데이터 존재 `DatabaseHooks.kt:468-500` + `V1__baseline.sql:249-263`
- 수정: log_entry 조회로 채움 — 스냅샷 시멘틱은 P0-20 writer에서 캡처가 정본.

**P0-22 [data] 지도 섹션이 선택 월이 아닌 항상 현재 라이브 지도 표시**
- legacy: `PageHistory.vue:23-33` (`:map-data="history.map"` 주입, disallow-click)
- 현재: `web/game/app/game/history/page.tsx:208-212` (`<MapViewer />` props 없음)
- 수정: MapViewer에 mapData prop 추가(주입 시 self-fetch 생략, 클릭 비활성). 두 맵뷰어 불변식 — MapPreview 동시 수정+양쪽 tsc.

### 2.11 `/game/inherit` — P0 ×6

**P0-23 [backend] availableSpecialWar/availableUnique emptyMap 하드코딩 → 특기 예약 영구 disabled**
- legacy: `hwe/v_inheritPoint.php:41-63`
- 현재: `InheritPointController.kt:112-113`
- 수정: GameConst.availableSpecialWar/allItems(amount>0)에서 {title,info}/{title,rawName,info} 실빌드.

**P0-24 [actions] 능력치 초기화(ResetStat) 폼·버튼 전무 (BE `/api/instant-action/ResetStat` 존재 → 순수 FE 갭)**
- legacy: `PageInheritPoint.vue:175-224,677-710`
- 현재: `web/game/app/game/inherit/page.tsx:372-389`; BE `InstantActionController.kt:58,75` + `InheritActionRegistry.kt:47-50`
- 수정: 기본3+추가3 입력+버튼, api.ts instant-action 헬퍼 신설(web/game에 참조 0건).

**P0-25 [actions] 장수 소유자 확인(CheckOwner) 부재 + availableTargetGeneral 응답 필드 자체 부재**
- legacy: `PageInheritPoint.vue:152-173,650-675` + `v_inheritPoint.php:19-22`
- 현재: `page.tsx:360-370` + `F4Dto.kt:424-440` (필드 없음; TS 타입 `game.ts:941`만 선언)
- 수정: InheritPointResponse에 availableTargetGeneral(npc<2) 추가 + FE select/버튼 → CheckOwner instant-action.

**P0-26 [actions] 유니크 경매 시작(OpenUniqueAuction) 부재 (BE intake auctionOpenUnique 존재 → FE 갭)**
- legacy: `PageInheritPoint.vue:56-84,610-648`
- 현재: `page.tsx:256-268`; BE `CommandWireMapper.kt:80,273` + 엔진 AuctionOpenHandler
- 수정: select 활성화 + 입찰 포인트(min=minSpecificUnique, max=items.previous) + 경매 시작 버튼.

**P0-27 [data] statMin/statMax 컨트롤러 하드코딩 10/90 — 정답 15/80 (GameConst.kt에 이미 존재)**
- legacy: `hwe/d_setting/GameConst.php:6-7` + `v_inheritPoint.php:108-114`
- 현재: `InheritPointController.kt:46-47,116-120` (프로젝트 `GameConst.kt:183-184`는 15/80 정상)
- 수정: 하드코딩 제거, GameConst.defaultStatMin/Max 사용. 하드코딩 정책 위반이기도 함.

**P0-28 [actions] '더 가져오기'(GetMoreLog) 페이지네이션 부재 — 버튼·엔드포인트 둘 다 없음**
- legacy: `PageInheritPoint.vue:239-241,712-726` + `GetMoreLog.php`
- 현재: `page.tsx:393-409`
- 수정: `GET /api/inherit-point/logs?lastId=` (id<lastId DESC LIMIT 30) 신설 + 버튼.

### 2.12 `/game/join` (장수 생성) — P0 ×3

**P0-29 [actions] 유산 포인트 사용 블록 전체 미존재 (wire/엔진까지 부재 — 진짜 백엔드 갭)**
- 문제: 천재로 생성/도시 지정/턴 시간 지정(60-zone)/추가 능력치 고정/필요 포인트 계산 전부 없음. `TurnDaemonCommand.kt:229-243` MakeGeneral에 inherit 필드 자체가 없음, MakeGeneralHandler 처리 없음.
- legacy: `PageJoin.vue:136-230,440-513`; `Join.php:142-145,233-306,357-370,479-498`; `v_join.php:41-42,78`
- 현재: `web/game/app/game/join/page.tsx` (inherit 0줄); `web/game/lib/api.ts:294-295`
- 수정: wire 필드 추가 → MakeGeneral.draw 분기(Join.php draw 순서 보존) → JoinRequest 확장 → FE 블록. 포인트 read는 InheritPointController 재사용.

**P0-30 [actions] 전콘 사용(pic) silent no-op — JoinController가 pic을 드랍, 항상 default.jpg**
- legacy: `PageJoin.vue:68-71,431-438`; `Join.php:379-385`; `v_join.php:69,72-77`
- 현재: `page.tsx:62-68` (미전송); `JoinController.kt:48,134-141` (드랍); `MakeGeneralHandler.kt:113`
- 수정: gateway 유저 프로필(picture/imgsvr/grade) 조회→MakeGeneral.picture 전달 + FE 체크박스/미리보기. 멤버 사진 체계 없으면 부재 백엔드로 백로그.

**P0-31 [content] 국가 임관권유문 섹션 전체 미존재**
- 문제: 셔플 국가 목록+국가색+scoutmsg HTML+토글 2종(localStorage). 전국가 목록+scoutmsg read 엔드포인트 없음(scout_msg는 nation.meta에 존재하나 자국만 노출).
- legacy: `PageJoin.vue:5-55,287,461-471`; `v_join.php:44-50`
- 현재: 섹션 부재 (`NationFinanceController.kt:72`는 자국만)
- 수정: join용 nation-scout-list read 컨트롤러 신설 후 FE 섹션 구현.

### 2.13 `/game/mailbox` — P0 ×4

**P0-32 [actions] 서신 발송 기능 전체 부재 — send 엔드포인트 자체 없음**
- legacy: `MessagePanel.vue:3-35,735-759`; `SendMessage.php:26-80`; `func_message.php:4-52`
- 현재: 송신 UI 부재; game-api에 send 컨트롤러 부재(POST는 accept/decline뿐). 연락처 read(`ContactController.kt:35`)는 존재.
- 수정: `POST /api/messages/send`(public/national/private 분기+MessageTarget) 신설 + 연락처 셀렉트(optgroup, *군주*/#외교권자#)+입력 99자+버튼 복원.

**P0-33 [content] MailMessage 인터페이스가 실제 DTO와 불일치 — 발신자/시각 공란 + 위조 '미읽음' 배지**
- legacy: `defs/API/Message.ts:21-37`; `MessagePlate.vue:24-100`
- 현재: `web/game/app/game/mailbox/page.tsx:12-24,144,171-173,200-201` vs `MessageDto.kt:18-36` (srcName/date/read 필드 없음)
- 수정: DTO 실필드(srcTarget?.name, time, text) 매핑; read/unreadCount 위조 배지 제거(legacy엔 latestRead 커서만 존재).

**P0-34 [backend] 외교 메시지 마스킹 누락 — 비외교권자에게 원문 노출**
- 문제: 페이지가 쓰는 `GET /api/mailbox/{mailbox}`는 type 구분·마스킹 없음. 마스킹은 미사용 recent/old에만 구현.
- legacy: `GetRecentMessage.php:125-139` (permission<3 → '(외교 메시지입니다)')
- 현재: `MailboxController.kt:47-54`; `page.tsx:50-67`
- 수정: 페이지를 `/api/mailbox/recent`(D7 봉투)로 전환하거나 raw 경로에 type 분리+마스킹 적용.

**P0-35 [backend] 외교 수락/거절 권한 게이트 부재 — 평장수가 불가침/종전 수락 가능(권한 상승)**
- legacy: `DiplomaticMessage.php:57-72` (permission<4 INVALID); `MessagePlate.vue:105-124,187-195`; `msg.ts:306-312`
- 현재: `DiplomaticMessageController.kt:55-122` (국가 일치만); `page.tsx:207-212,210-211`
- 수정: accept/decline에 secretPermission>=4 검사('해당 국가의 외교권자가 아닙니다.' 패러티), FE는 <4 disabled. (MailboxController.kt:275-303의 포팅본 재사용)

### 2.14 `/game/map` — P0 ×1

**P0-36 [data] 도시 state 아이콘이 잘못된 컬럼(frontState)에서 — 재해/호황 표시 위조**
- 문제: legacy는 city.state(이벤트 코드 1~9)인데 현재 BE는 front_state(전선 0~3)를 state 슬롯에 직렬화 → 전선 도시가 풍작/호황/혹한 아이콘을 거짓 표시, 실제 재난은 절대 미표시. 근본: 스키마에 city.state 컬럼 부재 + 엔진 메모리-only(재기동 유실).
- legacy: `func_map.php:144-148`; `hwe/sql/schema.sql` (state INT(2) vs front INT(1)); `RaiseDisaster.php:34-35,107,127`; `MapCityDetail.vue:44-46`
- 현재: `MapPreviewController.kt:122`, `WorldMapController.kt:101`, `CityReadRepository.kt:56-57`, `V1__baseline.sql:39-40`, `WorldActionContext.kt:442-444`
- 수정: city.state 컬럼 추가(Flyway)→flush 경로 포함(diffCity)→read 추가→state 직렬화. FE `MapViewer.tsx:236`의 state<=5 캡도 legacy state>0으로(코드 6~9 해제).

### 2.15 `/game/my-boss` (인사부) — P0 ×7

**P0-37 [content] 페이지 개념 전체가 fabricated — 인사부가 아님**
- 문제: legacy 인사부 = 관직 로스터(12→getNationChiefLevel, 초상+belong년)+오호장군/건안칠자+수뇌부 임명+도시 관직 임명+추방. 현재는 '내 상관' 카드 1장(통/무/지/경험/충성/병/금/쌀) — legacy가 보여주는 어떤 것도 없음.
- legacy: `hwe/b_myBossInfo.php:105-565`
- 현재: `web/game/app/game/my-boss/page.tsx:69-111`
- 수정: 인사부로 재구축(로스터+랭크행+임명/추방/외교권자 섹션) — P0-43 read DTO 선행.

**P0-38 [data] invented 카드조차 wrong data — DTO 필드명 전부 불일치 + 재야 가드 불발**
- 현재: `page.tsx:20,58,76-107` (General 캐스트) vs `IdentityDto.kt:502-509` (MyBossResponse) — name 공란/배지 '급'/스탯 '-'; `!boss` 분기 절대 불발(재야도 빈 카드).
- legacy: `b_myBossInfo.php:21-24` ('재야입니다.')
- 수정: 캐스트 제거, 실제 DTO 소비 + officer_level==0 재야 분기.

**P0-39 [actions] 수뇌부 임명(do수뇌임명) end-to-end 부재** — `j_myBossInfo.php:77-133` + `bossInfo.ts:155-246`; 현재 FE 버튼 0·BE 엔드포인트 0. 수정: intake 엔드포인트(CommandReserveService→엔진 핸들러→ChangeRecorder, one-daemon-write), deny 문자열 byte-exact, JosaUtil confirm.

**P0-40 [actions] 도시 관직 임명(태수/군사/종사, do도시임명) 부재** — `b_myBossInfo.php:316-461` + `j_myBossInfo.php:135-187`; FE+BE 부재. 수정: destCityID+officerLevel intake + region optgroup select.

**P0-41 [actions] 추방(do추방) 부재 — 몰수/배신 패널티/부대 해산/NPC 복수 메시지/로그 포함** — `j_myBossInfo.php:189-326` + `bossInfo.ts:113-153`; 수정: 엔진 핸들러로 포팅(RandUtil(LiteHashDrbg('BanNPC' 시드)), 로그·복수 메시지 5종 byte-exact) + intake + FE confirm.

**P0-42 [actions] 외교권자/조언자 임명(군주 전용) 부재** — `j_general_set_permission.php:1-80` + `b_myBossInfo.php:63-100,285-311`; 현재 `SecretPermission.kt`는 read-only 포팅뿐, set-permission mutation 없음. 수정: ruler-only intake('군주가 아닙니다') + general.permission ChangeRecorder write + 외교권자 최대 2 cap.

**P0-43 [backend] read DTO가 인사부 요구 데이터의 사실상 0% — 전면 신설 필요**
- 현재: `MyController.kt:189-209` 6필드뿐. 필요: nation{name,level,color,chief_set}, 로스터, 후보 3종(order by npc,binary(name)), killnum top5/firenum top7, 도시·관직 맵, ambassador/auditor 후보, myLevel/chiefStatMin.
- legacy: `b_myBossInfo.php:26-36,63-100,121-151,327,471-494`
- 수정: 전용 BossInfoController(또는 MyController 확장) 풀 DTO, SecretPermission.kt 재사용.

### 2.16 `/game/my-cities` — P0 ×4

**P0-44 [actions] 서버 정렬(12종) 폼 전체 누락** — `b_myCityInfo.php:54-70,103-169,8`; 현재 정렬 UI 없음+`MyController.kt:149` id ASC 고정. 수정: ?type=1..12 또는 FE 정렬(PHP usort 동형 비교자) + UI 복원.

**P0-45 [actions] extExpandCity '재정렬' 클라 9버튼 누락** — `extExpandCity.ts:576-673,87-127`; 데이터는 이미 보유 → 순수 FE 작업.

**P0-46 [actions] '암행부 연동'(도시별 장수 13컬럼 인라인 확장+추천 명령 강조) 누락** — `extExpandCity.ts:295-394,298-305,339-344`; 장수 read API는 존재 → 주로 FE 갭.

**P0-47 [actions] '인사부 연동' 즉시 임명 mutation 누락 — BE '임명' 엔드포인트 자체 부재(grep 0건)** — `extExpandCity.ts:129-293,257-262` + `j_myBossInfo.php:35-40`; 수정: j_myBossInfo 동등 intake(ChangeRecorder 경유) 신설 후 FE 태/군/종 버튼. (P0-39/40과 동일 인프라 — 공동 구현)

### 2.17 `/game/my-nation` — P0 ×2

**P0-48 [data] 장수 수(gennum) wrong data — 시드 meta에 gennum 없음 + Q12 recompute 미구현 → 항상 0**
- legacy: `b_myKingdomInfo.php` 장수 행 + `func.php:87` refreshNationStaticInfo
- 현재: `MyController.kt:244`; `ScenarioImporter.kt:156-158`; `PostUpdateMonthly.kt:396-397` (주석만)
- 수정: read에서 GeneralReadEntity COUNT(npc!=5) 라이브 산출; 별도로 시드 gennum + 월틱 Q12 구현.

**P0-49 [content] 국가열전 '-' 하드코딩 — 격리 사유 stale (log_entry NATION/HISTORY 이미 기록 중)**
- legacy: `b_myKingdomInfo.php` 국가열전 행 + `func_history.php:296-303`
- 현재: `page.tsx:181-185`; `IdentityDto.kt:525-526` (stale quarantine); 미러 패턴 `WorldLogReadRepository.kt:40-50`
- 수정: NationLogReadRepository(scope=NATION, category=HISTORY, nation_id, id DESC) 신설 → history 필드 노출 → 렌더.

### 2.18 `/game/nation` — P0 ×1

**P0-50 [actions] BuyHiddenBuff/BuyRandomUnique 양 버튼 guaranteed-fail (generalId 미전송 → 400 매번)**
- 문제: api.command 호출에 generalId 누락 → CommandController @RequestParam(필수) → 400 → '구매 요청에 실패했습니다.' 매번. BE wire+엔진 핸들러는 존재 — 순수 깨진 FE 배선.
- legacy: `PageInheritPoint.vue:476-503,103`
- 현재: `web/game/app/game/nation/page.tsx:72,83` + `api.ts:345-351` + `CommandController.kt:55-60`; BE `CommandWireMapper.kt:177-184`, `InheritResetHandler.kt:136`
- 수정: inherit 페이지 방식(frontInfo.general.generalId) 전달 — 더 좋게는 중복 상점 삭제 후 /game/inherit로 일원화.

### 2.19 `/game/nation-finance` (내무부) — P0 ×4 ⚠(데이터 잘림)

**P0-51 [backend] 응답 shape가 FE 타입과 불일치 — 국가 소속자 전원 런타임 크래시**
- 문제: Kotlin DTO는 평면(income:Int…), FE 타입은 중첩(income.gold.city…) → `page.tsx:142`에서 number에 .gold.city 접근 = TypeError, 페이지 전체 붕괴.
- legacy: `v_nationStratFinan.php:104-153` + `PageNationStratFinan.vue:247-283`
- 현재: `F4Dto.kt:243-263` + `NationFinanceController.kt:54-75` vs `types/game.ts:770-796` + `page.tsx:134-152`
- 수정: legacy 중첩 shape로 DTO 재구축(income{gold{city,war},rice{city,wall}}, policy, warSettingCnt{remain,inc,max}, officerLevel/year/month) + 중첩 JSON 컨트롤러 테스트.

**P0-52 [data] income/outcome 위조 0 — 아무도 쓰지 않는 meta 키 read**
- legacy: `v_nationStratFinan.php:77-115` (rate=100 기준 라이브 계산)
- 현재: `NationFinanceController.kt:63-64` (metaInt income/outcome — 기록 주체 없음)
- 수정: logic의 이식 완료 함수(getGoldIncome/getRiceIncome/getWallIncome/getOutcome — `ProcessIncome.kt:6-9,126` + war-gold)를 read 경로에서 호출, 4분해 반환.

**P0-53 [backend] 모든 setter 필드의 read 스토어/키 불일치 — 라운드트립 불능(silent wrong data)**
- 문제: nationMsg/scoutMsg/warSettingCnt는 nation_env KV에 기록되는데 meta에서 read; blockWar/blockScout는 meta["war"]/["scout"] Int인데 metaBool("block_war")로 read → setter 성공 후에도 화면은 ""/0/false.
- legacy: `v_nationStratFinan.php:129-130,146-151`
- 현재: `NationFinanceController.kt:63-72` vs 실제 쓰기 `NationFinanceSetterHandler.kt:40-99`, `NationFinanceSetters.kt:59-101`
- 수정: nation_env KV read(NationEnvReadRepository) + meta["war"]/["scout"] Int!=0 — setter→엔진→flush→GET 반영 IT 필수.

**P0-54 [content] 외교관계 섹션(전국가 7컬럼 표) 전체 부재**
- legacy: `PageNationStratFinan.vue:4-46` + `v_nationStratFinan.php:45-72`
- 현재: `page.tsx` 흔적 없음(섹션은 국가 방침부터, line 184); DTO에 year/month/nationsList 부재
- 수정: 페이지 첫 섹션으로 7컬럼 표(국가명/국력/장수/속령/상태/기간/종료 시점, 자국 '-'/state 7) + nationsList 응답 확장 + diplomacyStateInfo 매핑 포팅.

---

## 3. P1 — 페이지별 (데이터/액션/콘텐츠 결손)

### `/game` (메인) — 7건
- **P1-001 [content]** GameInfo 아래 3라인(접속중인 국가/접속자/국가방침) 부재 — BE도 onlineNations/onlineGen/notice 미배출. `PageFront.vue:27-33` + `GetFrontInfo.php:217,265,311-312` vs `GameChrome.tsx:80-156` + `FrontInfoController.kt:286-288,329-390`. 수정: KV 경로+DTO 필드 신설+섹션 추가.
- **P1-002 [backend]** 설문 셀 단절 — FrontGlobalInfo에 lastVote 없음(vote:Boolean만) → 항상 '진행중인 설문 없음'. `GetFrontInfo.php:183-189,231` vs `GameInfo.tsx:109`, `types.ts:48`, `IdentityDto.kt:96`, `FrontInfoController.kt:379-380`. 수정: lastVote{id,title,endDate}+myLastVote 배출, FE 토스트.
- **P1-003 [data]** 메인 맵이 10분 캐시 중립 preview — legacy는 GetMap(neutralView:0, showMe:1) 라이브. `PageFront.vue:516-529` vs `MapViewer.tsx:103` + `MapPreviewController.kt:52-70`. 수정: 라이브/인증 파라미터 + refreshKey 재조회.
- **P1-004 [content]** 예약 링 年/月·HH:mm·자율행동 표시 결손 — read 응답이 slots만(turnTime/turnTerm/year/month/autorun_limit/cutTurn 없음). `GetReservedCommand.php:55-92` vs `ReservedCommandsController.kt:42-46` + `PartialReservedCommand.tsx:54-55`. 수정: 응답 필드 추가+cutTurn 포팅+FE 렌더.
- **P1-005 [content]** GeneralBasicCard 부대(troopInfo) 행+다음 턴 카운터 부재 — BE troopInfo 합성 없음. `GeneralBasicCard.vue:139-150,280-283` + `GetFrontInfo.php:470-486` vs `GeneralBasicCard.tsx:15` + `IdentityDto.kt:203`. 
- **P1-006 [content]** NationBasicCard 전략 제한 툴팁(impossibleStrategicCommand) 부재 — getNextAvailableTurn 미배출. `GetFrontInfo.php:275-284,316-317` vs `NationBasicCard.tsx:13` + `FrontInfoController.kt:286-288`.
- **P1-007 [actions]** 갱 신/로비로/명령으로 버튼 플레이트 부재. `PageFront.vue:55-66,95-111,285-343` vs `GameChrome.tsx:80-156`.

### `/game/auction` — 5건
- **P1-008 [actions]** ?type=unique 딥링크 무시(항상 금/쌀 탭). `v_auction.php:14` vs `page.tsx:18` + `control-bar-config.ts:73-74`. 수정: useSearchParams.
- **P1-009 [content]** '이전 경매 20건' 영구 공백 — recentLogs=emptyList인데 엔진은 이미 log_entry(category=auction)에 기록 중(stale BLOCKED). `GetActiveResourceAuctionList.php:48` + `func_history.php:93-95` vs `AuctionController.kt:90`. 수정: log_entry 최신 20건 역순 + scope=action enum 버그 점검.
- **P1-010 [backend]** viewer 식별 부재(viewerGeneralId=0 고정) — 내 가명 '-', isMe 하이라이트 불발. CommandController는 이미 principal 해석 가능('세션 없음' 전제 stale). `GetUniqueItemAuctionList.php:40,86,94` + `Auction.php:35-54` vs `AuctionController.kt:56,115,128,162,169,238`. 수정: principal→generalId + obfuscatedNamePool KV 포팅.
- **P1-011 [content]** remainPoint null 고정 → 입찰 max 클램프 소실(P6에서 로직 이식 완료, read 배선만 부재). `GetUniqueItemAuctionDetail.php:77-82,101` vs `AuctionController.kt:170`.
- **P1-012 [content]** 등록 성공 토스트에 경매 번호 누락(202 비동기라 auctionID 미상). `AuctionResource.vue:296-299` vs `AuctionResource.tsx:145` + `CommandWireMapper.kt:259-272`. P0-04와 묶어 처리.

### `/game/betting` — 4건
- **P1-013 [data]** 목록 type 필터 누락 — 국가베팅 페이지가 전체(토너 포함) 노출. BE는 ?type 지원. `PageNationBetting.vue:58-60` vs `page.tsx:56`, `api.ts:276`, `BettingController.kt:81-101`.
- **P1-014 [content]** 목록 표기 불일치 — '[{open년}년 {open월}월] {name}'+3상태(종료/까지/베팅 마감) vs 현재 2상태 배지(마감-미정산이 '진행 중'으로 오표시)+legacy에 없는 총액. `PageNationBetting.vue:14-19` vs `page.tsx:111-118`.
- **P1-015 [data]** 정렬 — legacy reverse(최신 우선) vs 삽입순. `PageNationBetting.vue:9` vs `page.tsx:58`.
- **P1-016 [actions]** GlobalMenu '천통국 베팅' 죽은 링크(v_nationBetting.php 상대경로 404). `GlobalMenu.php:22` vs `global-menu-fixture.ts:12`, `GlobalMenu.tsx:33-36`. 수정: '/game/betting'으로 교체.

### `/game/board` — 1건
- **P1-017 [content]** author_icon(64px 초상) 전구간 부재 — 스키마·DTO·렌더 모두 없음(주석으로 백로그 명시). `BoardArticle.vue:15-17` + `j_board_article_add.php:65,73` vs `page.tsx:87-90` + `F4Dto.kt:460-465`. 수정: board_post.author_icon 마이그레이션→BoardHandler→DTO→렌더.

### `/game/chief-center` — 6건
- **P1-018 [data]** che_발령 brief 부대 재작성(postFilterNationCommand) 미적용 — page.tsx:15 주석 '(server-side 적용)'은 허위. `postFilterNationCommandGen.ts` + `PageChiefCenter.vue:154-178` vs `page.tsx:15,137` + `ChiefCenterController.kt:75-91`.
- **P1-019 [content]** 슬롯별 실행 시각 컬럼 부재(turnTime+idx*turnTerm — 데이터는 이미 응답에 있음). `TopItem.vue:192-206` + `ChiefReservedCommand.vue:535-547` vs `page.tsx:118-142` + `ChiefCommandReserve.tsx:113-141`.
- **P1-020 [content]** 자율 행동(autorun_limit) 표시 전무 — general aux read 경로 부재로 DTO null 고정. `ChiefReservedCommand.vue:512-528` + `GetReservedCommand.php:163` vs `ChiefCenterController.kt:156-157`.
- **P1-021 [data]** 팔레트 메타 3종 발산 — possible=full precheck(legacy는 hasMinConditionMet), compensation 항상 0, title=simpleName, canDisplay 필터 없음. `func.php:481-513` vs `ChiefCenterController.kt:178-220`.
- **P1-022 [content]** '연구' 카테고리(event_* 9종) 시나리오 무관 고정 노출 — legacy availableChiefCommand엔 없음(1010에서 legacy가 거부할 명령 제공). `GameConstBase.php:378-415` + `ReserveCommand.php:46-48` vs `F4StateText.kt:129-133` + stale 주석 `page.tsx:8-11`.
- **P1-023 [content]** 열람 권한 게이트 양측 모두 발산 — legacy는 permission>=1 read-only 열람 허용+문구 2종, 현재 FE는 officerLevel>=5 전면 차단(문구 불일치)+BE는 무게이트(일반 장수도 API로 전체 수신). `GetReservedCommand.php:57-62` + `func.php:390-430` vs `page.tsx:193-195,218-222` + `ChiefCenterController.kt:66-71`.

### `/game/city` — 4건
- **P1-024 [content]** 명 령 컬럼 공백 — 아국 비-NPC 예약 brief 5턴(turnText) 미배출. `b_currentCity.php:243-255,328-337` vs `CityDetailController.kt:215-250` + `page.tsx:283-284`.
- **P1-025 [data]** showDetailedInfo 게이트 과소 — CityConst path 인접 룰 미구현(인접 적도시 장수 명단 은닉 = 정찰 정보 누락). `b_currentCity.php:185-193,320-323` vs `CityDetailController.kt:190-194`.
- **P1-026 [data]** !valid 마스킹 집합 과대 — max값/시세/관직자명은 항상, def/wall은 공백지면 노출이 legacy. `b_currentCity.php:210-212,225-240,455-481` vs `CityDetailController.kt:296-312` + `page.tsx:147,221-225`.
- **P1-027 [data]** 관직명 해석 발산 — legacy는 nlevel=8 generic 칭호, 현재는 국가 레벨별 칭호(PHP 승). `b_currentCity.php:298` + `func_converter.php:522-535` vs `CityDetailController.kt:240`.

### `/game/diplomacy` — 5건
- **P1-028 [data]** 서신 정렬 역순(oldest-first) — PHP는 date desc+prepend. `j_diplomacy_get_letter.php:46` vs `DiplomacyLetterReadRepository.kt:59`, `page.tsx:316`, `types/game.ts:731`.
- **P1-029 [content]** 페이지 접근 게이트 부재(<1 차단 문구·무소속 deny). `t_diplomacy.php:28-32` vs `page.tsx`(게이트 없음), `DiplomacyController.kt:66-117`.
- **P1-030 [actions]** 파기 2단계 토스트 구분 불가 + 엔진 deny 무음 — res?.state 항상 undefined. `j_diplomacy_destroy_letter.php` + `diplomacy.ts:206-211` vs `CommandController.kt:52-82`, `page.tsx:165-184`, `DiplomacyLetterHandler.kt:247-248,279`. 수정: requestId/SSE 결과 회신.
- **P1-031 [backend]** read 권한 모델이 엔진과 불일치 — ambassador/auditor 드랍('BLOCKED' 사유 stale: SecretPermission.kt meta 구현 존재). `func.php:390-435` + `j_diplomacy_get_letter.php:50-53` vs `DiplomacyController.kt:148-169` + `SecretPermission.kt:39-63`.
- **P1-032 [backend]** nations 맵 자국·0 미필터. PHP는 제외. vs `DiplomacyController.kt:67-69`, `page.tsx:108,288-305`.

### `/game/generals` — 5건
- **P1-033 [content]** legacy 15컬럼 중 7개 미렌더(얼굴/연령/성격/특기/Lv/관직/삭턴) — DTO는 이미 보유, 페이지 미소비. `a_genList.php:127-141,187-204` vs `page.tsx:55-64,221-244`; 데이터 `F4Dto.kt:56-76`.
- **P1-034 [content]** injury 감산·lbonus(+N cyan) 미적용 — raw 렌더(부상 비가시). `a_genList.php:146-164` vs `page.tsx:228-230`.
- **P1-035 [backend]** 벌점 컬럼+기본 정렬(type 9 refresh_score_total DESC) 재현 불가 — general_access_log 원천 부재(§2 BLOCKED 문서화, P8). `a_genList.php:8,108,141,174,203-204` vs `GeneralsController.kt:48`, `F4Dto.kt:77-81`.
- **P1-036 [actions]** 정렬 15→8 축소(관직/삭턴/벌점/성격/내특/전특/연령/NPC 불가). `a_genList.php:67-84,99-115` vs `page.tsx:44-64`. P1-033과 함께(raw code desc 정렬이 패러티).
- **P1-037 [backend]** 접근 제어·갱신 가산 발산 — legacy 로그인+increaseRefresh(+2)+limit vs 현재 permitAll·없음(주석 divergence). `a_genList.php:11-28` vs `GeneralsController.kt:23-25,42-43`.

### `/game/global-diplomacy` — 2건
- **P1-038 [content]** 국가표 장수(gennum) 컬럼 부재 — DTO는 보유(F4Dto.kt:214), FE 타입/렌더만 누락. `SimpleNationList.vue:4-27` vs `page.tsx:306-343` + `types/game.ts:738-745`.
- **P1-039 [data]** 분쟁 현황이 '도시 {cityId}' raw 표기 — 컨트롤러는 c.name 보유하나 미배출, FE cityConst 없음. `PageGlobalDiplomacy.vue:68` vs `page.tsx:236-249` + `DiplomacyController.kt:202-224`. 수정: cityName 사이드카 또는 FE 해석.

### `/game/history` — 3건
- **P1-040 [actions]** 현재 월 '(현재)' 라이브 연감 부재 — GetCurrentHistory 동등 엔드포인트 자체 없음, FE는 last 클램프. `PageHistory.vue:138-142,155-182` vs `page.tsx:142-151` + `HistoryController.kt:69`.
- **P1-041 [actions]** 교차 서버 연감(serverID) 드롭 — OQ-8 의도 드롭이나 prod 2서버 운영 중이라 실사용 갭(재결정 필요). `v_history.php:14-25` + `GetHistory.php:34-39,145-163` vs `page.tsx:19` + `HistoryController.kt:39`.
- **P1-042 [backend]** 접근 제어/refresh 패러티 부재(현재 PUBLIC). `GetHistory.php:145-163` vs `HistoryController.kt:14`. 최소 인증 게이트 먼저.

### `/game/inherit` — 3건
- **P1-043 [content]** 로그 date 체인 전체 탈락 — 화면에 빈 '[]', TS 타입과 응답 불일치. `v_inheritPoint.php:74` vs `F4Dto.kt:417-422` + `page.tsx:401-403` + `V1__baseline.sql:291-298`.
- **P1-044 [actions]** 버프 구매 +1단계 고정 — legacy는 목표 레벨 임의 선택+리셋+비용 diff 표시. `PageInheritPoint.vue:126-148,476-512` vs `page.tsx:316-353,341-345`.
- **P1-045 [content]** 선택 특기/유니크 info 미표시 + 첫 항목 자동 선택 + 버튼 한글 라벨 불일치(구입/경매 시작…). `PageInheritPoint.vue:47-51,74-79,453` vs `page.tsx:243-267`.

### `/game/join` — 8건
- **P1-046 [data]** 능력치 조절 다른 세트·알고리즘 — legacy 4종(비율 정규화, 합=165 보장) vs 현재 5종(고정값+'균형'). `generalStats.ts:3-140` vs `page.tsx:82-103,141-147`. 수정: 4종 그대로 포팅.
- **P1-047 [content]** '묵력' 오기 2곳(→무력). `page.tsx:144,151`.
- **P1-048 [data]** 성격 select가 raw 코드('che_안전') — GameConst.kt:230-244 한글명 보유, GetConstController name=null BLOCKED 해제 가능. `PageJoin.vue:73-90` vs `page.tsx:13-25,176-187` + `GetConstController.kt:139-160`.
- **P1-049 [content]** 안내 문구 2종(15~80 경고/165 총합+보너스 3~5) 미존재 — bornMin/MaxStatBonus는 /api/const 미노출(GameConst.kt:177-178에 값 존재). `PageJoin.vue:123-134` vs `page.tsx:137-139`.
- **P1-050 [actions]** 합계 미달 confirm 게이트 + '다시 입력' 버튼 부재. `PageJoin.vue:234-235,392-418` vs `page.tsx:47-80,189-203`.
- **P1-051 [backend]** blockCustomGeneralName &2(무작위 이름 강제) 미지원 — &1만 검사. `v_join.php:70` + `Join.php:175,399-401,446-452` vs `JoinController.kt:68-73` + `page.tsx:124-134`.
- **P1-052 [backend]** Join.php launch 조건부 5종 미이식 — relYear>=3 경험치 20퍼센타일*0.8, relYear>=4 betray+2, genius 잔여 cap, penalty 병합, 이름 정제 체인. `Join.php:131-134,155-163,250,267-271,345-355,394-397` vs `MakeGeneralHandler.kt:92,118-119`, `MakeGeneral.kt:72-75`, `JoinController.kt:86,102`. B1 골든 백로그와 병합.
- **P1-053 [backend]** 상수/성격 하드코딩 이중 진실(/api/const 미사용) + 이름 prefill 항상 빈칸. `PageJoin.vue:349-369` vs `page.tsx:9-25,29-32` + `GetConstController.kt:119-130`. M-config/HARDCODE_INVENTORY 정책 위반.

### `/game/mailbox` — 5건
- **P1-054 [data]** 정렬·필터·리밋 발산 — valid_until 무필터·id ASC·무제한 → 만료/수락된 서신에 수락 버튼 노출. `Message.php:170-193` vs `MailboxController.kt:51` + `page.tsx:189`. recent(이미 구현, :324-334)로 전환.
- **P1-055 [content]** 헤더/본문 리치 필드 부재('이름:국가' 국색 배지, 나▶상대, 64px 아이콘, linkify, '삭제된 메시지입니다', 시각) + INFINITE_DATE 문자열 비교 불일치로 무기한 메시지에 항상 만료 배지(위조 표시). `MessagePlate.vue:7-103` vs `page.tsx:194-206`.
- **P1-056 [actions]** 갱신 모델 발산 — sequence 2.5초 폴링+신규 toast vs SSE 턴 완료만(턴 사이 실시간 수신 불가). `MessagePanel.vue:478-554,288-363` vs `page.tsx:91-96,170`.
- **P1-057 [backend]** recent/old의 currentGeneral()이 '첫 playable 장수' 폴백 — 전환 시 전 유저가 타인 개인함 열람 + latestRead 항상 (0,0). `GetRecentMessage.php:70-83` vs `MailboxController.kt:268-272,159`. 인증 주체→본인 general 매핑 선행.
- **P1-058 [actions]** (missingActions 묶음) 본인 메시지 5분 내 삭제 ❌(delete 엔드포인트 없음), 등용 수락/거절(scout BE 부재), 이전 메시지 불러오기(BE 존재·FE 미사용), 모두 읽음(general_stor BLOCKED), 회신 타깃/여기로, 접기. `MessagePlate.vue:14-22,105-124`; `MessagePanel.vue:41-43,65-67,76-78,111-118,573-589,698-733` vs `MailboxController.kt:174` 외.

### `/game/map` — 4건
- **P1-059 [content]** 글로벌 히스토리 10건 블록 통째 부재 — /api/world-log 재사용 가능. `PageCachedMap.vue:17-22` + `GetCachedMap.php:84,93` vs `page.tsx:11-19`.
- **P1-060 [content]** 연월 타이틀 초반 3년 색상+기술등급 툴팁 부재 — startYear/initialAllowedTechLevel/techLevelIncYear 미배출. `MapViewer.vue:16-25,256-304` vs `MapViewer.tsx:322` + `MapPreviewDto.kt:18-27` + `GetConstController.kt:103`.
- **P1-061 [data]** 계절 경계 공식 상이(3·6·9·12월 틀림) — legacy <=3/<=6/<=9. `MapViewer.vue:306-319` vs `MapViewer.tsx:84-90`. 두 맵뷰어 불변식: MapPreview도 동시 수정.
- **P1-062 [actions]** '도시명 표기'·'두번 탭 이동' 토글 2종 부재(클라 전용). `MapViewer.vue:30-53` vs `MapViewer.tsx:296`.

### `/game/my-boss` — 2건
- **P1-063 [data]** 후보 select 시멘틱 부재 — order by npc,binary(name)·3색 옵션(빨강/주황/흰)+범례·kick 목록 '(통/무/지, killturn턴)'·officer_set 제외·노랑 잠금 범례. `b_myBossInfo.php:29-31,216-224,280,330-332,459,515-526,544-549`.
- **P1-064 [content]** 오호장군【승전】/건안칠자【계략】 부재 — nation 스코프 rank_data top5/top7 엔드포인트 없음. `b_myBossInfo.php:131-151,187-193`.

### `/game/my-cities` — 4건
- **P1-065 [content]** 자금/군량/둔전 수입 3종 항상 '-' — Kotlin 계산 함수는 logic에 존재(IncomeTick.kt:29/47/65), read 측 nationType fold 미조립(문서화 격리). `b_myCityInfo.php:189-194,209-211,226-230` vs `page.tsx:76-81` + `IdentityDto.kt:483-486`.
- **P1-066 [data]** 기본 정렬 발산 — legacy type=10 시세 desc + region/level 그룹 구분 vs id ASC. `b_myCityInfo.php:8,146-150,196-202` vs `MyController.kt:149`.
- **P1-067 [backend]** 관직자 조회 nation 필터 없음(함락 후 타국 장수 표시 가능) + last-wins/first-wins 차이. `b_myCityInfo.php:80,193` vs `MyController.kt:151-154`.
- **P1-068 [content]** 로드 시 자동 스탯 경고색+[remain] 주석 부재(순수 FE, 데이터 보유). `extExpandCity.ts:486-546,676` vs `page.tsx:95-105`.

### `/game/my-generals` — 7건
- **P1-069 [content]** 벌점 15번째 컬럼 부재 — general_access_log 원천 진짜 부재(grep 0). FE 헬퍼는 이미 포팅(`formatRefreshScore.ts`) — 데이터 배관만 결손. `b_myGenInfo.php:20,109-110,129,178-179` vs `page.tsx:125` + `IdentityDto.kt:379-424`.
- **P1-070 [content]** 얼굴 컬럼이 파일명 텍스트 — 64px 초상 미렌더(BE picture/imageServer 공급 중, FE GetImageURL 헬퍼 부재). `b_myGenInfo.php:159-162` + `func.php:100-107` vs `page.tsx:134-136`.
- **P1-071 [data]** 정렬 계급(type2)/명성(type3) 누락 — raw dedication/experience 미배출이 원인(type3은 무문서 silent 누락). `b_myGenInfo.php:66-67,92-93` vs `page.tsx:27-54` + `IdentityDto.kt:379-424`.
- **P1-072 [data]** 성격/내특/전특 정렬 시멘틱 발산 — legacy는 raw 코드 컬럼 DESC, 현재는 한글명 localeCompare. `b_myGenInfo.php:101-110` vs `page.tsx:49-51,90-95`.
- **P1-073 [content]** isunited 시 소유 플레이어명 '(ownerName)' 미표시. `b_myGenInfo.php:31-36,155-157` vs `page.tsx:137` + MyController.
- **P1-074 [content]** 재야 가드 divergence — legacy '재야입니다.' 하드블록 vs 현재 본인 1행 위조 테이블. `b_myGenInfo.php:24-27` vs `MyController.kt:92-96` + `page.tsx:172-176`.
- **P1-075 [backend]** DTO 체인 갭 종합 — refreshScoreTotal/dedication/experience/raw 코드 3종/ownerName 미배출(나머지 매핑은 byte-검증 FAITHFUL). `b_myGenInfo.php:90-110` vs `IdentityDto.kt:379-424` + `MyController.kt:82-129`.

### `/game/my-nation` — 2건
- **P1-076 [content]** 수입/예산 6필드 전부 '-' — 공식은 이미 logic 이식 완료(ProcessIncome 등), read 측 조립만 부재(wire-up). `b_myKingdomInfo.php` 수입 블록 + `func_time_event.php:141-252` vs `page.tsx:138-159` + `IdentityDto.kt:568-570` + `IncomeTick.kt`/`ProcessIncome.kt:26-27`.
- **P1-077 [data]** 세율/지급률 '-' — ScenarioImporter가 rate=15/bill=100 미시드(legacy는 항상 시드). `Scenario/Nation.php:111-112` vs `ScenarioImporter.kt:156-158` + `MyController.kt:246-247`. 기존 월드 backfill 포함.

### `/game/nation` — 6건
- **P1-078 [content]** 19필드 중 8필드 '-' 스텁(세금/단기·세곡/둔전·수입/지출 금·미·국고/병량 예산). `b_myKingdomInfo.php:68-92,117-138` vs `page.tsx:168-185` + `IdentityDto.kt:521-526,568-570`. → P1-076과 동일 작업.
- **P1-079 [content]** 국가열전 '-' 스텁. → P0-49와 동일 작업. `page.tsx:206-210`.
- **P1-080 [content]** 세율/지급률 '-' 폴백. → P1-077와 동일. `page.tsx:166,173` + `MyController.kt:246-247`.
- **P1-081 [content]** 유산 버프 한글 라벨/설명 divergence('per level' 영문 혼입) — legacy inheritBuffHelpText verbatim 교체. `PageInheritPoint.vue:381-420` vs `page.tsx:11-20`.
- **P1-082 [data]** 버프 키명이 PHP wire 계약과 발산 — 'success'/'fail'(내부 CALC 별칭)을 저장 키로 사용 → aux JSON shape가 PHP 덤프와 어긋남(패러티 하니스/마이그레이션/rehydrate 파손). `TriggerInheritBuff.php:13-31` + `BuyHiddenBuff.php` vs `page.tsx:15-16` + `BuyHiddenBuffAction.kt:111-120`. 수정: domesticSuccessProb/domesticFailProb로 통일.
- **P1-083 [backend]** GET /api/my-nation-detail 11/19 필드 — income/budget/history 확장(격리 문서화는 정상 규율, read 갭이 모든 '-'의 원인). `MyController.kt:211-274` + `IdentityDto.kt:528-572`.

### `/game/nation-finance` — 1건+ ⚠truncated
- **P1-084 [backend]** editable 게이트 과소 — legacy editable = officer_level>=5 ∥ permission==4(ambassador). 현재 officerLevel>=5만 → ambassador가 편집 버튼을 못 봄(엔진 게이트는 수락 — 동일 기능 내 판정 분열). legacy는 checkSecretPermission<1이면 페이지 자체 거부('권한이 부족합니다…'/'국가에 소속되어있지 않습…'). `func.php:415-418` vs NationFinanceController. ⚠이 항목 이후 감사 원본 잘림 — 후속 재감사 시 이 페이지 P1/P2 추가 가능.

---

## 4. P2 — 압축 목록 (구조·표기 드리프트, 페이지별 한 줄)

**/game**: ① 섹션 구조 드리프트(GlobalMenu 3회/RecordZone 순서/모바일 GameBottomBar — `PageFront.vue:10-177,613-660` vs `GameChrome.tsx:80-156`) ② SimpleClock+버전 정보 모달 부재(`PartialReservedCommand.vue:16`, `PageFront.vue:172-177,235-249`).

**/auction**: ① 유니크 툴팁이 raw 키(iActionInfo item info 미배출 — `GetConstController.kt:137`) ② TopBackBar 수동 reload 부재(`PageAuction.vue:3-9,51-60`) ③ D2 date 직렬화 통일(의도 명기, 화면 영향 없음 — 하니스에서 비교 제외).

**/betting**: ① 구조 드리프트(제목 '국가 베팅장'/상세 위치/자동선택 — `PageNationBetting.vue:2-24`) ② D5 튜플 vs 객체(주석 문서화, 골든 도입 시 결정 — `BettingDto.kt:50-53`).

**/board**: ① 헤더 [작성자|제목|날짜] 3열 vs 2열+별도 블록(`BoardArticle.vue:3-13` vs `page.tsx:92-115`) ② 댓글 Enter 제출 부재(`page.tsx:139-158`) ③ 날짜 UTC ISO → KST 9시간 어긋남(`page.tsx:46-48`) ④ rate-limit 미포팅(QUARANTINED 문서화, P8 — `BoardActions.kt:19-24`).

**/chief-center**: ① lastExecute/date/mapName/unitSet 응답 필드 누락(`F4Dto.kt:339-365`) ② 하단 수뇌 요약 8칸/턴 거터/SimpleClock 부재(`PageChiefCenter.vue:10-84`) ③ 범위 밖 turn_idx 표시 래핑 없음(DB 정규화 비수행은 정당한 divergence — `ChiefCenterController.kt:43-46`).

**/city**: ① 인구 % 셀 누락 ② 얼굴 64px 초상 대신 파일명 텍스트(CDN base 미결합) ③ 요약표 4행 골격/국가색 헤더 드리프트+추가 배지 ④ 도시 select 4폭 '_' 패딩/select2 미적용 ⑤ 어드민(userGrade 6/7) 오버라이드 미모델(운영툴 갭 — `b_currentCity.php:180-183,278-280`).

**/diplomacy**: ① 카드 행 순서/헤더 문구/초상 20px 드리프트(`t_diplomacy.php:102-161`) ② replaced 기본 숨김+#N 토글 미구현 ③ legacy에 없는 섹션 2개(외교 명령 퀵액션·대상 국가 카드 — 패러티 우선이면 이동/제거) ④ date ISO 원문 렌더(→'YYYY-MM-DD HH:mm:ss').

**/generals**: ① 명성 셀이 세력장수 형태(Lv+honor 병합 — 컬럼 분리 필요) ② 계급 셀 bill 부가(장수일람은 getDed만) ③ isunited ownerName 미구현(백로그 가능) ④ 제목 '전체 장수' vs '장수일람' + 필터/검색은 additive로 기록.

**/global-diplomacy**: ① 맵 라이브/disallowClick/currentCityId dead-code(`MapViewer.tsx:92,175-176,250` — 두 맵뷰어 불변식 하 prop 추가) ② 매트릭스 헤더 vertical-rl 미적용 ③ increaseRefresh('중원정보') 미포팅 ④ 추가 UI 3종(새로고침/SSE/빈 행 — additions, 정책 따라 유지 가능).

**/history**: ① 빈 월 fallback이 ConvertLog 마크업 아님(byte-parity는 writer에서 저장 — `func_history.php:338,379`) ② 지도+국가표 2열 그리드/추가 헤더 2개 ③ 설정 드롭다운(isNationRankingBottom) 부재 ④ mapName이 시나리오 코드로 대체(테마 의미 문서화 필요).

**/inherit**: ① 포인트 목록 hr 구분/상점 2단 구조 드리프트(`PageInheritPoint.vue:23-25,35-121`).

**/join**: ① 섹션 순서/제목('장수 생성')/성공 alert 문구 드리프트(`PageJoin.vue:2,57-122,232-237,427`).

**/mailbox**: ① 4섹션 동시 표시(전체/국가/개인/외교) vs 단일 탭 3종(외교 탭 자체 없음) — 섹션 구성·순서는 legacy 따름(`MessagePanel.vue:1-186,806-833`).

**/map**: ① '{서버명} 현황' 카드 골격/연월 오버레이 드리프트 ② 툴팁 '【지역|등급】이름' 포맷+정적 cityRegions.json 의존 ③ 클릭 차단(v_cachedMap은 disallow — 또는 /api/map fog 게이팅으로 승격 결정) ④ defaultMapCode="che" 하드코딩(타 맵 시나리오 오류)+startYear/theme 필드 부재 ⑤ 고아 페이지(진입 링크 0건).

**/my-boss**: ① 5테이블 섹션 순서/헤더 밴드 색(청/보라/주황/적) 재현 ② executeAllCommand는 데몬 아키텍처상 N/A, increaseRefresh('인사부')는 시스템 포팅 시 일괄.

**/my-cities**: ① 수도 [대괄호] 누락(cyan만 적용 중 — 주의: my-nation/nation은 반대로 대괄호 제거 필요) ② 수치 표기 4건(인구율 후행 0/민심/주민 콤마/시세 '- %') ③ increaseRefresh('세력도시') 백로그.

**/my-generals**: ① 성격/특기 툴팁(getInfo) 소실 ② 구조: 14 vs 15컬럼/POST 폼 vs onChange/백버튼/'총 N명' additive.

**/my-nation**: ① 4행 그룹핑 드리프트(장수/기술력/작위 위치) ② 수도 대괄호 제거(cyan만이 legacy) ③ increaseRefresh('세력정보') 백로그.

**/nation**: ① 버프 순서 발산+randomUnique 필요 포인트 미표시(비용 [0,200,600,1200,2000,3000]은 정확) ② legacy-less 하이브리드 페이지 — /game/my-nation으로 redirect/삭제 + 상점은 /game/inherit로 이관 권장 ③ 수도 대괄호(두 페이지 lockstep 수정).

**/nation-finance**: ⚠ 감사 데이터 잘림으로 P2 미수집 — 재감사 필요.

---

## 5. 권장 수정 웨이브 (foundation-first, 병렬 에이전트용 disjoint 파일 스코프)

원칙(CLAUDE.md): 공유 확장 지점은 **Tier-0 파운데이션 웨이브에서 한 번만 widen**(creator-then-consumer), 페이지 패밀리는 **파일 스코프 disjoint**로 병렬. 같은 파일 co-widen 금지.

### W0 — 파운데이션 (순차 또는 상호-disjoint 소그룹, 페이지 웨이브 선행 필수)

| ID | 산출물 | 파일(공유 — 여기서만 widen) | 소비 페이지 |
|----|--------|------------------------------|-------------|
| W0-1 | FE 와이어/타입 일괄 widen: post() BLOCKED/UNKNOWN 토스트 분기, reservedCommands()/nationPush/nationRepeat/instant-action 헬퍼, betting type 파라미터, ConflictNation.nation·myNationID 리네임, gennum/lastVote/Mail DTO 타입 정합 | `web/game/lib/api.ts`, `web/game/types/game.ts`, `web/game/lib/types.ts` | 전 페이지 (P0-01·04·06·19·33·50, P1 다수) |
| W0-2 | 공유 DTO widen: lastVote/onlineNations/troopInfo(IdentityDto), MyGeneralSummary raw 필드, NationFinanceResponse 중첩 구조, availableTargetGeneral, InheritLog.date, ReservedCommandsResponse 메타 필드 | `app/game-api/.../dto/IdentityDto.kt`, `dto/F4Dto.kt`, `dto/MessageDto.kt`, `dto/AuctionDto.kt`, `dto/BettingDto.kt`, `dto/MapPreviewDto.kt` | main/auction/betting/diplomacy/inherit/mailbox/my-*/nation-finance |
| W0-3 | 권한 파운데이션: logic `SecretPermission.check`(meta ambassador/auditor 포함)를 game-api read 공용 헬퍼로 노출, secretPermission(0..4) 응답 동봉 규약 | `logic/.../intake/SecretPermission.kt`(읽기 재사용), game-api 공용 헬퍼 신설 | diplomacy(P0-15)/mailbox(P0-34·35)/board(P0-08)/chief-center(P1-023)/nation-finance(P1-084) |
| W0-4 | intake 결과 회신 채널: requestId 결과 조회 또는 SSE deny/result — CommandController는 여기서만 widen | `app/game-api/.../web/CommandController.kt` (+SSE 인프라) | auction(P0-04)/betting(P0-06)/diplomacy 파기(P1-030)/mailbox |
| W0-5 | log_entry read 파운데이션: NationLogReadRepository(scope=NATION) + 글로벌/개인/카테고리 피드 read 공용화 | game-api read 패키지(신규 파일 — `WorldLogReadRepository.kt` 패턴 미러) | main RecordZone(P0-03)/history(P0-21)/my-nation 국가열전(P0-49)/auction recentLogs(P1-009)/map history(P1-059) |
| W0-6 | MapViewer prop widen: mapData/disallowClick/currentCityId/live·showMe — **두 맵뷰어 불변식**: `web/gateway` MapPreview 동시 수정 + 양쪽 tsc | `web/game/components/game/MapViewer.tsx` + gateway MapPreview | map(P0-36 FE측·P1-061·062)/history(P0-22)/global-diplomacy(P2)/main(P1-003) |
| W0-7 | wire 계약 widen: diploRespondLetter, MakeGeneral inherit 4필드+picture, 임명/추방/도시임명/set-permission intake 코드 | `common/.../wire/TurnDaemonCommand.kt`, `app/game-api/.../reserve/CommandWireMapper.kt` | diplomacy(P0-16)/join(P0-29·30)/my-boss(P0-39~42)/my-cities(P0-47) |
| W0-8 | infra 공유: Flyway 버전 번호 사전 할당(city.state, board.author_icon, yearbook global_* jsonb, inheritance date) + `JdbcFlushExecutor.kt` 일괄 widen(city.state flush + ng_betting upsert) | `infra/.../persistence/JdbcFlushExecutor.kt`, `infra/src/main/resources/db/migration/V*` | map(P0-36)/betting(P0-07)/board(P1-017)/history(P0-20) |

### W1 — 페이지 패밀리 병렬 웨이브 (W0 완료 후, 파일 스코프 disjoint)

| 에이전트 | 페이지 | 전용 파일 스코프 | 주요 항목 |
|----------|--------|------------------|-----------|
| A | `/game` 메인 | `FrontInfoController.kt`, `ReservedCommandsController.kt`, `GameChrome/PartialReservedCommand/MyInfoLogPanel/GameInfo/GeneralBasicCard/NationBasicCard.tsx` | P0-01~03, P1-001~007 |
| B | auction | `AuctionController.kt`, `components/auction/*` | P0-04, P1-008~012 |
| C | betting (+아레나 신설) | `BettingController.kt`, engine `PlaceBetHandler.kt`, betting 페이지, `control-bar-config.ts`, `global-menu-fixture.ts` | P0-05~07, P1-013~016 |
| D | board | `BoardController.kt`, engine BoardHandler, board page | P0-08, P1-017 |
| E | chief-center | `ChiefCenterController.kt`, chief page + `ChiefCommandReserve.tsx` | P0-09~11, P1-018~023 |
| F | city | `CityDetailController.kt`, GeneralReadEntity(defence_train), city page | P0-12~14, P1-024~027 |
| G | diplomacy + global-diplomacy (**DiplomacyController.kt 공유 → 단일 에이전트**) | `DiplomacyController.kt`, engine `DiplomacyLetterHandler.kt`, 두 페이지 | P0-15~17·19, P1-028~032·038·039 |
| H | generals | `GeneralsController.kt`, generals page | P0-18, P1-033~037 |
| I | history | `HistoryController.kt`, engine `DaemonLoopConfig.kt`(LogHistory writer) | P0-20~22, P1-040~042 |
| J | inherit | `InheritPointController.kt`, `InstantActionController.kt`, inherit page | P0-23~28, P1-043~045 |
| K | join | `JoinController.kt`, engine `MakeGeneralHandler.kt`, logic `MakeGeneral.kt`, join page, scout-list 컨트롤러 신설 | P0-29~31, P1-046~053 |
| L | mailbox | `MailboxController.kt`, `DiplomaticMessageController.kt`, `ContactController.kt`, send 컨트롤러 신설, mailbox page | P0-32~35, P1-054~058 |
| M | map | `MapPreviewController.kt`, `WorldMapController.kt`, `CityReadRepository.kt`, map page | P0-36, P1-059~062 |
| N | my-* 5종 (**MyController.kt 공유 → 단일 에이전트 또는 내부 순차**) — my-boss/my-cities/my-generals/my-nation + nation(redirect 권장) | `MyController.kt`, BossInfoController 신설, engine 임명/추방 핸들러, `ScenarioImporter.kt`(rate/bill/gennum 시드), 5개 페이지 | P0-37~50, P1-063~083 |
| O | nation-finance | `NationFinanceController.kt`, NationEnvReadRepository 신설, nation-finance page | P0-51~54, P1-084 + **재감사(truncated)** |

### 공유 파일 충돌 경고 (co-widen 금지 — W0에서 선행 widen)

- `MyController.kt` — my-boss/my-cities/my-generals/my-nation/nation 5페이지가 동일 파일 → **에이전트 N 단일 소유**.
- `DiplomacyController.kt` — diplomacy + global-diplomacy → **에이전트 G 단일 소유**.
- `CommandController.kt` — W0-4(결과 회신)와 chief-center가 겹침 → W0에서 widen 완료 후 E는 소비만.
- `api.ts`/`types/game.ts`/`IdentityDto.kt`/`F4Dto.kt` — 사실상 전 에이전트 접점 → **W0-1/W0-2에서 일괄 widen, W1에서는 읽기 전용**.
- `MapViewer.tsx`(+gateway MapPreview) — A/G/I/M 4곳 접점 → W0-6 단일 widen, 이후 props 소비만. 두 맵뷰어 불변식 준수.
- `JdbcFlushExecutor.kt` + Flyway `V*` — C(betting upsert)·M(city.state)·D(author_icon)·I(yearbook jsonb) 접점 → W0-8에서 마이그레이션 번호 사전 할당+flush 일괄 widen.
- `TurnDaemonCommand.kt`/`CommandWireMapper.kt` — G/K/N 접점 → W0-7 단일 widen, 엔진 핸들러 구현은 각 에이전트(엔진 파일은 disjoint).

### 우선순위 권고

1. **W0 전체** (모든 P0 차단 해제의 전제).
2. **보안·오염 P0 우선**: P0-08(회의실 누출), P0-18(crew 누설), P0-34/35(외교 마스킹·권한 상승), P0-07(베팅 자원 오염), P0-53(라운드트립 불능).
3. **크래시·전면 불능**: P0-51(nation-finance 크래시), P0-19(global-diplomacy 붕괴), P0-12(city 진입 404), P0-09(chief wrong-ring), P0-15/16(외교 조약 불가).
4. **위조 표시 제거**: P0-01/03(메인), P0-04/06(성공 토스트), P0-13/14(city 수치), P0-36(map state), P0-38(my-boss), P0-22(history 지도).
5. P1 → P2는 페이지 에이전트 내에서 P0 마감 후 같은 파일 스코프로 연속 처리.

### 후속 조치
- `/game/nation-finance` 감사 데이터 잘림 — P1 5번째 finding 이후(잔여 P1/P2) **재감사 필요**.
- 의도적 divergence로 기록 유지할 항목: board rate-limit(P8 격리), chief turn_idx DB 정규화(one-daemon-write), auction D2 date 통일, generals permitAll(주석→결정 문서 승격 필요), history 교차 서버(OQ-8 재결정 필요), betting D5 튜플 직렬화.
