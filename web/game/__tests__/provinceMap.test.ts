import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  bindProvinceOwnership,
  bindCompleteProvinceOwnership,
  buildCountyAdministrativeIndex,
  buildProvinceAdministrativeIndex,
  composeProvincePixels,
  decodeProvincePixels,
  isOwnedNationVisual,
  loadProvinceIdentityMap,
  formatProvinceTooltip,
  type IsoCityOverlay,
  type IsoSourceSize,
  type ParentRegionRecordDto,
  type ProvinceRecordDto,
  type ProvinceIdentityMap,
} from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE } from './fixtures/che-tiles';

const SOURCE: IsoSourceSize = { width: 200, height: 120 };
const REAL_RGB8_PNG = Uint8Array.from(Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAAD0lEQVR4AQEEAPv/AAAQAQAlABLmzoVCAAAAAElFTkSuQmCC',
  'base64',
));

function expandRle(rle: [number, number][], cells: number): Int16Array {
  const values = new Int16Array(cells);
  let index = 0;
  for (const [value, count] of rle) {
    values.fill(value, index, index + count);
    index += count;
  }
  return values;
}

function bytes(...parts: readonly Uint8Array[]): Uint8Array {
  const result = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function uint32(value: number): Uint8Array {
  return new Uint8Array([(value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff]);
}

function crc32(value: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of value) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ ((crc & 1) === 1 ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(kind: string, payload?: Uint8Array): Uint8Array {
  const body = payload ?? new Uint8Array();
  const kindBytes = new TextEncoder().encode(kind);
  return bytes(uint32(body.length), kindBytes, body, uint32(crc32(bytes(kindBytes, body))));
}

function provincePng({
  width = 2,
  height = 1,
  bitDepth = 8,
  colorType = 2,
  interlace = 0,
  extraChunks = [],
}: {
  width?: number;
  height?: number;
  bitDepth?: number;
  colorType?: number;
  interlace?: number;
  extraChunks?: string[];
} = {}): Uint8Array {
  const ihdr = bytes(uint32(width), uint32(height), new Uint8Array([bitDepth, colorType, 0, 0, interlace]));
  return bytes(
    new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    ...extraChunks.map((kind) => pngChunk(kind, new Uint8Array([1]))),
    pngChunk('IDAT', new Uint8Array([0x78, 0x01])),
    pngChunk('IEND'),
  );
}

function installSuccessfulFetch(png = provincePng(), contentType = 'image/png') {
  const response = new Response(png, { status: 200, headers: { 'Content-Type': contentType } });
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, png };
}

function installBitmap(width = 2, height = 1) {
  const close = vi.fn();
  const bitmap = { width, height, close };
  const createBitmap = vi.fn().mockResolvedValue(bitmap);
  vi.stubGlobal('createImageBitmap', createBitmap);
  return { bitmap, close, createBitmap };
}

function installDecodeCanvas(context: CanvasRenderingContext2D | null) {
  const canvas = { width: 0, height: 0, getContext: vi.fn().mockReturnValue(context) };
  vi.spyOn(document, 'createElement').mockReturnValue(canvas as unknown as HTMLCanvasElement);
  return canvas;
}

function identityMapWithTwoProvinces() {
  return decodeProvincePixels(new Uint8ClampedArray([
    0, 0, 0, 0, 0, 16, 1, 255, 0, 16, 1, 255, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 16, 1, 255, 0, 16, 2, 255, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 2, 255,
  ]), 4, 3);
}

describe('province identity map', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('formats Han and external province tooltips with their own administrative systems', () => {
    expect(formatProvinceTooltip(
      { id: 'P1', displayName: '조선현', nameCh: '朝鮮縣', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY', parentRegionId: 'R1', cityIndex: 1, geometryBasis: 'HISTORICAL_BOUNDARY', confidence: 'REVIEWED' },
      { id: 'R1', displayName: '낙랑군', nameCh: '樂浪郡', administrativeSystem: 'HAN_COMMANDERY' },
    )).toBe('낙랑군 조선현');
    expect(formatProvinceTooltip(
      { id: 'P2', displayName: '구야국', nameCh: '狗邪國', administrativeSystem: 'BYEONHAN', kind: 'SETTLEMENT', parentRegionId: 'R2', cityIndex: 2, geometryBasis: 'HISTORICAL_SEAT_ADAPTED', confidence: 'IDENTIFIED' },
      { id: 'R2', displayName: '변한', nameCh: '', administrativeSystem: 'BYEONHAN' },
    )).toBe('변한 · 구야국');
    expect(formatProvinceTooltip(
      { id: 'P3', displayName: '국내성', nameCh: '國內城', administrativeSystem: 'GOGURYEO', kind: 'SETTLEMENT', parentRegionId: 'R3', cityIndex: 3, geometryBasis: 'HISTORICAL_SEAT_ADAPTED', confidence: 'IDENTIFIED' },
      { id: 'R3', displayName: '고구려', nameCh: '', administrativeSystem: 'GOGURYEO' },
    )).toBe('고구려 · 국내성');
  });

  it('indexes direct territories by explicit parent instead of city-array position', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255, 0, 16, 2, 255,
    ]), 2, 1);
    const index = buildProvinceAdministrativeIndex(map, [
      { id: 'P1', displayName: 'A', nameCh: '', administrativeSystem: 'MAHAN', kind: 'SETTLEMENT', parentRegionId: 'R1', cityIndex: 0, geometryBasis: 'HISTORICAL_SEAT_ADAPTED', confidence: 'REVIEWED' },
      { id: 'P2', displayName: '직할지', nameCh: '', administrativeSystem: 'MAHAN', kind: 'DIRECT_TERRITORY', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'MODERN_ADMIN_FALLBACK', confidence: 'INFERRED' },
    ], [{ id: 'R1', displayName: '마한', nameCh: '', administrativeSystem: 'MAHAN' }]);
    expect(Array.from(index.commanderyByProvince)).toEqual([0, 0]);
  });

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

  it('accepts the Python codec maximum commandery and rejects upper-bit overflow', () => {
    const maximum = decodeProvincePixels(new Uint8ClampedArray([0x0f, 0xf0, 0x01, 255]), 1, 1);

    expect(Array.from(maximum.provinces)).toEqual([0]);
    expect(Array.from(maximum.commanderies)).toEqual([254]);
    expect(() => decodeProvincePixels(new Uint8ClampedArray([0x10, 0x10, 0x01, 255]), 1, 1)).toThrow(/commandery/);
  });

  it('binds nation colors by sampling each live city province tile', () => {
    const binding = bindProvinceOwnership(identityMapWithTwoProvinces(), CHE_OVERLAYS_FIXTURE, { cols: 4, rows: 3 }, SOURCE);

    expect(binding.colors.get(0)).toEqual({ nationId: 1, rgb: [255, 0, 0] });
    expect(binding.colors.get(1)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
    expect(binding.conflicts).toEqual([]);
  });

  it('does not spread one occupied settlement across an unmapped province in the same commandery', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 16, 2, 255,
      0, 32, 3, 255,
      0, 32, 3, 255,
    ]), 4, 1);
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], x: 300, y: 0 },
    ];

    const countyIndex = buildCountyAdministrativeIndex(map, [
      { col: 0, row: 0 }, { col: 1, row: 0 }, { col: 2, row: 0 },
    ], [{ name: 'A' }, { name: 'B' }]);
    const binding = bindCompleteProvinceOwnership(map, cities, { cols: 4, rows: 1 }, { width: 400, height: 1 }, countyIndex);

    expect(binding.colors.get(0)).toEqual({ nationId: 1, rgb: [255, 0, 0] });
    expect(binding.colors.has(1)).toBe(false);
    expect(binding.colors.get(2)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
    expect(binding.cities?.has(1)).toBe(false);
    expect(Array.from(composeProvincePixels(map, binding).slice(4, 8))).toEqual([0, 0, 0, 0]);
  });

  it('keeps geometry without a direct city blank even when its commandery has an owner', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 16, 2, 255,
      0, 16, 3, 255,
    ]), 3, 1);
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], id: 10, x: 0, y: 0, nationId: 0, nationColor: '#000000' },
      { ...CHE_OVERLAYS_FIXTURE[1], id: 20, x: 200, y: 0, nationId: 2, nationColor: '#0000ff' },
    ];
    const countyIndex = buildCountyAdministrativeIndex(
      map,
      [{ col: 0, row: 0 }, { col: 1, row: 0 }, { col: 2, row: 0 }],
      [{ name: 'A군' }],
    );

    const binding = bindCompleteProvinceOwnership(
      map,
      cities,
      { cols: 3, rows: 1 },
      { width: 300, height: 1 },
      countyIndex,
    );

    expect(binding.colors.has(0)).toBe(false);
    expect(binding.colors.has(1)).toBe(false);
    expect(binding.cities?.has(1)).toBe(false);
  });

  it('resolves a shared county by centroid distance then lowest city id', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 16, 1, 255,
    ]), 2, 1);
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], id: 20, x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], id: 10, x: 100, y: 0 },
    ];

    const countyIndex = buildCountyAdministrativeIndex(map, [{ col: 0, row: 0 }], [{ name: 'A' }]);
    const binding = bindCompleteProvinceOwnership(map, cities, { cols: 2, rows: 1 }, { width: 200, height: 1 }, countyIndex);

    expect(binding.colors.get(0)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
    expect(binding.cities?.get(0)?.id).toBe(10);
    expect(binding.conflicts).toEqual([]);
  });

  it('colors only provinces with a direct owned runtime city', () => {
    const tiles = JSON.parse(readFileSync(resolve(process.cwd(), '../../data/map/han-tiles.json'), 'utf8')) as {
      _meta: { cols: number; rows: number };
      owner: [number, number][];
      parentOwner: [number, number][];
      cities: { col: number; row: number }[];
      juns: { name: string }[];
      provinceRecords: ProvinceRecordDto[];
      parentRegions: ParentRegionRecordDto[];
    };
    const runtime = JSON.parse(readFileSync(resolve(process.cwd(), '../../infra/src/main/resources/map/han.json'), 'utf8')) as {
      width: number;
      height: number;
      cities: { id: number; name: string; level: number; x: number; y: number; meta: { ju: string; jun: string } }[];
    };
    const cells = tiles._meta.cols * tiles._meta.rows;
    const map: ProvinceIdentityMap = {
      width: tiles._meta.cols,
      height: tiles._meta.rows,
      provinces: expandRle(tiles.owner, cells),
      commanderies: expandRle(tiles.parentOwner, cells),
      provinceEdges: [],
      commanderyEdges: [],
    };
    const overlays: IsoCityOverlay[] = runtime.cities.map((city) => ({
      ...city,
      nationId: 1,
      nationColor: '#a83232',
      regionName: city.meta.ju,
      commanderyName: city.meta.jun,
    }));
    const countyIndex = buildProvinceAdministrativeIndex(map, tiles.provinceRecords, tiles.parentRegions);
    expect(
      runtime.cities.every((city) => countyIndex.commanderyByName.has(city.meta.jun)),
      'every runtime commandery name must resolve directly or through a reviewed temporal alias',
    ).toBe(true);

    const binding = bindCompleteProvinceOwnership(
      map,
      overlays,
      { cols: tiles._meta.cols, rows: tiles._meta.rows },
      { width: runtime.width, height: runtime.height },
      countyIndex,
    );
    const landProvinces = new Set([...map.provinces].filter((province) => province >= 0));

    expect(landProvinces.size).toBe(tiles.provinceRecords.length);
    expect(binding.colors.size).toBeLessThan(landProvinces.size);
    expect([...binding.colors.values()].every((color) => color.nationId === 1)).toBe(true);
    expect([...binding.colors.values()].some((color) => color.nationId === 1)).toBe(true);
    expect(binding.conflicts).toEqual([]);
    expect([...countyIndex.commanderyByProvince].every((commandery) => commandery >= 0)).toBe(true);
  });

  it('renders all 15 scenarios without inferred ownership or cross-commandery claims', () => {
    const tiles = JSON.parse(readFileSync(resolve(process.cwd(), '../../data/map/han-tiles.json'), 'utf8')) as {
      _meta: { cols: number; rows: number };
      owner: [number, number][];
      parentOwner: [number, number][];
      provinceRecords: ProvinceRecordDto[];
      parentRegions: ParentRegionRecordDto[];
    };
    const runtime = JSON.parse(readFileSync(resolve(process.cwd(), '../../infra/src/main/resources/map/han.json'), 'utf8')) as {
      width: number;
      height: number;
      cities: { id: number; name: string; level: number; x: number; y: number; meta: { ju: string; jun: string } }[];
    };
    const scenarioDirectory = resolve(process.cwd(), '../../infra/src/main/resources/scenario');
    const scenarioFiles = readdirSync(scenarioDirectory)
      .filter((name) => /^scenario_(1010|1020|1021|1030|1031|1040|1041|1050|1060|1070|1080|1090|1100|1110|1120)\.json$/.test(name))
      .sort();
    const cells = tiles._meta.cols * tiles._meta.rows;
    const map: ProvinceIdentityMap = {
      width: tiles._meta.cols,
      height: tiles._meta.rows,
      provinces: expandRle(tiles.owner, cells),
      commanderies: expandRle(tiles.parentOwner, cells),
      provinceEdges: [],
      commanderyEdges: [],
    };
    const countyIndex = buildProvinceAdministrativeIndex(map, tiles.provinceRecords, tiles.parentRegions);
    const landCells = [...map.provinces]
      .map((province, index) => province >= 0 ? index : -1)
      .filter((index) => index >= 0);

    expect(scenarioFiles).toHaveLength(15);
    for (const scenarioFile of scenarioFiles) {
      const scenario = JSON.parse(readFileSync(resolve(scenarioDirectory, scenarioFile), 'utf8')) as {
        nation: [string, string, unknown, unknown, unknown, unknown, unknown, number, number[]][];
      };
      const ownerByCity = new Map<number, { nationId: number; nationName: string; nationColor: string }>();
      scenario.nation.forEach((nation, index) => {
        if (nation[7] <= 0) return;
        nation[8].forEach((cityId) => ownerByCity.set(cityId, {
          nationId: index + 1,
          nationName: nation[0],
          nationColor: nation[1],
        }));
      });
      const overlays: IsoCityOverlay[] = runtime.cities.map((city) => ({
        ...city,
        nationId: ownerByCity.get(city.id)?.nationId ?? 0,
        nationName: ownerByCity.get(city.id)?.nationName,
        nationColor: ownerByCity.get(city.id)?.nationColor,
        regionName: city.meta.ju,
        commanderyName: city.meta.jun,
      }));
      const binding = bindCompleteProvinceOwnership(
        map,
        overlays,
        { cols: tiles._meta.cols, rows: tiles._meta.rows },
        { width: runtime.width, height: runtime.height },
        countyIndex,
      );
      const pixels = composeProvincePixels(map, binding);

      expect(binding.colors.size, scenarioFile).toBeLessThanOrEqual(ownerByCity.size);
      expect(landCells.some((cell) => pixels[cell * 4 + 3] === 0), scenarioFile).toBe(true);
      expect([...binding.colors.keys()].every((province) => binding.directProvinces?.has(province)), scenarioFile).toBe(true);
      expect([...binding.cities!.entries()].every(([province, city]) => (
        countyIndex.commanderyByName.get(city.commanderyName!) === countyIndex.commanderyByProvince[province]
      )), scenarioFile).toBe(true);
    }
  }, 15_000);

  it('uses the county coordinate as the stable commandery parent across a mixed polygon', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 32, 1, 255,
    ]), 2, 1);

    const countyIndex = buildCountyAdministrativeIndex(
      map,
      [{ col: 1, row: 0 }],
      [{ name: 'A군' }, { name: 'B군' }],
    );

    expect([...countyIndex.commanderyByProvince]).toEqual([1]);
  });

  it('resolves temporal commandery aliases to the same province parent', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
    ]), 1, 1);
    const index = buildProvinceAdministrativeIndex(
      map,
      [{
        id: 'P1', displayName: '감릉현', nameCh: '甘陵縣', administrativeSystem: 'HAN_COMMANDERY',
        kind: 'COUNTY', parentRegionId: 'R1', cityIndex: 0,
        geometryBasis: 'HISTORICAL_SEAT_ADAPTED', confidence: 'REVIEWED',
      }],
      [{
        id: 'R1', displayName: '감릉군', nameCh: '甘陵郡',
        administrativeSystem: 'HAN_COMMANDERY', aliases: ['신성후국'],
      }],
    );

    expect(index.commanderyByName.get('감릉군')).toBe(0);
    expect(index.commanderyByName.get('신성후국')).toBe(0);
  });

  it('reconciles runtime ownership by commandery metadata and never falls back globally', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 32, 2, 255,
      0, 48, 3, 255,
    ]), 3, 1);
    const countyIndex = buildCountyAdministrativeIndex(
      map,
      [{ col: 0, row: 0 }, { col: 1, row: 0 }, { col: 2, row: 0 }],
      [{ name: 'A군' }, { name: 'B군' }, { name: 'C군' }],
    );
    const cities: IsoCityOverlay[] = [{
      ...CHE_OVERLAYS_FIXTURE[0],
      x: 0,
      y: 0,
      commanderyName: 'B군',
    }];

    const binding = bindCompleteProvinceOwnership(
      map,
      cities,
      { cols: 3, rows: 1 },
      { width: 300, height: 1 },
      countyIndex,
    );

    expect(binding.colors.has(0)).toBe(false);
    expect(binding.colors.get(1)).toEqual({ nationId: 1, rgb: [255, 0, 0] });
    expect(binding.colors.has(2)).toBe(false);
    expect(binding.cities?.has(2)).toBe(false);
  });

  it('does not let a city coordinate recolor a province outside its declared commandery', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 32, 2, 255,
    ]), 2, 1);
    const countyIndex = buildCountyAdministrativeIndex(
      map,
      [{ col: 0, row: 0 }, { col: 1, row: 0 }],
      [{ name: 'A군' }, { name: 'B군' }],
    );
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], id: 20, x: 0, y: 0, commanderyName: 'B군' },
      { ...CHE_OVERLAYS_FIXTURE[1], id: 10, x: 100, y: 0, commanderyName: 'B군' },
    ];

    const binding = bindCompleteProvinceOwnership(
      map,
      cities,
      { cols: 2, rows: 1 },
      { width: 200, height: 1 },
      countyIndex,
    );

    expect(binding.colors.has(0)).toBe(false);
    expect(binding.colors.get(1)).toEqual({ nationId: 2, rgb: [0, 0, 255] });
    expect(binding.cities?.has(0)).toBe(false);
    expect(binding.directProvinces?.has(0)).toBe(false);
  });

  it('does not claim the sampled province when a declared commandery cannot be resolved', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
    ]), 1, 1);
    const countyIndex = buildCountyAdministrativeIndex(
      map,
      [{ col: 0, row: 0 }],
      [{ name: 'A군' }],
    );

    const binding = bindCompleteProvinceOwnership(
      map,
      [{ ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0, commanderyName: '미등록군' }],
      { cols: 1, rows: 1 },
      { width: 1, height: 1 },
      countyIndex,
    );

    expect(binding.colors.size).toBe(0);
    expect(binding.cities?.size).toBe(0);
  });

  it('leaves unowned Han and external provinces transparent in the political layer', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255,
      0, 32, 2, 255,
    ]), 2, 1);
    const provinces: ProvinceRecordDto[] = [
      { id: 'P1', displayName: '조선현', nameCh: '', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'HISTORICAL_BOUNDARY', confidence: 'REVIEWED' },
      { id: 'P2', displayName: '국내성', nameCh: '', administrativeSystem: 'GOGURYEO', kind: 'SETTLEMENT', parentRegionId: 'R2', cityIndex: null, geometryBasis: 'HISTORICAL_BOUNDARY', confidence: 'REVIEWED' },
    ];
    const parents: ParentRegionRecordDto[] = [
      { id: 'R1', displayName: '낙랑군', nameCh: '', administrativeSystem: 'HAN_COMMANDERY' },
      { id: 'R2', displayName: '고구려', nameCh: '', administrativeSystem: 'GOGURYEO' },
    ];
    const binding = bindCompleteProvinceOwnership(
      map,
      [],
      { cols: 2, rows: 1 },
      { width: 200, height: 1 },
      buildProvinceAdministrativeIndex(map, provinces, parents),
    );

    expect(binding.colors.size).toBe(0);
    expect([...composeProvincePixels(map, binding)].filter((_, index) => index % 4 === 3)).toEqual([0, 0]);
  });

  it.each([
    [1, '#aBc123', true],
    [0, '#abcdef', false],
    [-1, '#abcdef', false],
    [Number.NaN, '#abcdef', false],
    [Number.POSITIVE_INFINITY, '#abcdef', false],
    [1.5, '#abcdef', false],
    [1, 'red', false],
    [1, '#abcd', false],
    [1, undefined, false],
  ])('strict ownership predicate classifies nation=%s color=%s as %s', (nationId, nationColor, expected) => {
    expect(isOwnedNationVisual(nationId, nationColor)).toBe(expected);
  });

  it('keeps a province neutral after a second and third nation claim', () => {
    const oneProvinceMap = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255, 0, 16, 1, 255,
    ]), 2, 1);
    const conflictingCities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], x: 100, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 33, nationId: 3, nationColor: '#00ff00', x: 0, y: 0 },
    ];

    const binding = bindProvinceOwnership(oneProvinceMap, conflictingCities, { cols: 2, rows: 1 }, { width: 200, height: 1 });

    expect(binding.colors.has(0)).toBe(false);
    expect(binding.conflicts).toEqual([0]);
  });

  it('ignores neutral, non-integer, invalid, out-of-grid, and sea samples', () => {
    const cities: IsoCityOverlay[] = [
      { ...CHE_OVERLAYS_FIXTURE[0], nationId: 0, x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 9, nationId: Number.NaN, x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 10, nationId: Number.POSITIVE_INFINITY, x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 11, nationId: 1.5, x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 12, nationColor: 'red', x: 50, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 13, x: 250, y: 40 },
      { ...CHE_OVERLAYS_FIXTURE[0], id: 14, x: 0, y: 0 },
    ];

    const binding = bindProvinceOwnership(identityMapWithTwoProvinces(), cities, { cols: 4, rows: 3 }, SOURCE);

    expect(binding.colors.size).toBe(0);
    expect(binding.conflicts).toEqual([]);
  });

  it('composes political land as exact opaque nation colors without terrain blending', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 16, 1, 255, 0, 16, 2, 255, 0, 0, 0, 0,
    ]), 3, 1);
    const binding = bindProvinceOwnership(map, [
      { ...CHE_OVERLAYS_FIXTURE[0], x: 0, y: 0 },
      { ...CHE_OVERLAYS_FIXTURE[1], x: 100, y: 0 },
    ], { cols: 3, rows: 1 }, { width: 300, height: 1 });

    expect(Array.from(composeProvincePixels(map, binding))).toEqual([
      255, 0, 0, 255,
      0, 0, 255, 255,
      0, 0, 0, 0,
    ]);
  });

  it('never composes sea or conflicted provinces from an untrusted binding map', () => {
    const map = decodeProvincePixels(new Uint8ClampedArray([
      0, 0, 0, 0, 0, 16, 1, 255,
    ]), 2, 1);
    const binding = {
      colors: new Map([
        [-1, { nationId: 1, rgb: [255, 0, 0] as [number, number, number] }],
        [0, { nationId: 2, rgb: [0, 0, 255] as [number, number, number] }],
      ]),
      conflicts: [0],
    };

    expect(Array.from(composeProvincePixels(map, binding))).toEqual([
      0, 0, 0, 0,
      0, 0, 0, 0,
    ]);
  });
});

describe('province identity image loader', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('fetches, decodes literal pixels, and closes the acquired bitmap', async () => {
    const { fetchMock } = installSuccessfulFetch(REAL_RGB8_PNG);
    const { bitmap, close, createBitmap } = installBitmap(1, 1);
    const drawImage = vi.fn();
    installDecodeCanvas({
      drawImage,
      getImageData: vi.fn().mockReturnValue({
        data: new Uint8ClampedArray([0, 16, 1, 255]),
      }),
    } as unknown as CanvasRenderingContext2D);

    const map = await loadProvinceIdentityMap('/province.png');

    expect(fetchMock).toHaveBeenCalledWith('/province.png');
    expect(createBitmap).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'image/png', size: expect.any(Number) }),
      { colorSpaceConversion: 'none', imageOrientation: 'none', premultiplyAlpha: 'none' },
    );
    expect(drawImage).toHaveBeenCalledWith(bitmap, 0, 0);
    expect(Array.from(map.provinces)).toEqual([0]);
    expect(Array.from(map.commanderies)).toEqual([0]);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('rejects a non-OK response before acquiring a bitmap', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 503 }));
    const createBitmap = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('createImageBitmap', createBitmap);

    await expect(loadProvinceIdentityMap('/missing.png')).rejects.toThrow('province map fetch failed: 503');
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it('rejects mislabeled or non-PNG bytes before acquiring a bitmap', async () => {
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);

    installSuccessfulFetch(provincePng(), 'application/octet-stream');
    await expect(loadProvinceIdentityMap('/mislabeled.png')).rejects.toThrow(/Content-Type/);

    installSuccessfulFetch(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]), 'image/png');
    await expect(loadProvinceIdentityMap('/lossy.png')).rejects.toThrow(/signature/);
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it.each([
    ['16-bit samples', provincePng({ bitDepth: 16 }), /8-bit/],
    ['palette samples', provincePng({ colorType: 3 }), /truecolor RGB/],
    ['alpha samples', provincePng({ colorType: 6 }), /truecolor RGB/],
    ['interlacing', provincePng({ interlace: 1 }), /interlaced/],
  ])('rejects unsupported IHDR contract: %s', async (_label, png, message) => {
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);
    installSuccessfulFetch(png);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(message);
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it.each(['eXIf', 'vpAg'])(
    'rejects non-canonical ancillary PNG chunk %s before decode',
    async (chunk) => {
      const createBitmap = vi.fn();
      vi.stubGlobal('createImageBitmap', createBitmap);
      installSuccessfulFetch(provincePng({ extraChunks: [chunk] }));

      await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/IHDR, IDAT, IEND/);
      expect(createBitmap).not.toHaveBeenCalled();
    },
  );

  it('rejects a corrupted chunk CRC before decode', async () => {
    const png = provincePng();
    png[png.length - 1] ^= 0xff;
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);
    installSuccessfulFetch(png);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/CRC/);
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it.each([
    ['axis', 4097, 1, /axis/],
    ['cell count', 4096, 1025, /cell/],
  ])('rejects an oversized IHDR %s before decode', async (_label, width, height, message) => {
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);
    installSuccessfulFetch(provincePng({ width, height }));

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(message);
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it('rejects an oversized Content-Length before reading the response body', async () => {
    const arrayBuffer = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({
        'Content-Type': 'image/png',
        'Content-Length': String(16 * 1024 * 1024 + 1),
      }),
      arrayBuffer,
    }));
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/Content-Length.*limit/);
    expect(arrayBuffer).not.toHaveBeenCalled();
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it('rejects an oversized response body without Content-Length before decode', async () => {
    const png = new Uint8Array(16 * 1024 * 1024 + 1);
    png.set(provincePng());
    const createBitmap = vi.fn();
    vi.stubGlobal('createImageBitmap', createBitmap);
    installSuccessfulFetch(png);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/byte limit/);
    expect(createBitmap).not.toHaveBeenCalled();
  });

  it('rejects transparent decoded pixels and still closes the bitmap', async () => {
    installSuccessfulFetch(provincePng({ width: 1, height: 1 }));
    const { close } = installBitmap(1, 1);
    installDecodeCanvas({
      drawImage: vi.fn(),
      getImageData: vi.fn().mockReturnValue({ data: new Uint8ClampedArray([0, 16, 1, 254]) }),
    } as unknown as CanvasRenderingContext2D);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/opaque/);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('falls back only when bitmap decode options are unsupported', async () => {
    const close = vi.fn();
    const bitmap = { width: 2, height: 1, close };
    const createBitmap = vi.fn()
      .mockRejectedValueOnce(new TypeError('options unsupported'))
      .mockResolvedValueOnce(bitmap);
    installSuccessfulFetch();
    vi.stubGlobal('createImageBitmap', createBitmap);
    installDecodeCanvas({
      drawImage: vi.fn(),
      getImageData: vi.fn().mockReturnValue({
        data: new Uint8ClampedArray([0, 16, 1, 255, 0, 0, 0, 255]),
      }),
    } as unknown as CanvasRenderingContext2D);

    await loadProvinceIdentityMap('/province.png');

    expect(createBitmap).toHaveBeenNthCalledWith(1, expect.any(Blob), {
      colorSpaceConversion: 'none',
      imageOrientation: 'none',
      premultiplyAlpha: 'none',
    });
    expect(createBitmap).toHaveBeenNthCalledWith(2, expect.any(Blob));
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the bitmap when the decode context is unavailable', async () => {
    installSuccessfulFetch();
    const { close } = installBitmap();
    installDecodeCanvas(null);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow('province decode context unavailable');
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the bitmap when canvas creation throws after acquisition', async () => {
    installSuccessfulFetch();
    const { close } = installBitmap();
    vi.spyOn(document, 'createElement').mockImplementation(() => {
      throw new Error('canvas unavailable');
    });

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow('canvas unavailable');
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the bitmap when reading decoded pixels throws', async () => {
    installSuccessfulFetch();
    const { close } = installBitmap();
    installDecodeCanvas({
      drawImage: vi.fn(),
      getImageData: vi.fn().mockImplementation(() => { throw new Error('tainted canvas'); }),
    } as unknown as CanvasRenderingContext2D);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow('tainted canvas');
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the bitmap when drawing it into the decode canvas throws', async () => {
    installSuccessfulFetch();
    const { close } = installBitmap();
    installDecodeCanvas({
      drawImage: vi.fn().mockImplementation(() => { throw new Error('bitmap draw failed'); }),
      getImageData: vi.fn(),
    } as unknown as CanvasRenderingContext2D);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow('bitmap draw failed');
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the bitmap when pixel decoding rejects an invalid hierarchy', async () => {
    installSuccessfulFetch(provincePng({ width: 1, height: 1 }));
    const { close } = installBitmap(1, 1);
    installDecodeCanvas({
      drawImage: vi.fn(),
      getImageData: vi.fn().mockReturnValue({ data: new Uint8ClampedArray([0, 16, 0, 255]) }),
    } as unknown as CanvasRenderingContext2D);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/hierarchy/);
    expect(close).toHaveBeenCalledTimes(1);
  });
});
