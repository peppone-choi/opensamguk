# W3 — GetConst / Auction / Betting / Message / Map

**DTOs:** `dto/AuctionDto.kt`, `dto/BettingDto.kt`, `dto/MessageDto.kt`, `dto/MapPreviewDto.kt`.
**Controllers:** `AuctionController.kt`, `BettingController.kt`, `MapPreviewController.kt`.
**Source verified against:** V7 (`ng_auction`/`ng_auction_bid`/`message`/`ng_betting`/`game_kv`), V1 (`city`, `inheritance_point`), `read/GameKvReadRepository.kt`.

## 1. Enrichable NOW
- **GetConst** — NOT a DB read; static const bundle. New `GetConstController` returning gameConst/gameUnitConst/cityConst/iAction maps from a committed `infra/.../resources` JSON or Kotlin enums. No source blocker, but no impl yet.
- **Auction** — `hostGeneralID, hostName` (`ng_auction` V7:84-85), `highestBid` (`ng_auction_bid` MAX(amount)), `remainPoint` (`inheritance_point` V1:280, key='previous').
- **Betting** — `market, candidates` from `game_kv` ('betting' ns, BettingInfo jsonb V7:58-66); `bettingDetail` = SUM(amount) GROUP BY betting_type FROM ng_betting.
- **Message** — `MsgTarget src/dest` via `message` int fields + general/nation JOIN (name/nation/color/icon); `option block` parsed from `message.message` jsonb.
- **Map** — `city.state←front_state`, `city.supply←supply_state`, `city.region←region` (all V1 city columns).

## 2. BLOCKED (missing source)
- **auction_recentLogs** — PHP `getAuctionLogRecent(20)` source not located; likely `log_entry` filtered. Clarify table/filter before implementing; interim empty list.
- **betting_배당 (odds)** — NOT persisted by design; PHP computes client-side from bettingDetail/myBetting. Backend must NOT return 배당; FE computes. Not a backend blocker — a contract note.
- **map_spyList / map_shownByGeneralList** — NOT in DB; derived from engine WorldSnapshot (fog-of-war / espionage) per request. (`shownByGeneralList` has a partial source: `GeneralReadRepository.findDistinctCityIdByNationId` line 180 already implements the func_map.php:135 query.) `spyList` needs engine spy-state. Defer to engine-coupled map task.

## 3. FE consumers
`web/game/app/game/auction/page.tsx`, `components/game/MessagePanel.tsx`, `components/game/MapViewer.tsx`, betting page (legacy Vue), `lib/api.ts`.

## 4. Risk
Independent of the FrontInfo foundation (different DTOs/controllers) → ships in parallel. Biggest open items: auction-log source (clarify) and spyList (engine-coupled). GetConst is a fresh static-service endpoint, not a DTO enrichment.
