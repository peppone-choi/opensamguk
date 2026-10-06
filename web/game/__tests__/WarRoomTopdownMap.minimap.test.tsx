import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ViewportClass } from '@opensamguk/ui';

// 실지도 결함 2 · 보드 V31: 작은 지도는 데스크톱 작전실에만 있다(모바일 V31K4MWarRoom에는 없다). 지도 그리기(GL)는 가짜.
const mocks = vi.hoisted(() => ({ viewport: null as string | null, minimap: [] as (boolean | undefined)[] }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, useViewportClass: () => mocks.viewport };
});
vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    loadBakePlaces: () => new Promise(() => undefined),
    TopdownMap: (props: { minimap?: boolean }) => { mocks.minimap.push(props.minimap); return <div data-testid="topdown-map" />; } };
});
import WarRoomTopdownMap from '@/components/campaign/WarRoomTopdownMap';
import type { MapPreviewResponse } from '@/lib/types';

const PREVIEW = { mapCode: 'han-world-v3', cities: [], nations: [] } as unknown as MapPreviewResponse;

describe('작전실 새 지도 작은 지도(보드 V31)', () => {
  it.each([
    ['desktop', true],
    ['tablet', true],
    ['mobile', false],
    // 화면 폭을 아직 모르면 두지 않는다(모바일에서 잠깐 떴다 사라지지 않게)
    [null, false],
  ] as const)('화면 %s → 작은 지도 %s', (viewport, shown) => {
    mocks.viewport = viewport satisfies ViewportClass | null;
    mocks.minimap.length = 0;
    render(<WarRoomTopdownMap source={{ bakeUrl: '/bake', kitUrl: '/kit' }} preview={PREVIEW} homeCityId={null}
      focusCityId={null} ariaLabel="천하 형세" />);
    expect(mocks.minimap.at(-1)).toBe(shown);
  });
});
