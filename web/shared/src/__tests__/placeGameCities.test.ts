import { describe, expect, it } from 'vitest';
import {
  firstPickableCity,
  gameXyToSourceCell,
  isExternalPlace,
  placeGameCities,
  type GameCityInput,
  type PlacedCity,
} from '../iso/placeGameCities';
import { buildProvinceSeatCells, type IsoCity } from '../iso/useIsoTileGrid';
import { PASS_LEVEL, STRATEGIC_PASSES } from '../iso/strategicPasses';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { HanTiles } from '../HanMapCanvas';

/** 8×8 원본 셀 = 2×2 타일. 배치 계산만 보므로 격자 내용은 필요 없다. */
const data = {
  grid: { cols: 2, rows: 2 } as never,
  provinceSeatCell: {
    // 縣 0 → 원본 셀 (5,1) = 타일 (1.25, 0.25) · 縣 1 → 좌표 없음
    col: Int32Array.from([5, -1]),
    row: Int32Array.from([1, -1]),
    cityIndex: Int32Array.from([-1, -1]),
  },
  sourceCols: 8,
  sourceRows: 8,
  cities: [] as IsoCity[],
  // 투영이 없으면 關 은 한 곳도 안 선다 — 이 묶음은 게임 城 배치만 본다.
  projection: undefined,
};
const options = { sourceSize: { width: 100, height: 100 } };

function city(over: Partial<GameCityInput> = {}): GameCityInput {
  return { id: 1, name: '장안', level: 9, nationId: 0, x: 50, y: 50, ...over };
}

describe('buildProvinceSeatCells', () => {
  it('provinceRecords.cityIndex 를 따라 원본 셀 좌표를 편다', () => {
    const tiles = {
      provinceRecords: [
        { cityIndex: 1 }, { cityIndex: null }, { cityIndex: 99 },
      ],
      cities: [{ col: 3, row: 4 }, { col: 521, row: 178 }],
    } as unknown as HanTiles;
    const seat = buildProvinceSeatCells(tiles);
    expect([...seat.col]).toEqual([521, -1, -1]);
    expect([...seat.row]).toEqual([178, -1, -1]);
    // 어느 지형 항목인지도 같이 편다 — 같은 곳을 두 번 그리는 것을 막는 열쇠다.
    expect([...seat.cityIndex]).toEqual([1, -1, -1]);
  });

  it('provinceRecords 가 없으면 빈 표다 — 지어내지 않는다', () => {
    const seat = buildProvinceSeatCells({ cities: [] } as unknown as HanTiles);
    expect(seat.col).toHaveLength(0);
  });
});

describe('placeGameCities', () => {
  it('provinceId 가 있으면 대조표 좌표를 쓴다 — x/y 는 무시한다', () => {
    const [placed] = placeGameCities([city({ provinceId: 0, x: 0, y: 0 })], data, options);
    expect(placed.exact).toBe(true);
    expect(placed.col).toBeCloseTo(1.25);
    expect(placed.row).toBeCloseTo(0.25);
    expect(placed.tileCol).toBe(1);
    expect(placed.tileRow).toBe(0);
  });

  it('provinceId 가 없으면 x/y 선형 폴백으로 떨어지고 그렇다고 표시한다', () => {
    const [placed] = placeGameCities([city({ x: 25, y: 75 })], data, options);
    expect(placed.exact).toBe(false);
    // x 25/100 × 8칸 = 셀 2 → 타일 0.5
    expect(placed.col).toBeCloseTo(0.5);
    expect(placed.row).toBeCloseTo(1.5);
  });

  it('대조표에 좌표가 없는 縣도 폴백으로 떨어진다', () => {
    const [placed] = placeGameCities([city({ provinceId: 1, x: 25, y: 75 })], data, options);
    expect(placed.exact).toBe(false);
    expect(placed.col).toBeCloseTo(0.5);
  });

  it('한 타일에 묶이는 두 도시가 서로 다른 소수 좌표로 살아남는다', () => {
    // 원본 셀 (4,0) 과 (5,1) 은 둘 다 타일 (1,0) 이다. 정수로 내리면 하나가 사라진다.
    const twoSeats = {
      ...data,
      provinceSeatCell: {
        col: Int32Array.from([5, 4]),
        row: Int32Array.from([1, 0]),
        cityIndex: Int32Array.from([-1, -1]),
      },
    };
    const placed = placeGameCities(
      [city({ id: 1, provinceId: 0 }), city({ id: 2, name: '하음', provinceId: 1 })],
      twoSeats, options,
    );
    expect(placed).toHaveLength(2);
    expect(placed[0].tileCol).toBe(placed[1].tileCol);
    expect(placed[0].tileRow).toBe(placed[1].tileRow);
    expect(placed[0].col).not.toBe(placed[1].col);
  });

  it('격자 밖으로 나가는 도시는 버린다', () => {
    expect(placeGameCities([city({ x: 900, y: 900 })], data, options)).toHaveLength(0);
  });

  it('국가표가 있으면 색과 이름을 달아 준다. 없는 id 는 중립으로 남는다', () => {
    const nations = new Map([[7, { name: '촉', color: '#00ff00' }]]);
    const placed = placeGameCities(
      [city({ id: 1, provinceId: 0, nationId: 7 }), city({ id: 2, provinceId: 0, nationId: 0 })],
      data, { ...options, nations },
    );
    expect(placed[0].nationColor).toBe('#00ff00');
    expect(placed[0].nationName).toBe('촉');
    expect(placed[1].nationColor).toBeUndefined();
  });

  it('입력 순서를 지킨다', () => {
    const placed = placeGameCities(
      [city({ id: 3, provinceId: 0 }), city({ id: 1, provinceId: 0 }), city({ id: 2, provinceId: 0 })],
      data, options,
    );
    expect(placed.map((c) => c.id)).toEqual([3, 1, 2]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 郡國 밖 세력(EXTERNAL_PLACE). 중원과 **같은 길**로 앉아야 한다 — 예전 렌더러는 37 곳을
// 따로 떼어 속 빈 마름모로 그렸는데, 그 중 31 곳은 이미 게임 城 이라(실측 31/37,
// provinceRecords[provinceId].cityIndex 가 그 항목을 가리킨다) 한 자리에 城 아이콘과
// 마름모가 겹쳐 섰다. 한반도·왜가 중원과 다른 그림으로 보인 이유가 이것이다.
describe('郡國 밖 세력', () => {
  const place = (over: Partial<IsoCity>): IsoCity => ({
    id: 'X042', name: '백제국', nameCh: '伯濟國', level: 4,
    kind: 'EXTERNAL_PLACE', seat: true, col: 0, row: 1, ...over,
  });

  it('게임 城 이 없는 곳은 城 과 같은 모양으로 앉는다', () => {
    const placed = placeGameCities([], { ...data, cities: [place({})] }, options);
    expect(placed).toHaveLength(1);
    expect(placed[0]).toMatchObject({ name: '백제국', tileCol: 0, tileRow: 1 });
    // 좌표는 CHGIS 실측이다 — x/y 선형 폴백이 아니다.
    expect(placed[0].exact).toBe(true);
  });

  // 지형의 level 4 는 등급이 아니라 자리표시다(build_external_places.py 주석). 그대로
  // 쓰면 백제국도 흉노도 같은 이민족 야영으로 선다 — 행정 계통으로 갈라야 한다.
  it('지형이 실어 보낸 자리표시 등급을 그대로 쓰지 않는다', () => {
    const placed = placeGameCities([], { ...data, cities: [place({})] }, options);
    expect(placed[0].level).not.toBe(4);
    expect(placed[0].level).toBe(5);
  });

  it('유목 세력만 야영(4)으로 선다', () => {
    const tribal = place({ name: '흉노', nameCh: '南匈奴', administrativeSystem: 'XIONGNU' });
    const settled = place({ administrativeSystem: 'BAEKJE' });
    expect(placeGameCities([], { ...data, cities: [tribal] }, options)[0].level).toBe(4);
    expect(placeGameCities([], { ...data, cities: [settled] }, options)[0].level).toBe(5);
  });

  it('id 가 음수라 게임 도시 번호와 절대 겹치지 않는다', () => {
    const placed = placeGameCities([], { ...data, cities: [place({})] }, options);
    expect(placed[0].id).toBeLessThan(0);
    expect(isExternalPlace(placed[0])).toBe(true);
  });

  it('이미 게임 城 인 곳은 덧대지 않는다 — 겹쳐 그리던 31 곳이 이 길로 걸러진다', () => {
    const covered = {
      ...data,
      provinceSeatCell: {
        col: Int32Array.from([5, -1]),
        row: Int32Array.from([1, -1]),
        // 縣 0 의 治所가 지형 cities[0] 이다 = 아래 external 과 같은 항목.
        cityIndex: Int32Array.from([0, -1]),
      },
      cities: [place({ col: 1, row: 0 })],
    };
    const placed = placeGameCities([city({ id: 754, name: '백제국', provinceId: 0 })], covered, options);
    expect(placed).toHaveLength(1);
    expect(placed[0].id).toBe(754);
  });

  it('EXTERNAL_PLACE 가 아닌 지형 항목은 덧대지 않는다', () => {
    const placed = placeGameCities([], { ...data, cities: [place({ kind: 'COUNTY' })] }, options);
    expect(placed).toHaveLength(0);
  });

  it('격자 밖은 버린다', () => {
    const placed = placeGameCities([], { ...data, cities: [place({ col: 9, row: 9 })] }, options);
    expect(placed).toHaveLength(0);
  });

  it('城 과 같은 칸에 들면 같이 발자국을 나눈다 — 겹쳐 세우지 않는다', () => {
    // 縣 0 의 治所는 타일 (1,0). 郡國 밖 세력도 같은 칸에 둔다.
    const placed = placeGameCities(
      [city({ provinceId: 0 })],
      { ...data, cities: [place({ col: 1, row: 0 })] },
      options,
    );
    expect(placed).toHaveLength(2);
    expect(placed.map((c) => c.drawScale)).toEqual([0.5, 0.5]);
    expect(placed[0].drawCol).not.toBeCloseTo(placed[1].drawCol);
  });
});

describe('gameXyToSourceCell', () => {
  it('축마다 배율이 따로다 — 기존 캔버스 mapCityToTile 과 같은 식', () => {
    expect(gameXyToSourceCell(350, 305, { width: 700, height: 610 }, { cols: 768, rows: 669 }))
      .toEqual({ col: 384, row: 334.5 });
  });
});

describe('세력 표시 중립성', () => {
  const nations = new Map([
    [1, { name: '위', color: '#ff0000' }],
    [0, { name: '재야', color: '#ff0000' }],
    [-1, { name: '표시 금지', color: '#ff0000' }],
    [2, { name: '표시 금지', color: 'red' }],
    [1.5, { name: '표시 금지', color: '#ff0000' }],
  ]);
  const place = (nationId: number) => placeGameCities(
    [city({ provinceId: 0, nationId })], data, { ...options, nations },
  )[0];

  it('소유가 확실하면 이름과 색을 붙인다', () => {
    expect(place(1)).toMatchObject({ nationName: '위', nationColor: '#ff0000' });
  });

  it.each([
    ['재야(0)', 0],
    ['음수', -1],
    ['#rrggbb 가 아닌 색', 2],
    ['비정수', 1.5],
    ['표에 없는 번호', 9],
  ])('%s 은 이름도 색도 붙이지 않는다', (_label, nationId) => {
    const placed = place(nationId);
    expect(placed.nationName).toBeUndefined();
    expect(placed.nationColor).toBeUndefined();
  });
});

describe('fitFootprintsInTile', () => {
  /** 마름모 노름. 아이소 칸은 이 값이 1 을 넘으면 칸 밖이다. */
  const norm = (c: number, r: number) => Math.abs(c) + Math.abs(r);

  it('혼자면 칸 한가운데에 세운다 — 원본 셀이 칸 모서리여도', () => {
    // 縣 0 은 원본 셀 (5,1), 타일 (1,0) 의 오른아래 구석이다.
    const [placed] = placeGameCities([city({ provinceId: 0 })], data, options);
    expect(placed.drawCol).toBeCloseTo(1);
    expect(placed.drawRow).toBeCloseTo(0);
    expect(placed.drawScale).toBe(1);
  });

  it('같은 칸에 둘이면 발자국을 반으로 줄이고 칸 안에서 벌린다', () => {
    const seatCell = {
      // 둘 다 타일 (1,0) 안이지만 원본 셀은 다르다 — 같은 자리에 겹치면 못 누른다.
      col: Int32Array.from([4, 7]),
      row: Int32Array.from([0, 3]),
      cityIndex: Int32Array.from([-1, -1]),
    };
    const both = placeGameCities(
      [city({ id: 1, provinceId: 0 }), city({ id: 2, provinceId: 1 })],
      { ...data, provinceSeatCell: seatCell },
      options,
    );
    expect(both).toHaveLength(2);
    for (const placed of both) {
      expect(placed.tileCol).toBe(1);
      expect(placed.tileRow).toBe(0);
      expect(placed.drawScale).toBeCloseTo(0.5);
      // 밑면(=drawScale 타일)이 칸을 넘지 않는다: |dc|+|dr| + scale <= 1.
      const off = norm(placed.drawCol - placed.tileCol, placed.drawRow - placed.tileRow);
      expect(off + placed.drawScale).toBeLessThanOrEqual(1 + 1e-9);
    }
    // 서로 떨어져 있어야 각각 집힌다.
    expect(norm(both[0].drawCol - both[1].drawCol, both[0].drawRow - both[1].drawRow))
      .toBeGreaterThan(0.5);
  });

  it('좌표까지 같으면 마름모 둘레로 돌려세운다 — 겹쳐 두면 뒤엣것을 못 누른다', () => {
    const seatCell = {
      col: Int32Array.from([5, 5]),
      row: Int32Array.from([1, 1]),
      cityIndex: Int32Array.from([-1, -1]),
    };
    const both = placeGameCities(
      [city({ id: 1, provinceId: 0 }), city({ id: 2, provinceId: 1 })],
      { ...data, provinceSeatCell: seatCell },
      options,
    );
    expect(both[0].drawCol).not.toBeCloseTo(both[1].drawCol);
  });
});

describe('firstPickableCity', () => {
  /** 집기 광선이 맞힌 것 하나. 집기 판정에 쓰는 건 id 뿐이라 나머지는 채우기다. */
  const hit = (id: number): PlacedCity => ({
    id, name: id < 0 ? '백제국' : '낙양', level: 4, nationId: 0,
    col: 0, row: 0, tileCol: 0, tileRow: 0, drawCol: 0, drawRow: 0,
    drawScale: 1, seat: true, isCapital: false, exact: true,
  });

  it('맨 앞이 郡國 밖 세력이면 건너뛰고 뒤의 城 을 집는다', () => {
    // 이 순서가 광선 순서다 — 앞에 선 이민족 하나가 뒤의 城 을 못 누르게 막으면 안 된다.
    expect(firstPickableCity([hit(-3), hit(42)])?.id).toBe(42);
  });

  it('맞은 게 전부 郡國 밖이면 null — 음수 id 는 onPickCity 로 절대 나가지 않는다', () => {
    expect(firstPickableCity([hit(-3), hit(-9)])).toBeNull();
  });

  it('빈 구멍(instanceId 없는 맞음)은 건너뛴다', () => {
    expect(firstPickableCity([undefined, hit(7)])?.id).toBe(7);
    expect(firstPickableCity([])).toBeNull();
  });
});

describe('placeGameCities 가 關 을 같이 세운다', () => {
  // 투영·격자는 저장소의 실제 지형 표에서 읽는다. 여기가 배선 게이트다 —
  // placeGameCities 에서 placeStrategicPasses 를 빼면 이 묶음이 빨개진다.
  const tiles = JSON.parse(
    readFileSync(resolve(__dirname, '../../../..', 'data/map/han-tiles.json'), 'utf-8'),
  ) as { _meta: { projection: unknown; cols: number; rows: number } };
  const real = {
    ...data,
    sourceCols: tiles._meta.cols,
    sourceRows: tiles._meta.rows,
    grid: {
      cols: Math.ceil(tiles._meta.cols / 4),
      rows: Math.ceil(tiles._meta.rows / 4),
    } as never,
    projection: tiles._meta.projection,
  } as typeof data;

  it('게임 城 이 하나도 없어도 關 8 곳은 선다', () => {
    const placed = placeGameCities([], real, options);
    const passes = placed.filter((c) => c.level === PASS_LEVEL);
    expect(passes).toHaveLength(STRATEGIC_PASSES.length);
    expect(passes.map((p) => p.name).sort()).toEqual(
      STRATEGIC_PASSES.map((p) => p.nameKo).sort(),
    );
    // 전부 음수 id — 눌러도 들어갈 데가 없다.
    expect(passes.every(isExternalPlace)).toBe(true);
  });

  it('關 도 fitFootprintsInTile 을 지난다 — 제 칸 안에 선다', () => {
    const passes = placeGameCities([], real, options).filter((c) => c.level === PASS_LEVEL);
    for (const pass of passes) {
      expect(Math.abs(pass.drawCol - (pass.tileCol + 0.5))).toBeLessThanOrEqual(0.5);
      expect(Math.abs(pass.drawRow - (pass.tileRow + 0.5))).toBeLessThanOrEqual(0.5);
    }
  });
});
