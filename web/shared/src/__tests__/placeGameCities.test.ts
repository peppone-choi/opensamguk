import { describe, expect, it } from 'vitest';
import {
  firstPickableCity,
  fitCityFootprints,
  fitFootprintsInTile,
  gameXyToSourceCell,
  isExternalPlace,
  placeGameCities,
  type GameCityInput,
  type PlacedCity,
} from '../iso/placeGameCities';
import { buildProvinceSeatCells, type IsoCity } from '../iso/useIsoTileGrid';
import { RASTER_GROUP } from '../isoTileGrid';
import { resolveCityFootprints } from '../iso/cityFootprint';
import { cellFootprintInTiles } from '../iso/buildingFit';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { HanTiles } from '../HanMapCanvas';

/**
 * 2×2 타일짜리 장난감 격자. 원본 셀은 2G×2G 다(G = RASTER_GROUP).
 *
 * 셀 수를 숫자로 박아 두면 G 를 바꿀 때 좌표가 통째로 어긋난다(4 → 2 로 내릴 때
 * 실제로 18 건이 빨개졌다). 그래서 **타일 기준으로 쓰고 G 를 곱한다** — 이 묶음이
 * 보는 것은 「타일 (1,0) 의 구석에 앉은 治所」이지 셀 번호가 아니다.
 */
const G = RASTER_GROUP;
const data = {
  grid: { cols: 2, rows: 2 } as never,
  provinceSeatCell: {
    // 縣 0 → 원본 셀 (G+1, 1) = 타일 (1+1/G, 1/G) — 타일 (1,0) 안 · 縣 1 → 좌표 없음
    col: Int32Array.from([G + 1, -1]),
    row: Int32Array.from([1, -1]),
    cityIndex: Int32Array.from([-1, -1]),
  },
  sourceCols: 2 * G,
  sourceRows: 2 * G,
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
    expect(placed.col).toBeCloseTo(1 + 1 / G);
    expect(placed.row).toBeCloseTo(1 / G);
    expect(placed.tileCol).toBe(1);
    expect(placed.tileRow).toBe(0);
  });

  it('provinceId 가 없으면 x/y 선형 폴백으로 떨어지고 그렇다고 표시한다', () => {
    const [placed] = placeGameCities([city({ x: 25, y: 75 })], data, options);
    expect(placed.exact).toBe(false);
    // x 25/100 × 2G칸 = 셀 G/2 → 타일 0.5 (G 와 무관하다)
    expect(placed.col).toBeCloseTo(0.5);
    expect(placed.row).toBeCloseTo(1.5);
  });

  it('대조표에 좌표가 없는 縣도 폴백으로 떨어진다', () => {
    const [placed] = placeGameCities([city({ provinceId: 1, x: 25, y: 75 })], data, options);
    expect(placed.exact).toBe(false);
    expect(placed.col).toBeCloseTo(0.5);
  });

  it('한 타일에 묶이는 두 도시가 서로 다른 소수 좌표로 살아남는다', () => {
    // 원본 셀 (G+1,1) 과 (G,0) 은 둘 다 타일 (1,0) 이다. 정수로 내리면 하나가 사라진다.
    const twoSeats = {
      ...data,
      provinceSeatCell: {
        col: Int32Array.from([G + 1, G]),
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
        col: Int32Array.from([G + 1, -1]),
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

  it('같은 타일이라도 원본 칸이 다르면 줄이지 않고 제 성내에 선다', () => {
    // 縣 0 의 治所는 원본 칸 (G+1,1). 郡國 밖 세력은 타일 (1,0) = 원본 칸 (G,0).
    const placed = placeGameCities(
      [city({ provinceId: 0 })],
      { ...data, cities: [place({ col: 1, row: 0 })] },
      options,
    );
    expect(placed).toHaveLength(2);
    // 경(7칸) 은 3.5 타일, 1칸 세력은 반 타일 — 1/n 로 줄이지 않는다.
    expect(placed.map((c) => c.drawScale)).toEqual([7 / G, 1 / G]);
    expect(placed[0].drawCol).not.toBeCloseTo(placed[1].drawCol);
  });

  it('원본 셀이 실려 오면 그 칸에 선다 — 타일 구석으로 쏠리지 않는다', () => {
    const [placed] = placeGameCities([], {
      ...data, cities: [place({ col: 1, row: 0, sourceCol: G + 1, sourceRow: 1 })],
    }, options);
    expect(placed.col).toBeCloseTo((G + 1) / G);
    expect(placed.tileCol).toBe(1);
    expect(placed.tileRow).toBe(0);
    // 1칸 성내 중심 = 원본 칸 (G+1,1) 의 중심.
    expect(placed.drawCol).toBeCloseTo((G + 1.5) / G - 0.5);
    expect(placed.drawRow).toBeCloseTo(1.5 / G - 0.5);
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

describe('fitCityFootprints — 성내에 꽉 맞춤', () => {
  /** 원본 칸 (c,r) 에 선 城. col/row 는 placeGameCities 가 넘기는 소수 타일 좌표다. */
  const at = (id: number, level: number, c: number, r: number): PlacedCity => ({
    id, name: `城${id}`, level, nationId: 0,
    col: c / G, row: r / G, tileCol: Math.floor(c / G), tileRow: Math.floor(r / G),
    drawCol: 0, drawRow: 0, drawScale: 1, seat: true, isCapital: false, exact: true,
  });

  it('혼자면 성내 중심 = 마커 칸 중심, 폭 = 변 칸 수 / G 타일', () => {
    // 縣 0 은 원본 셀 (G+1,1), 타일 (1,0) 의 오른아래 칸이다. 경(9) 은 7칸.
    const [placed] = placeGameCities([city({ provinceId: 0 })], data, options);
    expect(placed.drawCol).toBeCloseTo((G + 1.5) / G - 0.5);
    expect(placed.drawRow).toBeCloseTo(1.5 / G - 0.5);
    expect(placed.drawScale).toBeCloseTo(7 / G);
  });

  it.each([
    [9, 7], [8, 5], [7, 5], [6, 3], [10, 1], [11, 1], [5, 1],
  ])('등급 %i 은 변 %i 칸 — 이웃이 없으면 resolveCityFootprints 그대로', (level, span) => {
    const cities = [at(1, level, 40, 40)];
    fitCityFootprints(cities);
    expect(cities[0].drawScale).toBeCloseTo(span / G);
  });

  it('겹치면 HanMapCanvas 와 같은 규칙으로 줄인다(큰 城 먼저, 같으면 번호 순)', () => {
    const cities = [at(2, 9, 43, 40), at(1, 9, 40, 40)];
    fitCityFootprints(cities);
    const spans = resolveCityFootprints(cities.map((c) => ({
      id: c.id, level: c.level, col: c.col * G, row: c.row * G,
    })));
    expect(cities.map((c) => c.drawScale * G)).toEqual([spans.get(2), spans.get(1)]);
    expect(spans.get(1)).toBe(7);
    expect(spans.get(2)).toBeLessThan(7);
  });

  it('郡國 밖 세력은 게임 城 의 성내를 빼앗지 않는다 — 음수 id 가 먼저 풀리지 않는다', () => {
    // 둘 다 중(6) 3칸. 번호만 보면 -1 이 먼저라 게임 城 이 1칸으로 줄 뻔했다.
    const cities = [at(5, 6, 40, 40), at(-1, 6, 42, 40)];
    fitCityFootprints(cities);
    expect(cities[0].drawScale).toBeCloseTo(3 / G);
  });

  it('게임 城 의 성내는 게임 城 끼리만 푼다 — 더 큰 郡國 밖 세력이 있어도 HanMapCanvas 와 같다', () => {
    // 세력(특 5칸)이 먼저 풀리면 게임 城(중 3칸)이 1칸으로 준다. HanMapCanvas 는 세력을 모른다.
    const cities = [at(5, 6, 40, 40), at(-1, 8, 43, 40)];
    fitCityFootprints(cities);
    expect(cities[0].drawScale).toBeCloseTo(3 / G);
  });

  it('마커 칸까지 같으면 1/n 로 줄여 성내 안에서 벌린다 — 겹쳐 두면 뒤엣것을 못 누른다', () => {
    const cities = [at(1, 6, 40, 40), at(2, 6, 40, 40)];
    fitCityFootprints(cities);
    // 1번이 3칸을 잡고, 2번은 마커 칸이 이미 잡혀 1칸으로 준다(resolveCityFootprints). 그다음 둘 다 1/2.
    const own = [cellFootprintInTiles(40, 40, 3), cellFootprintInTiles(40, 40, 1)];
    cities.forEach((placed, n) => {
      expect(placed.drawScale).toBeCloseTo(own[n].width / 2);
      // 줄인 정사각형이 제 성내 안에 든다(타일 좌표에서 성내는 축 정렬 정사각형이다).
      const dc = Math.abs(placed.drawCol - own[n].centerCol);
      const dr = Math.abs(placed.drawRow - own[n].centerRow);
      expect(Math.max(dc, dr) + placed.drawScale / 2).toBeLessThanOrEqual(own[n].width / 2 + 1e-9);
    });
    expect(Math.abs(cities[0].drawCol - cities[1].drawCol) + Math.abs(cities[0].drawRow - cities[1].drawRow))
      .toBeGreaterThan(0.1);
  });

  it('랩처럼 id 가 겹쳐도(전부 -1) 모두 자리를 받는다', () => {
    const cities = [at(-1, 5, 10, 10), at(-1, 8, 20, 20), at(-1, 5, 30, 30)];
    fitCityFootprints(cities);
    expect(cities.map((c) => c.drawScale * G)).toEqual([1, 5, 1]);
  });

  it('옛 이름 fitFootprintsInTile 도 같은 함수다(랩 화면 호환)', () => {
    expect(fitFootprintsInTile).toBe(fitCityFootprints);
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

describe('placeGameCities 는 표시 전용 거점을 덧대지 않는다', () => {
  // 투영·격자는 저장소의 실제 지형 표에서 읽는다. 關 8 곳을 포함한 수·진·관 거점은 이제 게임 城
  // (han-world-v3 1025–1097)이라 서버가 실어 보낸다. 여기서 또 세우면 같은 자리에 두 번 선다.
  const tiles = JSON.parse(
    readFileSync(resolve(__dirname, '../../../..', 'data/map/han-tiles.json'), 'utf-8'),
  ) as { _meta: { projection: unknown; cols: number; rows: number } };
  const real = {
    ...data,
    sourceCols: tiles._meta.cols,
    sourceRows: tiles._meta.rows,
    grid: {
      cols: Math.ceil(tiles._meta.cols / RASTER_GROUP),
      rows: Math.ceil(tiles._meta.rows / RASTER_GROUP),
    } as never,
    projection: tiles._meta.projection,
  } as typeof data;

  it('게임 城 이 하나도 없으면 關 도 서지 않는다', () => {
    const placed = placeGameCities([], real, options);
    expect(placed.filter((c) => c.level === 3)).toHaveLength(0);
    expect(placed.filter((c) => c.id <= -1_000_000)).toHaveLength(0);
  });

  // 변경 縣 51 곳은 더는 여기서 덧대지 않는다 — han-world-v3 城 782–832 로 서버가 실어
  // 보낸다. 게임 城 이 하나도 없으면 郡國 밖 세력만 선다.
  it('게임 城 이 없으면 표시 전용 縣 은 한 곳도 없다', () => {
    const placed = placeGameCities([], real, options);
    expect(placed.filter((c) => c.id < -1_000_008)).toHaveLength(0);
  });
});
