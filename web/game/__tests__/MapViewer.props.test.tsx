import { render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse, WorldMapResponse } from '@/lib/types';

const mocks = vi.hoisted(() => ({
  mapPreview: vi.fn(),
  worldMap: vi.fn(),
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
}));

vi.mock('@/lib/api', () => ({ api: { mapPreview: mocks.mapPreview, worldMap: mocks.worldMap } }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
    mocks.props = props;
    return <div data-testid="shared-iso-map" />;
  } };
});

import MapViewer, { mapTitleColor, mapTitleTooltip, seasonOf } from '@/components/game/MapViewer';

const MAP: MapPreviewResponse = {
  serverName: '테스트섭', startYear: 200, year: 200, month: 5, turnPhase: 1, turnPhaseText: '상순',
  mapCode: 'han', width: 700, height: 610,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true }],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
};
const WORLD: WorldMapResponse = {
  result: true, version: 4, mapName: 'han', startYear: 180, year: 201, month: 7, turnPhase: 3, turnPhaseText: '하순',
  cityList: [[11, 6, 9, 2, 0, 0]], nationList: [[2, '오', '#0000ff', 11]], spyList: {}, shownByGeneralList: [],
  myCity: 11, myNation: 2,
};

beforeEach(() => {
  mocks.props = null;
  mocks.mapPreview.mockReset().mockResolvedValue(MAP);
  mocks.worldMap.mockReset().mockResolvedValue(WORLD);
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
    expect([200, 201, 202, 203].map((year) => mapTitleColor(200, year))).toEqual([
      'magenta', 'orange', 'yellow', undefined,
    ]);
    expect(mapTitleTooltip(200, 200, 5, 1, {
      maxTechLevel: 12, initialAllowedTechLevel: 1, techLevelIncYear: 5, openingLimitTurns: 36,
    })).toContain('초반제한 기간');
  });
});

describe('MapViewer data props', () => {
  it('mapData skips self-fetch and renders the title above the shared canvas', () => {
    render(<MapViewer mapData={MAP} />);
    expect(screen.getByText('200년 5월 상순')).toBeInTheDocument();
    expect(screen.getByTestId('shared-iso-map')).toBeInTheDocument();
    expect(mocks.mapPreview).not.toHaveBeenCalled();
  });

  it('self-fetch loads preview data', async () => {
    const mapCode = 'ha n&?';
    mocks.mapPreview.mockResolvedValueOnce({ ...MAP, mapCode });
    render(<MapViewer />);
    await screen.findByTestId('shared-iso-map');
    expect(mocks.mapPreview).toHaveBeenCalledTimes(1);
    expect(mocks.props?.mapCode).toBe(mapCode);
    const provinceUrl = typeof mocks.props?.provinceUrl === 'function'
      ? mocks.props.provinceUrl(mapCode)
      : mocks.props?.provinceUrl;
    expect(provinceUrl).toBe('/api/game/api/map/provinces?mapCode=ha%20n%26%3F');
  });

  it('live mode merges state, owner, supply, capital and my city', async () => {
    render(<MapViewer live />);
    await waitFor(() => expect(mocks.props?.cities?.[0].nationColor).toBe('#0000ff'));
    expect(mocks.props?.cities?.[0]).toMatchObject({ level: 6, state: 9, supply: false, isCapital: true });
    expect(mocks.props?.currentCityId).toBe(11);
  });

  it('forwards the optional initial focus profile unchanged', () => {
    render(<MapViewer mapData={MAP} initialFocus="current-city-close" />);
    expect(mocks.props?.initialFocus).toBe('current-city-close');
  });

  it('empty and failed previews remain fail-visible', async () => {
    const { unmount } = render(<MapViewer mapData={{ ...MAP, cities: [] }} />);
    expect(screen.getByText('지도 데이터 준비 중입니다.')).toBeInTheDocument();
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
