# MASTER GAP — opensamguk 전수 패러티 감사 (마스터 문서)

> **생성**: 2026-06-07 · **HEAD**: `b58f99a` · **출처**: `_full_audit_2026-06-07.raw.json` (5.1MB, 2단계 전수 감사 raw)
> **원칙**: PHP `legacy/devsam-core` = grand truth. 모든 갭은 file:line 근거. 날조 금지 — 본 문서는 raw의 합성이며, 단위별 상세 fixSpec/근거는 raw에 보존된다.

---

## 1. 요약

| 지표 | 수 |
|------|----|
| 전체 단위(total) | **768** |
| 비교 완료(compared) | **457** |
| 미포팅(missingPort) | **202** |
| 부분포팅(partialPort) | **100** |
| 현재만(currentOnly) | **72** |

비교된 457건의 평균 충실도(fidelity)는 **62/100**, 분포는 `0–29: 80건` · `30–49: 54건` · `50–69: 92건` · `70–89: 135건` · `90–100: 96건`. 비교 단위에 걸친 갭 항목은 high-severity **1,013개**, blocked(상위 의존으로 즉시 수정 불가) **294개**, parityViolations(명시적 패러티 위반) **1,026건**.

**신뢰도 한계(반드시 인지).** 본 전수 감사는 2단계로 총 482개 에이전트가 분담 수행했고, 그중 **13개 에이전트가 실패**하여 약 **311개 단위가 미비교** 상태로 남아 있다(768 − 457 = 311). 즉 본 문서의 "비교(comparisons) 457건"은 감사가 완료된 범위만을 반영하며, 미비교 311건의 충실도는 현재 알 수 없다. 미포팅/부분포팅/현재만 카운트(202/100/72 = 374)는 인벤토리 단계의 분류 결과로 비교 단계와 독립적이다. 따라서 "전수"는 인벤토리 기준이며, 충실도 게이트는 **부분 커버리지**임을 전제로 읽어야 한다.

기존 갭 문서(`API_GAP.md`, `FE_OUTPUT_*`, `LOGIC_GAP.md`, `READ_DTO_GAP.md`, `PARITY_RECONCILED.md`)와 `GAP_AUDIT.md`(W0–W9 웨이브 플랜)는 본 감사 이전의 영역별 1차 감사이며, 본 문서가 그 위에 얹히는 전수 합성본이다.

---

## 2. 미포팅 (missing 202)

현재 대응 코드가 전혀 없는 단위. kind별 분포: `php-ajax 41 · vue-component 39 · command 29 · domain 24 · fe-ts 17 · event 12 · other 12 · admin 10 · intake-api 6 · php-page 5 · read-api 5 · vue-page 2`.

### 2.1 command (29) — W7 che_계략 패밀리 + misc
대부분 ItemHooks.kt의 전투-계략 버프 **설명 텍스트**로만 존재하고 예약가능 커맨드로는 미포팅. CommandRegistry 등록 자체가 없음.

- `che_화계`: 화계(화공) 커맨드. ItemHooks.kt 계략 시스템 참조만, 예약 커맨드 별개.
- `che_파괴`: 시설 파괴. ItemHooks.kt 버프 설명 텍스트만.
- `che_탈취`: 자원 탈취. ItemHooks.kt 버프 설명 텍스트만.
- `che_선동`: 적 도시 선동. ItemHooks.kt 버프 설명 텍스트만.
- `che_첩보`: 첩보 활동. 어떤 참조도 없음.
- `che_단련`: 병사 없는 단련. traits/ActionPersonality.kt AI 키워드만.
- `che_강행`: 강행군(무리한 이동). 어떤 참조도 없음.
- `che_접경귀환`: che_귀환(CheGwihwan.kt)과 별개 커맨드. CommandRegistry 미등록.
- `che_숙련전환`: 숙련도 전환. 어떤 참조도 없음.
- `che_전투태세`: 전투 태세 전환. 어떤 참조도 없음.
- `che_모반시도`: 모반 시도. WAVE_7.md에 CheMobanSido.kt 신규 계획만 있음.
- `che_전투특기초기화`: InheritResets.kt(상속포인트 reset)는 별개 서브시스템. 미포팅.
- `che_내정특기초기화`: 전투특기초기화와 같은 서브시스템 미포팅.
- `che_등용수락`: non-reservable accept-trigger. P6 deferred(CheDeungyong.kt 주석에만 언급).
- `cr_인구이동`: 인구 이동 cr(국가) 커맨드. CommandRegistry 미등록.

> (인벤토리에는 위 항목이 `Command/General/...` prefix 중복 형태로도 잡혀 총 29개로 집계됨 — 동일 커맨드의 두 표기.)

### 2.2 admin (10) — 어드민 tool 전부 stub
어드민 화면/엔드포인트가 전부 미구현(현 admin/page.tsx는 placeholder 탭만).

- `_119_b.php`: 시간조정 submit(_119 form POST target). 엔드포인트 미구현.
- `_admin1.php` / `_admin1_submit.php`: 게임관리(grade≥4) — 운영자메세지·시작시간·최대장수/국가·시작년도·턴타임. 탭 stub.
- `_admin2.php` / `_admin2_submit.php`: 회원관리(grade≥6) — 아이템 지급/공지. 탭 stub.
- `_admin5.php` / `_admin5_submit.php`: 일제정보(grade≥6) — 장수·국가 일제 조회/정렬, 국가 설정 submit. 미구현.
- `_admin7.php`: 로그정보(grade≥6) — 장수 행동/전투 로그. 미구현.
- `_admin8.php`: 외교정보(grade≥6) — 외교 관계 전체 조회. 미구현.
- `_admin_force_rehall.php`: 강제 명예의 전당 등록(grade≥6, isunited 후). 미구현.

### 2.3 event (12) — 이민족/NPC 발생 이벤트
WorldActions.register 미등록. 이민족 시나리오 핵심(게임 후반부).

- `RaiseInvader`: 이민족 대규모 침입(수도 이전 + NPC 국가/장수 생성 + isunited=1, AutoDeleteInvader/InvaderEnding row 삽입).
- `InvaderEnding`: 이민족 엔딩(isunited=3, 엔딩 로그, event self-delete).
- `AutoDeleteInvader`: 침략자 자동 삭제.
- `RaiseNPCNation`: 공백지 NPC 국가 자동 생성(거리 필터 + pickGeneralFromPool).
- `RegNPC` / `RegNeutralNPC`: 시나리오 배치 NPC 등록(중립=npcType 6).
- `CreateManyNPC`: pickGeneralFromPool 기반 대규모 NPC 생성.
- `CreateAdminNPC`: PHP 본체가 'NYI' 스텁(포팅 불필요 수준).
- `BlockScoutAction` / `UnblockScoutAction`: 정찰 봉인/해제(이민족 시나리오 전용).
- `ChangeCity`: 도시 속성 변경(시나리오 초기화용).
- `LostUniqueItem`: 유니크 아이템 확률 분실(DRBG 시드, npc≤1 대상, 종별 lostProb).

> `other` kind 12개는 위 event 항목의 중복 표기(동일 Event/Action 단위).

### 2.4 domain (24) — 풀 추상화 + 트리거 + 시나리오 빌더 + DTO
- 장수 풀: `AbsGeneralPool/AbsFromUserPool`(추상 기반), `GeneralPool/RandomNameGeneral`. MakeGeneral.kt은 RNG draw만 담당, 풀 추상화 미포팅.
- 서버 인프라(logic/common에 없음): `ServerEnv`, `ServerTool`, `GlobalMenu`(빌더), `ResetHelper`, `VersionGitDynamic`(BuildInfo.kt로 대체 가능성).
- 트리거: `GeneralTriggerCaller`, `BaseGeneralTrigger`, `GeneralTrigger/{che_도시치료, che_병력군량소모, che_부상경감, che_아이템치료}`.
- 제약: `Constraint/AdhocCallback`(콜백 동적 제약), `Constraint/ExistsAllowJoinNation`. Presets.kt에 없음.
- 시나리오 빌더(DB INSERT): `Scenario/GeneralBuilder`(owner/nation/city/items 할당), `Scenario/Nation`, `ActionScenarioEffect`(3종). MakeGeneral.kt은 draw만.
- 메시지: `ScoutMessage`, `RaiseInvaderMessage`.
- DTO/텍스트: `DTO/{MenuItem, MenuLine, MenuMulti, MenuSplit, SelectItem}`, `DTO/{VoteInfo, VoteComment}`, `TextDecoration/{DyingMessage, SightseeingMessage}`.

### 2.5 read-api (5)
- `Nation/GetGeneralLog` (+ alias `General/GetGeneralLog`): 장수 행동 로그(ng_general_turn_log) 조회. WorldLogController는 world_log만 다룸.
- `Global/ExecuteEngine`: NO_SESSION 공개 엔진 상태 조회. HealthCheckController는 /health만.
- `Global/GeneralListWithToken`: GeneralList + select_npc_token 병합 응답. 토큰 필드 누락.
- `InheritAction/GetMoreLog`: 유산 로그 페이지네이션(lastID 기반). 최근 30건만 제공, getMore 없음.

### 2.6 intake-api (6)
- `General/DieOnPrestart`, `General/DropItem`, `General/InstantRetreat`: TurnDaemonCommand 정의는 common에 있으나 CommandWireMapper intakeCodes 미등록 + 디스패처 미바인딩 → 엔드포인트 없음.
- `InheritAction/CheckOwner`: 유산포인트로 NPC 소유권 이전. cost read만, write 액션 없음.
- `InheritAction/ResetStat`: 유산포인트로 스탯 재분배. TurnDaemonCommand 정의 자체 없음.
- `Misc/UploadImage`: 장수/국가 이미지 업로드. 엔드포인트 없음.

### 2.7 php-page (5) · vue-page (2)
- php-page: `admin1-game-env-ui`, `admin2-ingame-member-ui`, `admin5-nation-stats-ui`, `admin7-general-log-ui`, `admin8-diplomacy-ui` — 모두 어드민 UI(§2.2와 짝).
- vue-page: `PageBattleCenter.vue`(전투 중 현황), `v_battleCenter.php`(감찰부 = 장수 행동 로그 뷰어, 현재 coming-soon 리다이렉트).

### 2.8 vue-component (39) — 대부분 명령 인자 폼 + 거래/베팅/게시판 UI
**거래/베팅/게시판**: `AuctionResource`, `AuctionUniqueItem`, `BettingDetail`, `BoardArticle`, `BoardComment`, `TipTap`(리치 에디터).
**공통 위젯**: `BottomBar`, `TopBackBar`, `SimpleClock`, `SimpleNationList`, `GeneralList`(AgGrid), `GeneralLiteCard`, `GeneralSupplementCard`, `DragSelect`, `AutorunInfo`, `gridCellRenderer/{GridTooltipCell, SimpleTooltipCell}`(ag-grid 미사용으로 대응 없음).
**processing 서브폼**(CommandModal에 대응 sub-form 없음): `CitiesBasedOnDistance`, `CrewTypeItem`, `ProcessGeneralAmount`, `SelectColor` + 명령별 폼 — `che_건국`/`che_무작위건국`/`cr_건국`(국명+색상+성향), `che_군량매매`, `che_등용`/`che_선양`/`che_장수대상임관`(서신/장수선택), `che_숙련전환`, `che_임관`(임관권유문 목록), `che_장비매매`, `che_징병`, `che_헌납`(금/쌀 토글), `Nation/{che_국기변경, che_국호변경, che_물자원조, che_발령, che_피장파장, cr_인구이동}`.

### 2.9 php-ajax (41) — write/submit 인테이크 + 어드민 + 외교 서신 + 선택풀
**외교 서신 write**(game-api 인테이크 없음): `j_diplomacy_send_letter`, `j_diplomacy_destroy_letter`, `j_diplomacy_rollback_letter`.
**게시판/투표 write**: `j_board_article_add`, `j_board_comment_add`(BoardController는 read-only).
**개인 설정 write**: `j_set_my_setting`(tnmt/defence_train/use_treatment/use_auto_nation_turn), `j_vacation`(killturn×3), `j_myBossInfo`(인사부 임관/해임/전출/배속), `j_general_set_permission`(ambassador/auditor 일괄 UPDATE), `j_adjust_icon`(picture→general 동기화).
**선택풀/NPC**: `j_get_select_pool`, `j_get_select_npc_token`, `j_select_npc`, `j_select_picked_general`, `j_update_picked_general`(일부 CommandWireMapper wire는 있으나 HTTP 엔드포인트 없음).
**NPC 정책**: `j_set_npc_control`(NpcPolicyController는 GET만, POST 저장 없음).
**시뮬레이터**: `j_export_simulator_object`, `j_get_basic_general_list`은 별도(아래 §3 비교에 등장).
**어드민/루트DB**: `j_install`, `j_load_scenarios`, `j_raise_event`(grade≥6 이벤트 강제), `j_autoreset`(cron buildScenario), `admin1-submit-game-env`, `admin2-submit-ingame-member`, `admin5-submit-nation-change`, `admin-force-rehall`, `j-get-userlist`/`j-set-userlist`(루트DB 유저), `api-admin-ban-email`, `j-server-get-status`/`j-server-change-status`(서버 개폐), `j-basic-info`, `j_basic_info`(서버 오픈 예약 체크), `j_general_log_old`(장수 로그 read).

### 2.10 fe-ts (17) — 표시 포매터 + 선택풀 UI
**선택풀/처리화면**: `select_general_from_pool.ts`(289줄), `select_npc.ts`(436줄), `battleCenter.ts`, `v_processing.ts`(턴 처리 진행, 115줄), `helpTexts.ts`(NPC 정책 툴팁).
**utilGame 포매터(전부 미포팅)**: `formatLog`, `techLevel`, `tournament`, `calcInjury`, `formatInjury`, `formatDefenceTrain`, `formatGeneralTypeCall`, `formatHonor`, `formatDexLevel`, `formatCityName`, `formatRefreshScore`, `nextExpLevelRemain`.

---

## 3. 부분포팅 — 저충실도 순위표

`comparisons` 457건을 fidelity 오름차순 정렬. **fidelity < 50 = 우선 보강 대상.** 컬럼: `fid|kind|unit|gaps|high|blocked|요지`. **blocked gap은 상위(주로 백엔드 DTO/엔드포인트 미구현)에 막혀 FE만으로는 수정 불가** — blocked 수가 gap 수에 근접하면 FE 작업 전 BE 선결이 필요하다는 신호다.

### 3.1 충실도 0–49 (134건 — 전체 우선 보강 대상)

| fid | kind | unit | g | high | blk | 요지 |
|----:|------|------|--:|-----:|----:|------|
| 0 | fe-ts | extPluginTroop.ts | 8 | 3 | 0 | 정본 unit 미스매치: legacy extPluginTroop.ts = 부대편성이 아니라 암행부(b_genList) |
| 0 | php-ajax | proc.php ↔ CommandController.kt | 2 | 1 | 0 | 페어 자체가 미스매치(턴실행 vs 명령예약) |
| 0 | php-ajax | j_autoreset.php (예약오픈/리셋 cron) | 6 | 4 | 0 | 단위 매핑 부정확, cron 경로 미구현 |
| 0 | php-ajax | j_get_select_pool (장수선택풀 READ) | 9 | 8 | 1 | READ 엔드포인트 + FE 카드 렌더 전면 미구현 |
| 0 | intake-api | Message/ReadLatestMessage | 4 | 0 | 0 | latestRead KV 마킹 미구현 |
| 0 | event | StaticEvent/event_부대탑승즉시이동 | 4 | 3 | 0 | 전면 미포팅 |
| 3 | php-ajax | j_simulate_battle.php (시뮬레이터 BE) | 9 | 6 | 5 | SimulatorController = placeholder stub |
| 3 | fe-ts | ts-admin-member | 23 | 6 | 17 | 회원관리 화면 — 대부분 BLOCKED(BE 미구현) |
| 4 | fe-ts | bossInfo.ts → my-boss | 9 | 9 | 8 | 인사부 — 거의 전부 BLOCKED |
| 5 | fe-ts | utilGame/getNewMsgToast.ts | 9 | 6 | 1 | 새 메시지 토스트 팩토리 미포팅 |
| 5 | php-ajax | process_war.php (FE read) | 7 | 5 | 0 | impl 포인터 오류(FE 화면 아님) |
| 5 | admin | _119.php (마스터 관리자 패널) | 5 | 4 | 0 | 4기능 행 전부 미구현 |
| 5 | php-ajax | j_simulate_battle (입력폼+BE) | 15 | 6 | 1 | 시뮬레이터 전면 미구현 |
| 5 | read-api | Global/GetNationList | 6 | 3 | 1 | 사실상 미구현(~5%) |
| 5 | event | StaticEvent/event_부대발령즉시집합 | 5 | 5 | 0 | 부대원 일괄 이동/집합 미포팅 |
| 6 | php-page | battle_simulator.php | 15 | 9 | 2 | 수동 입력 시뮬레이터 페이지 미구현 |
| 8 | fe-ts | battle_simulator.ts | 12 | 7 | 6 | ~8%, 사실상 미이식 |
| 8 | fe-ts | myPage.ts | 21 | 10 | 2 | "내 정보" 컨트롤러 미이식 |
| 8 | fe-ts | extKingdoms.ts | 12 | 4 | 6 | nation/page.tsx는 세력일람 정본 아님 |
| 8 | fe-ts | betting.ts | 9 | 4 | 0 | 토너먼트 베팅 제출 핸들러 미이식 |
| 8 | fe-ts | bestGeneral.ts | 36 | 19 | 21 | 명장일람 — 대량 BLOCKED |
| 8 | php-page | a_bestGeneral.php | 9 | 4 | 2 | 페이지 정체성 자체 상이 |
| 8 | php-page | a_emperior_detail.php | 14 | 11 | 0 | 왕조일람 상세(emperior 테이블) 미구현 |
| 8 | php-page | b_genList.php | 12 | 6 | 1 | 암행부 — 정본 매핑 오류 + 전면 누락 |
| 8 | php-page | b_myBossInfo.php | 10 | 6 | 2 | 인사부 6섹션 미구현 |
| 8 | vue-page | v_join.php | 14 | 8 | 4 | 장수생성 폼(PageJoin) 미이식 |
| 8 | read-api | Nation/GetNationInfo | 8 | 6 | 1 | finance가 아니라 국가정보 read(정본 매핑오류) |
| 10 | vue-component | NumberInputWithInfo | 11 | 4 | 0 | 페어링 불일치 |
| 10 | vue-page | PageJoin.vue → CharacterClaim.tsx | 9 | 7 | 9 | 패러티 포트 아님 — 전부 BLOCKED |
| 10 | php-page | b_betting.php | 9 | 7 | 1 | 16강 베팅장 미구현 |
| 10 | read-api | Betting/GetBettingList | 8 | 6 | 1 | 목록 응답 형상 불일치 |
| 10 | read-api | Global/GetRecentRecord (3-피드) | 4 | 3 | 0 | 메인 3섹션 사실상 미구현 |
| 12 | vue-page | PageNationBetting.vue → betting | 9 | 6 | 1 | 날조 데이터, 정본과 무관 |
| 12 | vue-component | PartialReservedCommand.vue | 8 | 5 | 1 | 거의 미구현(read 엔드포인트 부재) |
| 12 | fe-ts | hallOfFame.ts → hall-of-fame | 8 | 4 | 0 | 명예의 전당 미이식 |
| 12 | php-page | a_hallOfFame.php | 10 | 5 | 0 | ~12%, hall 테이블 미연동 |
| 12 | php-page | c_tournament.php | 10 | 5 | 1 | 토너먼트 POST 액션 컨트롤러 미구현 |
| 12 | vue-page | v_nationBetting.php | 13 | 9 | 0 | 베팅 UI 정본 미이식 |
| 12 | php-ajax | j_myBossInfo (인사부 FE/read) | 8 | 3 | 1 | 임명/추방 + 전체화면 미구현 |
| 12 | php-ajax | j_select_picked_general | 5 | 2 | 1 | 선택풀→신규장수 생성 submit 미구현 |
| 15 | php-page | a_kingdomList.php | 12 | 7 | 0 | 세력일람 ROSTER 미구현 |
| 15 | php-page | a_traffic.php | 6 | 3 | 3 | 트래픽정보 3섹션 불일치 |
| 15 | php-ajax | j_diplomacy_respond_letter.php | 2 | 2 | 0 | 미포팅 |
| 15 | read-api | Auction/GetUniqueItemAuctionDetail | 11 | 6 | 0 | 대응 엔드포인트 없음 |
| 15 | read-api | Auction/GetUniqueItemAuctionList | 12 | 3 | 0 | 대응 엔드포인트 없음 |
| 15 | read-api | Global/GetCurrentHistory (연감) | 9 | 4 | 0 | 형상 거의 전면 불일치 |
| 15 | fe-ts | ts-admin-server | 7 | 4 | 5 | 어드민 서버운영 — 대부분 BLOCKED |
| 18 | vue-page | v_nationGeneral.php | 20 | 6 | 2 | 세력장수(GeneralList ag-grid) 미이식 |
| 18 | php-page | select_general_from_pool.php | 12 | 4 | 6 | 장수선택/생성 페이지 미구현 |
| 18 | domain | Scenario (월드 부트스트랩) | 8 | 5 | 0 | Scenario/Nation/GeneralBuilder 도메인 미포팅 |
| 20 | vue-component | GameBottomBar | 6 | 2 | 0 | 모바일 드롭업 바 미구현 |
| 20 | php-page | b_myPage.php | 32 | 5 | 11 | "내정보&설정" 사실상 미이식(18번 버튼이 /game로) |
| 20 | php-ajax | j_get_basic_general_list | 6 | 3 | 0 | 시뮬레이터 장수 picker 미구현 |
| 20 | php-ajax | j_get_select_npc_token | 11 | 5 | 1 | 빙의 토큰 발급/표시 미구현 |
| 20 | php-ajax | j_update_picked_general | 8 | 4 | 0 | 빙의 변경 미구현 |
| 20 | event | Event/Action/OpenNationBetting | 8 | 5 | 0 | 등록은 됐으나 관측효과 미구현 |
| 22 | vue-component | PageAuction.vue | 15 | 10 | 5 | 경매 화면(자원+유니크) 미이식 |
| 22 | fe-ts | msg.ts → mailbox | 12 | 4 | 1 | 메일함 골격만 |
| 22 | php-page | b_currentCity.php | 9 | 3 | 8 | 5섹션 중 헤더만 — 대부분 BLOCKED |
| 22 | php-page | b_myCityInfo.php | 17 | 6 | 0 | 세력도시 카드(도시당 21필드) 미구현 |
| 22 | php-page | b_myKingdomInfo.php | 14 | 6 | 1 | 세력정보 19필드 표 미구현 |
| 22 | vue-page | v_auction.php (경매장) | 15 | 6 | 6 | 금/쌀↔유니크 토글 화면 미이식 |
| 22 | other | Event/Action/OpenNationBetting | 14 | 13 | 0 | 구조적 스텁/재해석본 |
| 25 | fe-ts | GameConstStore.ts | 8 | 2 | 1 | const 캐시 형상 불일치 |
| 25 | vue-component | processing/Nation/che_불가침제의 | 6 | 2 | 1 | 3-인자 전용 폼 미구현 |
| 25 | php-page | a_emperior.php (역대왕조) | 10 | 5 | 4 | 왕조 카드 레이아웃 미구현 |
| 25 | php-page | b_myGenInfo.php | 13 | 8 | 1 | 세력장수 15컬럼/15정렬 미구현 |
| 25 | php-ajax | j_diplomacy_respond_letter | 8 | 4 | 0 | 서신 승인/거부 액션 미구현 |
| 25 | intake-api | General/BuildNationCandidate | 3 | 2 | 0 | 핸들러 미구현 |
| 25 | read-api | Global/GetHistory (연감) | 9 | 5 | 1 | staticVal 범위메타 불일치 |
| 25 | read-api | Message/GetRecentMessage | 11 | 6 | 2 | 통합 read 계약 미충족 |
| 25 | read-api | Message/GetOldMessage | 13 | 8 | 0 | 구조·필드·동작 분기 |
| 25 | php-ajax | j-server-get-admin-status | 10 | 4 | 0 | 서버관리 데이터 피드 미구현 |
| 25 | other | comp-bottom-nav | 6 | 2 | 0 | 두 legacy 정본 어느 쪽 포트도 아님 |
| 28 | vue-component | MessagePanel | 13 | 5 | 1 | read-only 열화 포트 |
| 28 | vue-component | MessagePlate | 10 | 3 | 1 | msgType 4분기 레이아웃 미구현 |
| 28 | vue-page | PageNationGeneral.vue | 19 | 5 | 1 | GeneralList ag-grid(~40컬럼) 미이식 |
| 28 | vue-page | v_processing.php | 12 | 5 | 10 | 명령별 풀페이지 앱 — 대부분 BLOCKED |
| 28 | php-page | select_npc.php | 13 | 5 | 5 | NPC빙의 선택 화면 미구현 |
| 28 | php-ajax | j_set_npc_control (NPC 정책 read) | 11 | 6 | 0 | 23필드 정책폼 미이식 |
| 30 | fe-ts | currentCity.ts | 9 | 3 | 0 | 도시 셀렉터+도시정보 화면 미이식 |
| 30 | php-page | a_genList.php (장수일람) | 17 | 10 | 2 | 컬럼/변환/정렬 광범위 불일치 |
| 30 | read-api | Vote/GetVoteList | 6 | 4 | 0 | DTO 데이터 패러티 깨짐 |
| 35 | vue-component | MapCityDetail | 5 | 2 | 1 | 정본 페어 상이(맵 마커 vs 상세) |
| 35 | vue-page | PageHistory.vue (연감) | 5 | 4 | 1 | 9요소 중 2개만, 지도 스냅샷 누락 |
| 35 | fe-ts | common_legacy.ts → format.ts | 4 | 2 | 0 | 정본 페어 상이(포매터 모듈) |
| 35 | php-page | a_npcList.php (빙의일람) | 11 | 3 | 3 | 12컬럼 갤러리 미구현 |
| 35 | vue-page | v_history.php | 8 | 6 | 1 | 2섹션만, 동작 불완전 |
| 35 | php-ajax | j_diplomacy_destroy_letter.php | 6 | 4 | 0 | 파기(상호동의) 버튼 백엔드 미구현 |
| 35 | php-ajax | j_diplomacy_rollback_letter.php | 4 | 1 | 0 | proposed 회수 미구현 |
| 35 | php-ajax | j_get_basic_general_list.php | 7 | 3 | 0 | 시뮬레이터 picker 데이터 미구현 |
| 35 | php-ajax | j_diplomacy_get_letter → letters | 8 | 4 | 0 | 서신 피드 envelope 불일치 |
| 35 | read-api | Auction/GetActiveResourceAuctionList | 13 | 5 | 1 | 자원경매 목록 형상 불일치 |
| 35 | read-api | Betting/GetBettingDetail | 8 | 5 | 0 | ~35%, 평탄 envelope 불일치 |
| 35 | intake-api | Betting/Bet (placeBet) | 8 | 7 | 0 | 체인은 배선됐으나 데이터 갭 |
| 35 | read-api | Command/GetReservedCommand (링패널) | 11 | 5 | 1 | 예약명령 링 심각 미달 |
| 35 | intake-api | General/Join (장수 직접생성) | 6 | 4 | 0 | create-general INSERT 경로 미구현 |
| 35 | command | che_견문 | 2 | 2 | 0 | 등록은 OK, run() 스텁 + 골든 없음 |
| 35 | command | che_해산 | 2 | 2 | 0 | run() 핵심(deleteNation cascade) 스텁 |
| 35 | command | che_인재탐색 | 3 | 3 | 0 | run()/resolve() 스텁(NPC-pool scouting) |
| 35 | command | che_종전제의 | 6 | 4 | 0 | run() 스텁급 |
| 35 | command | che_불가침제의 | 5 | 2 | 0 | run() 미완 |
| 35 | command | che_불가침수락 | 7 | 2 | 0 | 부분 포팅 + 미배선 |
| 35 | command | che_불가침파기제의 | 6 | 2 | 0 | run() 미완 |
| 35 | command | che_불가침파기수락 | 5 | 3 | 0 | instant action 재설계, 미완 |
| 35 | domain | InheritancePointManager | 7 | 5 | 0 | 두 병렬 구현 분기 |
| 35 | domain | UserLogger | 5 | 2 | 0 | 로깅 분기 |
| 35 | domain | Constraint/DisallowDiplomacyStatus | 3 | 2 | 0 | 등록·배선 OK, 효과 미검증 |
| 35 | domain | ActionCrewType | 3 | 2 | 0 | che_성벽선제 핵심효과 미포팅 |
| 35 | domain | UniqueItemLottery | 8 | 6 | 0 | 의도 축소 seam(NPC≥2 단락) |
| 35 | php-ajax | j-update-server | 9 | 2 | 0 | 서버관리 FE 패널 미구현 |
| 38 | vue-component | ChiefReservedCommand | 5 | 2 | 2 | 사령부 예약 미이식 |
| 38 | vue-page | PageTroop.vue (부대편성) | 11 | 4 | 0 | Nation.GeneralList 계약 불일치 |
| 38 | fe-ts | diplomacy.ts | 10 | 3 | 5 | 외교서신 CRUD 화면 미이식 |
| 38 | php-page | t_diplomacy.php (외교부) | 8 | 4 | 0 | FE 타입↔응답 불일치 |
| 38 | php-ajax | j_diplomacy_get_letter.php → letters | 11 | 6 | 2 | 3대 블록 패러티 깨짐 |
| 40 | vue-component | processing/ProcessGeneral | 8 | 2 | 1 | 장수선택 필드 대폭 축약 |
| 42 | domain | Message (도메인) | 5 | 2 | 0 | 라우팅/외교 accept만, 발신 경로 부족 |
| 45 | vue-component | MapCityBasic | 10 | 4 | 0 | 인게임 데이터 소스 불일치 |
| 45 | vue-component | MapViewer | 9 | 2 | 1 | fog 인게임 월드맵 부분 |
| 45 | vue-page | PageNPCControl.vue | 7 | 3 | 0 | NPC 정책 화면 부분 |
| 45 | fe-ts | history.ts → history | 9 | 5 | 0 | 연감 표시 정본 미이식 |
| 45 | vue-component | processing/ProcessNation | 7 | 2 | 0 | 국가 선택 6요소 부분 |
| 45 | php-page | b_tournament.php | 11 | 5 | 0 | 토너먼트 표면 레이아웃만 |
| 45 | vue-page | v_cachedMap.php → map | 8 | 2 | 0 | MapViewer 정본 부분 |
| 45 | vue-page | v_nationStratFinan.php → nation-finance | 9 | 4 | 0 | 예산/정책 표 부분 |
| 45 | php-ajax | j_diplomacy_send_letter.php | 6 | 4 | 0 | 서신 INSERT + aux 표시 미구현 |
| 45 | read-api | Global/GeneralList → /api/generals | 14 | 5 | 3 | 장수일람 렌더 폭넓게 불일치 |
| 45 | read-api | Global/GetDiplomacy | 8 | 3 | 0 | 중원정보 read 부분 |
| 45 | command | che_종전수락 | 6 | 2 | 0 | logic 포팅(non-stub) but 갭 |
| 45 | event | Event/Action/ProvideNPCTroopLeader | 4 | 4 | 0 | 배선됐으나 핵심 관측효과 미구현 |
| 45 | domain | Betting (도메인) | 4 | 4 | 0 | calcReward/giveReward 분기 |
| 45 | domain | NationTurn (국가명령 실행경로) | 5 | 4 | 0 | seam은 GREEN, 본체 부분 |
| 48 | vue-page | PageNationStratFinan.vue → nation-finance | 6 | 3 | 0 | 3대 섹션 부분 |

### 3.2 충실도 50–69 (92건 — 2차 보강)
세부는 raw 참조. 주목할 군집:
- **외교/베팅/경매 intake**: `Auction/{BidBuyRiceAuction, BidSellRiceAuction, BidUniqueAuction}`(55), `Message/DecideMessageResponse`(62), `che_종전수락`/`불가침` 패밀리 다수가 55–62.
- **건국/임관/전략 command**(55–62): `che_건국·무작위건국·선양·발령·초토화·감축·몰수·임관·장수대상임관·랜덤임관·출병·피장파장·무작위수도이전·집합·방랑`.
- **read-api**(55): `General/GetCommandTable`(blocked 4), `Global/GetConst`(pv 7), `Vote/GetVoteDetail`.
- **도메인/이벤트**(55–62): `Auction`(pv 7), `Diplomacy`, `Event/Action(base+factory)`(high 8), `ProcessWar/ConflictMap`, `Enums(16종)`, `ActionItem(100+종)`(52), `ActionSpecialWar(20종)`.
- **FE 페이지/컴포넌트**(55–68): `v_chiefCenter`, `v_globalDiplomacy`, `v_troop`(pv 9), `v_NPCControl`, `GameInfo`(pv 5), `MainControlBar`, `vue-lobby-page`, `index.php`(58, blocked 13).

### 3.3 충실도 70–100 (231건 — 양호, 회귀 감시)
- **70–89 (135건)**: 대부분 단일 high-gap의 마감 작업. 예) `Command/GeneralCommand`(78, high 4), 외교 6종(78, pv 7), `ProcessWar/ConflictMap`(72), `General 도메인`(78), `event_*연구` 다수(70–78).
- **90–100 (96건)**: 사실상 패러티 달성. 예) `che_거병`(96), `event_연구 9종`(96), `WarUnitTrigger/*`(90–100 다수), `ActionNationType(14종)`(96), 다수 `Nation/Set*`·`Troop/*`·`InheritAction/*` intake가 90–100. `utilGame/getNPCColor`·`formatVoteColor`·`Event/Action/{RandomizeCityTradeRate, ProcessSemiAnnual, DeleteEvent}` 등은 **100**.

> **명시적 패러티 위반(parityViolations) 1,026건**이 비교 단위에 흩어져 있다. 위반 밀집 상위: `OpenNationBetting`(10), `PageTroop/v_troop`(각 9), `Auction/BidUniqueAuction`(9), `Auction/BidSellRiceAuction`(8), `외교 6종`·`GeneralCommand`·`Global/GetConst`·`Auction 도메인`(각 7). 각 위반의 where/detail/fix는 raw의 `parityViolations[]`에 보존.

---

## 4. 현재만 (currentOnly 72)

**전부 legacy에 대응이 없는 신규 구조이며, 패러티 위반이 아니다(정당).** 대다수가 Next.js/CQRS 아키텍처가 요구하는 인프라(JWT 게이트, SSE, 멀티서버 프록시, 헬스체크)이거나 legacy의 인라인/PHP-서버 처리를 FE 모듈로 추출한 리팩터링 산물이다. 주의가 필요한 두 경우만 명시한다.

- **정당한 아키텍처 신규**: `AuthGate`/`comp-auth-gate`/`game-api-route-proxy`/`game-api-route-auth-me`(JWT 동일오리진 프록시), `ctrl-sse-relay`/`hook-use-sse`(SSE turnCompleted), `ctrl-health`/`*-health-route`(인프라), `gateway-route-*`/`lib-serverRegistry`/`lib-server-api`(멀티서버 프록시), `Toast`/`ErrorBoundary`/`Shell`/`Sidebar`/`Header`/`StatusBadge`/`Gauge` 등 UI 인프라, `eventResearch_helper`/`lib-menu-filter`/`lib-command-arg-types`/`lib-chosung`/`lib-flagTint`(legacy 인라인 추출 리팩터).
- **현재만이지만 기능 축소/스텁 — 추적 필요**:
  - `ctrl-simulator` (POST /api/simulate-battle): **순수 날조 스텁**(random() 반환). legacy `j_simulate_battle.php`는 실 전투 엔진 호출 → §3.1 시뮬레이터 갭(fid 3–8)과 동일 문제.
  - `ctrl-npc-policy` (GET /api/nation/npc-policy): read는 분리됐으나 POST 저장(`j_set_npc_control`)이 §2.9에 미구현.
  - `comp-game-table`: legacy ag-grid 대비 기능 축소(GeneralList/세력장수 화면 저충실도와 연결).
  - `page-coming-soon`: 미구현 경로(감찰부 등 4개) 404 버퍼 — §2.7 `v_battleCenter.php` 등과 짝.
  - `vue-admin-page`: 서버제어 탭만 완료, 회원관리·게임환경 탭 PLACEHOLDER(§2.2 어드민 전부 미구현과 짝).

---

## 5. foundation-first 실행 우선순위 (disjoint 그룹)

공유 확장점(CommandRegistry, intakeCodes, CommandWireMapper wire variants, F4Dto, 도메인 base)은 **Tier-0에서 1회만 widening**하고, 이후 패밀리는 disjoint 파일에서 병렬 소비한다(co-widen 금지 = merge conflict). 그룹 간 공유 파일이 겹치지 않도록 분리했다.

### Tier-0 — 공유 시드(순차, creator-then-consumer)
1. **Scenario/풀/트리거 도메인**(§2.4): `Scenario/{GeneralBuilder, Nation}`, `AbsGeneralPool`/`RandomNameGeneral`, `GeneralTriggerCaller`/`BaseGeneralTrigger` — 이민족 event·NPC 생성·장수직접생성이 모두 의존. 가장 먼저.
2. **utilGame 포매터 12종**(§2.10): `formatLog/honor/injury/dexLevel/cityName/...` — 거의 모든 FE 페이지가 소비. 단일 PR로 시드.
3. **intake/wire 시드**: `CommandWireMapper` intakeCodes + 디스패처에 `DieOnPrestart/DropItem/InstantRetreat/InheritAction.ResetStat/CheckOwner` 1회 등록.

### 그룹 A — missing command + event (/parity-wave, 골든 필수)
- **A1 che_계략 패밀리**(§2.1): `che_{화계, 파괴, 탈취, 선동, 첩보, 단련, 강행, 숙련전환, 전투태세, 모반시도, 전투특기초기화, 내정특기초기화, 접경귀환}` + `cr_인구이동`, `che_등용수락`. 각자 disjoint Che*.kt. /parity-wave로 골든→port→gate.
- **A2 부분포팅 command run() 마감**(§3.1, fid 35): `che_{견문, 해산, 인재탐색, 종전제의, 불가침제의, 불가침수락, 불가침파기제의, 불가침파기수락}`. 등록은 됐으니 run()/resolve() 본체 + 골든.
- **A3 이민족/NPC event**(§2.3): `RaiseInvader → {AutoDeleteInvader, InvaderEnding}`, `RaiseNPCNation`, `RegNPC/RegNeutralNPC`, `CreateManyNPC`, `Block/UnblockScoutAction`, `ChangeCity`, `LostUniqueItem`. Tier-0(1) 의존.

### 그룹 B — admin (별 그룹, gateway-api + 신규 admin 화면)
§2.2 + §2.9 어드민: `_admin1·2·5·7·8` + `_119`/`_admin_force_rehall` + 루트DB(`j-get/set-userlist`, `api-admin-ban-email`) + 서버개폐(`j-server-*`). 백엔드 엔드포인트 신설 + `vue-admin-page` PLACEHOLDER 탭 채움. **FE 작업이 BE에 BLOCKED이므로 BE 먼저.**

### 그룹 C — FE missing (별 그룹, BLOCKED 선결 주의)
- **C1 거래/베팅/게시판/외교 write 인테이크**(§2.9): `j_diplomacy_{send/destroy/rollback}_letter`, `j_board_{article/comment}_add`, `j_set_my_setting`, `j_vacation`, `j_myBossInfo`, `j_set_npc_control` POST. → 이후 FE 컴포넌트(§2.8 `AuctionResource/UniqueItem`, `BettingDetail`, `BoardArticle/Comment`, `TipTap`) 작업. **BE intake 먼저, 그다음 FE.**
- **C2 선택풀/빙의 플로우**: `j_get_select_pool`(read) + `select_general_from_pool.ts`/`select_npc.ts` FE + `j_select/update_picked_general` submit. read 엔드포인트가 FE를 BLOCK.
- **C3 저충실도 read 페이지 보강**(§3.1, blocked 낮은 것 우선): `a_kingdomList`/`a_genList`/`a_npcList`/`a_hallOfFame`/`a_emperior(_detail)`(랭킹 계열, blocked 적음), `b_myCityInfo`/`b_myKingdomInfo`/`b_myGenInfo`(세력 계열), `v_history`/`v_auction`/`v_nationGeneral`. **blocked 높은 `b_currentCity`(8), `b_myPage`(11), `v_processing`(10), bossInfo/bestGeneral/ts-admin은 BE 선결 후.**
- **C4 시뮬레이터**: `ctrl-simulator` 날조 스텁 → 실 전투엔진 배선(`j_simulate_battle` BE) + `battle_simulator.ts/php` FE + `j_get_basic_general_list` picker. 단일 수직 슬라이스.

### 그룹 D — read-api/intake 형상 보강(50–69, §3.2)
auction/betting/message/vote read DTO 형상을 legacy envelope에 맞춤. 각 컨트롤러 disjoint.

> **순서 요약**: Tier-0(Scenario+utilGame+wire) → 그룹 A(command/event, /parity-wave 병렬) ∥ 그룹 B(admin BE) → 그룹 C(FE, BE 선결분 먼저) → 그룹 D(DTO 형상). 그룹 간 공유 파일 비중첩.

---

## 6. 참조

- **상세 fixSpec/근거(file:line)**: 단위별 `gaps[].fixSpec`/`resolutionSource` 및 `parityViolations[].{where,detail,fix}`는 raw에 보존 — `docs/superpowers/gap/_full_audit_2026-06-07.raw.json`. jq 슬라이스로 조회(예: `jq '.comparisons[]|select(.unit|test("che_견문"))' ...`).
- **기존 영역별 1차 감사**: `API_GAP.md` · `FE_OUTPUT_ACTION_GAP.md` · `FE_OUTPUT_READ_GAP.md` · `FE_STRUCTURE_GAP.md` · `LOGIC_GAP.md` · `READ_DTO_GAP.md` · `FOUNDING_SEAM_FIX.md` · `PARITY_RECONCILED.md` · `WAVE_COVERAGE_REVIEW.md`.
- **웨이브 플랜/스펙**: `docs/superpowers/gap/waves/WAVE_0b–9.md` + `W3_*`/`W5d_*`/`W6_*`, `docs/superpowers/gap/specs/`.
- **상위 핸드오프**: `docs/superpowers/GAP_AUDIT.md`(W0–W9 10웨이브) · `SESSION_HANDOFF.md` · `PARITY_LEDGER.md` · `P6_STATUS.md` · `P7_STATUS.md`.

---

*문서 한계: 충실도 비교는 457/768 단위만 커버(13 에이전트 실패 → ~311 미비교). 미비교 단위의 패러티는 본 문서로 확정되지 않으며, 후속 감사로 보완해야 한다. 모든 수치는 HEAD `b58f99a` 시점 raw 기준.*
