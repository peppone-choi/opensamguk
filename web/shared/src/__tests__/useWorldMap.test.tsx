import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorldTiles } from '../WorldMapCanvas';
import { useWorldMap, type WorldMapPreview } from '../useWorldMap';
import { provinceNameOf, resetProvinceNames } from '../provinceNames';

const mocks = vi.hoisted(() => ({ order: [] as string[], province: vi.fn(), fetch: vi.fn() }));
vi.mock('../provinceMap', async () => {
  const actual = await vi.importActual<typeof import('../provinceMap')>('../provinceMap');
  return { ...actual, loadSharedProvinceIdentityMap: mocks.province };
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
  juns: [{ name: '甲郡', col: 384, row: 334 }], parentRegions: [{ name: '甲郡' }] } as unknown as WorldTiles;

beforeEach(() => {
  resetProvinceNames();
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
  it('paints from preview, pinned terrain and Ju before the province map arrives', async () => {
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return preview; });
    let releaseProvinces!: (map: null) => void;
    mocks.province.mockReset().mockImplementation(() => {
      mocks.order.push('provinces');
      return new Promise((resolve) => { releaseProvinces = resolve; });
    });
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    // 省 지도(수 초 걸리는 래스터)를 기다리지 않고 지형 · 城 으로 먼저 준비된다.
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    if (result.current.kind !== 'ready') throw new Error('not ready');
    expect(mocks.order).toEqual(['preview', 'terrain', 'ju', 'provinces']);
    expect(result.current.provinceMap).toBeNull();
    expect(mocks.province).toHaveBeenCalledWith(expect.stringContaining('/map/provinces?'), SHA);
    releaseProvinces(null);
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

  it('keeps geographic calculations stable when a work badge changes', async () => {
    const loadPreview = vi.fn(async () => preview);
    const { result, rerender } = renderHook(({ works }) => useWorldMap({ loadPreview, works }),
      { initialProps: { works: null as Parameters<typeof useWorldMap>[0]['works'] } });
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    if (result.current.kind !== 'ready') throw new Error('not ready');
    const { markerPositions, provinceCenter, commanderies, legend } = result.current;
    rerender({ works: { status: 'READY', counties: [{ countyId: 7,
      active: { work: 'ROAD', label: '도로', percent: 50 }, completed: [] }] } });
    expect(result.current.kind).toBe('ready');
    if (result.current.kind !== 'ready') throw new Error('not ready');
    expect(result.current.markerPositions).toBe(markerPositions);
    expect(result.current.provinceCenter).toBe(provinceCenter);
    expect(result.current.commanderies).toBe(commanderies);
    expect(result.current.legend).toBe(legend);
    expect(result.current.cities[0].cityBadges).toContainEqual({ kind: 'work', work: 'ROAD',
      label: '도로', phase: 'active', percent: 50 });
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
    // 省 지도가 오면 城 을 제 省 에 앉힌다(그 전에는 투영 좌표 칸).
    await waitFor(() => expect(result.current.kind === 'ready' && result.current.provinceMap).toBeTruthy());
    if (result.current.kind !== 'ready') throw new Error('not ready');
    expect(result.current.markerPositions.get(7)).toEqual({ col: 2, row: 0, provinceId: 1 });
  });

  it('stops at the preview when the new map draws it: terrain only for province names, no Ju or province map', async () => {
    const named = { ...structuredClone(tiles), provinceRecords: [{ id: 'P1', displayName: '갑현' }] };
    mocks.fetch.mockImplementation(async (url: string) => {
      mocks.order.push(url.includes('/terrain?') ? 'terrain' : url);
      return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(named) };
    });
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return { ...preview, topdownBakeId: 'c'.repeat(64) }; });
    const previewOnly = (p: WorldMapPreview) => p.topdownBakeId != null;
    const { result, rerender } = renderHook(({ refreshKey }) => useWorldMap({ loadPreview, refreshKey, previewOnly }),
      { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.kind).toBe('preview'));
    if (result.current.kind !== 'preview') throw new Error('not preview');
    expect(result.current.preview.topdownBakeId).toBe('c'.repeat(64));
    expect(result.current.legend).toEqual([{ nationId: 1, name: '魏', color: '#ff0000', cities: 1 }]);
    // 영지 · 공성 · 조정 화면이 읽는 구역 이름은 새 지도를 거쳐도 채워진다(지형을 이름용으로만 받는다)
    await waitFor(() => expect(provinceNameOf('P1')).toBe('갑현'));
    expect(mocks.fetch.mock.calls[0][0]).toContain(`baseTilesSha256=${SHA}`);
    // 다음 순이 와도 미리보기만 다시 받는다(이름은 이미 안다). 받다 실패하면 받은 판을 둔 채 오류만 단다
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(loadPreview).toHaveBeenCalledTimes(2));
    loadPreview.mockRejectedValueOnce(new Error('순 갱신 실패'));
    rerender({ refreshKey: 2 });
    await waitFor(() => expect(result.current).toMatchObject({ kind: 'preview', refreshError: '순 갱신 실패' }));
    // 세 번째(거절) 미리보기는 흉내가 기록하지 않는다 — 지형은 처음 한 번뿐
    expect(loadPreview).toHaveBeenCalledTimes(3);
    expect(mocks.order).toEqual(['preview', 'terrain', 'preview']);
    expect(mocks.province).not.toHaveBeenCalled();
  });

  it('keeps the preview when the names-only terrain fails', async () => {
    mocks.fetch.mockImplementation(async () => ({ ok: false, status: 503, headers: { get: () => null } }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const loadPreview = vi.fn(async () => ({ ...preview, topdownBakeId: 'c'.repeat(64) }));
    const { result } = renderHook(() => useWorldMap({ loadPreview, previewOnly: () => true }));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current).toMatchObject({ kind: 'preview' });
    expect(result.current.kind === 'preview' && result.current.refreshError).toBeFalsy();
    warn.mockRestore();
  });

  it('still loads the old board when the predicate says no', async () => {
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return preview; });
    const { result } = renderHook(() => useWorldMap({ loadPreview, previewOnly: (p) => p.topdownBakeId != null }));
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    expect(mocks.order).toEqual(['preview', 'terrain', 'ju', 'provinces']);
  });

  it('shows an unsupported board error without requesting another terrain', async () => {
    const loadPreview = vi.fn(async () => ({ ...preview, mapCode: 'old-board' }));
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current).toEqual({ kind: 'unsupported', mapCode: 'old-board' }));
    expect(mocks.fetch).not.toHaveBeenCalled();
  });
});
