import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { WorldMapCanvas as WorldMapCanvasType } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({ props: null as ComponentProps<typeof WorldMapCanvasType> | null, refreshKey: null as unknown }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, WorldMapCanvas: (props: ComponentProps<typeof WorldMapCanvasType>) => {
    mocks.props = props; return <div data-testid="war-map">
      <button type="button" onClick={() => props.onCityHover?.(props.cities![0], { x: 1, y: 2 })}>城 얹기</button>
    </div>;
  } };
});
vi.mock('@/lib/campaign-map', () => ({ CAMPAIGN_MAP_CODE: 'han-world-v3', CAMPAIGN_PROVINCES_URL: '/provinces',
  useCampaignWorldMap: (refreshKey: unknown) => { mocks.refreshKey = refreshKey; return { kind: 'ready', preview: { cities: [{ id: 7, commanderyName: '甲郡' }] },
    tiles: { _meta: { cols: 768, rows: 669 } }, tilesSha256: 'a'.repeat(64), provinceMap: null,
    provinceCenter: (id: string) => id === 'P1' ? { col: 10, row: 20 } : undefined,
    markerPositions: new Map([[7, { col: 384, row: 334 }]]),
    cities: [{ id: 7, name: '甲縣', commanderyName: '甲郡', mapLabel: '甲縣', level: 5, nationId: 1, x: 350, y: 305 }],
    commanderies: [{ no: 1, name: '甲郡', col: 384, row: 334, focusCityId: 7 },
      { no: 2, name: '乙郡', col: 420, row: 334, focusCityId: 8 }],
    sourceSize: { width: 700, height: 610 }, legend: [], administrativeOwnership: undefined }; } }));
import WarRoomMap from '@/components/campaign/WarRoomMap';

describe('WarRoomMap unified map props', () => {
  it('passes the served cells, centered 城, visibility, and projected corps', () => {
    render(<WarRoomMap refreshKey={9} homeCityId={7} visibility={new Map([[1, 'INTEL']])}
      corps={[{ corpsId: 'c1', ownerGeneralId: 1, commanderGeneralId: 1, nationId: 1,
        provinceId: 'P1', commanderyNo: 1, visibility: 'INTEL', own: false }]} />);
    expect(screen.getByTestId('war-map')).toBeInTheDocument();
    expect(mocks.refreshKey).toBe(9);
    expect(mocks.props?.tiles?._meta.cols).toBe(768);
    expect(mocks.props?.markerPositions?.get(7)).toEqual({ col: 384, row: 334 });
    expect(mocks.props?.cities?.[0].mapLabel).toBe('甲縣');
    expect(mocks.props?.commanderyVisibility?.get(1)).toBe('INTEL');
    expect(mocks.props?.fogMode).toBe('dim');
    expect(mocks.props?.corps).toMatchObject([{ id: 'c1', stale: true, col: 10, row: 20 }]);
    fireEvent.click(screen.getByRole('button', { name: '城 얹기' }));
    expect(screen.getByRole('status')).toHaveTextContent('甲郡 甲縣');
  });

  it('keeps 「내 위치」 on the home 城 while the camera visits another 郡', () => {
    render(<WarRoomMap homeCityId={7} visibility={null} />);
    expect(mocks.props).toMatchObject({ currentCityId: 7, cameraFocusCityId: 7 });
    fireEvent.click(screen.getByRole('button', { name: /乙郡/ }));
    // 다른 郡 으로 옮겨 보아도 표지는 내 城 에 남고, 카메라만 그 郡 치소로 간다.
    expect(mocks.props).toMatchObject({ currentCityId: 7, cameraFocusCityId: 8 });
  });

  it('fill — 작전실 재배치(P-W01): 패널 · 「천하 형세」 머리 · 밑 범례 줄 없이 상자를 채운다, 안 넘기면 그대로', () => {
    const { unmount } = render(<WarRoomMap fill homeCityId={7} visibility={null} />);
    expect(screen.getByTestId('war-room-map-fill')).toBeInTheDocument();
    expect(screen.queryByText('천하 형세')).toBeNull();
    expect(screen.queryByText('무주')).toBeNull();
    expect(mocks.props?.style).toMatchObject({ width: '100%', height: '100%' });
    unmount();
    render(<WarRoomMap homeCityId={7} visibility={null} />);
    expect(screen.getByText('천하 형세')).toBeInTheDocument();
    expect(mocks.props?.style).toMatchObject({ height: 560 });
  });

  it('fill — 郡 정보 줄(시야 · 「첩보 보내기」)은 지도 위 겹층, 틀이 준 자리(--commandery-info-*)를 비킨다 · 패널이면 지도 아래 흐름(#1232 리뷰)', () => {
    const onScout = vi.fn();
    const { unmount } = render(<WarRoomMap fill homeCityId={7} visibility={new Map([[1, 'INTEL']])} onScout={onScout} />);
    const scout = screen.getByRole('button', { name: '첩보 보내기' });
    const row = scout.parentElement!;
    expect(row).toContainElement(screen.getByTestId('commandery-focus'));
    expect(row.style.position).toBe('absolute');
    expect(row.style.bottom).toBe('var(--commandery-info-bottom, 8px)');
    expect(row.style.right).toBe('var(--commandery-info-right, var(--battlefield-control-right, 8px))');
    // 띠는 누르기를 지나보내고(핀치 · 끌기가 지도에 닿는다) 「첩보 보내기」만 받는다(#1232 CI 핀치)
    expect(row.style.pointerEvents).toBe('none');
    expect(scout.style.pointerEvents).toBe('auto');
    fireEvent.click(scout);
    expect(onScout).toHaveBeenCalledWith(1);
    unmount();
    render(<WarRoomMap homeCityId={7} visibility={new Map([[1, 'INTEL']])} onScout={onScout} />);
    expect(screen.getByRole('button', { name: '첩보 보내기' }).parentElement!.style.position).toBe('');
  });

  it('draws no 「내 위치」 when the home 城 is unknown', () => {
    render(<WarRoomMap homeCityId={null} visibility={null} />);
    expect(mocks.props?.currentCityId).toBeUndefined();
    expect(mocks.props?.cameraFocusCityId).toBe(7);
  });
});
