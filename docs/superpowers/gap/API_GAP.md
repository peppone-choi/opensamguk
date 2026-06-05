# API_GAP — Exhaustive PHP-endpoint → Kotlin-route parity audit

> Dimension: **API endpoints** (HTTP surface), not command-logic. The 93-command
> mutation-path ledger is `docs/superpowers/PARITY_LEDGER.md`; this doc references it and
> does NOT re-audit per-command rows.
>
> PHP grand truth surfaces (legacy/devsam-core/hwe):
> 1. **`hwe/sammo/API/**` (83 handlers)** — the CANONICAL modern REST surface, dispatched by
>    `api.php?path=Domain/Handler` via `APIHelper::launch`. This is the contract the Vue
>    frontend (`hwe/ts/defs/API/*.ts`) actually calls. **Primary mapping target.**
> 2. **`j_*.php` (33)** — legacy AJAX JSON endpoints. Some still live (map, simulator, npc
>    select, basic info); many superseded by `API/**` equivalents.
> 3. **`v_*.php` (16)** — server-rendered Vue-shell page views (HTML mount points). In the
>    Kotlin/Next stack these become Next.js pages (`web/game/app/**`), NOT REST routes — out
>    of REST scope but listed for completeness.
>
> Kotlin surfaces audited:
> - `app/game-api/.../controller/**` (22 controllers) + `app/game-api/.../web/**`
>   (Command, AvailableCommands, ReservedCommands, CityDetail, Health) + `sse/` + `owner/Possession`.
> - `app/gateway-api/.../controller/**` (Auth, Admin).
>
> Status legend:
> - **PRESENT** — a dedicated Kotlin REST route returns the equivalent payload (read) or
>   performs the equivalent mutation at a matching contract path.
> - **PARTIAL** — equivalent behavior is reachable but via a DIFFERENT shape than the PHP
>   contract: most mutations route through the generic `POST /api/command/{code}` intake
>   (logic ported, see PARITY_LEDGER) rather than a dedicated domain REST path; or only the
>   read half of a read+write handler exists; or payload is a subset.
> - **MISSING** — no Kotlin route AND no generic-intake path covers it.

---

## Totals (API-endpoint dimension)

| Surface | PHP count | PRESENT | PARTIAL | MISSING |
|---|---|---|---|---|
| `sammo/API/**` REST handlers | 83 | 19 | 30 | 34 |
| `j_*.php` legacy AJAX | 33 | 6 | 7 | 20 |
| `v_*.php` views (→ Next pages, not REST) | 16 | — (n/a REST) | — | — |
| **API-relevant total (handlers + j_)** | **116** | **25** | **37** | **54** |

> "PARTIAL" for mutations almost always means: **the command logic is ported & registered &
> the generic `POST /api/command/{code}` intake works, but there is no dedicated
> domain-named REST endpoint matching the PHP `api.php?path=...` contract**, so a faithful
> client (the Vue defs) cannot call the same path. Reservation/command-table handlers are
> the clearest PARTIALs; the dedicated mutation handlers (Bid/Open/Bet/Vote/SendMessage/
> Set*/Troop*/Inherit*) are mostly MISSING as REST and only some have ported logic.

---

## A. `sammo/API/**` — canonical REST handlers (83)

### A1. Command (5) — general-area reserve/push
PHP path `api.php?path=Command/X`. Kotlin equivalent = generic intake + reserved-list reads.

| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Command/ReserveCommand | POST | `POST /api/command/{code}` (CommandController) | PARTIAL | Generic single-code reserve. PHP shape `{turnList, action, arg}` not mirrored; turnIdx-per-call instead of turn-list array. |
| Command/ReserveBulkCommand | POST | — | MISSING | No bulk multi-turn reserve endpoint. FE can only reserve one turnIdx per POST. |
| Command/PushCommand | POST | — | MISSING | "Push down" / shift reserved queue not exposed. |
| Command/RepeatCommand | POST | — | MISSING | Repeat-last-command-to-fill-queue not exposed. |
| Command/GetReservedCommand | GET | `GET /api/reserved-commands` (ReservedCommandsController) | PARTIAL | Reads reserved general queue; verify field parity vs PHP (`turnList`, brief, arg). |

### A2. NationCommand (5) — nation/chief-area reserve/push
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| NationCommand/ReserveCommand | POST | `POST /api/command/{code}` (shared intake) | PARTIAL | Chief commands route through same generic intake; F4-C3 chief logic ported (NationFinanceSetters etc.). No nation-scoped reserve path. |
| NationCommand/ReserveBulkCommand | POST | — | MISSING | |
| NationCommand/PushCommand | POST | — | MISSING | |
| NationCommand/RepeatCommand | POST | — | MISSING | |
| NationCommand/GetReservedCommand | GET | `GET /api/nation/chief-reserved` (ChiefCenterController) | PARTIAL | Reads chief reserved queue; field parity to verify. |

### A3. General (8)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| General/GetFrontInfo | GET | `GET /api/front-info` (FrontInfoController) | PRESENT | Main turn-screen aggregate. |
| General/GetCommandTable | GET | `GET /api/commands/available` (AvailableCommandsController) | PARTIAL | Available-command list with precheck; verify it returns the full PHP command-table shape (groups, args, brief). |
| General/GetGeneralLog | POST | — | MISSING | General action/battle/history log paging. (`j_general_log_old.php` also unported.) |
| General/Join | POST | — | MISSING | **In-game 임관/등용 join** API (distinct from gateway auth). No REST route; join/start flow absent. |
| General/BuildNationCandidate | POST | — | MISSING | 건국 후보(거병→건국) candidate build. (Backlog: genfound-방랑군 quarantine.) |
| General/DieOnPrestart | POST | — | MISSING | Pre-start self-die / reroll. |
| General/DropItem | POST | — | MISSING | Drop equipped item. Logic may exist as command; no REST path. |
| General/InstantRetreat | POST | — | MISSING | Instant retreat action. |

### A4. Nation (11) — chief setters + nation reads
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Nation/GetNationInfo | GET | `GET /api/my-nation-detail` (MyController) + `GET /api/nation/{id}/finance` | PARTIAL | Read split across MyController/NationFinanceController; confirm union covers PHP `GetNationInfo` payload. |
| Nation/GeneralList | GET | `GET /api/generals` (GeneralsController) / `GET /api/my-generals` | PARTIAL | Nation general roster; scope/columns to verify vs PHP. |
| Nation/GetGeneralLog | POST | — | MISSING | Nation-scoped general log. |
| Nation/SetBill | POST | `POST /api/command/{code}` (NationFinanceSetters intake) | PARTIAL | Logic ported (세율/지급); no dedicated `Nation/SetBill` REST path. |
| Nation/SetRate | POST | `POST /api/command/{code}` (NationFinanceSetters intake) | PARTIAL | Same — generic intake only. |
| Nation/SetNotice | POST | `POST /api/command/{code}`? | PARTIAL | 공지 set; verify intake code wired (SecretPermission/NationFinance family). |
| Nation/SetScoutMsg | POST | — | MISSING | 등용 메시지 set — no intake code found. |
| Nation/SetBlockWar | POST | `POST /api/command/{code}` (intake) | PARTIAL | 전쟁금지 toggle; logic in intake family. |
| Nation/SetBlockScout | POST | `POST /api/command/{code}` (intake) | PARTIAL | 스카웃금지 toggle. |
| Nation/SetSecretLimit | POST | `POST /api/command/{code}` (SecretPermission intake) | PARTIAL | 기밀제한; ported as intake. |
| Nation/SetTroopName | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Overlaps Troop/SetTroopName; intake only, no REST path. |

### A5. Troop (5)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Troop/NewTroop | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Logic ported; no dedicated `Troop/NewTroop` REST path. GET read = `GET /api/troops`. |
| Troop/JoinTroop | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Intake only. |
| Troop/ExitTroop | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Intake only. |
| Troop/KickFromTroop | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Intake only. |
| Troop/SetTroopName | POST | `POST /api/command/{code}` (TroopActions intake) | PARTIAL | Intake only. |

### A6. Auction (9)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Auction/GetActiveResourceAuctionList | GET | `GET /api/auctions` (AuctionController) | PRESENT | Active list (read). |
| Auction/GetUniqueItemAuctionList | GET | `GET /api/auctions` | PARTIAL | Unique-item auctions may not be split out from resource list; verify type filter. |
| Auction/GetUniqueItemAuctionDetail | GET | `GET /api/auctions/{id}` + `/{id}/bids` | PARTIAL | Detail+bids read present; unique-specific fields (remainCloseDateExtensionCnt) to verify. |
| Auction/BidBuyRiceAuction | POST | — (intake `auctionBid`?) | PARTIAL | PARITY_LEDGER flags FE posts `auction_bid` vs intake `auctionBid` (silent no-op). No dedicated REST path. |
| Auction/BidSellRiceAuction | POST | — | PARTIAL | Same intake-casing bug class. |
| Auction/BidUniqueAuction | POST | — | PARTIAL | Same. |
| Auction/OpenBuyRiceAuction | POST | — | MISSING | Open new buy-rice auction — no path/intake found. |
| Auction/OpenSellRiceAuction | POST | — | MISSING | Open sell-rice auction — none. |
| Auction/OpenUniqueAuction | POST | — | MISSING | Open unique-item auction — none. |

### A7. Betting (3)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Betting/GetBettingList | GET | `GET /api/bettings/general/{generalId}` (BettingController) | PARTIAL | Reads by general; whole-list endpoint shape to verify. |
| Betting/GetBettingDetail | GET | `GET /api/bettings/{bettingId}/bets` | PARTIAL | Bets read present; detail (odds, options) field parity to verify. |
| Betting/Bet | POST | — (intake `placeBet`) | PARTIAL | PlaceBetHandler ported (P6); FE posts `bet` vs intake `placeBet` (silent no-op, PARITY_LEDGER). No dedicated REST path. |

### A8. Vote (5)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Vote/GetVoteList | GET | `GET /api/votes` (VoteController) | PRESENT | List (read). |
| Vote/GetVoteDetail | GET | `GET /api/votes/{id}` | PRESENT | Detail (read). |
| Vote/Vote | POST | — | MISSING | Cast vote — no REST/intake. (PARITY_LEDGER: vote RNG golden pending.) |
| Vote/NewVote | POST | — | MISSING | Create vote — none. |
| Vote/AddComment | POST | — | MISSING | Vote comment — none. |

### A9. Message (7) — diplomacy/personal messaging
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Message/GetRecentMessage | GET | `GET /api/mailbox/{mailbox}` (MailboxController) | PARTIAL | Recent by mailbox; verify "recent" semantics + cursor. |
| Message/GetOldMessage | GET | `GET /api/messages/{id}` | PARTIAL | Single fetch; PHP is paged-old — paging not mirrored. |
| Message/ReadLatestMessage | GET | `GET /api/mailbox/{mailbox}/unread` | PARTIAL | Unread marker; verify it marks-read like PHP. |
| Message/GetContactList | GET | — | MISSING | Contact/conversation list — no route. |
| Message/SendMessage | POST | — | MISSING | **Send personal/national/diplomatic message** — no REST path. (DiplomaticMessage accept/decline exists but not send.) |
| Message/DeleteMessage | POST | — | MISSING | Delete message — none. |
| Message/DecideMessageResponse | POST | `POST /api/messages/{id}/accept` + `/decline` (DiplomaticMessageController) | PARTIAL | Diplomatic accept/decline ported; PHP is generic message-response (covers more types). |

### A10. InheritAction (8)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| InheritAction/GetMoreLog | GET/POST | `GET /api/inherit-point` (InheritPointController, read) | PARTIAL | Point read present; paged inherit-log not exposed. |
| InheritAction/CheckOwner | POST | `POST /api/general/claim` + `GET /api/generals/claimable` (PossessionController) | PARTIAL | Ownership claim covered by Possession; CheckOwner semantics overlap, verify. |
| InheritAction/BuyHiddenBuff | POST | — | MISSING | BuyHiddenBuff logic ported (P6) but FE posts unregistered code → no-op (PARITY_LEDGER); no REST path. |
| InheritAction/BuyRandomUnique | POST | — | MISSING | Same: ported logic, no reachable REST/intake. |
| InheritAction/ResetStat | POST | — | MISSING | Stat reset (inherit-point spend) — none. |
| InheritAction/ResetTurnTime | POST | — | MISSING | Turn-time reset — none. |
| InheritAction/ResetSpecialWar | POST | — | MISSING | Special-war reset — none. |
| InheritAction/SetNextSpecialWar | POST | — | MISSING | Set next special-war — none. |

### A11. Global (12)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Global/GetGlobalMenu | GET | `GET /api/global-menu` (GlobalMenuController) | PRESENT | |
| Global/GetConst | GET | `GET /api/const` (GlobalMenuController) | PRESENT | |
| Global/GetMap | GET | `GET /api/map/preview` (MapPreviewController) | PARTIAL | Preview only; PHP GetMap returns full per-year/month map state (`j_map.php` params year/month). |
| Global/GetCachedMap | GET | `GET /api/map/preview` (cached 10min) | PARTIAL | Cache exists; cached-map historical snapshot semantics to verify. |
| Global/GetNationList | GET | `GET /api/rankings/kingdoms` / `GET /api/diplomacy/{nationId}` | PARTIAL | Nation list reachable via rankings; dedicated nation-list payload to verify. |
| Global/GetDiplomacy | GET | `GET /api/diplomacy/{nationId}` + `/conflict` (DiplomacyController) | PRESENT | Neutral-map masking ported (P7). |
| Global/GetHistory | GET | `GET /api/history` (HistoryController) | PRESENT | |
| Global/GetCurrentHistory | GET | `GET /api/history` | PARTIAL | Current vs full history — confirm "current year" filter param. |
| Global/GetRecentRecord | GET | — | MISSING | Recent record feed (`recent_map.php`/`j_map_recent.php`) — no route. |
| Global/GeneralList | GET | `GET /api/generals` / `GET /api/rankings/generals` | PRESENT | Global general list. |
| Global/GeneralListWithToken | GET | — | MISSING | General-list-with-select-token (NPC pick flow) — none. |
| Global/ExecuteEngine | POST | — | MISSING | Admin-trigger engine run. In Kotlin the daemon runs autonomously (no manual-trigger endpoint); intentional divergence but no admin equivalent. |

### A12. Login (3) + Admin (1) + Misc (1)
| PHP handler | Verb | Kotlin route | Status | Notes |
|---|---|---|---|---|
| Login/LoginByID | POST | `POST /auth/login` (gateway AuthController) | PARTIAL | Gateway uses own JWT/BCrypt (intentional divergence from Kakao OAuth); functional login present. |
| Login/LoginByToken | POST | `POST /auth/refresh` (gateway) | PARTIAL | Token-based session; refresh covers re-auth. |
| Login/ReqNonce | POST | — | MISSING | Nonce challenge step not in gateway flow (divergent auth). |
| Admin/BanEmailAddress | POST | — | MISSING | Email ban (gateway AdminController has version/deploy only). |
| Misc/UploadImage | POST | — | MISSING | Avatar/image upload — none (`j_adjust_icon.php` also unported). |

---

## B. `j_*.php` — legacy AJAX endpoints (33)

Many duplicate `API/**` handlers; the unique survivors are map/simulator/npc/server-info.

| PHP file | Purpose | Kotlin route | Status |
|---|---|---|---|
| j_basic_info.php | Per-user basic game state (generalID, nationID, isChief, permission) | `GET /api/front-info` / `GET /api/my-page` | PARTIAL |
| j_server_basic_info.php | Server/game meta (turn time, year/month, env) | `GET /api/front-info` (subset) | PARTIAL |
| j_map.php | Full map state by year/month | `GET /api/map/preview` | PARTIAL |
| j_map_recent.php | Recent map snapshot | — | MISSING |
| j_simulate_battle.php | Battle simulator run | `POST /api/simulate-battle` (SimulatorController) | PRESENT |
| j_export_simulator_object.php | Export general as sim object | — (SimulatorController read?) | MISSING |
| j_myBossInfo.php | Boss/chief info + action dispatch | `GET /api/my-boss` (MyController) | PARTIAL |
| j_get_city_list.php | City list (+ nations) | `GET /api/city/{id}` (CityDetail) | PARTIAL |
| j_get_basic_general_list.php | Basic general list by nation | `GET /api/generals` | PARTIAL |
| j_general_log_old.php | Old general log paging | — | MISSING |
| j_general_set_permission.php | Set general/ambassador permission | — (chief intake?) | MISSING |
| j_board_get_articles.php | Board article list (incl. secret) | `GET /api/board` (BoardController) | PARTIAL |
| j_board_article_add.php | Add board article | — | MISSING |
| j_board_comment_add.php | Add board comment | — | MISSING |
| j_diplomacy_get_letter.php | Get diplomacy letter | `GET /api/diplomacy/letters` (DiplomacyController) | PARTIAL |
| j_diplomacy_send_letter.php | Send diplomacy letter | — | MISSING |
| j_diplomacy_respond_letter.php | Respond to letter | `POST /api/messages/{id}/accept|decline` | PARTIAL |
| j_diplomacy_rollback_letter.php | Rollback sent letter | — | MISSING |
| j_diplomacy_destroy_letter.php | Destroy letter | — | MISSING |
| j_select_npc.php | Select NPC (pick) | `POST /api/general/claim` (Possession) | PARTIAL |
| j_get_select_npc_token.php | Get NPC select token (pool refresh) | — | MISSING |
| j_get_select_pool.php | Get select pool (rerollable) | `GET /api/generals/claimable` | PARTIAL |
| j_select_picked_general.php | Pick a generated general (custom stats) | — | MISSING |
| j_update_picked_general.php | Update picked general | — | MISSING |
| j_set_npc_control.php | Set NPC autorun control | `GET /api/nation/npc-policy` (read only) | PARTIAL |
| j_set_my_setting.php | Set personal settings (UI/notif) | — | MISSING |
| j_vacation.php | Toggle vacation mode | — | MISSING |
| j_adjust_icon.php | Adjust general icon/picture | — | MISSING |
| j_raise_event.php | Admin raise event | — | MISSING |
| j_autoreset.php | Admin server auto-reset | — | MISSING |
| j_load_scenarios.php | List available scenarios | — | MISSING |
| j_install.php / j_install_db.php | Server install (admin bootstrap) | — | MISSING (replaced by ScenarioImporter/Flyway, not a REST path) |

> j_install*/j_autoreset/j_raise_event are admin-bootstrap, intentionally replaced by
> Flyway + `ScenarioImporter`/`AdminSeeder` (not REST). Marked MISSING-as-REST but design-OK.

---

## C. `v_*.php` — server-rendered views (16) → Next.js pages (NOT REST)

These mount Vue shells; in the Kotlin/Next stack they map to `web/game/app/**` pages
(read-rendered via game-api). They are listed for completeness — none should be a REST route.

| v_*.php | Next page (web/game/app) | Status |
|---|---|---|
| v_processing.php | game main / processing screen | PRESENT (F2 GameChrome) |
| v_chiefCenter.php | chief-center | PRESENT-read (F4) |
| v_battleCenter.php | battle | PRESENT-read (F4) |
| v_troop.php | troop | PRESENT-read (F4) |
| v_auction.php | auction | PRESENT-read (F4) |
| v_board.php | board | PRESENT-read (F4) |
| v_vote.php | vote | PRESENT-read (F4) |
| v_globalDiplomacy.php | diplomacy | PRESENT-read (F4) |
| v_inheritPoint.php | inherit | PRESENT-read (F4) |
| v_NPCControl.php | npc-control | PRESENT-read (F4) |
| v_nationBetting.php | betting | PRESENT-read (F4) |
| v_nationGeneral.php | nation general | PARTIAL |
| v_nationStratFinan.php | nation strat/finance | PARTIAL |
| v_history.php | history | PRESENT-read (F3) |
| v_cachedMap.php | map | PARTIAL (preview only) |
| v_join.php | join/start | MISSING (no in-game join page; gateway lobby only) |

---

## Highest-priority MISSING (mutation surface — players literally cannot act)

1. **Message/SendMessage** + **GetContactList** + **DeleteMessage** — no personal/national
   message send. Only diplomatic accept/decline exists.
2. **Vote/Vote, Vote/NewVote, Vote/AddComment** — voting is read-only; cannot cast/create.
3. **Auction/Open*** (BuyRice/SellRice/Unique) + **Bid*** — cannot open OR (cleanly) bid;
   bid intake also has the `auction_bid`/`auctionBid` casing no-op bug.
4. **General/Join** + **General/BuildNationCandidate** — no in-game join / 건국 candidate REST;
   the new-player + founding loop has no HTTP entry (ties to genfound quarantine + prod
   거병 seam task #5).
5. **InheritAction/Buy*/Reset*/SetNextSpecialWar** (7 of 8) — inherit-point spend menu
   entirely unreachable; BuyHiddenBuff/BuyRandomUnique logic ported but no route.
6. **Command/ReserveBulkCommand + PushCommand + RepeatCommand** (×2 for NationCommand) —
   only single-turn reserve exists; no bulk/repeat/push queue management (a core UX of the
   turn screen).
7. **Diplomacy letter lifecycle** (send/rollback/destroy) — can read+respond but not
   send/withdraw a letter.
8. **NPC select-pool flow** (j_get_select_npc_token, j_select_picked_general,
   j_update_picked_general) — custom-general pick/generate path absent.
