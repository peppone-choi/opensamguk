# Cross-Agent Critique: ScenarioImporter npcMode/blockGeneralCreate seed

**Branch:** `loop-parity-2026-06-16-a`  
**Diff:** 20 lines (+) across 3 files  
**Reviewer:** adversarial-reviewer (compound-engineering)  
**Date:** 2026-06-16

---

## Summary

The change adds two constructor parameters — `npcMode: Int = 0` and `blockGeneralCreate: Int = 0` — to `ScenarioImporter`, writes them into `world_state.config` at seed time, and adds a single IT assertion that the keys exist in the seeded config. The defaults match legacy `install.php` (`npcmode_0` checked, `block_general_create_0` checked). The change is scoped to the `infra.seed` bootstrap package, uses only `JdbcTemplate`, and does not touch `ChangeRecorder` or `EntityManager`.

**Key context:** The frontend (`web/gateway` lobby page, `web/game` CharacterClaim/GameInfo) and the game-api read layer (`ServerBasicInfoController`, `FrontInfoController`, `JoinController`) already consume `npcmode` and `block_general_create` from `world_state.config`. Prior to this change, the keys were **absent** in a fresh seed, causing the controllers to fall back to `0` (PHP-parity behavior) but the frontend to mis-gate the entrance 3-button flow because `null ?? 0` and `null ?? 1` semantics diverged across components. The diff is a **config-gap closure**, not a new feature.

---

## Findings

### 1. `[P0] Parity: defaults match legacy install.php` — CLEARED

**Evidence:**
- `legacy/devsam-core/hwe/install.php:117-130`: `block_general_create_0` has `checked` attribute (default 0 = "가능"); `npcmode_0` has `checked` attribute (default 0 = "불가").
- `ScenarioImporter.kt:46,52`: `private val npcMode: Int = 0`, `private val blockGeneralCreate: Int = 0`.
- `j_server_basic_info.php:59-60,93-94`: PHP reads these from `game_env` KV and maps `npcmode` → text array `[0=>'불가',1=>'가능',2=>'선택 생성']`; `block_general_create` → raw int via `Util::toInt()`.
- The Kotlin `ServerBasicInfoController.kt:78,89` and `FrontInfoController.kt:431,440` apply the same `intOrNull(...) ?: 0` fallback, so a seeded `0` produces identical observable behavior to the fallback path.

**Verdict:** Default parity is correct. No divergence from PHP install defaults.

---

### 2. `[P0] One-daemon-write rule: not violated` — CLEARED

**Evidence:**
- `ScenarioImporter.kt` class-level KDoc (pre-existing): explicitly states this is "JDBC-only (NOT a one-daemon-write-rule violation)" and that the package `opensamguk.infra.seed` is outside the scanned write-path packages (`engine.{flush,turn,run}` and `infra.persistence`).
- The change adds only two `jsonObject` entries (`"npcmode" to npcMode`, `"block_general_create" to blockGeneralCreate`) inside the existing `insertWorldState` method, which uses `JdbcTemplate.update(...)` with a raw INSERT statement. No `EntityManager`, no `ChangeRecorder`, no gameplay write.
- Architecture guard tests (`DaemonNoEntityManagerTest`, `InfraNoEntityManagerTest`) are not affected because the seed package is excluded from their scan scope.

**Verdict:** No rule violation. The write is bootstrap-only, idempotent, and outside the daemon gameplay path.

---

### 3. `[P1] Seed bootstrap policy: caller chain passes defaults only` — ADVISORY

**Evidence:**
- `SeedBootstrap.kt:81` (the production boot path): `val importer = ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = scenarioCode)` — **only `scenarioCode` is passed positionally**. `npcMode` and `blockGeneralCreate` fall back to their `= 0` defaults.
- `ScenarioImporterIT.kt:74` (test): `return ScenarioImporter(scenario = scenario, cities = cities)` — same, defaults only.
- `ScenarioImporterIT.kt:81` (1030 test): `return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_1030")` — defaults only.
- There is **no env var or `@Value` injection** for `npcMode` or `blockGeneralCreate` in `SeedBootstrap`, `ScenarioSeedRunner`, or `docker-compose.yml`.

**Implication:** The change correctly closes the "fresh seed has no keys" gap for the standard scenario (1010, 1030), but it does **not** provide a mechanism for an admin to seed a non-default configuration (e.g., `npcMode=1` for a 빙의-가능 server) at boot time. The LEDGER entry acknowledges this: "기존 라이브 서버는 별도 어드민 config 편집 필요(백로그)."

**Risk:** This is a known, documented backlog item. The change does not introduce new risk; it removes the "missing key causes frontend mis-gate" risk for the default case. The advisory is informational: the env-var plumbing for non-default seeds remains unimplemented.

**Verdict:** Advisory only. Not a blocker.

---

### 4. `[P1] Test coverage: IT asserts key presence, not value semantics` — ADVISORY

**Evidence:**
- `ScenarioImporterIT.kt:167-168`: `assertTrue(config.contains("\"npcmode\""), ...)` and `assertTrue(config.contains("\"block_general_create\""), ...)` — these are **string-contains** assertions on the raw JSON text. They verify the key is present, not that the value is `0`.
- No assertion that `config["npcmode"]` parses to `0`, or that the value type is numeric (not string). The `jsonObject` helper produces a `LinkedHashMap<String, Any>`; the values are boxed `Int`s, but the IT does not verify deserialization round-trip.
- The `ServerBasicInfoControllerTest.kt` and `FrontInfoControllerTest.kt` already have unit-level tests that verify `npcMode` and `blockGeneralCreate` parsing from mocked config (e.g., `seedWorld(mapOf("npcmode" to 1, ...))` → `jsonPath("$.game.npcMode").value(1)`), so the read-path semantics are covered elsewhere.

**Implication:** The IT is minimal but sufficient for its purpose: "did the seed write the keys?" The value semantics are tested downstream. However, a slightly stronger assertion — e.g., parsing the JSON and checking `config["npcmode"] == 0` — would catch a hypothetical bug where the value was accidentally written as a string `"0"` instead of integer `0`. The `jsonObject` helper uses `to` which boxes as `Int`, so this is unlikely, but the IT does not mechanically prove it.

**Verdict:** Advisory. Consider adding a parsed-value assertion in a follow-up, but not a blocker.

---

### 5. `[P2] Engine killturn: npcmode still hard-coded to 0` — ADVISORY (documented backlog)

**Evidence:**
- `DaemonLoopConfig.kt:284-293`: `baselineKillturn = EffectiveGameConst.killturn(turnTerm, npcmode = 0)` — the engine's killturn calculation hard-codes `npcmode = 0` with a comment explaining that the seed does not persist `npcmode` for the standard scenario, and that a non-zero variant (빼섭 1030) would need meta-key plumbing.
- This diff writes `npcmode` to `config`, but the engine reads from `meta` (or hard-codes). The comment says "npcmode==1(빼섭) 변형이 도입되면 meta 키로 흘려야 한다."

**Implication:** The engine killturn path remains disconnected from the seeded `npcmode`. This is a **pre-existing** architectural gap, not introduced by this diff. The diff does not make it worse — it actually improves the situation by making the config key available for future engine consumption. However, there is a latent inconsistency: if an admin manually edits `world_state.config` to `npcmode=1`, the frontend and game-api will reflect it, but the engine's `baselineKillturn` will still compute as if `npcmode=0`.

**Verdict:** Advisory. Documented in code comments. Not a blocker for this diff, but should be tracked in the backlog for the 빼섭/변형-모드 workstream.

---

### 6. `[P0] No regression: existing tests unaffected` — CLEARED

**Evidence:**
- The diff touches only `ScenarioImporter.kt` (adds two params + two jsonObject entries), `ScenarioImporterIT.kt` (adds two assertions), and `LEDGER.md` (adds entry 23).
- No changes to `ServerBasicInfoController`, `FrontInfoController`, `JoinController`, `GameInfo.tsx`, `CharacterClaim.tsx`, or any other consumer.
- The `ScenarioBootIT` (engine e2e) does not assert `npcmode`/`block_general_create` values; it only checks that the world seeds and ticks. The added config keys are inert to the boot test.
- `JoinControllerTest.kt` already seeds `block_general_create=1` manually in its test setup; it is unaffected because it does not use `ScenarioImporter`.

**Verdict:** No regression risk. Existing test contracts are preserved.

---

## Verdict: cleared

The change is a **minimal, correct config-gap closure** that:
1. Matches PHP `install.php` defaults (`npcmode=0`, `block_general_create=0`).
2. Does not violate the one-daemon-write rule (JDBC-only bootstrap write, no `EntityManager`/`ChangeRecorder`).
3. Does not break seed bootstrap policy (idempotent, emptiness-gated, standard scenario defaults).
4. Removes the frontend entrance-gating misbehavior caused by missing config keys in a fresh seed.
5. Leaves no test regressions.

**Advisory items (non-blocking):**
- Non-default `npcMode`/`blockGeneralCreate` boot-time configuration requires future env-var plumbing (acknowledged in LEDGER).
- IT could assert parsed value, not just key presence (minor coverage gap).
- Engine `killturn` hard-coding remains disconnected from the seeded value (pre-existing, documented).

**Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>**
