import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildProvinceVisualAnchors, expandOwner, resolveCityFootprints, cityFootprintSpan } from '@opensamguk/ui';
import type { ProvinceIdentityMap } from '@opensamguk/ui';

describe('actual Han province clearance', () => {
  it('matches every Python clearance against the runtime TypeScript anchors', () => {
    const tiles = JSON.parse(readFileSync(resolve(__dirname, '../../../data/map/han-tiles.json'), 'utf8'));
    const world = JSON.parse(readFileSync(resolve(__dirname, '../../../infra/src/main/resources/map/han-world-v3.json'), 'utf8'));
    const width = tiles._meta.cols;
    const height = tiles._meta.rows;
    const map: ProvinceIdentityMap = {
      width,
      height,
      provinces: expandOwner(tiles.owner, width * height),
      commanderies: new Int16Array(width * height),
      provinceEdges: [],
      commanderyEdges: [],
    };
    const preferred = new Map<number, { col: number; row: number }>();
    tiles.provinceRecords.forEach((record: { cityIndex?: number }, index: number) => {
      const city = record.cityIndex == null ? undefined : tiles.cities[record.cityIndex];
      if (city) preferred.set(index, { col: city.col, row: city.row });
    });
    const anchors = buildProvinceVisualAnchors(map, preferred);
    const positions = world.cities.map((city: { id: number; level: number; spatialProvinceIndex: number }) => {
      const anchor = anchors[city.spatialProvinceIndex]!;
      return { id: city.id, level: city.level, col: anchor.col, row: anchor.row };
    });
    const spans = resolveCityFootprints(positions);
    const audit = JSON.parse(execFileSync('python3', [
      resolve(__dirname, '../../../tools/map/audit_province_clearance.py'), '--json',
    ], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }));
    expect(audit.clearanceByProvince).toEqual(anchors.map((anchor) => Math.min(3, anchor!.clearance)));
    const counts = new Map<string, number>();
    const rows = world.cities.map((city: { id: number; name: string; level: number; spatialProvinceIndex: number }) => {
      const clearance = anchors[city.spatialProvinceIndex]!.clearance;
      const span = spans.get(city.id)!;
      const required = Math.max(1, (span - 1) / 2);
      const rawRequired = Math.max(1, (cityFootprintSpan(city.level) - 1) / 2);
      const levelName = ({ 1: '수', 2: '진', 3: '관', 4: '이' } as Record<number, string>)[city.level] ?? '성';
      if (clearance < required) counts.set(levelName, (counts.get(levelName) ?? 0) + 1);
      expect(required).toBe(rawRequired);
      return { id: city.id, name: city.name, level: city.level, clearance, span, required };
    });
    expect(audit.narrowByLevel).toEqual(Object.fromEntries(counts));
    expect(rows).toHaveLength(world.cities.length);
  }, 60000);
});
