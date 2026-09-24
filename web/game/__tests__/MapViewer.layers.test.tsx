import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse } from '@/lib/types';

const mocks = vi.hoisted(() => ({
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
  frontInfo: vi.fn(), hwihaVisibility: vi.fn(), hwihaCorps: vi.fn(), hwihaWorks: vi.fn(),
  hwihaSieges: vi.fn(), hwihaScoutOptions: vi.fn(), strategicTopology: vi.fn(),
}));
const PREVIEW: MapPreviewResponse = {
  serverName: '테스트', year: 200, month: 5, mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [{ id: 7, name: '甲縣', level: 5, nationId: 1, x: 350, y: 305, state: 0, supply: true,
    isCapital: false, commanderyName: '甲郡' },
  { id: 8, name: '乙縣', level: 5, nationId: 1, x: 360, y: 305, state: 0, supply: true,
    isCapital: false, commanderyName: '乙郡' }],
  nations: [{ id: 1, name: '魏', color: '#ff0000' }],
};
vi.mock('@/lib/api', () => ({ api: {
  frontInfo: mocks.frontInfo, hwihaVisibility: mocks.hwihaVisibility, hwihaCorps: mocks.hwihaCorps,
  hwihaWorks: mocks.hwihaWorks, hwihaSieges: mocks.hwihaSieges,
  hwihaScoutOptions: mocks.hwihaScoutOptions, strategicTopology: mocks.strategicTopology,
} }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual,
    useWorldMap: () => ({ kind: 'ready' as const, preview: PREVIEW,
      tiles: { _meta: { cols: 768, rows: 669 } }, tilesSha256: 'test', provinceMap: null,
      provinceCenter: (id: string) => id === 'P1' ? { col: 10, row: 20 } : undefined,
      cities: actual.buildWorldCities(PREVIEW), markerPositions: new Map([[7, { col: 384, row: 334 }]]),
      commanderies: [{ no: 1, name: '甲郡', col: 384, row: 334, focusCityId: 7 },
        { no: 2, name: '乙郡', col: 394, row: 334, focusCityId: 8 }],
      sourceSize: { width: 700, height: 610 }, administrativeOwnership: undefined }),
    HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
      mocks.props = props; return <div data-testid="main-map" />;
    },
  };
});
import MapViewer from '@/components/game/MapViewer';

beforeEach(() => {
  mocks.props = null;
  mocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: 7 } });
  mocks.hwihaVisibility.mockReset().mockResolvedValue({ status: 'READY', commanderies: [{ no: 1, tier: 'INTEL', ageTurns: 2 }] });
  mocks.hwihaCorps.mockReset().mockResolvedValue({ status: 'READY', corps: [
    { corpsId: 'seen', ownerGeneralId: 7, commanderGeneralId: 7, nationId: 1, provinceId: 'P1', commanderyNo: 1, visibility: 'INTEL', own: true },
    { corpsId: 'hidden', ownerGeneralId: 8, commanderGeneralId: 8, nationId: 2, provinceId: 'P1', commanderyNo: 2, visibility: 'FOG', own: false },
  ] });
  mocks.hwihaWorks.mockReset().mockResolvedValue({ status: 'READY', counties: [] });
  mocks.hwihaSieges.mockReset().mockResolvedValue({ status: 'READY', sieges: [] });
  mocks.hwihaScoutOptions.mockReset().mockResolvedValue({ status: 'READY', options: [] });
  mocks.strategicTopology.mockReset().mockRejectedValue(new Error('no topology in fixture'));
  vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {}, clear() {}, key: () => null, length: 0 });
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {} }));
});

describe('MapViewer Hwiha layers', () => {
  it('passes projected corps and dim visibility into the main war room without hidden corps', async () => {
    render(<MapViewer mapData={PREVIEW} hwihaLayers="full" currentCityId={7} />);
    await waitFor(() => expect(mocks.props?.commanderyVisibility?.get(1)).toBe('INTEL'));
    expect(mocks.props?.fogMode).toBe('dim');
    expect(mocks.props?.corps).toMatchObject([{ id: 'seen', stale: true }]);
    expect(mocks.props?.markerPositions?.get(7)).toEqual({ col: 384, row: 334 });
    expect(screen.getByText('2순 전 정보')).toBeInTheDocument();
    expect(mocks.hwihaCorps).toHaveBeenCalledWith(7, expect.any(AbortSignal));
  });

  it('keeps the map and clears only the rejected Hwiha layers', async () => {
    mocks.hwihaVisibility.mockResolvedValue({ status: 'WRONG_RULE_PROFILE' });
    mocks.hwihaCorps.mockRejectedValue(new Error('unavailable'));
    render(<MapViewer mapData={PREVIEW} hwihaLayers="full" />);
    expect(screen.getByTestId('main-map')).toBeInTheDocument();
    expect(await screen.findByText('시야를 불러오지 못해 안개 레이어를 비웠습니다.')).toBeInTheDocument();
    expect(screen.getByText('군단을 불러오지 못해 군단 레이어를 비웠습니다.')).toBeInTheDocument();
    expect(mocks.props?.commanderyVisibility).toBeNull();
    expect(mocks.props?.corps).toEqual([]);
  });

  it('moves the camera to a neighboring 郡 without moving the current 城 marker', async () => {
    render(<MapViewer mapData={PREVIEW} hwihaLayers="full" currentCityId={7} />);
    await waitFor(() => expect(screen.getByRole('button', { name: '동 — 乙郡' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '동 — 乙郡' }));
    expect(mocks.props?.currentCityId).toBe(7);
    expect(mocks.props?.cameraFocusCityId).toBe(8);
  });
});
