import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { PlacesData, SupplyMapLine } from '@opensamguk/ui/map/topdown';
import type { SupplyLinesRead } from '@/lib/use-supply-lines';

// P-W03 「보급선」 층(계약판 K4-06): 서버가 연결을 주면 진짜 층 · 범례 · 끊긴 곳, 안 주면 「서버 대기 · K4-06」, 읽기 실패면 「불러오지 못함」. 지도 그리기(GL)는 가짜.
const mocks = vi.hoisted(() => ({ supply: undefined as readonly SupplyMapLine[] | undefined }));
const PLACES = {
  schemaVersion: 1, provinceCount: 1, counties: [], ju: [], passes: [], labels: [], commanderies: [], provinceAdmin: [[0, 0, 0]],
  cities: [{ id: 3, name: '아랫현' }],
} as unknown as PlacesData;
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    loadBakePlaces: async () => PLACES,
    TopdownMap: (props: { supply?: readonly SupplyMapLine[] }) => { mocks.supply = props.supply; return <div data-testid="topdown-map" />; } };
});
import WarRoomTopdownMap from '@/components/campaign/WarRoomTopdownMap';
import type { MapPreviewResponse } from '@/lib/types';

const PREVIEW = { mapCode: 'han-world-v3', cities: [{ id: 1, name: '선무' }], nations: [], provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 0 }] } as unknown as MapPreviewResponse;
const draw = (supply?: SupplyLinesRead) => render(<WarRoomTopdownMap source={{ bakeUrl: '/bake', kitUrl: '/kit' }} preview={PREVIEW}
  homeCityId={null} focusCityId={null} ariaLabel="천하 형세" supply={supply} />);
const openLayers = () => fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));

describe('작전실 새 지도 「보급선」 층(P-W03 · K4-06)', () => {
  it('서버가 연결을 안 주면(lines null) 「서버 대기 · K4-06」 줄, 읽기 실패면 「불러오지 못함」', () => {
    const { unmount } = draw({ lines: null, failed: false });
    openLayers();
    expect(screen.getByRole('region', { name: '지도 레이어' }).querySelector('[data-pending-layer="supply"]')).toHaveTextContent('보급선서버 대기 · K4-06');
    expect(screen.queryByRole('button', { name: /보급선/ })).toBeNull();
    unmount();
    draw({ lines: null, failed: true });
    openLayers();
    expect(screen.getByRole('region', { name: '지도 레이어' }).querySelector('[data-pending-layer="supply"]')).toHaveTextContent('보급선불러오지 못함');
  });

  it('연결을 주면 켜고 끄는 층 · 지도에는 두 城과 상태만 · 범례에 선 둘과 끊긴 곳(이름 미리보기 → bake, 까닭 서버 문구)', async () => {
    draw({ failed: false, lines: [
      { fromCityId: 1, toCityId: 2, via: 'ROAD', state: 'OPEN', cutReason: null },
      { fromCityId: 1, toCityId: 3, via: 'WATER', state: 'CUT', cutReason: '나루를 잃었다' },
    ] });
    await waitFor(() => expect(mocks.supply).toEqual([{ fromCityId: 1, toCityId: 2, state: 'OPEN' }, { fromCityId: 1, toCityId: 3, state: 'CUT' }]));
    openLayers();
    const panel = screen.getByRole('region', { name: '지도 레이어' });
    expect(panel.querySelector('[data-pending-layer="supply"]')).toBeNull();
    expect(screen.getByRole('button', { name: /보급선/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '범례' }));
    const legend = screen.getByRole('region', { name: '범례' });
    expect(legend).toHaveTextContent('보급 연결');
    expect(legend).toHaveTextContent('보급 끊김');
    await waitFor(() => expect(screen.getByRole('list', { name: '끊긴 보급' })).toHaveTextContent('선무 – 아랫현 · 나루를 잃었다'));
  });
});
