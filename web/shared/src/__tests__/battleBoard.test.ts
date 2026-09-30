import { describe, expect, it } from 'vitest';
import {
  cellPolygon,
  cellToScreen,
  composeBoard,
  drawOrder,
  fitView,
  parseBattleKit,
  pickCell,
  redrawCells,
  screenToCell,
  type KitJson,
} from '../battle/battleBoard';

// 합성 키트: 4×4칸 판 둘, 층 2, 조각 32×16. 조각 1 = 색 1 마름모, 2 = 색 2 꽉 찬 네모, 3 = 색 3 마름모(층 1용)
const SIDE = 4;
const LAYERS = 2;
const STEP = 16;
const TOP = LAYERS * STEP + 8;
const W = 2 * SIDE * 16 + 32;
const H = 2 * SIDE * 8 + TOP + 16;

function diamond(value: number): Uint8Array {
  const piece = new Uint8Array(16 * 32).fill(255);
  for (let y = 0; y < 16; y += 1) {
    for (let x = 0; x < 32; x += 1) {
      if (Math.abs(x + 0.5 - 16) / 16 + Math.abs(y + 0.5 - 8) / 8 <= 1) piece[y * 32 + x] = value;
    }
  }
  return piece;
}

function kit() {
  const pieces = new Uint8Array(1 * 4 * 16 * 32).fill(255);
  pieces.set(diamond(1), 1 * 512);
  pieces.set(new Uint8Array(512).fill(2), 2 * 512);
  pieces.set(diamond(3), 3 * 512);
  const records = new Uint8Array(1 * 4 * 8);
  records.set([1, 1, 0, 0, 0, 0, 0, 0], 1 * 8); // 기록 1: 땅
  records.set([2, 1, 3, 0, 0, 0, 0, 0], 2 * 8); // 기록 2: 땅 + 층 1 벽
  records.set([1, 2, 0, 0, 0, 0, 0, 0], 3 * 8); // 기록 3: 꽉 찬 네모
  const boards = new Uint8Array(2 * SIDE * SIDE).fill(1);
  boards[1 * SIDE + 2] = 2; // 판 0 (1,2)에 벽
  const json: KitJson = {
    schemaVersion: 1,
    artifactId: 'waryong-battle-kit',
    layout: { boards: 2, side: SIDE, layers: LAYERS, tilesets: 1, piecesPerTileset: 4, piece: [32, 16], step: STEP, top: TOP, canvas: [W, H], empty: 255 },
    palette: { rgb: Array.from({ length: 16 }, (_, i) => [i * 10, i * 10, i * 10] as [number, number, number]) },
    boards: [
      { id: 0, tileset: 0, layoutSha256: '', composedSha256: '' },
      { id: 1, tileset: 0, layoutSha256: '', composedSha256: '' },
    ],
  };
  return parseBattleKit(json, { pieces: pieces.buffer, records: records.buffer, boards: boards.buffer });
}

describe('전장 판 조립', () => {
  it('뒤(r − c 작음)에서 앞으로, 같으면 r + c 순서', () => {
    const order = Array.from(drawOrder(SIDE));
    expect(order[0]).toBe(0 * SIDE + 3); // (0,3): r − c = −3
    expect(order.at(-1)).toBe(3 * SIDE + 0); // (3,0): r − c = 3
    expect(new Set(order).size).toBe(SIDE * SIDE);
  });

  it('층 0을 다 그린 뒤 층 1을 그리고, 화소마다 그린 칸을 남긴다', () => {
    const k = kit();
    const board = composeBoard(k, 0);
    expect([board.width, board.height]).toEqual([W, H]);
    // (1,2) 벽: 층 1 마름모 가운데 화소는 색 3, 칸은 (1,2)
    const wall = { x: (1 + 2) * 16 + 16, y: (1 - 2) * 8 + SIDE * 8 + TOP - STEP + 8 };
    expect(board.indices[wall.y * W + wall.x]).toBe(3);
    expect(board.cellOf[wall.y * W + wall.x]).toBe(1 * SIDE + 2);
    // 빈 곳
    expect(board.indices[0]).toBe(255);
    expect(board.cellOf[0]).toBe(-1);
  });

  it('바꿔 끼운 기록으로 그리고, 바뀐 칸만 다시 칠해도 통째 조립과 같다', () => {
    const k = kit();
    const changes = new Map([['2,1', 3], ['1,2', 1]]);
    const full = composeBoard(k, 0, changes);
    const partial = composeBoard(k, 0);
    const rect = redrawCells(k, partial, changes)!;
    expect(rect.width).toBeGreaterThan(0);
    expect(Array.from(partial.indices)).toEqual(Array.from(full.indices));
    expect(Array.from(partial.cellOf)).toEqual(Array.from(full.cellOf));
    expect(full.indices[((2 - 1) * 8 + SIDE * 8 + TOP + 8) * W + (2 + 1) * 16 + 16]).toBe(2);
  });

  it('칸 → 화면 → 칸이 돌아오고, 누른 화소는 그린 칸을 고른다', () => {
    const k = kit();
    const board = composeBoard(k, 0);
    const view = fitView(board, { width: 390, height: 300 });
    for (let r = 0; r < SIDE; r += 1) {
      for (let c = 0; c < SIDE; c += 1) {
        const foot = cellToScreen(k, board, r, c, view);
        expect(screenToCell(k, foot.x, foot.y, view)).toEqual({ r, c });
        expect(pickCell(k, board, foot.x, foot.y, view)).not.toBeNull();
      }
    }
    const wallTop = cellToScreen(k, board, 1, 2, view, 'top');
    expect(pickCell(k, board, wallTop.x, wallTop.y, view)).toEqual({ r: 1, c: 2 });
    expect(wallTop.y).toBeLessThan(cellToScreen(k, board, 1, 2, view).y);
    expect(screenToCell(k, -100, -100, view)).toBeNull();
    const poly = cellPolygon(k, board, 0, 0, view);
    expect(poly).toHaveLength(4);
    expect(poly[1].x - poly[3].x).toBeCloseTo(32 * view.scale, 9);
  });

  it('형식이 어긋나면 거절한다', () => {
    const json = { schemaVersion: 1, artifactId: 'waryong-battle-kit', layout: { boards: 1, side: 2, layers: 1, tilesets: 1, piecesPerTileset: 1, piece: [32, 16], step: 16, top: 24, canvas: [96, 72], empty: 255 }, palette: { rgb: [] }, boards: [] } as unknown as KitJson;
    expect(() => parseBattleKit(json, { pieces: new ArrayBuffer(3), records: new ArrayBuffer(8), boards: new ArrayBuffer(4) })).toThrow('pieces');
  });
});
