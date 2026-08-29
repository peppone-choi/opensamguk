# Han County Tooltip and Marker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill every Han land county deterministically, show county-level administrative tooltips, and replace the canvas placeholder castle with curated production markers.

**Architecture:** The shared map layer will derive a county administration/ownership index from `ProvinceIdentityMap`, `HanTiles`, and runtime city overlays. Both consumers receive the same `IsoCountyHover` callback. Marker artwork is curated in `opensamguk-images`, exported into both web apps, preloaded once, and drawn by the shared canvas without changing city activation semantics.

**Tech Stack:** Kotlin/Spring MVC, TypeScript/React, Canvas 2D, Vitest, Python asset tooling, sprite-gen standalone curation.

**Spec:** `docs/superpowers/specs/2026-08-29-han-county-map-tooltip-icons.md`

## Global Constraints

- Hover identity is the `owner` county polygon; water has no hover.
- Tooltip copy is `【<ju> | <level>】 <jun> <county>` plus owned nation on the next line.
- City activation remains marker-based and passes the existing runtime city ID.
- All behavior changes use red-green-refactor TDD.
- `opensamguk-images` owns marker originals, generator, and preview; `opensamguk` owns web exports only.

---

### Task 1: Administrative metadata contract

**Files:**
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/MapPreviewDto.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt`
- Modify: `web/game/lib/types.ts`
- Test: `infra/src/test/kotlin/opensamguk/infra/seed/MapJsonTest.kt`
- Test: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`

**Interfaces:**
- Produces: `MapCityCoord(regionName, commanderyName, isCommanderySeat)` and matching `MapPreviewCity` JSON fields.
- Consumes: `map/<code>.json cities[].meta.{ju,jun,isSeat}`; exact hovered county names come from `han-tiles.cities[owner]`.

- [ ] Write a failing `MapJson` test with literal `사예`, `경조윤`, and `true` expectations.
- [ ] Run `./gradlew :infra:test --tests '*MapJsonTest*'` and confirm the missing metadata failure.
- [ ] Parse the four fields without inventing values when metadata is absent.
- [ ] Write a failing controller JSON-contract test for the four fields.
- [ ] Run the controller test and confirm the fields are absent.
- [ ] Copy parsed metadata into `MapPreviewCity`, update TypeScript contracts, and rerun both tests green.

### Task 2: Complete county ownership and hover identity

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/index.ts`
- Test: `web/game/__tests__/provinceMap.test.ts`
- Test: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Produces: `bindCompleteProvinceOwnership(...)`, `buildCountyIndex(...)`, and `IsoCountyHover`.
- Consumes: runtime city overlays, province/commandery grids, `tiles.cities`, `tiles.juns`.

- [ ] Write failing literal fixtures proving an unmapped county inherits the nearest same-commandery city and a collision chooses nearest then lowest city ID.
- [ ] Run the two focused Vitest files and confirm blank/conflict failures.
- [ ] Implement deterministic county centroid and nearest-city binding; keep water transparent.
- [ ] Write a failing pointer test that hovers a county away from every marker and receives county/commandery identity.
- [ ] Implement `screenToCell` county hit testing while leaving `cityAt` activation untouched.
- [ ] Rerun the focused tests green and refactor shared lookup types.

### Task 3: Shared lobby and game tooltip

**Files:**
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/gateway/components/MapPreview.tsx`
- Modify: `web/game/__tests__/MapViewer.interaction.test.tsx`
- Modify: `web/gateway/__tests__/MapPreview.iso.test.tsx`

**Interfaces:**
- Consumes: `IsoCountyHover` and enriched `IsoCityOverlay` metadata.
- Produces: identical visible tooltip content in both applications.

- [ ] Change the mocked shared canvas tests first to emit `사예 / 경조윤 / 장안현`; assert the exact literal tooltip and observe failure.
- [ ] Replace `CITY_REGIONS` and city-hover tooltip state with county-hover state in both consumers.
- [ ] Preserve nation visibility rules and cursor positioning.
- [ ] Run both focused Vitest files green.

### Task 4: Curated production map markers

**Files (`opensamguk-images`):**
- Create: `originals/map-city-markers/`
- Create: `tools/build-map-city-markers.py`
- Create: `previews/map-city-markers.png`
- Modify: `.license-boundaries.json`
- Modify: `README.md`

**Files (`opensamguk` exports/runtime):**
- Create: `web/game/public/map/markers/*.png`
- Create: `web/gateway/public/map/markers/*.png`
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Test: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Produces: transparent `county`, `commandery`, and `capital` marker exports with shared anchor metadata.
- Consumes: city `isCommanderySeat`/`isCapital` and existing nation/status overlays.

- [ ] Produce multiple semantically named marker candidates and import them through sprite-gen `unpack_atlas_run.py --pngs-dir`.
- [ ] Open the Korean curation view, verify HTTP 200, compare actual-size and enlarged candidates, and record the selected design.
- [ ] Implement the selected design in the deterministic images-repo generator and add `--check` byte-drift verification.
- [ ] Write a failing canvas test that expects marker image preload/draw selection for county, commandery, and capital.
- [ ] Export the assets to both web apps and replace the placeholder rectangle renderer with anchored `drawImage` calls.
- [ ] Preserve flag, capital emphasis, event, current, selected, label, pointer, touch, and keyboard behavior; rerun focused tests green.

### Task 5: Verification, reports, integration, and deployment

**Files:**
- Create: `reports/opensamguk-images/tasks/2026-08-29-han-map-city-icons.md`
- Create: `reports/opensamguk/tasks/2026-08-29-han-county-tooltip-icons.md`

**Interfaces:**
- Consumes: all prior task outputs.
- Produces: merge commits, green CI, deployed map, and recorded risks.

- [ ] Run images license-boundary and generator drift checks.
- [ ] Run focused frontend tests, both typechecks, relevant Kotlin tests, and map Python tests.
- [ ] Run browser-level visual QA for land fill, water exclusion, county tooltip, and marker hierarchy.
- [ ] Commit each repository with the required co-author trailer, push branches, open PRs, and wait for CI.
- [ ] Merge the images PR before the application PR, deploy the application, and verify the public route.
- [ ] Write both task reports with result, commits, verification evidence, docs impact, and remaining risks.
