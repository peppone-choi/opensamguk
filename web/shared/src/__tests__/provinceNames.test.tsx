import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorldTiles } from '../WorldMapCanvas';
import { provinceNameOf, rememberProvinceNames, useProvinceName } from '../provinceNames';
import { useWorldMap, type WorldMapPreview } from '../useWorldMap';

const mocks = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('../provinceMap', async () => {
  const actual = await vi.importActual<typeof import('../provinceMap')>('../provinceMap');
  return { ...actual, loadSharedProvinceIdentityMap: vi.fn(async () => null) };
});

const SHA = 'c'.repeat(64);
const preview: WorldMapPreview = {
  mapCode: 'han-world-v3', width: 700, height: 610,
  strategicTopology: { worldId: 1, mapCode: 'han-world-v3', topologyRevision: 'test',
    topologyHash: 'd'.repeat(64), baseTilesSha256: SHA, cols: 4, rows: 3 },
  cities: [{ id: 7, name: '甲縣', level: 5, nationId: 1, x: 350, y: 305, state: 0, commanderyName: '甲郡' }],
  nations: [{ id: 1, name: '魏', color: '#ff0000' }],
};
const tiles = {
  _meta: { cols: 4, rows: 3, year: 200, terrainLegend: {} },
  juns: [{ name: '甲郡', col: 1, row: 1 }],
  provinceRecords: [
    { id: 'HAN-P-0001', displayName: '영천 북부', nameCh: '潁川北', administrativeSystem: 'HAN_COMMANDERY',
      kind: 'SPATIAL_PROVINCE', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'test', confidence: 'test' },
    { id: 'HAN-P-0002', displayName: '양적', nameCh: '陽翟', administrativeSystem: 'HAN_COMMANDERY',
      kind: 'COUNTY', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'test', confidence: 'test' },
  ],
} as unknown as WorldTiles;

beforeEach(() => {
  mocks.fetch.mockReset().mockImplementation(async (url: string) => {
    if (url.includes('/terrain?')) {
      return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(tiles) };
    }
    return { ok: false, json: async () => null };
  });
  vi.stubGlobal('fetch', mocks.fetch);
});

describe('구역 이름(useProvinceName)', () => {
  it('지도 훅이 지형을 받기 전에는 모른다 — 이름을 얻으려고 지형을 새로 받지 않는다', () => {
    const { result } = renderHook(() => useProvinceName());
    expect(result.current('HAN-P-0002')).toBeUndefined();
    expect(result.current(1)).toBeUndefined();
    expect(mocks.fetch).not.toHaveBeenCalled();
  });

  it('지도 훅이 지형을 받으면 이미 붙어 있던 화면도 이름을 얻는다(id · 번호)', async () => {
    const names = renderHook(() => useProvinceName());
    expect(names.result.current('HAN-P-0002')).toBeUndefined();
    const map = renderHook(() => useWorldMap({ loadPreview: async () => preview }));
    await waitFor(() => expect(map.result.current.kind).toBe('ready'));
    expect(names.result.current('HAN-P-0002')).toBe('양적');
    expect(names.result.current('HAN-P-0001')).toBe('영천 북부');
    expect(names.result.current(1)).toBe('양적');
    // 지형 요청은 지도 훅의 한 번뿐이다.
    expect(mocks.fetch.mock.calls.filter(([url]) => String(url).includes('/terrain?'))).toHaveLength(1);
  });

  it('모르는 id · 번호는 지어내지 않는다', () => {
    act(() => rememberProvinceNames(tiles, SHA));
    expect(provinceNameOf('HAN-P-9999')).toBeUndefined();
    expect(provinceNameOf(2)).toBeUndefined();
    expect(provinceNameOf(-1)).toBeUndefined();
    expect(provinceNameOf(0.5)).toBeUndefined();
    expect(provinceNameOf(null)).toBeUndefined();
    expect(provinceNameOf(undefined)).toBeUndefined();
  });
});
