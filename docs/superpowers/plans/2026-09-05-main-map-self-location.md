# Main Map Initial Focus and Self-Location Implementation Plan

> **For Codex:** Execute this plan with `superpowers:test-driven-development`, then run
> `superpowers:verification-before-completion` and request an independent code review before
> reporting completion.

**Goal:** Start the live main map approximately 1.82 times closer to the player's current city and
render an accessible, unmistakable self-location overlay without changing other map consumers or
overriding manual navigation.

**Architecture:** Add one optional intent-level focus profile to `MapViewer`/`HanMapCanvas` and opt
in only from `GameChrome`. Keep camera math and the self-location drawing layer inside the shared
canvas. Replace the 500 ms opacity blink with a deterministic slow phase that is disabled by
`prefers-reduced-motion`.

**Tech Stack:** React 19, TypeScript 5.7, Canvas 2D, Vitest, Testing Library, pnpm.

---

## Task 1: Red-test the main-only initial focus contract

**Files:**

- Modify: `web/game/__tests__/HanMapCanvas.test.ts`
- Modify: `web/game/__tests__/MapViewer.props.test.tsx`
- Modify: `web/game/__tests__/GameChrome.main-map.test.tsx`

**Step 1: Add a failing pure camera test**

Extend the `등급 → 최소 표시 zoom 매핑` suite with these assertions:

- `initialFocusedView(..., current, 'current-city-close')` centers `current` and targets CSS scale
  `10` after DPR conversion and existing maximum clamping.
- Omitting the profile preserves the current county-label threshold behavior.
- Omitting the current position preserves `initialView` even when the profile is present.

Use more than one DPR so CSS scale and backing-store scale cannot be conflated.

**Step 2: Add failing prop-plumbing tests**

- `MapViewer.props.test.tsx`: rendering `<MapViewer initialFocus="current-city-close" />` forwards the
  exact profile to the mocked shared canvas.
- `GameChrome.main-map.test.tsx`: the live main-board call includes
  `initialFocus: 'current-city-close'` while keeping `live`, `showMe`, and `currentCityId`.

**Step 3: Run the focused tests and capture RED**

Run:

```bash
cd web/game
corepack pnpm test -- __tests__/HanMapCanvas.test.ts \
  __tests__/MapViewer.props.test.tsx \
  __tests__/GameChrome.main-map.test.tsx
```

Expected: failures because the profile type/prop and close-focus math do not exist.

## Task 2: Implement the optional close-focus profile

**Files:**

- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/game/components/game/GameChrome.tsx`

**Step 1: Define the narrow shared contract**

In `HanMapCanvas.tsx`, export:

```ts
export type InitialFocusProfile = 'current-city-close';
```

Add `initialFocus?: InitialFocusProfile` to `HanMapCanvasProps`. Extend `initialFocusedView` with an
optional final profile argument. For `current-city-close`, target `10 * effectiveDpr(dpr)` and clamp
with the same `maxScaleForDpr(dpr) * 0.9` ceiling used by the existing initial view. For `undefined`,
retain byte-for-byte-equivalent existing behavior.

Pass the profile into the initial view calculation in the resize/focus effect. Do not alter
`userModifiedViewRef`: resize after pan/zoom must continue preserving the user's center and scale.

**Step 2: Thread the profile without deriving another city identity**

- Add `initialFocus?: InitialFocusProfile` to `MapViewerProps`.
- Forward it unchanged to `HanMapCanvas`.
- Pass `initialFocus="current-city-close"` only from the main `GameChrome` map.

Do not set a new default in `MapViewer`; selection/history/other maps must remain unchanged.

**Step 3: Run focused camera/prop tests and capture GREEN**

Run the Task 1 command. Expected: all three focused files pass.

## Task 3: Red-test the independent self-location layer

**Files:**

- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Step 1: Extend the canvas recorder only as needed**

Record the drawing operations required to distinguish the new layer: stroke styles/widths, filled
chevron path, `fillText('내 위치', ...)`, and interval activity. Keep the recorder generic; do not
hard-code a production success result into the test harness.

**Step 2: Add failing detail and overview assertions**

Using the existing contained-overview fixture plus a normal detail fixture, assert that a current
city renders:

- a dark under-stroke and white/gold double halo;
- a non-color chevron cue;
- the exact chip text `내 위치`;
- the existing selected-city rectangle independently when another/current city is selected;
- no changed hit target or pointer interception.

Add a missing-current fixture that renders no self label and does not throw.

**Step 3: Add a failing reduced-motion test**

Mock `matchMedia('(prefers-reduced-motion: reduce)')` as matching, use fake timers, and prove that no
self-location animation interval advances while the full static overlay remains. Add the normal-mode
counterpart proving only the approved slow phase schedules redraws.

**Step 4: Run the interaction test and capture RED**

```bash
cd web/game
corepack pnpm test -- __tests__/HanMapCanvas.interaction.test.tsx
```

Expected: failures because the chip/chevron/double halo and reduced-motion-safe phase do not exist.

## Task 4: Implement the self-location renderer and motion policy

**Files:**

- Modify: `web/shared/src/HanMapCanvas.tsx`

**Step 1: Make animation input deterministic**

Add a `selfLocationPhaseRef` alongside `flagPhaseRef`. Remove the `Date.now()`-based 500 ms blink and
the separate unconditional current-city interval. Use a slow fixed interval (at least 1,200 ms) to
toggle only a low-amplitude halo phase when a current marker exists and reduced motion is false.

Reuse one `prefers-reduced-motion` decision for flag and self-location animation where practical.
The static self layer must render regardless of that decision.

**Step 2: Draw one semantic overlay in both LOD paths**

Extract a small internal `drawCurrentLocationOverlay` helper that accepts context, screen position,
marker radius/detail, DPR, and phase. Draw in this order:

1. dark outer under-stroke;
2. white and gold halo strokes;
3. upward chevron above the marker;
4. compact dark-backed, high-contrast `내 위치` chip.

Keep chip and stroke dimensions in screen-space/DPR units. Do not add the overlay to `hitRef`, and
wrap canvas state changes with `save()`/`restore()`. Call the helper from both overview and detail
paths whenever `city.layers` contains `current`.

**Step 3: Preserve selection, capital, state, and city-name channels**

Keep the current marker independent from `selected`. Position the chip above the marker so it does
not replace `mapLabel`; use the existing collision candidates or a deterministic reserved offset if
needed. Do not change ownership colors or imply that the current city is selected.

**Step 4: Run interaction and full focused tests**

Run:

```bash
cd web/game
corepack pnpm test -- __tests__/HanMapCanvas.interaction.test.tsx \
  __tests__/HanMapCanvas.test.ts \
  __tests__/MapViewer.props.test.tsx \
  __tests__/GameChrome.main-map.test.tsx \
  __tests__/MapViewer.interaction.test.tsx
```

Expected: all focused map tests pass.

## Task 5: Visible documentation and verification

**Files:**

- Modify if applicable: the existing `docs/user/**` page that describes the live main map or marker
  legend
- Create: `reports/opensamguk/tasks/2026-09-05-main-map-self-location.md` in the metarepo after the
  repository commit

**Step 1: Check user-document impact**

Search `docs/user` for the main-map marker legend. If a current page describes the map, update it
with the close initial focus, `내 위치` layer, and reduced-motion behavior. If no such page exists,
record `docs-impact: no current marker legend exists` in the task report rather than creating an
unrelated guide.

**Step 2: Run frontend verification**

```bash
cd web/game
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
```

Confirm command exit codes and final Vitest/Next output; do not infer success from a wrapper message.

**Step 3: Capture visual evidence**

Run the existing local game surface with a seeded world when available and capture the main map at
normal and reduced motion. Verify the current city begins centered near CSS scale 10, the label is
legible over light/dark terrain, and pan/zoom is not reset. If a live seed cannot run, record the
blocker and use the deterministic canvas test fixture output; do not fabricate a screenshot.

**Step 4: Review and commit the logical implementation**

Run `git diff --check`, inspect the complete branch diff, and request independent review. Resolve all
fix-required findings. Commit the implementation as one logical task:

```text
feat(map): emphasize the player's initial location

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

**Step 5: Update GitHub only with verified evidence**

Comment on #212 with camera/LOD behavior and transform tests. Comment on #465 with the independent
self-location layer, reduced-motion behavior, and visual evidence. Do not close either issue unless
all acceptance criteria in its current body are complete.

**Step 6: Write the metarepo task report**

Record result, commit, verification commands and counts, issue comments, docs impact, visual evidence,
and remaining risks. Leave a dirty worktree in place; remove only after the report if it is clean and
the user no longer needs it.
