# Parity Audit V3 — Batch 4 (Features/Info Pages)

## Summary

| Page | Verdict |
|------|---------|
| auction | 🔴 MAJOR GAPS |
| betting | 🟡 MINOR GAPS |
| best-generals | 🟡 MINOR GAPS |
| hall-of-fame | ✅ PARITY |
| dynasty | 🟡 MINOR GAPS |
| emperor | 🟡 MINOR GAPS |
| history | 🟡 MINOR GAPS |
| tournament | 🟡 MINOR GAPS |
| vote | 🟡 MINOR GAPS |
| inherit | 🔴 MAJOR GAPS |

---

### auction — 🔴 MAJOR GAPS
- **Missing resource auction (buy/sell rice)**: Legacy has full buy-rice and sell-rice auction lists with bid/create UI (`AuctionResource.vue`). New page does not exist or is a stub — no auction page was found at the expected path. The legacy supports: listing active auctions, selecting & bidding on buy-rice/sell-rice auctions, creating new auctions (type, amount, duration, start/finish bid), and showing recent auction logs.
- **Missing unique item auction section**: Legacy `PageInheritPoint.vue` allows opening unique item auctions via `SammoAPI.Auction.OpenUniqueAuction`. The new inherit page has a simplified unique auction but the dedicated auction page is missing the resource trading feature entirely.
- **Missing bid amount validation with NumberInputWithInfo**: Legacy has min/max/step constraints on bid amounts tied to auction's startBidAmount/finishBidAmount.

### betting — 🟡 MINOR GAPS
- **Different betting model**: Legacy uses a generic betting system (`PageNationBetting.vue` + `BettingDetail.vue`) with: betting list by year/month, multiple betting events (not just tournament), candidate selection with `selectCnt` (multi-pick), exclusive vs non-exclusive betting, partial match rewards (graduated payoff for partial correct picks), and admin-seeded bets. New page is tournament-betting-only with per-general single bets.
- **Missing multi-candidate selection**: Legacy supports `selectCnt > 1` where users pick multiple candidates as a combo bet. New page only supports single-target bets.
- **Missing graduated reward calculation**: Legacy has complex reward calculation for partial matches (matching some but not all picks). New page uses simple odds multiplier.
- **Missing betting event list navigation**: Legacy shows a list of all betting events (past and current) and lets you click into details. New page has a history tab but uses a different data model.
- **Missing gold/inheritancePoint mode**: Legacy betting supports both gold-based and inheritance-point-based betting (`reqInheritancePoint`). New page only shows gold.

### best-generals — 🟡 MINOR GAPS
- **Missing `deathcrew` display**: Legacy PHP renders deathcrew (사망 병력) column; new page computes killrate from it but doesn't show it directly.
- **Unique item owners section is new addition**: Not in legacy — this is a positive enhancement, no gap.
- **NPC toggle behavior**: Legacy `bestGeneral.ts` is minimal (just CSS/tooltip init); actual ranking was PHP-rendered. New page has full client-side sorting which is an improvement.

### hall-of-fame — ✅ PARITY
- Season/scenario filtering present (matches legacy `$('#by_scenario')` change handler)
- All legacy categories covered (experience, dedication, firenum, warnum, killnum, winrate, occupied, killcrew, killrate, dex1-5, tournament rates, betting stats)
- Card view + table view provides equivalent or better UX than legacy PHP-rendered page
- Owner name display present

### dynasty — 🟡 MINOR GAPS
- **New page concept**: Legacy has no dedicated "dynasty" page — `history.ts`/`v_history.ts` covered yearbook/chronicle. The new dynasty page is a reinterpretation using world records to build a dynasty timeline. This is largely additive.
- **Event classification is heuristic**: Uses text matching (건국, 멸망, 통일, etc.) rather than structured event types from server. May misclassify events.
- **Missing map viewer**: Legacy `PageHistory.vue` includes `MapViewer` component showing territory map for each year/month. Dynasty page has no map.

### emperor — 🟡 MINOR GAPS
- **New page concept**: No direct legacy equivalent as a standalone emperor page. Legacy spread emperor info across various views.
- **Yearbook viewer**: Present but uses generic `historyApi` calls. Legacy yearbook was part of `PageHistory.vue`.
- **Missing officer level details**: Legacy PHP had detailed officer hierarchy. New page shows officers with level ≥ 5 only.
- **Nation stats histograms**: New addition, no legacy equivalent — positive enhancement.

### history — 🟡 MINOR GAPS
- **Missing map viewer for specific year/month**: Legacy `PageHistory.vue` has `MapViewer` component rendering territory map per year/month. New page has a "맵 재현" tab with snapshot browsing via `mapRecentApi`, but this is a different mechanism (cached snapshots vs live yearMonth query).
- **Missing `SimpleNationList` equivalent**: Legacy shows nation ranking sidebar alongside map. New page shows yearbook nations in text form.
- **Missing year/month dropdown with prev/next navigation**: Legacy has `◀ 이전달` / `다음달 ▶` buttons with continuous year-month dropdown. New page has separate year and month selects without prev/next buttons.
- **Missing `global_action` (장수 동향) section**: Legacy shows both `global_history` (중원 정세) and `global_action` (장수 동향) separately. New page combines all events into a single timeline.

### tournament — 🟡 MINOR GAPS
- **Legacy `formatTournament.ts`**: Contains `formatTournamentLog` for rendering tournament battle logs with HTML formatting. New page has no battle log rendering — missing detailed fight-by-fight results.
- **Missing tournament battle log viewer**: Legacy displayed per-match combat logs. New page shows bracket results only (winner/loser) without the combat narrative.
- **Operator features are new additions**: Phase advancement, operator messaging — not in legacy frontend (was backend/admin only). Positive enhancement.
- **Preliminary ranking table interpretation**: New page computes win/loss/draw from bracket data. Legacy didn't have this client-side feature.

### vote — 🟡 MINOR GAPS
- **Different data model**: Legacy `v_vote.ts` + `PageVote.vue` uses server-rendered PHP with Vue overlay for interactivity. New page uses message-based voting system.
- **Missing vote detail sub-page**: `vote/[id]/page.tsx` path exists but wasn't found — may be a routing issue or stub.
- **Legacy voting was tied to game mechanics**: Legacy votes could have game effects (nation decisions). New page is a generic polling system.
- **Multi-selection support**: New page adds `maxSelections` parameter. Legacy had `selectCnt` in betting but standard vote was single-pick.

### inherit — 🔴 MAJOR GAPS
- **Missing inheritance buff system**: Legacy has 8 specific combat buffs (`warAvoidRatio`, `warCriticalRatio`, `warMagicTrialProb`, `warAvoidRatioOppose`, `warCriticalRatioOppose`, `warMagicTrialProbOppose`, `domesticSuccessProb`, `domesticFailProb`) with granular level control. New page has generic stat/resource buffs (leadership, strength, intel, politics, charm, gold, rice, crew, exp) that don't match the legacy buff types at all.
- **Missing point breakdown categories**: Legacy shows detailed point sources: `previous`, `lived_month`, `max_belong`, `max_domestic_critical`, `active_action`, `combat`, `sabotage`, `unifier`, `dex`, `tournament`, `betting` with individual values. New page has simplified `previousPoints` + `newPoints` + `pointSources` array.
- **Missing `inheritBonusStat` in stat reset**: Legacy stat reset includes both base stats AND bonus stats (3 additional stat points). New page only has base stat redistribution.
- **Missing "시작 도시 지정" in legacy**: New page adds city selection which doesn't exist in legacy — but this could be intentional new feature.
- **Hardcoded costs don't match legacy**: New page hardcodes costs (e.g., `STAT_RESET_COST=500`, `CHECK_OWNER_COST=50`, `RANDOM_UNIQUE_COST=300`). Legacy costs come from server (`staticValues.inheritActionCost`) and are dynamic.
- **Missing "더 가져오기" (load more) for logs**: Legacy has pagination via `getMoreLog()` with `lastID`. New page shows all logs at once without pagination.
- **Owner check by name vs by ID**: Legacy uses general ID selector (`availableTargetGeneral` dropdown). New page uses free-text name input — different API contract.
- **War special list is hardcoded**: Legacy gets `availableSpecialWar` from server with descriptions. New page has hardcoded `WAR_SPECIALS` array without info text.
- **Unique item list is hardcoded**: Legacy gets `availableUnique` from server with descriptions and rawName. New page has hardcoded `UNIQUE_ITEMS`.
