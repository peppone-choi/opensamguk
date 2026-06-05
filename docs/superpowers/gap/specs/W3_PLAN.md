# W3_PLAN — Read-DTO enrichment, consolidated (6 groups)

Goal: bring the FE-output read DTOs from ~30% of the PHP contract toward parity.
**Every field below was verified against the PERSISTED SOURCE**: `infra/src/main/resources/db/migration/V1__baseline.sql` … `V10`, the JPA read entities (`GeneralReadEntity`, `NationReadEntity`, `NationTurnReadEntity`), and the engine WorldSnapshot/rowmappers. Fields with no backing column/entity are in §2 (BLOCKED) — none fabricated.

## Verified corrections to the reconciled audit
| Audit claimed | Ground truth |
|---|---|
| `special_code/special2_code/personal_code/penalty` columns missing | **EXIST in V1 `general`** — just unmapped in GeneralReadEntity (1-line add each) |
| `nation.power` missing | **EXISTS** via `V8__nation_power.sql` (the `SUM(crew)` proxy comment in GeneralReadRepository:168 is stale) |
| `general.officer_city` missing/unclear | **EXISTS** via `V6__p4_war_columns.sql:16` |
| `general_record` (recentRecord) | **ABSENT everywhere** — confirmed blocker; `log_entry` is the only partial substitute |
| `rank_data` write-only | **Confirmed** — table exists (V1:162) but ZERO read path in game-api |
| `dex1-5` | **Confirmed blocker** — not a column, not in V6, zero meta/snapshot refs |

---

## 1. Enrichable NOW (source exists) — by group, ordered by leverage

1. **ChiefCenter** (lowest risk, 0 blockers) — `name, npcType, turnTime` (general cols), `myGeneralId, myOfficerLevel, nationName, nationLevel` (resolver), `year/month/turnTerm` (game_kv). Only work = TURNTIME_FULL formatter. Separate controller → no foundation contention.
2. **FrontGlobalInfo** — ~22 `world_state.config` JSONB keys + computed tournament/vote/nationBetting booleans + cheap COUNT aggregates (general/nation/city/npc, created user/NPC) + `auctionCount`. All JSONB/COUNT, FE contract all-optional → no break. Touches only FrontInfoController.
3. **FrontGeneralInfo** — thread already-mapped cols (`picture, experience, dedication, train, atmos, injury, crewTypeId, equip codes, troop, officerLevel`) + **map 4 existing cols** (`special_code→specialDomestic, special2_code→specialWar, personal_code→personal, penalty`).
4. **FrontNationInfo** — `power(V8), level, capital, gold, rice, tech, type_code, color` + meta `gennum` + grouped aggregates `population.{cityCnt,now,max}`, `crew.{generalCnt,now,max}` + computed `topChiefs, type.{name,pros,cons}` (verify NationType class), `impossibleStrategicCommand`.
5. **GeneralList** — P0/P1 tiered: all general cols incl **`officer_city`(V6)**, meta-derived (explevel/dedlevel/age/belong/specage/*_exp), computed (killturn/honorText/lbonus/bill), `reservedCommand` (general_turn). New `GET /api/nation/general-list`.
6. **GetConst/Auction/Betting/Message/Map** — auction `hostGeneralID/hostName/highestBid/remainPoint`; betting `market/candidates/bettingDetail`; message `MsgTarget/option`; map `city.{state,supply,region}` (V1 cols). GetConst = new static-service endpoint.

## 2. BLOCKED (missing opensamguk source — DEFER, do not fabricate)

| Field(s) | Missing source | Needs |
|---|---|---|
| **warnum/killnum/deathnum/killcrew/deathcrew/firenum** | `rank_data` table exists but NO read entity/repo/query | New `RankDataReadEntity` + `RankDataReadRepository` (value by (general_id,type)). **SHARED** by FrontGeneralInfo + GeneralList → build ONCE. |
| **dex1-5** | not a column, not V6, not meta | `general` column add (or engine meta write) + WorldSnapshot/rowmapper plumbing. **SHARED** blocker. |
| **recentRecord** | `general_record`/`world_history` tables ABSENT | New migration + engine write; interim = `log_entry` (SYSTEM/GENERAL) feed via existing WorldLogReadRepository. |
| **serverLocked** | `plock` table ABSENT | Migration OR `world_state.meta['locked']`; interim hard-code `false`. |
| **refresh_score / refresh_score_total** | `general_access_log` table ABSENT | Migration + write path (P8 throttle). |
| nation `bill/taxRate/diplomaticLimit/strategicCmdLimit/prohibitScout/prohibitWar` | no column | Verify engine writes to `nation.meta`; else migration + engine write. |
| `onlineGen/notice` | KV (`game_env`/`nation_env`) not daemon-populated | KV-wiring task. |
| `auction_recentLogs` | PHP `getAuctionLogRecent` source unlocated | Clarify `log_entry` filter; interim empty list. |
| `map_spyList` | engine WorldSnapshot espionage | engine-coupled map task. |
| `betting_배당` | NOT persisted by design | Contract note: FE computes; backend must NOT return it. |
| `defence_train / autorun_limit` | no column | verify meta; else defer. |

## 3. FE consumers map → W4 follow-up
- **FrontGeneralInfo** → `app/game/page.tsx` (무력 mojibake bug L92 — fix as W4a), `generals/page.tsx`, `[general]/page.tsx`, `rankings/generals.tsx`; components GeneralCard/WarStats/EquipmentSlots/TraitDisplay.
- **FrontNationInfo** → `components/game/NationBasicCard.tsx` (uncomment OMITTED fields as DTO provides), `GeneralBasicCard.tsx`, `app/game/page.tsx`, `app/game/nation/page.tsx`.
- **FrontGlobalInfo** → `components/game/GameInfo.tsx`, `lib/menu-filter.ts`, `lib/global-menu-fixture.ts`, `lib/types.ts` (recentRecord:113).
- **ChiefCenter** → `PageChiefCenter.vue`, `ChiefCenterView.vue`, web/game chief-center page.
- **GeneralList** → GeneralList.vue (AG-Grid), web/game nation/general-list route.
- **Auction/Betting/Message/Map** → `auction/page.tsx`, `MessagePanel.tsx`, `MapViewer.tsx`, betting page, `lib/api.ts`.
→ **W4** wires each enriched DTO into these pages + fixes the 무력→묠력 mojibake string-table bug first (W4a).

## 4. Recommended implementation order + shared-file risk

**Shared-file hazard:** `FrontInfoController` is co-widened by 3 groups (FrontGeneralInfo, FrontNationInfo, FrontGlobalInfo); `GeneralReadEntity` + `GeneralReadRepository` by 2 (FrontGeneralInfo, GeneralList). Parallel worktrees on these = guaranteed merge conflict. → **Foundation-first, exactly like W6.**

**Wave 0 — foundation (sequential, ONE worktree):**
- F1: add the 4 `@Column` mappings to GeneralReadEntity (`special_code, special2_code, personal_code, penalty`) + `officer_city`. `ddl-auto: validate` confirms.
- F2: `RankDataReadEntity` + `RankDataReadRepository` (the rank_data read path) — the shared blocker-breaker for the 6 war-stat fields.
- F3: shared TurnTime full-format formatter (ChiefCenter + GeneralList).
- F4: nation aggregate `@Query` (grouped, N+1-safe) on NationReadRepository.
- F5: agree the FrontInfoResponse DTO shape (the seam all 3 FrontInfo groups append to).

**Wave 1 — parallel (disjoint after foundation):**
- ChiefCenter (own controller) and GetConst/Auction/Betting/Message/Map (own controllers/DTOs) can run **immediately**, independent of Wave 0.
- After F1–F5: FrontGeneralInfo, FrontNationInfo, FrontGlobalInfo each append their fields to the agreed FrontInfoResponse seam (sequential commits on the shared controller, or staged on one branch). GeneralList builds its new controller consuming F2.

**Gate:** each DTO group verified against a PHP `GetFrontInfo`/`GetReservedCommand`/`GetConst` capture; BLOCKED fields stay null/omitted with the §2 reason logged — never invented.
