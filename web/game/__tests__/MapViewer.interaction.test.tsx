import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType, PlacedCity } from '@opensamguk/ui';
import type IsoWorldMapType from '@/components/iso/IsoWorldMap';
import type { MapPreviewResponse } from '@/lib/types';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
  iso: null as ComponentProps<typeof IsoWorldMapType> | null,
}));

vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return {
    ...actual,
    HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
      shared.props = props;
      const city = props.cities?.[0];
      const county = city ? {
        provinceId: 1033,
        commanderyId: 0,
        regionName: '사예',
        commanderyName: '경조윤',
        countyName: '장안현',
        level: 9,
        nationId: city.nationId,
        nationName: city.nationName,
        nationColor: city.nationColor,
        ...(props.administrativeOwnership ? {
          hierarchyPath: '공간 낙양 → 낙양현 → 하남윤',
          displayedOwnerNationName: '위',
          provinceOccupantNationName: '위',
          jurisdictionOwnerNationName: '위',
          commanderyControllerNationName: '한',
          provinceJurisdictionMismatch: false,
          jurisdictionCommanderyMismatch: true,
          ownershipMismatch: true,
        } : {}),
      } : null;
      return (
        <div data-testid="shared-iso-map" data-map-code={props.mapCode}>
          <button type="button" onClick={() => props.onCountyHover?.(county, { x: 20, y: 30 })}>hover county</button>
          <button type="button" onClick={() => city && props.onCityActivate?.(city, { pointerType: 'mouse' })}>activate mouse</button>
          <button type="button" onClick={() => city && props.onCityActivate?.(city, { pointerType: 'touch' })}>activate touch</button>
        </div>
      );
    },
  };
});

// 아이소가 정본 지도다. 격자·스프라이트 없이 MapViewer 의 계약만 보므로 대역을 세운다.
// 城 을 집었을 때 어떤 모양이 오는지는 placeGameCities 테스트가 따로 지킨다.
vi.mock('@/components/iso/IsoWorldMap', () => ({
  default: (props: ComponentProps<typeof IsoWorldMapType>) => {
    shared.iso = props;
    const city = props.cities?.[0];
    const placed: PlacedCity | null = city ? {
      id: city.id, name: city.name, level: city.level, nationId: city.nationId,
      col: 1.25, row: 0.25, tileCol: 1, tileRow: 0,
      drawCol: 1, drawRow: 0, drawScale: 1,
      seat: false, isCapital: city.isCapital === true, exact: true,
    } : null;
    return (
      <div data-testid="iso-world-map" data-terrain={props.terrainUrl}>
        <button type="button" onClick={() => placed && props.onCityActivate?.(placed, { pointerType: 'mouse' })}>activate mouse</button>
        <button type="button" onClick={() => placed && props.onCityActivate?.(placed, { pointerType: 'touch' })}>activate touch</button>
      </div>
    );
  },
}));

import MapViewer from '@/components/game/MapViewer';

const MAP: MapPreviewResponse = {
  serverName: '테스트섭', year: 200, month: 5, turnPhase: 1, turnPhaseText: '상순',
  mapCode: 'han', width: 700, height: 610,
  cities: [
    { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 6, supply: true, isCapital: true },
    { id: 22, name: '허창', level: 6, nationId: 1, x: 500, y: 300, state: 0, supply: false, isCapital: false },
  ],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
};

beforeEach(() => {
  shared.props = null;
  shared.iso = null;
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: () => null,
    get length() { return values.size; },
  });
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {} }));
  Object.defineProperty(navigator, 'maxTouchPoints', { configurable: true, value: 0 });
});

describe('MapViewer 아이소 지도(정본)', () => {
  it('城·선택 상태·지형 주소를 아이소 판에 넘기고 옛 DOM 애셋은 없다', () => {
    const mapCode = 'ha n&?';
    render(<MapViewer mapData={{ ...MAP, mapCode }} currentCityId={11} selectedCityId={22} />);
    expect(screen.getByTestId('iso-world-map'))
      .toHaveAttribute('data-terrain', '/api/game/api/map/terrain?mapCode=ha%20n%26%3F');
    expect(screen.queryByTestId('shared-iso-map')).toBeNull();
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.iso?.currentCityId).toBe(11);
    expect(shared.iso?.selectedCityId).toBe(22);
    expect(shared.iso?.cities?.map((city) => city.id)).toEqual([11, 22]);
    expect(shared.iso?.nations).toEqual([{ id: 1, name: '위', color: '#ff0000' }]);
  });

  it('legacyCanvas 를 켰을 때만 옛 평면 캔버스가 나온다', () => {
    const mapCode = 'ha n&?';
    render(<MapViewer legacyCanvas mapData={{ ...MAP, mapCode }} currentCityId={11} selectedCityId={22} />);
    expect(screen.getByTestId('shared-iso-map')).toHaveAttribute('data-map-code', mapCode);
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.props?.currentCityId).toBe(11);
    expect(shared.props?.selectedCityId).toBe(22);
    expect(shared.props?.cities).toEqual([
      expect.objectContaining({ id: 11, nationColor: '#ff0000', nationName: '위', state: 6, supply: true, isCapital: true }),
      expect.objectContaining({ id: 22, nationColor: '#ff0000', supply: false }),
    ]);
    const provinceUrl = typeof shared.props?.provinceUrl === 'function'
      ? shared.props.provinceUrl(mapCode)
      : shared.props?.provinceUrl;
    expect(provinceUrl).toBe('/api/game/api/map/provinces?mapCode=ha%20n%26%3F');
  });

  it('shows the region commandery and county from the polygon callback', () => {
    render(<MapViewer legacyCanvas mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(screen.getByRole('status')).toHaveTextContent('경조윤 장안현');
    expect(screen.getByRole('status')).not.toHaveTextContent('사예');
    expect(screen.getByRole('status')).not.toHaveTextContent('【');
    expect(screen.getByRole('status')).toHaveTextContent('위');
  });

  it('투영 소유권을 전달하고 툴팁은 활성 레이어 소유자만 표시한다', () => {
    render(<MapViewer mapData={{
      ...MAP,
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1 }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 1 }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 2 }],
      nations: [...MAP.nations, { id: 2, name: '한', color: '#0000ff' }],
    }} legacyCanvas />);

    expect(shared.props?.administrativeOwnership).toEqual({
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1, nationColor: '#ff0000', nationName: '위' }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 1, nationColor: '#ff0000', nationName: '위' }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 2, nationColor: '#0000ff', nationName: '한' }],
    });
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(document.querySelectorAll('.map-tooltip-meta')).toHaveLength(1);
    expect(document.querySelector('.map-tooltip-meta')).toHaveTextContent(/^위$/);
  });

  it.each([
    ['unknown nation', 99, []],
    ['malformed color', 1, [{ id: 1, name: '위', color: 'red' }]],
    ['NaN id', Number.NaN, [{ id: Number.NaN, name: '위', color: '#ff0000' }]],
    ['infinite id', Number.POSITIVE_INFINITY, [{ id: Number.POSITIVE_INFINITY, name: '위', color: '#ff0000' }]],
    ['fractional id', 1.5, [{ id: 1.5, name: '위', color: '#ff0000' }]],
  ])('keeps %s visually and semantically unowned', (_label, nationId, nations) => {
    render(<MapViewer legacyCanvas mapData={{
      ...MAP,
      cities: [{ ...MAP.cities[0], nationId }],
      nations,
    }} />);

    expect(shared.props?.cities?.[0]).toEqual(expect.objectContaining({
      nationId,
      nationColor: undefined,
    }));
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(screen.getByRole('status')).toHaveTextContent('장안현');
    expect(document.querySelector('.map-tooltip-meta')).toBeNull();
  });

  it('preserves explicit nation id zero as neutral', () => {
    render(<MapViewer legacyCanvas mapData={{
      ...MAP,
      cities: [{ ...MAP.cities[0], nationId: 0 }],
      nations: [],
    }} />);

    expect(shared.props?.cities?.[0]).toEqual(expect.objectContaining({
      nationId: 0,
      nationName: '공 백 지',
      nationColor: undefined,
    }));
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(document.querySelector('.map-tooltip-meta')).toBeNull();
  });

  it('selection mode activates onCitySelect without navigation', () => {
    const onCitySelect = vi.fn();
    const onNavigate = vi.fn();
    render(<MapViewer mapData={MAP} onCitySelect={onCitySelect} onNavigate={onNavigate} />);
    fireEvent.click(screen.getByRole('button', { name: 'activate mouse' }));
    expect(onCitySelect).toHaveBeenCalledWith(11);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('navigation mode activates the server-aware city URL', () => {
    const onNavigate = vi.fn();
    render(<MapViewer mapData={MAP} disallowClick={false} onNavigate={onNavigate} />);
    fireEvent.click(screen.getByRole('button', { name: 'activate mouse' }));
    expect(onNavigate).toHaveBeenCalledWith('/game/city?id=11');
  });

  it('disallowClick blocks activation', () => {
    const onNavigate = vi.fn();
    render(<MapViewer mapData={MAP} disallowClick onNavigate={onNavigate} />);
    fireEvent.click(screen.getByRole('button', { name: 'activate mouse' }));
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('touch requires the same city twice when single-tap is off', () => {
    const onNavigate = vi.fn();
    render(<MapViewer mapData={MAP} disallowClick={false} onNavigate={onNavigate} />);
    const touch = screen.getByRole('button', { name: 'activate touch' });
    fireEvent.click(touch);
    expect(onNavigate).not.toHaveBeenCalled();
    fireEvent.click(touch);
    expect(onNavigate).toHaveBeenCalledWith('/game/city?id=11');
  });

  it('city-name toggle controls canvas labels', () => {
    render(<MapViewer mapData={MAP} />);
    expect(shared.iso?.hideCityNames).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
    expect(shared.iso?.hideCityNames).toBe(true);
  });
});
