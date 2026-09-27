import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorldMapCanvas as WorldMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse } from '@/lib/types';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof WorldMapCanvasType> | null,
}));

vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return {
    ...actual,
    useWorldMap: ({ mapData }: { mapData?: MapPreviewResponse }) => {
      if (!mapData) return { kind: 'loading' as const };
      if (mapData.mapCode !== 'han-world-v3') return { kind: 'unsupported' as const, mapCode: mapData.mapCode };
      const nations = new Map(mapData.nations.map((nation) => [nation.id, nation]));
      const colorOf = (nationId: number) => ({ nationName: nations.get(nationId)?.name, nationColor: nations.get(nationId)?.color });
      return { kind: 'ready' as const, preview: mapData, tiles: { _meta: { cols: 768, rows: 669 } },
        tilesSha256: 'test', provinceMap: null, commanderies: [], markerPositions: new Map([[11, { col: 100, row: 100 }]]),
        cities: actual.buildWorldCities(mapData), sourceSize: { width: mapData.width, height: mapData.height },
        administrativeOwnership: mapData.provinceOccupancy?.length && mapData.jurisdictionOwnership?.length && mapData.commanderyControl?.length
          ? { provinceOccupancy: mapData.provinceOccupancy.map((row) => ({ ...row, ...colorOf(row.nationId) })),
            jurisdictionOwnership: mapData.jurisdictionOwnership.map((row) => ({ ...row, ...colorOf(row.nationId) })),
            commanderyControl: mapData.commanderyControl.map((row) => ({ ...row, ...colorOf(row.nationId) })) } : undefined,
      };
    },
    WorldMapCanvas: (props: ComponentProps<typeof WorldMapCanvasType>) => {
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
          <button
            type="button"
            onClick={() => county && props.onCountyHover?.({
              ...county, commanderyName: '영천군', countyName: '양성현', countyGloss: '陽城',
            }, { x: 20, y: 30 })}
          >hover glossed county</button>
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
  mapCode: 'han-world-v3', width: 700, height: 610,
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

describe('MapViewer 메인 2D 지도', () => {
  it('城·선택 상태·지형 주소를 작전실과 같은 캔버스에 넘긴다', () => {
    const mapCode = 'han-world-v3';
    render(<MapViewer mapData={{ ...MAP, mapCode }} currentCityId={11} selectedCityId={22} />);
    expect(screen.getByTestId('shared-iso-map')).toHaveAttribute('data-map-code', mapCode);
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.props?.currentCityId).toBe(11);
    expect(shared.props?.selectedCityId).toBe(22);
    expect(shared.props?.cities?.map((city) => city.id)).toEqual([11, 22]);
    expect(shared.props?.tiles).toBeDefined();
    expect(shared.props?.markerPositions?.get(11)).toEqual({ col: 100, row: 100 });
    expect(shared.props?.terrainUrl).toBeUndefined();
  });

  it('지역 城 데이터와 province PNG 를 메인 캔버스에 넘긴다', () => {
    const mapCode = 'han-world-v3';
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
    expect(shared.props?.provinceUrl).toBe('/api/game/api/map/provinces?mapCode=han-world-v3');
  });

  it('shows the region commandery and county from the polygon callback', () => {
    render(<MapViewer mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(screen.getByRole('status')).toHaveTextContent('경조윤 장안현');
    expect(screen.getByRole('status')).not.toHaveTextContent('사예');
    expect(screen.getByRole('status')).not.toHaveTextContent('【');
    expect(screen.getByRole('status')).toHaveTextContent('위');
  });

  // #838: 같은 郡 안 同音 縣(영천군 양성현 陽城·襄城)은 작고 흐린 漢字 병기를 뒤에 단다.
  it('같은 郡 안 同音 縣이면 툴팁 이름 뒤에 漢字 병기 span 을 단다', () => {
    render(<MapViewer mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover glossed county' }));
    const name = document.querySelector('.map-tooltip-name');
    expect(name).toHaveTextContent('영천군 양성현陽城');
    const gloss = name?.querySelector('.os-place-gloss');
    expect(gloss).toHaveTextContent('陽城');
    expect(gloss).toHaveAttribute('lang', 'zh-Hant');
  });

  it('병기 대상이 아니면 툴팁 이름에 병기 span 이 없다', () => {
    render(<MapViewer mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover county' }));
    expect(document.querySelector('.map-tooltip-name .os-place-gloss')).toBeNull();
  });

  it('투영 소유권을 전달하고 툴팁은 활성 레이어 소유자만 표시한다', () => {
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
    expect(document.querySelector('.map-tooltip-meta')).toHaveTextContent(/^위$/);
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
      nationName: '공백지',
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
