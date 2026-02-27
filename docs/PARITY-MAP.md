# 패러티 매핑 (Parity Map)

> **원칙**
> - **프론트엔드**: 레거시(PHP+Vue) = 패러티 소스
> - **백엔드**: 레거시 vs core2026 중 더 나은 것 = 패러티 소스 (파일별 표기)
> - `[L]` = 레거시가 패러티 소스, `[C]` = core2026이 패러티 소스, `[L+C]` = 둘 다 참조

---

## A. 프론트엔드 페이지 매핑 (패러티 소스: 레거시)

### A1. 인증 (Auth)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(auth)/login/` | `hwe/index.php` + `ts/gateway/login.ts` | `gateway-frontend/views/HomeView.vue` | 로그인 |
| `(auth)/register/` | `hwe/index.php` + `ts/gateway/login.ts` | `gateway-frontend/views/HomeView.vue` | 회원가입 (레거시는 로그인과 동일 페이지) |
| `(auth)/account/` | `ts/gateway/user_info.ts` | — | 계정 설정 |

### A2. 로비 (Lobby)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(lobby)/lobby/page.tsx` | `ts/gateway/entrance.ts` | `gateway-frontend/views/LobbyView.vue` | 월드 목록/입장 |
| `(lobby)/lobby/join/` | `hwe/v_join.php` + `ts/v_join.ts` + `PageJoin.vue` | `game-frontend/views/JoinView.vue` | 신규 장수 생성 |
| `(lobby)/lobby/select-npc/` | `hwe/select_npc.php` + `ts/select_npc.ts` | — | NPC 빙의 선택 |
| `(lobby)/lobby/select-pool/` | `hwe/select_general_from_pool.php` + `ts/select_general_from_pool.ts` | — | 장수풀 선택 |

### A3. 게임 메인 (Game Main)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(game)/page.tsx` | `hwe/ts/PageFront.vue` + `ts/v_front.ts` | `game-frontend/views/MainView.vue` | **메인 대시보드** (분석 완료) |

### A4. 게임 정보열람 (Browse)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(game)/general/` | `hwe/b_myGenInfo.php` | — | 내 장수 정보 |
| `(game)/generals/` | `hwe/b_genList.php` + `hwe/a_genList.php` | — | 장수 목록 |
| `(game)/city/` | `hwe/b_currentCity.php` + `hwe/b_myCityInfo.php` | — | 도시 정보 |
| `(game)/nation/` | `hwe/b_myKingdomInfo.php` | `game-frontend/views/NationAffairsView.vue` | 내 국가 정보 |
| `(game)/nations/` | `hwe/a_kingdomList.php` | — | 국가 목록 |
| `(game)/superior/` | `hwe/b_myBossInfo.php` + `ts/bossInfo.ts` | — | 태수/군주 정보 |
| `(game)/my-page/` | `hwe/b_myPage.php` + `ts/myPage.ts` | `game-frontend/views/MyPageView.vue` + `MySettingsView.vue` | 마이 페이지 |
| `(game)/npc-list/` | `hwe/a_npcList.php` | — | NPC 목록 |
| `(game)/traffic/` | `hwe/a_traffic.php` | — | 접속 현황 |

### A5. 게임 기능 페이지 (Features)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(game)/commands/` | `ts/PartialReservedCommand.vue` + `ts/processing/` | — | 명령 예약 (별도 페이지) |
| `(game)/processing/` | `hwe/v_processing.php` + `ts/v_processing.ts` + `ts/processing/*.vue` | — | 명령 실행 UI |
| `(game)/map/` | `hwe/v_cachedMap.php` + `ts/v_cachedMap.ts` + `PageCachedMap.vue` + `hwe/recent_map.php` | — | 지도 |
| `(game)/auction/` | `hwe/v_auction.php` + `ts/v_auction.ts` + `PageAuction.vue` | — | 경매장 |
| `(game)/battle-center/` | `hwe/v_battleCenter.php` + `ts/v_battleCenter.ts` + `PageBattleCenter.vue` | `game-frontend/views/BattleCenterView.vue` | 전투 중앙 |
| `(game)/battle-simulator/` | `hwe/battle_simulator.php` + `ts/battle_simulator.ts` | `game-frontend/views/BattleSimulatorView.vue` | 전투 시뮬레이터 |
| `(game)/board/` | `hwe/v_board.php` + `ts/v_board.ts` + `PageBoard.vue` | `game-frontend/views/BoardView.vue` | 게시판 |
| `(game)/diplomacy/` | `hwe/v_globalDiplomacy.php` + `ts/v_globalDiplomacy.ts` + `PageGlobalDiplomacy.vue` + `hwe/t_diplomacy.php` | `game-frontend/views/DiplomacyView.vue` | 외교 |
| `(game)/history/` | `hwe/v_history.php` + `ts/v_history.ts` + `PageHistory.vue` | — | 기록/로그 |
| `(game)/inherit/` | `hwe/v_inheritPoint.php` + `ts/v_inheritPoint.ts` + `PageInheritPoint.vue` | `game-frontend/views/InheritView.vue` | 유산 포인트 |
| `(game)/messages/` | `ts/components/MessagePanel.vue` + `ts/msg.ts` | — | 메시지함 (별도 페이지) |
| `(game)/troop/` | `hwe/v_troop.php` + `ts/v_troop.ts` + `PageTroop.vue` | — | 부대 관리 |
| `(game)/vote/` | `hwe/v_vote.php` + `ts/v_vote.ts` + `PageVote.vue` | `game-frontend/views/SurveyView.vue` | 투표 |
| `(game)/betting/` | `hwe/b_betting.php` + `ts/betting.ts` + `PageNationBetting.vue` | — | 베팅 |
| `(game)/tournament/` | `hwe/b_tournament.php` + `hwe/c_tournament.php` | `game-frontend/views/TournamentView.vue` | 토너먼트 |
| `(game)/npc-control/` | `hwe/v_NPCControl.php` + `ts/v_NPCControl.ts` + `PageNPCControl.vue` | `game-frontend/views/NpcControlView.vue` | NPC 조종 |

### A6. 국가 관리 (Nation Management)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(game)/chief/` | `hwe/v_chiefCenter.php` + `ts/v_chiefCenter.ts` + `PageChiefCenter.vue` + `ts/ChiefCenter/*.vue` | `game-frontend/views/ChiefCenterView.vue` | 기밀실 (군주 전용) |
| `(game)/nation-generals/` | `ts/v_nationGeneral.ts` + `PageNationGeneral.vue` | `game-frontend/views/NationGeneralsView.vue` | 국가 장수 관리 |
| `(game)/nation-cities/` | `ts/v_nationStratFinan.ts` + `PageNationStratFinan.vue` (일부) | `game-frontend/views/NationCitiesView.vue` | 국가 도시 목록 |
| `(game)/internal-affairs/` | `PageNationStratFinan.vue` (재정/전략 탭) | `game-frontend/views/NationStratFinanView.vue` | 내정 전략/재정 |
| `(game)/personnel/` | `PageChiefCenter.vue` (인사 탭) | `game-frontend/views/NationPersonnelView.vue` | 인사 관리 |
| `(game)/spy/` | `hwe/func_message.php` (첩보 관련) | `game-frontend/views/ScoutMessageView.vue` | 첩보/정찰 |

### A7. 랭킹/명예 (Rankings)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(game)/best-generals/` | `hwe/a_bestGeneral.php` + `ts/bestGeneral.ts` | `game-frontend/views/BestGeneralView.vue` | 명장 랭킹 |
| `(game)/emperor/` | `hwe/a_emperior.php` + `hwe/a_emperior_detail.php` | `game-frontend/views/DynastyListView.vue` + `DynastyDetailView.vue` | 황제 목록 |
| `(game)/dynasty/` | `hwe/a_emperior.php` (왕조 부분) | `game-frontend/views/DynastyListView.vue` | 왕조 일람 |
| `(game)/hall-of-fame/` | `hwe/a_hallOfFame.php` + `ts/hallOfFame.ts` | `game-frontend/views/HallOfFameView.vue` | 명예의 전당 |

### A8. 관리자 (Admin)

| 현재 (Next.js) | 레거시 (PHP/Vue/TS) | core2026 | 비고 |
|---|---|---|---|
| `(admin)/admin/page.tsx` | `hwe/_admin1.php` + `ts/gateway/admin_server.ts` | `gateway-frontend/views/AdminView.vue` | 관리자 메인 |
| `(admin)/admin/members/` | `hwe/_admin2.php` + `ts/gateway/admin_member.ts` | — | 회원 관리 |
| `(admin)/admin/users/` | `hwe/_admin2.php` | — | 사용자 관리 |
| `(admin)/admin/diplomacy/` | `hwe/_admin5.php` | — | 외교 관리 |
| `(admin)/admin/logs/` | `hwe/_admin7.php` | — | 로그 조회 |
| `(admin)/admin/statistics/` | `hwe/_admin8.php` | — | 통계 |
| `(admin)/admin/time-control/` | `hwe/_119.php` + `hwe/_119_b.php` | — | 시간 제어 |

### A9. 프론트엔드 컴포넌트 매핑

| 현재 (React) | 레거시 (Vue) | core2026 (Vue) | 비고 |
|---|---|---|---|
| `general-basic-card.tsx` | `GeneralBasicCard.vue` + `GameInfo.vue` | `GeneralBasicCard.vue` | 장수 정보 카드 |
| `nation-basic-card.tsx` | `NationBasicCard.vue` + `GameInfo.vue` | `NationBasicCard.vue` | 국가 정보 카드 |
| `city-basic-card.tsx` | `CityBasicCard.vue` + `GameInfo.vue` | `CityBasicCard.vue` | 도시 정보 카드 |
| `main-control-bar.tsx` | `MainControlBar.vue` + `MainControlDropdown.vue` | — | 메인 컨트롤 바 |
| `command-panel.tsx` | `PartialReservedCommand.vue` | `CommandListPanel.vue` | 명령 예약 패널 |
| `command-select-form.tsx` | `CommandSelectForm.vue` | `CommandSelectForm.vue` | 명령 선택 폼 |
| `map-viewer.tsx` | `MapViewer.vue` + `MapCityBasic.vue` + `MapCityDetail.vue` | `MapViewer.vue` + `MapCityBasic.vue` + `MapCityDetail.vue` | 지도 뷰어 |
| `message-panel.tsx` | `MessagePanel.vue` | `MessagePanel.vue` | 메시지 패널 |
| `message-plate.tsx` | `MessagePlate.vue` | — | 메시지 표시판 |
| `game-bottom-bar.tsx` | `GameBottomBar.vue` + `BottomBar.vue` | — | 하단 바 |
| `sammo-bar.tsx` | `SammoBar.vue` | — | 수치 바 |
| `record-zone.tsx` | (PageFront.vue 내부 RecordZone) | — | 기록 영역 |
| `turn-timer.tsx` | `SimpleClock.vue` | — | 턴 타이머 |
| `page-header.tsx` | `TopBackBar.vue` | — | 페이지 헤더 |
| `general-portrait.tsx` | (inline in legacy) | — | 장수 초상화 |
| `nation-badge.tsx` | (inline in legacy) | — | 국가 뱃지 |
| `stat-bar.tsx` | `SammoBar.vue` (variant) | — | 스탯 바 |
| `resource-display.tsx` | (inline in legacy) | — | 자원 표시 |
| `konva-map-canvas.tsx` | `MapViewer.vue` (canvas) | `MapViewer.vue` | 지도 캔버스 |
| `dev-bar.tsx` | — | — | 개발용 (레거시 없음) |
| `empty-state.tsx` | — | — | UI 유틸 (레거시 없음) |
| `loading-state.tsx` | — | — | UI 유틸 (레거시 없음) |
| `command-arg-form.tsx` | `ts/processing/*.vue` (13개) | — | 명령 인수 폼 |
| — ❌ | `AutorunInfo.vue` | — | **미구현**: 자동실행 정보 |
| — ❌ | `GlobalMenu.vue` + `GlobalMenuDropdown.vue` | — | **미구현**: 글로벌 메뉴 (다른 네비게이션 패턴) |
| — ❌ | `GeneralLiteCard.vue` | — | **미구현**: 간이 장수 카드 |
| — ❌ | `GeneralSupplementCard.vue` | — | **미구현**: 장수 보충 카드 |
| — ❌ | `GeneralList.vue` | — | **미구현**: 장수 리스트 (별도 컴포넌트) |
| — ❌ | `SimpleNationList.vue` | — | **미구현**: 간이 국가 목록 |
| — ❌ | `DragSelect.vue` | — | **미구현**: 드래그 선택 UI |
| — ❌ | `NumberInputWithInfo.vue` | — | **미구현**: 수치 입력 UI |
| — ❌ | `TipTap.vue` | — | **미구현**: 리치 텍스트 에디터 |
| — ❌ | `BettingDetail.vue` | — | **미구현**: 베팅 상세 |
| — ❌ | `BoardArticle.vue` + `BoardComment.vue` | — | **미구현**: 게시판 컴포넌트 |
| — ❌ | `AuctionResource.vue` + `AuctionUniqueItem.vue` | — | **미구현**: 경매 컴포넌트 |
| — ❌ | `ChiefReservedCommand.vue` | — | **미구현**: 기밀실 예약명령 |

### A10. 프론트엔드 스토어 매핑

| 현재 (Zustand) | 레거시 (Vue) | core2026 (Pinia) | 비고 |
|---|---|---|---|
| `authStore.ts` | `ts/gateway/common.ts` | `gateway-frontend/stores/` | 인증 상태 |
| `generalStore.ts` | `ts/state/` | `game-frontend/stores/session.ts` | 장수 상태 |
| `worldStore.ts` | `ts/GameConstStore.ts` | `game-frontend/stores/session.ts` | 월드 상태 |
| `gameStore.ts` | (PageFront.vue 내부 state) | `game-frontend/stores/mainDashboard.ts` | 게임 대시보드 상태 |
| — | — | `game-frontend/stores/mapViewer.ts` | 지도 뷰어 상태 |

### A11. API 클라이언트 매핑

| 현재 (`gameApi.ts`) | 레거시 (`SammoAPI.ts`) | core2026 (tRPC) | 비고 |
|---|---|---|---|
| `frontApi` | `SammoAPI.General.GetFrontInfo` | `game-api/router/general/` | 메인 정보 |
| `generalApi` | `SammoAPI.General.*` | `game-api/router/general/` | 장수 API |
| `nationApi` | `SammoAPI.Nation.*` | `game-api/router/nation/` | 국가 API |
| `commandApi` | `SammoAPI.Command.*` | `game-api/router/turns/` | 명령 API |
| `nationCommandApi` | `SammoAPI.NationCommand.*` | `game-api/router/turns/` | 국가명령 API |
| `mapApi` | `SammoAPI.Global.GetMap` / `GetCachedMap` | `game-api/router/` (maps) | 지도 API |
| `cityApi` | (inline in SammoAPI) | — | 도시 API |
| `messageApi` | `SammoAPI.Message.*` | `game-api/router/messages/` | 메시지 API |
| `auctionApi` | `SammoAPI.Auction.*` | `game-api/router/auction/` | 경매 API |
| `tournamentApi` | (inline) | `game-api/router/tournament/` | 토너먼트 API |
| `bettingApi` | `SammoAPI.Betting.*` | — | 베팅 API |
| `diplomacyApi` | `SammoAPI.Global.GetDiplomacy` | `game-api/router/diplomacy/` | 외교 API |
| `diplomacyLetterApi` | (j_diplomacy_*.php) | — | 외교 서신 API |
| `troopApi` | `SammoAPI.Troop.*` | `game-api/router/troop/` | 부대 API |
| `voteApi` | `SammoAPI.Vote.*` | `game-api/router/vote/` | 투표 API |
| `boardApi` | (j_board_*.php) | `game-api/router/board/` | 게시판 API |
| `inheritApi` | `SammoAPI.InheritAction.*` | `game-api/router/inherit/` | 유산 API |
| `historyApi` | `SammoAPI.Global.GetHistory` | — | 기록 API |
| `rankingApi` | (a_*.php) | `game-api/router/ranking/` | 랭킹 API |
| `battleSimApi` | (j_simulate_battle.php) | `game-api/router/battle/` | 전투 시뮬 API |
| `worldApi` | (j_server_basic_info.php) | `game-api/router/world/` | 월드 API |
| `scenarioApi` | (j_load_scenarios.php) | `gateway-api/scenario/` | 시나리오 API |
| `adminApi` | (_admin*.php) | `gateway-api/adminRouter.ts` | 관리자 API |
| `authApi` | (gateway login/register) | `gateway-api/auth/` | 인증 API |
| `accountApi` | (gateway user_info) | — | 계정 API |
| `turnApi` | `SammoAPI.Global.ExecuteEngine` | `game-api/router/turnDaemon/` | 턴 API |
| `realtimeApi` | (WebSocket) | `game-api/realtime/` | 실시간 API |

---

## B. 백엔드 매핑

### B1. 컨트롤러 ↔ 레거시 API ↔ core2026 라우터

| 현재 (Spring Boot) | 레거시 (PHP API) | core2026 (tRPC Router) | 패러티 소스 |
|---|---|---|---|
| `AuthController.kt` | `hwe/index.php` (login) + `legacy/src/sammo/API/Login/` | `gateway-api/auth/` + `game-api/router/auth/` | `[L+C]` |
| `AccountController.kt` | `ts/gateway/user_info.ts` | — | `[L]` |
| `AdminController.kt` | `hwe/_admin*.php` + `hwe/j_raise_event.php` | `gateway-api/adminRouter.ts` | `[L+C]` |
| `AuctionController.kt` | `hwe/sammo/API/Auction/*.php` (9개) | `game-api/router/auction/` | `[L]` — 레거시가 9개 API로 더 상세 |
| `BattleSimController.kt` | `hwe/j_simulate_battle.php` | `game-api/router/battle/` | `[C]` — core2026이 더 구조적 |
| `CityController.kt` | `hwe/j_get_city_list.php` + `hwe/b_currentCity.php` | — | `[L]` |
| `CommandController.kt` | `hwe/sammo/API/Command/*.php` (5개) | `game-api/router/turns/` | `[C]` — core2026이 턴/커맨드 분리 더 깔끔 |
| `DiplomacyController.kt` | `hwe/sammo/API/Global/GetDiplomacy.php` | `game-api/router/diplomacy/` | `[C]` |
| `DiplomacyLetterController.kt` | `hwe/j_diplomacy_*.php` (5개) | `game-api/router/diplomacy/` (통합) | `[L]` — 서신 API가 더 세분화 |
| `GeneralController.kt` | `hwe/sammo/API/General/*.php` (8개) + `hwe/j_get_basic_general_list.php` | `game-api/router/general/` | `[L+C]` |
| `HistoryController.kt` | `hwe/sammo/API/Global/GetHistory.php` + `GetCurrentHistory.php` + `GetRecentRecord.php` | — | `[L]` |
| `InheritanceController.kt` | `hwe/sammo/API/InheritAction/*.php` (8개) | `game-api/router/inherit/` | `[L]` — 8개 API로 더 상세 |
| `MapController.kt` | `hwe/sammo/API/Global/GetMap.php` + `GetCachedMap.php` + `hwe/j_map.php` | `game-api/` (maps) | `[L+C]` |
| `MessageController.kt` | `hwe/sammo/API/Message/*.php` (7개) | `game-api/router/messages/` | `[L]` — 7개 API로 더 상세 |
| `NationController.kt` | `hwe/sammo/API/Nation/*.php` (11개) + `hwe/sammo/API/Global/GetNationList.php` | `game-api/router/nation/` | `[L+C]` |
| `NationManagementController.kt` | `hwe/sammo/API/Nation/Set*.php` | `game-api/router/nation/endpoints/` | `[L]` |
| `NationPolicyController.kt` | (inline in Nation API) | `game-api/router/nation/` | `[C]` |
| `NpcPolicyController.kt` | `hwe/j_set_npc_control.php` | `game-api/router/npc/` | `[L+C]` |
| `RankingController.kt` | `hwe/a_bestGeneral.php` + `a_hallOfFame.php` + `a_emperior.php` | `game-api/router/ranking/` + `game-api/router/dynasty/` | `[L+C]` |
| `RealtimeController.kt` | (WebSocket in legacy) | `game-api/realtime/` | `[C]` |
| `ScenarioController.kt` | `hwe/j_load_scenarios.php` + `hwe/sammo/Scenario.php` | `gateway-api/scenario/scenarioCatalog.ts` + `game-engine/scenario/` | `[C]` — core2026 시나리오 구조가 우수 |
| `TournamentController.kt` | `hwe/func_tournament.php` + `hwe/c_tournament.php` | `game-api/router/tournament/` | `[C]` |
| `TroopController.kt` | `hwe/sammo/API/Troop/*.php` (5개) | `game-api/router/troop/` | `[L]` — 5개 API로 더 상세 |
| `TurnController.kt` | `hwe/sammo/API/Global/ExecuteEngine.php` | `game-api/router/turnDaemon/` | `[C]` |
| `VoteController.kt` | `hwe/sammo/API/Vote/*.php` (5개) | `game-api/router/vote/` | `[L]` — 5개 API로 더 상세 |
| `WorldController.kt` | `hwe/j_server_basic_info.php` | `game-api/router/world/` | `[C]` |

### B2. 서비스 ↔ 레거시 로직 ↔ core2026 로직

| 현재 (Spring Boot) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `AuthService.kt` | `legacy/src/sammo/Session.php` + `KakaoUtil.php` | `gateway-api/auth/*.ts` (11개) | `[C]` — 세션/인증 분리가 우수 |
| `AccountService.kt` | `ts/gateway/user_info.ts` | — | `[L]` |
| `AdminService.kt` | `hwe/_admin*.php` | `gateway-api/adminRouter.ts` + `orchestrator/` | `[C]` — 오케스트레이터 패턴 |
| `AuctionService.kt` | `hwe/sammo/Auction*.php` (5개 클래스) | `game-api/router/auction/` + `game-engine/auction/` | `[L]` — 경매 로직이 더 완성도 높음 |
| `BattleSimService.kt` | `hwe/j_simulate_battle.php` + `hwe/process_war.php` | `game-api/router/battle/` | `[C]` |
| `CityService.kt` | `hwe/sammo/CityHelper.php` + `CityConstBase.php` + `CityInitialDetail.php` | — | `[L]` — 도시 상수/헬퍼가 가장 상세 |
| `CommandService.kt` | `hwe/sammo/API/Command/*.php` + `hwe/func_command.php` | `game-api/router/turns/` + `game-engine/turn/reservedTurn*.ts` | `[C]` — 턴 커맨드 구조가 더 깔끔 |
| `DiplomacyLetterService.kt` | `hwe/sammo/DiplomaticMessage.php` + `hwe/j_diplomacy_*.php` | `logic/diplomacy/` + `game-api/router/diplomacy/` | `[L+C]` |
| `FrontInfoService.kt` | `hwe/sammo/API/General/GetFrontInfo.php` + `hwe/func.php` | `game-api/router/general/` (frontInfo) | `[L]` — 가장 상세한 프론트 데이터 |
| `GameConstService.kt` | `hwe/sammo/GameConstBase.php` + `GameUnitConstBase.php` | `logic/resources/` + `resources/unitset/` | `[C]` — JSON 스키마 + 유닛셋 분리 |
| `GameEventService.kt` | `hwe/sammo/Event/*.php` + `StaticEvent/` + `StaticEventHandler.php` | — | `[L]` — 이벤트 시스템이 레거시에만 완전 |
| `GeneralService.kt` | `hwe/sammo/General.php` + `GeneralBase.php` + `GeneralLite.php` + `DummyGeneral.php` | `logic/domain/entities.ts` | `[L]` — 장수 로직이 가장 완성도 높음 |
| `HistoryService.kt` | `hwe/func_history.php` + `hwe/sammo/API/Global/GetHistory.php` | — | `[L]` |
| `InheritanceService.kt` | `hwe/sammo/InheritancePointManager.php` + `API/InheritAction/*.php` | `logic/inheritance/inheritBuff.ts` | `[L]` — 8개 액션이 더 상세 |
| `ItemService.kt` | `hwe/sammo/BaseItem.php` + `BaseStatItem.php` + `ActionItem/` | `logic/items/*.ts` (120+개) | `[C]` — 아이템 120+개 개별 파일로 완전 분리 |
| `MapService.kt` | `hwe/func_map.php` + `hwe/j_map.php` | `resources/map/*.json` (9개) | `[C]` — JSON 맵 데이터 |
| `MessageService.kt` | `hwe/sammo/Message.php` + `MessageTarget.php` + `ScoutMessage.php` | `logic/messages/` | `[L]` — 메시지 타겟/정찰 로직 |
| `NationService.kt` | `hwe/sammo/BaseNation.php` + `hwe/sammo/Scenario/Nation.php` | `logic/domain/entities.ts` | `[L]` |
| `OfficerRankService.kt` | `hwe/sammo/TriggerOfficerLevel.php` | — | `[L]` |
| `RankingService.kt` | `hwe/a_bestGeneral.php` + `a_hallOfFame.php` | `game-api/router/ranking/` | `[L+C]` |
| `ScenarioService.kt` | `hwe/sammo/Scenario.php` + `hwe/sammo/Scenario/GeneralBuilder.php` + `Nation.php` | `logic/scenario/` + `game-engine/scenario/` + `resources/scenario/*.json` (82개) | `[C]` — 82개 시나리오 JSON + 구조적 로더 |
| `TournamentService.kt` | `hwe/func_tournament.php` + `hwe/sammo/Betting.php` | `logic/tournament/` + `game-engine/tournament/` + `game-api/router/tournament/` | `[C]` — 토너먼트/베팅 분리 |
| `TroopService.kt` | `hwe/sammo/API/Troop/*.php` | `game-api/router/troop/` | `[L]` |
| `TurnManagementService.kt` | `hwe/sammo/TurnExecutionHelper.php` + `hwe/proc.php` | `game-engine/turn/turnDaemon.ts` + `lifecycle/` | `[C]` — 라이프사이클 분리가 우수 |
| `VoteService.kt` | `hwe/sammo/API/Vote/*.php` | `game-api/router/vote/` | `[L]` |
| `WorldService.kt` | `hwe/sammo/ServerEnv.php` + `ServerTool.php` | `logic/world/` + `logic/ports/` | `[C]` — DI/포트 패턴 |

### B3. 장수 커맨드 (General Commands) — 55개

| 커맨드 | 레거시 (PHP) | core2026 (TS) | 현재 (Kotlin) | 패러티 소스 |
|---|---|---|---|---|
| 휴식 | `Command/General/휴식.php` | `actions/turn/general/휴식.ts` | `command/general/휴식.kt` | `[C]` |
| 강행 | `Command/General/che_강행.php` | `actions/turn/general/che_강행.ts` | `command/general/강행.kt` | `[C]` |
| 거병 | `Command/General/che_거병.php` | `actions/turn/general/che_거병.ts` | `command/general/거병.kt` | `[C]` |
| 건국 | `Command/General/che_건국.php` | `actions/turn/general/che_건국.ts` | `command/general/건국.kt` | `[C]` |
| 견문 | `Command/General/che_견문.php` | `actions/turn/general/che_견문.ts` | `command/general/견문.kt` | `[C]` |
| 군량매매 | `Command/General/che_군량매매.php` | `actions/turn/general/che_군량매매.ts` | `command/general/che_군량매매.kt` | `[C]` |
| 귀환 | `Command/General/che_귀환.php` | `actions/turn/general/che_귀환.ts` | `command/general/귀환.kt` | `[C]` |
| 기술연구 | `Command/General/che_기술연구.php` | `actions/turn/general/che_기술연구.ts` | `command/general/che_기술연구.kt` | `[C]` |
| 내정특기초기화 | `Command/General/che_내정특기초기화.php` | `actions/turn/general/che_내정특기초기화.ts` | `command/general/내정특기초기화.kt` | `[C]` |
| 농지개간 | `Command/General/che_농지개간.php` | `actions/turn/general/che_농지개간.ts` | `command/general/che_농지개간.kt` | `[C]` |
| 단련 | `Command/General/che_단련.php` | `actions/turn/general/che_단련.ts` | `command/general/che_단련.kt` | `[C]` |
| 등용 | `Command/General/che_등용.php` | `actions/turn/general/che_등용.ts` | `command/general/등용.kt` | `[C]` |
| 등용수락 | `Command/General/che_등용수락.php` | `actions/turn/general/che_등용수락.ts` | `command/general/등용수락.kt` | `[C]` |
| 랜덤임관 | `Command/General/che_랜덤임관.php` | `actions/turn/general/che_랜덤임관.ts` | `command/general/랜덤임관.kt` | `[C]` |
| 모반시도 | `Command/General/che_모반시도.php` | `actions/turn/general/che_모반시도.ts` | `command/general/모반시도.kt` | `[C]` |
| 모병 | `Command/General/che_모병.php` | `actions/turn/general/che_모병.ts` | `command/general/che_모병.kt` | `[C]` |
| 무작위건국 | `Command/General/che_무작위건국.php` | `actions/turn/general/che_무작위건국.ts` | `command/general/무작위건국.kt` | `[C]` |
| 물자조달 | `Command/General/che_물자조달.php` | `actions/turn/general/che_물자조달.ts` | `command/general/che_물자조달.kt` | `[C]` |
| 방랑 | `Command/General/che_방랑.php` | `actions/turn/general/che_방랑.ts` | `command/general/방랑.kt` | `[C]` |
| 사기진작 | `Command/General/che_사기진작.php` | `actions/turn/general/che_사기진작.ts` | `command/general/che_사기진작.kt` | `[C]` |
| 상업투자 | `Command/General/che_상업투자.php` | `actions/turn/general/che_상업투자.ts` | `command/general/che_상업투자.kt` | `[C]` |
| 선동 | `Command/General/che_선동.php` | `actions/turn/general/che_선동.ts` | `command/general/선동.kt` | `[C]` |
| 선양 | `Command/General/che_선양.php` | `actions/turn/general/che_선양.ts` | `command/general/선양.kt` | `[C]` |
| 성벽보수 | `Command/General/che_성벽보수.php` | `actions/turn/general/che_성벽보수.ts` | `command/general/che_성벽보수.kt` | `[C]` |
| 소집해제 | `Command/General/che_소집해제.php` | `actions/turn/general/che_소집해제.ts` | `command/general/che_소집해제.kt` | `[C]` |
| 수비강화 | `Command/General/che_수비강화.php` | `actions/turn/general/che_수비강화.ts` | `command/general/che_수비강화.kt` | `[C]` |
| 숙련전환 | `Command/General/che_숙련전환.php` | `actions/turn/general/che_숙련전환.ts` | `command/general/che_숙련전환.kt` | `[C]` |
| 요양 | `Command/General/che_요양.php` | `actions/turn/general/che_요양.ts` | `command/general/요양.kt` | `[C]` |
| 은퇴 | `Command/General/che_은퇴.php` | `actions/turn/general/che_은퇴.ts` | `command/general/은퇴.kt` | `[C]` |
| 이동 | `Command/General/che_이동.php` | `actions/turn/general/che_이동.ts` | `command/general/이동.kt` | `[C]` |
| 인재탐색 | `Command/General/che_인재탐색.php` | `actions/turn/general/che_인재탐색.ts` | `command/general/인재탐색.kt` | `[C]` |
| 임관 | `Command/General/che_임관.php` | `actions/turn/general/che_임관.ts` | `command/general/임관.kt` | `[C]` |
| 장비매매 | `Command/General/che_장비매매.php` | `actions/turn/general/che_장비매매.ts` | `command/general/장비매매.kt` | `[C]` |
| 장수대상임관 | `Command/General/che_장수대상임관.php` | `actions/turn/general/che_장수대상임관.ts` | `command/general/장수대상임관.kt` | `[C]` |
| 전투태세 | `Command/General/che_전투태세.php` | `actions/turn/general/che_전투태세.ts` | `command/general/전투태세.kt` | `[C]` |
| 전투특기초기화 | `Command/General/che_전투특기초기화.php` | `actions/turn/general/che_전투특기초기화.ts` | `command/general/전투특기초기화.kt` | `[C]` |
| 접경귀환 | `Command/General/che_접경귀환.php` | `actions/turn/general/che_접경귀환.ts` | `command/general/접경귀환.kt` | `[C]` |
| 정착장려 | `Command/General/che_정착장려.php` | `actions/turn/general/che_정착장려.ts` | `command/general/che_정착장려.kt` | `[C]` |
| 주민선정 | `Command/General/che_주민선정.php` | `actions/turn/general/che_주민선정.ts` | `command/general/che_주민선정.kt` | `[C]` |
| 증여 | `Command/General/che_증여.php` | `actions/turn/general/che_증여.ts` | `command/general/증여.kt` | `[C]` |
| 집합 | `Command/General/che_집합.php` | `actions/turn/general/che_집합.ts` | `command/general/집합.kt` | `[C]` |
| 징병 | `Command/General/che_징병.php` | `actions/turn/general/che_징병.ts` | `command/general/che_징병.kt` | `[C]` |
| 첩보 | `Command/General/che_첩보.php` | `actions/turn/general/che_첩보.ts` | `command/general/첩보.kt` | `[C]` |
| 출병 | `Command/General/che_출병.php` | `actions/turn/general/che_출병.ts` | `command/general/출병.kt` | `[C]` |
| 치안강화 | `Command/General/che_치안강화.php` | `actions/turn/general/che_치안강화.ts` | `command/general/che_치안강화.kt` | `[C]` |
| 탈취 | `Command/General/che_탈취.php` | `actions/turn/general/che_탈취.ts` | `command/general/탈취.kt` | `[C]` |
| 파괴 | `Command/General/che_파괴.php` | `actions/turn/general/che_파괴.ts` | `command/general/파괴.kt` | `[C]` |
| 하야 | `Command/General/che_하야.php` | `actions/turn/general/che_하야.ts` | `command/general/하야.kt` | `[C]` |
| 해산 | `Command/General/che_해산.php` | `actions/turn/general/che_해산.ts` | `command/general/해산.kt` | `[C]` |
| 헌납 | `Command/General/che_헌납.php` | `actions/turn/general/che_헌납.ts` | `command/general/che_헌납.kt` | `[C]` |
| 화계 | `Command/General/che_화계.php` | `actions/turn/general/che_화계.ts` | `command/general/화계.kt` | `[C]` |
| 훈련 | `Command/General/che_훈련.php` | `actions/turn/general/che_훈련.ts` | `command/general/che_훈련.kt` | `[C]` |
| NPC능동 | `Command/General/che_NPC능동.php` | `actions/turn/general/che_NPC능동.ts` | `command/general/NPC능동.kt` | `[C]` |
| cr_건국 | `Command/General/cr_건국.php` | `actions/turn/general/cr_건국.ts` | `command/general/CR건국.kt` | `[C]` |
| cr_맹훈련 | `Command/General/cr_맹훈련.php` | `actions/turn/general/cr_맹훈련.ts` | `command/general/CR맹훈련.kt` | `[C]` |

> **장수 커맨드 패러티**: 레거시 55 = core2026 58 (공통모듈 3개 추가) = 현재 56. 모든 커맨드 `[C]` — core2026이 타입안전 + DI + constraint 분리로 우수.

### B4. 국가 커맨드 (Nation Commands) — 38개

| 커맨드 | 레거시 (PHP) | core2026 (TS) | 현재 (Kotlin) | 패러티 소스 |
|---|---|---|---|---|
| 휴식 | `Command/Nation/휴식.php` | `actions/turn/nation/휴식.ts` | `command/nation/Nation휴식.kt` | `[C]` |
| 감축 | `che_감축.php` | `che_감축.ts` | `che_감축.kt` | `[C]` |
| 국기변경 | `che_국기변경.php` | `che_국기변경.ts` | `che_국기변경.kt` | `[C]` |
| 국호변경 | `che_국호변경.php` | `che_국호변경.ts` | `che_국호변경.kt` | `[C]` |
| 급습 | `che_급습.php` | `che_급습.ts` | `che_급습.kt` | `[C]` |
| 몰수 | `che_몰수.php` | `che_몰수.ts` | `che_몰수.kt` | `[C]` |
| 무작위수도이전 | `che_무작위수도이전.php` | `che_무작위수도이전.ts` | `che_무작위수도이전.kt` | `[C]` |
| 물자원조 | `che_물자원조.php` | `che_물자원조.ts` | `che_물자원조.kt` | `[C]` |
| 발령 | `che_발령.php` | `che_발령.ts` | `che_발령.kt` | `[C]` |
| 백성동원 | `che_백성동원.php` | `che_백성동원.ts` | `che_백성동원.kt` | `[C]` |
| 부대탈퇴지시 | `che_부대탈퇴지시.php` | `che_부대탈퇴지시.ts` | `che_부대탈퇴지시.kt` | `[C]` |
| 불가침수락 | `che_불가침수락.php` | `instant/nation/che_불가침수락.ts` | `che_불가침수락.kt` | `[C]` |
| 불가침제의 | `che_불가침제의.php` | `che_불가침제의.ts` | `che_불가침제의.kt` | `[C]` |
| 불가침파기수락 | `che_불가침파기수락.php` | `instant/nation/che_불가침파기수락.ts` | `che_불가침파기수락.kt` | `[C]` |
| 불가침파기제의 | `che_불가침파기제의.php` | `che_불가침파기제의.ts` | `che_불가침파기제의.kt` | `[C]` |
| 선전포고 | `che_선전포고.php` | `che_선전포고.ts` | `che_선전포고.kt` | `[C]` |
| 수몰 | `che_수몰.php` | `che_수몰.ts` | `che_수몰.kt` | `[C]` |
| 의병모집 | `che_의병모집.php` | `che_의병모집.ts` | `che_의병모집.kt` | `[C]` |
| 이호경식 | `che_이호경식.php` | `che_이호경식.ts` | `che_이호경식.kt` | `[C]` |
| 종전수락 | `che_종전수락.php` (❌ 파일 없음) | `instant/nation/che_종전수락.ts` | `che_종전수락.kt` | `[C]` |
| 종전제의 | `che_종전제의.php` | `che_종전제의.ts` | `che_종전제의.kt` | `[C]` |
| 증축 | `che_증축.php` | `che_증축.ts` | `che_증축.kt` | `[C]` |
| 천도 | `che_천도.php` | `che_천도.ts` | `che_천도.kt` | `[C]` |
| 초토화 | `che_초토화.php` | `che_초토화.ts` | `che_초토화.kt` | `[C]` |
| 포상 | `che_포상.php` | `che_포상.ts` | `che_포상.kt` | `[C]` |
| 피장파장 | `che_피장파장.php` | `che_피장파장.ts` | `che_피장파장.kt` | `[C]` |
| 필사즉생 | `che_필사즉생.php` | `che_필사즉생.ts` | `che_필사즉생.kt` | `[C]` |
| 허보 | `che_허보.php` | `che_허보.ts` | `che_허보.kt` | `[C]` |
| cr_인구이동 | `cr_인구이동.php` | `cr_인구이동.ts` | `cr_인구이동.kt` | `[C]` |
| event_극병연구 | `event_극병연구.php` | `event_극병연구.ts` | `event_극병연구.kt` | `[C]` |
| event_대검병연구 | `event_대검병연구.php` | `event_대검병연구.ts` | `event_대검병연구.kt` | `[C]` |
| event_무희연구 | `event_무희연구.php` | `event_무희연구.ts` | `event_무희연구.kt` | `[C]` |
| event_산저병연구 | `event_산저병연구.php` | `event_산저병연구.ts` | `event_산저병연구.kt` | `[C]` |
| event_상병연구 | `event_상병연구.php` | `event_상병연구.ts` | `event_상병연구.kt` | `[C]` |
| event_원융노병연구 | `event_원융노병연구.php` | `event_원융노병연구.ts` | `event_원융노병연구.kt` | `[C]` |
| event_음귀병연구 | `event_음귀병연구.php` | `event_음귀병연구.ts` | `event_음귀병연구.kt` | `[C]` |
| event_화륜차연구 | `event_화륜차연구.php` | `event_화륜차연구.ts` | `event_화륜차연구.kt` | `[C]` |
| event_화시병연구 | `event_화시병연구.php` | `event_화시병연구.ts` | `event_화시병연구.kt` | `[C]` |

> **국가 커맨드 패러티**: 레거시 38 = core2026 37 (instant 3개 분리) = 현재 38. 모든 커맨드 `[C]`.

### B5. 엔진/턴 시스템

| 현재 (Kotlin) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `engine/TurnDaemon.kt` | `hwe/proc.php` + `sammo/TurnExecutionHelper.php` | `game-engine/turn/turnDaemon.ts` + `lifecycle/turnDaemonLifecycle.ts` | `[C]` — 라이프사이클 분리 |
| `engine/TurnService.kt` | `hwe/proc.php` (턴 처리) | `game-engine/turn/reservedTurnHandler.ts` + `inMemoryTurnProcessor.ts` | `[C]` — in-memory 프로세서 |
| `engine/EconomyService.kt` | `hwe/func.php` (경제 파트) | `logic/economy/nationIncome.ts` + `game-engine/turn/incomeHandler.ts` | `[C]` |
| `engine/DiplomacyService.kt` | `hwe/sammo/DiplomaticMessage.php` | `logic/diplomacy/` | `[L+C]` |
| `engine/EventService.kt` | `hwe/sammo/Event/*.php` + `StaticEvent/*.php` + `StaticEventHandler.php` | — | `[L]` — 이벤트 시스템이 core2026에 미완 |
| `engine/DistanceService.kt` | `hwe/func_map.php` (거리 계산) | `logic/world/distance.ts` | `[C]` |
| `engine/NpcSpawnService.kt` | `hwe/sammo/ResetHelper.php` (NPC 스폰) | — | `[L]` |
| `engine/RealtimeService.kt` | (WebSocket) | `game-api/realtime/` | `[C]` |
| `engine/SpecialAssignmentService.kt` | `hwe/sammo/SpecialityHelper.php` | `logic/triggers/special/` | `[C]` — 특기별 분리 |
| `engine/GeneralMaintenanceService.kt` | `hwe/sammo/General.php` (유지보수) | — | `[L]` |
| `engine/UnificationService.kt` | (inline in proc.php) | `game-engine/turn/unificationHandler.ts` | `[C]` |
| `engine/UniqueLotteryService.kt` | (inline in func.php) | `logic/rewards/uniqueLottery.ts` | `[C]` |
| `engine/YearbookService.kt` | (inline in proc.php) | `game-engine/turn/yearbookHandler.ts` | `[C]` |
| `engine/TournamentBattle.kt` | `hwe/func_tournament.php` | `logic/tournament/battle.ts` + `game-engine/tournament/` | `[C]` |

### B6. 전투/전쟁 시스템

| 현재 (Kotlin) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `engine/war/BattleEngine.kt` | `hwe/process_war.php` | `logic/war/engine.ts` | `[C]` |
| `engine/war/BattleService.kt` | `hwe/process_war.php` (서비스 레이어) | `logic/war/actions.ts` | `[C]` |
| `engine/war/BattleTrigger.kt` | `hwe/sammo/WarUnitTrigger/*.php` | `logic/war/triggers.ts` + `triggers/*.ts` (17개) | `[C]` |
| `engine/war/WarFormula.kt` | `hwe/process_war.php` (공식) | `logic/war/utils.ts` | `[C]` |
| `engine/war/WarUnit.kt` | `hwe/sammo/WarUnit.php` | `logic/war/units.ts` + `units/base.ts` | `[C]` |
| `engine/war/WarUnitGeneral.kt` | `hwe/sammo/WarUnitGeneral.php` | `logic/war/units/general.ts` | `[C]` |
| `engine/war/WarUnitCity.kt` | `hwe/sammo/WarUnitCity.php` | `logic/war/units/city.ts` | `[C]` |
| `engine/war/WarAftermath.kt` | `hwe/process_war.php` (전후 처리) | `logic/war/aftermath.ts` | `[C]` |
| — | `hwe/sammo/GameUnitDetail.php` | `logic/war/crewType.ts` + `logic/war/types.ts` | — |

### B7. AI / NPC 시스템

| 현재 (Kotlin) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `engine/ai/GeneralAI.kt` | `hwe/sammo/GeneralAI.php` | `game-engine/turn/ai/generalAi.ts` + `generalAi/*.ts` (7개) | `[C]` — 모듈 분리 우수 |
| `engine/ai/NationAI.kt` | `hwe/sammo/AutorunNationPolicy.php` | `game-engine/turn/ai/generalAiNationActions.ts` | `[C]` |
| `engine/ai/NpcPolicy.kt` | `hwe/sammo/AutorunGeneralPolicy.php` | `game-engine/turn/ai/policies.ts` | `[C]` |
| `engine/ai/AIContext.kt` | (inline) | `game-engine/turn/ai/types.ts` + `aiUtils.ts` | `[C]` |
| `engine/ai/DiplomacyState.kt` | (inline) | `game-engine/turn/ai/generalAi/worldStateView.ts` | `[C]` |

### B8. 제약조건 (Constraints)

| 현재 (Kotlin) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `command/constraint/Constraint.kt` | `sammo/Constraint/Constraint.php` (base) | `logic/constraints/types.ts` | `[C]` |
| `command/constraint/ConstraintChain.kt` | `sammo/Constraint/ConstraintHelper.php` | `logic/constraints/evaluate.ts` | `[C]` |
| `command/constraint/ConstraintHelper.kt` | `sammo/Constraint/ConstraintHelper.php` | `logic/constraints/helpers.ts` + `presets.ts` | `[C]` |
| — | `sammo/Constraint/*.php` (73개 개별) | `logic/constraints/` (11개 모듈) | — |

> **Constraint**: 레거시 73개 개별 파일 vs core2026 11개 모듈로 통합. core2026이 더 관리 용이.

### B9. 트리거/특기 시스템

| 현재 (Kotlin) | 레거시 (PHP) | core2026 (TS) | 패러티 소스 |
|---|---|---|---|
| `engine/trigger/GeneralTrigger.kt` | `sammo/BaseGeneralTrigger.php` + `GeneralTrigger/*.php` + `GeneralTriggerCaller.php` | `logic/triggers/general.ts` + `general-action.ts` + `generalTriggers/` | `[C]` |
| `engine/trigger/TriggerCaller.kt` | `sammo/TriggerCaller.php` + `ObjectTrigger.php` | `logic/triggers/core.ts` + `index.ts` | `[C]` |
| `engine/modifier/SpecialModifiers.kt` | `sammo/BaseSpecial.php` + `ActionSpecialDomestic/` + `ActionSpecialWar/` | `logic/triggers/special/domestic/` (9개) + `war/` (22개) + `nation/` (15개) + `personality/` (12개) | `[C]` — 58개 특기 개별 분리 |
| `engine/modifier/ItemModifiers.kt` | `sammo/BaseItem.php` + `BaseStatItem.php` + `ActionItem/` | `logic/items/*.ts` (120+개) | `[C]` — 아이템 120+개 |
| `engine/modifier/PersonalityModifiers.kt` | `sammo/ActionPersonality/` | `logic/triggers/special/personality/` (12개) | `[C]` |
| `engine/modifier/NationTypeModifiers.kt` | `sammo/ActionNationType/` | `logic/triggers/special/nation/` (15개) | `[C]` |
| `engine/modifier/InheritBuffModifier.kt` | `sammo/TriggerInheritBuff.php` | `logic/inheritance/inheritBuff.ts` | `[C]` |
| `engine/modifier/ActionModifier.kt` | (inline) | — | `[L]` |
| `engine/modifier/ModifierService.kt` | (inline) | — | `[L]` |
| `engine/modifier/TraitSelector.kt` + `TraitSpec.kt` | `sammo/SpecialityHelper.php` | `logic/triggers/special/selector.ts` + `requirements.ts` | `[C]` |
| `engine/CrewTypeAvailability.kt` | `sammo/GameUnitConstraint/` + `ActionCrewType/` | `logic/war/crewType.ts` | `[C]` |
| `engine/DeterministicRng.kt` | `legacy/src/sammo/LiteHashDRBG.php` + `RNG.php` | `common/util/LiteHashDRBG.ts` + `RNG.ts` | `[C]` |

### B10. 엔티티/도메인 모델

| 현재 (JPA Entity) | 레거시 (PHP/DB) | core2026 (Prisma) | 패러티 소스 |
|---|---|---|---|
| `entity/General.kt` | `sammo/General.php` + `GeneralBase.php` | `infra/prisma/game.prisma` (General model) + `logic/domain/entities.ts` | `[C]` |
| `entity/Nation.kt` | `sammo/BaseNation.php` | `infra/prisma/game.prisma` (Nation model) | `[C]` |
| `entity/City.kt` | `sammo/CityConstBase.php` + `CityHelper.php` | `infra/prisma/game.prisma` (City model) | `[L+C]` |
| `entity/AppUser.kt` | (gateway DB) | `infra/prisma/gateway.prisma` (User model) | `[C]` |
| `entity/WorldState.kt` | `sammo/ServerEnv.php` | `logic/ports/world.ts` + `worldSnapshot.ts` | `[C]` |
| `entity/Auction.kt` + `AuctionBid.kt` | `sammo/Auction.php` | `infra/prisma/game.prisma` | `[L]` |
| `entity/Betting.kt` + `BetEntry.kt` | `sammo/Betting.php` | `infra/prisma/game.prisma` | `[C]` |
| `entity/Board.kt` + `BoardComment.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Diplomacy.kt` | `sammo/DiplomaticMessage.php` | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Emperor.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Event.kt` | `sammo/BaseStaticEvent.php` | — | `[L]` |
| `entity/GameHistory.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/GeneralAccessLog.kt` | (DB table) | `infra/prisma/game.prisma` | `[L]` |
| `entity/GeneralRecord.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/GeneralTurn.kt` | `sammo/LastTurn.php` | `infra/prisma/game.prisma` | `[C]` |
| `entity/HallOfFame.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Message.kt` | `sammo/Message.php` | `infra/prisma/game.prisma` | `[L]` |
| `entity/NationFlag.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/NationTurn.kt` | (DB table) | `infra/prisma/game.prisma` | `[C]` |
| `entity/OldGeneral.kt` + `OldNation.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/RankData.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Tournament.kt` | (DB table) | `infra/prisma/game.prisma` | `[C]` |
| `entity/Troop.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/Vote.kt` + `VoteCast.kt` | (DB table) | `infra/prisma/game.prisma` | `[L+C]` |
| `entity/WorldHistory.kt` | (DB table) | `infra/prisma/game.prisma` | `[C]` |
| `entity/YearbookHistory.kt` | (DB table) | `infra/prisma/game.prisma` | `[C]` |

### B11. 리소스/데이터

| 현재 | 레거시 | core2026 | 패러티 소스 |
|---|---|---|---|
| `resources/data/game_const.json` | `sammo/GameConstBase.php` + `CityConstBase.php` | `resources/unitset/*.json` (7개) + `resources/schema/*.json` | `[C]` — JSON 스키마 |
| `resources/data/officer_ranks.json` | `sammo/TriggerOfficerLevel.php` | — | `[L]` |
| `resources/data/maps/*.json` | `hwe/scenario/map/*.php` | `resources/map/*.json` (9개) | `[C]` — 9개 맵 JSON |
| `resources/data/scenarios/` | `hwe/scenario/*.json` (24개) + `sammo/Scenario.php` | `resources/scenario/*.json` (82개!) | `[C]` — 82개 시나리오 |
| — | `resources/turn-commands/` | `resources/turn-commands/default.json` | `[C]` |

---

## C. 매핑 없는 파일 (Gap 분석)

### C1. 레거시에만 있는 것 (현재 미구현)

| 레거시 파일 | 설명 | 중요도 |
|---|---|---|
| `sammo/GlobalMenu.php` | 글로벌 메뉴 생성기 | 🟡 (다른 네비 패턴) |
| `hwe/func_gamerule.php` | 게임 규칙 표시 | 🟡 |
| `hwe/func_time_event.php` | 시간 이벤트 처리 | 🔴 |
| `hwe/func_legacy.php` | 레거시 호환 함수 | ⚪ (불필요) |
| `hwe/func_string.php` | 문자열 유틸 | ⚪ (Kotlin stdlib) |
| `hwe/func_template.php` | 템플릿 유틸 | ⚪ (불필요) |
| `hwe/func_converter.php` | 데이터 변환 유틸 | ⚪ |
| `sammo/UserLogger.php` | 유저 로거 | 🟡 |
| `sammo/ActionLogger.php` | 액션 로거 | 🔴 |
| `sammo/TextDecoration/` | 텍스트 장식 | 🟡 |
| `sammo/AbsFromUserPool.php` + `AbsGeneralPool.php` + `GeneralPool/` | 장수 풀 시스템 | 🔴 |
| `sammo/DefaultAction.php` | 기본 액션 정의 | 🟡 |
| `sammo/LazyVarUpdater.php` + `LazyVarAndAuxUpdater.php` | 지연 변수 업데이터 | 🟡 |
| `sammo/RaiseInvaderMessage.php` | 이민족 침입 메시지 | 🔴 |
| `hwe/j_autoreset.php` | 자동 리셋 | 🟡 |
| `hwe/j_vacation.php` | 휴가 모드 | 🟡 |
| `hwe/j_adjust_icon.php` | 아이콘 조정 | ⚪ |
| `hwe/j_general_set_permission.php` | 권한 설정 | 🔴 |
| `hwe/j_set_my_setting.php` | 개인 설정 | 🟡 |
| `hwe/j_general_log_old.php` | 과거 로그 조회 | 🟡 |
| `hwe/ts/components/AutorunInfo.vue` | 자동실행 정보 표시 | 🔴 |
| `hwe/ts/legacy/` | 레거시 호환 코드 | ⚪ |

### C2. core2026에만 있는 것 (현재 미구현)

| core2026 파일 | 설명 | 중요도 |
|---|---|---|
| `logic/logging/*.ts` (6개) | 구조화된 액션/유저 로깅 | 🔴 |
| `logic/items/*.ts` (120+개) | 아이템 개별 효과 파일 | 🔴 |
| `logic/triggers/special/domestic/` (9개) | 내정 특기 효과 | 🔴 |
| `logic/triggers/special/war/` (22개) | 전투 특기 효과 | 🔴 |
| `logic/triggers/special/nation/` (15개) | 국가 유형 효과 | 🔴 |
| `logic/triggers/special/personality/` (12개) | 성격 효과 | 🔴 |
| `game-engine/lifecycle/` (6개) | 턴 데몬 라이프사이클 관리 | 🟡 |
| `game-engine/turn/inMemory*.ts` | 인메모리 상태 관리 | 🟡 (다른 아키텍처) |
| `gateway-api/orchestrator/` (10개) | 서버 오케스트레이션 | 🟡 (단일 앱 아키텍처) |
| `common/ranking/` | 랭킹 유틸 | 🟡 |
| `common/realtime/` | 실시간 공통 유틸 | 🟡 |
| `resources/turn-commands/default.json` | 기본 턴 커맨드 프로파일 | 🔴 |
| `game-frontend/views/PublicView.vue` | 공개 뷰 | 🟡 |
| `game-frontend/views/MySettingsView.vue` | 개인 설정 뷰 | 🟡 |
| `game-frontend/views/ScoutMessageView.vue` | 첩보 메시지 뷰 | 🔴 |
| `game-frontend/components/main/SelectedCityPanel.vue` | 선택 도시 패널 | 🟡 |

### C3. 현재에만 있는 것 (패러티 소스 없음)

| 현재 파일 | 설명 | 비고 |
|---|---|---|
| `frontend/components/game/dev-bar.tsx` | 개발용 바 | 개발 전용 |
| `frontend/components/game/empty-state.tsx` | 빈 상태 UI | UI 유틸 |
| `frontend/components/game/loading-state.tsx` | 로딩 상태 UI | UI 유틸 |
| `frontend/components/game/konva-map-canvas.tsx` | Konva 기반 지도 | 기술 차이 (레거시=Canvas 직접) |
| `backend/engine/modifier/TraitSelector.kt` + `TraitSpec.kt` | 특성 선택기 | 아키텍처 차이 |

---

## D. 패러티 소스 요약 통계

| 도메인 | `[L]` 레거시 | `[C]` core2026 | `[L+C]` 둘 다 | 합계 |
|---|---|---|---|---|
| 프론트엔드 페이지 | **전부** | (참조만) | — | 40+ 페이지 |
| 컨트롤러 | 8 | 10 | 8 | 26 |
| 서비스 | 10 | 12 | 4 | 26 |
| 장수 커맨드 | 0 | **55** | 0 | 55 |
| 국가 커맨드 | 0 | **38** | 0 | 38 |
| 엔진/턴 | 3 | **10** | 1 | 14 |
| 전투 시스템 | 0 | **8** | 0 | 8 |
| AI/NPC | 0 | **5** | 0 | 5 |
| 제약조건 | 0 | **3** | 0 | 3 |
| 트리거/특기 | 2 | **10** | 0 | 12 |
| 엔티티 | 4 | 6 | **12** | 22 |
| 리소스/데이터 | 1 | **4** | 0 | 5 |

> **결론**: 프론트엔드는 100% 레거시 기준. 백엔드는 ~75% core2026 기준 (커맨드/엔진/전투/AI/트리거 전부 core2026이 우수).

---

## E. 프론트엔드 콘텐츠 패러티 Diff (레거시 PHP vs Next.js)

> 분석 일자: 2026-02-27
> 레거시 PHP 20+ 페이지 vs Next.js 38개 게임 페이지 전수 비교
> 패러티 달성=14, 아키텍처 차이=4, 갭 발견=4건, Next.js 전용=13

### E1. 패러티 달성 (14 페이지)

| Next.js | 레거시 PHP | 비고 |
|---|---|---|
| general/ | b_myGenInfo.php | Next.js가 더 풍부 (프로필+전투기록+장비+숙련도) |
| my-page/ | b_myGenInfo.php 확장 | 4탭 info/battle/settings/log |
| nation/ | b_myKingdomInfo.php | 4탭 + 수입/지출/예산 계산 |
| map/ | v_cachedMap.php | SVG + 테마/레이어/히스토리 |
| commands/ | v_processing.php | 드래그앤드롭 + 프리셋 |
| battle-center/ | v_battleCenter.php | 비교 모드 추가 |
| board/ | v_board.php | 공개/기밀 탭, 댓글 동등 |
| messages/ | MessagePanel.vue | 4 우편함 탭. WS auto-refresh |
| troop/ | v_troop.php | 턴 브리프 + 커맨드 타임라인 추가 |
| auction/ | v_auction.php | 리소스/유니크 탭 동등 |
| best-generals/ | a_bestGeneral.php | 5그룹 탭 Top50 동등 |
| hall-of-fame/ | a_hallOfFame.php | 카드/표 뷰 모드 추가 |
| diplomacy/ | v_globalDiplomacy.php | 3탭 + 외교 서신 워크플로우 |
| history/ | v_history.php | 연대기 + 맵 스냅샷 + 검색/필터 |

### E2. 아키텍처 차이 (허용, 4 페이지)

| Next.js | 레거시 | 차이 |
|---|---|---|
| chief/ + superior/ | v_chiefCenter.php | 사령부/인사부 2페이지 분리 |
| emperor/ + dynasty/ | a_emperior.php | 황제/왕조 2페이지 분리 |
| inherit/ | v_inheritPoint.php | 6탭 구조 재편 |
| betting/ | (없음) | Next.js 전용 신규 기능 |

### E3. 갭 발견 (수정 필요, 4건)

P0: vote 페이지 - 댓글(comments) 섹션 누락
- 레거시: 투표마다 댓글 테이블 + 입력 폼
- Next.js: 결과 + 투표 버튼만. 댓글 없음

P1: generals 페이지 - 요약 통계 행 누락
- 레거시: 총 금/쌀, 병력 분포 (훈련 90+/80+/60+), 평균 자금/군량
- Next.js: 필터/소트 + 테이블만

P1: nations 페이지 - 관직 10-5 및 사신/감찰 미표시
- 레거시: officers 12-5 전원 + ambassadors/auditors
- Next.js: chief(12)/advisor(11)만 표시

P2: city 페이지 - 병력 준비도 요약 누락
- 레거시: 적/아/90+/60+/수비가능 카운트
- Next.js: 장수 이름 목록 + 총 주둔병력만

### E4. Next.js 전용 (13 페이지)

battle-simulator, battle, internal-affairs, nation-cities,
nation-generals, npc-control, npc-list, personnel, spy,
tournament, traffic, betting, processing
