# Static Province ID Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared map's straight-road web and nation-colored castles with a Paradox-style political layer backed by one deterministic province ID PNG, hierarchical 현/군·국 borders, neutral castle bodies, and device-independent pointer-centered zoom.

**Architecture:** A standard-library Python generator expands `han-tiles.json` once and writes a lossless RGB identity PNG whose pixels encode both province and commandery indices. The game API serves that packaged artifact byte-for-byte. `@opensamguk/ui` decodes the image once per map code, binds live city ownership by sampling each city's tile, composes an offscreen nation-color layer only when ownership changes, caches static boundary paths, and reuses those layers for every zoom/pan frame.

**Tech Stack:** Python 3 standard library (PNG chunks + deterministic stored DEFLATE), Kotlin 2.1/Spring MVC, React 19, TypeScript 5.7, Canvas 2D, Vitest/Testing Library, JUnit 5, Docker.

**Spec:** `docs/superpowers/specs/2026-08-26-static-province-id-map-design.md`

## Global Constraints

- `data/map/han-tiles.json` remains the committed source of truth; generated PNG/metadata are build outputs and stay ignored.
- PNG code is exactly `0` for uncovered cells and `((commanderyIndex + 1) << 12) | (provinceIndex + 1)` for covered cells.
- Province indices are `< 4095`; commandery indices are `< 255`; lossless RGB only.
- `owner` and `seatOwner` determine political coverage. Terrain mismatch counts are reported, never used to rewrite identities.
- Static indices are never nation IDs. Live city ownership binds by sampling the province image at the city's mapped tile.
- Zoom/pan frames never decode the image, scan the whole grid, or reconstruct political geometry.
- Castle bodies and glow are neutral. Nation color is limited to province fill and the small flag.
- `adjacency.county` remains in data for gameplay but produces no straight road draw commands.
- Maximum zoom is `32` CSS pixels per grid unit times device-pixel ratio.
- Missing/invalid province assets render terrain-only and never substitute another map code.
- Frozen/golden baselines are not deleted or weakened without an explicit behavioral reason.
- Implementation is one logical commit ending with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task 1: Deterministic Province ID Asset Generator

**Files:**
- Create: `tools/map/build_province_map.py`
- Create: `tools/map/tests/test_build_province_map.py`

**Interfaces:**
- Consumes: `data/map/<mapCode>-tiles.json` fields `_meta`, `terrain`, `owner`, `seatOwner`, `cities`, `juns`.
- Produces: `expand_rle(runs, cells) -> list[int]`, `encode_identity(province, commandery) -> tuple[int, int, int]`, `decode_identity(rgb) -> tuple[int, int] | None`, `build_assets(input_path, output_dir, map_code) -> BuildResult`, and `check_assets(input_path, output_dir, map_code) -> bool`.
- CLI: `python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han [--check]`.
- Outputs: `build/generated-map/han-provinces.png`, `build/generated-map/han-provinces.meta.json`.

- [ ] **Step 1: Write failing codec, validation, and determinism tests**

Use a literal 3×2 fixture. Expectations are handwritten rather than computed by the codec under test.

```python
class ProvinceMapGeneratorTest(unittest.TestCase):
    def test_identity_codec_uses_documented_bit_layout(self):
        self.assertEqual(encode_identity(-1, -1), (0, 0, 0))
        self.assertEqual(encode_identity(0, 0), (0x00, 0x10, 0x01))
        self.assertEqual(decode_identity((0x00, 0x10, 0x01)), (0, 0))
        self.assertEqual(decode_identity((0, 0, 0)), None)

    def test_build_round_trips_both_grids_and_is_byte_deterministic(self):
        fixture = {
            "_meta": {"cols": 3, "rows": 2},
            "terrain": ["011", "110"],
            "owner": [[-1, 1], [0, 1], [1, 1], [2, 2], [-1, 1]],
            "seatOwner": [[-1, 1], [0, 2], [1, 2], [-1, 1]],
            "cities": [{}, {}, {}], "juns": [{}, {}],
        }
        first = build_fixture(fixture)
        second = build_fixture(fixture)
        self.assertEqual(first.png_bytes, second.png_bytes)
        self.assertEqual(first.metadata_bytes, second.metadata_bytes)
        self.assertEqual(first.decoded_provinces, [-1, 0, 1, 2, 2, -1])
        self.assertEqual(first.decoded_commanderies, [-1, 0, 0, 1, 1, -1])

    def test_rejects_coverage_disagreement_and_index_overflow(self):
        with self.assertRaisesRegex(ValueError, "coverage disagreement"):
            build_from_runs(owner=[[0, 1]], seat_owner=[[-1, 1]], cols=1, rows=1)
        with self.assertRaisesRegex(ValueError, "province index"):
            encode_identity(4095, 0)
        with self.assertRaisesRegex(ValueError, "commandery index"):
            encode_identity(0, 255)

    def test_check_detects_tampered_output_and_map_code_is_safe(self):
        result = build_fixture(valid_fixture)
        result.png_path.write_bytes(result.png_bytes + b"tampered")
        self.assertFalse(check_assets(result.input_path, result.output_dir, "han"))
        with self.assertRaisesRegex(ValueError, "map code"):
            build_assets(result.input_path, result.output_dir, "../han")
```

`valid_fixture` is the module-level literal dictionary shown above. `build_fixture(data)` is a test-only helper in `test_build_province_map.py`: it writes compact JSON to `TemporaryDirectory`, calls the real `build_assets`, reads the emitted PNG/metadata bytes, and decodes PNG scanlines with the production `decode_identity`. It returns a test-only record carrying `input_path`, `output_dir`, `png_path`, emitted bytes, and decoded arrays; it does not reimplement the identity bit layout.

These tests catch a wrong bit shift, truncated RLE, non-deterministic writer, or mismatched hierarchy coverage.

- [ ] **Step 2: Run the generator tests and verify RED**

Run: `python3 -m unittest tools.map.tests.test_build_province_map -v`

Expected: import failure for missing `tools.map.build_province_map`.

- [ ] **Step 3: Implement the minimal deterministic generator**

Write PNG chunks in fixed order (`IHDR`, one `IDAT`, `IEND`), use filter byte `0` per row, and emit RFC 1950 stored-DEFLATE blocks. Do not call Pillow or accept a lossy extension.

```python
PROVINCE_BITS = 12
PROVINCE_LIMIT = (1 << PROVINCE_BITS) - 1
COMMANDERY_LIMIT = 255

def encode_identity(province: int, commandery: int) -> tuple[int, int, int]:
    if province == commandery == -1:
        return (0, 0, 0)
    if not 0 <= province < PROVINCE_LIMIT:
        raise ValueError(f"province index out of range: {province}")
    if not 0 <= commandery < COMMANDERY_LIMIT:
        raise ValueError(f"commandery index out of range: {commandery}")
    code = ((commandery + 1) << PROVINCE_BITS) | (province + 1)
    return ((code >> 16) & 0xff, (code >> 8) & 0xff, code & 0xff)
```

Metadata uses sorted compact JSON with one final newline. Record schema version, codec fields, dimensions, source/PNG SHA-256, counts, and both terrain/political mismatch counters; omit timestamps and absolute paths.

- [ ] **Step 4: Run focused tests and the real `han` generator**

```bash
python3 -m unittest tools.map.tests.test_build_province_map -v
python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han
python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han --check
```

Expected: tests pass; output reports `768×669`, 1,138 province identities, 170 present commandery identities, and check mode exits 0 without rewriting.

- [ ] **Step 5: Inspect output without promoting it to source**

```bash
file build/generated-map/han-provinces.png
git status --short build/generated-map data/map
```

Expected: 768×669 8-bit RGB PNG; no generated file appears under `data/map`; build output remains ignored.

---

### Task 2: Package the Generated Artifact in the Game API Image

**Files:**
- Modify: `docker/game-api.Dockerfile`

**Interfaces:**
- Consumes: Task 1 CLI and committed `data/map/han-tiles.json`.
- Produces: `/app/data/map/han-provinces.png` and `.meta.json` beside `han-tiles.json` in the runtime image.

- [ ] **Step 1: Prove the current image lacks the artifact**

```bash
docker build -f docker/game-api.Dockerfile -t opensamguk/game-api:province-red .
docker run --rm --entrypoint sh opensamguk/game-api:province-red -c 'test -f /app/data/map/han-provinces.png'
```

Expected: second command exits 1.

- [ ] **Step 2: Add one build-stage generation and exact runtime copies**

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-minimal \
    && rm -rf /var/lib/apt/lists/*
RUN python3 tools/map/build_province_map.py \
    --input data/map/han-tiles.json \
    --output-dir build/generated-map \
    --map-code han
COPY --from=build /src/build/generated-map/han-provinces.png /app/data/map/han-provinces.png
COPY --from=build /src/build/generated-map/han-provinces.meta.json /app/data/map/han-provinces.meta.json
```

Keep the existing `han-tiles.json` copy and never copy `data/map/*` broadly.

- [ ] **Step 3: Build and compare packaged bytes**

```bash
python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han
docker build -f docker/game-api.Dockerfile -t opensamguk/game-api:province-green .
container_id=$(docker create opensamguk/game-api:province-green)
docker cp "$container_id:/app/data/map/han-provinces.png" build/container-han-provinces.png
docker rm "$container_id"
cmp build/generated-map/han-provinces.png build/container-han-provinces.png
```

Expected: Docker build succeeds and `cmp` exits 0.

---

### Task 3: Serve Province Images Byte-for-Byte

**Files:**
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt`

**Interfaces:**
- Consumes: `HAN_MAP_FILE`; derives `<mapCode>-provinces.png` from the same directory.
- Produces: `GET /api/map/provinces?mapCode=<safe-code>` with exact PNG bytes, `image/png`, ETag, one-hour public cache, 304, and no-fallback 404.

- [ ] **Step 1: Write failing endpoint tests**

Use literal PNG-signature bytes in a temporary `che-provinces.png` and assert exact bytes/content type/ETag/304. Add separate cases for `../secret`, missing `che`, and proof missing `che` does not return `han-provinces.png`.

```kotlin
val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
val tag = mvc.perform(get("/api/map/provinces").queryParam("mapCode", "che"))
    .andExpect(status().isOk)
    .andExpect(content().contentType(MediaType.IMAGE_PNG))
    .andExpect(content().bytes(bytes))
    .andReturn().response.getHeader(HttpHeaders.ETAG)!!
```

- [ ] **Step 2: Run the controller test and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.TerrainMapControllerTest --rerun-tasks
```

Expected: province test receives 404 because the endpoint is absent.

- [ ] **Step 3: Implement one exact-byte sibling helper**

```kotlin
@GetMapping("/provinces")
fun provinces(
    @RequestParam(defaultValue = "han") mapCode: String,
    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
): ResponseEntity<ByteArray> = serveMapFile(mapCode, "provinces.png", MediaType.IMAGE_PNG, ifNoneMatch)
```

Keep public endpoint signatures explicit. Share safe map-code validation, size/mtime ETag, cache, and `Files.readAllBytes`. Path resolution is exact: `/terrain?mapCode=han` uses the configured path verbatim; other terrain codes use the sibling `<code>-tiles.json`; every province code, including `han`, uses sibling `<code>-provinces.png`.

- [ ] **Step 4: Run focused test and inspect XML**

Run the Step 2 command, then:

```bash
rg -n 'tests="|failures="|errors="|skipped="' app/game-api/build/test-results/test/TEST-opensamguk.gameapi.controller.TerrainMapControllerTest.xml
```

Expected: `BUILD SUCCESSFUL`; XML has zero failures/errors.

---

### Task 4: Decode Identity Pixels and Bind Live Province Ownership

**Files:**
- Create: `web/shared/src/provinceMap.ts`
- Create: `web/game/__tests__/provinceMap.test.ts`
- Modify: `web/shared/src/index.ts`
- Modify: `web/game/__tests__/fixtures/che-tiles.ts`

**Interfaces:**
- Produces `ProvinceIdentityMap { width, height, provinces: Int16Array, commanderies: Int16Array, provinceEdges, commanderyEdges }`.
- Produces `decodeProvincePixels(rgba, width, height)`, `bindProvinceOwnership(map, cities, grid, source)`, and `composeProvincePixels(map, binding, alpha = 96)`.
- Produces `ProvinceEdge { x1, y1, x2, y2 }` in unprojected grid coordinates.
- Consumes existing `mapCityToTile`, `IsoCityOverlay`, `GridSize`, and source dimensions.

- [ ] **Step 1: Write failing literal decoder and hierarchy-edge tests**

```ts
it('decodes both identities and separates province from commandery edges', () => {
  const rgba = new Uint8ClampedArray([
    0,0,0,255, 0,16,1,255, 0,16,2,255,
    0,32,3,255, 0,32,3,255, 0,0,0,255,
  ]);
  const map = decodeProvincePixels(rgba, 3, 2);
  expect(Array.from(map.provinces)).toEqual([-1, 0, 1, 2, 2, -1]);
  expect(Array.from(map.commanderies)).toEqual([-1, 0, 0, 1, 1, -1]);
  expect(map.provinceEdges).toContainEqual({ x1: 1.5, y1: -0.5, x2: 1.5, y2: 0.5 });
  expect(map.commanderyEdges).not.toContainEqual({ x1: 1.5, y1: -0.5, x2: 1.5, y2: 0.5 });
  expect(map.commanderyEdges).toContainEqual({ x1: 0.5, y1: 0.5, x2: 1.5, y2: 0.5 });
});
```

Also assert no edge against ID `0`, wrong RGBA byte length rejects, and covered pixels with a zero hierarchy field reject.

- [ ] **Step 2: Write failing sampling and conflict tests**

Update the `che` fixture's `owner`/`seatOwner` to contain two actual province regions. Use existing overlays at exact mapped cells.

```ts
it('binds nation colors by sampling each live city province tile', () => {
  const binding = bindProvinceOwnership(identityMap, CHE_OVERLAYS_FIXTURE, { cols: 4, rows: 3 }, SOURCE);
  expect(binding.colors.get(0)).toEqual({ nationId: 1, rgb: [255, 0, 0] });
  expect(binding.colors.get(1)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
  expect(binding.conflicts).toEqual([]);
});

it('leaves a province neutral when two nations claim one sampled province', () => {
  const binding = bindProvinceOwnership(oneProvinceMap, conflictingCities, grid, SOURCE);
  expect(binding.colors.has(0)).toBe(false);
  expect(binding.conflicts).toEqual([0]);
});
```

Assert `nationId=0`, missing/non-hex color, outside-grid coordinate, and sea sample produce no color.

- [ ] **Step 3: Run the suite and verify RED**

Run: `pnpm -C web --filter @opensamguk/web-game exec vitest run __tests__/provinceMap.test.ts`

Expected: missing module/export failures.

- [ ] **Step 4: Implement decoder, static edges, binding, and fill pixels**

Use right/bottom neighbors only so each shared edge appears once. A same-commandery/different-province transition enters only `provinceEdges`; a commandery transition enters both so the stronger stroke covers the thin one.

```ts
const PROVINCE_MASK = 0x0fff;
const code = (r << 16) | (g << 8) | b;
const province = (code & PROVINCE_MASK) - 1;
const commandery = ((code >>> 12) & 0xff) - 1;
```

`bindProvinceOwnership` rounds mapped coordinates, bounds-checks, accepts only `/^#[0-9a-fA-F]{6}$/`, and removes a province color for the rest of that pass after a different nation collides. `composeProvincePixels` uses transparent pixels for sea/unmapped/conflicted provinces and RGB alpha `96` for owned provinces.

- [ ] **Step 5: Run tests and shared typecheck**

```bash
pnpm -C web --filter @opensamguk/web-game exec vitest run __tests__/provinceMap.test.ts
pnpm -C web --filter @opensamguk/ui typecheck
```

Expected: suite passes and `tsc --noEmit` exits 0.

---

### Task 5: Cached Political Rendering, Neutral Castles, and DPR-Aware Zoom

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/provinceMap.ts`
- Modify: `web/shared/src/isoMap.ts`
- Modify: `web/shared/src/index.ts`
- Modify: `web/game/__tests__/HanMapCanvas.test.ts`
- Modify: `web/game/__tests__/isoMap.test.ts`
- Modify: `web/game/__tests__/che-tiles.golden.test.ts`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`

**Interfaces:**
- Adds `provinceUrl?: string | ((mapCode: string) => string)` and injection-friendly `provinceMap?: ProvinceIdentityMap | null` to `HanMapCanvasProps`.
- Adds `MAX_CSS_SCALE = 32`, `maxScaleForDpr(dpr)`, optional `max` to `zoomAt`/`scaleForSpan`, and optional `dpr = 1` to `initialView`/`labelZoomFor`.
- `buildIsoScene` produces no roads; scene cities expose `territoryColor` and fixed `iconColor`.
- Default province URL is `/api/game/api/map/provinces?mapCode=<code>`.

- [ ] **Step 1: Write failing scene tests for road removal and neutral icons**

```ts
expect(sceneGolden(scene)).toContain(
  'city:11@1.000,1.000 territory=#ff0000 icon=#8b8172[castle:8,flag,capital,event:6,supply:on,current,name:낙양]'
);
expect(scene.roads).toEqual([]);
expect(scene.cities[0].iconColor).toBe('#8b8172');
expect(scene.cities[0].layers).not.toContain('aura');
```

These catch reintroduced adjacency lines, nation-colored castle fill, or nation-colored glow.

- [ ] **Step 2: Write failing CSS/DPR zoom tests**

```ts
it('caps at 32 CSS pixels on DPR 1 and DPR 2', () => {
  expect(maxScaleForDpr(1)).toBe(32);
  expect(maxScaleForDpr(2)).toBe(64);
  expect(zoomAt(viewAt(800, 600, 1, 1, 10), 100, 80, 100, 0.5, 64).scale).toBe(64);
});

it('keeps the cell under the pointer at the higher cap', () => {
  const before = screenToCell(240, 180, view);
  const after = zoomAt(view, 240, 180, 4, 0.5, 64);
  expect(screenToCell(240, 180, after)).toEqual(before);
});
```

Update label tests to pass DPR and assert absolute CSS thresholds become backing-pixel thresholds.

- [ ] **Step 3: Write failing cache/interaction tests**

Supply a literal `provinceMap`. Record `putImageData`, `drawImage`, strokes, and active fill style. Assert:

1. initial render composes political pixels once;
2. wheel zoom and pan add draws but no `putImageData`;
3. changing nation color adds exactly one composition;
4. wheel zoom exceeds backing scale `14` and remains pointer-centered;
5. no stroke uses old road color `rgba(225, 192, 120, 0.72)`;
6. castle rectangles use `#8b8172`, never fixture nation colors.
7. a rejected fetch or dimension mismatch keeps the terrain canvas and emits no political draw.

- [ ] **Step 4: Run focused suites and verify RED**

```bash
pnpm -C web --filter @opensamguk/web-game exec vitest run \
  __tests__/provinceMap.test.ts \
  __tests__/che-tiles.golden.test.ts \
  __tests__/isoMap.test.ts \
  __tests__/HanMapCanvas.interaction.test.tsx
```

Expected: failures for old road golden, old scale cap, missing props/functions, and colored castles.

- [ ] **Step 5: Implement same-origin PNG loading and static caches**

```ts
export async function loadProvinceIdentityMap(url: string): Promise<ProvinceIdentityMap> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`province map fetch failed: ${response.status}`);
  const bitmap = await createImageBitmap(await response.blob());
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  if (!context) throw new Error('province decode context unavailable');
  try {
    context.drawImage(bitmap, 0, 0);
    return decodeProvincePixels(context.getImageData(0, 0, bitmap.width, bitmap.height).data, bitmap.width, bitmap.height);
  } finally {
    bitmap.close();
  }
}
```

Load only when `provinceMap` is not supplied. Failure stores a terrain-only `null` political layer. Reject dimension mismatch. Build province/commandery `Path2D` once per identity image and the fill canvas once per identity/ownership change. `render()` draws in fixed order: terrain, political fill, province edges, commandery edges, then city markers/labels. Disable image smoothing for the political fill so neighboring identity colors never bleed. `render()` only transforms/draws prepared canvases and cached paths.

- [ ] **Step 6: Remove roads and neutralize castle bodies**

Delete adjacency road construction/stroke while retaining `HanTiles.adjacency`. Use:

```ts
const CASTLE_FILL = '#8b8172';
const CASTLE_STROKE = '#f3dfb0';
const PROVINCE_BORDER = 'rgba(18,20,22,0.58)';
const COMMANDERY_BORDER_DARK = 'rgba(10,12,14,0.82)';
const COMMANDERY_BORDER_LIGHT = 'rgba(225,210,163,0.76)';
```

Draw cached paths under the existing isometric transform with source-space widths derived from backing pixels: province `dpr / scale`, commandery dark underlay `3 * dpr / scale`, and commandery light top stroke `1.5 * dpr / scale`. Keep flag cloth in `territoryColor` and preserve capital/event/current/selected/supply semantics.

- [ ] **Step 7: Implement DPR-aware zoom and LOD**

```ts
export const MAX_CSS_SCALE = 32;
export const maxScaleForDpr = (dpr: number) => MAX_CSS_SCALE * Math.max(1, dpr);
```

Store DPR in `sizeRef`; pass `maxScaleForDpr(dpr)` to wheel/button zoom and initial span calculation. Multiply absolute label density thresholds by DPR; retain fit-relative marker thresholds. Clamp a preserved view when viewport or DPR changes.

- [ ] **Step 8: Run shared-renderer gates**

```bash
pnpm -C web --filter @opensamguk/web-game exec vitest run \
  __tests__/provinceMap.test.ts \
  __tests__/HanMapCanvas.test.ts \
  __tests__/che-tiles.golden.test.ts \
  __tests__/isoMap.test.ts \
  __tests__/HanMapCanvas.interaction.test.tsx
pnpm -C web --filter @opensamguk/ui typecheck
```

Expected: all listed suites pass and shared typecheck exits 0.

---

### Task 6: Wire One Province Asset Through Gateway and Game Maps

**Files:**
- Modify: `web/gateway/__tests__/MapPreview.iso.test.tsx`
- Modify: `web/gateway/components/MapPreview.tsx`
- Modify: `web/game/__tests__/MapViewer.interaction.test.tsx`
- Modify: `web/game/__tests__/MapViewer.asset.test.tsx`
- Modify: `web/game/__tests__/MapViewer.props.test.tsx`
- Modify: `web/game/components/game/MapViewer.tsx`

**Interfaces:**
- Gateway: `/api/game/api/map/provinces?server=<serverId>&mapCode=<mapCode>`.
- Game: `/api/game/api/map/provinces?mapCode=<mapCode>`.
- Both pass `provinceUrl`; political failure never sets required-terrain `tileMissing`.

- [ ] **Step 1: Add failing wrapper assertions**

```ts
const provinceUrl = typeof shared.props?.provinceUrl === 'function'
  ? shared.props.provinceUrl('han')
  : shared.props?.provinceUrl;
expect(provinceUrl).toBe('/api/game/api/map/provinces?mapCode=han');
```

Gateway expectation includes encoded `server=s1`. Retain no-CDN/no-`.map-road` assertions.

- [ ] **Step 2: Run wrapper suites and verify RED**

```bash
pnpm -C web --filter @opensamguk/web-gateway exec vitest run __tests__/MapPreview.iso.test.tsx
pnpm -C web --filter @opensamguk/web-game exec vitest run __tests__/MapViewer.interaction.test.tsx __tests__/MapViewer.asset.test.tsx __tests__/MapViewer.props.test.tsx
```

Expected: `provinceUrl` is undefined.

- [ ] **Step 3: Add map-code-aware callbacks**

Mirror terrain URL policy in each wrapper. Gateway includes its server query. Pass both URLs to `HanMapCanvas`; keep `onMissing` attached only to terrain.

- [ ] **Step 4: Run wrapper suites and app typechecks**

```bash
pnpm -C web --filter @opensamguk/web-gateway exec vitest run __tests__/MapPreview.iso.test.tsx
pnpm -C web --filter @opensamguk/web-game exec vitest run __tests__/MapViewer.interaction.test.tsx __tests__/MapViewer.asset.test.tsx __tests__/MapViewer.props.test.tsx __tests__/game-map-page.test.tsx
pnpm -C web --filter @opensamguk/web-gateway typecheck
pnpm -C web --filter @opensamguk/web-game typecheck
```

Expected: all listed tests/typechecks pass.

---

### Task 7: ADR, Full Verification, Visual Evidence, Report, and Logical Commit

**Files:**
- Modify: `.ai/decisions.md` (ADR-LITE-044 generated deployment surface)
- Create outside project Git repo: `../../../reports/opensamguk/tasks/2026-08-26-static-province-id-map.md`

**Interfaces:**
- Produces updated architecture record, exact generator/API/frontend evidence, visual matrix, remaining-risk record, and one implementation commit SHA.

- [ ] **Step 1: Revise ADR-LITE-044 without changing source authority**

Record that `han-tiles.json` stays the committed identity source while PNG/metadata are deterministic Docker build outputs served by game-api. Record codec, no-lossy-transform rule, terrain-only failure, and rollback (stop packaging/serving the political image). Do not promote the generated image to historical source.

- [ ] **Step 2: Run complete generator/map tests**

```bash
python3 -m unittest discover -s tools/map/tests -v
python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han --check
```

Record exact test count, PNG SHA-256, dimensions, province count, commandery count, and mismatch counters.

- [ ] **Step 3: Run backend/frontend gates**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.TerrainMapControllerTest --rerun-tasks
pnpm -C web --filter @opensamguk/ui typecheck
pnpm -C web --filter @opensamguk/web-game test
pnpm -C web --filter @opensamguk/web-gateway test
pnpm -C web --filter @opensamguk/web-game typecheck
pnpm -C web --filter @opensamguk/web-gateway typecheck
git diff --check
```

Confirm Gradle with `BUILD SUCCESSFUL` plus zero-failure XML. Record exact Vitest file/test counts; interrupted/flaky runs are not green.

- [ ] **Step 4: Verify runtime artifact and HTTP behavior**

```bash
docker build -f docker/game-api.Dockerfile -t opensamguk/game-api:province-final .
docker run --rm --entrypoint sh opensamguk/game-api:province-final -c 'test -f /app/data/map/han-provinces.png && test -f /app/data/map/han-provinces.meta.json'
docker compose up -d --build game-api web-game web-gateway nginx
curl -fsSI 'http://localhost/api/game/api/map/provinces?mapCode=han'
test "$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost/api/game/api/map/provinces?mapCode=../secret')" = 404
```

Expected: `200`, `image/png`, ETag/cache headers; invalid code is `404`.

- [ ] **Step 5: Capture overview and close-zoom visual evidence**

Open authenticated `/game/map` in the in-app browser. Capture overview and a pointer-centered wheel zoom at least `24 CSS px` per unit. Inspect:

- province fills follow live nation colors;
- adjacent same-nation 현 retain thin separation;
- 군·국 edges are stronger;
- castle bodies are neutral and flags retain nation color;
- no straight road web;
- hover/click target the intended city;
- pan/zoom stay responsive and canvas is nonblank.

Commit screenshots under `docs/superpowers/evidence/2026-08-26-static-province-id-map/` only if they contain no private player/server data. Otherwise keep them local and record inspection results/paths in the metarepo report.

- [ ] **Step 6: Request independent review and clear blockers**

Review the full range from `d05f26fc` to the working tree against spec/plan. Inspect generator determinism, codec bounds, API fallback safety, ownership binding, redraw cost, icon color, road removal, DPR zoom, tests, and artifact policy. Fix every Critical/Important issue, rerun focused tests, and re-review until `Ready to merge: Yes`.

- [ ] **Step 7: Write the metarepo task report**

Create `reports/opensamguk/tasks/2026-08-26-static-province-id-map.md` from metarepo root. Include result, codec/dimensions/hashes, changed files, exact verification counts, review verdict/remediation, remaining risks, and before/after rows for province fill, 현 boundary, 군·국 boundary, roads, castle, flag, capital, event, supply, current, selected, tooltip, click, keyboard, touch, wheel/button zoom, pan, and fallback.

- [ ] **Step 8: Create one logical implementation commit**

```bash
git status --short
git diff --check
git add .ai/decisions.md docker/game-api.Dockerfile \
  app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt \
  tools/map/build_province_map.py tools/map/tests/test_build_province_map.py \
  web/shared/src web/game/__tests__ web/game/components/game/MapViewer.tsx \
  web/gateway/__tests__/MapPreview.iso.test.tsx web/gateway/components/MapPreview.tsx
git commit -m "feat: render static province identity map

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: one implementation commit after two design commits; no generated asset, container copy, private screenshot, `.env`, or unrelated file is staged.

- [ ] **Step 9: Run post-commit smoke and finalize report SHA**

```bash
git status --short --branch
git show --stat --oneline --decorate HEAD
python3 -m unittest tools.map.tests.test_build_province_map -v
pnpm -C web --filter @opensamguk/web-game exec vitest run __tests__/provinceMap.test.ts __tests__/HanMapCanvas.interaction.test.tsx __tests__/MapViewer.interaction.test.tsx
```

Expected: clean project worktree and focused smoke passes. Update the external metarepo report with final SHA. Do not push, merge, deploy, or remove the worktree without separate instruction.
