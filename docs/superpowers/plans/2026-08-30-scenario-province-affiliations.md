# Scenario Province Affiliations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every land province a historically scoped display affiliation, prevent cross-commandery recoloring, and correct Wei's 225/228 New City counties.

**Architecture:** Extend the existing province administrative index and ownership binding rather than creating a second renderer. Actual nation ownership remains authoritative; only missing ownership receives map-only administrative metadata and a deterministic system palette. Explicit scenario city overrides handle the known runtime-parent exception.

**Tech Stack:** TypeScript, React, Vitest, Python 3, JSON scenario generator

**Spec:** `docs/superpowers/specs/2026-08-30-scenario-province-affiliations-design.md`

## Global Constraints

- Do not invent gameplay nation ownership to fill the political map.
- Chinese Han-commandery and non-Han external affiliations must use different terminology.
- Political colors are flat deterministic RGB values and remain opaque over land.
- Preserve runtime compatibility with legacy map data lacking province records.

---

### Task 1: Province affiliation binding

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Test: `web/game/__tests__/provinceMap.test.ts`

**Interfaces:**
- Consumes: `ProvinceRecordDto.administrativeSystem`, `ParentRegionRecordDto.displayName`, live `IsoCityOverlay` ownership
- Produces: `CountyAdministrativeIndex.administrativeSystemByProvince`, `ProvinceOwnershipBinding.affiliations`

- [ ] **Step 1: Write failing tests** for cross-parent rejection, Han fallback, external-system fallback, and full opaque land coverage.
- [ ] **Step 2: Run** `pnpm --dir web/game test -- provinceMap.test.ts` and confirm the new assertions fail.
- [ ] **Step 3: Implement** administrative metadata, deterministic colors, and parent-safe sample selection in `provinceMap.ts`.
- [ ] **Step 4: Run** the focused Vitest file and confirm it passes.
- [ ] **Step 5: Commit** the tested binding change.

### Task 2: Tooltip affiliation output

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Test: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Consumes: `ProvinceOwnershipBinding.affiliations`
- Produces: `IsoCountyHover.nationName` and `nationColor` for synthetic administrative affiliations while retaining `nationId: 0`

- [ ] **Step 1: Write a failing interaction test** showing an unowned external province exposes its polity name and an unowned Han province exposes local administration.
- [ ] **Step 2: Run** the focused interaction test and confirm failure.
- [ ] **Step 3: Implement** hover fallback from binding affiliation metadata.
- [ ] **Step 4: Run** focused frontend tests and typecheck.
- [ ] **Step 5: Commit** the tooltip change.

### Task 3: New City historical override

**Files:**
- Modify: `tools/scenario/han_ownership.json`
- Modify: `infra/src/main/resources/scenario/scenario_1100.json`
- Modify: `infra/src/main/resources/scenario/scenario_1110.json`
- Create: `tools/scenario/tests/test_apply_han_world.py`

**Interfaces:**
- Consumes: `rewrite(doc, code, by_jun, id_of, seat_of, che2jun, own)`
- Produces: Wei city lists containing runtime ids 590 (`상용`) and 435 (`방릉`) in scenarios 1100 and 1110

- [ ] **Step 1: Write a failing generator test** asserting the explicit override wins over Hanzhong's commandery assignment.
- [ ] **Step 2: Run** `python3 -m unittest tools.scenario.tests.test_apply_han_world -v` and confirm failure.
- [ ] **Step 3: Add** the two city overrides to Wei in both ownership records and regenerate scenarios with `python3 tools/scenario/apply_han_world.py`.
- [ ] **Step 4: Run** the generator test and `python3 tools/scenario/apply_han_world.py --check`.
- [ ] **Step 5: Commit** the scenario source and generated artifacts.

### Task 4: Integrated verification

**Files:**
- Modify: `docs/superpowers/plans/2026-08-30-scenario-province-affiliations.md` (checkbox state only)

**Interfaces:**
- Consumes: all prior task outputs
- Produces: verified branch ready for review and deployment

- [ ] **Step 1: Run** the complete `provinceMap` and `HanMapCanvas` test files.
- [ ] **Step 2: Run** `pnpm --dir web/game typecheck` and the scenario Python suite relevant to the generator.
- [ ] **Step 3: Run** repository formatting/status checks and inspect the final diff for generated-only noise.
- [ ] **Step 4: Perform** a self-review against every acceptance criterion and repair any defect found.
- [ ] **Step 5: Commit** verification documentation and prepare the task report.
