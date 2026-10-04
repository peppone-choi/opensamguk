import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { PlacesData } from '@opensamguk/ui/map/topdown';

// 실지도 결함 5: 새 지도면 훅이 미리보기에서 멈춘다(kind 'preview' — 옛 지형 · 省 PNG 없음).
// 지도는 장소 표를 기다리지 않고 바로 서고(늦은 장소 표 규칙은 지도가 지킨다), 시야 · 첩보 줄의 郡은 bake 장소 표에서 온다.
const mocks = vi.hoisted(() => ({
  map: null as { focusCityId?: number | null; ariaLabel?: string } | null,
  releasePlaces: null as ((places: unknown) => void) | null,
}));
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual, topdownScreensEnabled: () => true,
    loadBakeProvinceCenters: async () => [],
    loadBakePlaces: () => new Promise((resolve) => { mocks.releasePlaces = resolve; }) };
});
vi.mock('@/components/campaign/WarRoomTopdownMap', () => ({
  default: (props: { focusCityId?: number | null; ariaLabel?: string }) => { mocks.map = props; return <div data-testid="topdown-map" />; },
}));
vi.mock('@/lib/campaign-map', () => ({ CAMPAIGN_MAP_CODE: 'han-world-v3', CAMPAIGN_PROVINCES_URL: '/provinces',
  useCampaignWorldMap: () => ({ kind: 'preview', legend: [{ nationId: 1, name: '위', color: '#b03a2e', cities: 2 }],
    preview: { topdownBakeId: 'b'.repeat(64), cities: [
      { id: 3, commanderyName: '甲郡', isCommanderySeat: true }, { id: 7, commanderyName: '乙郡', isCommanderySeat: true }] } }) }));
import WarRoomMap from '@/components/campaign/WarRoomMap';

const PLACES = {
  commanderies: [{ id: 'C0', name: '甲郡', kind: 'COMMANDERY', seatCityId: 3 }, { id: 'C1', name: '乙郡', kind: 'COMMANDERY', seatCityId: 7 }],
  cities: [{ id: 3, cell: [10, 10] }, { id: 7, cell: [20, 20] }],
  labels: [{ id: 'commandery:1', kind: 'commandery', anchor: [22, 21] }],
} as unknown as PlacesData;

describe('작전실 새 지도: 미리보기만으로 서고 郡 줄은 bake 장소 표에서', () => {
  it('장소 표 전에도 내 城 초점으로 지도가 서고, 장소 표가 오면 내 城의 郡 · 시야 줄이 붙는다', async () => {
    render(<WarRoomMap homeCityId={7} visibility={new Map([[1, 'INTEL']])} />);
    expect(screen.getByTestId('topdown-map')).toBeInTheDocument();
    expect(mocks.map).toMatchObject({ focusCityId: 7, ariaLabel: '천하 형세' });
    expect(screen.queryByTestId('commandery-focus')).toBeNull();
    expect(screen.getByText('위')).toBeInTheDocument();
    await act(async () => { mocks.releasePlaces?.(PLACES); });
    // 郡 번호 = 장소 표 자리(1) — 서버 시야 지도(번호 1 = 첩보)와 같은 번호로 읽는다
    expect(screen.getByTestId('commandery-focus')).toHaveTextContent('乙郡');
    expect(screen.getByText('지금 여기')).toBeInTheDocument();
    expect(screen.getByText('첩보 시야')).toBeInTheDocument();
    expect(mocks.map).toMatchObject({ focusCityId: 7, ariaLabel: '천하 형세 — 乙郡' });
  });
});
