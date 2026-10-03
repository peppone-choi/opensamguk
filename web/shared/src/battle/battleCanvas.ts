// 전장 판을 브라우저 캔버스로: 키트 받기(한 번만), 판 그림 한 장, 바뀐 칸만 다시 칠하기, 작은 판, 유닛 얹기.
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
  type UnitKit,
} from './battleUnits';

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

const spriteCache = new Map<string, Uint8ClampedArray>();
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

/** The unit under a screen point (front-most wins), from drawBoardUnits' result. */
export function pickUnit(drawn: readonly DrawnUnit[], x: number, y: number): string | null {
  for (let i = drawn.length - 1; i >= 0; i -= 1) {
    const { hit } = drawn[i];
    if (x >= hit.x && x < hit.x + hit.width && y >= hit.y && y < hit.y + hit.height) return drawn[i].id;
  }
  return null;
}
