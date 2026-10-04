import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MapLayers, PlacesData, WorldState } from '@opensamguk/ui/map/topdown';

// P-W03 「시야」 층: 서버 郡 시야(/api/visibility)를 bake 장소 표의 구역 → 郡 번호로 지도에 넘긴다. 지도 그리기(GL)는 가짜.
const mocks = vi.hoisted(() => ({ world: undefined as WorldState | undefined, layers: undefined as MapLayers | undefined }));
const PLACES = {
  schemaVersion: 1, provinceCount: 3, counties: [], ju: [], passes: [], cities: [], labels: [],
  // 구역 0 → 장소 표 郡 0(서버 번호 5), 구역 1 → 郡 1(번호 없음 = 자리 1), 구역 2 → 모름
  provinceAdmin: [[0, 0, 0], [1, 1, 0], [-1, -1, -1]],
  commanderies: [
    { id: 'PARENT-0005', name: '甲郡', kind: 'COMMANDERY', seatCityId: null, commanderyNo: 5 },
    { id: 'PARENT-0001', name: '乙郡', kind: 'COMMANDERY', seatCityId: null },
  ],
} as unknown as PlacesData;
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    loadBakePlaces: async () => PLACES,
    TopdownMap: (props: { world?: WorldState; layers?: MapLayers }) => {
      mocks.world = props.world; mocks.layers = props.layers; return <div data-testid="topdown-map" />;
    } };
});
import WarRoomTopdownMap from '@/components/campaign/WarRoomTopdownMap';
import type { MapPreviewResponse } from '@/lib/types';

const PREVIEW = { mapCode: 'han-world-v3', cities: [], nations: [{ id: 1, name: '조조', color: '#4a6fa5' }],
  provinceOccupancy: [0, 1, 2].map((provinceIndex) => ({ provinceRecordId: `P${provinceIndex}`, provinceIndex, nationId: 1 })),
} as unknown as MapPreviewResponse;
const draw = (visibility?: ReadonlyMap<number, 'FULL' | 'INTEL' | 'FOG'> | null) =>
  render(<WarRoomTopdownMap source={{ bakeUrl: '/bake', kitUrl: '/kit' }} preview={PREVIEW} homeCityId={null}
    focusCityId={null} ariaLabel="천하 형세" visibility={visibility} />);

describe('작전실 새 지도 「시야」 층(P-W03)', () => {
  it('서버 郡 시야를 구역 → 郡 번호 표와 함께 넘긴다(번호는 bake commanderyNo, 없으면 장소 표 자리)', async () => {
    const vision = new Map([[5, 'FULL'], [1, 'FOG']] as const);
    draw(vision);
    await waitFor(() => expect(mocks.world?.vision).toBe(vision));
    expect(Array.from(mocks.world!.commanderyOfProvince!)).toEqual([5, 1, -1]);
    expect(mocks.world!.occupancy).toHaveLength(3);
  });

  it('시야를 못 받았으면(null) 안개를 짓지 않는다 — 세력색만 넘긴다', async () => {
    draw(null);
    await waitFor(() => expect(mocks.world).toBeDefined());
    expect(mocks.world!.vision).toBeUndefined();
    expect(mocks.world!.commanderyOfProvince).toBeUndefined();
  });

  it('레이어 판의 「시야」는 켜고 끄는 층이다(기본 켬) — 「서버 대기」 줄은 보급선 · 수역만', async () => {
    draw(new Map([[5, 'FOG']] as const));
    await waitFor(() => expect(mocks.world?.vision).toBeDefined());
    expect(mocks.layers?.fog).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
    const panel = screen.getByRole('region', { name: '지도 레이어' });
    expect(panel.querySelector('[data-pending-layer="fog"]')).toBeNull();
    expect([...panel.querySelectorAll('[data-pending-layer]')].map((row) => row.getAttribute('data-pending-layer'))).toEqual(['supply', 'water']);
    fireEvent.click(screen.getByRole('button', { name: /시야/ }));
    expect(mocks.layers?.fog).toBe(false);
  });
});
