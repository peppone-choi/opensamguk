// 실시간 전투 판 조작(보드 V31K6v2BattleLiveMany · D24 세부 1 · 2) — 사각형 안 내 부곡 고르기 · 축소했을 때 장수별 묶기 · 끌기 판정 · 판 움직이기.
import { describe, expect, it } from 'vitest';
import { clusterMarks, DRAG_THRESHOLD, idsInRect, isDrag, panCenter, toPicture } from '../lib/battle/board-select';

const marks = [
    { id: 'a', x: 100, y: 100, group: 1 },
    { id: 'b', x: 118, y: 108, group: 1 },
    { id: 'c', x: 112, y: 104, group: 2 },
    { id: 'd', x: 300, y: 200, group: 1 },
];

describe('판에서 고르기 — 사각형', () => {
    it('두 점(어느 방향이든)이 만든 사각형 안에 화면 중심이 든 부곡만, 경계 포함', () => {
        expect(idsInRect(marks, { x: 90, y: 90 }, { x: 120, y: 110 })).toEqual(['a', 'b', 'c']);
        expect(idsInRect(marks, { x: 120, y: 110 }, { x: 90, y: 90 })).toEqual(['a', 'b', 'c']);
        expect(idsInRect(marks, { x: 100, y: 100 }, { x: 100, y: 100 })).toEqual(['a']);
        expect(idsInRect(marks, { x: 0, y: 0 }, { x: 10, y: 10 })).toEqual([]);
    });

    it('끌기 판정 — 문턱 미만은 누르기, 이상은 끌기', () => {
        expect(isDrag({ x: 0, y: 0 }, { x: DRAG_THRESHOLD - 1, y: 0 })).toBe(false);
        expect(isDrag({ x: 0, y: 0 }, { x: DRAG_THRESHOLD, y: 0 })).toBe(true);
        expect(isDrag({ x: 0, y: 0 }, { x: 0, y: -DRAG_THRESHOLD })).toBe(true);
    });
});

describe('많을 때 묶기', () => {
    it('같은 장수 부곡이 가까우면 하나로(중심 · 수), 다른 장수는 겹쳐도 따로, 먼 부곡은 따로', () => {
        const got = clusterMarks(marks, 36);
        expect(got.map((c) => [c.group, c.ids])).toEqual([[1, ['a', 'b']], [2, ['c']], [1, ['d']]]);
        expect(got[0]).toMatchObject({ x: 109, y: 104 });
    });

    it('반경이 작으면 갈라진다', () => {
        expect(clusterMarks(marks, 10).map((c) => c.ids)).toEqual([['a'], ['b'], ['c'], ['d']]);
    });
});

describe('판 움직이기', () => {
    const view = { scale: 2, offsetX: -100, offsetY: 50 };
    it('화면 점 → 판 그림 좌표', () => {
        expect(toPicture({ x: 100, y: 150 }, view)).toEqual({ x: 100, y: 50 });
    });
    it('끈 만큼(배율로 나눠) 가운데가 반대로 움직이고, 판 그림 밖으로는 나가지 않는다', () => {
        const picture = { width: 1000, height: 600 };
        expect(panCenter({ x: 500, y: 300 }, { x: 40, y: -20 }, 2, picture)).toEqual({ x: 480, y: 310 });
        expect(panCenter({ x: 10, y: 590 }, { x: 100, y: -100 }, 1, picture)).toEqual({ x: 0, y: 600 });
    });
});
