import { act, render } from '@testing-library/react';
import React, { useRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { measureAvoidRects, useAvoidRects, type AvoidRect } from '@/lib/useAvoidRects';

// jsdom 은 배치를 모른다 — 상자를 정해 준다.
function rectOf(left: number, top: number, width: number, height: number): DOMRect {
    return { left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) } as DOMRect;
}

function place(el: Element, r: DOMRect) {
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue(r);
}

describe('measureAvoidRects', () => {
    afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); });

    it('지도 상자 기준으로 자르고, 겹치지 않거나 크기 없는 판은 뺀다', () => {
        document.body.innerHTML = '<div id="map"></div><div class="plate a"></div><div class="plate b"></div><div class="plate c"></div><div class="plate d"></div>';
        place(document.getElementById('map')!, rectOf(0, 100, 390, 480));
        const [a, b, c, d] = Array.from(document.querySelectorAll('.plate'));
        place(a, rectOf(12, 392, 220, 88)); // 로고 판 — 안에 든다
        place(b, rectOf(-20, 560, 100, 60)); // 지도 아래 끝에 걸친다 — 잘린다
        place(c, rectOf(0, 700, 390, 50)); // 지도 밖
        place(d, rectOf(10, 200, 0, 0)); // 크기 없음(숨김)
        expect(measureAvoidRects(document.getElementById('map')!, '.plate')).toEqual([
            { x: 12, y: 292, width: 220, height: 88 },
            { x: 0, y: 460, width: 80, height: 20 },
        ]);
    });
});

describe('useAvoidRects', () => {
    afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals(); });

    it('값이 같으면 같은 배열을 돌려주고, 판이 움직이면 새로 잰다', () => {
        let frameCb: FrameRequestCallback | null = null;
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { frameCb = cb; return 1; });
        vi.stubGlobal('cancelAnimationFrame', () => { frameCb = null; });
        const seen: (readonly AvoidRect[])[] = [];
        function Probe() {
            const ref = useRef<HTMLDivElement>(null);
            seen.push(useAvoidRects(ref, '.plate'));
            return (
                <>
                    <div ref={ref} id="map" />
                    <div className="plate" />
                </>
            );
        }
        const mapRect = rectOf(0, 0, 1440, 900);
        let plateRect = rectOf(32, 80, 520, 300);
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
            return this.id === 'map' ? mapRect : plateRect;
        });
        render(<Probe />);
        const first = seen[seen.length - 1];
        expect(first).toEqual([{ x: 32, y: 80, width: 520, height: 300 }]);

        // 같은 자리에서 다시 재면 새 배열을 만들지 않는다.
        act(() => { window.dispatchEvent(new Event('resize')); frameCb?.(0); });
        expect(seen[seen.length - 1]).toBe(first);

        // 판이 움직이면(스크롤) 새 상자.
        plateRect = rectOf(32, 40, 520, 300);
        act(() => { window.dispatchEvent(new Event('scroll')); frameCb?.(0); });
        expect(seen[seen.length - 1]).toEqual([{ x: 32, y: 40, width: 520, height: 300 }]);
    });

    it('selector 가 없으면 빈 배열', () => {
        function Probe() {
            const ref = useRef<HTMLDivElement>(null);
            const rects = useAvoidRects(ref, null);
            return <div ref={ref} data-n={rects.length} />;
        }
        const { container } = render(<Probe />);
        expect(container.firstElementChild).toHaveAttribute('data-n', '0');
    });
});
