import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { WorldMapCanvas as WorldMapCanvasType } from '@opensamguk/ui';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof WorldMapCanvasType> | null,
}));

vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual,
    useWorldMap: ({ mapData }: { mapData?: MapData }) => {
      if (!mapData) return { kind: 'loading' as const };
      if (mapData.mapCode !== 'han-world-v3') return { kind: 'unsupported' as const, mapCode: mapData.mapCode };
      const nations = new Map(mapData.nations.map((nation) => [nation.id, nation]));
      const colorOf = (nationId: number) => ({ nationName: nations.get(nationId)?.name, nationColor: nations.get(nationId)?.color });
      return { kind: 'ready' as const, preview: mapData, tiles: { _meta: { cols: 768, rows: 669 } },
        tilesSha256: 'test', provinceMap: null, markerPositions: new Map([[11, { col: 100, row: 100 }]]),
        cities: actual.buildWorldCities(mapData), sourceSize: { width: mapData.width, height: mapData.height },
        administrativeOwnership: mapData.provinceOccupancy?.length && mapData.jurisdictionOwnership?.length && mapData.commanderyControl?.length
          ? { provinceOccupancy: mapData.provinceOccupancy.map((row) => ({ ...row, ...colorOf(row.nationId) })),
            jurisdictionOwnership: mapData.jurisdictionOwnership.map((row) => ({ ...row, ...colorOf(row.nationId) })),
            commanderyControl: mapData.commanderyControl.map((row) => ({ ...row, ...colorOf(row.nationId) })) } : undefined,
      };
    },
    WorldMapCanvas: (props: ComponentProps<typeof WorldMapCanvasType>) => {
      shared.props = props;
      return <div data-testid="han-map" aria-label={props.ariaLabel}>
        <button type="button" onClick={() => props.onCityActivate?.(props.cities![0])}>첫 城 누르기</button>
        <button type="button" onClick={() => props.onCityHover?.(props.cities![0], { x: 40, y: 60 })}>첫 城 얹기</button>
        <button type="button" onClick={() => props.onCityHover?.(null)}>城 밖으로</button>
      </div>;
    },
  };
});

import MapPreview, { type MapData } from '@/components/MapPreview';

const MAP: MapData = {
  serverName: '테스트섭', year: 200, month: 5, turnPhaseText: '상순', mapCode: 'han-world-v3',
  width: 100, height: 100,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 1, x: 50, y: 50,
    regionName: '사예', provinceId: 0, state: 6, supply: true, isCapital: true }],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
};

beforeEach(() => {
  shared.props = null;
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: () => null,
    get length() { return values.size; },
  });
});

describe('MapPreview 작전실 2D 판', () => {
  it('지형·프로빈스 주소와 州 배정을 전달한다', () => {
    const mapCode = 'han-world-v3';
    render(<MapPreview serverId="s 1&?" mapData={{ ...MAP, mapCode }} currentCityId={11} />);
    expect(shared.props?.tiles).toBeDefined();
    expect(shared.props?.markerPositions?.get(11)).toEqual({ col: 100, row: 100 });
    expect(shared.props?.showCellGrid).toBe(true);
    expect(shared.props?.terrainUrl).toBeUndefined();
    expect(shared.props?.provinceUrl).toBe('/api/game/api/map/provinces?server=s%201%26%3F&mapCode=han-world-v3');
    expect(shared.props?.cities?.[0]).toMatchObject({ id: 11, regionName: '사예' });
    expect(shared.props?.currentCityId).toBe(11);
    expect(screen.getByTestId('han-map')).toHaveAttribute('aria-label', `${mapCode} 서버 지도`);
  });

  it('소유색은 유효한 국가에만 붙이고 건물 색은 따로 둔다', () => {
    render(<MapPreview mapData={MAP} />);
    expect(shared.props?.cities?.[0]).toMatchObject({ nationName: '위', nationColor: '#ff0000' });
    expect(shared.props?.politicalStyle).toBe('tint');
    expect(shared.props?.showCityFootprint).toBe(true);
  });

  it('provinceOccupancy 소유자를 지도 투영에 전달한다', () => {
    render(<MapPreview mapData={{ ...MAP,
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 1, nationId: 2 }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 2 }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 2 }],
      nations: [...MAP.nations, { id: 2, name: '한', color: '#0000ff' }],
    }} />);
    expect(shared.props?.administrativeOwnership?.provinceOccupancy).toContainEqual({
      provinceRecordId: 'P1', provinceIndex: 1, nationId: 2, nationName: '한', nationColor: '#0000ff',
    });
  });

  it('클릭과 hover가 지도 선택·툴팁에 연결된다', () => {
    render(<MapPreview mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: '첫 城 얹기' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).toHaveStyle({ left: '54px', top: '74px' });
    expect(shared.props?.selectedCityId).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '城 밖으로' }));
    expect(screen.queryByRole('status')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '첫 城 누르기' }));
    expect(shared.props?.selectedCityId).toBe(11);
  });

  it('도시명 토글을 캔버스로 전달한다', () => {
    render(<MapPreview mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
    expect(shared.props?.hideCityNames).toBe(true);
  });

  it.each([
    ['표에 없는 세력', 2, []],
    ['잘못된 색', 1, [{ id: 1, name: '표시 금지', color: 'red' }]],
    ['재야', 0, [{ id: 0, name: '표시 금지', color: '#ff0000' }]],
  ])('%s 소유는 城의 국가색이 되지 않는다', (_label, nationId, nations) => {
    render(<MapPreview mapData={{ ...MAP, cities: [{ ...MAP.cities[0], nationId }], nations }} />);
    expect(shared.props?.cities?.[0]).toMatchObject({ nationId, nationName: '공백지', nationColor: undefined });
  });
});
