# Map Visual Containment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Han map city buildings, flags, state indicators, and county labels readable without painting or clipping them outside their canonical county province.

**Architecture:** `provinceMap.ts` will provide deterministic visual-anchor and containment primitives derived only from the province identity map. `HanMapCanvas.tsx` will consume those primitives to select CSS-pixel LOD tiers, render only complete visuals, and use canonical province labels while leaving ownership and command coordinates unchanged.

**Tech Stack:** React, TypeScript, Canvas 2D, Vitest, Testing Library, Next.js, pnpm

**Spec:** `docs/superpowers/specs/2026-09-01-map-visual-containment-design.md`

## Global Constraints

- Work only in the `bin/start-task` worktree.
- `data/map/han-tiles.json` and the province identity PNG remain the map SSoT.
- Every painted city visual and label must remain inside its canonical province.
- Detailed markers use exactly 16/24/32/48 CSS px and remain DPR-invariant.
- Do not edit icon masters in `opensamguk`; this task changes renderer behavior only.
- Preserve click, touch, keyboard, hover, ownership, command, replay, and public-route contracts.

---

### Task 1: Deterministic interior visual anchors

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Test: `web/game/__tests__/provinceMap.test.ts`

**Interfaces:**
- Consumes: `ProvinceIdentityMap`, canonical seat `col`/`row`, and `provinceId`.
- Produces: `buildProvinceVisualAnchors(map): readonly ProvinceVisualAnchor[]` and a deterministic anchor lookup used only for rendering.

- [ ] **Step 1: Write failing anchor tests**

Add a boundary-seat fixture whose deepest cell is unambiguous and a symmetric fixture whose tie must resolve by distance to the seat, then row and column.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `pnpm --dir web/game test -- provinceMap.test.ts`

Expected: failure because the visual-anchor interface does not exist.

- [ ] **Step 3: Implement the minimal anchor builder**

Compute boundary distance within each province, retain maximum-clearance candidates, and apply deterministic tie-breaking. Do not mutate `resolveProvincePlacement` or ownership binding.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `pnpm --dir web/game test -- provinceMap.test.ts`

Expected: all province-map tests pass.

### Task 2: DPR-invariant marker and label metrics

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Test: `web/game/__tests__/HanMapCanvas.test.ts`

**Interfaces:**
- Consumes: canvas backing `scale`, `dpr`, marker level, and measured label text.
- Produces: pure marker-tier, draw-box, visible-box, overlay-box, and label-metric helpers.

- [ ] **Step 1: Write failing metric tests**

Assert 16/24/32/48 CSS marker tiers, identical CSS boxes at DPR 1/1.5/2/3, an 11px minimum CSS label, and DPR-scaled stroke/font backing metrics.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `pnpm --dir web/game test -- HanMapCanvas.test.ts`

Expected: current 32/48/64 marker behavior and backing-pixel label font fail the new contract.

- [ ] **Step 3: Implement pure metric helpers**

Use fixed CSS tier values and multiply once by effective DPR. Keep `imageSmoothingEnabled=false` for sprite draws and expose only the helpers needed by tests and the renderer.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `pnpm --dir web/game test -- HanMapCanvas.test.ts`

Expected: all canvas unit tests pass.

### Task 3: Complete-contained rendering without clipping

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/index.ts`
- Test: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Consumes: Task 1 anchors and Task 2 visual metrics.
- Produces: actual drawn-tier hitboxes, canonical province labels, complete-contained marker/flag/status/selection painting, and overview glyph fallback.

- [ ] **Step 1: Write failing interaction tests**

Record transforms, draw boxes, paths, fonts, and text. Assert that city rendering never calls province clip; a boundary seat uses its interior anchor; detailed painting occurs only for a box fully inside the province; a too-small province uses the outlined overview glyph; and canonical province text is used when its box fits.

- [ ] **Step 2: Run the focused interaction test and verify RED**

Run: `pnpm --dir web/game test -- HanMapCanvas.interaction.test.tsx`

Expected: clip-count, boundary-anchor, label-source, or incomplete-paint assertions fail.

- [ ] **Step 3: Implement contained rendering**

Remove baked city clip paths. Build anchors once per province map, preserve canonical ownership placement, choose the largest fitting visual tier, paint a bordered overview glyph if needed, and derive hitboxes from the drawn tier. Use `provinceRecords[provinceId].displayName` for inline labels and keep full hover tooltip behavior.

- [ ] **Step 4: Run focused interaction and unit tests**

Run: `pnpm --dir web/game test -- HanMapCanvas.interaction.test.tsx HanMapCanvas.test.ts provinceMap.test.ts MapViewer.interaction.test.tsx`

Expected: all focused tests pass.

### Task 4: Cross-surface verification and visual smoke

**Files:**
- Modify: `reports/opensamguk/tasks/2026-09-01-map-visual-containment.md`

**Interfaces:**
- Consumes: completed renderer and tests.
- Produces: verification evidence, screenshots, browser-smoke results, risks, commit/PR/deployment/PEP references.

- [ ] **Step 1: Run static and automated verification**

Run:

```bash
pnpm --dir web test
pnpm --dir web typecheck
pnpm --dir web/game build
pnpm --dir web/gateway build
git diff --check
```

Expected: zero failures; pre-existing warnings are recorded separately.

- [ ] **Step 2: Run browser matrix smoke**

Capture the gateway preview and authenticated game map at DPR 1/1.5/2/3 where the browser supports emulation. Check initial fit, zoom tiers, city-name toggle, hover, selection/click hitbox, mobile width, and resize. Record any surface blocked by authentication with substitute evidence and residual risk.

- [ ] **Step 3: Review, commit, PR, CI, deploy, and PEP**

Request code review, resolve Critical/Important findings, commit with the required trailer, open a small PR, wait for CI, merge, verify the production image and health endpoints, promote PEP, and verify the public route separately from deployment success.

- [ ] **Step 4: Complete the task report**

Record user-visible result, files, commits, PR, merge SHA, verification, production image, PEP, health, public route, exceptions, residual risks, and the next smallest task.

