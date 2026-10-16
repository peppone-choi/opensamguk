// 전장 판을 브라우저 캔버스로: 키트 받기(한 번만), 판 그림 한 장, 바뀐 칸만 다시 칠하기, 작은 판, 유닛 얹기.
// 판 그림 v2(아트 팩, 기능 플래그): 같은 투영 · 판 크기로 팩 프레임을 조립한 그림 한 장(artBoardPicture)과 팩 유닛 그림(drawArtUnit).
import { boardRgba, composeBoard, drawRank, parseBattleKit, redrawCells, type BattleKit, type BoardView, type ComposedBoard, type KitJson } from './battleBoard';
import {
  occludeUnit,
  parseUnitKit,
  unitPlacement,
  unitRamp,
  unitScreenRects,
  unitSpriteRgba,
  UNIT_SIZE,
  type ScreenRect,
  type UnitFacing,
  type UnitKit,
} from './battleUnits';
import { boardTerrain, groundBoard, planBoard } from './boardComposer';
import { LruCache } from './artpack/lru';
import { resolveUnitFrame, type ArtpackAction, type ArtpackFrame } from './artpack/manifest';
import type { LoadedArtpack } from './artpack/loader';

const kits = new Map<string, Promise<BattleKit>>();

async function fetchBytes(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  const bytes = await response.arrayBuffer();
  if (!url.endsWith('.gz')) return bytes;
  return new Response(new Response(bytes).body!.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();
}

const join = (base: string, file: string) => `${base.replace(/\/+$/, '')}/${file}`;

const kitJsons = new Map<string, Promise<KitJson>>();

/** kit.json once per base URL (the board and the unit loaders share it). */
function loadKitJson(baseUrl: string): Promise<KitJson> {
  const known = kitJsons.get(baseUrl);
  if (known) return known;
  const load = fetchBytes(join(baseUrl, 'kit.json')).then((bytes) => JSON.parse(new TextDecoder().decode(bytes)) as KitJson);
  kitJsons.set(baseUrl, load);
  load.catch(() => kitJsons.delete(baseUrl));
  return load;
}

/** Fetches the kit once per base URL (about 0.3 MB). */
export function loadBattleKit(baseUrl: string): Promise<BattleKit> {
  const known = kits.get(baseUrl);
  if (known) return known;
  const load = (async () => {
    const [json, pieces, records, boards] = await Promise.all([
      loadKitJson(baseUrl),
      fetchBytes(join(baseUrl, 'pieces.bin.gz')),
      fetchBytes(join(baseUrl, 'records.bin')),
      fetchBytes(join(baseUrl, 'boards.bin.gz')),
    ]);
    return parseBattleKit(json, { pieces, records, boards });
  })();
  kits.set(baseUrl, load);
  load.catch(() => kits.delete(baseUrl));
  return load;
}

export interface BoardPicture {
  board: ComposedBoard;
  /** Original-size picture (board px); draw it scaled with drawImage while pinching — no re-assembly. */
  canvas: OffscreenCanvas;
}

export function boardPicture(kit: BattleKit, boardId: number, overrides?: ReadonlyMap<string, number>): BoardPicture {
  const board = composeBoard(kit, boardId, overrides);
  const canvas = new OffscreenCanvas(board.width, board.height);
  canvas.getContext('2d')!.putImageData(new ImageData(boardRgba(kit, board), board.width, board.height), 0, 0);
  return { board, canvas };
}

/** Structure changes (gate, wall, ladder record swaps): repaint only the covered rectangle. */
export function updateBoardPicture(kit: BattleKit, picture: BoardPicture, changes: ReadonlyMap<string, number>): void {
  const rect = redrawCells(kit, picture.board, changes);
  if (!rect || rect.width <= 0 || rect.height <= 0) return;
  picture.canvas.getContext('2d')!.putImageData(new ImageData(boardRgba(kit, picture.board, rect), rect.width, rect.height), rect.x, rect.y);
}

/** Small whole-board picture (smoothed) for the mini board. */
export function boardThumbnail(picture: BoardPicture, maxSize: number): OffscreenCanvas {
  const scale = Math.min(maxSize / picture.board.width, maxSize / picture.board.height);
  const thumb = new OffscreenCanvas(Math.max(1, Math.round(picture.board.width * scale)), Math.max(1, Math.round(picture.board.height * scale)));
  const ctx = thumb.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(picture.canvas, 0, 0, thumb.width, thumb.height);
  return thumb;
}

// ── 유닛(분대 표기 B안) ──

const unitKits = new Map<string, Promise<UnitKit>>();

/** Unit sprites and roles (about 60 KB), fetched after the board so the board shows first. */
export function loadUnitKit(baseUrl: string): Promise<UnitKit> {
  const known = unitKits.get(baseUrl);
  if (known) return known;
  const load = (async () => {
    const [json, units, roles] = await Promise.all([
      loadKitJson(baseUrl),
      fetchBytes(join(baseUrl, 'units.bin.gz')),
      fetchBytes(join(baseUrl, 'unit-roles.bin.gz')),
    ]);
    return parseUnitKit(json.palette.rgb, { units, roles });
  })();
  unitKits.set(baseUrl, load);
  load.catch(() => unitKits.delete(baseUrl));
  return load;
}

export interface BoardUnit {
  id: string;
  r: number;
  c: number;
  /** Unit sprite 0–89 (see UNIT_ACTIONS / unitFrame). */
  frame: number;
  /** Nation colour #rrggbb. */
  colour: string;
  /** First letter on the flag (the leader's name); empty = flag without a letter. */
  letter: string;
}

export interface DrawnUnit {
  id: string;
  /** Touch area (≥ 44 px). */
  hit: ScreenRect;
  /** Flag box on screen (for labels or cards placed next to it). */
  flag: ScreenRect;
}

const spriteCache = new LruCache<string, Uint8ClampedArray>();
let scratch: OffscreenCanvas | null = null;

function cachedSprite(kit: UnitKit, frame: number, colour: string): Uint8ClampedArray {
  const key = `${frame}|${colour}`;
  let sprite = spriteCache.get(key);
  if (!sprite) {
    sprite = unitSpriteRgba(kit, frame, unitRamp(colour));
    spriteCache.set(key, sprite);
  }
  return sprite;
}

function lightColour(hex: string): boolean {
  const n = parseInt(hex.replace('#', ''), 16);
  return (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255 >= 0.62;
}

/** Swallowtail flag (design B: pole, cloth in the nation colour, the leader's first letter). */
function drawUnitFlag(ctx: CanvasRenderingContext2D, rect: ScreenRect, colour: string, letter: string): void {
  const s = rect.width / 20; // design viewBox 20 × 14
  ctx.save();
  ctx.translate(rect.x, rect.y);
  ctx.scale(s, s);
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = '#1b201d';
  ctx.beginPath();
  ctx.moveTo(2, 0);
  ctx.lineTo(2, 14);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(3, 1);
  ctx.lineTo(18, 1);
  ctx.lineTo(14, 6);
  ctx.lineTo(18, 11);
  ctx.lineTo(3, 11);
  ctx.closePath();
  ctx.fillStyle = colour;
  ctx.fill();
  ctx.lineWidth = 1;
  ctx.strokeStyle = '#0c0f0e';
  ctx.stroke();
  if (letter) {
    ctx.fillStyle = lightColour(colour) ? '#141413' : '#ffffff';
    ctx.font = "900 8px 'Noto Serif KR Variable', 'Noto Serif KR', serif";
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(letter, 9, 6.2);
  }
  ctx.restore();
}

/**
 * Draws units over the board picture (already drawn with the same view), back to front in the original order,
 * with terrain drawn later hiding them; flags go on top. Returns each unit's touch area, front-most last.
 */
export function drawBoardUnits(
  ctx: CanvasRenderingContext2D,
  kit: BattleKit,
  units: UnitKit,
  picture: BoardPicture,
  list: readonly BoardUnit[],
  view: BoardView,
): DrawnUnit[] {
  const { side } = kit.layout;
  const rank = drawRank(side);
  const sorted = [...list].filter((u) => u.r >= 0 && u.r < side && u.c >= 0 && u.c < side).sort((a, b) => rank[a.r * side + a.c] - rank[b.r * side + b.c]);
  scratch ??= new OffscreenCanvas(UNIT_SIZE, UNIT_SIZE);
  const sctx = scratch.getContext('2d')!;
  const smoothing = ctx.imageSmoothingEnabled;
  ctx.imageSmoothingEnabled = false;
  const placed = sorted.map((unit) => {
    const placement = unitPlacement(kit, unit.r, unit.c);
    const sprite = occludeUnit(picture.board, cachedSprite(units, unit.frame, unit.colour).slice(), placement, side);
    sctx.putImageData(new ImageData(sprite, UNIT_SIZE, UNIT_SIZE), 0, 0);
    const rects = unitScreenRects(placement, view);
    ctx.drawImage(scratch!, rects.sprite.x, rects.sprite.y, rects.sprite.width, rects.sprite.height);
    return { unit, rects };
  });
  ctx.imageSmoothingEnabled = smoothing;
  for (const { unit, rects } of placed) drawUnitFlag(ctx, rects.flag, unit.colour, unit.letter);
  return placed.map(({ unit, rects }) => ({ id: unit.id, hit: rects.hit, flag: rects.flag }));
}

// ── 판 그림 v2(아트 팩) ──

/** Largest picture resolution (picture px per board px) and area — the kit canvas 2080 × 1160 at 2 is about 9.7 M px. */
export const ART_PICTURE_MAX_RESOLUTION = 2;
const ART_PICTURE_MAX_PIXELS = 16_777_216;

/** Picture px per board px for a pack: its own scale, capped by ART_PICTURE_MAX_RESOLUTION and the area limit. */
export function artPictureResolution(board: { width: number; height: number }, pixelsPerBoardPx: number): number {
  let res = Math.max(1, Math.min(pixelsPerBoardPx, ART_PICTURE_MAX_RESOLUTION));
  while (res > 1 && board.width * board.height * res * res > ART_PICTURE_MAX_PIXELS) res -= 1;
  return res;
}

/**
 * The board drawn from an art pack (same size in board px as boardPicture, canvas at artPictureResolution).
 * drawImage it with board width × height like the v1 picture; taps use the ground diamonds (groundBoard).
 */
export function artBoardPicture(kit: BattleKit, boardId: number, pack: LoadedArtpack, overrides?: ReadonlyMap<string, number>): BoardPicture {
  const terrain = boardTerrain(kit, boardId, overrides);
  const board = groundBoard(kit, boardId, terrain.grid);
  const res = artPictureResolution(board, pack.manifest.pixelsPerBoardPx);
  const canvas = new OffscreenCanvas(board.width * res, board.height * res);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = res < pack.manifest.pixelsPerBoardPx;
  ctx.imageSmoothingQuality = 'high';
  for (const item of planBoard(kit, boardId, terrain, pack.manifest)) {
    const f = item.frame;
    ctx.drawImage(pack.atlases[f.atlas], f.x, f.y, f.w, f.h, item.x * res, item.y * res, item.w * res, item.h * res);
  }
  return { board, canvas };
}

const artSpriteCache = new LruCache<string, OffscreenCanvas>();

function frameCanvas(pack: LoadedArtpack, frame: ArtpackFrame, flip: boolean): OffscreenCanvas {
  const canvas = new OffscreenCanvas(frame.w, frame.h);
  const ctx = canvas.getContext('2d')!;
  if (flip) ctx.setTransform(-1, 0, 0, 1, frame.w, 0);
  ctx.drawImage(pack.atlases[frame.atlas], frame.x, frame.y, frame.w, frame.h, 0, 0, frame.w, frame.h);
  return canvas;
}

/** A pack frame (mirrored if asked) with its grey mask painted in the nation colour × grey level. */
function artSprite(pack: LoadedArtpack, frame: ArtpackFrame, mask: ArtpackFrame | null, flip: boolean, colour: string | undefined): OffscreenCanvas {
  const tint = mask && colour ? colour : '';
  const key = `${pack.manifest.packId}|${frame.atlas},${frame.x},${frame.y}|${flip ? 1 : 0}|${tint}`;
  const known = artSpriteCache.get(key);
  if (known) return known;
  const sprite = frameCanvas(pack, frame, flip);
  if (mask && tint) {
    const layer = frameCanvas(pack, mask, flip);
    const lctx = layer.getContext('2d')!;
    const pixels = lctx.getImageData(0, 0, mask.w, mask.h);
    const [red, green, blue] = unitRamp(tint).main;
    for (let at = 0; at < pixels.data.length; at += 4) {
      const grey = pixels.data[at] / 255;
      pixels.data[at] = red * grey;
      pixels.data[at + 1] = green * grey;
      pixels.data[at + 2] = blue * grey;
    }
    lctx.putImageData(pixels, 0, 0);
    sprite.getContext('2d')!.drawImage(layer, 0, 0);
  }
  artSpriteCache.set(key, sprite);
  return sprite;
}

export interface ArtUnit {
  /** Pack unit slot (unit type code); undefined or not in the pack → null (draw the plain diamond). */
  slot?: string;
  facing?: UnitFacing;
  action?: ArtpackAction;
  frame?: number;
  /** Nation colour #rrggbb for the mask; undefined = no tint. */
  colour?: string;
}

/** Draws a pack unit with its foot on `foot` (screen px). Returns the drawn rectangle, or null when the pack has no art for it. */
export function drawArtUnit(ctx: CanvasRenderingContext2D, pack: LoadedArtpack, unit: ArtUnit, foot: { x: number; y: number }, view: BoardView): ScreenRect | null {
  const resolved = resolveUnitFrame(pack.manifest, unit.slot, unit.action ?? 'idle', unit.facing ?? 'sw', unit.frame ?? 0);
  if (!resolved) return null;
  const { frame, mask, flip } = resolved;
  const k = view.scale / pack.manifest.pixelsPerBoardPx;
  const ax = flip ? frame.w - frame.ax : frame.ax;
  const rect = { x: foot.x - ax * k, y: foot.y - frame.ay * k, width: frame.w * k, height: frame.h * k };
  const smoothing = ctx.imageSmoothingEnabled;
  ctx.imageSmoothingEnabled = k < 1;
  ctx.drawImage(artSprite(pack, frame, mask, flip, unit.colour), rect.x, rect.y, rect.width, rect.height);
  ctx.imageSmoothingEnabled = smoothing;
  return rect;
}

/** The unit under a screen point (front-most wins), from drawBoardUnits' result. */
export function pickUnit(drawn: readonly DrawnUnit[], x: number, y: number): string | null {
  for (let i = drawn.length - 1; i >= 0; i -= 1) {
    const { hit } = drawn[i];
    if (x >= hit.x && x < hit.x + hit.width && y >= hit.y && y < hit.y + hit.height) return drawn[i].id;
  }
  return null;
}
