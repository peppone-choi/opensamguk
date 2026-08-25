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
      return (
        <div data-testid="shared-iso-map" data-map-code={props.mapCode}>
          <button type="button" onClick={() => city && props.onCityHover?.(city, { x: 20, y: 30 })}>hover city</button>
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
    render(<MapViewer mapData={MAP} currentCityId={11} selectedCityId={22} />);
    expect(screen.getByTestId('shared-iso-map')).toHaveAttribute('data-map-code', 'han');
    expect(document.querySelector('.map-bg')).toBeNull();
    expect(document.querySelector('.map-road')).toBeNull();
    expect(shared.props?.currentCityId).toBe(11);
    expect(shared.props?.selectedCityId).toBe(22);
    expect(shared.props?.cities).toEqual([
      expect.objectContaining({ id: 11, nationColor: '#ff0000', nationName: '위', state: 6, supply: true, isCapital: true }),
      expect.objectContaining({ id: 22, nationColor: '#ff0000', supply: false }),
    ]);
  });

  it('keeps hover tooltip content through the canvas callback', () => {
    render(<MapViewer mapData={MAP} />);
    fireEvent.click(screen.getByRole('button', { name: 'hover city' }));
    expect(screen.getByRole('status')).toHaveTextContent('낙양');
    expect(screen.getByRole('status')).toHaveTextContent('위');
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
