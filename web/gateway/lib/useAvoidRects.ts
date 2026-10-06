'use client';

import { useEffect, useRef, useState, type RefObject } from 'react';

/** 지도 상자 기준 CSS px 상자(공용 지도 `labelAvoid` 와 같은 모양). */
export interface AvoidRect {
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

const NONE: readonly AvoidRect[] = [];

/** 지도 상자(`root`)와 겹치는 판(`selector`)들의 상자를 root 기준으로 잰다. 겹치지 않거나 보이지 않는 판은 뺀다. */
export function measureAvoidRects(root: Element, selector: string): AvoidRect[] {
    const base = root.getBoundingClientRect();
    const out: AvoidRect[] = [];
    for (const el of Array.from(document.querySelectorAll(selector))) {
        const r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) continue;
        const left = Math.max(r.left, base.left);
        const top = Math.max(r.top, base.top);
        const right = Math.min(r.right, base.right);
        const bottom = Math.min(r.bottom, base.bottom);
        if (right <= left || bottom <= top) continue;
        out.push({
            x: Math.round(left - base.left),
            y: Math.round(top - base.top),
            width: Math.round(right - left),
            height: Math.round(bottom - top),
        });
    }
    return out;
}

function same(a: readonly AvoidRect[], b: readonly AvoidRect[]): boolean {
    return a.length === b.length && a.every((r, i) => r.x === b[i].x && r.y === b[i].y && r.width === b[i].width && r.height === b[i].height);
}

/**
 * 지도 위에 떠 있는 고정 판(로고 판 · 로그인 패널 등)의 상자 — 지도 이름표가 그 밑에 숨지 않게 넘긴다(K10 실지도 10-03).
 * 판 · 지도 크기가 바뀌거나 쪽이 스크롤되면 다시 잰다. 값이 같으면 같은 배열을 돌려준다(지도가 다시 그리지 않게).
 */
export function useAvoidRects(root: RefObject<HTMLElement | null>, selector: string | null, key: string | number = 0): readonly AvoidRect[] {
    const [rects, setRects] = useState<readonly AvoidRect[]>(NONE);
    const last = useRef<readonly AvoidRect[]>(NONE);

    useEffect(() => {
        const box = root.current;
        if (!box || !selector) return undefined;
        let frame = 0;
        const measure = () => {
            frame = 0;
            const next = measureAvoidRects(box, selector);
            if (same(next, last.current)) return;
            last.current = next;
            setRects(next);
        };
        const schedule = () => { if (!frame) frame = requestAnimationFrame(measure); };
        measure();
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
        observer?.observe(box);
        for (const el of Array.from(document.querySelectorAll(selector))) observer?.observe(el);
        window.addEventListener('resize', schedule);
        window.addEventListener('scroll', schedule, { passive: true });
        return () => {
            if (frame) cancelAnimationFrame(frame);
            observer?.disconnect();
            window.removeEventListener('resize', schedule);
            window.removeEventListener('scroll', schedule);
        };
        // key: 피할 판이 나중에 생기면(지도 조작을 다른 자리로 내보낼 때 등) 부른 쪽이 바꿔 다시 잰다.
    }, [root, selector, key]);

    return selector ? rects : NONE;
}
