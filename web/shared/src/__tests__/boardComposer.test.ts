import { describe, expect, it } from 'vitest';
import { cellPolygon, cellToScreen, composeBoard, drawOrder, fitView, parseBattleKit, pickCell, screenToCell, type KitJson } from '../battle/battleBoard';
import { boardTerrain, cellVariant, edgesWhere, groundBoard, planBoard } from '../battle/boardComposer';
import { EDGE_NE, EDGE_NW, EDGE_SE, EDGE_SW, type ArtpackFrame, type ArtpackManifest } from '../battle/artpack/manifest';
import { proceduralArtpack } from '../battle/artpack/procedural';

// 합성 키트(battleBoard 시험과 같은 모양): 4×4칸 판 둘. 기록 1 = 평지(P), 2 = 성벽(W, 층 1 조각), 3 = 강(R).
const SIDE = 4;
const LAYERS = 2;
const STEP = 16;
const TOP = LAYERS * STEP + 8;
const W = 2 * SIDE * 16 + 32;
const H = 2 * SIDE * 8 + TOP + 16;

function kit(recordClass: string[] = ['PPWR']) {
  const pieces = new Uint8Array(4 * 16 * 32).fill(255);
  pieces.fill(1, 1 * 512, 2 * 512);
  pieces.fill(3, 3 * 512, 4 * 512);
  const records = new Uint8Array(4 * 8);
  records.set([1, 1, 0, 0, 0, 0, 0, 0], 1 * 8);
  records.set([2, 1, 3, 0, 0, 0, 0, 0], 2 * 8);
  records.set([1, 1, 0, 0, 0, 0, 0, 0], 3 * 8);
  const boards = new Uint8Array(2 * SIDE * SIDE).fill(1);
  boards[1 * SIDE + 2] = 2; // 판 0 (1,2) 성벽
  boards[SIDE * SIDE + 2 * SIDE + 1] = 3; // 판 1 (2,1) 강
  const json: KitJson = {
    schemaVersion: 1,
    artifactId: 'waryong-battle-kit',
    layout: { boards: 2, side: SIDE, layers: LAYERS, tilesets: 1, piecesPerTileset: 4, piece: [32, 16], step: STEP, top: TOP, canvas: [W, H], empty: 255 },
    palette: { rgb: Array.from({ length: 16 }, (_, i) => [i * 10, i * 10, i * 10] as [number, number, number]) },
    boards: [
      { id: 0, tileset: 0, layoutSha256: '', composedSha256: '' },
      { id: 1, tileset: 0, layoutSha256: '', composedSha256: '' },
    ],
    recordClass,
  };
  return parseBattleKit(json, { pieces: pieces.buffer, records: records.buffer, boards: boards.buffer });
}

const frame = (x: number, w = 64, h = 32, ax = 32, ay = 16): ArtpackFrame => ({ atlas: 0, x, y: 0, w, h, ax, ay });

/** 배율 2 팩: 바닥 하나씩, W 에 서 있는 그림(64 × 56, 발 (32, 40)), R 전이 16칸. */
function manifest(): ArtpackManifest {
  const transitions: (ArtpackFrame | null)[] = Array.from({ length: 16 }, (_, i) => (i ? frame(1000 + i) : null));
  return {
    schemaVersion: 1,
    artifactId: 'opensamguk-battle-artpack',
    packId: 'composer-test',
    pixelsPerBoardPx: 2,
    shadow: 'baked',
    atlases: [{ file: 'a.png', width: 2048, height: 64 }],
    terrain: {
      P: { ground: [frame(0), frame(64)] },
      F: { ground: [frame(128)] },
      M: { ground: [frame(192)] },
      R: { ground: [frame(256)] },
      W: { ground: [frame(320)], tall: [frame(384, 64, 56, 32, 40)] },
    },
    transitions: { R: transitions },
    units: {},
    provenance: [{ source: 'hand', note: 'test' }],
  };
}

describe('판 지형 글자', () => {
  it('칸 기록 → 지형 글자(덮어쓰기 반영)', () => {
    const t = boardTerrain(kit(), 0);
    expect(t.letters[1 * SIDE + 2]).toBe('W');
    expect(t.letters.filter((l) => l === 'P')).toHaveLength(SIDE * SIDE - 1);
    const changed = boardTerrain(kit(), 0, new Map([['0,0', 3]]));
    expect(changed.letters[0]).toBe('R');
    expect(changed.grid[0]).toBe(3);
  });

  it('recordClass 가 없거나 모르는 글자면 조립하지 않는다(원작 키트 그림으로 돌아감)', () => {
    expect(() => boardTerrain(kit([]), 0)).toThrow(/recordClass/);
    expect(() => boardTerrain(kit(['PPXR']), 0)).toThrow(/terrain 'X'/);
  });
});

describe('판 조립 계획', () => {
  it('바닥 전부 → 전이 → 서 있는 그림, 바닥은 뒤에서 앞 순서', () => {
    const k = kit();
    const items = planBoard(k, 1, boardTerrain(k, 1), manifest());
    const kinds = items.map((i) => i.kind);
    expect(kinds.filter((x) => x === 'ground')).toHaveLength(SIDE * SIDE);
    expect(kinds.lastIndexOf('ground')).toBeLessThan(kinds.indexOf('transition'));
    expect(items.filter((i) => i.kind === 'ground').map((i) => i.cell)).toEqual(Array.from(drawOrder(SIDE)));
  });

  it('발 기준점이 칸 발(층 0 마름모 가운데)에 놓인다 — 투영식은 원작 그대로', () => {
    const k = kit();
    const board = composeBoard(k, 0);
    const view = { scale: 1, offsetX: 0, offsetY: 0 };
    const items = planBoard(k, 0, boardTerrain(k, 0), manifest());
    for (const item of items) {
      const r = (item.cell / SIDE) | 0;
      const foot = cellToScreen(k, board, r, item.cell % SIDE, view);
      expect(item.x + item.frame.ax / 2).toBe(foot.x);
      expect(item.y + item.frame.ay / 2).toBe(foot.y);
      expect([item.w, item.h]).toEqual([item.frame.w / 2, item.frame.h / 2]);
    }
    const wall = items.find((i) => i.kind === 'tall')!;
    expect(wall.cell).toBe(1 * SIDE + 2);
    expect([wall.w, wall.h]).toEqual([32, 28]);
  });

  it('강 옆 칸에 그 변 쪽 전이(EDGE 비트)', () => {
    const k = kit();
    const items = planBoard(k, 1, boardTerrain(k, 1), manifest());
    const river = 2 * SIDE + 1;
    const edges = new Map(items.filter((i) => i.kind === 'transition').map((i) => [i.cell, i.frame.x - 1000]));
    expect(edges.get(river - 1)).toBe(EDGE_NE); // (2,0): 강이 c + 1
    expect(edges.get(river - SIDE)).toBe(EDGE_SE); // (1,1): 강이 r + 1
    expect(edges.get(river + 1)).toBe(EDGE_SW); // (2,2): 강이 c − 1
    expect(edges.get(river + SIDE)).toBe(EDGE_NW); // (3,1): 강이 r − 1
    expect(edges.has(river)).toBe(false);
    expect(edges.size).toBe(4);
  });

  it('같은 글자 이웃으로 서 있는 그림 고르기(tallByEdges), 없으면 tall', () => {
    const k = kit();
    const m = manifest();
    m.terrain.W.tallByEdges = Array.from({ length: 16 }, (_, i) => (i === EDGE_SE ? frame(1500, 64, 56, 32, 40) : null));
    const walls = new Map([['2,2', 2]]); // (1,2) 아래(r + 1)에 성벽 하나 더
    const items = planBoard(k, 0, boardTerrain(k, 0, walls), m).filter((i) => i.kind === 'tall');
    expect(items.find((i) => i.cell === 1 * SIDE + 2)!.frame.x).toBe(1500);
    expect(items.find((i) => i.cell === 2 * SIDE + 2)!.frame.x).toBe(384);
  });

  it('변형은 판 · 칸마다 고정', () => {
    expect(cellVariant(5, 77, 4)).toBe(cellVariant(5, 77, 4));
    const spread = new Set(Array.from({ length: 64 }, (_, i) => cellVariant(0, i, 4)));
    expect(spread.size).toBe(4);
    expect(edgesWhere(['P', 'P', 'P', 'P'], 2, 0, (l) => l === 'P')).toBe(EDGE_NE | EDGE_SE);
  });

  it('임시 절차 타일 팩으로도 모든 칸이 바닥을 받는다', () => {
    const k = kit();
    const items = planBoard(k, 0, boardTerrain(k, 0), proceduralArtpack().manifest);
    expect(items.filter((i) => i.kind === 'ground')).toHaveLength(SIDE * SIDE);
    expect(items.filter((i) => i.kind === 'tall').map((i) => i.cell)).toEqual([1 * SIDE + 2]);
  });
});

describe('v2 판의 칸 대응(data-cells · 누르기)', () => {
  it('판 크기 · 칸 마름모 화면 좌표가 원작 조립 판과 같다', () => {
    const k = kit();
    const v1 = composeBoard(k, 0);
    const v2 = groundBoard(k, 0, boardTerrain(k, 0).grid);
    expect([v2.width, v2.height]).toEqual([v1.width, v1.height]);
    const viewport = { width: 390, height: 640 };
    const view1 = fitView(v1, viewport);
    const view2 = fitView(v2, viewport);
    expect(view2).toEqual(view1);
    for (let r = 0; r < SIDE; r += 1) {
      for (let c = 0; c < SIDE; c += 1) expect(cellPolygon(k, v2, r, c, view2)).toEqual(cellPolygon(k, v1, r, c, view1));
    }
  });

  it('칸 가운데를 누르면 그 칸(바닥 마름모)', () => {
    const k = kit();
    const v2 = groundBoard(k, 0, boardTerrain(k, 0).grid);
    const view = { scale: 2, offsetX: 7, offsetY: -3 };
    for (let r = 0; r < SIDE; r += 1) {
      for (let c = 0; c < SIDE; c += 1) {
        const p = cellToScreen(k, v2, r, c, view);
        expect(pickCell(k, v2, p.x, p.y, view)).toEqual({ r, c });
        expect(screenToCell(k, p.x, p.y, view)).toEqual({ r, c });
      }
    }
    expect(pickCell(k, v2, 0, 0, { scale: 1, offsetX: 0, offsetY: 0 })).toBeNull();
  });
});
