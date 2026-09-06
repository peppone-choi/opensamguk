import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
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
          displayedOwnerNationName: '한',
          provinceOccupantNationName: '위',
          jurisdictionOwnerNationName: '한',
          commanderyControllerNationName: '조',
          provinceJurisdictionMismatch: true,
          jurisdictionCommanderyMismatch: true,
          ownershipMismatch: true,
        } : {}),
      } : null;
      return (
        <div data-testid="shared-iso-map" data-map-code={props.mapCode}>
          <button
            type="button"
            onClick={() => props.onCountyHover?.(county, { x: 30, y: 40 })}
          >
            hover first county
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
  width: 700,
  height: 610,
  cities: [
    { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 6, supply: true, isCapital: true },
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
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect() {}
  });
});

describe('MapPreview shared isometric renderer', () => {
  it('passes the real han map code and every city overlay without CDN map nodes', () => {
    const mapCode = 'ha n&?';
    render(<MapPreview serverId="s 1&?" mapData={{ ...MAP, mapCode }} currentCityId={11} />);

    expect(screen.getByTestId('shared-iso-map')).toHaveAttribute('data-map-code', mapCode);
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.props?.currentCityId).toBe(11);
    expect(shared.props?.cities?.[0]).toMatchObject({
      id: 11,
      nationName: '위',
      nationColor: '#ff0000',
      state: 6,
      isCapital: true,
    });
    const url = typeof shared.props?.terrainUrl === 'function'
      ? shared.props.terrainUrl(mapCode)
      : shared.props?.terrainUrl;
    expect(url).toBe('/api/game/api/map/terrain?server=s%201%26%3F&mapCode=ha%20n%26%3F');
    const provinceUrl = typeof shared.props?.provinceUrl === 'function'
      ? shared.props.provinceUrl(mapCode)
      : shared.props?.provinceUrl;
    expect(provinceUrl).toBe('/api/game/api/map/provinces?server=s%201%26%3F&mapCode=ha%20n%26%3F');
  });

  it('shows the lobby region commandery and county through the polygon callback', () => {
    render(<MapPreview mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover first county' }));
    expect(screen.getByRole('status')).toHaveTextContent('경조윤 장안현');
    expect(screen.getByRole('status')).not.toHaveTextContent('사예');
    expect(screen.getByRole('status')).not.toHaveTextContent('【');
    expect(screen.getByRole('status')).toHaveTextContent('위');
  });

  it('게이트웨이에서도 소유권을 전달하고 기본 툴팁은 계층 경로와 현재 레이어 소유자만 표시한다', () => {
    render(<MapPreview mapData={{
      ...MAP,
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1 }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 2 }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 3 }],
      nations: [
        ...MAP.nations,
        { id: 2, name: '한', color: '#0000ff' },
        { id: 3, name: '조', color: '#00ff00' },
      ],
    }} />);

    expect(shared.props?.administrativeOwnership).toEqual({
      provinceOccupancy: [{ provinceRecordId: 'P1', provinceIndex: 0, nationId: 1, nationColor: '#ff0000', nationName: '위' }],
      jurisdictionOwnership: [{ jurisdictionId: 'J1', nationId: 2, nationColor: '#0000ff', nationName: '한' }],
      commanderyControl: [{ commanderyId: 'C1', nationId: 3, nationColor: '#00ff00', nationName: '조' }],
    });
    fireEvent.click(screen.getByRole('button', { name: 'hover first county' }));
    expect(document.querySelectorAll('.map-preview-tooltip-meta')).toHaveLength(1);
    // #638(050058c7) 이후 기본 툴팁은 현재 레이어(현) 소유자 이름만 보여 준다 — 계층 경로/다른 레이어 소유자는 없다.
    expect(document.querySelector('.map-preview-tooltip-meta')).toHaveTextContent('한');
    expect(document.querySelector('.map-preview-tooltip-meta')).not.toHaveTextContent('공간:');
    expect(document.querySelector('.map-preview-tooltip-meta')).not.toHaveTextContent('군국:');
    expect(document.querySelector('.map-preview-tooltip-meta')).not.toHaveTextContent('→');
    expect(screen.getByRole('status')).not.toHaveTextContent('공간 점유:');
    expect(screen.getByRole('status')).not.toHaveTextContent('현 소유:');
    expect(screen.getByRole('status')).not.toHaveTextContent('군국 통제:');
    expect(screen.getByRole('status')).not.toHaveTextContent('다릅니다.');
  });

  it.each([
    ['unknown nation', 2, []],
    ['malformed color', 1, [{ id: 1, name: '표시 금지', color: 'red' }]],
    ['NaN nation', Number.NaN, [{ id: Number.NaN, name: '표시 금지', color: '#ff0000' }]],
    ['infinite nation', Number.POSITIVE_INFINITY, [{ id: Number.POSITIVE_INFINITY, name: '표시 금지', color: '#ff0000' }]],
    ['fractional nation', 1.5, [{ id: 1.5, name: '표시 금지', color: '#ff0000' }]],
    ['zero nation', 0, [{ id: 0, name: '표시 금지', color: '#ff0000' }]],
    ['negative nation', -1, [{ id: -1, name: '표시 금지', color: '#ff0000' }]],
  ])('keeps %s ownership neutral in canvas props and tooltip', (_label, nationId, nations) => {
    render(<MapPreview mapData={{
      ...MAP,
      cities: [{ ...MAP.cities[0], nationId }],
      nations,
    }} />);

    expect(shared.props?.cities?.[0]).toMatchObject({
      nationId,
      nationName: undefined,
      nationColor: undefined,
    });
    fireEvent.click(screen.getByRole('button', { name: 'hover first county' }));
    expect(screen.getByRole('status')).toHaveTextContent('장안현');
    expect(screen.getByRole('status')).not.toHaveTextContent('표시 금지');
  });
});
