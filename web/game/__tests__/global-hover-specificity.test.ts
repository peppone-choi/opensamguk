// 전역 요소 hover 가 공용 부품(.os-*)을 덮지 않는다(K3, 2026-10-02 — K5 가 게이트웨이에서 찾은 결함과 같은 꼴).
//
// 맨 `button:hover`(특이성 0,1,1)는 `.os-seg__item--on`(0,1,0) 같은 클래스 한 개짜리 규칙보다 세다 — 고른 칸의 청동 배경이 마우스를
// 올리면 bg-active 로 바뀌었다. 그렇다고 통째로 `:where(button:hover)`(0,0,0)로 감싸면 바로 위 맨 요소 규칙 `button { background }`
// (0,0,1)에 져서 클래스 없는 단추의 hover 가 사라진다(#1206 리뷰). 그래서 `button:where(:hover)`(0,0,1) — 맨 요소 규칙은 뒤 순서로
// 이기고 클래스 규칙에는 진다. 실제 모양은 e2e/smoke/parts-lab.spec.ts 「고른 칸은 hover 에도 청동 · 맨 단추는 hover 에 바뀐다」가 본다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const GLOBALS = resolve(__dirname, '../app/globals.css');

/** 맨 위 단계 규칙의 선택자 목록(주석 · @media 머리 · 키프레임 단계 제외). 중첩은 @media 한 겹까지. */
function topSelectors(css: string): string[] {
    const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
    const out: string[] = [];
    const re = /([^{}]+)\{/g;
    for (const m of text.matchAll(re)) {
        const head = m[1].trim();
        if (!head || head.startsWith('@') || /^(from|to|\d+%)$/.test(head)) continue;
        out.push(...head.split(',').map((s) => s.trim()).filter(Boolean));
    }
    return out;
}

/** 조상 없이 요소 하나 + 의사 클래스로만 된 hover 선택자(예: `button:hover`, `a:not(.x):hover`). `:where(...)` 안은 아니다. */
const BARE_ELEMENT_HOVER = /^(?:button|a|input|select|textarea|label|summary)(?::[a-z-]+(?:\([^)]*\))?)*:hover(?::[a-z-]+(?:\([^)]*\))?)*$/;

/** 통째로 감싼 특이성 0 hover(예: `:where(button:hover)`) — 같은 요소의 맨 규칙(0,0,1)에 져서 hover 가 사라진다. */
const ZERO_ELEMENT_HOVER = /^:where\((?:button|a|input|select|textarea|label|summary)\b[^)]*:hover[^)]*\)$/;

describe('전역 요소 hover 는 요소 하나 특이성(0,0,1) — 클래스에는 지고 맨 요소 규칙에는 이긴다', () => {
    it('찾는 식이 맨 요소 hover 는 잡고, :where · 조상 · 클래스 hover 는 잡지 않는다', () => {
        expect(BARE_ELEMENT_HOVER.test('button:hover')).toBe(true);
        expect(BARE_ELEMENT_HOVER.test('a:hover')).toBe(true);
        expect(BARE_ELEMENT_HOVER.test('button:not(:disabled):hover')).toBe(true);
        expect(BARE_ELEMENT_HOVER.test(':where(button:hover)')).toBe(false);
        expect(BARE_ELEMENT_HOVER.test('button:where(:hover)')).toBe(false);
        expect(ZERO_ELEMENT_HOVER.test(':where(button:hover)')).toBe(true);
        expect(ZERO_ELEMENT_HOVER.test(':where(a:hover)')).toBe(true);
        expect(ZERO_ELEMENT_HOVER.test('button:where(:hover)')).toBe(false);
        expect(BARE_ELEMENT_HOVER.test('.error-state button:hover')).toBe(false);
        expect(BARE_ELEMENT_HOVER.test('.os-seg__item:hover')).toBe(false);
        expect(topSelectors('/* a:hover {} */ a, b:hover { x: 1 } @media (x) { button:hover { y: 2 } }')).toEqual(['a', 'b:hover', 'button:hover']);
    });

    it('web/game globals.css 에 맨 요소 hover(0,1,1)도, 통째로 감싼 0 특이성 hover 도 없다', () => {
        const selectors = topSelectors(readFileSync(GLOBALS, 'utf8'));
        expect(selectors.length).toBeGreaterThan(100); // 훑기가 살아 있는지
        expect(selectors.filter((s) => BARE_ELEMENT_HOVER.test(s))).toEqual([]);
        expect(selectors.filter((s) => ZERO_ELEMENT_HOVER.test(s))).toEqual([]);
    });

    it('맨 단추 · 링크 hover 는 같은 특이성의 맨 요소 규칙 뒤에 있다 — 순서로 이겨야 hover 가 보인다', () => {
        const selectors = topSelectors(readFileSync(GLOBALS, 'utf8'));
        for (const [base, hover] of [['button', 'button:where(:hover)'], ['a', 'a:where(:hover)']] as const) {
            expect(selectors).toContain(base);
            expect(selectors).toContain(hover);
            expect(selectors.indexOf(hover)).toBeGreaterThan(selectors.lastIndexOf(base));
        }
    });
});
