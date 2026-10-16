// 전술 판 그림 v2 조립 계획 — 칸 지형 글자(P/F/M/R/W, 키트 recordClass = 서버 카탈로그) → 아트 팩 바닥 · 전이 · 서 있는 그림.
// 투영은 원작 키트 그대로(pieceOrigin 층 0)라 칸 ↔ 화면 대응 · 판 크기가 v1 과 같다. 그리는 순서:
// 바닥 전부 → 전이 전부 → 서 있는 그림(나무 · 산 · 성벽)을 (r − c, r + c) 작은 칸(뒤)부터. 순수 TS라 노드에서 시험한다.
import { drawOrder, pieceOrigin, type BattleKit, type ComposedBoard } from './battleBoard';
import {
  EDGE_NE,
  EDGE_NW,
  EDGE_SE,
  EDGE_SW,
  TERRAIN_LETTERS,
  TRANSITION_LETTERS,
  type ArtpackFrame,
  type ArtpackManifest,
  type TerrainLetter,
} from './artpack/manifest';

export interface BoardTerrain {
  /** Record id per cell after overrides (row-major). */
  grid: Uint8Array;
  /** Terrain letter per cell (row-major). */
  letters: TerrainLetter[];
}

/** The board's terrain letters (after record overrides 'r,c' → record). Throws when the kit has no recordClass. */
export function boardTerrain(kit: BattleKit, boardId: number, overrides?: ReadonlyMap<string, number>): BoardTerrain {
  const info = kit.boardInfo[boardId];
  if (!info) throw new Error(`battle board ${boardId} is not in the kit`);
  const classes = kit.recordClass?.[info.tileset];
  if (!classes) throw new Error('battle kit has no recordClass');
  const { side } = kit.layout;
  const grid = kit.boards.slice(boardId * side * side, (boardId + 1) * side * side);
  overrides?.forEach((record, key) => {
    const [r, c] = key.split(',').map(Number);
    if (r >= 0 && r < side && c >= 0 && c < side) grid[r * side + c] = record;
  });
  const letters = Array.from(grid, (record, i) => {
    const letter = classes[record] as TerrainLetter;
    if (!(TERRAIN_LETTERS as readonly string[]).includes(letter)) throw new Error(`battle board ${boardId}: cell ${i} has terrain '${letter}'`);
    return letter;
  });
  return { grid, letters };
}

export interface BoardDrawItem {
  kind: 'ground' | 'transition' | 'tall';
  /** r · side + c. */
  cell: number;
  frame: ArtpackFrame;
  /** Destination in board px (frame scaled by 1 / pixelsPerBoardPx, foot anchor on the cell foot). */
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Variant pick per cell — stable for a board, varied between neighbours. */
export function cellVariant(boardId: number, cell: number, count: number): number {
  let h = Math.imul(boardId + 1, 0x9e3779b1) ^ Math.imul(cell + 1, 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 15), 0x2c1b3c6d);
  return ((h ^ (h >>> 12)) >>> 0) % count;
}

/** EDGE_* bits of the neighbours of `cell` whose letter passes `test`. */
export function edgesWhere(letters: readonly TerrainLetter[], side: number, cell: number, test: (letter: TerrainLetter) => boolean): number {
  const r = (cell / side) | 0;
  const c = cell - r * side;
  let bits = 0;
  if (c + 1 < side && test(letters[cell + 1])) bits |= EDGE_NE;
  if (r + 1 < side && test(letters[cell + side])) bits |= EDGE_SE;
  if (c > 0 && test(letters[cell - 1])) bits |= EDGE_SW;
  if (r > 0 && test(letters[cell - side])) bits |= EDGE_NW;
  return bits;
}

/** Back-to-front draw list for a board. */
export function planBoard(kit: BattleKit, boardId: number, terrain: BoardTerrain, manifest: ArtpackManifest): BoardDrawItem[] {
  const { side } = kit.layout;
  const ppb = manifest.pixelsPerBoardPx;
  const order = drawOrder(side);
  const place = (kind: BoardDrawItem['kind'], cell: number, frame: ArtpackFrame): BoardDrawItem => {
    const r = (cell / side) | 0;
    const o = pieceOrigin(kit.layout, r, cell - r * side, 0);
    return { kind, cell, frame, x: o.x + 16 - frame.ax / ppb, y: o.y + 8 - frame.ay / ppb, w: frame.w / ppb, h: frame.h / ppb };
  };
  const ground: BoardDrawItem[] = [];
  const edges: BoardDrawItem[] = [];
  const tall: BoardDrawItem[] = [];
  for (let i = 0; i < order.length; i += 1) {
    const cell = order[i];
    const letter = terrain.letters[cell];
    const def = manifest.terrain[letter];
    ground.push(place('ground', cell, def.ground[cellVariant(boardId, cell, def.ground.length)]));
    for (const other of TRANSITION_LETTERS) {
      if (other === letter) continue;
      const bits = edgesWhere(terrain.letters, side, cell, (l) => l === other);
      const frame = bits ? manifest.transitions?.[other]?.[bits] : null;
      if (frame) edges.push(place('transition', cell, frame));
    }
    const joined = def.tallByEdges?.[edgesWhere(terrain.letters, side, cell, (l) => l === letter)];
    const frame = joined ?? (def.tall?.length ? def.tall[cellVariant(boardId, cell, def.tall.length)] : null);
    if (frame) tall.push(place('tall', cell, frame));
  }
  return [...ground, ...edges, ...tall];
}

/**
 * A ComposedBoard for taps on the v2 picture: same size as the kit canvas, `cellOf` = the ground diamond of each
 * cell (front cells win on shared edges), no palette indices (all `empty`). Standing pieces do not take taps.
 */
export function groundBoard(kit: BattleKit, boardId: number, grid: Uint8Array): ComposedBoard {
  const { side, empty } = kit.layout;
  const [width, height] = kit.layout.canvas;
  const cellOf = new Int16Array(width * height).fill(-1);
  const order = drawOrder(side);
  for (let i = 0; i < order.length; i += 1) {
    const cell = order[i];
    const r = (cell / side) | 0;
    const o = pieceOrigin(kit.layout, r, cell - r * side, 0);
    for (let py = 0; py < 16; py += 1) {
      const y = o.y + py;
      if (y < 0 || y >= height) continue;
      for (let px = 0; px < 32; px += 1) {
        const x = o.x + px;
        if (x < 0 || x >= width || Math.abs(px + 0.5 - 16) / 16 + Math.abs(py + 0.5 - 8) / 8 > 1) continue;
        cellOf[y * width + x] = cell;
      }
    }
  }
  return { boardId, width, height, indices: new Uint8Array(width * height).fill(empty), cellOf, layerOf: new Uint8Array(width * height), grid };
}
