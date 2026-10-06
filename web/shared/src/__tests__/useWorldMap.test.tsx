import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorldTiles } from '../map/mapData';
import { useWorldMap, type WorldMapPreview } from '../useWorldMap';
import { provinceNameOf, resetProvinceNames } from '../provinceNames';

const mocks = vi.hoisted(() => ({ order: [] as string[], fetch: vi.fn() }));
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
  mocks.fetch.mockReset().mockImplementation(async (url: string) => {
    if (url.includes('/terrain?')) {
      mocks.order.push('terrain');
      return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(tiles) };
    }
    throw new Error(`unexpected URL ${url}`);
  });
  vi.stubGlobal('fetch', mocks.fetch);
});

describe('useWorldMap common served board', () => {
  it('stops at the preview: terrain only for province names, no Ju or province map(옛 지도판은 지웠다, M2-9)', async () => {
    const named = { ...structuredClone(tiles), provinceRecords: [{ id: 'P1', displayName: '갑현' }] };
    mocks.fetch.mockImplementation(async (url: string) => {
      mocks.order.push(url.includes('/terrain?') ? 'terrain' : url);
      return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(named) };
    });
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return { ...preview, topdownBakeId: 'c'.repeat(64) }; });
    const { result, rerender } = renderHook(({ refreshKey }) => useWorldMap({ loadPreview, refreshKey }),
      { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.kind).toBe('preview'));
    if (result.current.kind !== 'preview') throw new Error('not preview');
    expect(result.current.preview.topdownBakeId).toBe('c'.repeat(64));
    expect(result.current.legend).toEqual([{ nationId: 1, name: '魏', color: '#ff0000', cities: 1 }]);
    // 영지 · 공성 · 조정 화면이 읽는 구역 이름은 채워진다(지형을 이름용으로만 받는다 — K4-21 전까지, D113)
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
  });

  it('keeps the preview when the names-only terrain fails', async () => {
    mocks.fetch.mockImplementation(async () => ({ ok: false, status: 503, headers: { get: () => null } }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const loadPreview = vi.fn(async () => ({ ...preview, topdownBakeId: 'c'.repeat(64) }));
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current).toMatchObject({ kind: 'preview' });
    expect(result.current.kind === 'preview' && result.current.refreshError).toBeFalsy();
    warn.mockRestore();
  });

  it('bakeId 가 없는 서버도 미리보기에서 멈춘다 — 옛 지도판 몫(州 색인 · 省 그림)을 청하지 않는다(화면은 「지도를 준비 중입니다」)', async () => {
    const loadPreview = vi.fn(async () => { mocks.order.push('preview'); return preview; });
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current.kind).toBe('preview'));
    await waitFor(() => expect(mocks.order).toEqual(['preview', 'terrain']));
    expect(mocks.fetch.mock.calls.map(([url]) => String(url)).filter((url) => !url.includes('/terrain?'))).toEqual([]);
  });

  it('shows an unsupported board error without requesting another terrain', async () => {
    const loadPreview = vi.fn(async () => ({ ...preview, mapCode: 'old-board' }));
    const { result } = renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(result.current).toEqual({ kind: 'unsupported', mapCode: 'old-board' }));
    expect(mocks.fetch).not.toHaveBeenCalled();
  });
});
