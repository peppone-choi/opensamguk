# Stub Implementation Report — LegacyParityController

**Date:** 2026-02-23  
**Endpoints Implemented:** 3 (j_autoreset, j_map_recent, j_raise_event)

---

## 1. j_autoreset — `POST /api/worlds/{worldId}/auto-reset-check`

**Legacy Reference:** `legacy/hwe/j_autoreset.php`

**What it does:** Checks whether a world should be auto-closed or auto-reset based on a scheduled reset date. The legacy PHP checks a `reserved_open` table, compares the current time against the scheduled reset date, and decides whether to:
- Close the server early (based on game state: unified/stopped + time proximity)
- Trigger a full scenario reset when the scheduled time arrives

**Implementation:**
- **New Service:** `AutoResetService` (`service/AutoResetService.kt`)
- Scheduled reset info is read from `WorldState.meta["reservedResetDate"]` (ISO-8601 string) and `WorldState.meta["reservedResetOptions"]` (map)
- Game state (`isunited`) is read from `WorldState.config["isunited"]`
- Replicates all three early-close conditions from PHP:
  1. Stopped game (isunited=2) past midpoint → close
  2. Unified (isunited>0) past 2/3 point → close  
  3. Less than 10 minutes to reset → close regardless
- When reset time arrives, calls `ScenarioService.initializeWorld()` to rebuild
- Returns `{result, affected, status, info}` matching legacy response format

**Files Created/Modified:**
- `service/AutoResetService.kt` (NEW — 130 lines)
- `controller/LegacyParityController.kt` (UPDATED)

---

## 2. j_map_recent — `GET /api/worlds/{worldId}/map-recent`

**Legacy Reference:** `legacy/hwe/j_map_recent.php`

**What it does:** Returns a cached snapshot of the world map with all city data, nation data, and recent history. Supports HTTP caching via ETag/If-None-Match for 304 Not Modified responses. Cache TTL is 10 minutes.

**Implementation:**
- **New Service:** `MapRecentService` (`service/MapRecentService.kt`)
- In-memory per-world cache with 600-second TTL (matches PHP's 600s)
- SHA-256 ETag generation from worldId + timestamp (matches PHP pattern)
- Returns full map data including:
  - Cities with coordinates, nation ownership, population, stats, supply state
  - Nations with id, name, color, level, gold, rice
  - Last 10 world history entries
  - Map theme code
  - Current year/month
- HTTP 304 Not Modified when client ETag matches cache
- Proper `ETag` and `Cache-Control` response headers

**Files Created/Modified:**
- `service/MapRecentService.kt` (NEW — 150 lines)
- `controller/LegacyParityController.kt` (UPDATED — now uses `If-None-Match` header)

---

## 3. j_raise_event — `POST /api/admin/raise-event`

**Legacy Reference:** `legacy/hwe/j_raise_event.php`

**What it does:** Admin-only endpoint (grade ≥ 6) that manually triggers a game event action. The legacy PHP uses `Event\Action::build()` to instantiate an action class by name (e.g., `ProcessIncome`, `UpdateNationLevel`) and runs it against the game environment.

**Implementation:**
- **New Service:** `AdminEventService` (`service/AdminEventService.kt`)
- Verifies admin grade ≥ 6 (matching PHP's `userGrade < 6` check)
- Resolves grade with same ADMIN role escalation logic as `AdminAuthorizationService`
- Maps legacy PHP `Event\Action\*` class names to Kotlin action types:
  - `ProcessIncome` → `process_income`
  - `ProcessSemiAnnual` → `process_semi_annual`
  - `UpdateCitySupply` → `update_city_supply`
  - `UpdateNationLevel` → `update_nation_level`
  - `RandomizeCityTradeRate` → `randomize_trade_rate`
  - `RaiseInvader` → `raise_invader`
  - `RaiseNPCNation` / `RegNeutralNPC` → `raise_npc_nation`
  - `DeleteEvent` → `delete_event`
  - `NoticeToHistoryLog` → `log`
- Passes additional args (eventId for delete, message for log)
- Returns `{result, reason, info}` matching legacy response format
- Request DTO updated to accept optional `worldId` field

**Files Created/Modified:**
- `service/AdminEventService.kt` (NEW — 160 lines)
- `controller/LegacyParityController.kt` (UPDATED — `RaiseEventRequest` now has `worldId`)

---

## Summary of Changes

| File | Action | Lines |
|------|--------|-------|
| `service/AutoResetService.kt` | NEW | ~130 |
| `service/MapRecentService.kt` | NEW | ~150 |
| `service/AdminEventService.kt` | NEW | ~160 |
| `controller/LegacyParityController.kt` | REWRITTEN | ~130 |

All three stubs have been replaced with full implementations. No TODOs, no placeholders remain.

**Note:** Compilation could not be verified (no JDK in sandbox). Code follows the same patterns as existing services (dependency injection, repository access, transaction management).
