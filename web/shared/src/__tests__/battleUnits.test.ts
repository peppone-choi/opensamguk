import { describe, expect, it } from 'vitest';
import { composeBoard, drawOrder, parseBattleKit, type KitJson } from '../battle/battleBoard';
import {
  facingOf,
  occludeUnit,
  UNIT_FALLEN,
  unitFrame,
  parseUnitKit,
  UNIT_PIECES_PER_SIDE,
  unitPlacement,
  unitRamp,
  unitSpriteRgba,
  type Rgb,
} from '../battle/battleUnits';

const PALETTE: Rgb[] = Array.from({ length: 16 }, (_, i) => [i * 10, i * 10, i * 10]);

describe('유닛 역할 색', () => {
  // recolor.py ramp() 값(파이썬 colorsys)
  const cases: [string, Rgb, Rgb, Rgb, Rgb][] = [
    ['#4f7fbf', [79, 127, 191], [30, 53, 83], [134, 148, 168], [255, 237, 0]],
    ['#c0392b', [192, 57, 43], [81, 24, 18], [166, 121, 116], [255, 237, 0]],
    ['#f1c40f', [241, 196, 15], [102, 83, 6], [181, 167, 113], [255, 237, 0]],
    ['#ffffff', [255, 255, 255], [107, 107, 107], [217, 217, 217], [0, 0, 0]],
    ['#000000', [0, 0, 0], [15, 15, 15], [76, 76, 76], [255, 237, 0]],
  ];
  it.each(cases)('%s → 원작 도구와 같은 네 색', (hex, main, shade, light, trim) => {
    const ramp = unitRamp(hex);
    for (const [got, want] of [[ramp.main, main], [ramp.shade, shade], [ramp.light, light], [ramp.trim, trim]] as const) {
      got.forEach((v, i) => expect(Math.abs(v - want[i])).toBeLessThanOrEqual(1));
    }
  });

  it('형식이 틀린 색은 거절한다', () => {
    expect(() => unitRamp('red')).toThrow('bad colour');
  });
});

function unitKit() {
  const units = new Uint8Array(2 * UNIT_PIECES_PER_SIDE * 512).fill(255);
  const roles = new Uint8Array(UNIT_PIECES_PER_SIDE * 512);
  // 유닛 0: 위 조각 전부 색 10(역할 1), 아래 조각 전부 색 6(역할 2)
  units.fill(10, 0, 512);
  units.fill(6, 512, 1024);
  roles.fill(1, 0, 512);
  roles.fill(2, 512, 1024);
  // 파랑 편 유닛 0은 색 3 · 8
  units.fill(3, UNIT_PIECES_PER_SIDE * 512, UNIT_PIECES_PER_SIDE * 512 + 512);
  units.fill(8, UNIT_PIECES_PER_SIDE * 512 + 512, UNIT_PIECES_PER_SIDE * 512 + 1024);
  return parseUnitKit(PALETTE, { units: units.buffer, roles: roles.buffer });
}

describe('유닛 그림', () => {
  it('위 조각 2u 위에 아래 조각 2u + 1, 역할만 세력색으로', () => {
    const kit = unitKit();
    const ramp = unitRamp('#4f7fbf');
    const sprite = unitSpriteRgba(kit, 0, ramp);
    expect(Array.from(sprite.slice(0, 4))).toEqual([...ramp.main, 255]);
    expect(Array.from(sprite.slice(16 * 32 * 4, 16 * 32 * 4 + 4))).toEqual([...ramp.shade, 255]);
    const original = unitSpriteRgba(kit, 0, null);
    expect(Array.from(original.slice(0, 4))).toEqual([100, 100, 100, 255]);
    expect(Array.from(unitSpriteRgba(kit, 0, null, 'blue').slice(0, 4))).toEqual([30, 30, 30, 255]);
    expect(Array.from(unitSpriteRgba(kit, 1, null).slice(0, 4))).toEqual([0, 0, 0, 0]);
    expect(() => unitSpriteRgba(kit, 90, null)).toThrow('out of range');
  });

  it('크기가 어긋나면 거절한다', () => {
    expect(() => parseUnitKit(PALETTE, { units: new ArrayBuffer(4), roles: new ArrayBuffer(4) })).toThrow('units');
  });
});

// 5×5칸 판, 층 3. 조각 1 = 색 1 마름모. 기록 1 = 층 0 땅, 기록 2 = 층 0 · 1 · 2 벽.
const SIDE = 5;
const TOP = 40;
function diamond(): Uint8Array {
  const piece = new Uint8Array(512).fill(255);
  for (let y = 0; y < 16; y += 1) {
    for (let x = 0; x < 32; x += 1) if (Math.abs(x + 0.5 - 16) / 16 + Math.abs(y + 0.5 - 8) / 8 <= 1) piece[y * 32 + x] = 1;
  }
  return piece;
}
function battleKit() {
  const pieces = new Uint8Array(4 * 512).fill(255);
  pieces.set(diamond(), 512);
  const records = new Uint8Array(4 * 8);
  records.set([1, 1, 0, 0, 0, 0, 0, 0], 8);
  records.set([3, 1, 1, 1, 0, 0, 0, 0], 16);
  const boards = new Uint8Array(SIDE * SIDE).fill(1);
  for (const [r, c] of [[2, 1], [3, 2], [1, 3], [2, 2]]) boards[r * SIDE + c] = 2;
  const json: KitJson = {
    schemaVersion: 1,
    artifactId: 'waryong-battle-kit',
    layout: { boards: 1, side: SIDE, layers: 3, tilesets: 1, piecesPerTileset: 4, piece: [32, 16], step: 16, top: TOP, canvas: [2 * SIDE * 16 + 32, 2 * SIDE * 8 + TOP + 16], empty: 255 },
    palette: { rgb: PALETTE },
    boards: [{ id: 0, tileset: 0, layoutSha256: '', composedSha256: '' }],
  };
  return parseBattleKit(json, { pieces: pieces.buffer, records: records.buffer, boards: boards.buffer });
}

/** Reference: the original painter with the unit slotted in (bottom after its cell on layer 0, top after it on layer 1). */
function referenceVisible(kit: ReturnType<typeof battleKit>, ur: number, uc: number): Set<string> {
  const [w, h] = kit.layout.canvas;
  const owner = new Array<string>(w * h).fill('');
  const order = drawOrder(SIDE);
  const at = unitPlacement(kit, ur, uc);
  for (let layer = 0; layer < kit.layout.layers; layer += 1) {
    for (const cell of order) {
      const r = Math.floor(cell / SIDE);
      const c = cell % SIDE;
      const pid = kit.records[kit.boards[cell] * 8 + 1 + layer];
      if (pid) {
        const ox = (r + c) * 16;
        const oy = (r - c) * 8 + SIDE * 8 + TOP - layer * 16;
        for (let y = 0; y < 16; y += 1) for (let x = 0; x < 32; x += 1) if (kit.pieces[pid * 512 + y * 32 + x] !== 255) owner[(oy + y) * w + ox + x] = 'T';
      }
      if (r === ur && c === uc && layer < 2) {
        const y0 = layer === 0 ? 16 : 0;
        for (let y = 0; y < 16; y += 1) for (let x = 0; x < 32; x += 1) owner[(at.y + y0 + y) * w + at.x + x] = `U${x},${y0 + y}`;
      }
    }
  }
  return new Set(owner.filter((o) => o.startsWith('U')).map((o) => o.slice(1)));
}

describe('유닛 자리 · 가림', () => {
  it('아래 조각은 제 칸 층 0 자리, 위 조각은 한 층 위', () => {
    const kit = battleKit();
    expect(unitPlacement(kit, 1, 1)).toMatchObject({ x: 32, y: SIDE * 8 + TOP - 16 });
  });

  it('모든 칸에서 원작 순서로 끼워 그린 것과 보이는 화소가 같다', () => {
    const kit = battleKit();
    const board = composeBoard(kit, 0);
    let hiddenSomewhere = 0;
    for (let r = 0; r < SIDE; r += 1) {
      for (let c = 0; c < SIDE; c += 1) {
        const sprite = new Uint8ClampedArray(32 * 32 * 4).fill(255);
        occludeUnit(board, sprite, unitPlacement(kit, r, c), SIDE);
        const shown = new Set<string>();
        for (let y = 0; y < 32; y += 1) for (let x = 0; x < 32; x += 1) if (sprite[(y * 32 + x) * 4 + 3]) shown.add(`${x},${y}`);
        expect(shown, `${r},${c}`).toEqual(referenceVisible(kit, r, c));
        hiddenSomewhere += 1024 - shown.size;
      }
    }
    expect(hiddenSomewhere).toBeGreaterThan(0);
  });
});

describe('동작 표(원작 코드)', () => {
  it('병종 base + 2·방향 + 공격 8 + 위상, 피격은 base + 16 + 위상', () => {
    expect(unitFrame('leader', 'sw', 'idle', 0)).toBe(0);
    expect(unitFrame('leader', 'se', 'idle', 1)).toBe(7);
    expect(unitFrame('lightCavalry', 'nw', 'attack', 0)).toBe(18 + 2 + 8);
    expect(unitFrame('archer', 'ne', 'attack', 1)).toBe(36 + 4 + 8 + 1);
    expect(unitFrame('infantry', 'se', 'hit', 1)).toBe(54 + 17);
    expect(UNIT_FALLEN.archer).toEqual([86, 87]);
  });

  it('칸 한 걸음의 방향: c − 1 = SW, r − 1 = NW, c + 1 = NE, r + 1 = SE', () => {
    expect(facingOf(0, -1)).toBe('sw');
    expect(facingOf(-1, 0)).toBe('nw');
    expect(facingOf(0, 1)).toBe('ne');
    expect(facingOf(1, 0)).toBe('se');
    expect(facingOf(1, 1)).toBeNull();
  });
});
