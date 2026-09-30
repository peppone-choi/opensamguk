// 탑다운 지도 렌더러: 조각 받기 → 지형 층(WebGL2), 이름표 · 깃발 · 1칸 거점 · 내 위치(2D 겹층).
// 그릴 일이 있을 때만 그린다(가만히 있으면 프레임 0).
import { chunksForRect, chunkKey, ChunkLoader } from './chunks';
import { isOwnedNationVisual } from '../../nationVisual';
import { planChunks } from './streaming';
import { bitmapPixels, overviewPixels, pixelsToCanvas } from './overviewPicture';
import { viewLevel, visibleCellRect, cellToScreen } from './camera';
import { FootprintIndex, hitTest, type HitResult, type SpriteHit } from './hitTest';
import { layoutLabels, type LabelCandidate, type LabelKind } from './labels';
import { decodeGreyPng, fetchBytes, fetchJson, fetchOverview, joinUrl, loadBitmap } from './loaders';
import { adminTexels, footprints, labelCandidates, parsePlaces, type PlacesData } from './places';
import { buildProvinceTable, type VisionState } from './provinceTable';
import { drawMyLocation, myLocationHitRect, type MyLocation } from './myLocation';
import { CORPS_HIT_Z, corpsPlacement, type CorpsArt, type CorpsMarker, type Heading } from './corps';
import { createKitCorpsArt } from './corpsArt';
import { drawFlag, drawSite, sheetFrom, type SpriteSheet } from './sprites';
import { createGl } from './gl/glUtil';
import { TerrainLayer } from './gl/terrainLayer';
import { type BakeManifest, type Camera, type ChunkData, type MapShape, type ViewLevel, type Viewport } from './types';

export interface TopdownSource {
  /** Directory holding manifest.json, grid/ and places.json.gz. */
  bakeUrl: string;
  /** Directory holding the kit export (kit-index.png, kit-mip*.png, palettes.json, sites/flags sheets). */
  kitUrl: string;
}

export interface WorldNation { id: number; name: string; color: string }

export interface WorldState {
  occupancy: ReadonlyArray<{ provinceIndex: number; nationId: number }>;
  nations: ReadonlyArray<WorldNation>;
  commanderyOfProvince?: ReadonlyArray<number>;
  vision?: ReadonlyMap<number, VisionState>;
  pick?: { candidates: ReadonlyMap<number, boolean> };
  selectedProvinces?: ReadonlySet<number>;
}

export interface MapLayers {
  provinceLines: boolean;
  countyLines: boolean;
  commanderyLines: boolean;
  cityNames: boolean;
  /** 「부대 경로」: 남은 행군 경로. */
  corpsRoutes: boolean;
}

export const DEFAULT_LAYERS: MapLayers = { provinceLines: false, countyLines: false, commanderyLines: false, cityNames: true, corpsRoutes: true };

const BACKGROUND: [number, number, number] = [12 / 255, 15 / 255, 14 / 255];
const AVAILABLE: [number, number, number] = [0x8f / 255, 0xa7 / 255, 0x7a / 255];
const UNAVAILABLE: [number, number, number] = [0xe0 / 255, 0x8a / 255, 0x7c / 255];
const SELECTED: [number, number, number] = [0xff / 255, 0xd3 / 255, 0x6d / 255];
const BAND_PX: Record<ViewLevel, number> = { county: 3, commandery: 4, ju: 5 };
const LABEL_FONT = "'Noto Serif KR Variable', 'Noto Serif KR', 'Nanum Myeongjo', serif";
const FLAG_PX = 32;
/** 조각 올리기(인터리브 + texSubImage3D)는 프레임당 이만큼만. */
const MAX_UPLOADS_PER_FRAME = 4;

export interface RendererStats { chunkFetches: number; chunksOnGpu: number; frames: number; lastFrameMs: number }

export class TopdownRenderer {
  private readonly gl: WebGL2RenderingContext;
  private readonly overlay: CanvasRenderingContext2D;
  private terrain: TerrainLayer | null = null;
  private loader: ChunkLoader | null = null;
  private manifest: BakeManifest | null = null;
  private places: PlacesData | null = null;
  private labels: LabelCandidate[] = [];
  private footprintIndex: FootprintIndex | null = null;
  private sites: SpriteSheet | null = null;
  private flags: SpriteSheet | null = null;
  private markers: SpriteSheet | null = null;
  private corps: readonly CorpsMarker[] = [];
  private readonly corpsArt: CorpsArt = createKitCorpsArt({
    sheets: () => ({ markers: this.markers, flags: this.flags }),
    cached: (key, make) => this.cached(key, make),
    font: LABEL_FONT,
  });
  private readonly spriteCache = new Map<string, OffscreenCanvas>();
  private readonly uploaded = new Map<string, ChunkData>();
  private world: WorldState | null = null;
  private nationById = new Map<number, WorldNation>();
  private nationOfProvince = new Int32Array(0);
  private camera: Camera = { center: { col: 1536, row: 1338 }, zoom: 16 };
  private viewport: Viewport = { width: 1, height: 1, dpr: 1 };
  private layers: MapLayers = DEFAULT_LAYERS;
  private pickMode = false;
  private scheduled = 0;
  private frames = 0;
  private lastFrameMs = 0;
  private sprites: SpriteHit[] = [];
  private me: MyLocation | null = null;
  private overview: ChunkData | null = null;
  private overviewSize = { cols: 0, rows: 0 };
  private mip1: Uint8ClampedArray | null = null;
  private mip1Width = 32;
  private overviewImage: OffscreenCanvas | null = null;
  private readonly measureCache = new Map<string, { width: number; height: number }>();

  constructor(private readonly glCanvas: HTMLCanvasElement, private readonly overlayCanvas: HTMLCanvasElement) {
    const gl = createGl(glCanvas);
    if (!gl) throw new Error('WebGL2 unavailable');
    this.gl = gl;
    const overlay = overlayCanvas.getContext('2d');
    if (!overlay) throw new Error('2d context unavailable');
    this.overlay = overlay;
  }

  /** Resolves when everything behind the first frame (mips, overview, places, sprites) has arrived. */
  complete: Promise<void> = Promise.resolve();

  /**
   * 첫 그림에 필요한 것만 기다린다: 매니페스트 · 키트 색인 · 팔레트(그다음 프레임이 보이는 조각을 받는다).
   * 밉 · 개관 · places · 스프라이트는 뒤에서 받아 오는 대로 얹는다(첫 화면 요청 수를 줄인다).
   */
  async load(source: TopdownSource): Promise<void> {
    const manifest = await fetchJson<BakeManifest>(joinUrl(source.bakeUrl, 'manifest.json'));
    if (manifest.schemaVersion !== 1 || manifest.artifactId !== 'topdown-bake') throw new Error('unsupported bake manifest');
    const shape: MapShape = manifest.shape;
    const [index, palettes] = await Promise.all([
      decodeGreyPng(joinUrl(source.kitUrl, 'kit-index.png')),
      fetchJson<{ dayBank: number; banks: number[][][] }>(joinUrl(source.kitUrl, 'palettes.json')),
    ]);
    const palette = new Uint8Array(16 * 4);
    palettes.banks[palettes.dayBank].forEach(([r, g, b], i) => palette.set([r, g, b, 255], i * 4));
    const terrain = new TerrainLayer(this.gl, shape, manifest.chunkSize);
    terrain.setKit({ index, palette, atlasColumns: index.width / 16 });
    this.terrain = terrain;
    this.manifest = manifest;
    this.loader = new ChunkLoader({
      manifest,
      fetchChunk: (entry) => fetchBytes(joinUrl(source.bakeUrl, entry.file!)),
      onEvict: (key) => {
        this.uploaded.delete(key);
      },
    });
    // 나머지는 보이는 조각이 도착한 뒤 받는다(같은 연결을 먼저 차지하지 않게). 州 보기거나 1.5초가 지나면 바로.
    this.complete = new Promise<void>((resolve, reject) => {
      let started = false;
      this.startRest = () => {
        if (started) return;
        started = true;
        this.startRest = null;
        this.loadRest(source, manifest).then(resolve, reject);
      };
      this.restTimer = window.setTimeout(() => this.startRest?.(), 1500);
    });
    this.requestFrame();
  }

  private startRest: (() => void) | null = null;
  private restTimer = 0;

  private async loadRest(source: TopdownSource, manifest: BakeManifest): Promise<void> {
    const kitUrl = (file: string) => joinUrl(source.kitUrl, file);
    const mips = Promise.all([8, 4, 2, 1].map((size) => loadBitmap(kitUrl(`kit-mip${size}.png`)))).then(([m8, m4, m2, m1]) => {
      this.terrain?.setMips({ 8: m8, 4: m4, 2: m2, 1: m1 });
      this.mip1 = bitmapPixels(m1);
      this.mip1Width = m1.width;
      this.requestFrame();
    });
    const overview = fetchOverview(source.bakeUrl, manifest).then((data) => {
      this.terrain?.setOverview(manifest.overview.cols, manifest.overview.rows, manifest.overview.block, data);
      this.overview = data;
      this.overviewSize = { cols: manifest.overview.cols, rows: manifest.overview.rows };
      this.requestFrame();
    });
    const places = fetchJson<unknown>(joinUrl(source.bakeUrl, manifest.places.file)).then((raw) => {
      const data = parsePlaces(raw);
      const admin = adminTexels(data);
      this.terrain?.setAdmin(admin.width, admin.height, admin.data);
      this.places = data;
      this.labels = labelCandidates(data);
      this.footprintIndex = new FootprintIndex(footprints(data));
      this.setWorld(this.world ?? { occupancy: [], nations: [] });
    });
    const sprites = Promise.all([
      loadSheet(kitUrl('sites.png'), kitUrl('sites-roles.png')),
      loadSheet(kitUrl('flags.png'), kitUrl('flags-roles.png')),
      loadSheet(kitUrl('markers.png'), kitUrl('markers-roles.png')).catch(() => null),
    ]).then(([sites, flags, markers]) => {
      this.sites = sites;
      this.flags = flags;
      this.markers = markers;
      this.requestFrame();
    });
    await Promise.all([mips, overview, places, sprites]);
  }

  get shape(): MapShape | null {
    return this.manifest?.shape ?? null;
  }

  get placesData(): PlacesData | null {
    return this.places;
  }

  setWorld(world: WorldState): void {
    this.world = world;
    this.nationById = new Map(world.nations.map((n) => [n.id, n]));
    if (!this.terrain || !this.places) return;
    const table = buildProvinceTable({
      provinceCount: this.places.provinceCount,
      occupancy: world.occupancy,
      nations: world.nations,
      commanderyOfProvince: world.commanderyOfProvince,
      vision: world.vision,
      pick: world.pick,
      selected: world.selectedProvinces,
    });
    this.pickMode = !!world.pick;
    this.nationOfProvince = new Int32Array(this.places.provinceCount).fill(0);
    for (const o of world.occupancy) {
      if (o.provinceIndex >= 0 && o.provinceIndex < this.nationOfProvince.length) this.nationOfProvince[o.provinceIndex] = o.nationId;
    }
    this.terrain.setProvinceTable(table.width, table.height, table.bytes, table.nationPalette);
    this.requestFrame();
  }

  /** 부대 표지(K2-08). 빈 배열이면 지운다. */
  setCorps(corps: readonly CorpsMarker[]): void {
    this.corps = corps;
    this.requestFrame();
  }

  /** 내 위치 표지(M2-11). null이면 지운다. */
  setMe(me: MyLocation | null): void {
    this.me = me;
    this.requestFrame();
  }

  /**
   * Whole-map picture for the minimap: one pixel per overview entry, coloured by the tile's 1-px mip.
   * Built once from data already loaded (no extra requests).
   */
  overviewPicture(): OffscreenCanvas | null {
    if (this.overviewImage) return this.overviewImage;
    if (!this.overview || !this.mip1) return null;
    const { cols, rows } = this.overviewSize;
    this.overviewImage = pixelsToCanvas(overviewPixels(this.overview, cols, rows, this.mip1, this.mip1Width), cols, rows);
    return this.overviewImage;
  }

  setLayers(layers: MapLayers): void {
    this.layers = layers;
    this.requestFrame();
  }

  setView(camera: Camera, viewport: Viewport): void {
    const resized = viewport.width !== this.viewport.width || viewport.height !== this.viewport.height || viewport.dpr !== this.viewport.dpr;
    this.camera = camera;
    this.viewport = viewport;
    if (resized) {
      for (const canvas of [this.glCanvas, this.overlayCanvas]) {
        canvas.width = Math.round(viewport.width * viewport.dpr);
        canvas.height = Math.round(viewport.height * viewport.dpr);
      }
    }
    this.requestFrame();
  }

  requestFrame(): void {
    if (this.scheduled) return;
    this.scheduled = requestAnimationFrame(() => {
      this.scheduled = 0;
      this.frame();
    });
  }

  hit(point: { x: number; y: number }): HitResult {
    return hitTest(point, this.camera, this.viewport, {
      sprites: this.sprites,
      footprints: this.footprintIndex ?? undefined,
      provinceAt: (col, row) => this.provinceAt(col, row),
    });
  }

  provinceAt(col: number, row: number): number {
    const manifest = this.manifest;
    if (!manifest || !this.loader) return 0;
    const size = manifest.chunkSize;
    const data = this.loader.peek(Math.floor(col / size), Math.floor(row / size));
    if (!data) return 0;
    return data.provinces[(row % size) * size + (col % size)];
  }

  stats(): RendererStats {
    return {
      chunkFetches: this.loader?.stats().fetches ?? 0,
      chunksOnGpu: this.uploaded.size,
      frames: this.frames,
      lastFrameMs: this.lastFrameMs,
    };
  }

  /**
   * Frees GPU objects. The context itself is left alone: React strict mode mounts twice on the same canvas,
   * and getContext would hand the second renderer the context the first one lost.
   */
  dispose(): void {
    if (this.scheduled) cancelAnimationFrame(this.scheduled);
    this.scheduled = 0;
    window.clearTimeout(this.restTimer);
    this.startRest = null;
    this.terrain?.dispose();
    this.terrain = null;
  }

  private frame(): void {
    const started = performance.now();
    const terrain = this.terrain;
    const manifest = this.manifest;
    if (!terrain || !manifest || !this.loader) return;
    const level = viewLevel(this.camera.zoom);
    if (level !== 'ju') this.streamChunks(manifest);
    if (this.startRest && (level === 'ju' || this.visibleChunksReady(manifest))) this.startRest();
    terrain.draw(this.camera, this.viewport, {
      forceOverview: level === 'ju',
      bandPx: BAND_PX[level],
      adminLines: (this.layers.provinceLines ? 1 : 0) | (this.layers.countyLines ? 2 : 0)
        | (this.layers.commanderyLines ? 4 : 0) | (level === 'ju' ? 8 : 0),
      pickMode: this.pickMode,
      background: BACKGROUND,
      availableColor: AVAILABLE,
      unavailableColor: UNAVAILABLE,
      selectedColor: SELECTED,
    });
    this.drawOverlay(level);
    this.frames += 1;
    this.lastFrameMs = performance.now() - started;
  }

  private streamChunks(manifest: BakeManifest): void {
    const rect = visibleCellRect(this.camera, this.viewport, manifest.shape);
    const wanted = chunksForRect(rect, manifest.chunkSize, manifest.shape, 1);
    const visible = new Set(chunksForRect(rect, manifest.chunkSize, manifest.shape, 0).map((c) => chunkKey(c.cx, c.cy)));
    const plan = planChunks(wanted, {
      peek: (cx, cy) => this.loader!.peek(cx, cy),
      onGpu: (cx, cy) => this.terrain!.hasChunk(cx, cy),
      uploaded: (key) => this.uploaded.get(key),
    }, MAX_UPLOADS_PER_FRAME);
    for (const { cx, cy, key, data } of plan.upload) {
      this.terrain!.putChunk(cx, cy, data);
      this.uploaded.set(key, data);
    }
    if (plan.more) this.requestFrame(); // 한 프레임에 몰아 올리지 않는다(첫 끌기 튐)
    for (const { cx, cy } of plan.request) {
      this.loader!.request(cx, cy).then(() => this.requestFrame(), () => undefined);
    }
    this.terrain!.touch(visible);
  }

  private visibleChunksReady(manifest: BakeManifest): boolean {
    const rect = visibleCellRect(this.camera, this.viewport, manifest.shape);
    return chunksForRect(rect, manifest.chunkSize, manifest.shape, 0).every(({ cx, cy }) => this.loader!.peek(cx, cy) !== undefined);
  }

  private measure = (text: string, fontPx: number, bold: boolean): { width: number; height: number } => {
    const key = `${fontPx}|${bold}|${text}`;
    const known = this.measureCache.get(key);
    if (known) return known;
    this.overlay.font = `${bold ? 900 : 700} ${fontPx}px ${LABEL_FONT}`;
    const size = { width: Math.ceil(this.overlay.measureText(text).width) + 12, height: fontPx + 6 };
    this.measureCache.set(key, size);
    return size;
  };

  private drawOverlay(level: ViewLevel): void {
    const ctx = this.overlay;
    const { dpr } = this.viewport;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, this.overlayCanvas.width, this.overlayCanvas.height);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.imageSmoothingEnabled = false;
    const sprites: SpriteHit[] = [];
    const places = this.places;
    if (!places) return;
    const cam = this.camera;
    const rect = visibleCellRect(cam, this.viewport, places.cities.length ? this.manifest!.shape : { cols: 0, rows: 0 });
    const inView = (col: number, row: number, margin: number) =>
      col >= rect.col0 - margin && col < rect.col1 + margin && row >= rect.row0 - margin && row < rect.row1 + margin;

    if (level === 'county') {
      for (const city of places.cities) {
        if (!city.site || !inView(city.cell[0], city.cell[1], 2) || !this.sites) continue;
        const nation = this.ownedNation(city.provinceIndex);
        const colour = nation?.color ?? null;
        const sprite = this.cached(`site|${city.site}|${colour ?? 'none'}`, () => drawSite(this.sites!, city.site!, colour));
        const at = cellToScreen({ col: city.cell[0], row: city.cell[1] }, cam, this.viewport);
        ctx.drawImage(sprite, at.x, at.y, cam.zoom, cam.zoom);
      }
    }
    if (level !== 'ju' && this.flags) {
      for (const city of places.cities) {
        if (level === 'commandery' && !city.isSeat && city.level !== 3) continue;
        const fp = city.footprint;
        if (!inView(fp.originCol, fp.originRow, 4)) continue;
        const nation = this.ownedNation(city.provinceIndex);
        if (!nation) continue;
        const letter = [...nation.name][0] ?? '';
        const flag = this.cached(`flag|fringe|${nation.color}|${letter}`, () => drawFlag(this.flags!, 'fringe', nation.color, letter, FLAG_PX / 16, LABEL_FONT));
        const corner = cellToScreen({ col: fp.originCol, row: fp.originRow }, cam, this.viewport);
        const x = corner.x - FLAG_PX * 0.08;
        const y = corner.y - FLAG_PX * 0.72;
        ctx.drawImage(flag, x, y, FLAG_PX, FLAG_PX);
        sprites.push({ kind: 'flag', id: String(city.id), rect: { x, y, width: FLAG_PX, height: FLAG_PX }, z: 1 });
      }
    }
    const corpsBoxes: { x: number; y: number; width: number; height: number }[] = [];
    if (level !== 'ju') {
      const toScreen = (cell: { col: number; row: number }) => cellToScreen({ col: cell.col + 0.5, row: cell.row + 0.5 }, cam, this.viewport);
      const placed = this.corps
        .filter((marker) => inView(marker.cell.col, marker.cell.row, 40))
        .map((marker) => ({ marker, place: corpsPlacement(marker, cam.zoom, toScreen) }));
      // 경로를 모두 먼저 그려 다른 부대 표지를 덮지 않게 한다
      if (this.layers.corpsRoutes) for (const { marker, place } of placed) this.corpsArt.drawRoute(ctx, marker, place.route);
      for (const { marker, place } of placed) {
        if (marker.heading) this.corpsArt.drawBody(ctx, marker as CorpsMarker & { heading: Heading }, place.body);
        this.corpsArt.drawFlag(ctx, marker, place.flag);
        sprites.push({ kind: 'corps', id: marker.id, rect: place.hit, z: CORPS_HIT_Z });
        corpsBoxes.push(place.body, place.flag);
      }
    }
    if (this.layers.cityNames || level === 'ju') {
      const hidden = new Set<LabelKind>(this.layers.cityNames ? [] : ['county', 'commanderySeat', 'pass', 'ferry']);
      const candidates = this.labels.filter((l) => l.kind === 'ju' || l.kind === 'commandery' || inView(l.anchor.col, l.anchor.row, 8));
      for (const label of layoutLabels(candidates, cam, this.viewport, this.measure, { hidden, avoid: corpsBoxes })) {
        ctx.fillStyle = 'rgba(12,15,14,0.72)';
        ctx.fillRect(label.x, label.y, label.width, label.height);
        ctx.fillStyle = '#f5ecd6';
        ctx.font = `${label.kind === 'ju' ? 900 : 700} ${label.fontPx}px ${LABEL_FONT}`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(label.text, label.x + label.width / 2, label.y + label.height / 2 + 1);
      }
    }
    if (this.me) {
      const placement = drawMyLocation(ctx, this.me, (cell) => cellToScreen({ col: cell.col + 0.5, row: cell.row + 0.5 }, cam, this.viewport), this.viewport);
      sprites.push({ kind: 'me', id: 'me', rect: myLocationHitRect(placement), z: 10 });
    }
    this.sprites = sprites;
  }

  /** 지붕 색과 같은 규칙: id > 0이고 #rrggbb 색인 세력만 깃발 · 거점에 칠한다. */
  private ownedNation(provinceIndex: number): WorldNation | null {
    const nation = this.nationById.get(this.nationOfProvince[provinceIndex] ?? 0);
    return nation && isOwnedNationVisual(nation.id, nation.color) ? nation : null;
  }

  private cached(key: string, make: () => OffscreenCanvas): OffscreenCanvas {
    let canvas = this.spriteCache.get(key);
    if (!canvas) {
      canvas = make();
      this.spriteCache.set(key, canvas);
    }
    return canvas;
  }
}

async function loadSheet(rgbaUrl: string, rolesUrl: string): Promise<SpriteSheet> {
  const [bitmap, roles] = await Promise.all([loadBitmap(rgbaUrl), decodeGreyPng(rolesUrl)]);
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(bitmap, 0, 0);
  return sheetFrom(ctx.getImageData(0, 0, bitmap.width, bitmap.height), roles.data);
}
