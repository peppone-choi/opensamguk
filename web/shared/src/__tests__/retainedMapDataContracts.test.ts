import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { HanTiles, IsoCityOverlay } from '../HanMapCanvas';
import type { ProvinceIdentityMap } from '../provinceMap';
import { buildMarkerPositions } from '../useWorldMap';

// Retained board data contracts. These do not depend on the retired sprite renderer.
const root = resolve(__dirname, '../../../..');
const tiles = JSON.parse(readFileSync(resolve(root, 'data/map/han-tiles.json'), 'utf8')) as HanTiles;
const world = JSON.parse(readFileSync(resolve(root, 'infra/src/main/resources/map/han-world-v3.json'), 'utf8')) as {
  cities: { id: number; name: string; x: number; y: number; provinceId?: number }[];
  seaRoutes: { from: number; to: number; kind: string }[];
};
const han = JSON.parse(readFileSync(resolve(root, 'infra/src/main/resources/map/han.json'), 'utf8')) as {
  cities: { level: number; meta?: { nameCh?: string } }[];
};
const ledger = JSON.parse(readFileSync(resolve(root, 'data/curated/han/city-seed-reseats-v1.json'), 'utf8')) as {
  reseats: { cityIndex: number; placeId: string; fromCell: [number, number]; toCell: [number, number] }[];
};
const owner = new Int32Array(tiles._meta.cols * tiles._meta.rows);
let offset = 0;
for (const [value, length] of tiles.owner) {
  owner.fill(value, offset, offset + length);
  offset += length;
}

const cellsByProvince = new Map<number, number[]>();
for (let index = 0; index < owner.length; index += 1) {
  const province = owner[index];
  if (province < 0) continue;
  const cells = cellsByProvince.get(province) ?? [];
  cells.push(index);
  cellsByProvince.set(province, cells);
}
const provincesByCity = new Map<number, number[]>();
tiles.provinceRecords?.forEach((record, province) => {
  if (record.cityIndex == null) return;
  const indices = provincesByCity.get(record.cityIndex) ?? [];
  indices.push(province);
  provincesByCity.set(record.cityIndex, indices);
});

function projectedCell(city: HanTiles['cities'][number]): [number, number] {
  const p = tiles._meta.projection!;
  return [(city.lon * p.k - p.x0 + p.pad) / p.cell, (p.y1 + p.pad - city.lat) / p.cell];
}

function reseatCandidates() {
  return tiles.cities.flatMap((city, cityIndex) => {
    const provinces = provincesByCity.get(cityIndex);
    if (!provinces) return [];
    const [col, row] = projectedCell(city);
    if (Math.hypot(col - city.col, row - city.row) < 10) return [];
    let best: { col: number; row: number; distance: number } | null = null;
    for (const province of provinces) for (const index of cellsByProvince.get(province) ?? []) {
      const candidate = { col: index % tiles._meta.cols, row: Math.floor(index / tiles._meta.cols), distance: 0 };
      candidate.distance = Math.hypot(candidate.col - col, candidate.row - row);
      if (!best || candidate.distance < best.distance) best = candidate;
    }
    if (!best || best.distance > 2 || (best.col === city.col && best.row === city.row)) return [];
    return [{ cityIndex, placeId: city.id, fromCell: [city.col, city.row], toCell: [best.col, best.row] }];
  });
}

describe('committed Han board data', () => {
  it('the owner raster, province seats, and game cities agree', () => {
    expect(offset).toBe(owner.length);
    const mismatches = tiles.provinceRecords?.flatMap((record, province) => {
      const city = record.cityIndex == null ? null : tiles.cities[record.cityIndex];
      return city && owner[city.row * tiles._meta.cols + city.col] !== province ? [record.id] : [];
    });
    expect(mismatches).toEqual([]);
    expect(world.cities.filter((city) => city.provinceId == null || !cellsByProvince.has(city.provinceId)).map((city) => city.id)).toEqual([]);
  });

  it('places every game 城 marker inside its own 省 on the actual board', () => {
    const parentById = new Map((tiles.parentRegions ?? []).map((parent, index) => [parent.id, index]));
    const commanderyByProvince = tiles.provinceRecords?.map((record) => parentById.get(record.parentRegionId) ?? -1) ?? [];
    const commanderies = Int16Array.from(owner, (province) => province < 0 ? -1 : commanderyByProvince[province]);
    const provinceMap: ProvinceIdentityMap = {
      width: tiles._meta.cols, height: tiles._meta.rows, provinces: Int16Array.from(owner),
      commanderies, provinceEdges: [], commanderyEdges: [],
    };
    const markers = buildMarkerPositions(world.cities as IsoCityOverlay[], tiles,
      { width: 700, height: 610 }, provinceMap);
    const outside = world.cities.filter((city) => {
      const marker = markers.get(city.id);
      return !marker || marker.provinceId !== city.provinceId
        || owner[marker.row * tiles._meta.cols + marker.col] !== city.provinceId;
    });
    expect(outside.map((city) => city.id)).toEqual([]);
  }, 30_000);

  it('the reseat ledger matches a fresh calculation and catches the old 于山國 location', () => {
    expect(reseatCandidates()).toEqual(ledger.reseats.map(({ cityIndex, placeId, fromCell, toCell }) =>
      ({ cityIndex, placeId, fromCell, toCell })));
    const usan = tiles.cities.find((city) => city.id === 'X035')!;
    const [col, row] = projectedCell(usan);
    const scale = tiles._meta.resolutionScale ?? 1;
    expect(Math.hypot(col - usan.col, row - usan.row)).toBeLessThan(scale);
    const formerCell = [756 * scale + Math.floor((scale - 1) / 2),
      201 * scale + Math.floor((scale - 1) / 2)];
    expect([usan.col, usan.row]).not.toEqual(formerCell);
    const previous = [usan.col, usan.row];
    [usan.col, usan.row] = formerCell;
    try { expect(reseatCandidates().map((candidate) => candidate.placeId)).toEqual(['X035']); }
    finally { [usan.col, usan.row] = previous; }
  });

  it('has thirteen sea and six river links with game city endpoints', () => {
    const ids = new Set(world.cities.map((city) => city.id));
    expect(world.seaRoutes.filter((route) => route.kind === 'SEA')).toHaveLength(13);
    expect(world.seaRoutes.filter((route) => route.kind === 'RIVER')).toHaveLength(6);
    expect(world.seaRoutes).toHaveLength(19);
    expect(world.seaRoutes.every((route) => ids.has(route.from) && ids.has(route.to))).toBe(true);
  });

  it('keeps all external places linked to an administrative system and their graded tiers', () => {
    const externals = tiles.cities.filter((city) => city.kind === 'EXTERNAL_PLACE');
    const systemByName = new Map(tiles.provinceRecords?.flatMap((record) => {
      const city = record.cityIndex == null ? null : tiles.cities[record.cityIndex];
      return city?.kind === 'EXTERNAL_PLACE' ? [[city.nameCh, record.administrativeSystem] as const] : [];
    }));
    const levelByName = new Map(han.cities.flatMap((city) => city.meta?.nameCh
      ? [[city.meta.nameCh, city.level] as const] : []));
    expect(externals).toHaveLength(72);
    expect(externals.filter((city) => !systemByName.has(city.nameCh)).map((city) => city.nameCh)).toEqual([]);
    expect(externals.filter((city) => levelByName.get(city.nameCh) === 4).map((city) => city.nameCh).sort())
      .toEqual(['南匈奴', '哀牢', '山越', '烏桓', '白馬氐', '西羌', '鮮卑'].sort());
    expect(levelByName.get('夫餘')).toBe(6);
    expect(levelByName.get('邪馬壹國')).toBe(6);
  });
});
