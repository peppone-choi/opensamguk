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
      return (
        <div data-testid="shared-iso-map" data-map-code={props.mapCode}>
          <button
            type="button"
            onClick={() => props.onCityHover?.(props.cities?.[0] ?? null, { x: 30, y: 40 })}
          >
            hover first city
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

  it('keeps the lobby tooltip through the canvas hover callback', () => {
    render(<MapPreview mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover first city' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).toHaveTextContent('위');
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
    fireEvent.click(screen.getByRole('button', { name: 'hover first city' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).not.toHaveTextContent('표시 금지');
  });
});
