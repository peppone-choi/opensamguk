// 되돌림 표가 규칙과 일치하는지 지형 파일에서 **다시 계산해서** 본다.
//
// 표를 손으로 적어 두고 그 표만 읽으면 아무것도 증명하지 못한다. 여기서는
// han-tiles.json 의 owner 래스터·provinceRecords·_meta.projection 만으로 규칙을 다시
// 돌려, 나오는 줄이 표와 **정확히** 같은지 본다. 지형 파일이 바뀌어 밀림이 사라지거나
// 다른 곳이 생기면 이 검사가 빨개진다.

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expandRunLength } from '../isoTileGrid';
import {
  CITY_SEED_RESEATS, applyCitySeedReseats, type ReseatableCity,
} from '../iso/citySeedReseat';
import type { HanTiles } from '../HanMapCanvas';

const ROOT = resolve(__dirname, '../../../..');
const tiles = JSON.parse(
  readFileSync(resolve(ROOT, 'data/map/han-tiles.json'), 'utf-8'),
) as HanTiles;
const ledger = JSON.parse(
  readFileSync(resolve(ROOT, 'data/curated/han/city-seed-reseats-v1.json'), 'utf-8'),
) as {
  reseats: {
    cityIndex: number; placeId: string; fromCell: [number, number]; toCell: [number, number];
    cellsFromLatLonBefore: number; cellsFromLatLonAfter: number;
  }[];
};

const srcCols = tiles._meta.cols;
const srcRows = tiles._meta.rows;
const owner = expandRunLength(tiles.owner, srcCols * srcRows);

/** 縣 인덱스 → 그 縣 이 가진 원본 셀들. */
const cellsByProvince = new Map<number, number[]>();
for (let i = 0; i < owner.length; i += 1) {
  const value = owner[i];
  if (value < 0) continue;
  const bucket = cellsByProvince.get(value);
  if (bucket) bucket.push(i);
  else cellsByProvince.set(value, [i]);
}

/** cities[] 인덱스 → 그 城 을 가리키는 provinceRecords 인덱스들. */
const provincesByCity = new Map<number, number[]>();
(tiles.provinceRecords ?? []).forEach((record, index) => {
  const cityIndex = record.cityIndex;
  if (cityIndex == null || !Number.isInteger(cityIndex)) return;
  const bucket = provincesByCity.get(cityIndex);
  if (bucket) bucket.push(index);
  else provincesByCity.set(cityIndex, [index]);
});

const projection = tiles._meta.projection!;

function project(lat: number, lon: number): [number, number] {
  return [
    (lon * projection.k - projection.x0 + projection.pad) / projection.cell,
    (projection.y1 + projection.pad - lat) / projection.cell,
  ];
}

interface Candidate {
  cityIndex: number; placeId: string;
  from: [number, number]; to: [number, number];
  before: number; after: number;
}

/** 규칙을 지형 파일에서 다시 돌린다. citySeedReseat.ts 주석의 그 규칙이다. */
function recompute(): Candidate[] {
  const out: Candidate[] = [];
  tiles.cities.forEach((city, cityIndex) => {
    const provinces = provincesByCity.get(cityIndex);
    if (!provinces) return;
    const [pc, pr] = project(city.lat, city.lon);
    const before = Math.hypot(pc - city.col, pr - city.row);
    if (before < 10) return;
    let best: [number, number] | null = null;
    let bestDistance = Infinity;
    for (const province of provinces) {
      for (const index of cellsByProvince.get(province) ?? []) {
        const col = index % srcCols;
        const row = Math.floor(index / srcCols);
        const distance = (col - pc) ** 2 + (row - pr) ** 2;
        if (distance < bestDistance) {
          bestDistance = distance;
          best = [col, row];
        }
      }
    }
    if (!best) return;
    const after = Math.sqrt(bestDistance);
    if (after > 2) return;
    if (best[0] === city.col && best[1] === city.row) return;
    out.push({
      cityIndex, placeId: city.id, from: [city.col, city.row], to: best, before, after,
    });
  });
  return out;
}

describe('citySeedReseat', () => {
  const found = recompute().sort((a, b) => b.before - a.before);

  it('지형 파일에서 다시 돌린 규칙이 표와 같은 곳을 짚는다', () => {
    expect(found.map((row) => row.placeId)).toEqual(['X035', '40078', '70524', '42147']);
    expect(found.map((row) => [row.cityIndex, row.from, row.to])).toEqual(
      CITY_SEED_RESEATS
        .map((row) => [row.cityIndex, [row.fromCol, row.fromRow], [row.toCol, row.toRow]]),
    );
  });

  it('원장과 런타임 표가 한 글자도 어긋나지 않는다', () => {
    expect(ledger.reseats.map((row) => ({
      cityIndex: row.cityIndex, placeId: row.placeId,
      fromCol: row.fromCell[0], fromRow: row.fromCell[1],
      toCol: row.toCell[0], toRow: row.toCell[1],
    }))).toEqual(CITY_SEED_RESEATS.map((row) => ({ ...row })));
  });

  it('于山國 을 오키 제도에서 울릉도로 되돌린다', () => {
    const usan = found.find((row) => row.placeId === 'X035')!;
    // 되돌리기 전 49 칸, 되돌린 뒤 반 칸.
    expect(usan.before).toBeGreaterThan(45);
    expect(usan.after).toBeLessThan(1);
    // 옮길 칸은 지어낸 좌표가 아니라 **파일이 于山國 것이라고 적어 둔 칸**이다.
    const [col, row] = usan.to;
    const province = (tiles.provinceRecords ?? []).findIndex((r) => r.id === 'X035');
    expect(owner[row * srcCols + col]).toBe(province);
    // 파일 자신의 郡 행도 같은 칸을 가리킨다.
    const jun = (tiles.juns ?? []).find((entry) => entry.nameCh === '于山國');
    expect(jun && [jun.col, jun.row]).toEqual([col, row]);
  });

  it('applyCitySeedReseats 가 표대로 옮기고, 두 번 부르면 다시 옮기지 않는다', () => {
    const cities: ReseatableCity[] = tiles.cities.map((city) => ({
      id: city.id, col: city.col, row: city.row,
    }));
    expect(applyCitySeedReseats(cities)).toBe(CITY_SEED_RESEATS.length);
    for (const reseat of CITY_SEED_RESEATS) {
      expect([cities[reseat.cityIndex].col, cities[reseat.cityIndex].row])
        .toEqual([reseat.toCol, reseat.toRow]);
    }
    expect(applyCitySeedReseats(cities)).toBe(0);
  });

  // 함수가 옳아도 아무도 안 부르면 지도는 그대로다. 훅은 fetch·canvas 를 물고 있어
  // 여기서 돌릴 수 없으므로, 배선은 원문에서 본다 — 順序까지 본다(治所 좌표를 펴기 전에
  // 고쳐야 격자·소유·게임 城 이 전부 고친 칸을 읽는다).
  it('useIsoTileGrid 가 治所 좌표를 펴기 전에 이 되돌림을 부른다', () => {
    const source = readFileSync(resolve(__dirname, '../iso/useIsoTileGrid.ts'), 'utf-8');
    const call = source.indexOf('applyCitySeedReseats(tiles.cities)');
    const seats = source.indexOf('buildProvinceSeatCells(tiles)');
    expect(call).toBeGreaterThan(0);
    expect(seats).toBeGreaterThan(call);
  });

  it('id 가 맞지 않는 행은 건드리지 않는다', () => {
    const cities: ReseatableCity[] = tiles.cities.map((city) => ({
      id: `${city.id}-changed`, col: city.col, row: city.row,
    }));
    expect(applyCitySeedReseats(cities)).toBe(0);
  });
});
