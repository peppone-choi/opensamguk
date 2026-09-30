import { screenToCell } from './camera';
import type { Camera, ScreenPoint, Viewport } from './types';

export type SpriteKind = 'me' | 'corps' | 'flag' | 'pass' | 'city';

export interface SpriteHit {
  kind: SpriteKind;
  id: string;
  /** Screen CSS px. */
  rect: { x: number; y: number; width: number; height: number };
  /** Higher is on top. */
  z: number;
}

export interface CityFootprint { cityId: number; originCol: number; originRow: number; span: number }

// 칸 열 번호가 이 값을 넘지 않는다는 전제로 row * 65536 + col 을 열쇠로 쓴다.
const KEY_STRIDE = 65536;

function cellKey(col: number, row: number): number | undefined {
  if (!Number.isInteger(col) || !Number.isInteger(row) || col < 0 || row < 0 || col >= KEY_STRIDE) {
    return undefined;
  }
  return row * KEY_STRIDE + col;
}

/**
 * Cell → city lookup for city footprints. A footprint covers
 * [originCol, originCol + span) × [originRow, originRow + span); the first footprint listed wins an overlap.
 */
export class FootprintIndex {
  private readonly cells = new Map<number, number>();

  constructor(footprints: ReadonlyArray<CityFootprint>) {
    for (const { cityId, originCol, originRow, span } of footprints) {
      for (let row = originRow; row < originRow + span; row += 1) {
        for (let col = originCol; col < originCol + span; col += 1) {
          const key = cellKey(col, row);
          if (key !== undefined && !this.cells.has(key)) this.cells.set(key, cityId);
        }
      }
    }
  }

  cityAt(col: number, row: number): number | undefined {
    const key = cellKey(col, row);
    return key === undefined ? undefined : this.cells.get(key);
  }
}

export type HitKind = SpriteKind | 'province' | 'none';

export interface HitResult {
  kind: HitKind;
  /** Sprite id, city id, province index (plane value − 1), or null. */
  id: string | number | null;
  cell: { col: number; row: number };
}

export interface HitTestLayers {
  sprites: ReadonlyArray<SpriteHit>;
  footprints?: FootprintIndex;
  /** Province plane value at a cell, 0 = none (also for cells off the map). */
  provinceAt: (col: number, row: number) => number;
}

function contains(rect: SpriteHit['rect'], point: ScreenPoint): boolean {
  return point.x >= rect.x && point.x < rect.x + rect.width
    && point.y >= rect.y && point.y < rect.y + rect.height;
}

/** Sprites (highest z, later wins ties) → city footprint → province → none. */
export function hitTest(
  point: ScreenPoint,
  cam: Camera,
  viewport: Viewport,
  layers: HitTestLayers,
): HitResult {
  const at = screenToCell(point, cam, viewport);
  const cell = { col: Math.floor(at.col), row: Math.floor(at.row) };

  let top: SpriteHit | undefined;
  for (const sprite of layers.sprites) {
    if (contains(sprite.rect, point) && (!top || sprite.z >= top.z)) top = sprite;
  }
  if (top) return { kind: top.kind, id: top.id, cell };

  const cityId = layers.footprints?.cityAt(cell.col, cell.row);
  if (cityId !== undefined) return { kind: 'city', id: cityId, cell };

  const plane = layers.provinceAt(cell.col, cell.row);
  if (plane > 0) return { kind: 'province', id: plane - 1, cell };

  return { kind: 'none', id: null, cell };
}
