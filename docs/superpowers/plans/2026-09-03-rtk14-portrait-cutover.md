# RTK14 Portrait Cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every production-materialized RTK14 officer to the matching `opensamguk-images/portraits/rtk14/serving/portrait/<stable-id>.png` asset without name guessing or legacy-picture reuse.

**Architecture:** Keep `RTK14_STATS_JSON_B64` and generated 1,000-row scenarios untracked. During materialization, hash each source officer's seven-field identity fingerprint and join it to the committed stable officer registry, then write `<stable-id>.png` into the scenario picture field. Both web clients recognize only the reserved `10001..11000` band and resolve it to the RTK14 serving directory; legacy account icons and managed uploads keep their existing behavior.

**Tech Stack:** Python 3 standard library, Kotlin scenario importer contract, TypeScript, React, Vitest, pnpm, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-07-18-scenario-system.md`

## Global Constraints

- RTK14 source rows and materialized 1,000-row scenario JSON remain ignored and untracked.
- Stable officer IDs are exactly `10001..11000`; a missing, duplicate, or drifting fingerprint fails closed.
- `opensamguk-images` owns portrait originals and serving files; `opensamguk` stores only URL/data contracts.
- No flat-name fallback is permitted for duplicate names.
- Managed account uploads and legacy non-RTK shared icons remain backward compatible.

---

### Task 1: Web portrait serving contract

**Files:**
- Modify: `web/game/lib/portrait.ts`
- Modify: `web/gateway/lib/portrait.ts`
- Test: `web/game/__tests__/portrait.test.tsx`
- Test: `web/gateway/__tests__/portrait.test.tsx`

**Interfaces:**
- Consumes: `IMAGE_CDN_BASE` and shared `picture`/`imageServer` values.
- Produces: `RTK14_PORTRAIT_CDN` and `portraitUrl()` routing for bare or `.png` stable IDs.

- [x] **Step 1: Write failing boundary tests** for `10001`, `10001.png`, `11000`, and `11000.png` in both clients.
- [x] **Step 2: Run both focused suites and verify RED** because `RTK14_PORTRAIT_CDN` and stable-ID routing do not exist.
- [x] **Step 3: Implement minimal reserved-band routing** after the managed-upload branch and before legacy shared-icon resolution.
- [x] **Step 4: Run both focused suites and verify GREEN** with all existing upload, traversal, fallback, and legacy cases intact.

### Task 2: Source fingerprint to portrait stable-ID join

**Files:**
- Modify: `tools/rtk14/build_rtk14_stats.py`
- Test: `tools/rtk14/test_build_rtk14_stats.py`

**Interfaces:**
- Consumes: validated RTK source fields `(birth, death, L, S, I, politics, charm)`, `tools/scenario/officer-id-registry.tsv`, and `tools/scenario/officer-name-map.tsv`.
- Produces: `attach_portrait_ids(rtk, registry_path, name_map_path)`, adding an in-memory `portraitId` to every one of the 1,000 source candidates.

- [x] **Step 1: Write failing tests** proving a source fingerprint maps to the stable ID, the Korean name must agree, and missing/duplicate/drifting registry rows fail closed.
- [x] **Step 2: Run the focused Python suite and verify RED** for the missing join API and unchanged picture fields.
- [x] **Step 3: Implement the standard-library TSV loader and fingerprint join.** Compute SHA-256 from the same seven ordered values used by `refine_officers.py`; require registry and Korean name-map IDs to be exactly `10001..11000`; require every source fingerprint and Korean name to resolve exactly once.
- [x] **Step 4: Set matched and appended tuple picture fields** to `<portraitId>.png` while preserving source-number lifecycle metadata at tuple index 17.
- [x] **Step 5: Invoke the join in the CLI before `build_all`** for both XLSX and JSON source modes; keep `--dump-rtk-source-json` free of the derived `portraitId` field.
- [x] **Step 6: Run the focused Python suite and verify GREEN.**

### Task 3: Existing-world portrait migration

**Files:**
- Create: `infra/src/main/kotlin/db/migration/V48__rtk14_portrait_cutover.kt`
- Test: `infra/src/test/kotlin/opensamguk/infra/persistence/V48Rtk14PortraitCutoverMigrationTest.kt`

**Interfaces:**
- Consumes: each world's effective materialized scenario and persisted `meta.rtk14_officer_number`.
- Produces: current `general.picture` values and deferred `RegNPC`/`RegNeutralNPC` tuple pictures rewritten to the stable RTK14 PNG name.

- [x] **Step 1: Write failing Testcontainers migration tests** for an active NPC, a deferred NPC, a partial 1,000-ID mapping, and an unaffected settings-only world.
- [x] **Step 2: Run the focused migration suite and verify RED** because V48 does not exist.
- [x] **Step 3: Implement fail-closed effective-scenario resolution and exact 1,000-ID validation.**
- [x] **Step 4: Update active and deferred RTK14 general pictures transactionally** while leaving account-profile storage and worlds without RTK source metadata untouched.
- [ ] **Step 5: Run the focused migration suite and verify GREEN.**

### Task 4: Cross-repository identity and asset audit

**Files:**
- Modify: `tools/rtk14/test_build_rtk14_stats.py`
- Reference only: `../opensamguk-images/portraits/rtk14/officer-id-registry.tsv`

**Interfaces:**
- Consumes: both committed registries and all 1,000 local serving PNGs.
- Produces: verification evidence only; no third-party image bytes enter `opensamguk`.

- [x] **Step 1: Run a local audit** requiring both registries to contain exactly the same IDs, kanji names, and readings.
- [x] **Step 2: Require all 1,000 `serving/portrait/<id>.png` files** and no gaps in the reserved band.
- [x] **Step 3: Verify representative and boundary CDN URLs** return HTTP 200.

### Task 5: Full verification and delivery

**Files:**
- Modify: `docs/superpowers/plans/2026-09-03-rtk14-portrait-cutover.md`
- Create outside Git repository: `reports/opensamguk/tasks/2026-09-03-rtk14-portrait-cutover.md`

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: reviewed commit/PR, CI evidence, issue disposition, and metarepo report.

- [x] **Step 1: Run Python tests** with `python3 -m unittest tools/rtk14/test_build_rtk14_stats.py`.
- [x] **Step 2: Run both complete portrait suites, type checks, and production builds.**
- [x] **Step 3: Run repository checks** with `git diff --check` and the relevant change verifier.
- [ ] **Step 4: Commit, push, open a PR, wait for CI, and merge only when required checks pass.**
- [ ] **Step 5: Close only issues whose acceptance criteria are met or explicitly superseded by the user's temporary RTK14-source decision; leave broader scenario/default-cutover issues open with a status comment.**
- [ ] **Step 6: Record result, commit/merge SHA, verification, issue actions, and remaining risks in the task report.**
