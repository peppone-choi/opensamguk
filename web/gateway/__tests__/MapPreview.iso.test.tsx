// 로비 지도는 게임창과 같은 아이소 2D 판을 쓴다. 여기서 지키는 것은 세 가지다.
//   1) 지형 주소를 server·mapCode 로 정확히 만든다 (CDN 지도 노드는 쓰지 않는다).
//   2) 게임 도시가 격자에 앉는다 — placeGameCities 는 진짜를 돌린다.
//   3) 소유가 확실할 때만 세력색·세력명이 붙는다.
// 격자 적재(useIsoTileGrid)는 fetch·ImageBitmap 이 필요하므로 여기서만 가짜를 세운다.
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { IsoMap2D as IsoMap2DType, IsoMapData } from '@opensamguk/ui';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof IsoMap2DType> | null,
  terrainUrl: null as string | null,
}));

// 8×8 원본 셀 = 2×2 타일. 縣 0 의 治所만 셀 (5,1) 에 둔다.
const GRID = {
  grid: { cols: 2, rows: 2 },
  provinceSeatCell: {
    col: Int32Array.from([5, -1]),
    row: Int32Array.from([1, -1]),
    cityIndex: Int32Array.from([-1, -1]),
  },
  sourceCols: 8,
  sourceRows: 8,
  // 지형 응답의 城·郡國 밖 세력 목록. 여기서는 배치만 보므로 비워 둔다.
  cities: [],
} as unknown as IsoMapData;

vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return {
    ...actual,
    useIsoTileGrid: (terrainUrl: string) => {
      shared.terrainUrl = terrainUrl;
      return terrainUrl
        ? { status: 'ready' as const, data: GRID, error: null }
        : { status: 'loading' as const, data: null, error: null };
    },
    IsoMap2D: (props: ComponentProps<typeof IsoMap2DType>) => {
      shared.props = props;
      return (
        <div data-testid="iso2d" aria-label={props.ariaLabel}>
          <button
            type="button"
            onClick={() => props.onPickCity?.(props.cities![0], { pointerType: 'mouse' })}
          >
            첫 城 누르기
          </button>
          {/* 진짜 판은 캔버스 위 좌표로 부른다. 여기서는 그 호출만 흉내낸다. */}
          <button
            type="button"
            onClick={() => props.onHoverCity?.(props.cities![0], { x: 40, y: 60 })}
          >
            첫 城 얹기
          </button>
          <button type="button" onClick={() => props.onHoverCity?.(null, { x: 0, y: 0 })}>
            城 밖으로
          </button>
        </div>
      );
    },
  };
});

import MapPreview, { type MapData } from '@/components/MapPreview';

const MAP: MapData = {
  serverName: '테스트섭',
  year: 200,
  month: 5,
  turnPhaseText: '상순',
  mapCode: 'han',
  width: 100,
  height: 100,
  cities: [
    {
      id: 11, name: '낙양', level: 8, nationId: 1, x: 50, y: 50,
      provinceId: 0, state: 6, supply: true, isCapital: true,
    },
  ],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
};

beforeEach(() => {
  shared.props = null;
  shared.terrainUrl = null;
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: () => null,
    get length() { return values.size; },
  });
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect() {}
  });
});

describe('MapPreview 아이소 2D 판', () => {
  it('지형 주소를 server·mapCode 로 만들고 CDN 지도 노드는 쓰지 않는다', () => {
    const mapCode = 'ha n&?';
    render(<MapPreview serverId="s 1&?" mapData={{ ...MAP, mapCode }} currentCityId={11} />);

    expect(shared.terrainUrl).toBe('/api/game/api/map/terrain?server=s%201%26%3F&mapCode=ha%20n%26%3F');
    expect(screen.getByTestId('iso2d')).toHaveAttribute('aria-label', `${mapCode} 서버 아이소 지도`);
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.props?.currentCityId).toBe(11);
  });

  it('게임 도시를 격자에 앉히고 세력색을 붙인다', () => {
    render(<MapPreview mapData={MAP} />);
    // 縣 0 의 治所 셀 (5,1) → 타일 (1.25, 0.25). 반올림하지 않는다.
    expect(shared.props?.cities?.[0]).toMatchObject({
      id: 11, name: '낙양', nationName: '위', nationColor: '#ff0000',
      col: 1.25, row: 0.25, isCapital: true, exact: true,
    });
    expect(shared.props?.tintMode).toBe('nation');
  });

  it('provinceOccupancy 가 있으면 그것이 縣 색의 정본이다', () => {
    render(<MapPreview mapData={{
      ...MAP,
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 1, nationId: 2 }],
      nations: [...MAP.nations, { id: 2, name: '한', color: '#0000ff' }],
    }} />);
    // 도시(縣 0)가 아니라 응답이 말한 縣 1 만 칠해진다.
    expect(shared.props?.nationColorByOwner).toEqual({ 1: '#0000ff' });
  });

  it('城 을 누르면 그 城 정보가 캔버스 위에 뜬다', () => {
    render(<MapPreview mapData={MAP} />);
    expect(screen.queryByRole('status')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '첫 城 누르기' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).toHaveTextContent('위 · 수도');
    expect(shared.props?.selectedCityId).toBe(11);
  });

  it('城 에 마우스를 얹기만 해도 그 城 정보가 뜨고, 벗어나면 사라진다', () => {
    render(<MapPreview mapData={MAP} />);
    expect(screen.queryByRole('status')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '첫 城 얹기' }));
    const tip = screen.getByRole('status');
    expect(tip).toHaveTextContent('낙양');
    expect(tip).toHaveTextContent('위 · 수도');
    // 얹은 툴팁은 커서를 따라간다 — 왼위에 붙는 건 눌러서 고른 쪽이다.
    expect(tip).toHaveStyle({ left: '54px', top: '74px' });
    // 얹은 것만으로는 城 이 선택되지 않는다.
    expect(shared.props?.selectedCityId).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '城 밖으로' }));
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('도시명 표기 토글이 캔버스까지 간다', () => {
    render(<MapPreview mapData={MAP} />);
    expect(shared.props?.hideCityNames).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
    expect(shared.props?.hideCityNames).toBe(true);
    expect(window.localStorage.getItem('sam.hideMapCityName')).toBe('yes');
  });

  it.each([
    ['표에 없는 세력', 2, []],
    ['#rrggbb 가 아닌 색', 1, [{ id: 1, name: '표시 금지', color: 'red' }]],
    ['NaN', Number.NaN, [{ id: Number.NaN, name: '표시 금지', color: '#ff0000' }]],
    ['무한대', Number.POSITIVE_INFINITY, [{ id: Number.POSITIVE_INFINITY, name: '표시 금지', color: '#ff0000' }]],
    ['비정수', 1.5, [{ id: 1.5, name: '표시 금지', color: '#ff0000' }]],
    ['재야(0)', 0, [{ id: 0, name: '표시 금지', color: '#ff0000' }]],
    ['음수', -1, [{ id: -1, name: '표시 금지', color: '#ff0000' }]],
  ])('%s 소유는 城에도 縣 색에도 나타나지 않는다', (_label, nationId, nations) => {
    render(<MapPreview mapData={{
      ...MAP,
      cities: [{ ...MAP.cities[0], nationId }],
      nations,
    }} />);

    expect(shared.props?.cities?.[0]).toMatchObject({
      nationId, nationName: undefined, nationColor: undefined,
    });
    expect(shared.props?.nationColorByOwner).toEqual({});
    fireEvent.click(screen.getByRole('button', { name: '첫 城 누르기' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).not.toHaveTextContent('표시 금지');
  });
});
