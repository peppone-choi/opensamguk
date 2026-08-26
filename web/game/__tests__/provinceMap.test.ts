import { describe, expect, it } from 'vitest';
import {
  bindProvinceOwnership,
  composeProvincePixels,
  decodeProvincePixels,
  type IsoCityOverlay,
  type IsoSourceSize,
} from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE } from './fixtures/che-tiles';

const SOURCE: IsoSourceSize = { width: 200, height: 120 };

function identityMapWithTwoProvinces() {
  return decodeProvincePixels(new Uint8ClampedArray([
    0, 0, 0, 0, 0, 16, 1, 255, 0, 16, 1, 255, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 16, 1, 255, 0, 16, 2, 255, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 2, 255,
  ]), 4, 3);
}

describe('province identity map', () => {
  it('decodes both identities and separates province from commandery edges', () => {
    const rgba = new Uint8ClampedArray([
      0, 0, 0, 255, 0, 16, 1, 255, 0, 16, 2, 255,
      0, 32, 3, 255, 0, 32, 3, 255, 0, 0, 0, 255,
    ]);

    const map = decodeProvincePixels(rgba, 3, 2);

    expect(Array.from(map.provinces)).toEqual([-1, 0, 1, 2, 2, -1]);
    expect(Array.from(map.commanderies)).toEqual([-1, 0, 0, 1, 1, -1]);
    expect(map.provinceEdges).toContainEqual({ x1: 1.5, y1: -0.5, x2: 1.5, y2: 0.5 });
    expect(map.commanderyEdges).not.toContainEqual({ x1: 1.5, y1: -0.5, x2: 1.5, y2: 0.5 });
    expect(map.commanderyEdges).toContainEqual({ x1: 0.5, y1: 0.5, x2: 1.5, y2: 0.5 });
    expect(map.provinceEdges).not.toContainEqual({ x1: 0.5, y1: -0.5, x2: 0.5, y2: 0.5 });
  });

  it('rejects malformed RGBA and covered pixels with a missing hierarchy identity', () => {
    expect(() => decodeProvincePixels(new Uint8ClampedArray([0, 16, 1]), 1, 1)).toThrow(/RGBA/);
    expect(() => decodeProvincePixels(new Uint8ClampedArray([0, 16, 0, 255]), 1, 1)).toThrow(/hierarchy/);
    expect(() => decodeProvincePixels(new Uint8ClampedArray([0, 0, 1, 255]), 1, 1)).toThrow(/hierarchy/);
  });

  it('binds nation colors by sampling each live city province tile', () => {
    const binding = bindProvinceOwnership(identityMapWithTwoProvinces(), CHE_OVERLAYS_FIXTURE, { cols: 4, rows: 3 }, SOURCE);

    expect(binding.colors.get(0)).toEqual({ nationId: 1, rgb: [255, 0, 0] });
    expect(binding.colors.get(1)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
    expect(binding.conflicts).toEqual([]);
  });

  it('leaves a province neutral when two nations claim one sampled province', () => {
    const oneProvinceMap = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255, 0, 16, 1, 255,
    ]), 2, 1);
    const conflictingCities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], x: 100, y: 0 },
    ];

    const binding = bindProvinceOwnership(oneProvinceMap, conflictingCities, { cols: 2, rows: 1 }, { width: 200, height: 1 });

    expect(binding.colors.has(0)).toBe(false);
    expect(binding.conflicts).toEqual([0]);
  });

  it('ignores neutral, invalid, out-of-grid, and sea samples', () => {
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], nationId: 0, x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 12, nationColor: 'red', x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 13, x: 250, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 14, x: 0, y: 0 },
    ];

    const binding = bindProvinceOwnership(identityMapWithTwoProvinces(), cities, { cols: 4, rows: 3 }, SOURCE);

    expect(binding.colors.size).toBe(0);
    expect(binding.conflicts).toEqual([]);
  });

  it('composes only owned province pixels with the requested alpha', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255, 0, 16, 2, 255, 0, 0, 0, 0,
    ]), 3, 1);
    const binding = bindProvinceOwnership(map, [
      { ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], x: 100, y: 0 },
    ], { cols: 3, rows: 1 }, { width: 300, height: 1 });

    expect(Array.from(composeProvincePixels(map, binding))).toEqual([
      255, 0, 0, 96,
      0, 0, 255, 96,
      0, 0, 0, 0,
    ]);
    expect(Array.from(composeProvincePixels(map, binding, 40))).toEqual([
      255, 0, 0, 40,
      0, 0, 255, 40,
      0, 0, 0, 0,
    ]);
  });
});
