import { describe, expect, it } from 'vitest';
import {
  HEIGHT_STEP_WORLD,
  RASTER_GROUP,
  TERRAIN,
  buildCornerLattice,
  buildIsoTileGrid,
  buildTileHeights,
  downsampleTerrain,
  fillSeaEnclosedGaps,
  levelsFromImageData,
  relaxCornerLattice,
  pickTileAtScreen,
  terrainFromElevation,
  tileToScreen,
} from '../isoTileGrid';

/** 지형 코드 숫자 배열을 API 가 주는 문자열 줄로 만든다. */
function lines(grid: number[][]): string[] {
  return grid.map((row) => row.join(''));
}

/** cols×rows 를 한 코드로 채운 원본 격자. */
function filled(cols: number, rows: number, code: number): number[][] {
  return Array.from({ length: rows }, () => Array.from({ length: cols }, () => code));
}

describe('downsampleTerrain', () => {
  it('768×669 를 192×167 로 줄인다 — 남는 행은 버린다', () => {
    const tiles = downsampleTerrain(lines(filled(768, 669, TERRAIN.PLAIN)));
    expect(tiles.cols).toBe(192);
    expect(tiles.rows).toBe(167);
    expect(tiles.code).toHaveLength(192 * 167);
  });

  it('강은 블록에 한 셀만 있어도 살린다', () => {
    const grid = filled(RASTER_GROUP, RASTER_GROUP, TERRAIN.PLAIN);
    grid[2][1] = TERRAIN.RIVER;
    expect(downsampleTerrain(lines(grid)).code[0]).toBe(TERRAIN.RIVER);
  });

  it('호수·구릉은 한 셀이면 잡티로 버리고 두 셀부터 살린다', () => {
    const one = filled(RASTER_GROUP, RASTER_GROUP, TERRAIN.PLAIN);
    one[0][0] = TERRAIN.LAKE;
    expect(downsampleTerrain(lines(one)).code[0]).toBe(TERRAIN.PLAIN);

    const two = filled(RASTER_GROUP, RASTER_GROUP, TERRAIN.PLAIN);
    two[0][0] = TERRAIN.LAKE;
    two[0][1] = TERRAIN.LAKE;
    expect(downsampleTerrain(lines(two)).code[0]).toBe(TERRAIN.LAKE);
  });

  it('해안이 8:8 로 갈리면 육지가 이긴다', () => {
    const grid = filled(RASTER_GROUP, RASTER_GROUP, TERRAIN.SEA);
    for (let r = 0; r < 2; r += 1) for (let c = 0; c < RASTER_GROUP; c += 1) grid[r][c] = TERRAIN.PLAIN;
    expect(downsampleTerrain(lines(grid)).code[0]).toBe(TERRAIN.PLAIN);
  });

  it('전부 범위밖일 때만 범위밖이다', () => {
    expect(downsampleTerrain(lines(filled(4, 4, TERRAIN.OUT_OF_SCOPE))).code[0])
      .toBe(TERRAIN.OUT_OF_SCOPE);

    const mixed = filled(RASTER_GROUP, RASTER_GROUP, TERRAIN.OUT_OF_SCOPE);
    mixed[3][3] = TERRAIN.DESERT;
    expect(downsampleTerrain(lines(mixed)).code[0]).toBe(TERRAIN.DESERT);
  });
});

describe('fillSeaEnclosedGaps', () => {
  const O = TERRAIN.OUT_OF_SCOPE;
  const S = TERRAIN.SEA;
  const P = TERRAIN.PLAIN;

  it('사방이 바다면 바다로 메운다', () => {
    const code = Uint8Array.from([S, S, S, S, O, S, S, S, S]);
    fillSeaEnclosedGaps(code, 3, 3);
    expect([...code]).toEqual([S, S, S, S, S, S, S, S, S]);
  });

  it('뭍에 한 변이라도 닿으면 그대로 둔다 — 초원을 바다로 바꾸지 않는다', () => {
    const code = Uint8Array.from([S, P, S, S, O, S, S, S, S]);
    fillSeaEnclosedGaps(code, 3, 3);
    expect(code[4]).toBe(O);
  });

  it('덩어리 전체를 함께 판정한다', () => {
    // 가운데 세로 두 칸이 한 덩어리다. 위쪽이 뭍에 닿으므로 둘 다 남는다.
    const code = Uint8Array.from([S, P, S, S, O, S, S, O, S, S, S, S]);
    fillSeaEnclosedGaps(code, 3, 4);
    expect(code[4]).toBe(O);
    expect(code[7]).toBe(O);
  });
});

describe('terrainFromElevation', () => {
  const O = TERRAIN.OUT_OF_SCOPE;

  it('지도 밖 타일에 표고 등급대로 지형을 준다', () => {
    const code = Uint8Array.from([O, O, O, O, O, O, O]);
    const level = Uint8Array.from([0, 1, 2, 3, 4, 5, 6]);
    terrainFromElevation(code, level);
    expect([...code]).toEqual([
      TERRAIN.SEA, TERRAIN.PLAIN, TERRAIN.HILL, TERRAIN.PLATEAU,
      TERRAIN.PLATEAU, TERRAIN.MOUNTAIN, TERRAIN.MOUNTAIN,
    ]);
  });

  it('지도 안 타일은 표고가 뭐든 건드리지 않는다', () => {
    const code = Uint8Array.from([TERRAIN.PLAIN, TERRAIN.SEA]);
    terrainFromElevation(code, Uint8Array.from([6, 6]));
    expect([...code]).toEqual([TERRAIN.PLAIN, TERRAIN.SEA]);
  });
});

describe('buildIsoTileGrid — 지도 밖 채우기', () => {
  const O = TERRAIN.OUT_OF_SCOPE;
  const P = TERRAIN.PLAIN;

  /** 왼쪽 절반이 평지, 오른쪽 절반이 지도 밖인 8×8 원본. 타일로는 2×2 가 된다. */
  function source(): string[] {
    return Array.from({ length: 8 }, () => [P, P, P, P, O, O, O, O].join(''));
  }

  /** 2×2 타일 전부 레벨 5(=산) 로 놓은 DEM 픽셀. */
  function dem(level: number): Uint8Array {
    const rgba = new Uint8Array(4 * 4);
    for (let i = 0; i < 4; i += 1) rgba[i * 4] = level;
    return rgba;
  }

  it('지도 밖에 표고 지형이 들어차고 OUT_OF_SCOPE 가 남지 않는다', () => {
    const grid = buildIsoTileGrid(source(), dem(5), 2, 2, 4);
    expect([...grid.code]).not.toContain(TERRAIN.OUT_OF_SCOPE);
    expect(grid.code[1]).toBe(TERRAIN.MOUNTAIN);
    expect(grid.code[3]).toBe(TERRAIN.MOUNTAIN);
  });

  it('playable 이 지도 안팎을 그대로 기억한다', () => {
    const grid = buildIsoTileGrid(source(), dem(5), 2, 2, 4);
    expect([...grid.playable]).toEqual([1, 0, 1, 0]);
  });

  it('지도 밖도 DEM 높이를 그대로 받는다 — 0 으로 눌리지 않는다', () => {
    const grid = buildIsoTileGrid(source(), dem(5), 2, 2, 4);
    expect(grid.level[1]).toBe(5);
  });
});

describe('levelsFromImageData', () => {
  it('바다와 범위밖은 0 으로 누른다', () => {
    const terrain = { cols: 2, rows: 1, code: Uint8Array.from([TERRAIN.SEA, TERRAIN.MOUNTAIN]) };
    const rgba = Uint8Array.from([4, 4, 4, 255, 5, 5, 5, 255]);
    expect(Array.from(levelsFromImageData(rgba, 2, 1, terrain))).toEqual([0, 5]);
  });

  it('격자가 어긋나면 던진다', () => {
    const terrain = { cols: 3, rows: 1, code: new Uint8Array(3) };
    expect(() => levelsFromImageData(new Uint8Array(8), 2, 1, terrain)).toThrow(/격자 불일치/);
  });
});

describe('buildCornerLattice / buildTileHeights', () => {
  it('이웃 타일이 같은 코너 값을 본다', () => {
    // 2×1: 왼쪽 0, 오른쪽 2. 가운데 코너는 두 타일이 공유한다.
    const corner = buildCornerLattice(Uint8Array.from([0, 2]), 2, 1);
    const h = buildTileHeights(corner, 2, 1);
    // 왼쪽 타일의 E·S 는 오른쪽 타일의 N·W 와 같은 격자점이다.
    expect(h.cornerNESW[1]).toBe(h.cornerNESW[4]); // 왼쪽 E == 오른쪽 N
    expect(h.cornerNESW[2]).toBe(h.cornerNESW[7]); // 왼쪽 S == 오른쪽 W
    expect(corner[1]).toBe(1); // (0+2)/2 = 1
  });

  it('평지는 마스크 0, 한 칸 경사는 해당 비트만 세운다', () => {
    const flat = buildTileHeights(buildCornerLattice(new Uint8Array(9).fill(3), 3, 3), 3, 3);
    expect(flat.mask[4]).toBe(0);
    expect(flat.baseHeight[4]).toBe(3);
    expect(flat.cliff[4]).toBe(0);

    // N 코너만 한 칸 높은 타일을 직접 만든다 (cols=1, rows=1 → 코너 2×2).
    const one = buildTileHeights(Uint8Array.from([1, 0, 0, 0]), 1, 1);
    expect(one.baseHeight[0]).toBe(0);
    expect(one.mask[0]).toBe(1);
  });

  it('마스크 15 는 나올 수 없다 — baseHeight 가 코너 최소값이다', () => {
    const corner = Uint8Array.from([2, 5, 0, 6, 1, 3, 4, 4, 2]);
    const h = buildTileHeights(corner, 2, 2);
    for (const m of h.mask) expect(m).toBeLessThan(15);
  });

  it('낙차가 2 이상이면 절벽으로 표시한다 — 눌러 주지 않은 격자의 모습이다', () => {
    const h = buildTileHeights(Uint8Array.from([0, 0, 0, 3]), 1, 1);
    expect(h.cliff[0]).toBe(1);
    expect(h.baseHeight[0]).toBe(0);
  });

  it('relaxCornerLattice 가 타일 안 낙차를 1 단으로 누른다', () => {
    const corner = relaxCornerLattice(Uint8Array.from([0, 0, 0, 3]), 1, 1);
    const h = buildTileHeights(corner, 1, 1);
    expect(h.cliff[0]).toBe(0);
    expect(Math.max(...corner) - Math.min(...corner)).toBeLessThanOrEqual(1);
  });

  it('buildCornerLattice 를 거치면 절벽이 하나도 남지 않는다', () => {
    // 레벨이 한 칸 건너 0↔6 으로 튀는 최악의 입력.
    const cols = 9;
    const rows = 9;
    const level = new Uint8Array(cols * rows);
    for (let r = 0; r < rows; r += 1) {
      for (let c = 0; c < cols; c += 1) level[r * cols + c] = (c + r) % 2 === 0 ? 0 : 6;
    }
    const h = buildTileHeights(buildCornerLattice(level, cols, rows), cols, rows);
    expect([...h.cliff].some((v) => v === 1)).toBe(false);
  });
});

describe('화면·세계 기하', () => {
  it('타일 화면 좌표는 iso2d 계약(폭 256 · 높이 128)을 따른다', () => {
    expect(tileToScreen(0, 0)).toEqual([0, 0]);
    expect(tileToScreen(1, 0)).toEqual([128, 64]);
    expect(tileToScreen(0, 1)).toEqual([-128, 64]);
  });

  it('한 단차의 세계 높이가 화면 32px 과 맞는다', () => {
    // 화면 투영: 타일 폭 = √2, 세로 한 칸 = HEIGHT_STEP_WORLD·cos30°.
    const ratio = (HEIGHT_STEP_WORLD * Math.cos(Math.PI / 6)) / Math.SQRT2;
    expect(ratio).toBeCloseTo(32 / 256, 12);
  });
});

describe('pickTileAtScreen — 높이를 감안한 집기', () => {
  // 4×4 격자. (2,2) 한 곳만 두 단 높고 나머지는 지면이다.
  const cols = 4;
  const rows = 4;
  const flat = () => {
    const baseHeight = new Uint8Array(cols * rows);
    const playable = new Uint8Array(cols * rows).fill(1);
    return { cols, rows, baseHeight, playable };
  };

  it('평지에서는 지면 역변환과 같은 타일을 집는다', () => {
    const grid = flat();
    for (const [c, r] of [[0, 0], [3, 3], [1, 2], [2, 1]]) {
      const [x, y] = tileToScreen(c, r);
      expect(pickTileAtScreen(x, y, grid)).toEqual({ col: c, row: r });
    }
  });

  it('높은 타일의 윗면을 누르면 그 타일이 잡힌다 — 높이를 무시하면 다른 타일이 잡힌다', () => {
    const grid = flat();
    grid.baseHeight[2 * cols + 2] = 4;
    // 화면에서 (2,2) 윗면은 지면 위치보다 4×32px 만큼 올라가 있다.
    const [x, y0] = tileToScreen(2, 2);
    const y = y0 - 4 * 32;
    expect(pickTileAtScreen(x, y, grid)).toEqual({ col: 2, row: 2 });
    // 높이를 무시한 옛 역변환은 같은 점을 (1,1) 로 읽는다 — 한 칸 어긋난다.
    const naiveCol = Math.round((x / 128 + y / 64) / 2);
    const naiveRow = Math.round((y / 64 - x / 128) / 2);
    expect({ col: naiveCol, row: naiveRow }).toEqual({ col: 1, row: 1 });
  });

  it('지도 밖(playable 0) 타일은 잡히지 않는다', () => {
    const grid = flat();
    grid.playable[1 * cols + 1] = 0;
    const [x, y] = tileToScreen(1, 1);
    expect(pickTileAtScreen(x, y, grid)).toBeNull();
  });

  it('격자 밖은 null 이다', () => {
    const grid = flat();
    const [x, y] = tileToScreen(-4, -4);
    expect(pickTileAtScreen(x, y, grid)).toBeNull();
  });
});
