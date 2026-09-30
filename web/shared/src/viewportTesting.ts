import { BREAKPOINTS } from './breakpoints';

/**
 * 시험용 matchMedia — 창 폭 하나를 받아 **조건마다** 참 · 거짓을 답한다(min-width · max-width 를 읽는다).
 * useViewportClass 를 쓰는 화면을 시험할 때는 이것을 쓴다. 모든 쿼리에 같은 값을 주는 흉내는 실제 폭과 무관하다 — 늘 false 면
 * 훅이 null(「재기 전」)에 머물러 null 이면 기본 배치로 두는 화면이 그대로 멈추고(K4 발견), 늘 true 면 늘 mobile 이다.
 *
 *   const viewport = installViewport(390);  // mobile
 *   act(() => viewport.resize(1440));       // desktop — 값이 뒤집힌 쿼리의 구독자에게만 { matches, media } 를 알린다
 *   viewport.restore();
 *
 * matchMedia 만 흉내 낸다 — `window.innerWidth` · `resize` 이벤트는 바꾸지 않는다(그것을 읽는 부품은 따로 흉내 낸다).
 */
export function installViewport(width: number): {
  readonly resize: (next: number) => void;
  /** 지금 붙어 있는 리스너 수(모든 쿼리 합) — 풀린 컴포넌트가 리스너를 뗐는지 단언할 때. */
  readonly listenerCount: () => number;
  readonly restore: () => void;
} {
  type Listener = (event: MediaQueryListEvent) => void;
  interface Query { readonly media: string; last: boolean; readonly listeners: Set<Listener> }
  let current = width;
  const queries: Query[] = [];
  const original = window.matchMedia;
  window.matchMedia = ((media: string) => {
    // 쿼리(MQL)마다 리스너 · 직전 값을 따로 둔다 — 값이 뒤집힌 쿼리에만 알린다(실제 브라우저와 같다).
    const query: Query = { media, last: mediaMatches(media, current), listeners: new Set() };
    queries.push(query);
    return {
      get matches() { return mediaMatches(media, current); },
      media,
      onchange: null,
      addEventListener: (_type: string, fn: Listener) => query.listeners.add(fn),
      removeEventListener: (_type: string, fn: Listener) => query.listeners.delete(fn),
      addListener: (fn: Listener) => query.listeners.add(fn),
      removeListener: (fn: Listener) => query.listeners.delete(fn),
      dispatchEvent: () => false,
    } as unknown as MediaQueryList;
  }) as typeof window.matchMedia;
  return {
    resize(next: number) {
      current = next;
      for (const query of queries) {
        const matches = mediaMatches(query.media, current);
        if (matches === query.last) continue;
        query.last = matches;
        const event = { matches, media: query.media } as MediaQueryListEvent;
        for (const fn of [...query.listeners]) fn(event);
      }
    },
    listenerCount() {
      return queries.reduce((n, query) => n + query.listeners.size, 0);
    },
    restore() {
      window.matchMedia = original;
      queries.length = 0;
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
