import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MEDIA } from '../breakpoints';
import { useViewportClass } from '../useViewportClass';
import { VIEWPORT_WIDTHS, installViewport, mediaMatches } from '../viewportTesting';

function Probe() {
  return <output data-testid="v">{useViewportClass() ?? 'null'}</output>;
}

let restore: (() => void) | null = null;
afterEach(() => { restore?.(); restore = null; });

describe('installViewport — 조건마다 답하는 matchMedia 흉내', () => {
  it('세 단의 쿼리가 겹치지 않는다 — 폭마다 정확히 하나만 참', () => {
    for (const width of [0, 390, 767, 767.98, 768, 1000, 1199.98, 1200, 1440, 3000]) {
      const hits = (['mobile', 'tablet', 'desktop'] as const).filter((name) => mediaMatches(MEDIA[name], width));
      expect(hits, String(width)).toHaveLength(1);
    }
    expect(mediaMatches('(prefers-color-scheme: dark)', 1440)).toBe(false);
  });

  it('useViewportClass 가 단을 재고, resize 에 따라 바뀐다', () => {
    const viewport = installViewport(VIEWPORT_WIDTHS.mobile);
    restore = viewport.restore;
    render(<Probe />);
    expect(screen.getByTestId('v')).toHaveTextContent('mobile');
    act(() => viewport.resize(VIEWPORT_WIDTHS.tablet));
    expect(screen.getByTestId('v')).toHaveTextContent('tablet');
    act(() => viewport.resize(VIEWPORT_WIDTHS.desktop));
    expect(screen.getByTestId('v')).toHaveTextContent('desktop');
  });

  it('값이 뒤집힌 쿼리에만 { matches, media } 이벤트를 보낸다 — 같은 단 안의 폭 변화는 조용하다', () => {
    const viewport = installViewport(1300);
    restore = viewport.restore;
    const desktop = window.matchMedia(MEDIA.desktop);
    const mobile = window.matchMedia(MEDIA.mobile);
    const seen: Array<{ media: string; matches: boolean }> = [];
    const fn = (event: MediaQueryListEvent) => seen.push({ media: event.media, matches: event.matches });
    desktop.addEventListener('change', fn);
    mobile.addEventListener('change', fn);
    viewport.resize(1440); // 여전히 desktop — 아무도 뒤집히지 않는다
    expect(seen).toEqual([]);
    viewport.resize(390); // desktop 꺼짐 · mobile 켜짐
    expect(seen).toEqual([{ media: MEDIA.desktop, matches: false }, { media: MEDIA.mobile, matches: true }]);
  });

  it('리스너는 그 쿼리에만 붙는다 — 다른 쿼리만 뒤집히면 불리지 않고, 뗀 뒤로는 알리지 않는다', () => {
    const viewport = installViewport(390);
    restore = viewport.restore;
    const desktop = window.matchMedia(MEDIA.desktop);
    let count = 0;
    const fn = () => { count += 1; };
    desktop.addEventListener('change', fn);
    viewport.resize(1000); // mobile → tablet: desktop 은 그대로 거짓
    expect(count).toBe(0);
    viewport.resize(1440); // desktop 켜짐
    expect(count).toBe(1);
    desktop.removeEventListener('change', fn);
    viewport.resize(390);
    expect(count).toBe(1);
  });

  it('함정 재현: 모든 쿼리에 같은 값을 주는 흉내는 null 에 머문다(그래서 이 도우미를 쓴다)', () => {
    const original = window.matchMedia;
    window.matchMedia = ((q: string) => ({ matches: true, media: q, addEventListener: () => {}, removeEventListener: () => {} })) as unknown as typeof window.matchMedia;
    restore = () => { window.matchMedia = original; };
    render(<Probe />);
    // mobile 쿼리가 먼저 맞으니 mobile — 늘 false 면 null. 둘 다 실제 폭과 무관한 답이다.
    expect(screen.getByTestId('v')).toHaveTextContent('mobile');
    window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} })) as unknown as typeof window.matchMedia;
    render(<Probe />);
    expect(screen.getAllByTestId('v')[1]).toHaveTextContent('null');
  });
});
