# Review: Admin server config edit (PATCH /api/admin/game-settings + frontend editor)

**Branch:** `loop-admin-config-edit-2026-06-16` vs `origin/main`  
**Scope:** Backend PATCH endpoint, read surface `editableFields`, frontend `GameSettingsControl`, tests.  
**Reviewer:** adversarial  
**Date:** 2026-06-16

---

## 🔴 P0 — Blocking

*None.*

---

## 🟡 P1 — Significant (fix before merge)

*None.*

---

## 🟢 P2 — Advisory (acceptable, document or fix at leisure)

### 1. `bearer()` token extraction diverges between AdminReadController and AdminWriteController

**File:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:117`  
**Evidence:**
- `AdminWriteController.bearer()` (line 117): `authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)`
- `AdminReadController.bearer()` (line 86-89): `if (authorization == null || !authorization.startsWith("Bearer ")) return null; return authorization.substring(7).ifBlank { null }`

**Scenario:** Admin sends `Authorization: bearer lowercase` or `Authorization: Bearer ` (7 chars, empty token). AdminWriteController accepts the lowercase prefix (fine) but returns empty string `""` instead of null for `Bearer ` (space after but no token). `requireAdmin()` then passes `""` to `verifier.isValid("")` which returns false → 401. So the outcome is the same, but the token extraction contract is inconsistent. If a future refactor changes `verifier.isValid()` to treat empty string differently, the gate behavior diverges silently.

**Fix:** Unify `bearer()` into a shared utility or make both controllers use identical logic (exact case + `.ifBlank { null }`).

---

### 2. JPA write to `world_state.config` does not propagate to running game-engine daemon

**File:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:48-86`  
**Evidence:**
- The comment on lines 17-18 states: "server management mutations that do NOT touch game-state (no ChangeRecorder / one-daemon-write rule violation)."
- However, `npcmode` and `block_general_create` are **game-state gating values** consumed by the engine during general creation and possession flows.
- The engine loads `world_state` into `InMemoryTurnWorld` at boot time (`WorldSnapshotLoader`). The daemon's in-memory `config` bag is the source of truth for these values during turn processing.
- Admin PATCH updates DB via JPA `save()`, but the running daemon never re-reads the row. The `InMemoryTurnWorld` config stays stale until daemon restart.

**Scenario:**
1. Game engine running with `npcmode=0` (불가).
2. Admin PATCH sets `npcmode=1` (가능) via admin panel.
3. DB row updated. Frontend shows new value.
4. Player tries to 빙의 — engine still reads `npcmode=0` from in-memory `InMemoryTurnWorld`, denies 빙의.
5. Admin confused: "I enabled it, why doesn't it work?"

**This is a composition failure, not a one-daemon-write rule violation.** The write path is correct (JPA for admin, not ChangeRecorder). But the read-consistency model between admin-JPA and daemon-memory is broken for config values that the engine actually uses.

**Fix options:**
- (a) Document the limitation: "config changes require daemon restart to take effect."
- (b) Add a daemon-side config refresh hook (e.g., Redis pub/sub or periodic poll) for the specific keys the engine reads.
- (c) Move these gating values to a separate table/column that the engine reads per-request rather than caching.

**Confidence:** 75 — the scenario is mechanically constructible from the code. The daemon's boot-only load pattern is visible in `WorldSnapshotLoader` and `InMemoryTurnWorld` architecture.

---

### 3. `LinkedHashMap` insertion order is not guaranteed across round-trip persistence

**File:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:73`  
**Evidence:**
- Line 73: `val nextConfig = LinkedHashMap(entity.config)` — preserves order at write time.
- But `entity.config` is typed as `Map<String, Any?>`, and the JPA `@Convert` converter (`MetaJsonConverter`) deserializes from JSONB. The converter may return a regular `HashMap` or `LinkedHashMap` depending on the JSON library's default.
- The test `patch game-settings preserves insertion order` (line 152-173) mocks `world.save()` and asserts on the in-memory entity object. It does NOT test actual DB round-trip.

**Scenario:**
1. Config keys stored as `{"z":1,"a":2}` in DB (JSON object keys have no inherent order guarantee in PostgreSQL jsonb, though PostgreSQL preserves input order in practice).
2. Converter reads back as `HashMap` → iteration order is hash-based, not insertion order.
3. Admin PATCH adds `npcmode`. New keys appear in hash-order, not at the end.
4. If downstream code (e.g., PHP golden comparison, log serialization) depends on key order, this diverges.

**Fix:** Add an integration test that actually persists and re-reads from DB to verify order preservation, or explicitly document that jsonb key order is not a parity target.

---

### 4. No backend rate limiting or optimistic locking on singleton `world_state` row

**File:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt:48-86`  
**Evidence:**
- `world.findById(1)` → `world.save(entity)` is a read-modify-write with no `@Version` optimistic lock or `SELECT FOR UPDATE`.
- Two admins PATCH simultaneously: both read same state, both modify, last write wins. Config key A's change from admin 1 could be silently overwritten by admin 2's config key B if they touch different keys, because the entire `config` bag is rewritten.

**Scenario:**
1. Admin 1 reads config `{npcmode:0, block_general_create:0}`.
2. Admin 2 reads same config.
3. Admin 1 PATCH `npcmode=1`. Saves `{npcmode:1, block_general_create:0}`.
4. Admin 2 PATCH `block_general_create=1`. Saves `{npcmode:0, block_general_create:1}`.
5. Result: `npcmode` reverted to 0 silently.

**Fix:** Add `@Version` to `WorldStateReadEntity` and handle `OptimisticLockingFailureException`, or use `SELECT FOR UPDATE` in the patch method.

---

### 5. Frontend `parseInt` vs backend `toIntOrNull` can diverge on edge-case strings

**File:** `web/gateway/app/admin/page.tsx:1058`  
**Evidence:**
- Frontend: `parseInt(raw, 10)` — `"1.5"` → `1`, `" 1 "` → `1`, `"0x10"` → `0` (stops at x).
- Backend: `(raw as? String)?.toIntOrNull()` — `"1.5"` → `null`, `" 1 "` → `null` (whitespace not trimmed), `"0x10"` → `null`.

**Scenario:**
- Frontend sends a value that `parseInt` accepts but `toIntOrNull` rejects. Since frontend uses `<select>` with controlled option values, this is unlikely in normal use. But if the `editableFields` schema ever adds a free-text `number` type, this divergence becomes reachable.

**Fix:** Use consistent parsing. Frontend could validate with `Number.isInteger(parsed) && parsed >= 0 && parsed <= 2` before sending, or backend could accept `String.trim().toIntOrNull()` to match `parseInt`'s whitespace tolerance.

---

### 6. Test uses Kotlin `assert()` instead of JUnit assertions

**File:** `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminWriteControllerTest.kt:59-61, 170-172`  
**Evidence:**
- Lines 59-61: `assert(entity.config["npcmode"] == 1)` — Kotlin `assert()` is disabled by default in JVM unless `-ea` flag is passed. In standard Gradle test runs, assertions are NOT enabled. These assertions are silently no-ops.
- Line 170-172: `assert(keys == listOf("a", "b", "c", "npcmode"))` — same issue.

**Scenario:**
- The `assert()` calls pass even if the condition is false, because `-ea` is not enabled. The test appears green but the actual state verification is not running.

**Fix:** Replace with `org.junit.jupiter.api.Assertions.assertEquals()` or `assertThat()` from AssertJ. These are always active regardless of JVM flags.

---

## 🧪 Testing Gaps

1. **No IT for DB round-trip order preservation** — the `LinkedHashMap` test is a mock test only.
2. **No test for concurrent PATCH** — optimistic locking or row-level conflict is untested.
3. **No test for daemon config refresh** — the stale-memory issue is architectural, not unit-testable, but should be documented.
4. **No frontend test** for `GameSettingsControl` (no Playwright/component test for admin panel save flow).

---

## Verdict: cleared with advisory notes

No blocking issues. The feature is safe to merge with the following post-merge follow-ups:

1. **P2.6 (test assertions)** — Replace Kotlin `assert()` with JUnit assertions in `AdminWriteControllerTest`. This is the highest-priority advisory because the test gives false confidence.
2. **P2.2 (daemon stale config)** — Document that config changes require daemon restart, or implement a refresh mechanism. This is a product behavior issue, not a code bug.
3. **P2.1 (bearer divergence)** — Unify `bearer()` extraction logic across admin controllers.
4. **P2.4 (concurrent write)** — Add optimistic locking if multiple admins are expected.

The ADMIN gate is correctly enforced. The whitelist validation is correct. The one-daemon-write rule is not violated (JPA write path is appropriate for admin-only config). Frontend-backend API contract matches.
