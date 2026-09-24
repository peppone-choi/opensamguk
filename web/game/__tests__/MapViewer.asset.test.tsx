import { render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HanMapCanvas as HanMapCanvasType } from '@opensamguk/ui';
import type { MapPreviewResponse } from '@/lib/types';

const shared = vi.hoisted(() => ({
  props: null as ComponentProps<typeof HanMapCanvasType> | null,
  fetch: vi.fn(),
}));
vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual, HanMapCanvas: (props: ComponentProps<typeof HanMapCanvasType>) => {
    shared.props = props;
    return <div data-testid="shared-map" />;
  } };
});
import MapViewer from '@/components/game/MapViewer';

const MAP: MapPreviewResponse = {
  serverName: '테스트섭', year: 200, month: 5, mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 0, x: 300, y: 250, state: 0, supply: true, isCapital: false }],
  nations: [],
};

beforeEach(() => {
  shared.props = null;
  shared.fetch.mockReset().mockImplementation(async (input: string) => input.includes('/terrain?')
    ? { ok: true, headers: { get: () => null }, json: async () => ({ _meta: { cols: 768, rows: 669 }, juns: [] }) }
    : { ok: false, status: 404 });
  vi.stubGlobal('fetch', shared.fetch);
  vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {}, clear() {}, key: () => null, length: 0 });
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {} }));
});

describe('MapViewer served map assets', () => {
  it('passes the served board and centered markers without requesting sprite backgrounds', async () => {
    render(<MapViewer mapData={MAP} />);
    await screen.findByTestId('shared-map');
    expect(shared.props?.tiles?._meta.cols).toBe(768);
    expect(shared.props?.markerPositions?.has(11)).toBe(true);
    expect(shared.props?.terrainUrl).toBeUndefined();
    expect(shared.fetch.mock.calls.filter(([url]) => String(url).includes('/terrain?'))).toHaveLength(1);
    expect(document.querySelector('.map-bg, .map-road, img[src*="/game/map/che/"]')).toBeNull();
  });

  it('rejects a different board before loading terrain', async () => {
    render(<MapViewer mapData={{ ...MAP, mapCode: 'old-board' }} />);
    expect(await screen.findByText('지원하지 않는 지도 판: old-board')).toBeInTheDocument();
    expect(shared.fetch).not.toHaveBeenCalled();
  });
});
