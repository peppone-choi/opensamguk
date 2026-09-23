import { describe, expect, it } from 'vitest';
import { drawCorpsOverlay, type MapCorpsOverlay } from '../iso/corpsOverlay';

/** 호출만 기록하는 가짜 2D 문맥 — jsdom 에는 캔버스가 없다. */
function fakeContext() {
    const calls: string[] = [];
    const state = { globalAlpha: 1, strokeStyle: '', fillStyle: '', font: '' };
    const record = (name: string) => (...args: unknown[]) => {
        calls.push(`${name}${name === 'fillText' ? `:${String(args[0])}` : ''}`);
    };
    const ctx = new Proxy(state as Record<string, unknown>, {
        get(target, prop: string) {
            if (prop in target) return target[prop];
            if (prop === 'measureText') return (text: string) => ({ width: text.length * 6 });
            return record(prop);
        },
        set(target, prop: string, value) {
            if (prop === 'globalAlpha') calls.push(`alpha:${value}`);
            target[prop] = value;
            return true;
        },
    });
    return { ctx: ctx as unknown as CanvasRenderingContext2D, calls };
}

const view = { scale: 10, ox: 0, oy: 0 };

describe('drawCorpsOverlay', () => {
    it('draws path and intercept range only for my own corps', () => {
        const own: MapCorpsOverlay = {
            id: 'a', col: 5, row: 5, label: '하후돈', own: true, interceptRadiusCells: 3,
            path: [{ col: 5, row: 5 }, { col: 6, row: 5 }, { col: 7, row: 6 }],
        };
        const enemy: MapCorpsOverlay = {
            id: 'b', col: 9, row: 9, label: '안량', own: false, color: '#7aa7c7',
            path: [{ col: 9, row: 9 }, { col: 8, row: 8 }], interceptRadiusCells: 2,
        };
        const { ctx, calls } = fakeContext();
        const hits = drawCorpsOverlay(ctx, [own, enemy], view, 1);
        expect(hits.map((h) => h.corps.id)).toEqual(['a', 'b']);
        // 내 군단: 범위(arc) 1번 + 경로(lineTo 2번). 적 군단의 경로·범위는 그리지 않는다.
        expect(calls.filter((c) => c === 'arc')).toHaveLength(1);
        expect(calls.filter((c) => c === 'lineTo').length).toBeGreaterThanOrEqual(2);
        expect(calls).toContain('fillText:하후돈');
        expect(calls).toContain('fillText:안량');
    });

    it('fades a stale sighting and marks it with ?', () => {
        const { ctx, calls } = fakeContext();
        drawCorpsOverlay(ctx, [{ id: 'c', col: 1, row: 1, label: '문추', own: false, stale: true }], view, 1);
        expect(calls).toContain('alpha:0.55');
        expect(calls).toContain('fillText:?');
    });

    it('shows the troop band next to the name when given', () => {
        const { ctx, calls } = fakeContext();
        drawCorpsOverlay(ctx, [{ id: 'd', col: 1, row: 1, label: '허저', troopsLabel: '1천 남짓', own: true }], view, 1);
        expect(calls).toContain('fillText:허저 · 1천 남짓');
    });
});
