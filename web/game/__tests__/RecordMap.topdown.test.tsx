import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { TopdownMap as TopdownMapType, TopdownMapHandle } from '@opensamguk/ui/map/topdown';

// 기록 지도: 교체 스위치 + bakeId 면 새 지도(그 현 가운데 · 현 보기 · 고름), 아니면 옛 지도판 그대로. 지도 그리기(GL)는 가짜.
const shared = vi.hoisted(() => ({
  topdown: null as ComponentProps<typeof TopdownMapType> | null,
  centerOn: vi.fn(),
  preview: null as Record<string, unknown> | null,
}));

vi.mock('@/lib/campaign-map', () => ({
  CAMPAIGN_MAP_CODE: 'han-world-v3',
  CAMPAIGN_PROVINCES_URL: '/provinces',
  useCampaignWorldMap: () => ({ kind: 'ready', preview: shared.preview, tiles: {}, tilesSha256: 't', provinceMap: null,
    cities: [], administrativeOwnership: undefined, sourceSize: { width: 700, height: 610 }, markerPositions: new Map() }),
}));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, WorldMapCanvas: (props: { selectedCityId?: number }) => <div data-testid="old-map" data-selected={props.selectedCityId} /> };
});
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    loadBakePlaces: async () => ({ provinceCount: 2 }),
    cityCell: (_places: unknown, cityId: number) => ({ col: cityId * 10 + 0.5, row: cityId * 10 + 0.5 }),
    TopdownMap: (props: ComponentProps<typeof TopdownMapType>) => {
      shared.topdown = props;
      // 실제 지도처럼 그린 뒤 handle 을 넘긴다
      props.onReady?.({ centerOn: shared.centerOn, setLevel: vi.fn(), zoomStep: vi.fn(), focusCity: vi.fn(() => true) } as TopdownMapHandle);
      return <div data-testid="topdown-map" />;
    },
  };
});

import RecordMap from '@/components/records/RecordMap';

const PREVIEW = {
  mapCode: 'han-world-v3', cities: [], nations: [{ id: 1, name: '조조', color: '#4f7fbf' }],
  provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 }],
  topdownBakeId: 'b'.repeat(64),
};

beforeEach(() => {
  shared.topdown = null;
  shared.centerOn.mockReset();
  shared.preview = PREVIEW;
  vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
});
afterEach(() => {
  vi.unstubAllEnvs();
});

describe('RecordMap — 교체 스위치', () => {
  it('bakeId 가 있으면 새 지도: 그 현을 현 보기로 가운데 두고 고른다, 현이 바뀌면 다시 맞춘다', async () => {
    const { rerender } = render(<RecordMap cityId={3} label="선무" />);
    await waitFor(() => expect(shared.centerOn).toHaveBeenCalledWith({ col: 30.5, row: 30.5 }, 16));
    expect(screen.queryByTestId('old-map')).toBeNull();
    expect(shared.topdown!.selectedCityId).toBe(3);
    expect(shared.topdown!.ariaLabel).toBe('선무 일대 지도');
    expect(shared.topdown!.minimap).toBeFalsy();
    rerender(<RecordMap cityId={5} label="허현" />);
    await waitFor(() => expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 50.5, row: 50.5 }, 16));
    expect(shared.topdown!.selectedCityId).toBe(5);
  });

  it('bakeId 가 없거나 스위치가 꺼지면 옛 지도판(그 현을 고른 채)', () => {
    shared.preview = { ...PREVIEW, topdownBakeId: undefined };
    const { unmount } = render(<RecordMap cityId={3} label="선무" />);
    expect(screen.getByTestId('old-map')).toHaveAttribute('data-selected', '3');
    expect(screen.queryByTestId('topdown-map')).toBeNull();
    unmount();
    shared.preview = PREVIEW;
    vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '');
    render(<RecordMap cityId={3} label="선무" />);
    expect(screen.getByTestId('old-map')).toBeInTheDocument();
    expect(screen.queryByTestId('topdown-map')).toBeNull();
  });
});
