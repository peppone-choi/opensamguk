import { render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { CorpsMarker } from '@opensamguk/ui/map/topdown';

// 군단 자리는 bake 개관 격자의 구역 대표 칸에서 구한다(서버 구역 id → bake 번호는 미리보기 provinceOccupancy).
// 옛 省 식별 PNG(운영 24.7MB, 16MiB 상한으로 버려졌다)는 옛 지도와 함께 지웠다.
const mocks = vi.hoisted(() => ({ corps: null as readonly CorpsMarker[] | null | undefined, visibility: undefined as unknown }));
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    // bake 구역 0 → (1470, 898), 구역 1 은 개관 격자에 없음
    loadBakeProvinceCenters: async () => [{ col: 1470, row: 898 }, null],
    loadBakePlaces: async () => ({ commanderies: [], cities: [], labels: [] }) };
});
vi.mock('@/components/campaign/WarRoomTopdownMap', () => ({
  default: (props: { corps?: readonly CorpsMarker[]; visibility?: unknown }) => {
    mocks.corps = props.corps; mocks.visibility = props.visibility; return <div data-testid="topdown-map" />;
  },
}));
vi.mock('@/lib/campaign-map', () => ({ CAMPAIGN_MAP_CODE: 'han-world-v3',
  useCampaignWorldMap: () => ({ kind: 'preview',
    preview: { cities: [{ id: 7, commanderyName: '甲郡' }], topdownBakeId: 'b'.repeat(64),
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'P2', provinceIndex: 1, nationId: 0 }] },
    legend: [] }) }));
import WarRoomMap from '@/components/campaign/WarRoomMap';

describe('작전실 지도 군단 자리', () => {
  it('군단은 bake 대표 칸에 서고, 대표 칸이 없는 구역의 군단은 싣지 않는다', async () => {
    const visibility = new Map([[1, 'FULL']] as const);
    render(<WarRoomMap homeCityId={7} visibility={visibility}
      corps={[
        { corpsId: 'c1', ownerGeneralId: 1, commanderGeneralId: 1, nationId: 1, provinceId: 'P1', commanderyNo: 1, visibility: 'FULL', own: true },
        { corpsId: 'c2', ownerGeneralId: 2, commanderGeneralId: 2, nationId: 2, provinceId: 'P2', commanderyNo: 1, visibility: 'FULL', own: false },
      ]} />);
    await waitFor(() => expect(mocks.corps?.length).toBeGreaterThan(0));
    expect(mocks.corps).toMatchObject([{ id: 'c1', cell: { col: 1470, row: 898 } }]);
    expect(mocks.corps).toHaveLength(1);
    // 같은 시야를 새 지도 「시야」 층에도 넘긴다(P-W03)
    expect(mocks.visibility).toBe(visibility);
  });
});
