// OPENSAM-201 아이소 투영·뷰포트 수학 검증. DOM 없이 순수 함수만 본다.
import { describe, it, expect } from 'vitest';
import {
    cellToScreen, screenToCell, visibleCells, fitScale, zoomAt, clampView, centeredView,
    viewAt, scaleForSpan, junSpanCells, MAX_SCALE, type IsoView,
} from '@/lib/isoMap';

const G = { cols: 768, rows: 669 };

describe('아이소 투영', () => {
    const v: IsoView = { scale: 4, ox: 100, oy: 50 };

    it('역변환이 원래 셀로 돌아온다', () => {
        for (const [c, r] of [[0, 0], [767, 668], [376, 233], [1, 500]]) {
            const [sx, sy] = cellToScreen(c, r, v);
            const [c2, r2] = screenToCell(sx, sy, v);
            expect(c2).toBeCloseTo(c, 9);
            expect(r2).toBeCloseTo(r, 9);
        }
    });

    it('타일이 2:1 다이아몬드다 — col+1 과 row+1 이 화면에서 좌우 대칭이다', () => {
        const [x0, y0] = cellToScreen(10, 10, v);
        const [xc, yc] = cellToScreen(11, 10, v);
        const [xr, yr] = cellToScreen(10, 11, v);
        expect(xc - x0).toBeCloseTo(v.scale);
        expect(x0 - xr).toBeCloseTo(v.scale);
        expect(yc - y0).toBeCloseTo(v.scale / 2);
        expect(yr - y0).toBeCloseTo(v.scale / 2);
    });
});

describe('컬링', () => {
    it('화면 밖 셀을 잘라낸다 — 513k 셀 중 일부만 남는다', () => {
        const v = { scale: 8, ox: 0, oy: 0 };
        const box = visibleCells(800, 600, v, G);
        const kept = (box.col1 - box.col0 + 1) * (box.row1 - box.row0 + 1);
        expect(kept).toBeLessThan(G.cols * G.rows / 4);
    });

    it('보이는 셀은 컬링 박스 안에 있다', () => {
        const v = { scale: 6, ox: -1200, oy: -300 };
        const box = visibleCells(800, 600, v, G);
        for (let r = 0; r < G.rows; r += 37) {
            for (let c = 0; c < G.cols; c += 41) {
                const [sx, sy] = cellToScreen(c, r, v);
                if (sx < 0 || sx > 800 || sy < 0 || sy > 600) continue;
                expect(c).toBeGreaterThanOrEqual(box.col0);
                expect(c).toBeLessThanOrEqual(box.col1);
                expect(r).toBeGreaterThanOrEqual(box.row0);
                expect(r).toBeLessThanOrEqual(box.row1);
            }
        }
    });

    it('격자 밖으로 넘지 않는다', () => {
        const box = visibleCells(800, 600, centeredView(800, 600, G), G);
        expect(box.col0).toBeGreaterThanOrEqual(0);
        expect(box.row0).toBeGreaterThanOrEqual(0);
        expect(box.col1).toBeLessThanOrEqual(G.cols - 1);
        expect(box.row1).toBeLessThanOrEqual(G.rows - 1);
    });
});

describe('줌', () => {
    it('커서 밑 셀이 확대 후에도 커서 밑에 남는다', () => {
        const v = { scale: 3, ox: 40, oy: 20 };
        const [before] = [screenToCell(500, 300, v)];
        const z = zoomAt(v, 500, 300, 1.8, 0.5);
        const after = screenToCell(500, 300, z);
        expect(after[0]).toBeCloseTo(before[0], 6);
        expect(after[1]).toBeCloseTo(before[1], 6);
    });

    it('배율이 하한·상한에 갇힌다', () => {
        const v = { scale: 3, ox: 0, oy: 0 };
        expect(zoomAt(v, 0, 0, 0.01, 1.5).scale).toBe(1.5);
        expect(zoomAt(v, 0, 0, 1000, 0.1).scale).toBe(MAX_SCALE);
    });

    it('fit 배율에서 격자 전체가 화면에 들어간다', () => {
        const s = fitScale(800, 600, G);
        const v = centeredView(800, 600, G);
        expect(v.scale).toBe(s);
        for (const [c, r] of [[0, 0], [G.cols - 1, 0], [0, G.rows - 1], [G.cols - 1, G.rows - 1]]) {
            const [sx, sy] = cellToScreen(c, r, v);
            expect(sx).toBeGreaterThanOrEqual(-1);
            expect(sx).toBeLessThanOrEqual(801);
            expect(sy).toBeGreaterThanOrEqual(-1);
            expect(sy).toBeLessThanOrEqual(601);
        }
    });
});

describe('팬 클램프', () => {
    it('격자를 화면 밖으로 완전히 밀어낼 수 없다', () => {
        const v = { scale: 5, ox: 999999, oy: 999999 };
        const c = clampView(v, 800, 600, G);
        const box = visibleCells(800, 600, c, G);
        expect(box.col1).toBeGreaterThanOrEqual(box.col0);
        expect(box.row1).toBeGreaterThanOrEqual(box.row0);
    });

    it('확대 상태에서 격자 구석까지 끌린다 — 중심 셀 고정이 아니다', () => {
        const c = clampView({ scale: 5, ox: -999999, oy: -999999 }, 800, 600, G);
        const box = visibleCells(800, 600, c, G);
        expect(box.col1).toBe(G.cols - 1);
        expect(box.row1).toBe(G.rows - 1);
    });

    it('화면 안에 있는 뷰는 건드리지 않는다', () => {
        const v = centeredView(800, 600, G);
        expect(clampView(v, 800, 600, G)).toEqual(v);
    });
});

describe('첫 화면 배율 — 郡 두세 개', () => {
    // 郡治가 24칸 간격 격자로 놓인 가상 세계. 최근접 거리가 곧 郡 한 칸의 폭이다.
    const juns: { col: number; row: number }[] = [];
    for (let r = 0; r < 5; r++) for (let c = 0; c < 5; c++) juns.push({ col: c * 24, row: r * 24 });

    it('郡 폭을 최근접 거리로 잡는다', () => {
        expect(junSpanCells(juns)).toBeCloseTo(24);
    });

    it('郡治가 하나뿐이면 기본값으로 버틴다', () => {
        expect(junSpanCells([{ col: 3, row: 3 }])).toBe(24);
    });

    it('지정한 셀이 화면 정중앙에 온다', () => {
        const v = viewAt(800, 600, 120, 90, 5);
        const [x, y] = cellToScreen(120, 90, v);
        expect(x).toBeCloseTo(400);
        expect(y).toBeCloseTo(300);
    });

    it('첫 화면에 郡이 두세 개 폭으로 담긴다 — 전체 격자보다 훨씬 크게 당겨져 있다', () => {
        const span = 3 * junSpanCells(juns);
        const s = scaleForSpan(800, 600, span);
        // 화면 가로에 span 칸이 정확히 담긴다(아이소는 가로 2·span·scale).
        expect(2 * span * s).toBeLessThanOrEqual(800 + 1e-9);
        expect(s).toBeGreaterThan(fitScale(800, 600, G));
    });

    it('배율 상한을 넘지 않는다', () => {
        expect(scaleForSpan(800, 600, 1)).toBe(MAX_SCALE);
    });
});
