import { BREAKPOINTS } from './breakpoints';

/**
 * 시험용 matchMedia — 창 폭 하나를 받아 **조건마다** 참 · 거짓을 답한다(min-width · max-width 를 읽는다).
 * useViewportClass 를 쓰는 화면을 시험할 때는 이것을 쓴다. 모든 쿼리에 같은 값을 주는 흉내는 실제 폭과 무관하다 — 늘 false 면
 * 훅이 null(「재기 전」)에 머물러 null 이면 기본 배치로 두는 화면이 그대로 멈추고(K4 발견), 늘 true 면 늘 mobile 이다.
 *
 *   const viewport = installViewport(390);  // mobile
 *   act(() => viewport.resize(1440));       // desktop — 구독자에게 change 를 알린다
 *   viewport.restore();
 */
export function installViewport(width: number): { readonly resize: (next: number) => void; readonly restore: () => void } {
  let current = width;
  const listeners = new Set<() => void>();
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => {
    const matches = () => mediaMatches(query, current);
    return {
      get matches() { return matches(); },
      media: query,
      onchange: null,
      addEventListener: (_type: string, fn: () => void) => listeners.add(fn),
      removeEventListener: (_type: string, fn: () => void) => listeners.delete(fn),
      addListener: (fn: () => void) => listeners.add(fn),
      removeListener: (fn: () => void) => listeners.delete(fn),
      dispatchEvent: () => false,
    } as unknown as MediaQueryList;
  }) as typeof window.matchMedia;
  return {
    resize(next: number) {
      current = next;
      for (const fn of [...listeners]) fn();
    },
    restore() {
      window.matchMedia = original;
      listeners.clear();
    },
  };
}

/** `(min-width: Npx)` · `(max-width: Npx)` 를 `and` 로 이은 쿼리를 창 폭에 맞춰 판정한다. 모르는 조건은 거짓. */
export function mediaMatches(query: string, width: number): boolean {
  const parts = query.split(/\s+and\s+/i).map((part) => part.trim());
  return parts.length > 0 && parts.every((part) => {
    const min = /^\(min-width:\s*([\d.]+)px\)$/i.exec(part);
    if (min) return width >= Number(min[1]);
    const max = /^\(max-width:\s*([\d.]+)px\)$/i.exec(part);
    if (max) return width <= Number(max[1]);
    return false;
  });
}

/** 단마다 대표 폭(보드 캡처 폭) — 시험에서 폭을 지어내지 않게. */
export const VIEWPORT_WIDTHS = { mobile: 390, tablet: BREAKPOINTS.tablet + 256, desktop: 1440 } as const;
