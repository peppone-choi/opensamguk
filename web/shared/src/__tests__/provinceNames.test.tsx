import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorldTiles } from '../map/mapData';
import { provinceNameOf, rememberProvinceNames, useProvinceName } from '../provinceNames';
import { useWorldMap, type WorldMapPreview } from '../useWorldMap';

const mocks = vi.hoisted(() => ({ fetch: vi.fn() }));

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
    { id: 'SUB-200176-a24ca75c3f46', displayName: '영천 북부', nameCh: '潁川北', administrativeSystem: 'HAN_COMMANDERY',
      kind: 'SPATIAL_PROVINCE', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'test', confidence: 'test' },
    { id: '200012', displayName: '양적', nameCh: '陽翟', administrativeSystem: 'HAN_COMMANDERY',
      kind: 'COUNTY', parentRegionId: 'R1', cityIndex: null, geometryBasis: 'test', confidence: 'test' },
  ],
} as unknown as WorldTiles;
// 지도 훅의 effect 는 loadPreview 가 바뀌면 다시 돈다 — 실제 화면처럼 고정 함수를 넘긴다.
const loadPreview = async () => preview;

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
    expect(result.current('200012')).toBeUndefined();
    expect(result.current(1)).toBeUndefined();
    expect(mocks.fetch).not.toHaveBeenCalled();
  });

  it('지도 훅이 지형을 받으면 이미 붙어 있던 화면도 이름을 얻는다(id · 번호)', async () => {
    const names = renderHook(() => useProvinceName());
    const pinned = renderHook(() => useProvinceName(SHA));
    expect(names.result.current('200012')).toBeUndefined();
    renderHook(() => useWorldMap({ loadPreview }));
    await waitFor(() => expect(names.result.current('200012')).toBe('양적'));
    expect(names.result.current('SUB-200176-a24ca75c3f46')).toBe('영천 북부');
    // 번호는 판마다 다르다 — 지문을 준 쪽만 번호로 찾는다.
    expect(names.result.current(1)).toBeUndefined();
    expect(pinned.result.current(1)).toBe('양적');
    // 지형 요청은 지도 훅의 한 번뿐이다.
    expect(mocks.fetch.mock.calls.filter(([url]) => String(url).includes('/terrain?'))).toHaveLength(1);
  });

  it('모르는 id · 번호는 지어내지 않는다', () => {
    act(() => rememberProvinceNames(tiles, SHA));
    expect(provinceNameOf('gc-g9999-001')).toBeUndefined();
    // CHGIS 번호 id(`'200012'`)는 문자열이다 — 숫자 모양 문자열을 번호(순서)로 읽지 않는다.
    expect(provinceNameOf('1', SHA)).toBeUndefined();
    expect(provinceNameOf(2, SHA)).toBeUndefined();
    expect(provinceNameOf(-1, SHA)).toBeUndefined();
    expect(provinceNameOf(0.5, SHA)).toBeUndefined();
    expect(provinceNameOf(null)).toBeUndefined();
    expect(provinceNameOf(undefined)).toBeUndefined();
  });

  it('지문이 다른 판의 이름은 주지 않는다 — 서버를 바꿔도 다른 판 이름을 빌려 오지 않는다', () => {
    const OTHER = 'e'.repeat(64);
    const otherTiles = { ...tiles, provinceRecords: [
      { ...tiles.provinceRecords![1], id: '200012', displayName: '다른 판 양적' },
      { ...tiles.provinceRecords![0], id: 'ss-ancheng', displayName: '다른 판에만' },
    ] } as WorldTiles;
    act(() => rememberProvinceNames(tiles, SHA));
    act(() => rememberProvinceNames(otherTiles, OTHER));
    // 지문을 주면 그 판의 이름표만 본다.
    expect(provinceNameOf('200012', SHA)).toBe('양적');
    expect(provinceNameOf('200012', OTHER)).toBe('다른 판 양적');
    expect(provinceNameOf(0, SHA)).toBe('영천 북부');
    expect(provinceNameOf(0, OTHER)).toBe('다른 판 양적');
    expect(provinceNameOf('ss-ancheng', SHA)).toBeUndefined();
    // 받은 적 없는 지문은 모른다.
    expect(provinceNameOf('200012', 'f'.repeat(64))).toBeUndefined();
    // 지문을 모르면 가장 최근에 받은 판에서 id 로만 찾는다.
    expect(provinceNameOf('ss-ancheng')).toBe('다른 판에만');
  });

  it('취소된 요청(서버 교체)의 지형은 이름표에 적지 않는다', async () => {
    let releaseFirst!: () => void;
    const firstHeld = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const firstTiles = { ...tiles, provinceRecords: [
      { ...tiles.provinceRecords![0], id: 'KOR-X065-X028', displayName: '옛 서버에만' },
    ] } as WorldTiles;
    let terrainCalls = 0;
    let firstTilesRead = false;
    mocks.fetch.mockImplementation(async (url: string) => {
      if (url.includes('/terrain?')) {
        terrainCalls += 1;
        if (terrainCalls === 1) {
          await firstHeld;
          return {
            ok: true,
            headers: { get: () => `"sha256-${'1'.repeat(64)}"` },
            json: async () => { firstTilesRead = true; return structuredClone(firstTiles); },
          };
        }
        return { ok: true, headers: { get: () => `"sha256-${SHA}"` }, json: async () => structuredClone(tiles) };
      }
      return { ok: false, json: async () => null };
    });
    const { rerender, result } = renderHook(({ serverId }) => useWorldMap({ loadPreview, serverId }),
      { initialProps: { serverId: 'old' } });
    await waitFor(() => expect(terrainCalls).toBe(1));
    rerender({ serverId: 'new' });
    expect(result.current.kind).toBe('preview');
    await waitFor(() => expect(provinceNameOf('200012')).toBe('양적'));
    act(() => { releaseFirst(); });
    // 옛 요청이 지형을 읽는 데까지 갔는지 본다 — 가지도 않았는데 초록이면 공허한 시험이다.
    await waitFor(() => expect(firstTilesRead).toBe(true));
    // abort 검사 · 등록은 json() 뒤 마이크로태스크에서 돈다. 몇 번 도는지 세지 않고 매크로태스크 경계 하나로 다 흘려 보낸다.
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(provinceNameOf('KOR-X065-X028')).toBeUndefined();
    expect(provinceNameOf('200012')).toBe('양적');
  });
});
