import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanTiles } from '../HanMapCanvas';
import { useWorldMap, type WorldMapPreview } from '../useWorldMap';

const mocks = vi.hoisted(() => ({ order: [] as string[], province: vi.fn(), fetch: vi.fn() }));
vi.mock('../provinceMap', async () => {
  const actual = await vi.importActual<typeof import('../provinceMap')>('../provinceMap');
  return { ...actual, loadProvinceIdentityMap: mocks.province };
});
const SHA = 'a'.repeat(64);
const preview: WorldMapPreview = {
  mapCode: 'han-world-v3', width: 700, height: 610,
  strategicTopology: { worldId: 1, mapCode: 'han-world-v3', topologyRevision: 'test',
    topologyHash: 'b'.repeat(64), baseTilesSha256: SHA, cols: 768, rows: 669 },
  cities: [{ id: 7, name: '甲縣', level: 5, nationId: 1, x: 350, y: 305, state: 0, supply: false,
    commanderyName: '甲郡' }],
  nations: [{ id: 1, name: '魏', color: '#ff0000' }],
};
const tiles = { _meta: { cols: 768, rows: 669, year: 200, terrainLegend: {} },
  juns: [{ name: '甲郡', col: 384, row: 334 }], parentRegions: [{ name: '甲郡' }] } as unknown as HanTiles;

beforeEach(() => {
  mocks.order.length = 0;
  mocks.province.mockReset().mockImplementation(async () => { mocks.order.push('provinces'); return null; });
  mocks.fetch.mockReset().mockImplementation(async (url: string) => {
    if (url.includes('/terrain?')) {
      mocks.order.push('terrain');
      return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(tiles) };
    }
    if (url.includes('/ju?')) {
      mocks.order.push('ju');
      return { ok: true, json: async () => ({ sourceSha256: SHA, juByParent: ['사예'] }) };
    }
    throw new Error(`unexpected URL ${url}`);
  });
  vi.stubGlobal('fetch', mocks.fetch);
});

describe('useWorldMap common served board', () => {
  it('loads preview, pinned terrain, provinces, then Ju and centers the 城 marker', async () => {
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return preview; });
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    if (result.current.kind !== 'ready') throw new Error('not ready');
    expect(mocks.order).toEqual(['preview', 'terrain', 'provinces', 'ju']);
    expect(mocks.fetch.mock.calls[0][0]).toContain(`baseTilesSha256=${SHA}`);
    expect(result.current.tilesSha256).toBe(SHA);
    expect(result.current.tiles.parentRegions?.[0].ju).toBe('사예');
    expect(result.current.markerPositions.get(7)).toEqual({ col: 384, row: 335 });
    expect(result.current.cities[0]).toMatchObject({ mapLabel: '甲縣', cityBadges: [{ kind: 'supply', supplied: false }] });
  });

  it('reuses the pinned terrain during a control refresh', async () => {
    const loadPreview = vi.fn(async () => preview);
    const { result, rerender } = renderHook(({ refreshKey }) => useWorldMap({ loadPreview, refreshKey }),
      { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(loadPreview).toHaveBeenCalledTimes(2));
    expect(mocks.fetch.mock.calls.filter(([url]) => String(url).includes('/terrain?'))).toHaveLength(1);
  });

  it('seats a 城 in its own 省 even when its projected point is in a neighbor', async () => {
    const province = { width: 3, height: 1, provinces: new Int16Array([0, 0, 1]),
      commanderies: new Int16Array([0, 0, 0]), provinceEdges: [], commanderyEdges: [] };
    mocks.province.mockResolvedValue(province);
    mocks.fetch.mockImplementation(async (url: string) => String(url).includes('/terrain?')
      ? { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => ({
        _meta: { cols: 3, rows: 1 }, cities: [{ col: 0, row: 0 }, { col: 2, row: 0 }],
        juns: [{ name: '甲郡', col: 0, row: 0 }],
      }) }
      : { ok: false });
    const loadPreview = vi.fn(async () => ({ ...preview, width: 3, height: 1,
      cities: [{ ...preview.cities[0], x: 0, y: 0, provinceId: 1 }] }));
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    if (result.current.kind !== 'ready') throw new Error('not ready');
    expect(result.current.markerPositions.get(7)).toEqual({ col: 2, row: 0, provinceId: 1 });
  });

  it('shows an unsupported board error without requesting another terrain', async () => {
    const loadPreview = vi.fn(async () => ({ ...preview, mapCode: 'old-board' }));
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current).toEqual({ kind: 'unsupported', mapCode: 'old-board' }));
    expect(mocks.fetch).not.toHaveBeenCalled();
  });
});
