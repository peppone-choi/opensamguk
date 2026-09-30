'use client';

import { useEffect, useState } from 'react';
import { MEDIA, type ViewportClass } from './breakpoints';

/**
 * 지금 화면 폭의 단(모바일 < 768 ≤ 태블릿 < 1200 ≤ 데스크톱). `matchMedia` 로 재고, 창 크기가 바뀌면 갱신한다.
 *
 * **재기 전에는 null 이다** — SSR 과 첫 그림에서는 모른다. 부른 쪽은 null 일 때 CSS 기본 배치를 그대로 두고, 단이 정해진
 * 뒤에만 부품 트리를 바꾼다(첫 그림 깜빡임 · 수화 불일치 없게).
 *
 * 쓰는 규칙: **배치 차이는 CSS 미디어 쿼리가 먼저다.** 이 훅은 구조(부품 트리)가 달라야 할 때만 쓴다 — 예: 데스크톱은
 * 옆 열 패널, 모바일은 하단 시트처럼 다른 부품을 그릴 때. 크기 · 여백 · 줄바꿈 차이는 CSS 로 한다.
 */
export function useViewportClass(): ViewportClass | null {
  const [viewport, setViewport] = useState<ViewportClass | null>(null);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const queries = (['mobile', 'tablet', 'desktop'] as const).map((name) => [name, window.matchMedia(MEDIA[name])] as const);
    const measure = () => setViewport(queries.find(([, query]) => query.matches)?.[0] ?? null);
    measure();
    for (const [, query] of queries) query.addEventListener('change', measure);
    return () => { for (const [, query] of queries) query.removeEventListener('change', measure); };
  }, []);
  return viewport;
}
