import { describe, expect, it } from 'vitest';
import { cityFootprintBlock, cityFootprintSpan, resolveCityFootprints } from '../iso/cityFootprint';

describe('성내 칸', () => {
    it('등급이 높은 城 이 넓다 — 경 7 · 특 5 · 대 5 · 중 3 · 나머지 1(영현 포함)', () => {
        expect(cityFootprintSpan(9)).toBe(7); // 경
        expect(cityFootprintSpan(8)).toBe(5); // 특
        expect(cityFootprintSpan(7)).toBe(5); // 대
        expect(cityFootprintSpan(6)).toBe(3); // 중
        expect(cityFootprintSpan(5)).toBe(1); // 소
        expect(cityFootprintSpan(10)).toBe(1); // 영현 — 밀집지 겹침 때문에 1칸
        expect(cityFootprintSpan(11)).toBe(1); // 장현 — 縣 대부분
    });

    it('변은 모두 홀수다 — 城 마커 칸이 늘 한가운데 칸이 된다', () => {
        for (let level = 0; level <= 12; level += 1) {
            expect(cityFootprintSpan(level) % 2).toBe(1);
        }
    });

    it('모르는 등급은 한 칸으로 둔다 — 지도를 잡아먹지 않는다', () => {
        expect(cityFootprintSpan(0)).toBe(1);
        expect(cityFootprintSpan(99)).toBe(1);
    });

    it('마커 칸이 성내 중심이다', () => {
        expect(cityFootprintBlock(6, 10, 20)).toEqual({ col0: 9, row0: 19, span: 3 }); // 중 3
        expect(cityFootprintBlock(7, 10, 20)).toEqual({ col0: 8, row0: 18, span: 5 }); // 대 5
        expect(cityFootprintBlock(9, 10, 20)).toEqual({ col0: 7, row0: 17, span: 7 }); // 경 7
    });

    it('겹치면 큰 城 이 칸을 갖고 작은 城 은 변을 2 씩 줄인다', () => {
        const spans = resolveCityFootprints([
            { id: 2, level: 7, col: 13, row: 10 }, // 대 5 — 경과 겹친다
            { id: 1, level: 9, col: 10, row: 10 }, // 경 7 — 먼저 잡는다
            { id: 3, level: 11, col: 30, row: 30 }, // 멀리 떨어진 장현
        ]);
        expect(spans.get(1)).toBe(7);
        expect(spans.get(2)).toBe(1); // 5 → 3 도 겹쳐 1
        expect(spans.get(3)).toBe(1);
    });

    it('입력 순서가 달라도 같은 답이다', () => {
        const a = [{ id: 5, level: 7, col: 0, row: 0 }, { id: 6, level: 7, col: 3, row: 0 }];
        const x = resolveCityFootprints(a);
        const y = resolveCityFootprints([...a].reverse());
        expect([...x.entries()].sort()).toEqual([...y.entries()].sort());
        expect(x.get(5)).toBe(5); // 같은 변이면 번호가 작은 쪽이 먼저
        expect(x.get(6)).toBe(1);
    });

    it('한 칸이면 마커 칸 그 자체다', () => {
        expect(cityFootprintBlock(5, 10, 20)).toEqual({ col0: 10, row0: 20, span: 1 });
    });
});
