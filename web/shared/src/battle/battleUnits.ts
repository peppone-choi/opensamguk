// 와룡전 전투 유닛(분대 표기 B안: 원작 유닛 그림 + 머리 위 작은 깃발, 기본 배율 원작 2배).
// 빨강 편 조각을 틀로 역할층(주색 · 그늘 · 밝은 곳 · 깃발 테두리)만 세력색으로 칠한다(원작 파랑 편과 99% 같음).
// 자리는 원작 규칙: 아래 조각은 제 칸 층 0, 위 조각은 층 1 자리에 그리고, 뒤에 그려질 지형 조각이 가린다.
import { drawRank, pieceOrigin, type BattleKit, type BoardView, type ComposedBoard } from './battleBoard';

export const UNIT_PIECE_WIDTH = 32;
export const UNIT_PIECE_HEIGHT = 16;
const PIECE_BYTES = UNIT_PIECE_WIDTH * UNIT_PIECE_HEIGHT;
/** Pieces per side; 0–179 red, 180–359 blue. A unit u = pieces 2u (top) and 2u + 1 (bottom). */
export const UNIT_PIECES_PER_SIDE = 180;
export const UNITS_PER_SIDE = UNIT_PIECES_PER_SIDE / 2;
export const UNIT_SIZE = 32;
const EMPTY = 255;
/** Single-piece items (small flags, arrows, debris): both sides are drawn differently, so roles do not apply. */
const SINGLE_PIECES = { from: 152, to: 167 };

export type Rgb = [number, number, number];

export interface UnitKit {
  /** [piece][16][32] palette index, 255 transparent. */
  pieces: Uint8Array;
  /** [red piece][16][32]: 0 keep, 1 main, 2 shade, 3 light, 4 flag rim. */
  roles: Uint8Array;
  palette: Rgb[];
}

export function parseUnitKit(palette: Rgb[], files: { units: ArrayBuffer; roles: ArrayBuffer }): UnitKit {
  const expect = (name: string, buffer: ArrayBuffer, bytes: number) => {
    if (buffer.byteLength !== bytes) throw new Error(`battle units: ${name} has ${buffer.byteLength} bytes, expected ${bytes}`);
  };
  expect('units', files.units, 2 * UNIT_PIECES_PER_SIDE * PIECE_BYTES);
  expect('roles', files.roles, UNIT_PIECES_PER_SIDE * PIECE_BYTES);
  if (palette.length < 16) throw new Error('battle units: palette needs 16 colours');
  return { pieces: new Uint8Array(files.units), roles: new Uint8Array(files.roles), palette };
}

// ── 역할 색: recolor.py ramp()와 같은 식(colorsys HLS) ──

export interface UnitRamp {
  main: Rgb;
  shade: Rgb;
  light: Rgb;
  trim: Rgb;
}

function rgbToHls(r: number, g: number, b: number): [number, number, number] {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, l, 0];
  const range = max - min;
  const s = l <= 0.5 ? range / (max + min) : range / (2 - max - min);
  const rc = (max - r) / range;
  const gc = (max - g) / range;
  const bc = (max - b) / range;
  const h = r === max ? bc - gc : g === max ? 2 + rc - bc : 4 + gc - rc;
  return [(((h / 6) % 1) + 1) % 1, l, s];
}

function hlsToRgb(h: number, l: number, s: number): [number, number, number] {
  if (s === 0) return [l, l, l];
  const m2 = l <= 0.5 ? l * (1 + s) : l + s - l * s;
  const m1 = 2 * l - m2;
  const v = (hue: number) => {
    const x = ((hue % 1) + 1) % 1;
    if (x < 1 / 6) return m1 + (m2 - m1) * x * 6;
    if (x < 0.5) return m2;
    if (x < 2 / 3) return m1 + (m2 - m1) * (2 / 3 - x) * 6;
    return m1;
  };
  return [v(h + 1 / 3), v(h), v(h - 1 / 3)];
}

const toByte = (value: number) => Math.min(255, Math.max(0, Math.round(value * 255)));

/** Four role colours from one nation colour (#rrggbb). */
export function unitRamp(hex: string): UnitRamp {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`battle units: bad colour ${hex}`);
  const n = parseInt(m[1], 16);
  const c: [number, number, number] = [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
  const [h, l, s] = rgbToHls(...c);
  const shade = hlsToRgb(h, Math.max(l * 0.42, 0.06), Math.min(s, 1));
  const light = hlsToRgb(h, Math.min(l * 0.55 + 0.3, 0.85), s * 0.35);
  // 깃발 테두리: 어두운 색엔 금색, 밝은 색엔 검정
  const trim: [number, number, number] = l < 0.62 ? [1, 0.93, 0] : [0, 0, 0];
  const bytes = (rgb: [number, number, number]): Rgb => [toByte(rgb[0]), toByte(rgb[1]), toByte(rgb[2])];
  return { main: bytes(c), shade: bytes(shade), light: bytes(light), trim: bytes(trim) };
}

/** The original blue side's role colours (palette 3 · 8 · 1 · 12), for comparing the template with the original art. */
export function paletteRamp(palette: Rgb[], main: number, shade: number, light: number, trim: number): UnitRamp {
  return { main: palette[main], shade: palette[shade], light: palette[light], trim: palette[trim] };
}

/**
 * RGBA of one 32 × 16 piece. `side` picks the original art; with a ramp the red template's role pixels are repainted
 * (single-piece items keep the original red art — their roles do not line up).
 */
export function unitPieceRgba(kit: UnitKit, piece: number, ramp: UnitRamp | null, side: 'red' | 'blue' = 'red'): Uint8ClampedArray {
  const out = new Uint8ClampedArray(PIECE_BYTES * 4);
  const source = side === 'blue' && !ramp ? piece + UNIT_PIECES_PER_SIDE : piece;
  const recolour = ramp && !(piece >= SINGLE_PIECES.from && piece <= SINGLE_PIECES.to) ? ramp : null;
  const roleColour = recolour ? [null, recolour.main, recolour.shade, recolour.light, recolour.trim] : null;
  for (let i = 0; i < PIECE_BYTES; i += 1) {
    const value = kit.pieces[source * PIECE_BYTES + i];
    if (value === EMPTY) continue;
    const colour = roleColour?.[kit.roles[piece * PIECE_BYTES + i]] ?? kit.palette[value];
    out[i * 4] = colour[0];
    out[i * 4 + 1] = colour[1];
    out[i * 4 + 2] = colour[2];
    out[i * 4 + 3] = 255;
  }
  return out;
}

/** 32 × 32 RGBA of unit `u` (0–89): top piece 2u over bottom piece 2u + 1. */
export function unitSpriteRgba(kit: UnitKit, unit: number, ramp: UnitRamp | null, side: 'red' | 'blue' = 'red'): Uint8ClampedArray {
  if (!(unit >= 0 && unit < UNITS_PER_SIDE)) throw new Error(`battle units: unit ${unit} out of range`);
  const out = new Uint8ClampedArray(UNIT_SIZE * UNIT_SIZE * 4);
  out.set(unitPieceRgba(kit, unit * 2, ramp, side), 0);
  out.set(unitPieceRgba(kit, unit * 2 + 1, ramp, side), PIECE_BYTES * 4);
  return out;
}

// ── 동작 표(KI.EXE 0xb2f1–0xb319 정적 해독, 2026-09-30): u = base + (피격 ? 16 : 2·방향 + (공격 ? 8 : 0)) + 위상 ──

/** Sprite group per troop type. `leader` (black horse) is what the original gives each side's unit slot 0. */
export const UNIT_TYPE_BASE = { leader: 0, lightCavalry: 18, archer: 36, infantry: 54 } as const;
export type UnitType = keyof typeof UNIT_TYPE_BASE;
/** Screen facings in the original's index order: x−1 · y−1 · x+1 · y+1 on the board (x = c, y = r). */
export const UNIT_FACINGS = ['sw', 'nw', 'ne', 'se'] as const;
export type UnitFacing = (typeof UNIT_FACINGS)[number];
/** The original alternates two phases on every battle tick whatever the unit does (no separate stand frame). */
export type UnitAction = 'idle' | 'attack' | 'hit';
/** Falling frames per type, shown one tick then two ticks before the unit is removed. */
export const UNIT_FALLEN: Record<UnitType, readonly [number, number]> = {
  leader: [84, 85],
  lightCavalry: [84, 85],
  archer: [86, 87],
  infantry: [88, 89],
};
/** Big banners (4-frame loop, one frame per tick). */
export const UNIT_BANNER_FRAMES = [72, 73, 74, 75] as const;

export function unitFrame(type: UnitType, facing: UnitFacing, action: UnitAction, phase: 0 | 1): number {
  const base = UNIT_TYPE_BASE[type];
  if (action === 'hit') return base + 16 + phase; // 피격은 방향 없이 그린다
  return base + 2 * UNIT_FACINGS.indexOf(facing) + (action === 'attack' ? 8 : 0) + phase;
}

/** Facing for a one-cell step (dr, dc); null when not a 4-neighbour step. */
export function facingOf(dr: number, dc: number): UnitFacing | null {
  if (dr === 0 && dc === -1) return 'sw';
  if (dr === -1 && dc === 0) return 'nw';
  if (dr === 0 && dc === 1) return 'ne';
  if (dr === 1 && dc === 0) return 'se';
  return null;
}

// ── 자리 · 가림 ──

export interface UnitPlacement {
  /** Board px of the sprite's top-left (32 × 32 at scale 1). */
  x: number;
  y: number;
  /** Rank of the cell within a layer pass (original draw order). */
  rank: number;
}

/** The bottom piece takes the cell's layer-0 slot, the top piece its layer-1 slot (KI.EXE 0xdbcc). */
export function unitPlacement(kit: BattleKit, r: number, c: number): UnitPlacement {
  const origin = pieceOrigin(kit.layout, r, c, 0);
  return { x: origin.x, y: origin.y - kit.layout.step, rank: drawRank(kit.layout.side)[r * kit.layout.side + c] };
}

/**
 * Clears the sprite pixels that terrain drawn later in the original order covers: a pixel stays when the terrain
 * piece on top there is on a lower layer, or on the same layer at a cell drawn no later than the unit's own cell.
 * Works in place on a 32 × 32 RGBA sprite; returns it.
 */
export function occludeUnit(board: ComposedBoard, sprite: Uint8ClampedArray, placement: UnitPlacement, side: number): Uint8ClampedArray {
  const rank = drawRank(side);
  for (let y = 0; y < UNIT_SIZE; y += 1) {
    const by = placement.y + y;
    if (by < 0 || by >= board.height) continue;
    const unitLayer = y < UNIT_PIECE_HEIGHT ? 1 : 0;
    for (let x = 0; x < UNIT_SIZE; x += 1) {
      const bx = placement.x + x;
      if (bx < 0 || bx >= board.width) continue;
      const i = (y * UNIT_SIZE + x) * 4;
      if (sprite[i + 3] === 0) continue;
      const cell = board.cellOf[by * board.width + bx];
      if (cell < 0) continue;
      const layer = board.layerOf[by * board.width + bx];
      const covered = layer > unitLayer || (layer === unitLayer && rank[cell] > placement.rank);
      if (covered) sprite[i + 3] = 0;
    }
  }
  return sprite;
}

// ── 머리 위 작은 깃발(설계 B안: 장대 + 제비꼬리 천 + 장수 첫 글자) ──

/** Flag box relative to the sprite's top-left, in board px (the design's 30 × 21 at 2×). */
export const UNIT_FLAG = { x: 9, y: -3, width: 15, height: 10.5 } as const;
/** Minimum touch target (K3 rule). */
export const UNIT_MIN_HIT_PX = 44;

export interface ScreenRect { x: number; y: number; width: number; height: number }

/** Screen rectangles of a unit's sprite and flag, and its touch area (their union grown to 44 px around its centre). */
export function unitScreenRects(placement: UnitPlacement, view: BoardView): { sprite: ScreenRect; flag: ScreenRect; hit: ScreenRect } {
  const sx = placement.x * view.scale + view.offsetX;
  const sy = placement.y * view.scale + view.offsetY;
  const sprite = { x: sx, y: sy, width: UNIT_SIZE * view.scale, height: UNIT_SIZE * view.scale };
  const flag = { x: sx + UNIT_FLAG.x * view.scale, y: sy + UNIT_FLAG.y * view.scale, width: UNIT_FLAG.width * view.scale, height: UNIT_FLAG.height * view.scale };
  const x0 = Math.min(sprite.x, flag.x);
  const y0 = Math.min(sprite.y, flag.y);
  const x1 = Math.max(sprite.x + sprite.width, flag.x + flag.width);
  const y1 = Math.max(sprite.y + sprite.height, flag.y + flag.height);
  const width = Math.max(UNIT_MIN_HIT_PX, x1 - x0);
  const height = Math.max(UNIT_MIN_HIT_PX, y1 - y0);
  return { sprite, flag, hit: { x: (x0 + x1 - width) / 2, y: (y0 + y1 - height) / 2, width, height } };
}
