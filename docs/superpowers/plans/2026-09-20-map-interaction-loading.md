# 2D map interaction and loading implementation plan

**Goal:** Keep pan/zoom responsive and avoid repeating immutable map preparation on return visits.
**Architecture:** A bounded LRU of world-coordinate raster chunks replaces the single viewport surface. Active zoom transforms existing chunks, then a trailing redraw refines at the final scale. A subsequent change separates immutable terrain from political decoration and shares version-scoped geometry/sprite requests.
**Tech Stack:** React, Canvas 2D, TypeScript, Vitest, Chromium.
**Spec:** User-approved steps 3 and 4 of the September 20 Windows Chrome performance proposal. Implement locally, independently commit/review/PR/merge each step. Deployment is not part of this request.

## Constraints and review focus
- Keep 4,194,304 cached RGBA pixels, 4096 maximum side, native overlay/hit coordinates.
- Cache misses must not trigger a full-world direct draw; only context failure permits fallback.
- During zoom display available world-coordinate chunks, background for uncovered regions; refine after 120ms without zoom input. Visible chunks take precedence over prefetch.
- Build at most one missing chunk per frame. This bounds submitted work, not wall-clock time of an individual chunk.
- Reject stale asynchronous results and cancel timers/requests on unmount and identity changes.
- Cache immutable data by server, URL and actual response identity; unversioned sources must revalidate. Political data must remain fresh.

## Step 3 — chunks and trailing zoom refinement
- [x] Add `mapChunkCache.ts` with `get(view, render, settled)` returning surfaces and pending work. Use 512-pixel allocations with one-pixel gutters, stable world chunk origins, LRU eviction, and adaptive density keeping at most 12 visible chunks and 16 resident chunks.
- [x] Add failing tests for incremental initial fill, stationary reuse, newly exposed pan regions, active-zoom reuse, final-scale refinement, memory/eviction/disposal, fractional-coordinate coverage, context failure and viewport/DPR changes. Run `vitest run src/__tests__/mapChunkCache.test.ts` and confirm failures before implementation.
- [x] Integrate in `IsoMap2D.tsx`: clip chunk interiors and composite at world coordinates; pass raster scale to border rendering; schedule pending chunks via RAF. A shared trailing zoom timer handles wheel/pinch/buttons/fit and is cancelled by effect cleanup.
- [ ] Run shared suite and TypeScript. Verify actual terrain in Chromium: fill, repeated pan, wheel zoom, settled refinement, no page errors, no chunk seams. Review, commit, open PR and merge after CI.

## Step 4 — loading reuse and independent invalidation
- [ ] Extract reusable immutable grid preparation and an abort-aware bounded resource cache. Concurrent consumers share one request; releasing one cannot cancel another. Reject/remove failed entries. Match actual ETag and server scope before reusing prepared grids; do not trust a requested hash as proof of bytes.
- [ ] Keep elevation attribution fetch outside first usable map readiness and update attribution separately without replacing geometry. Share sprite decoding by URL and remove rejected loads.
- [ ] Keep terrain chunk lifetime independent of political style. Cache political drawing commands separately, and composite tint with `color` and borders with normal blending to preserve backdrop semantics without a second raster cache.
- [ ] Add regression tests for coalescing, cancellation, remount, changed server/version/ETag, retry, slow manifest, latest political data, stable equivalent colors, and unchanged geometry after attribution arrives. Run shared and affected app suites/typechecks, actual map interactions and reentry in Chromium, review, commit, PR and merge after CI.

## Verification ledger
Step 2 already separately committed as e979d980 (PR 844). Shared 262 tests and typecheck passed. No Windows real-device benchmark is claimed.
