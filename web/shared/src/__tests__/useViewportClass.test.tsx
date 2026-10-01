import { act, render, screen } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { useViewportClass } from '../useViewportClass';
import { installViewport } from '../viewportTesting';

function Probe() {
  const v = useViewportClass();
  return <output data-testid="v">{v ?? 'null'}</output>;
}

let restore: (() => void) | null = null;
afterEach(() => { restore?.(); restore = null; });

describe('useViewportClass', () => {
  it('재기 전(SSR)에는 null 이다 — 첫 그림은 CSS 기본 배치', () => {
    restore = installViewport(1440).restore;
    expect(renderToString(<Probe />)).toContain('null');
  });

  it('matchMedia 로 단을 재고 창 크기가 바뀌면 갱신하며, 풀면 더 알림을 받지 않는다', () => {
    const viewport = installViewport(390);
    restore = viewport.restore;
    const { unmount } = render(<Probe />);
    expect(screen.getByTestId('v')).toHaveTextContent('mobile');
    act(() => viewport.resize(900));
    expect(screen.getByTestId('v')).toHaveTextContent('tablet');
    act(() => viewport.resize(1440));
    expect(screen.getByTestId('v')).toHaveTextContent('desktop');
    expect(viewport.listenerCount()).toBeGreaterThan(0);
    unmount();
    // 풀면 리스너를 모두 뗀다 — 훅의 정리 함수를 지우면 여기서 빨개진다(적색 확인)
    expect(viewport.listenerCount()).toBe(0);
  });
});
