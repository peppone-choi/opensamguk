import { render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse } from '@/lib/types';

const shared = vi.hoisted(() => ({ props: null as ComponentProps<typeof HanMapCanvasType> | null }));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
    shared.props = props;
    return <div data-testid="shared-iso-map" />;
  } };
});

import MapViewer from '@/components/game/MapViewer';

const MAP: MapPreviewResponse = {
  serverName: '테스트섭', year: 200, month: 5, mapCode: 'han', width: 700, height: 610,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 0, x: 300, y: 250, state: 0, supply: true, isCapital: false }],
  nations: [],
};

describe('MapViewer asset-independent terrain selection', () => {
  it('requests han tiles and never renders a che background or road image', () => {
    const mapCode = 'ha n&?';
    vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {}, clear() {}, key: () => null, length: 0 });
    vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {} }));
    render(<MapViewer mapData={{ ...MAP, mapCode }} />);
    expect(screen.getByTestId('shared-iso-map')).toBeInTheDocument();
    const url = typeof shared.props?.terrainUrl === 'function' ? shared.props.terrainUrl(mapCode) : shared.props?.terrainUrl;
    expect(url).toBe('/api/game/api/map/terrain?mapCode=ha%20n%26%3F');
    const provinceUrl = typeof shared.props?.provinceUrl === 'function' ? shared.props.provinceUrl(mapCode) : shared.props?.provinceUrl;
    expect(provinceUrl).toBe('/api/game/api/map/provinces?mapCode=ha%20n%26%3F');
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(document.querySelector('img[src*="/game/map/che/"]')).toBeNull();
  });
});
