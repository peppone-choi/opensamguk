# 2D map interaction and loading implementation plan

**Goal:** Keep pan/zoom responsive and avoid repeating immutable map preparation on return visits.
**Architecture:** A bounded LRU of world-coordinate raster chunks replaces the single viewport surface. Active zoom transforms existing chunks, then a trailing redraw refines at the final scale. A subsequent change separates immutable terrain from political decoration and shares version-scoped geometry/sprite requests.
**Tech Stack:** React, Canvas 2D, TypeScript, Vitest, Chromium.
**Spec:** User-approved steps 3 and 4 of the September 20 Windows Chrome performance proposal. Implement locally, independently commit/review/PR/merge each step. Deployment is not part of this request.

## Constraints and review focus
- Keep 4,194,304 terrain RGBA pixels plus at most the same number of composite pixels (32MiB combined), 4096 maximum side, native overlay/hit coordinates.
- Cache misses must not trigger a full-world direct draw; only context failure permits fallback.
- During zoom display available world-coordinate chunks, background for uncovered regions; refine after 120ms without zoom input. Visible chunks take precedence over prefetch.
- Build at most one missing chunk per frame. This bounds submitted work, not wall-clock time of an individual chunk.
- Reject stale asynchronous results and cancel timers/requests on unmount and identity changes.
- Cache immutable data by server, URL and actual response identity; unversioned sources must revalidate. Political data must remain fresh.

## Step 3 — chunks and trailing zoom refinement
- [x] Add `mapChunkCache.ts` with `get(view, render, settled)` returning surfaces and pending work. Use 512-pixel allocations with one-pixel gutters, stable world chunk origins, LRU eviction, and adaptive density keeping at most 12 visible chunks and 16 resident chunks.
- [x] Add failing tests for incremental initial fill, stationary reuse, newly exposed pan regions, active-zoom reuse, final-scale refinement, memory/eviction/disposal, fractional-coordinate coverage, context failure and viewport/DPR changes. Run `vitest run src/__tests__/mapChunkCache.test.ts` and confirm failures before implementation.
- [x] Integrate in `IsoMap2D.tsx`: clip chunk interiors and composite at world coordinates; pass raster scale to border rendering; schedule pending chunks via RAF. A shared trailing zoom timer handles wheel/pinch/buttons/fit and is cancelled by effect cleanup.
- [x] Run shared suite and TypeScript. Verify actual terrain in Chromium: fill, repeated pan, wheel zoom, settled refinement, no page errors, no chunk seams. Review, commit, open PR and merge after CI.

## Step 4 — loading reuse and independent invalidation
- [x] Extract reusable immutable grid preparation and an abort-aware bounded resource cache. Concurrent consumers share one request; releasing one cannot cancel another. Reject/remove failed entries. Match actual ETag and server scope before reusing prepared grids; do not trust a requested hash as proof of bytes.
- [x] Keep elevation attribution fetch outside first usable map readiness and update attribution separately without replacing geometry. Share sprite decoding by URL and remove rejected loads.
- [x] Keep terrain chunk lifetime independent of political style. Cache a political composite bitmap beside each terrain chunk, preserving original drawing order and backdrop blending. Color changes rebuild composites only.
- [x] Add regression tests for coalescing, cancellation, remount, changed server/version/ETag, retry, slow manifest, latest political data, stable equivalent colors, and unchanged geometry after attribution arrives. Shared283, game707, gateway266 tests and typechecks passed; actual map interaction, political overlap pixel comparison, seams and reentry passed in Chromium. Review found no blockers. Commit, PR and merge after CI.

## Verification ledger
Step 2 already separately committed as e979d980 (PR 844). Shared 262 tests and typecheck passed. No Windows real-device benchmark is claimed.


## Implementation rulings and verification

- Step 3 merged as PR845, commit c5553c77; shared272 and typecheck, actual map pan/zoom, device-pixel seam check passed. Last-resort eviction can briefly expose background if old and new visible generations exceed16 allocations.
- Step 4 geometry requests coalesce only while pending; even with another mounted consumer, each new reader revalidates the response ETag. At most2 prepared identities retained, server query/cookie scope included. DEM1 and decoded sprites128 idle entries are separately bounded; failed/aborted resources are not retained.
- Each chunk optionally retains a composite bitmap copied from its terrain and decorated in original order. Both are evicted/disposed together. This raises the combined RGBA cap to32MiB so pan/zoom can reuse bitmaps without replaying political paths each frame; canvas, sprites and GPU copies are additional.
- Attribution has a separate lifetime and never gates geometry readiness. Attribution arrival preserves the grid/owner arrays and terrain cache.
- Review fixes: new mounted reader skipping validation and alpha/order changes at elevated overlaps both reproduced RED and corrected GREEN. Final composite implementation independent review found no blockers.
- Windows real-device performance remains unmeasured. Browser QA uses real terrain with synthetic ownership/city overlays, not a production account.
