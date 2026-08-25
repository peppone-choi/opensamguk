# Iso Tile Map Unification Design

## Goal

Make the lobby preview, game main map, and dedicated map page render through one shared isometric tile canvas. The `han` world must consume its real tile payload and must never select the `che` CDN background as a fallback.

## Canonical model

ADR-LITE-044 is revised: the isometric grid is the canonical map projection. `data/map/han-tiles.json` remains the only committed CHGIS-derived runtime tile payload. Legacy map artwork is not a rendering dependency; non-Han compatibility is represented by the same palette-driven tile contract and covered with a synthetic `che-tiles` golden fixture rather than a committed legacy-derived image or map blob.

The rollback path is to restore the pre-revision ADR, restore the two DOM/CDN viewers, and keep the shared canvas available only on the dedicated map page. The rollback must not delete frozen tests or CHGIS isolation rules.

## Architecture

`web/shared` owns the projection math and `HanMapCanvas`, generalized as a tile renderer whose inputs are a tile document, optional live city overlays, source map dimensions, and interaction callbacks. It paints terrain, country ownership, city/castle symbols, flags, capital stars, event badges, labels, current/selected state, and supply state on a single canvas. Pointer hit-testing emits hover and activation events; wheel, drag, and zoom buttons share the existing isometric view math.

The game and gateway wrappers continue to own API loading, live-preview merging, title/caption text, navigation policy, touch two-tap policy, local-storage toggles, and tooltip DOM. They pass normalized overlays to the shared canvas. City coordinates are converted independently by axis:

```text
col = x * tileCols / sourceWidth
row = y * tileRows / sourceHeight
```

This is the exact inverse of `tools/scenario/build_han_world.py` and must not use a single uniform multiplier.

`TerrainMapController` accepts a validated `mapCode` query and resolves `<mapCode>-tiles.json` beside the configured default tile file. Invalid codes and absent files return 404. Every selected file retains byte-for-byte response delivery, public one-hour cache control, ETag generation from size and modified time, and `If-None-Match` 304 handling.

## Behavior preservation

The shared canvas preserves every current visual/interaction item in the migration scope:

1. terrain and roads from the palette tile payload;
2. city marker/castle and city name;
3. nation color/aura and colored flag;
4. capital star;
5. event-state badge;
6. current-city pulse, selected-city state, and supply-off dimming;
7. hover/focus tooltip selection;
8. mouse activation, keyboard activation through the wrapper controls where present, selection callback, navigation, and touch two-tap gating;
9. city-name toggle;
10. wheel zoom, button zoom, and pointer panning on all three surfaces.

The game map page stops choosing between `HanMapCanvas` and `MapViewer`; it always renders the shared canvas path with the current map code. The wrappers keep graceful loading/error placeholders when tiles are unavailable and do not silently substitute another map.

## Testing

Backend tests first prove map-code selection, invalid-code rejection, exact body delivery, ETag presence, and a second 304 request. Shared tests cover axis-specific coordinate conversion, drawing commands for all overlay kinds, hit-testing, zoom, and panning. A small synthetic `che-tiles` fixture has a checked command-stream/hash golden so palette rendering changes are intentional and reviewable. Existing `isoMap.test.ts` remains unchanged and passing.

`MapViewer.interaction`, `MapViewer.props`, and `MapViewer.asset` are rewritten around observable shared-canvas behavior: terrain URL contains the real map code, no CDN background/road exists, city hover/selection/navigation semantics remain, and interaction controls work. Gateway gains equivalent lobby tests, with zoom and pan as the priority-one acceptance path.

## Constraints

- Do not delete or weaken frozen baselines. Any expectation changed by this approved product decision records that reason in the test or task report.
- Do not commit CHGIS originals, `han-places.json`, or `terrain-grid.json`.
- Do not add legacy map artwork or a production `che-tiles.json` to the repository.
- Keep one logical commit and the required co-author trailer.
