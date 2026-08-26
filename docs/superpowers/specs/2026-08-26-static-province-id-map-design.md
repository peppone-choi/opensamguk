# Static Province ID Map Design

**Date:** 2026-08-26  
**Status:** user-approved design, pending implementation plan  
**Reference:** [Province Map example](https://static.wikia.nocookie.net/thosuand-week-reich/images/d/d8/Province_Map.webp/revision/latest?cb=20230822011159)

## Goal

Render the `han` world like a Paradox political map:

- one game city is one 현 (縣), the selectable province;
- multiple 현 form one 군·국 (郡·國), the state-level grouping;
- province land, rather than the city icon, carries the owning nation's color;
- province borders are thin and 군·국 borders are stronger;
- the current all-adjacency road web is not rendered;
- mouse-wheel zoom remains pointer-centered and can zoom farther than today.

The province geometry is baked once into a lossless image artifact. Zoom and pan must never walk the full tile grid or rebuild province geometry.

## Existing Inputs

`data/map/han-tiles.json` remains the source of truth.

- `owner`: row-major RLE; every land tile stores its 현/city index.
- `seatOwner`: row-major RLE; every land tile stores its 군·국 index.
- `cities`: stable array addressed by `owner` values.
- `juns`: stable array addressed by `seatOwner` values.
- `terrain`: identifies sea and land independently of political ownership.

These indices are geographic identities, not runtime nation IDs. The implementation must never interpret an `owner` or `seatOwner` value as a nation.

## Static Asset Contract

### Province ID image

A deterministic generator produces:

```text
<generated-map-dir>/han-provinces.png
<generated-map-dir>/han-provinces.meta.json
```

The PNG has exactly the same width and height as the tile grid and uses lossless 8-bit RGB. One image pixel corresponds to one isometric tile. WebP, JPEG, palette quantization, resizing, and color-profile conversion are forbidden because they can change identity bytes.

The RGB integer stores both hierarchy levels:

```text
0                                  = sea / outside
((commanderyIndex + 1) << 12)
  | (provinceIndex + 1)            = land tile
```

The generator validates:

- `provinceIndex < 4095` and `commanderyIndex < 255`;
- RLE expansion length equals `cols * rows` for both arrays;
- sea pixels encode `0`;
- every land pixel has both a province and a 군·국 identity;
- decoding every emitted pixel reproduces the input `owner` and `seatOwner` values exactly.

The metadata sidecar records schema version, dimensions, source SHA-256, PNG SHA-256, province count, commandery count, and the bit layout. Re-running the generator with unchanged input must produce byte-identical PNG and metadata content, except that metadata contains no timestamp or machine-specific path.

### Artifact policy

`han-tiles.json` remains the committed source artifact. The generated PNG and metadata are deployment/build outputs, not hand-edited source and not Git-tracked inputs. The build/deployment path runs the deterministic generator before packaging the game API image. A check mode compares fresh output hashes with packaged output and fails on drift.

This preserves the existing CHGIS isolation rule: no original shapefile or intermediate geographic dataset is added to Git. The task will revise ADR-LITE-044 only as needed to name the generated province image as a derived deployment surface, without promoting it to a new source of truth.

## Delivery Contract

The game API serves the generated image through a map-code-aware endpoint alongside the existing terrain endpoint:

```text
GET /api/map/provinces?mapCode=han
```

The endpoint:

- accepts only the existing safe map-code grammar;
- serves `<mapCode>-provinces.png` without recompression or transformation;
- emits `image/png`, cache control, ETag, and `304 Not Modified` behavior;
- returns `404` when the artifact is absent and never substitutes another map code.

Gateway and game clients use their existing same-origin proxy path. The shared renderer receives a province image URL in the same way it receives the terrain URL.

## Runtime Rendering

### Decode and palette composition

`HanMapCanvas` loads the static province ID PNG once per map code. It decodes the RGB identity pixels into province and 군·국 indices and verifies dimensions against `han-tiles.json`.

Runtime cities provide the changing nation ownership. For each live city, the renderer maps its `(x, y)` to `(col, row)` with the existing axis-specific conversion and samples the ID image at that tile. That sampled province index is the explicit bridge from a game city to its 현 polygon. Conflicting assignments to the same province are rejected deterministically and reported; an unmapped or neutral province remains uncolored.

When the ID image or city ownership changes, the renderer performs one palette-composition pass into an offscreen political canvas:

- owned province fill: normalized nation color with readable terrain transparency;
- neutral/unmapped province: transparent fill;
- 현 boundary: thin dark edge when neighboring province IDs differ;
- 군·국 boundary: stronger light/dark edge when neighboring commandery IDs differ;
- coastline: no political border against ID `0`.

Zoom, pan, hover, selection, and animation frames only transform and draw the prepared terrain and political canvases. They do not decode RLE, scan all pixels, recompute boundaries, or rebuild the province image.

### Marker color

Castle geometry uses a fixed neutral stone palette regardless of nation. Capital stars, event badges, current-city rings, selected-city outlines, and supply dimming keep their semantic colors. A small flag may retain the nation color, but the castle body and its glow must not.

### Roads

The renderer does not draw `adjacency.county` as straight roads. The adjacency data remains available for gameplay and is not deleted from the payload.

### Zoom

Wheel zoom stays centered on the pointer by preserving the cell under the cursor. Button zoom uses the same camera function. The maximum scale becomes `32` CSS pixels per isometric grid unit, converted to backing pixels with the current device-pixel ratio. This replaces the current absolute backing-pixel limit of `14`, which unintentionally permits less visible zoom on high-DPI displays. Minimum zoom remains the fitted map scale.

## Failure Behavior

- Missing province image: render terrain and neutral markers, expose the existing missing/error path, and do not fall back to another map.
- Dimension or codec mismatch: reject the political layer and render terrain-only.
- Missing live ownership: leave that province transparent.
- Invalid nation color: use neutral ownership rather than inventing a color.
- Cross-origin/canvas-tainted image: treat as a political-layer load failure; deployment must serve the asset from the same origin.

## Test Strategy

### Generator

- hand-authored tiny RLE fixture produces literal RGB identity values;
- decode round-trip reproduces both owner arrays exactly;
- same input yields byte-identical PNG and metadata;
- overflow, malformed RLE, sea/land mismatch, and lossy-output requests fail;
- check mode detects a stale packaged artifact.

### API

- correct map-code selection and exact bytes;
- `Content-Type`, ETag, cache, and `304` regression coverage;
- traversal-like map codes and missing artifacts return `404`;
- no fallback substitution.

### Shared renderer

- a live city samples its province from the ID image and colors the full polygon;
- adjacent provinces of the same nation remain visibly separated by the thin 현 edge;
- a 군·국 transition receives the stronger edge;
- sea has no political outline;
- castle fill never receives nation color;
- the road draw command is absent;
- wheel zoom remains pointer-centered and reaches beyond the former maximum;
- repeated zoom/pan does not rerun ID decoding or political composition.

### Integration and visual QA

- gateway preview, main map, and dedicated map page all request the same map-code-specific province asset;
- screenshots at overview and close zoom show readable province/state hierarchy;
- canvas remains nonblank and interactions continue to hit the correct city;
- generated asset hashes and relevant frontend/backend test counts are recorded in the task report.

## Non-goals

- changing movement, adjacency, supply, combat, or command targeting;
- changing which 현 belongs to which 군·국;
- inventing ownership for static geography without a live city;
- adding curved borders, road routing, or animated political effects;
- reproducing or bundling the linked reference image.

## Acceptance Criteria

1. The packaged `han` map has one deterministic, lossless province ID PNG derived from `owner` and `seatOwner`.
2. Province fill reflects runtime city nation ownership, while castle bodies remain neutral.
3. 현 and 군·국 boundaries are visually distinct and remain stable through zoom and pan.
4. Straight adjacency roads are absent from the rendered map.
5. Pointer-centered wheel zoom exceeds the previous maximum without breaking hit testing.
6. Zoom/pan frames reuse prepared images and perform no full-grid rebake.
7. Missing or invalid province assets fail terrain-only without cross-map fallback.
8. Generator, API, renderer, integration, typecheck, and visual checks pass and are recorded in the metarepo task report.
