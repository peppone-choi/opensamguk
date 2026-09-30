import { act, render, screen } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { MEDIA } from '../breakpoints';
import { useViewportClass } from '../useViewportClass';

function Probe() {
  const v = useViewportClass();
  return <output data-testid="v">{v ?? 'null'}</output>;
}

type Listener = () => void;
const original = window.matchMedia;
let width = 1440;
const listeners = new Set<Listener>();

function install() {
  window.matchMedia = ((query: string) => {
    const matches = () => {
      if (query === MEDIA.mobile) return width < 768;
      if (query === MEDIA.tablet) return width >= 768 && width < 1200;
      if (query === MEDIA.desktop) return width >= 1200;
      return false;
    };
    return {
      get matches() { return matches(); },
      media: query,
      addEventListener: (_: string, fn: Listener) => listeners.add(fn),
      removeEventListener: (_: string, fn: Listener) => listeners.delete(fn),
    } as unknown as MediaQueryList;
  }) as typeof window.matchMedia;
}

afterEach(() => { window.matchMedia = original; listeners.clear(); width = 1440; });

describe('useViewportClass', () => {
  it('재기 전(SSR)에는 null 이다 — 첫 그림은 CSS 기본 배치', () => {
    install();
    expect(renderToString(<Probe />)).toContain('null');
  });

  it('matchMedia 로 단을 재고 창 크기가 바뀌면 갱신하며, 풀 때 리스너를 뗀다', () => {
    install();
    width = 390;
    const { unmount } = render(<Probe />);
    expect(screen.getByTestId('v')).toHaveTextContent('mobile');
    act(() => { width = 900; for (const fn of listeners) fn(); });
    expect(screen.getByTestId('v')).toHaveTextContent('tablet');
    act(() => { width = 1440; for (const fn of listeners) fn(); });
    expect(screen.getByTestId('v')).toHaveTextContent('desktop');
    unmount();
    expect(listeners.size).toBe(0);
  });
});
