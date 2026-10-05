import { render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { CorpsMarker } from '@opensamguk/ui/map/topdown';

// 운영 省 식별 PNG(24.7MB)는 16MiB 상한으로 버려진다 — provinceMap null, 옛 provinceCenter 는 늘 비어 있다.
// 새 지도는 그래도 군단 자리를 bake 개관 격자의 구역 대표 칸에서 구해야 한다(서버 구역 id → bake 번호는 미리보기 provinceOccupancy).
const mocks = vi.hoisted(() => ({ corps: null as readonly CorpsMarker[] | null | undefined, visibility: undefined as unknown }));
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual, topdownScreensEnabled: () => true,
    // bake 구역 0 → (1470, 898), 구역 1 은 개관 격자에 없음
    loadBakeProvinceCenters: async () => [{ col: 1470, row: 898 }, null],
    loadBakePlaces: async () => ({ commanderies: [], cities: [], labels: [] }) };
});
vi.mock('@/components/campaign/WarRoomTopdownMap', () => ({
  default: (props: { corps?: readonly CorpsMarker[]; visibility?: unknown }) => {
    mocks.corps = props.corps; mocks.visibility = props.visibility; return <div data-testid="topdown-map" />;
  },
}));
vi.mock('@/lib/campaign-map', () => ({ CAMPAIGN_MAP_CODE: 'han-world-v3', CAMPAIGN_PROVINCES_URL: '/provinces',
  useCampaignWorldMap: () => ({ kind: 'ready',
    preview: { cities: [{ id: 7, commanderyName: '甲郡' }], topdownBakeId: 'b'.repeat(64),
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'P2', provinceIndex: 1, nationId: 0 }] },
    tiles: { _meta: { cols: 768, rows: 669 } }, tilesSha256: 'a'.repeat(64), provinceMap: null,
    provinceCenter: () => undefined,
    markerPositions: new Map(), cities: [],
    commanderies: [{ no: 1, name: '甲郡', col: 384, row: 334, focusCityId: 7 }],
    sourceSize: { width: 700, height: 610 }, legend: [], administrativeOwnership: undefined }) }));
import WarRoomMap from '@/components/campaign/WarRoomMap';

describe('작전실 새 지도 군단 자리(옛 省 PNG 없이)', () => {
  it('省 PNG 가 버려져도 군단은 bake 대표 칸에 서고, 대표 칸이 없는 구역의 군단은 싣지 않는다', async () => {
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
