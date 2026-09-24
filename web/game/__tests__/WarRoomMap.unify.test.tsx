import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({ props: null as ComponentProps<typeof HanMapCanvasType> | null }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
    mocks.props = props; return <div data-testid="war-map">
      <button type="button" onClick={() => props.onCityHover?.(props.cities![0], { x: 1, y: 2 })}>城 얹기</button>
    </div>;
  } };
});
vi.mock('@/lib/hwiha-map', () => ({ HWIHA_MAP_CODE: 'han-world-v3', HWIHA_PROVINCES_URL: '/provinces',
  useHwihaWorldMap: () => ({ kind: 'ready', preview: { cities: [{ id: 7, commanderyName: '甲郡' }] },
    tiles: { _meta: { cols: 768, rows: 669 } }, tilesSha256: 'a'.repeat(64), provinceMap: null,
    provinceCenter: (id: string) => id === 'P1' ? { col: 10, row: 20 } : undefined,
    markerPositions: new Map([[7, { col: 384, row: 334 }]]),
    cities: [{ id: 7, name: '甲縣', commanderyName: '甲郡', mapLabel: '甲縣', level: 5, nationId: 1, x: 350, y: 305 }],
    commanderies: [{ no: 1, name: '甲郡', col: 384, row: 334, focusCityId: 7 }],
    sourceSize: { width: 700, height: 610 }, legend: [], administrativeOwnership: undefined }) }));
import WarRoomMap from '@/components/hwiha/WarRoomMap';

describe('WarRoomMap unified map props', () => {
  it('passes the served cells, centered 城, visibility, and projected corps', () => {
    render(<WarRoomMap homeCityId={7} visibility={new Map([[1, 'INTEL']])}
      corps={[{ corpsId: 'c1', ownerGeneralId: 1, commanderGeneralId: 1, nationId: 1,
        provinceId: 'P1', commanderyNo: 1, visibility: 'INTEL', own: false }]} />);
    expect(screen.getByTestId('war-map')).toBeInTheDocument();
    expect(mocks.props?.tiles?._meta.cols).toBe(768);
    expect(mocks.props?.markerPositions?.get(7)).toEqual({ col: 384, row: 334 });
    expect(mocks.props?.cities?.[0].mapLabel).toBe('甲縣');
    expect(mocks.props?.commanderyVisibility?.get(1)).toBe('INTEL');
    expect(mocks.props?.fogMode).toBe('dim');
    expect(mocks.props?.corps).toMatchObject([{ id: 'c1', stale: true, col: 10, row: 20 }]);
    fireEvent.click(screen.getByRole('button', { name: '城 얹기' }));
    expect(screen.getByRole('status')).toHaveTextContent('甲郡 甲縣');
  });
});
