# W3 Specification: FrontGlobalInfo Global-Gates Enrichment

**Group ID:** `frontglobalinfo_global_gates`  
**Module:** FrontInfoController / FrontGlobalInfo DTO  
**Scope:** GameInfo header (§3) + GlobalMenu flag gates (§4)  
**Status:** PARTIAL — 8 fields present; ~25 menu-gate flags + recentRecord missing

---

## 1. Current State (Kotlin FrontGlobalInfo DTO)

**File:** `/app/game-api/src/main/kotlin/opensamguk/gameapi/dto/IdentityDto.kt:43-53`

**Present fields (8):**
```kotlin
data class FrontGlobalInfo(
    val year: Int,
    val month: Int,
    val turnterm: Int,
    val scenario: String,
    val scenarioText: String,
    val generalCount: Int,
    val nationCount: Int,
    val cityCount: Int,
    val npcCount: Int,
)
```

**Current Controller:** `/app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt:140-156`
- Assembles only the 8 scalar fields from `world_state` entity
- Hard-codes `recentRecord = emptyList()` (line 135, 68)

---

## 2. PHP Grand Truth (GetFrontInfo.php)

**Legacy source:** `/legacy/devsam-core/hwe/sammo/API/General/GetFrontInfo.php:158-232`

The `generateGlobalInfo()` method assembles **32 fields** from:
- `game_env` KVStorage namespace (read-only, cached, 19 keys):
  ```php
  'scenario_text', 'extended_general', 'fiction', 'npcmode',
  'join_mode', 'autorun_user', 'turnterm', 'turntime',
  'lastVote', 'develcost', 'msg',
  'online_nation', 'online_user_cnt',
  'year', 'month', 'startyear',
  'maxgeneral', 'refreshLimit', 'server_cnt'
  ```
- Database queries (ng_auction count, plock, general GROUP BY npc, vote state, tournament state)
- Computed gates (isTournamentActive, isBettingActive, isLocked, etc.)

---

## 3. Missing Fields (Detailed Inventory)

### A. GameInfo Header Fields (Used by web/game/components/game/GameInfo.tsx)

| Field | Type | PHP Source | Purpose | Status |
|-------|------|-----------|---------|--------|
| `title` | `string?` | `game_env.scenario_text` or const mapName | Map scenario name | **MISSING** |
| `serverName` | `string?` | Derived from lobby / env | Server designation | **MISSING** (may be None in single-server) |
| `serverCnt` | `int?` | `game_env.server_cnt` | Active servers count | **MISSING** |
| `extendedGeneral` | `boolean?` | `game_env.extended_general` | 확장 special flag | **MISSING** |
| `isFiction` | `boolean?` | `game_env.fiction` | 가상 vs 사실 mode | **MISSING** |
| `npcMode` | `int?` | `game_env.npcmode` | 0=불가/1=가능/2=선택생성 | **MISSING** |
| `onlineUserCnt` | `int?` | `game_env.online_user_cnt` | Active players | **MISSING** |
| `apiLimit` | `int?` | `game_env.refreshLimit` | Turn refresh quota | **MISSING** |
| `createdUserCnt` | `int?` | Count of user generals | User-claimed generals | **MISSING** (fallback: generalCount) |
| `createdNPCCnt` | `int?` | Count of NPC generals | NPC pool size | **MISSING** (fallback: npcCount) |
| `generalCntLimit` | `int?` | `game_env.maxgeneral` | Total general cap | **MISSING** |
| `lastExecuted` | `string?` | `game_env.turntime` | Last turn execution time | **MISSING** |
| `auctionCount` | `int?` | `SELECT count(*) FROM ng_auction WHERE finished=0` | Open auctions | **MISSING** |
| `isTournamentActive` | `boolean?` | `game_env.tournament > 0` | Tournament running | **MISSING** |
| `tournamentType` | `string?` | `game_env.tnmt_type` | Tournament format (e.g., 중원) | **MISSING** |
| `tournamentState` | `string?` | `game_env.tournament` state enum | Current tournament phase | **MISSING** |
| `serverLocked` | `boolean?` | `SELECT plock FROM plock WHERE type="GAME"` | Server locked status | **MISSING** |

### B. GlobalMenu Flag Gates (condHighlightVar / condShowVar)

| Flag | Type | PHP Source | Purpose | MenuItems | Status |
|------|------|-----------|---------|-----------|--------|
| `nationBetting` | `boolean?` | Computed (not stored) | Show 천통국 베팅 highlight | "천통국 베팅" | **MISSING** |
| `vote` | `boolean?` | `game_env.lastVote` exists & not expired | Show 설문조사 highlight | "설문조사" | **MISSING** |
| `npcMode` | `int?` | `game_env.npcmode` | Gate "빙의일람" visibility | "기타정보" submenu | **PARTIAL** (exists as enum, not boolean) |
| `isTournamentApplicationOpen` | `boolean?` | `game_env.tournament == 1` | Tournament registration phase | (implicit, used in GameInfo) | **MISSING** |
| `isBettingActive` | `boolean?` | `game_env.tournament == 6` | Betting phase active | (implicit, used in GameInfo) | **MISSING** |
| `isunited` | `boolean?` | `game_env.isunited` | 통일 state for diplomacy UI | (implicit, legacy gates) | **MISSING** |
| `joinMode` | `int?` | `game_env.join_mode` | Join restrictions | (implicit, legacy gates) | **MISSING** |
| `autorunUser` | `boolean?` | `game_env.autorun_user` | Auto-run enabled | (implicit) | **MISSING** |
| `develCost` | `int?` | `game_env.develcost` | Dev mode flag / cost | (implicit) | **MISSING** |
| `noticeMsg` | `string?` | `game_env.msg` | Server notice | (implicit) | **MISSING** |

### C. Recent Action Records

| Field | Type | PHP Source | Purpose | Status |
|-------|------|-----------|---------|--------|
| `recentRecord` | `List<String>` | `general_record` / `world_history` tables | Recent player + world history logs | **HARD-CODED EMPTY** |

**Sub-structure:** Based on `generateRecentRecord()` (lines 104-156):
- `recentRecord.history` — last 15 world history rows (world_history, nation_id=0)
- `recentRecord.global` — last 15 global action logs (general_record, general_id=0, log_type='history')
- `recentRecord.general` — last 15 personal action logs (general_record, general_id={caller}, log_type='action')
- Pagination: `lastWorldHistoryID`, `lastGeneralRecordID` (request params, not in global)
- Flush flags: `flushHistory`, `flushGlobal`, `flushGeneral` (optimization flags)

---

## 4. Data Source Availability Analysis

### A. game_env KV Namespace (World-State Cached)

**Backing:** `world_state.config` JSONB (P0-A baseline) or KVStorage table (if implemented)

**Current Kotlin read:** WorldStateReadEntity reads `config` + `meta` JSONB maps; no KV table exists in V1__baseline.sql.

| Key | Column | V1 Schema | Availability | Note |
|-----|--------|-----------|--------------|------|
| year | world_state.current_year | ✓ | **AVAILABLE** | Direct column |
| month | world_state.current_month | ✓ | **AVAILABLE** | Direct column |
| turnterm | world_state.tick_seconds ÷ 60 | ✓ | **AVAILABLE** | Computed |
| scenario_text | world_state.config['scenario_text'] | ✓ | **AVAILABLE** | JSONB read |
| extended_general | world_state.config['extended_general'] | ✓ | **AVAILABLE** | JSONB read |
| fiction | world_state.config['fiction'] | ✓ | **AVAILABLE** | JSONB read |
| npcmode | world_state.config['npcmode'] | ✓ | **AVAILABLE** | JSONB read |
| join_mode | world_state.config['join_mode'] | ✓ | **AVAILABLE** | JSONB read |
| autorun_user | world_state.config['autorun_user'] | ✓ | **AVAILABLE** | JSONB read |
| turntime | world_state.config['turntime'] (lastExecuted) | ✓ | **AVAILABLE** | JSONB read |
| lastVote | world_state.config['lastVote'] | ✓ | **AVAILABLE** | JSONB read (foreign key) |
| develcost | world_state.config['develcost'] | ✓ | **AVAILABLE** | JSONB read |
| msg | world_state.config['msg'] | ✓ | **AVAILABLE** | JSONB read |
| online_nation | world_state.config['online_nation'] | ✓ | **AVAILABLE** | JSONB read |
| online_user_cnt | world_state.config['online_user_cnt'] | ✓ | **AVAILABLE** | JSONB read |
| startyear | world_state.config['startyear'] | ✓ | **AVAILABLE** | JSONB read |
| maxgeneral | world_state.config['maxgeneral'] | ✓ | **AVAILABLE** | JSONB read |
| refreshLimit | world_state.config['refreshLimit'] | ✓ | **AVAILABLE** | JSONB read |
| server_cnt | world_state.config['server_cnt'] | ✓ | **AVAILABLE** | JSONB read |
| isunited | world_state.config['isunited'] | ✓ | **AVAILABLE** | JSONB read (not confirmed in current schema docs) |
| tournament | world_state.config['tournament'] | ✓ | **AVAILABLE** | JSONB read |
| tnmt_type | world_state.config['tnmt_type'] | ✓ | **AVAILABLE** | JSONB read |

### B. Database Queries

| Data | Source Query | V1 Schema | Availability | Note |
|------|--------------|-----------|--------------|------|
| auctionCount | `SELECT count(*) FROM ng_auction WHERE finished=0` | ✗ | **MISSING SOURCE** | No ng_auction table in V1 baseline; PHP uses ng_auction, Kotlin has auction table |
| isLocked (serverLocked) | `SELECT plock FROM plock WHERE type="GAME"` | ✗ | **MISSING SOURCE** | No plock table in V1 baseline |
| genCount (user/npc breakdown) | `SELECT npc, count(no) FROM general GROUP BY npc` | ✓ | **AVAILABLE** | Use GeneralReadRepository; already counts by npcState |

### C. Vote Status (PHP VoteInfo::fromArray)

| Data | Source | V1 Schema | Availability |
|------|--------|-----------|--------------|
| lastVoteID | `game_env['lastVote']` KV value (vote ID) | ✓ | **AVAILABLE** (as JSONB) |
| lastVote detail | `vote_poll` table lookup + end_at validation | ✓ | **AVAILABLE** (VotePollRepository exists) |

---

## 5. Missing Source Blockers

**CRITICAL — Fields with NO opensamguk persisted source (Cannot render faithfully):**

1. **auctionCount** — Requires `ng_auction` table (PHP legacy). V1 baseline has `auction` table (P0-A migration), but:
   - Table exists: `/infra/src/main/resources/db/migration/V1__baseline.sql:320-336`
   - Status field: `status auction_status NOT NULL` (ENUM: OPEN, FINALIZING, FINISHED, CANCELED)
   - Fix: Query `SELECT count(*) FROM auction WHERE status IN ('OPEN', 'FINALIZING')`

2. **isLocked (serverLocked)** — Requires `plock` table. V1 baseline has NO plock table.
   - **BLOCKER:** Must add `plock` table to V1 migration OR map to an alternative world_state column
   - Interim: Hard-code `false` or check a world_state.meta['locked'] flag

3. **serverName** — No column/KV in V1 baseline; lobby context only
   - **BLOCKER:** May be None in single-server setup; fallback to empty string

4. **createdUserCnt** — Requires counting user generals (npc_state=0) separately from all generals
   - Source: `SELECT count(*) FROM general WHERE npc_state=0` (available)
   - Note: Currently hard-coded to `generalCount`; need separate query

5. **createdNPCCnt** — Requires counting NPC generals (npc_state > 0)
   - Source: `SELECT count(*) FROM general WHERE npc_state > 0` (available)
   - Note: Currently hard-coded to `npcCount`; need separate query

6. **recentRecord (history + logs)** — Requires `log_entry` table (P0-A baseline)
   - Table exists: `/infra/src/main/resources/db/migration/V1__baseline.sql:249-266`
   - Mapping: `WorldLogReadRepository` already exists; filters by scope (SYSTEM) + category (HISTORY/SUMMARY)
   - **BLOCKER for general-level logs:** Log entries do not distinguish between general_id=0 (world) and personal logs; must filter by:
     - `scope='SYSTEM', category IN ('HISTORY','SUMMARY')` for world logs
     - `scope='GENERAL', general_id=?, category='ACTION'` for personal logs
   - Implementation status: WorldLogReadRepository only returns world logs; no general-level query exists

---

## 6. Enrichment Plan

### Phase 1: Add Missing DTO Fields

**File:** `/app/game-api/src/main/kotlin/opensamguk/gameapi/dto/IdentityDto.kt:43-53`

Add the following optional fields to `FrontGlobalInfo`:

```kotlin
data class FrontGlobalInfo(
    val year: Int,
    val month: Int,
    val turnterm: Int,
    val scenario: String,
    val scenarioText: String,
    val generalCount: Int,
    val nationCount: Int,
    val cityCount: Int,
    val npcCount: Int,
    
    // ── GameInfo header fields (spec §3) ─────────────────────────
    val title: String? = null,
    val serverName: String? = null,
    val serverCnt: Int? = null,
    val extendedGeneral: Boolean? = null,
    val isFiction: Boolean? = null,
    val npcMode: Int? = null,
    val onlineUserCnt: Int? = null,
    val apiLimit: Int? = null,
    val createdUserCnt: Int? = null,
    val createdNPCCnt: Int? = null,
    val generalCntLimit: Int? = null,
    val lastExecuted: String? = null,
    val auctionCount: Int? = null,
    val isTournamentActive: Boolean? = null,
    val tournamentType: String? = null,
    val tournamentState: String? = null,
    val serverLocked: Boolean? = null,
    
    // ── GlobalMenu gate flags (spec §4) ───────────────────────────
    val nationBetting: Boolean? = null,
    val vote: Boolean? = null,
    val isunited: Boolean? = null,
    val joinMode: Int? = null,
    val autorunUser: Boolean? = null,
    val develCost: Int? = null,
    val noticeMsg: String? = null,
    val isTournamentApplicationOpen: Boolean? = null,
    val isBettingActive: Boolean? = null,
)
```

### Phase 2: Enrich buildGlobal() in FrontInfoController

**File:** `/app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt:140-156`

Expand `buildGlobal()` to:
1. Extract all `game_env` JSONB keys from world_state.config
2. Query auction count via AuctionRepository
3. Query user general count (npc_state=0) via GeneralReadRepository
4. Query NPC count breakdown (npc_state>0) via GeneralReadRepository
5. Optionally fetch vote detail via VotePollRepository
6. Set derived boolean gates (isTournamentActive, isBettingActive, isTournamentApplicationOpen)
7. Handle missing sources gracefully (null/fallback)

### Phase 3: Add recentRecord Enrichment

**Current:** Hard-coded `emptyList()` (lines 68, 135)

**Options:**
- **Option A (Minimal):** Return empty list (status quo; lowest effort)
- **Option B (Full):** 
  - Extend FrontInfoController constructor to inject WorldLogReadRepository
  - Query world logs (scope=SYSTEM, category IN HISTORY/SUMMARY) → top 15
  - Query general logs (scope=GENERAL, general_id={caller}, category=ACTION) → top 15
  - Return combined structure matching PHP `generateRecentRecord()`

**Note:** Pagination (lastWorldHistoryID, lastGeneralRecordID) is a request param; FrontInfoResponse.recentRecord is currently `List<String>`. Consider if this needs to evolve into a structured object with history/global/general sublists + pagination state.

### Phase 4: Add Missing Data Sources (if P0-B extends schema)

1. **plock table** — If serverLocked is critical:
   - Add migration: `CREATE TABLE plock (type VARCHAR(20) PRIMARY KEY, plock BOOLEAN);`
   - Or map to world_state.meta['locked'] flag
   - Query in buildGlobal(): `SELECT plock FROM plock WHERE type='GAME' LIMIT 1`

2. **ng_auction → auction table** — Already in V1 baseline; use `auction` table
   - Query: `SELECT count(*) FROM auction WHERE status IN ('OPEN', 'FINALIZING')`
   - Inject: AuctionRepository (if P6 auction is complete) or raw JdbcTemplate

---

## 7. Repositories Required

**Existing (✓ used):**
- WorldStateReadRepository (world_state entity)
- GeneralReadRepository (general entity; use countByNpcState)
- NationReadRepository (nation entity)
- CityReadRepository (city entity)
- VotePollRepository (vote_poll entity; check if injectable)
- WorldLogReadRepository (log_entry entity; exists, world-level only)

**New/Extended:**
- **AuctionRepository** — count open auctions (if not already P6-complete)
- **GeneralLogReadRepository** (or extend WorldLogReadRepository) — query personal-level logs by general_id + category

---

## 8. Test Coverage

1. Unit test: buildGlobal() with various world_state.config payloads (nulls, missing keys, type coercion)
2. Integration test: GET /api/front-info with a live DB; verify all optional fields populated or null
3. Contract test: GameInfo.tsx renders with missing fields (graceful fallbacks)
4. E2E test: GlobalMenu filters correctly using menu-gate flags (condShowVar/condHighlightVar evaluation)

---

## 9. Risk Summary

| Risk | Mitigation |
|------|-----------|
| JSONB key typos (game_env config) | Type-safe constant map (Enum or sealed class) |
| Missing plock table | Hard-code false or add migration (see Phase 4) |
| Type mismatches (int→boolean) | Explicit null coercion; log warnings for unexpected types |
| Pagination state lost (recentRecord) | Define clear contract: List<String> (current) or StructuredRecentRecord (future) |
| Vote expiry logic | Fetch lastVote ID and validate end_at < now via VotePollRepository |
| Performance (multiple queries per request) | All reads are fast (single world_state row, indexed aggregates); acceptable |

---

## 10. Frontend Impact

**Files consuming FrontGlobalInfo:**
- `/web/game/components/game/GameInfo.tsx` — Renders all 13 header cells; graceful null fallbacks present
- `/web/game/lib/menu-filter.ts` — Filters menu using condShowVar gates; requires boolean/truthy values
- `/web/game/lib/types.ts` — TS type contract FrontGlobalInfo (already declares all fields as optional)

**No breaking changes:** All new fields are optional; existing code continues to work.

---

## Appendix: PHP Grand-Truth Reference

**GetFrontInfo.php return array keys (lines 202-232):**
```
scenarioText, extendedGeneral, isFiction, npcMode, joinMode, startyear, year, month,
autorunUser, turnterm, lastExecuted, lastVoteID, develCost, noticeMsg, onlineNations,
onlineUserCnt, apiLimit, auctionCount, isTournamentActive, isTournamentApplicationOpen,
isBettingActive, isLocked, tournamentType, tournamentState, tournamentTime, genCount,
generalCntLimit, serverCnt, lastVote
```

**Suggested mapping to Kotlin FrontGlobalInfo:**
- `scenarioText` → `scenarioText` (present)
- `year` → `year` (present)
- `month` → `month` (present)
- `turnterm` → `turnterm` (present)
- `generalCount` ← filtered from genCount[npc=0] (present, needs refinement)
- `npcCount` ← sum of genCount[npc>0] (present, needs refinement)
- All others → new optional fields (listed in Phase 1)

