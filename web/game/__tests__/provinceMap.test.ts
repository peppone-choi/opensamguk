import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  bindProvinceOwnership,
  composeProvincePixels,
  decodeProvincePixels,
  loadProvinceIdentityMap,
  type IsoCityOverlay,
  type IsoSourceSize,
} from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE } from './fixtures/che-tiles';

const SOURCE: IsoSourceSize = { width: 200, height: 120 };

function installSuccessfulFetch() {
  const blob = new Blob(['png']);
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    blob: vi.fn().mockResolvedValue(blob),
  });
  vi.stubGlobal('fetch', fetchMock);
  return { blob, fetchMock };
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
    const { blob, fetchMock } = installSuccessfulFetch();
    const { bitmap, close, createBitmap } = installBitmap();
    const drawImage = vi.fn();
    installDecodeCanvas({
      drawImage,
      getImageData: vi.fn().mockReturnValue({
        data: new Uint8ClampedArray([0, 16, 1, 255, 0, 0, 0, 255]),
      }),
    } as unknown as CanvasRenderingContext2D);

    const map = await loadProvinceIdentityMap('/province.png');

    expect(fetchMock).toHaveBeenCalledWith('/province.png');
    expect(createBitmap).toHaveBeenCalledWith(blob);
    expect(drawImage).toHaveBeenCalledWith(bitmap, 0, 0);
    expect(Array.from(map.provinces)).toEqual([0, -1]);
    expect(Array.from(map.commanderies)).toEqual([0, -1]);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('rejects a non-OK response before acquiring a bitmap', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 503 });
    const createBitmap = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('createImageBitmap', createBitmap);

    await expect(loadProvinceIdentityMap('/missing.png')).rejects.toThrow('province map fetch failed: 503');
    expect(createBitmap).not.toHaveBeenCalled();
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
    installSuccessfulFetch();
    const { close } = installBitmap(1, 1);
    installDecodeCanvas({
      drawImage: vi.fn(),
      getImageData: vi.fn().mockReturnValue({ data: new Uint8ClampedArray([0, 16, 0, 255]) }),
    } as unknown as CanvasRenderingContext2D);

    await expect(loadProvinceIdentityMap('/province.png')).rejects.toThrow(/hierarchy/);
    expect(close).toHaveBeenCalledTimes(1);
  });
});
