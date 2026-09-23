import { describe, expect, it } from 'vitest';
import { cityFootprintBlock, cityFootprintSpan } from '../iso/cityFootprint';

describe('성내 칸', () => {
    it('등급이 높은 城 이 넓다 — 경 5 · 특 4 · 대 3 · 중 2 · 영현 2 · 나머지 1', () => {
        expect(cityFootprintSpan(9)).toBe(5); // 경
        expect(cityFootprintSpan(8)).toBe(4); // 특
        expect(cityFootprintSpan(7)).toBe(3); // 대
        expect(cityFootprintSpan(6)).toBe(2); // 중
        expect(cityFootprintSpan(5)).toBe(1); // 소
        expect(cityFootprintSpan(10)).toBe(2); // 영현 — 縣令, 호구 1만 이상
        expect(cityFootprintSpan(11)).toBe(1); // 장현 — 縣 대부분
    });

    it('규모 축과 縣 축은 따로다 — 영현(縣令)이 소(규모)보다 넓을 수 있다', () => {
        // 등급 필드에 두 축이 섞여 있어 숫자 크기로 비교할 수 없다. 표가 그 사실을 지켜야 한다.
        expect(cityFootprintSpan(10)).toBeGreaterThan(cityFootprintSpan(5));
    });

    it('모르는 등급은 한 칸으로 둔다 — 지도를 잡아먹지 않는다', () => {
        expect(cityFootprintSpan(0)).toBe(1);
        expect(cityFootprintSpan(99)).toBe(1);
    });

    it('홀수 변은 마커 칸이 중심이다', () => {
        expect(cityFootprintBlock(7, 10, 20)).toEqual({ col0: 9, row0: 19, span: 3 }); // 대 3
        expect(cityFootprintBlock(9, 10, 20)).toEqual({ col0: 8, row0: 18, span: 5 }); // 경 5
    });

    it('짝수 변은 마커 칸을 왼쪽·위로 삼는다 — 판마다 달라지지 않게', () => {
        expect(cityFootprintBlock(6, 10, 20)).toEqual({ col0: 10, row0: 20, span: 2 }); // 중 2
        expect(cityFootprintBlock(8, 10, 20)).toEqual({ col0: 9, row0: 19, span: 4 }); // 특 4
    });

    it('한 칸이면 마커 칸 그 자체다', () => {
        expect(cityFootprintBlock(5, 10, 20)).toEqual({ col0: 10, row0: 20, span: 1 });
    });
});
