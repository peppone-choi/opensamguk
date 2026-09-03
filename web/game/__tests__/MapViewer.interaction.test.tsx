import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse } from '@/lib/types';

const shared = vi.hoisted(() => ({ props: null as ComponentProps<typeof HanMapCanvasType> | null }));

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

describe('MapViewer shared canvas overlays', () => {
  it('passes all city visuals and removes legacy DOM assets', () => {
    const mapCode = 'ha n&?';
    render(<MapViewer mapData={{ ...MAP, mapCode }} currentCityId={11} selectedCityId={22} />);
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
    render(<MapViewer mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(screen.getByRole('status')).toHaveTextContent('경조윤 장안현');
    expect(screen.getByRole('status')).not.toHaveTextContent('사예');
    expect(screen.getByRole('status')).not.toHaveTextContent('【');
    expect(screen.getByRole('status')).toHaveTextContent('위');
  });

  it('투영 소유권을 전달하고 툴팁은 소유자 정보만 한 줄로 표시한다', () => {
    render(<MapViewer mapData={{
      ...MAP,
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1 }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 1 }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 2 }],
      nations: [...MAP.nations, { id: 2, name: '한', color: '#0000ff' }],
    }} />);

    expect(shared.props?.administrativeOwnership).toEqual({
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1, nationColor: '#ff0000', nationName: '위' }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 1, nationColor: '#ff0000', nationName: '위' }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 2, nationColor: '#0000ff', nationName: '한' }],
    });
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(document.querySelectorAll('.map-tooltip-meta')).toHaveLength(1);
    expect(document.querySelector('.map-tooltip-meta')).toHaveTextContent('공간: 위 / 현: 위 / 군국: 한');
    expect(document.querySelector('.map-tooltip-meta')).not.toHaveTextContent('→');
    expect(screen.getByRole('status')).not.toHaveTextContent('공간 점유:');
    expect(screen.getByRole('status')).not.toHaveTextContent('현 소유:');
    expect(screen.getByRole('status')).not.toHaveTextContent('군국 통제:');
    expect(screen.getByRole('status')).not.toHaveTextContent('다릅니다.');
  });

  it.each([
    ['unknown nation', 99, []],
    ['malformed color', 1, [{ id: 1, name: '위', color: 'red' }]],
    ['NaN id', Number.NaN, [{ id: Number.NaN, name: '위', color: '#ff0000' }]],
    ['infinite id', Number.POSITIVE_INFINITY, [{ id: Number.POSITIVE_INFINITY, name: '위', color: '#ff0000' }]],
    ['fractional id', 1.5, [{ id: 1.5, name: '위', color: '#ff0000' }]],
  ])('keeps %s visually and semantically unowned', (_label, nationId, nations) => {
    render(<MapViewer mapData={{
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
    render(<MapViewer mapData={{
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
    expect(shared.props?.hideCityNames).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
    expect(shared.props?.hideCityNames).toBe(true);
  });
});
