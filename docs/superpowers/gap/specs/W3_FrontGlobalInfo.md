# W3 — FrontGlobalInfo / global gates enrichment

**DTO/controller:** `dto/IdentityDto.kt:43-53`, `controller/FrontInfoController.kt:140-156`.
**Source verified against:** `world_state` (config jsonb), V1 baseline (auction/vote/general tables), `read/WorldStateReadRepository.kt`, `read/GameKvReadRepository.kt`.

## 1. Enrichable NOW
All `game_env` keys live in `world_state.config` jsonb (already loaded) — pure JSONB reads, FE contract all-optional so no break:
`scenarioText, extendedGeneral, isFiction, npcMode, joinMode, autorunUser, turnterm, lastExecuted, lastVoteID, develCost, noticeMsg, onlineUserCnt, year, month, startyear, generalCntLimit(maxgeneral), apiLimit(refreshLimit), serverCnt, isunited, title, tournamentType, tournamentState`.
Computed booleans: `isTournamentActive(tournament>0), isTournamentApplicationOpen(==1), isBettingActive(==6), nationBetting, vote(lastVote+expiry)`.
Aggregates (cheap COUNT): `generalCount, nationCount, cityCount, npcCount, createdUserCnt(npc_state=0), createdNPCCnt(npc_state>0)`.
`auctionCount` = COUNT FROM auction WHERE status IN (OPEN,FINALIZING) (auction table V1).

## 2. BLOCKED (missing opensamguk source)
- **`serverLocked`** — `plock` table **absent** from ALL migrations (V1-V10). Interim: hard-code `false` OR `world_state.meta['locked']` flag; faithful render needs a migration. Defer (low-impact, recommend interim false).
- **`recentRecord`** — `general_record` table **does NOT exist anywhere** (zero refs in migrations/kt/ts). `web/game/lib/types.ts:113` declares `recentRecord: string[]`. PARTIAL substitute: `log_entry` table exists (V1:255+, scope SYSTEM/GENERAL, `WorldLogReadRepository` present) → can feed a SYSTEM/GENERAL log feed, but the PHP `general_record`/`world_history` exact shape is unbacked. Defer faithful version; interim = log_entry feed.

## 3. FE consumers
`components/game/GameInfo.tsx` (header: year/month/turnterm/scenarioText/npcCount/extendedGeneral/isFiction/npcMode/onlineUserCnt/apiLimit/general counts/tournament*/lastExecuted/auctionCount/serverLocked/serverCnt), `lib/menu-filter.ts` (needs nationBetting/vote/npcMode/isTournamentApplicationOpen/isBettingActive), `lib/global-menu-fixture.ts`.

## 4. Risk
LOW — all sources are JSONB reads or indexed COUNTs. Co-widens `FrontInfoController` only (not the repos the other groups touch) → least foundation-conflict. Mitigate JSONB key typos with a typed config accessor. `serverLocked` + `recentRecord` are the only real gaps and both have safe interims.
