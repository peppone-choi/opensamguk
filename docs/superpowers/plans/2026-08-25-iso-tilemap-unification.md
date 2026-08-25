# Iso Tile Map Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render lobby, main, and map-page surfaces through one shared isometric tile canvas without a `han` to `che` fallback.

**Architecture:** Move projection math and the generalized canvas into `@opensamguk/ui`; keep API/wrapper policy in each app and normalize live cities into shared overlays. Extend the terrain endpoint with validated map-code file selection while preserving conditional caching.

**Tech Stack:** React 19, TypeScript 5.7, Canvas 2D, Vitest/Testing Library, Kotlin 2.1, Spring MVC, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-25-iso-tilemap-unification-design.md`

## Global Constraints

- Existing golden and frozen baselines must not be deleted or weakened.
- `data/map/han-tiles.json` remains the only committed CHGIS-derived tile payload; original shapefiles, `han-places.json`, and `terrain-grid.json` remain uncommitted.
- City overlay conversion uses `col = x * cols / width` and `row = y * rows / height` independently.
- One logical commit must end with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task 1: Revise the architecture decision

**Files:**
- Modify: `.ai/decisions.md`

**Interfaces:**
- Consumes: ADR-LITE-039/040/042/044.
- Produces: revised ADR-LITE-044 with canonical isometric projection and rollback path.

- [ ] **Step 1: Replace ADR-LITE-044 with the approved revision**

Record canonical projection, palette rendering, shared ownership, CHGIS constraints, consequences, and rollback in the existing ADR slot.

- [ ] **Step 2: Inspect the decision diff**

Run: `git diff --check -- .ai/decisions.md && git diff -- .ai/decisions.md`
Expected: one coherent ADR replacement with no whitespace errors.

### Task 2: Make terrain files map-code aware

**Files:**
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt`

**Interfaces:**
- Consumes: configured default path `data/map/han-tiles.json`, query `mapCode: String = "han"`.
- Produces: safe sibling resolution `<mapCode>-tiles.json`, unchanged ETag/cache/body behavior.

- [ ] **Step 1: Write failing selection and cache regression tests**

Use a temporary directory containing literal `han-tiles.json` and `che-tiles.json`; assert `?mapCode=che` returns only the che literal, its ETag, and 304 on repeat. Assert `../secret` is 404.

- [ ] **Step 2: Run the red gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.TerrainMapControllerTest --rerun-tasks`
Expected: FAIL because the controller ignores `mapCode`.

- [ ] **Step 3: Implement validated sibling selection**

Accept `@RequestParam(defaultValue = "han") mapCode`; allow only `[a-z0-9_]+`; resolve `<mapCode>-tiles.json` from the configured path's parent; retain size/mtime ETag, cache control, content type, and exact bytes.

- [ ] **Step 4: Run the green gate and inspect XML**

Run the command from Step 2, then inspect `app/game-api/build/test-results/test/TEST-opensamguk.gameapi.controller.TerrainMapControllerTest.xml` for zero failures.

### Task 3: Promote the shared renderer with test-first contracts

**Files:**
- Create: `web/shared/src/isoMap.ts`
- Create: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/index.ts`
- Modify: `web/shared/package.json`
- Create: `web/game/__tests__/fixtures/che-tiles.ts`
- Preserve and retarget: `web/game/__tests__/HanMapCanvas.test.ts`
- Create: `web/game/__tests__/che-tiles.golden.test.ts`
- Create: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Preserve unchanged: `web/game/__tests__/isoMap.test.ts`
- Keep compatibility re-export after consumers migrate: `web/game/lib/isoMap.ts`
- Remove after consumers migrate: `web/game/components/game/HanMapCanvas.tsx`

**Interfaces:**
- Produces: `mapCityToTile(city, grid, source): {col,row}`, `HanMapCanvas`, `HanTiles`, `IsoCityOverlay`, `IsoMapInteraction`.
- `HanMapCanvas` props include `mapCode`, optional `tiles`, `terrainUrl`, `cities`, `sourceSize`, `hideCityNames`, `selectedCityId`, `currentCityId`, `onCityHover`, `onCityActivate`, and `onMissing`.

- [ ] **Step 1: Rewrite shared-canvas tests before moving code**

Assert unequal-axis conversion literals, a deterministic command snapshot/hash for the synthetic che fixture, and observable draw commands for terrain, nation fill, flag, capital star, event badge, supply dim, current/selected outlines, city name, zoom, pan, hover, and activate.

- [ ] **Step 2: Run the red frontend gate**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts`
Expected: FAIL because shared exports and overlay contracts do not exist.

- [ ] **Step 3: Move projection math and implement the generalized canvas**

Copy the existing projection implementation without semantic changes, adapt imports to package-relative paths, add palette ownership/road/overlay drawing, axis-specific coordinate normalization, hit-testing, and controlled callbacks. Fetch `terrainUrl(mapCode)` without fallback substitution.

- [ ] **Step 4: Export shared contracts and run green tests**

Run: `pnpm --filter @opensamguk/ui typecheck && pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts __tests__/isoMap.test.ts`
Expected: both suites pass; the existing isoMap suite is unchanged.

### Task 4: Migrate lobby first

**Files:**
- Create: `web/gateway/__tests__/MapPreview.iso.test.tsx`
- Rewrite: `web/gateway/components/MapPreview.tsx`
- Modify only if obsolete selectors remain: `web/gateway/app/globals.css`

**Interfaces:**
- Consumes: shared `HanMapCanvas`; `MapPreviewResponse` normalized into `IsoCityOverlay[]`.
- Produces: lobby canvas with zoom/pan, hover tooltip, city-name toggle, no CDN map fallback.

- [ ] **Step 1: Write failing lobby behavior tests**

Assert the requested terrain URL contains the response `mapCode`, no `.map-bg`/`.map-road` nodes exist, wheel changes draw scale, pointer drag changes origin, and hover emits the existing tooltip content.

- [ ] **Step 2: Run the lobby red gate**

Run: `pnpm --filter @opensamguk/web-gateway exec vitest run __tests__/MapPreview.iso.test.tsx`
Expected: FAIL because the current preview is a fixed DOM scale with CDN images.

- [ ] **Step 3: Replace the DOM world with the shared canvas**

Keep preview fetch, nation lookup, title/caption, tooltip, and name-toggle policy; remove CDN map selection and marker DOM; pass normalized cities and callbacks to `HanMapCanvas`.

- [ ] **Step 4: Run gateway green gates**

Run: `pnpm --filter @opensamguk/web-gateway exec vitest run __tests__/MapPreview.iso.test.tsx && pnpm --filter @opensamguk/web-gateway typecheck`
Expected: tests and typecheck pass.

### Task 5: Migrate main map and dedicated page

**Files:**
- Rewrite: `web/game/__tests__/MapViewer.interaction.test.tsx`
- Rewrite: `web/game/__tests__/MapViewer.props.test.tsx`
- Rewrite: `web/game/__tests__/MapViewer.asset.test.tsx`
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/game/app/game/map/page.tsx`
- Modify: `web/game/__tests__/game-map-page.test.tsx`
- Modify only if obsolete selectors remain: `web/game/app/globals.css`

**Interfaces:**
- Consumes: shared canvas callbacks and existing `MapViewerProps`.
- Produces: selection/navigation/touch policies with all visuals on the same canvas; map page always uses the same renderer.

- [ ] **Step 1: Rewrite the three requested suites to the new observable contract**

Cover map-code URL selection, absence of CDN assets, zoom/pan, every overlay item, hover, navigation, selection, disabled click, current city, live merge, name toggle, and touch two-tap behavior.

- [ ] **Step 2: Run the main-map red gate**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/MapViewer.interaction.test.tsx __tests__/MapViewer.props.test.tsx __tests__/MapViewer.asset.test.tsx __tests__/game-map-page.test.tsx`
Expected: FAIL because the current DOM/CDN renderer lacks the shared canvas contract.

- [ ] **Step 3: Replace marker DOM and unify the map page**

Keep fetch/live merge/title/toggles/tooltip and routing policy in `MapViewer`; normalize overlays and call shared canvas. Update the dedicated page to render `MapViewer`/shared canvas for every map code and remove the old local component/imports.

- [ ] **Step 4: Run game green gates**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts __tests__/isoMap.test.ts __tests__/MapViewer.interaction.test.tsx __tests__/MapViewer.props.test.tsx __tests__/MapViewer.asset.test.tsx __tests__/game-map-page.test.tsx && pnpm --filter @opensamguk/web-game typecheck`
Expected: all targeted suites and typecheck pass.

### Task 6: Verify, report, and commit

**Files:**
- Create in the metarepo (outside the project Git repository): `reports/opensamguk/tasks/2026-08-26-iso-tilemap-unify.md`

**Interfaces:**
- Produces: result, commit, verification, risks, and before/after interaction matrix.

- [ ] **Step 1: Prove fallback removal and CHGIS scope**

Run: `rg -n "CDN_MAPS|cdnMapCode|map-bg|map-road|return .*che" web/game/components/game/MapViewer.tsx web/gateway/components/MapPreview.tsx` and `git status --short data/map tools/map`.
Expected: no fallback path; no forbidden CHGIS artifacts.

- [ ] **Step 2: Run fresh full relevant verification**

Run backend controller tests, both frontend app test suites, three package typechecks, `python3 -m unittest discover -s tools/map/tests`, and `git diff --check`. Confirm Gradle success via output plus test XML, not exit code alone.

- [ ] **Step 3: Write the task report**

Include result, exact verification counts, remaining risks, expected-value rationale, and a before/after matrix with terrain, roads, castle, name, nation color, flag, capital, event, supply, current, selected, tooltip, click, keyboard, touch, zoom, and pan rows.

- [ ] **Step 4: Commit one logical change**

Run:

```bash
git add .ai/decisions.md docs/superpowers/specs/2026-08-25-iso-tilemap-unification-design.md docs/superpowers/plans/2026-08-25-iso-tilemap-unification.md app/game-api web
git commit -m "feat: unify maps on shared isometric renderer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: one commit on `work/opensamguk/iso-tilemap-unify`.
