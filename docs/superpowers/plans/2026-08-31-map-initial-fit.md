# Map Initial Fit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first game and gateway map view fit the complete Han grid to the real CSS container without clipping at any supported DPR.

**Architecture:** Keep sizing and projection in the shared `HanMapCanvas`. The component will measure both CSS axes, allocate a DPR-scaled backing store, and distinguish untouched fit state from a user-modified view so responsive refits do not erase intentional navigation.

**Tech Stack:** React 19, TypeScript, Canvas 2D, ResizeObserver, Vitest, Testing Library, Next.js 15

**Spec:** `docs/design/map-initial-fit.md`

## Global Constraints

- Preserve the canonical 768×669 `han` grid and province SSoT.
- Do not add ROAD edges or change scenario ownership data.
- Game and gateway must consume one shared viewport contract.
- Keep canvas coordinates in backing pixels and UI dimensions in CSS pixels.
- Preserve existing wheel, pinch, selection, hover, and hitbox behavior.

---

### Task 1: Full-grid initial projection

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.test.ts`

**Interfaces:**
- Consumes: `centeredView(width: number, height: number, grid: GridSize): IsoView`
- Produces: `initialView(width: number, height: number, grid: GridSize, tiles: HanTiles, dpr?: number): IsoView`, now defined as the full-grid centered fit contract.

- [x] **Step 1: Write the failing full-grid tests**

Add cases using wide and tall containers. Project all four grid corners with `cellToScreen` and assert every coordinate lies within `[0,width] × [0,height]`; assert the view center resolves to the grid center and that DPR changes only scale backing coordinates proportionally.

- [x] **Step 2: Run the focused test and confirm RED**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts`

Expected: the existing 河南尹-focused `initialView` clips at least one full-grid corner.

- [x] **Step 3: Implement the minimal shared initial view**

Make `initialView` return a `centeredView` for the whole grid. Retain the exported signature for downstream compatibility while removing the commandery-span zoom from initial rendering.

- [x] **Step 4: Run the focused test and confirm GREEN**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts`

Expected: all tests pass.

- [x] **Step 5: Commit the projection contract**

Commit: `fix(map): fit the full Han grid on first view`

### Task 2: Real container sizing and responsive refit

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Consumes: `HTMLElement.clientWidth`, `HTMLElement.clientHeight`, `effectiveDpr`, `fitScale`, `viewAt`, `clampView`.
- Produces: a `HanMapCanvas` resize lifecycle where untouched views refit and interacted views preserve center and CSS zoom.

- [x] **Step 1: Write failing component tests**

Stub the container at 320×480 and 1000×500, verify canvas CSS/backing dimensions, then change the stubbed size and dispatch resize. Assert untouched state equals the new `initialView`. Exercise DPR values 1, 1.5, 2, and 3. Add a wheel zoom before resize and assert its center cell and CSS-scale relationship survive.

- [x] **Step 2: Run the interaction test and confirm RED**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.interaction.test.tsx`

Expected: canvas height remains `round(width × 0.53)` and untouched resize preserves the obsolete view instead of refitting.

- [x] **Step 3: Implement measured sizing and view intent**

Give the shared map root `height: 100%`, measure `box.clientHeight` with an explicit safe fallback only when layout reports zero, and set canvas CSS/backing sizes from both measured axes. Track whether wheel, buttons, drag, or pinch modified the view. Recenter/refit untouched views; for modified views preserve the old center and CSS zoom across size/DPR changes while clamping to the new fit minimum.

- [x] **Step 4: Run focused tests and confirm GREEN**

Run: `pnpm --filter @opensamguk/web-game exec vitest run __tests__/HanMapCanvas.test.ts __tests__/HanMapCanvas.interaction.test.tsx __tests__/isoMap.test.ts`

Expected: all tests pass.

- [x] **Step 5: Commit responsive sizing**

Commit: `fix(map): size the viewport from its container`

### Task 3: Consumer and release verification

**Files:**
- Modify only if a regression requires it: `web/gateway/__tests__/MapPreview.iso.test.tsx`
- Modify only if a regression requires it: `web/game/__tests__/MapViewer.interaction.test.tsx`
- Create after completion: meta report `reports/opensamguk/tasks/2026-08-31-map-initial-fit.md`

**Interfaces:**
- Consumes: the shared `HanMapCanvas` contract from Tasks 1–2.
- Produces: CI, deployment, browser, and operational evidence for the small PR.

- [x] **Step 1: Run consumer regressions**

Run game and gateway map consumer tests plus the full focused shared-map suite. Confirm both surfaces render the shared component without local viewport math.

- [x] **Step 2: Run static and production gates**

Run game and gateway type checks, game and gateway production builds, and `git diff --check`.

- [ ] **Step 3: Review and create the PR**

Request an independent code review, address verified findings with RED→GREEN tests, push the branch, and create one PR.

- [ ] **Step 4: Merge and promote**

After CI and review pass, merge, wait for image creation, promote PEP with `include_engine=false`, and verify API health, engine health, and public route.

- [ ] **Step 5: Browser smoke and report**

At desktop and mobile viewport sizes, capture the login map preview and game map where authentication permits. Confirm no clipping, scroll/zoom controls, backing-store DPR, and console errors. Record commits, PR/merge SHA, checks, image tag, promotion run, route status, screenshots, and residual risks in the meta report.

## Self-Review

- Spec coverage: initial full-grid fit, actual two-axis container size, resize, DPR 1/1.5/2/3, mobile, shared consumers, interactions, release, and browser smoke all map to explicit tasks.
- Placeholder scan: no deferred implementation placeholders are present; conditional consumer edits are restricted to observed regressions.
- Type consistency: `IsoView`, `GridSize`, `initialView`, and the existing DPR/backing coordinate contract retain their current exported types.
