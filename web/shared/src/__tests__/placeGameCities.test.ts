import { describe, expect, it } from 'vitest';
import { gameXyToSourceCell, placeGameCities, type GameCityInput } from '../iso/placeGameCities';
import { buildProvinceSeatCells } from '../iso/useIsoTileGrid';
import type { HanTiles } from '../HanMapCanvas';

/** 8×8 원본 셀 = 2×2 타일. 배치 계산만 보므로 격자 내용은 필요 없다. */
const data = {
  grid: { cols: 2, rows: 2 } as never,
  provinceSeatCell: {
    // 縣 0 → 원본 셀 (5,1) = 타일 (1.25, 0.25) · 縣 1 → 좌표 없음
    col: Int32Array.from([5, -1]),
    row: Int32Array.from([1, -1]),
  },
  sourceCols: 8,
  sourceRows: 8,
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
      provinceSeatCell: { col: Int32Array.from([5, 4]), row: Int32Array.from([1, 0]) },
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
