import { render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse, WorldMapResponse } from '@/lib/types';
import { STRATEGIC_BINDING, STRATEGIC_TOPOLOGY } from './fixtures/strategic-topology';

const mocks = vi.hoisted(() => ({
  mapPreview: vi.fn(),
  worldMap: vi.fn(),
  strategicTopology: vi.fn(),
  frontInfo: vi.fn(),
  hwihaWorks: vi.fn(),
  hwihaSieges: vi.fn(),
  hwihaVisibility: vi.fn(),
  hwihaCorps: vi.fn(),
  hwihaScoutOptions: vi.fn(),
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
  fetch: vi.fn(),
}));

vi.mock('@/lib/api', () => ({ api: { mapPreview: mocks.mapPreview, worldMap: mocks.worldMap,
  strategicTopology: mocks.strategicTopology, frontInfo: mocks.frontInfo,
  hwihaWorks: mocks.hwihaWorks, hwihaSieges: mocks.hwihaSieges,
  hwihaVisibility: mocks.hwihaVisibility, hwihaCorps: mocks.hwihaCorps,
  hwihaScoutOptions: mocks.hwihaScoutOptions } }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
    mocks.props = props;
    return <div data-testid="shared-iso-map" />;
  } };
});
import MapViewer, { mapTitleClass, mapTitleTooltip, seasonOf } from '@/components/game/MapViewer';

const MAP: MapPreviewResponse = {
  serverName: '테스트섭', startYear: 200, year: 200, month: 5, turnPhase: 1, turnPhaseText: '상순',
  mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true }],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
};
const WORLD: WorldMapResponse = {
  result: true, version: 4, mapName: 'han-world-v3', startYear: 180, year: 201, month: 7, turnPhase: 3, turnPhaseText: '하순',
  cityList: [[11, 6, 9, 2, 0, 0]], nationList: [[2, '오', '#0000ff', 11]], spyList: {}, shownByGeneralList: [],
  myCity: 11, myNation: 2,
};

beforeEach(() => {
  document.cookie = 'sam_server=; Max-Age=0; path=/';
  mocks.props = null;
  mocks.fetch.mockReset().mockImplementation(async (input: string) =>
    input.includes('/terrain?') ? { ok: true, headers: { get: () => null },
      json: async () => ({ _meta: { cols: 768, rows: 669, year: 200, terrainLegend: {} }, juns: [] }) }
      : { ok: false, status: 404 });
  vi.stubGlobal('fetch', mocks.fetch);
  mocks.mapPreview.mockReset().mockResolvedValue(MAP);
  mocks.worldMap.mockReset().mockResolvedValue(WORLD);
  mocks.strategicTopology.mockReset().mockResolvedValue(STRATEGIC_TOPOLOGY);
  mocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: null } });
  mocks.hwihaWorks.mockReset().mockResolvedValue({ status: 'READY', counties: [] });
  mocks.hwihaSieges.mockReset().mockResolvedValue({ status: 'READY', sieges: [] });
  mocks.hwihaVisibility.mockReset().mockResolvedValue({ status: 'READY', commanderies: [] });
  mocks.hwihaCorps.mockReset().mockResolvedValue({ status: 'READY', corps: [] });
  mocks.hwihaScoutOptions.mockReset().mockResolvedValue({ status: 'READY', options: [] });
  vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {}, clear() {}, key: () => null, length: 0 });
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {} }));
  Object.defineProperty(navigator, 'maxTouchPoints', { configurable: true, value: 0 });
});

describe('MapViewer pure title contracts', () => {
  it('keeps season boundaries', () => {
    expect([1, 3, 4, 6, 7, 9, 10, 12].map(seasonOf)).toEqual([
      'spring', 'spring', 'summer', 'summer', 'fall', 'fall', 'winter', 'winter',
    ]);
  });

  it('keeps opening-year title colors and limit tooltip', () => {
    // 색은 팔레트 클래스로 바뀌었다(구 magenta/orange/yellow) — 개시 3년 경계는 그대로다.
    expect([200, 201, 202, 203].map((year) => mapTitleClass(200, year))).toEqual([
      'map-title--y1', 'map-title--y2', 'map-title--y3', undefined,
    ]);
    expect(mapTitleTooltip(200, 200, 5, 1, {
      maxTechLevel: 12, initialAllowedTechLevel: 1, techLevelIncYear: 5, openingLimitTurns: 36,
    })).toContain('초반제한 기간');
  });
});

describe('MapViewer data props', () => {
  it('loads the pinned terrain once and reuses it until the base byte pin changes', async () => {
    mocks.mapPreview.mockResolvedValue({ ...MAP, strategicTopology: STRATEGIC_BINDING });
    const { rerender } = render(<MapViewer />);
    await waitFor(() => expect(mocks.props?.strategicTopology).toEqual(STRATEGIC_TOPOLOGY));
    const terrainCalls = () => mocks.fetch.mock.calls.filter(([url]) => String(url).includes('/terrain?'));
    expect(terrainCalls()).toHaveLength(1);
    expect(terrainCalls()[0][0]).toContain(`baseTilesSha256=${STRATEGIC_BINDING.baseTilesSha256}`);
    rerender(<MapViewer refreshKey={1} />);
    await waitFor(() => expect(mocks.strategicTopology).toHaveBeenCalledTimes(2));
    expect(terrainCalls()).toHaveLength(1);
    const nextBinding = { ...STRATEGIC_BINDING, baseTilesSha256: 'e'.repeat(64), topologyHash: 'f'.repeat(64) };
    mocks.mapPreview.mockResolvedValue({ ...MAP, strategicTopology: nextBinding });
    mocks.strategicTopology.mockResolvedValue({ ...STRATEGIC_TOPOLOGY, binding: nextBinding });
    rerender(<MapViewer refreshKey={2} />);
    await waitFor(() => expect(mocks.props?.strategicTopology?.binding).toEqual(nextBinding));
    expect(terrainCalls()).toHaveLength(2);
    expect(terrainCalls()[1][0]).toContain(`baseTilesSha256=${nextBinding.baseTilesSha256}`);
  });

  it('immediately hides old control and clears the route binding if a refresh fails', async () => {
    const onBinding = vi.fn();
    mocks.mapPreview.mockResolvedValue({ ...MAP, mapCode: 'han-world-v3', strategicTopology: STRATEGIC_BINDING });
    const { rerender } = render(<MapViewer onStrategicBindingChange={onBinding} />);
    await waitFor(() => expect(mocks.props?.strategicTopology).toEqual(STRATEGIC_TOPOLOGY));
    mocks.mapPreview.mockRejectedValueOnce(new Error('offline'));
    rerender(<MapViewer refreshKey={1} onStrategicBindingChange={onBinding} />);
    expect(mocks.props?.strategicTopology).toBeUndefined();
    await waitFor(() => expect(onBinding).toHaveBeenLastCalledWith(null));
    expect(screen.getByTestId('shared-iso-map')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('수역 데이터를 갱신하지 못했습니다.')).toBeInTheDocument());
  });

  it('drops a topology response if the proxy server cookie changed while it was pending', async () => {
    document.cookie = 'sam_server=pep; path=/';
    let finish!: (value: typeof STRATEGIC_TOPOLOGY) => void;
    mocks.mapPreview.mockResolvedValue({ ...MAP, mapCode: 'han-world-v3', strategicTopology: STRATEGIC_BINDING });
    mocks.strategicTopology.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    render(<MapViewer />);
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    document.cookie = 'sam_server=other; path=/';
    finish(STRATEGIC_TOPOLOGY);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/서버.*변경/));
    expect(mocks.props?.strategicTopology).toBeUndefined();
  });
  it('fetches the matching V3 topology and redacted control without changing map ownership', async () => {
    mocks.mapPreview.mockResolvedValueOnce({ ...MAP, mapCode: 'han-world-v3', strategicTopology: STRATEGIC_BINDING });
    render(<MapViewer />);
    await waitFor(() => expect(mocks.props).toMatchObject({ strategicTopology: STRATEGIC_TOPOLOGY }));
    expect(mocks.props?.cities?.[0].nationColor).toBe('#ff0000');
  });

  it('does not mix mismatched topology with the visible land map', async () => {
    mocks.mapPreview.mockResolvedValueOnce({ ...MAP, mapCode: 'han-world-v3', strategicTopology: STRATEGIC_BINDING });
    mocks.strategicTopology.mockResolvedValueOnce({ ...STRATEGIC_TOPOLOGY,
      binding: { ...STRATEGIC_BINDING, topologyHash: 'd'.repeat(64) } });
    render(<MapViewer />);
    expect(await screen.findByText(/수역.*일치하지/)).toBeInTheDocument();
    expect(mocks.props?.cities?.[0].id).toBe(11);
    expect(mocks.props).not.toHaveProperty('strategicTopology', STRATEGIC_TOPOLOGY);
  });

  it('never loads current control over an explicitly supplied historical map', () => {
    render(<MapViewer mapData={{ ...MAP, mapCode: 'han-world-v3' }} />);
    expect(mocks.strategicTopology).not.toHaveBeenCalled();
  });

  it('mapData skips preview fetch and renders the title above the default 2D map', async () => {
    render(<MapViewer mapData={MAP} />);
    await screen.findByTestId('shared-iso-map');
    expect(screen.getByText('200년 5월 상순')).toBeInTheDocument();
    expect(screen.getByTestId('shared-iso-map')).toBeInTheDocument();
    expect(mocks.mapPreview).not.toHaveBeenCalled();
  });

  it('rejects another map board before requesting terrain', async () => {
    mocks.mapPreview.mockResolvedValueOnce({ ...MAP, mapCode: 'old-board' });
    render(<MapViewer />);
    expect(await screen.findByText('지원하지 않는 지도 판: old-board')).toBeInTheDocument();
    expect(mocks.fetch).not.toHaveBeenCalled();
  });

  it('loads the served terrain and province PNG through the common map hook', async () => {
    render(<MapViewer />);
    await screen.findByTestId('shared-iso-map');
    expect(mocks.props?.mapCode).toBe('han-world-v3');
    expect(mocks.props?.tiles?._meta.cols).toBe(768);
    expect(mocks.props?.markerPositions?.has(11)).toBe(true);
    expect(mocks.props?.terrainUrl).toBeUndefined();
    expect(mocks.fetch.mock.calls.map(([url]) => url)).toContain('/api/game/api/map/provinces?mapCode=han-world-v3');
  });

  it('live mode merges state, owner, supply, capital and my city', async () => {
    render(<MapViewer live />);
    await waitFor(() => expect(mocks.props?.cities?.[0]).toMatchObject({
      id: 11, level: 6, nationId: 2, state: 9, supply: false, isCapital: true,
    }));
    expect(mocks.props?.currentCityId).toBe(11);
  });

  it('requests preview and world in parallel instead of serially (§4 initial loading)', async () => {
    let resolvePreview!: (value: typeof MAP) => void;
    let resolveWorld!: (value: typeof WORLD) => void;
    mocks.mapPreview.mockImplementation(() => new Promise<typeof MAP>((resolve) => { resolvePreview = resolve; }));
    mocks.worldMap.mockImplementation(() => new Promise<typeof WORLD>((resolve) => { resolveWorld = resolve; }));
    render(<MapViewer live />);
    await waitFor(() => expect(mocks.mapPreview).toHaveBeenCalledTimes(1));
    // 미리보기가 끝나기 전에 월드 조회가 이미 나갔어야 한다 — 직렬이면 아직 안 나간다.
    expect(mocks.worldMap).toHaveBeenCalledTimes(1);
    resolveWorld(WORLD);
    resolvePreview(MAP);
    await waitFor(() => expect(mocks.props?.cities?.[0].nationColor).toBe('#0000ff'));
  });

  it('falls back to preview-only when the parallel world request fails', async () => {
    mocks.worldMap.mockRejectedValueOnce(new Error('offline'));
    render(<MapViewer live />);
    await waitFor(() => expect(mocks.props?.cities?.[0]).toMatchObject({ id: 11, nationId: 1 }));
  });

  it('shows nine-kind work and siege details on the default 2D city layer', async () => {
    mocks.frontInfo.mockResolvedValueOnce({ general: { generalId: 7 } });
    mocks.hwihaWorks.mockResolvedValueOnce({ status: 'READY', counties: [{ countyId: 11,
      active: { work: 'ROAD', label: '도로', percent: 35 }, completed: [] }] });
    mocks.hwihaSieges.mockResolvedValueOnce({ status: 'READY', sieges: [{ countyId: 11, status: 'ACTIVE' }] });
    render(<MapViewer live />);
    await waitFor(() => expect(mocks.props?.cities?.[0].cityBadges).toEqual([
      { kind: 'supply', supplied: false },
      { kind: 'work', work: 'ROAD', label: '도로', phase: 'active', percent: 35 },
      { kind: 'siege' },
    ]));
  });

  it('forwards the optional initial focus profile unchanged', async () => {
    render(<MapViewer mapData={MAP} initialFocus="current-city-close" />);
    await screen.findByTestId('shared-iso-map');
    expect(mocks.props?.initialFocus).toBe('current-city-close');
  });

  it('empty and failed previews remain fail-visible', async () => {
    const { unmount } = render(<MapViewer mapData={{ ...MAP, cities: [] }} />);
    expect(await screen.findByText('지도 데이터 준비 중입니다.')).toBeInTheDocument();
    unmount();
    mocks.mapPreview.mockRejectedValueOnce(new Error('offline'));
    render(<MapViewer />);
    expect(await screen.findByText('지도 데이터 준비 중입니다.')).toBeInTheDocument();
  });

  it('keeps the existing canvas visible while refreshKey reloads', async () => {
    const pending = new Promise<MapPreviewResponse>(() => {});
    const { rerender } = render(<MapViewer />);
    await screen.findByTestId('shared-iso-map');
    mocks.mapPreview.mockReturnValueOnce(pending);
    rerender(<MapViewer refreshKey={1} />);
    expect(screen.getByTestId('shared-iso-map')).toBeInTheDocument();
  });
});
