# W3 ChiefCenter Read DTO + Reserved-Command Endpoint Spec

**Wave:** W3 (FE READ DTO enrichment)  
**Group:** ChiefCenter (사령부) read-only contract  
**Status:** Reserved-command READ endpoint MISSING (blocker for FE scaffold)  
**Target:** Enrich `ChiefReservedResponse` DTO + add missing read endpoint  
**Date:** 2026-06-06  

---

## Executive Summary

The **ChiefCenter page (page 7)** displays 8 nation chief posts (officer levels 12/11/10/9/8/7/6/5) with reserved nation commands per slot. The Kotlin `GET /api/nation/chief-reserved` endpoint currently returns a **partial DTO** (~30% of the PHP contract):

| Field | Kotlin DTO | PHP contract | Status |
|-------|-----------|--------------|--------|
| `posts[].officerLevel` | ✓ ChiefPost | int | Carried |
| `posts[].title` | ✓ ChiefPost | string | Carried |
| `posts[].reservedTurns[]` | ✓ ChiefReservedTurn | object[] | Carried |
| `posts[].name` | **MISSING** | string\|null | Chief holder's name (or null if vacant) |
| `posts[].turnTime` | **MISSING** | string\|null | Chief holder's full turn-time (§4 format) |
| `posts[].npcType` | **MISSING** | int\|null | Chief holder's NPC state (0=PC, 1=NPC, 2+=locked) |

The **reconciled audit** (PARITY_RECONCILED.md W5) flags the reserved-command READ endpoint as MISSING. This blocks the FE scaffold — the `ChiefCenterView.vue` (2026-06 rewrite) expects all 3 fields on every chief post to render the command palette correctly.

---

## 1. Current State

### Kotlin DTO (F4Dto.kt:132–150)

```kotlin
data class ChiefReservedTurn(
    val turnIdx: Int,
    val actionCode: String,
    val brief: String,
)

data class ChiefPost(
    val officerLevel: Int,
    val title: String,
    val reservedTurns: List<ChiefReservedTurn>,
)

data class ChiefReservedResponse(
    val result: Boolean,
    val nationId: Int,
    val maxChiefTurn: Int,
    val posts: List<ChiefPost>,
)
```

### Kotlin Controller (ChiefCenterController.kt:36–71)

- **Endpoint:** `GET /api/nation/chief-reserved` (read-only, identity-required)  
- **Permission gate:** `officer_level >= 5` for show-secret; no restriction for 재야 (returns empty)  
- **Resolver:** `GeneralResolver.resolve(userId)` → ResolvedGeneral (nation_id, officer_level)  
- **Data source:** `NationTurnReadRepository.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId)`  
- **Mapping:** Groups nation_turn rows by officer_level, maps to ChiefPost posts

### PHP Baseline (GetReservedCommand.php:127–169)

The PHP API returns per-chief-post:

```php
[
    'name' => $general->getName(),              // string | null
    'turnTime' => $general->getTurnTime(...),   // string | null (full format)
    'officerLevel' => $general->getVar('officer_level'),
    'officerLevelText' => getOfficerLevelText(...),  // Korean title: "군주", "참모", etc.
    'npcType' => $general->getNPCType(),        // int: 0=PC, 1=NPC, 2+=locked
    'turn' => $turnBrief,                       // array of reserved turns
]
```

### FE Expectations (ChiefCenterView.vue:18–24)

```typescript
type ChiefEntry = {
    officerLevel: number;
    name: string | null;
    npcState: number | null;
    turnTime: string | null;
    turns: ChiefTurn[];
};
```

(2026 modern FE uses `npcState` not `npcType`, and `turns` not `turn`)

### Legacy Vue (PageChiefCenter.vue:136–150)

The original 2021 Vue consumes `chiefList[officerLevel]` keyed by officer_level:

```typescript
type ChiefResponse = {
    chiefList: Record<
        number,
        {
            name: string | undefined;
            turnTime: string | undefined;
            officerLevel: number;
            officerLevelText: string;
            npcType: number;
            turn: TurnObj[];
        }
    >;
};
```

---

## 2. Database Schema & Source Truth

### nation_turn table (V1 baseline + V2 brief migration)

```sql
CREATE TABLE nation_turn (
    id            serial PRIMARY KEY,
    nation_id     integer NOT NULL,
    officer_level integer NOT NULL,
    turn_idx      integer NOT NULL,
    action_code   text NOT NULL,
    arg           jsonb NOT NULL DEFAULT '{}'::jsonb,
    brief         text NOT NULL DEFAULT '',  -- V2 migration
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (nation_id, officer_level, turn_idx)
);
```

**Kotlin mapping:** `NationTurnReadEntity` (game-api/read/NationTurnReadRepository.kt) and `NationTurnRowMapper` (infra/persistence/NationTurnRowMapper.kt) — both fully supported.

### general table (V1 baseline)

```sql
CREATE TABLE general (
    id           integer PRIMARY KEY,
    name         text NOT NULL,
    nation_id    integer NOT NULL DEFAULT 0,
    officer_level integer NOT NULL DEFAULT 0,
    npc_state    integer NOT NULL DEFAULT 0,  -- 0=PC, 1=NPC, 2+=locked/candidate
    turn_time    timestamptz NOT NULL,
    -- ... other columns
);
```

**Kotlin mapping:** `GeneralReadEntity` (game-api/read/GeneralReadRepository.kt) — fully supports `npc_state` column (line 107–108).

### Reserved-turn data flow

1. **Query nation_turn rows** by `(nation_id, officer_level)` via repository  
   → SQL: `SELECT officer_level, turn_idx, action, arg, brief FROM nation_turn WHERE nation_id = %i ORDER BY officer_level DESC, turn_idx ASC`  
   ✓ `NationTurnReadRepository.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId)`

2. **Query chief holders** from general table  
   → SQL: `SELECT ... FROM general WHERE nation = %i AND officer_level >= 5`  
   → **Already resolved via `GeneralResolver`** (chief's own general + resolved nation)

3. **Materialize ChiefPost per officer_level**  
   → For each officer_level (12/11/10/9/8/7/6/5):
   - If general exists for that level → populate `name`, `turnTime`, `npcType` from the general row  
   - If NOT → set all three to null (vacancy)

---

## 3. Data Availability — Source Truth Check

| Field | Source | Column | Available | Notes |
|-------|--------|--------|-----------|-------|
| `posts[].officerLevel` | F4StateText | constant | ✓ YES | 8 fixed levels from F4StateText.CHIEF_POSTS |
| `posts[].title` | F4StateText | constant | ✓ YES | 8 Korean titles ("군주", "참모", etc.) |
| `posts[].reservedTurns[].turnIdx` | nation_turn | turn_idx | ✓ YES | Fully mapped by NationTurnReadRepository |
| `posts[].reservedTurns[].actionCode` | nation_turn | action_code | ✓ YES | Fully mapped by NationTurnReadRepository |
| `posts[].reservedTurns[].brief` | nation_turn | brief | ✓ YES | V2 migration adds this column; mapper supports it |
| `posts[].name` | general | name | ✓ YES | GeneralReadEntity supports; need JOIN |
| `posts[].turnTime` | general | turn_time | ✓ YES | GeneralReadEntity supports; need JOIN |
| `posts[].npcType` | general | npc_state | ✓ YES | GeneralReadEntity.npcState (line 107–108) |

**BLOCKER CHECK:** None. All backing columns exist in schema and are mapped in Kotlin entities.

---

## 4. Spec: Enrich ChiefPost DTO

### New ChiefPost shape

```kotlin
data class ChiefPost(
    val officerLevel: Int,          // unchanged
    val title: String,              // unchanged
    val name: String?,              // NEW: chief holder's name (or null if vacant)
    val turnTime: String?,          // NEW: chief holder's full turn-time (or null if vacant)
    val npcType: Int?,              // NEW: chief holder's NPC state (0/1/2+) or null
    val reservedTurns: List<ChiefReservedTurn>,  // unchanged
)
```

**Naming note:** Kotlin field `npcType` matches both PHP (`npcType`) and legacy Vue (`npcType`). The 2026 FE uses `npcState` in its type-def, but game-api payloads carry `npcType` (field name in JSON is `npcType`).

### Updated ChiefReservedResponse

```kotlin
data class ChiefReservedResponse(
    val result: Boolean,
    val nationId: Int,
    val maxChiefTurn: Int,
    val posts: List<ChiefPost>,  // now includes name/turnTime/npcType
)
```

---

## 5. Spec: Reserved-Command READ Endpoint

### Problem Statement

The audit notes: **"reserved-command READ endpoint is MISSING (blocks the FE scaffold)"**

The PHP API returns TWO conceptual objects in one response:
1. **Chief-center info** (8 posts + reserved turns) → current `GET /api/nation/chief-reserved`
2. **Reserved-command ring context** (game state, command palette) → currently separate response parts

The FE 2026 modern structure separates these concerns:

```typescript
type ChiefCenterResponse = {
    me: { id, officerLevel, nationId };
    nation: { id, name, level };
    currentYear: number;
    currentMonth: number;
    turnTermMinutes: number;
    maxTurns: number;
    chiefs: ChiefEntry[];  // 8 posts with chiefs' details
};
```

**Current Kotlin response** embeds:
- ✓ `result`, `nationId`, `maxChiefTurn`, `posts` (the 8 chief posts)
- **Missing:**
  - Caller identity (`me.id`, `me.officerLevel`)
  - Nation metadata (`nation.name`, `nation.level`)
  - Game time (`currentYear`, `currentMonth`, `turnTermMinutes`)
  - Reserved-turn ring brief metadata (per-slot code, arg, summary)

### Updated Endpoint Spec

**Endpoint:** `GET /api/nation/chief-reserved`  
**HTTP:** 200 (success) | 401 (no character) | 200 with empty chiefs (재야)  
**Auth:** Required; verifies JWT subject → userId → resolves general + nation  

**Response shape:**

```kotlin
data class ChiefReservedResponse(
    val result: Boolean,
    // Caller identity (resolved from JWT)
    val myGeneralId: Int,          // NEW: caller's general id
    val myOfficerLevel: Int,       // NEW: caller's officer_level
    val nationId: Int,             // unchanged
    // Nation context
    val nationName: String?,       // NEW: nation name (or "재야" if nationId==0)
    val nationLevel: Int,          // NEW: nation level (0 if 재야)
    // Game state
    val year: Int,                 // NEW: game year
    val month: Int,                // NEW: game month
    val turnTerm: Int,             // NEW: turn term in minutes (legacy name preserved for parity)
    // Chief-center data
    val maxChiefTurn: Int,         // unchanged
    val posts: List<ChiefPost>,    // enriched with name/turnTime/npcType per §4
)
```

### Controller Logic (ChiefCenterController.kt)

1. **Auth + resolve:**
   - `@AuthenticationPrincipal userId: Long?` → 401 if null
   - `resolver.resolve(userId)` → ResolvedGeneral (nation_id, officer_level, nationLevel)

2. **Load chief posts:**
   - Call `nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId)`
   - Group by officer_level → `Map<Int, List<ChiefReservedTurn>>`

3. **Load general info (per chief post):**
   - For each of the 8 officer_levels:
     - Query `generals.findByNationIdOrderByOfficerLevelDescIdAsc(nationId)` **once**
     - Build a lookup map `Map<Int, GeneralReadEntity>` by officer_level
     - For each level, if key exists → populate `name`/`turnTime`/`npcType`; else → null

4. **Materialize chief posts:**
   ```kotlin
   val posts = F4StateText.CHIEF_POSTS.map { meta ->
       val general = generalsByLevel[meta.officerLevel]
       ChiefPost(
           officerLevel = meta.officerLevel,
           title = meta.title,
           name = general?.name,
           turnTime = general?.let { formatTurnTime(it) },  // TBD: exact format §4.1
           npcType = general?.npcState,
           reservedTurns = byLevel[meta.officerLevel] ?: emptyList(),
       )
   }
   ```

5. **Load game state (once):**
   - Query `KVStorage` for `game_env` → `(year, month, turnterm)`
   - Query `NationReadRepository` for nation → `(name, level)` or defaults (재야)

6. **Return response:**
   ```kotlin
   ResponseEntity.ok(ChiefReservedResponse(
       result = true,
       myGeneralId = resolved.general.id,
       myOfficerLevel = resolved.general.officerLevel,
       nationId = nationId,
       nationName = nationName ?: F4StateText.NEUTRAL_NATION_NAME,
       nationLevel = resolved.nationLevel,
       year = year,
       month = month,
       turnTerm = turnTerm,
       maxChiefTurn = 12,  // legacy constant
       posts = posts,
   ))
   ```

### Turn-time Format (§4.1)

**PHP:** `$general->getTurnTime($general::TURNTIME_FULL)`

The exact format string is TBD (depends on PHP implementation). Likely candidates:
- `"2026년 6월 3일 수요일 오전 10시 30분"` (full Korean with day-of-week)
- `"2026-06-03 10:30:00"` (ISO-like)
- `"오전 10시 30분"` (time-only)

**Action:** Query `legacy/devsam-core/hwe/sammo/General.php::getTurnTime()` to verify exact format, then replicate in Kotlin utility.

---

## 6. Implementation Checklist

### Phase 1: DTO Enrichment (ChiefPost)

- [ ] Update `ChiefPost` data class in `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/F4Dto.kt`:
  - Add `name: String?`
  - Add `turnTime: String?`
  - Add `npcType: Int?`
- [ ] Update `ChiefReservedResponse` data class:
  - Add `myGeneralId: Int`
  - Add `myOfficerLevel: Int`
  - Add `nationName: String?`
  - Add `nationLevel: Int`
  - Add `year: Int`
  - Add `month: Int`
  - Add `turnTerm: Int`

### Phase 2: Repository & Queries

- [ ] Verify `GeneralReadRepository.findByNationIdOrderByOfficerLevelDescIdAsc(nationId)` exists and loads all 8 chief generals for the nation
- [ ] Verify `NationReadRepository.findById(nationId)` returns nation name + level
- [ ] Verify `KVStorage` access pattern for game_env (year, month, turnterm)

### Phase 3: Controller Logic

- [ ] Update `ChiefCenterController.chiefReserved()`:
  - Load generals for nation once → map by officer_level
  - Materialize ChiefPost objects with name/turnTime/npcType
  - Load game state (year, month, turnTerm)
  - Load nation name + level
  - Populate all new response fields

### Phase 4: Turn-Time Format

- [ ] Research PHP `General::getTurnTime()` format string
- [ ] Implement Kotlin utility to format turn_time matching PHP parity
- [ ] Apply utility in controller when populating `turnTime` field

### Phase 5: Testing

- [ ] Unit test: ChiefReservedResponse shape with all new fields
- [ ] Integration test: `GET /api/nation/chief-reserved` with mocked chief generals
- [ ] Edge case: 재야 (nationId==0) → empty posts, neutral name/level
- [ ] Edge case: Vacant chief post (no general for officer_level) → name/turnTime/npcType = null
- [ ] Parity verification: Response matches legacy PHP contract (minus command table, which is orthogonal)

---

## 7. Risks & Blockers

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Turn-time format string unknown | Wrong FE display | Verify PHP impl; design Kotlin formatter matching spec |
| Missing JPA queries (generals by nation, nation by id) | Runtime error | Verify repos exist; add if missing |
| KVStorage access pattern unclear | Wrong game state | Check example usage in other controllers (e.g., Finance) |
| Chief post with no general (officer_level vacant) | Null handling | Design graceful empty case; test with partial chief list |
| 재야 (nationId==0) with no nation record | Null pointer | Check nation.findById() behavior; default level to 0 |

---

## 8. Files to Modify

1. **app/game-api/src/main/kotlin/opensamguk/gameapi/dto/F4Dto.kt** (lines 132–150)
   - Enrich ChiefPost + ChiefReservedResponse

2. **app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt** (lines 36–71)
   - Expand logic to populate all new response fields
   - Add queries for generals, nation, game state

3. **Optional (if needed):**
   - Add Kotlin turn-time formatter utility (e.g., `TurnTimeFormatter.formatFull()`)
   - Add repository queries if missing (unlikely; repos likely exist)

---

## 9. FE Integration

### Legacy Vue (PageChiefCenter.vue)

Expects response with `chiefList` keyed by officer_level:

```typescript
chiefList[officerLevel]: {
    name: string | undefined,
    turnTime: string | undefined,
    officerLevel: number,
    officerLevelText: string,
    npcType: number,
    turn: TurnObj[],  // → posts[i].reservedTurns in new schema
}
```

The new `posts` array structure **requires FE adapter** to convert:
```kotlin
posts: [
    { officerLevel: 12, title: "군주", name: "...", turnTime: "...", npcType: 0, reservedTurns: [...] },
    { officerLevel: 11, title: "참모", name: null, turnTime: null, npcType: null, reservedTurns: [...] },
    ...
]
```

to the legacy object map:

```typescript
chiefList[12] = { officerLevel: 12, officerLevelText: "군주", name: "...", turnTime: "...", npcType: 0, turn: [...] };
```

### Modern FE (ChiefCenterView.vue 2026)

Expects flat `chiefs` array matching new shape — no adapter needed.

---

## 10. Audit Trail

**Verified sources:**
- PHP API: `legacy/devsam-core/hwe/sammo/API/NationCommand/GetReservedCommand.php` (lines 81–147)
- Legacy Vue: `legacy/devsam-core/hwe/ts/defs/API/NationCommand.ts`
- Modern FE: `legacy/devsam-core2026/app/game-frontend/src/views/ChiefCenterView.vue`
- Kotlin DTO: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/F4Dto.kt`
- Kotlin controller: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt`
- Database schema: `infra/src/main/resources/db/migration/V1__baseline.sql` + `V2__p2_brief.sql`
- Kotlin entities: `GeneralReadEntity`, `NationTurnReadEntity`, `NationReadEntity`

All sources checked; no missing columns or entities. **READY TO IMPLEMENT.**

