# Scenario Map and Roster Integrity Implementation Plan

> Historical plan. Its opaque-fill and same-parent inheritance requirements were
> superseded on 2026-08-31 by direct county-province ownership with transparent
> unowned provinces. Do not use those requirements for new implementation work.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every scenario's political map follow its dated ownership source without visual holes, expose real county names everywhere in China, and reject scenario seeds whose active NPC roster differs from the committed JSON contract.

**Architecture:** Keep `han_ownership.json`, the historical county catalog, and scenario JSON as the sources of truth. The map generator must attach every Chinese land polygon to a sourced county instead of emitting `DIRECT_TERRITORY`; the frontend then resolves ownership through that county/commandery relationship rather than through an incidental city-coordinate sample. Scenario JSON carries explicit seed expectations that `ScenarioImporter` validates before writing a world.

**Tech Stack:** Python 3 generators and unittest, JSON resources, Kotlin/Spring scenario importer, TypeScript/Vitest map binding.

**Spec:** User-approved chat design on 2026-08-30: all county names visible; no Chinese `직할지`; all fifteen scenario borders reviewed and corrected; scenario 1010 starts with the full expected NPC roster.

## Global Constraints

- Historical county names come from the committed county catalog; never invent a county name.
- Chinese map output may not contain `DIRECT_TERRITORY`, `직할지`, or `직할령`.
- Gameplay ownership remains scenario-specific and follows `tools/scenario/han_ownership.json` plus explicit county overrides.
- Neutral or external territory remains distinguishable but may not render as transparent land.
- Every scenario seed declares and enforces its active roster count for both base and extended-general modes.

---

### Task 1: County-complete province geometry

**Files:**
- Modify: `tools/map/world_province_geometry.py`
- Modify: `tools/map/build_tile_grid.py`
- Test: `tools/map/tests/test_world_province_geometry.py`
- Test: `tools/map/tests/test_world_province_pipeline.py`

**Interfaces:**
- Consumes: dated county seeds, parent-region ids, gameplay city catalog.
- Produces: province records whose Chinese polygons all have real county names and non-null county linkage.

- [ ] Add a failing fixture with a seedless modern polygon inside a Chinese commandery and assert it is assigned to a real same-parent county.
- [ ] Run the focused tests and confirm failure caused by `CREATE_DIRECT_TERRITORY`.
- [ ] Replace direct-territory creation with deterministic same-parent county assignment; fail generation if a Chinese parent has no sourced county candidate.
- [ ] Package the resolved county name without `직할지` rewriting and regenerate map artifacts.
- [ ] Run focused and full map tests.

### Task 2: Data-driven province ownership binding

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Test: `web/game/__tests__/provinceMap.test.ts`

**Interfaces:**
- Consumes: `ProvinceRecordDto.cityIndex`, parent relationship, runtime city ownership.
- Produces: opaque colors for every land province, preserving neutral land as neutral instead of transparent.

- [ ] Add failing tests proving same-parent county polygons inherit the correct runtime ownership and neutral land remains opaque.
- [ ] Run focused Vitest and confirm the old direct-sample-only behavior fails.
- [ ] Bind county records to runtime ownership deterministically and add a neutral political color for genuinely unowned land.
- [ ] Run focused tests and typecheck.

### Task 3: Fifteen-scenario ownership audit and regeneration

**Files:**
- Modify: `tools/scenario/han_ownership.json`
- Modify: `tools/scenario/apply_han_world.py`
- Modify: `infra/src/main/resources/scenario/scenario_1010.json` through `scenario_1120.json` covered by the ownership source.
- Test: `tools/scenario/tests/test_apply_han_world.py`

**Interfaces:**
- Consumes: each scenario start year, nation list, commandery/county historical ownership evidence.
- Produces: disjoint nation city lists, explicit neutral/external coverage, and a complete per-scenario audit summary.

- [ ] Add a failing all-scenario test for duplicate claims, unknown ids, missing ownership basis, and unresolved Chinese political polygons.
- [ ] Audit each ownership row against its dated basis and correct commandery/county overrides.
- [ ] Regenerate all fifteen scenario JSON files with `apply_han_world.py`.
- [ ] Run `apply_han_world.py --check` and the scenario test suite.

### Task 4: NPC seed contracts

**Files:**
- Modify: `infra/src/main/resources/scenario/scenario_*.json`
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt`
- Test: `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioJsonTest.kt`
- Test: `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioImporterIT.kt`

**Interfaces:**
- Consumes: JSON `seedContract.activeGenerals.base` and `.extended`.
- Produces: pre-seed validation that aborts on roster truncation or wrong effective scenario data.

- [ ] Add failing parser/importer tests for scenario 1010 literals `174` and `229`, plus mismatch rejection.
- [ ] Decode the contract and validate the selected active roster before inserts.
- [ ] Generate literal contracts for every scenario from independently audited lifecycle rules.
- [ ] Run focused JVM tests.

### Task 5: Integrated verification and report

**Files:**
- Modify: this plan's checkbox state.
- Create: metarepo `reports/opensamguk/tasks/2026-08-30-scenario-map-roster-integrity.md` after verification.

**Interfaces:**
- Consumes: all preceding outputs.
- Produces: reviewed branch, fresh verification evidence, and task report.

- [ ] Run full map/scenario Python suites, focused frontend tests and typecheck, and focused JVM tests.
- [ ] Generate an audit table for all scenarios: start year, nations, owned/neutral cities, base/extended active NPCs, duplicate claims.
- [ ] Inspect generated diffs and run `git diff --check`.
- [ ] Commit coherent changes and record result, commits, verification, and remaining risks in the metarepo report.
