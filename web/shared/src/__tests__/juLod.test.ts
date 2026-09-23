import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildJuLayer, juUrlForTerrain, JU_NAMES, mapLod, verifiedJuByParent } from '../iso/juLod';

const tiles = JSON.parse(readFileSync(resolve(__dirname, '../../../../data/map/han-tiles.json'), 'utf8')) as {
  parentRegions: { nameCh: string }[];
};
const tilesBytes = readFileSync(resolve(__dirname, '../../../../data/map/han-tiles.json'));
const hash = createHash('sha256').update(tilesBytes).digest('hex');
const index = JSON.parse(readFileSync(resolve(__dirname, '../../../../data/map/han-ju-index-v1.json'), 'utf8')) as {
  byTerrainSha256: Record<string, string[]>;
};
const world = JSON.parse(readFileSync(resolve(__dirname, '../../../../infra/src/main/resources/map/han-world-v3.json'), 'utf8')) as {
  cities: { meta: { junCh: string; ju: string } }[];
};

describe('州/郡/縣 zoom selection', () => {
  it('switches at stable CSS-pixel thresholds across DPR values', () => {
    for (const dpr of [1, 1.5, 2, 3]) {
      expect(mapLod(3.19 * dpr / dpr)).toBe('JU');
      expect(mapLod(3.2 * dpr / dpr)).toBe('COMMANDERY');
      expect(mapLod(10.99 * dpr / dpr)).toBe('COMMANDERY');
      expect(mapLod(11 * dpr / dpr)).toBe('COUNTY');
    }
  });

  it('exposes exactly the scenario 州 and agrees with every matching world parent', () => {
    const assigned = index.byTerrainSha256[hash];
    expect(new Set(assigned)).toEqual(new Set([...JU_NAMES, '동이']));
    const byParent = new Map(tiles.parentRegions.map((parent, i) => [parent.nameCh, assigned[i]]));
    for (const city of world.cities) {
      const assigned = byParent.get(city.meta.junCh);
      if (assigned !== undefined) expect(assigned).toBe(city.meta.ju);
    }
  });

  it('binds only to the exact terrain hash and preserves the server query', () => {
    expect(juUrlForTerrain('/api/game/api/map/terrain?server=7&mapCode=han-world-v3'))
      .toBe('/api/game/api/map/ju?server=7&mapCode=han-world-v3');
    expect(verifiedJuByParent({ sourceSha256: hash, juByParent: index.byTerrainSha256[hash] },
      hash, tiles.parentRegions.length)).toHaveLength(tiles.parentRegions.length);
    expect(verifiedJuByParent({ sourceSha256: 'wrong', juByParent: index.byTerrainSha256[hash] },
      hash, tiles.parentRegions.length)).toBeNull();
  });

  it('puts labels in their own raster region and draws only between distinct 州', () => {
    const layer = buildJuLayer(new Int16Array([0, 0, 1, 0, -1, 1, 2, 2, 1]), 3, 3,
      [{ ju: '사예' }, { ju: '예주' }, { ju: '예주' }]);
    expect(layer.labels).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: '사예', cells: 3 }),
      expect.objectContaining({ name: '예주', cells: 5 }),
    ]));
    expect(layer.edges).toContainEqual({ x1: 1.5, y1: -0.5, x2: 1.5, y2: 0.5 });
    expect(() => buildJuLayer(new Int16Array([0]), 1, 1, [{ ju: undefined }])).toThrow('no 州');
  });
});
