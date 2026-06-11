# Read-API Surface Regrade — 2026-06-12

영역: `legacy/devsam-core/hwe/sammo/API/**/*.php` (81 엔드포인트) ↔ `app/game-api` 컨트롤러 전수 매핑.
READ-ONLY 실측 — 소스 수정 없음. 모든 인용은 실제 읽은 file:line.

## 판정 기준

- **OK**: PHP 엔드포인트의 기능 표면이 game-api 엔드포인트(REST 또는 `/api/command/{code}` intake)로 도달 가능하고, 명백한 차단 요인이 없음. (deep 행동 패러티는 본 regrade 범위 밖 — 표면 매핑 기준.)
- **PARTIAL**: 엔드포인트는 존재하나 응답 필드 BLOCKED/빈 배열, FE-BE 계약 버그, 또는 기능 일부만 커버.
- **MISSING**: 도달 가능한 구현 부재. 컨트롤러 셸이 있어도 무조건 409(미배선)면 MISSING으로 분류.
- intake 명령(`/api/command/{code}`)은 `CommandWireMapper.intakeCodes`(CommandWireMapper.kt:43-91) 등재 + 엔진 핸들러 존재를 확인해야 OK.
- severity: **P0** = FE가 실제 호출하는데 항상 실패하거나 오염 mutation, **P1** = 기능 부재(패러티 갭, FE 미호출 포함), **P2** = 부분/정책적 divergence/저위험.

## 전수 매핑 표 (81 엔드포인트: OK 58 / PARTIAL 12 / MISSING 11)

### Auction (9)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Auction/BidBuyRiceAuction | POST /api/command/auctionBid (CommandWireMapper.kt:45,135) | OK | — |
| Auction/BidSellRiceAuction | POST /api/command/auctionBid (동일 intake, auctionId로 식별) | OK | — |
| Auction/BidUniqueAuction | POST /api/command/auctionBid (tryExtendCloseDate 포함, CommandWireMapper.kt:140) | OK | — |
| Auction/GetActiveResourceAuctionList | GET /api/auctions (AuctionController.kt:68) | OK | — |
| Auction/GetUniqueItemAuctionDetail | GET /api/auctions/{id}/unique-detail (AuctionController.kt:139) | OK | — |
| Auction/GetUniqueItemAuctionList | GET /api/auctions/unique (AuctionController.kt:102) | OK | — |
| Auction/OpenBuyRiceAuction | POST /api/command/auctionOpenBuyRice (CommandWireMapper.kt:78) | OK | — |
| Auction/OpenSellRiceAuction | POST /api/command/auctionOpenSellRice (CommandWireMapper.kt:79) | OK | — |
| Auction/OpenUniqueAuction | POST /api/command/auctionOpenUnique (CommandWireMapper.kt:80, FE api.ts:490-494) | OK | — |

### Betting (3)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Betting/Bet | POST /api/command/placeBet (CommandWireMapper.kt:44,128) — 핸들러 검증 간이화 | PARTIAL | **P0** (F-1) |
| Betting/GetBettingDetail | GET /api/bettings/{bettingId}/detail (BettingController.kt:147) | OK | — |
| Betting/GetBettingList | GET /api/bettings (BettingController.kt:80) | OK | — |

### Command (5)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Command/GetReservedCommand | GET /api/reserved-commands (ReservedCommandsController.kt:79) | OK | — |
| Command/PushCommand | POST /api/command/push (CommandController.kt:134) | OK | — |
| Command/RepeatCommand | POST /api/command/repeat (CommandController.kt:147) | OK | — |
| Command/ReserveBulkCommand | POST /api/command/bulk (CommandController.kt:123) | OK | — |
| Command/ReserveCommand | POST /api/command/{code} (CommandController.kt:55, precheck→reserve) | OK | — |

### General (8)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| General/BuildNationCandidate | 컨트롤러 부재 + 엔진 핸들러 스텁(`"미구현"`, BuildNationCandidateHandler.kt:29-31) | MISSING | **P1** (F-4) |
| General/DieOnPrestart | /api/instant-action/DieOnPrestart — intakeCodes 미등재 → 무조건 409 | MISSING | **P1** (F-5) |
| General/DropItem | /api/instant-action/DropItem — 동일 409 | MISSING | **P1** (F-5) |
| General/GetCommandTable | GET /api/commands/available (AvailableCommandsController.kt:74) | OK | — |
| General/GetFrontInfo | GET /api/front-info (FrontInfoController.kt:91) — recentRecord=emptyList BLOCKED | PARTIAL | P1 (F-8 연동) |
| General/GetGeneralLog | GET /api/general-log (GeneralLogController.kt:46, reqType 4종 + 권한 게이트) | OK | — |
| General/InstantRetreat | /api/instant-action/InstantRetreat — 동일 409 | MISSING | **P1** (F-5) |
| General/Join | POST /api/join (JoinController.kt:57, MakeGeneralHandler) | OK | — |

### Global (12)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Global/ExecuteEngine | 공개 턴실행 endpoint 없음 — 데몬 자율 케이던스 + engine StatusController(/admin/turn-daemon/{status,pause,resume}, StatusController.kt:48-87) | MISSING | P2 (F-13, 의도적 divergence) |
| Global/GeneralList | GET /api/generals (GeneralsController.kt:42) | OK | — |
| Global/GeneralListWithToken | 전용 endpoint 부재 — GET /api/generals/claimable (PossessionController.kt:40-45)이 토큰 풀 일부 대체 | PARTIAL | P2 (F-12) |
| Global/GetCachedMap | GET /api/map (WorldMapController.kt:50; 캐시 변형은 비분리) | OK | — |
| Global/GetConst | GET /api/const (GetConstController.kt:41) | OK | — |
| Global/GetCurrentHistory | GET /api/history — 라이브 현재 스냅샷 미합성(last 월로 클램프) | PARTIAL | P2 (F-9) |
| Global/GetDiplomacy | GET /api/diplomacy/{nationId} (DiplomacyController.kt:43, neutral-map 마스킹 완료) | OK | — |
| Global/GetGlobalMenu | GET /api/global-menu (GlobalMenuController.kt:26) | OK | — |
| Global/GetHistory | GET /api/history (HistoryController.kt:38) — globalHistory/globalAction 빈 배열 BLOCKED | PARTIAL | P2 (F-9) |
| Global/GetMap | GET /api/map (WorldMapController.kt:50) + /api/map/preview | OK | — |
| Global/GetNationList | 전용 endpoint 부재 — /api/rankings/kingdoms(RankingController.kt:47) + /kingdom-roster(:55)로 분산 커버 | PARTIAL | P2 (F-11) |
| Global/GetRecentRecord | 증분 폴링(lastGeneralRecordID/lastWorldHistoryID) endpoint 부재 | MISSING | **P1** (F-8) |

### InheritAction (8)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| InheritAction/BuyHiddenBuff | POST /api/command/BuyHiddenBuff (CommandWireMapper.kt:58) — FE 버튼 guaranteed-fail 400 (P0-50) | PARTIAL | **P0** (F-3) |
| InheritAction/BuyRandomUnique | POST /api/command/BuyRandomUnique (CommandWireMapper.kt:59) — 동일 P0-50 | PARTIAL | **P0** (F-3) |
| InheritAction/CheckOwner | /api/instant-action/CheckOwner — intakeCodes 미등재 + wire variant 자체 부재 → 409 | MISSING | **P1** (F-6) |
| InheritAction/GetMoreLog | 페이징 endpoint 부재 — /api/inherit-point는 최근 30건 고정 | MISSING | P2 (F-10) |
| InheritAction/ResetSpecialWar | POST /api/command/inheritResetSpecialWar (CommandWireMapper.kt:55, InheritResetHandler) | OK | — |
| InheritAction/ResetStat | /api/instant-action/ResetStat — FE가 실제 호출하는데 무조건 409 | MISSING | **P0** (F-2) |
| InheritAction/ResetTurnTime | POST /api/command/inheritResetTurnTime (CommandWireMapper.kt:54) | OK | — |
| InheritAction/SetNextSpecialWar | POST /api/command/inheritSetNextSpecialWar (CommandWireMapper.kt:56) | OK | — |

### Message (7)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Message/DecideMessageResponse | POST /api/messages/{id}/accept · /decline (DiplomaticMessageController.kt:55,124; P0-35 권한 게이트 FIXED) | OK | — |
| Message/DeleteMessage | POST /api/command/deleteMessage (CommandWireMapper.kt:76,254) | OK | — |
| Message/GetContactList | GET /api/contacts (ContactController.kt:34) | OK | — |
| Message/GetOldMessage | GET /api/mailbox/old (MailboxController.kt:178) | OK | — |
| Message/GetRecentMessage | GET /api/mailbox/recent (MailboxController.kt:94) — latestRead=(0,0) BLOCKED | PARTIAL | P2 (F-7) |
| Message/ReadLatestMessage | latestRead 커서 endpoint 부재(general_stor 미구현) | MISSING | P2 (F-7) |
| Message/SendMessage | POST /api/command/sendMessage (BE 존재: CommandWireMapper.kt:75 + MessageHandler.kt:51) — FE 발송 호출 부재 | PARTIAL | **P1** (F-0, P0-32 재판정) |

### Misc (1)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Misc/UploadImage | 구현 전무 (app/·web/ 전체 grep 0건) | MISSING | **P1** (F-14) |

### Nation (11)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Nation/GeneralList | GET /api/nation/general-list (GeneralListController.kt:60) | OK | — |
| Nation/GetGeneralLog | GET /api/general-log (GeneralLogController.kt:46-69 — PHP 동일 permission/reqType 게이트) | OK | — |
| Nation/GetNationInfo | GET /api/my-nation-detail (MyController.kt:219) — taxRate/bill meta 방어 read(부재 시 null) | PARTIAL | P2 (F-15) |
| Nation/SetBill | POST /api/command/setBill (CommandWireMapper.kt:49,151, NationFinanceSetterHandler) | OK | — |
| Nation/SetBlockScout | POST /api/command/setBlockScout (CommandWireMapper.kt:52) | OK | — |
| Nation/SetBlockWar | POST /api/command/setBlockWar (CommandWireMapper.kt:51) | OK | — |
| Nation/SetNotice | POST /api/command/setNotice (CommandWireMapper.kt:46,142) | OK | — |
| Nation/SetRate | POST /api/command/setRate (CommandWireMapper.kt:48,148) | OK | — |
| Nation/SetScoutMsg | POST /api/command/setScoutMsg (CommandWireMapper.kt:47,145) | OK | — |
| Nation/SetSecretLimit | POST /api/command/setSecretLimit (CommandWireMapper.kt:50) | OK | — |
| Nation/SetTroopName | POST /api/command/troopSetName (CommandWireMapper.kt:65; Troop/SetTroopName과 단일 intake로 병합) | OK | — |

### NationCommand (5)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| NationCommand/GetReservedCommand | GET /api/nation/chief-reserved (ChiefCenterController.kt:65) | OK | — |
| NationCommand/PushCommand | POST /api/command/nation/push (CommandController.kt:173, PHP 순서 보존 amount==0 게이트:183) | OK | — |
| NationCommand/RepeatCommand | POST /api/command/nation/repeat (CommandController.kt:190) | OK | — |
| NationCommand/ReserveBulkCommand | POST /api/command/nation/bulk (CommandController.kt:160, reserveBulkNation → nation_turn) | OK | — |
| NationCommand/ReserveCommand | 단건 전용 endpoint 없음 — FE는 nation/bulk 단건 호출로 등가 사용(chief-center/page.tsx:8) | PARTIAL | P2 (F-16) |

### Troop (5)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Troop/ExitTroop | POST /api/command/troopExit (CommandWireMapper.kt:63, TroopHandler) | OK | — |
| Troop/JoinTroop | POST /api/command/troopJoin (CommandWireMapper.kt:62) | OK | — |
| Troop/KickFromTroop | POST /api/command/troopKick (CommandWireMapper.kt:64) | OK | — |
| Troop/NewTroop | POST /api/command/troopNew (CommandWireMapper.kt:61) | OK | — |
| Troop/SetTroopName | POST /api/command/troopSetName (CommandWireMapper.kt:65) | OK | — |

### Vote (5)

| PHP API | Kotlin endpoint | status | severity |
|---|---|---|---|
| Vote/AddComment | POST /api/command/voteComment (CommandWireMapper.kt:72, VoteHandler) | OK | — |
| Vote/GetVoteDetail | GET /api/votes/{id} (VoteController.kt:61) | OK | — |
| Vote/GetVoteList | GET /api/votes (VoteController.kt:40) | OK | — |
| Vote/NewVote | POST /api/command/newVote (CommandWireMapper.kt:70) | OK | — |
| Vote/Vote | POST /api/command/voteCast (CommandWireMapper.kt:71) | OK | — |

## Finding 목록

### F-2 [P0] InheritAction/ResetStat — FE가 실제 호출하는 경로가 무조건 409

- legacy: `hwe/sammo/API/InheritAction/ResetStat.php:22-59` (validateArgs 스탯 합 검증 + launch 유산 차감/재분배).
- 현재 impl: FE `web/game/lib/api.ts:405` → `POST /api/instant-action/ResetStat`, 호출처 `web/game/app/game/inherit/page.tsx:214` (`api.resetStat(args, generalId)`).
- 차단점 3중: ① `CommandWireMapper.intakeCodes`(CommandWireMapper.kt:43-91)에 `ResetStat` 없음 → `InstantActionController.kt:97-100`의 FOUNDATION 가드가 **409 "아직 배선되지 않은 즉시 액션입니다."** 반환. ② `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`에 ResetStat wire variant 부재(grep 0건). ③ 엔진 dispatcher(`TurnDaemonCommandDispatcher.kt`) case 부재.
- 즉, **유산 능력치 재분배 버튼이 라이브에서 항상 실패**. 백로그 항목 그대로 미해소.

### F-1 [P0] Betting/Bet — placeBet intake는 존재하나 핸들러 검증·부수효과 비패러티

- legacy: `hwe/sammo/API/Betting/Bet.php` → `Betting::bet` (개장/마감 연월, 중복 베팅, bettingType 카운트, 유산-베팅 분기 등 전체 검증 + 패러티 deny 문자열).
- 현재 impl: `app/game-engine/src/main/kotlin/opensamguk/engine/betting/PlaceBetHandler.kt:34-50` — 장수 존재/amount>0/금 보유 3종 간이 검증만, deny 문자열 비패러티(`"베팅 금액은 0보다 커야 합니다."`, PlaceBetHandler.kt:45 — PHP에 없는 문자열).
- PAGE_PARITY_AUDIT_2026-06-11.md:475의 P0-07(베팅 오염)과 동일 항목, 바퀴 20 in-progress. 표면은 OK이나 mutation 오염 위험 → PARTIAL/P0 유지.

### F-3 [P0] InheritAction/BuyHiddenBuff·BuyRandomUnique — 버튼 guaranteed-fail 400 (P0-50)

- legacy: `hwe/sammo/API/InheritAction/BuyHiddenBuff.php` / `BuyRandomUnique.php`.
- 현재 impl: intake는 등재(`CommandWireMapper.kt:58-59`), 핸들러 존재(P6 BuyHiddenBuff cumulative-diff). 그러나 FE `web/game/app/game/nation/page.tsx:86,102`의 호출이 generalId 미전송으로 **매번 400** (PAGE_PARITY_AUDIT_2026-06-11.md:268 P0-50, 바퀴 21 pending).
- BE 표면은 닫혔고 FE-BE 계약 버그만 남음.

### F-0 [P1] Message/SendMessage — BE 엔드포인트는 신설됨(P0-32 재판정), FE 발송 호출 부재

- legacy: `hwe/sammo/API/Message/SendMessage.php:26-152` (validateArgs + launch).
- 현재 impl: `CommandWireMapper.kt:75` `"sendMessage"` intake + `app/game-engine/.../intake/MessageHandler.kt:50-51` (`SendMessage.php::launch` 포팅 주석, `:518` SendMessageResult).
- **재판정**: PAGE_PARITY_AUDIT_2026-06-11.md:196 "send 엔드포인트 자체 없음"은 BE 측에서는 **stale** — `POST /api/command/sendMessage`가 존재한다. 남은 갭은 FE: `web/game/lib/api.ts`에 sendMessage 함수 자체가 없고(grep 0건, lib/mailbox.ts에도 없음) mailbox 페이지에 발송 UI 부재. P0→P1로 강등(BE 닫힘, FE만 남음).

### F-5 [P1] General/DieOnPrestart · DropItem · InstantRetreat — wire variant만 있고 미배선 (409 셸)

- legacy: `General/DieOnPrestart.php:21-31`, `General/DropItem.php:18-37`, `General/InstantRetreat.php:26-36`.
- 현재 impl: wire variant는 존재(`common/.../wire/TurnDaemonCommand.kt:212` DieOnPrestart, `:259` InstantRetreat, `:287` DropItem)하나 ① `CommandWireMapper.intakeCodes` 미등재 → `InstantActionController.kt:97-100` 409, ② 엔진 `TurnDaemonCommandDispatcher.kt`에 dispatch case 0건(grep). FE 호출처도 없음(web/game/app·components grep 0건).
- 3종 모두 실기능 부재. FE 미호출이므로 P0 아님.

### F-6 [P1] InheritAction/CheckOwner — wire variant조차 부재

- legacy: `InheritAction/CheckOwner.php:31-52`.
- 현재 impl: `InheritActionRegistry.kt:49`에 코드 등록 + `InstantActionController` 셸 + inherit 페이지에 cost 표시(`inherit/page.tsx:449`)만 있고, intakeCodes/wire variant/핸들러 전부 부재 → 409.

### F-4 [P1] General/BuildNationCandidate — 컨트롤러 부재 + 엔진 핸들러 스텁

- legacy: `General/BuildNationCandidate.php:22-34`.
- 현재 impl: game-api에 컨트롤러 없음(`CommandWireMapper.kt:89` 주석의 "NationController"는 실존하지 않음 — app/game-api grep 0건). 엔진 `BuildNationCandidateHandler.kt:29-31`은 `GeneralBoolResult(ok=false, reason="미구현")` 고정 반환.
- 거병(건국 후보) 전체 불가. P5 quarantine(genfound-방랑군: 거병→건국 mini-sim 필요)과 연동된 알려진 갭 — 신규 악화 아님, 미해소 확인.

### F-8 [P1] Global/GetRecentRecord — 증분 로그 폴링 endpoint 부재

- legacy: `Global/GetRecentRecord.php:17-50` (ROW_LIMIT 15, lastGeneralRecordID/lastWorldHistoryID 증분).
- 현재 impl: `WorldLogController.kt:23-29` 최근 30건 고정(증분 없음), `FrontInfoController.kt:133-135` `recentRecord = emptyList()` **[§2 BLOCKED]** (general_record/world_history 테이블 부재). 메인 화면 최근 동향 패널 데이터 공백.

### F-14 [P1] Misc/UploadImage — 전무

- legacy: `Misc/UploadImage.php:17-77` (file_put_contents 저장 경로 포함).
- 현재 impl: app/·web/ 전체 grep 0건. 장수 사진 업로드 불가(P0-29~31 join pic 그룹과 연동).

### F-7 [P2] Message/ReadLatestMessage + latestRead 커서 — general_stor 미구현

- legacy: `Message/ReadLatestMessage.php:33-50` (GeneralStorKey latestRead{Private,Diplomacy}Msg KV 갱신).
- 현재 impl: 대응 endpoint 부재. `MailboxController.kt:92` 주석 `latestRead = BLOCKED (0,0) — general_stor 미구현`, `:108`/`:163` `LatestRead()` 고정. 읽음 커서 미동작(미읽음 표시 패러티 갭).

### F-10 [P2] InheritAction/GetMoreLog — 유산 로그 페이징 부재

- legacy: `InheritAction/GetMoreLog.php:14-29`.
- 현재 impl: `InheritPointController.kt:94` `PageRequest.of(0, 30)` 고정 — 31건째부터 열람 불가. FE 호출처 없음(inherit/page.tsx grep).

### F-12 [P2] Global/GeneralListWithToken — 전용 표면 부재

- legacy: `Global/GeneralListWithToken.php:5-7` (`$withToken=true`) + `Global/GeneralList.php:147-157` (select_npc_token keepCnt 맵 합성).
- 현재 impl: `PossessionController.kt:40-45` `/api/generals/claimable`(SelectNpcTokenService)가 후보 풀+토큰을 대체 제공하나, PHP의 "전체 장수 목록 + token 맵" 합성 셰이프와 다름.

### F-9 [P2] Global/GetHistory · GetCurrentHistory — 연감 레코드 부분 공백

- legacy: `Global/GetCurrentHistory.php:24-31` (`getCurrentHistory()` 라이브 스냅샷, func_history.php:384).
- 현재 impl: `HistoryController.kt:38-97` — `:78` 주석 `BLOCKED: yearbook_history 미보유(§5) → 빈 배열`(globalHistory/globalAction), 현재 월은 라이브 합성 없이 마지막 기록 월로 클램프(HistoryController.kt:69-71).

### F-15 [P2] Nation/GetNationInfo — my-nation-detail 부분 커버

- legacy: `Nation/GetNationInfo.php:37-42`.
- 현재 impl: `MyController.kt:219-280` — `:254-255` `taxRate`/`bill`은 meta 방어 read(부재 시 null, `[§2 BLOCKED — meta UNVERIFIED]` 주석).

### F-11 [P2] Global/GetNationList — 전용 endpoint 없이 2개로 분산

- legacy: `Global/GetNationList.php:26-60` (power 정렬 + 국가별 generals(officer_level<5→1 마스킹)/cities 합성).
- 현재 impl: `RankingController.kt:47-49` `/api/rankings/kingdoms` + `:52-58` `/kingdom-roster`(a_kingdomList 대응)로 분산. 단일 합성 셰이프(국가별 generals+cities 단일 응답)는 없음.

### F-13 [P2] Global/ExecuteEngine — 의도적 아키텍처 divergence

- legacy: `Global/ExecuteEngine.php:24-44` (`TurnExecutionHelper::executeAllCommand`:37 — 클라이언트 폴링이 턴 실행을 트리거).
- 현재 impl: 공개 endpoint 없음. 턴 실행은 game-engine 데몬 자율(TurnDaemonLifecycle) + SSE `/sse/turn`(RealtimeRelayController.kt:29), 운영 제어는 engine `StatusController.kt:48-87`(/admin/turn-daemon pause/resume). memory-centric CQRS 설계상 의도적 대체 — 기능 등가(턴은 전진함), 표면만 상이. 백로그 표기 유지하되 P2.

### F-16 [P2] NationCommand/ReserveCommand(단건) — bulk 등가로 흡수

- legacy: `NationCommand/ReserveCommand.php:16-37`.
- 현재 impl: 단건 전용 endpoint 없음. FE chief-center가 `POST /api/command/nation/bulk`(chief-center/page.tsx:8, CommandController.kt:160)로 단건도 처리 — 기능 등가. 참고: `CommandWireMapper.kt:94-102` 주석이 C3 사령 12종을 "general_turn 링"이라 서술하나 실제 FE 경로는 nation/bulk→`reserveBulkNation`(nation_turn) — 주석-실코드 불일치(문서 버그).

## 닫힌 항목 검증 결과 (백로그 재판정)

| 백로그 항목 | 재판정 | 근거 |
|---|---|---|
| Global/ExecuteEngine | MISSING 유지 — 의도적 divergence로 P2 강등 | F-13 |
| Global/GeneralListWithToken | PARTIAL — claimable+token 서비스가 일부 대체 | F-12 |
| InheritAction/GetMoreLog | MISSING 유지 (30건 고정) | F-10 |
| General/DieOnPrestart · DropItem · InstantRetreat | MISSING 유지 — wire variant만 생기고 배선 0 (409) | F-5 |
| InheritAction/CheckOwner | MISSING 유지 — wire variant도 없음 | F-6 |
| InheritAction/ResetStat | MISSING 유지 + **FE 실호출 발견으로 P0 승격** | F-2 |
| Misc/UploadImage | MISSING 유지 | F-14 |
| NationCommand/* | **5종 중 4종 닫힘 확인** (chief-reserved/push/repeat/bulk — CommandController.kt:160-203, ChiefCenterController.kt:65). 단건 ReserveCommand만 bulk 등가(P2) | F-16 |
| Message/SendMessage (P0-32) | **BE는 닫힘 확인** (intake+MessageHandler.handleSend) — 잔여는 FE 발송 UI/api 함수 부재. P0→P1 | F-0 |
| (추가 검증) DecideMessageResponse | 닫힘 — accept/decline + secretPermission>=4 게이트 (P0-35 FIXED 확인) | 표 Message |
| (추가 검증) Troop/Vote/Board/Auction/Nation Set* | 전부 intake 등재 + 핸들러(TroopHandler/VoteHandler/BoardHandler/NationFinanceSetterHandler) 존재 — 표면 닫힘 | 표 |

## 집계

- 총 81 PHP 엔드포인트: **OK 58 / PARTIAL 12 / MISSING 11**
- Finding: **P0 ×3** (F-1, F-2, F-3) · **P1 ×6** (F-0, F-4, F-5, F-6, F-8, F-14) · **P2 ×8** (F-7, F-9, F-10, F-11, F-12, F-13, F-15, F-16)
