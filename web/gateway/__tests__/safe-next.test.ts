// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { safeNextPath } from '@/lib/safeNext';

const ORIGIN = 'http://gw.test';

describe('로그인 뒤 이동 주소 — 같은 출처 경로만', () => {
    it.each([
        ['/lobby', '/lobby'],
        ['/admin', '/admin'],
        ['/board/posts/7?page=2', '/board/posts/7?page=2'],
        ['/lobby#top', '/lobby'],
    ])('%s → %s(양성 대조)', (next, want) => {
        expect(safeNextPath(next, ORIGIN)).toBe(want);
    });

    it.each([
        [null],
        [''],
        ['lobby'],
        ['//other.example'],
        ['https://other.example/lobby'],
        ['/\\other.example'],
        ['/\\\\other.example'],
        ['/%5Cother.example'],
        ['/%09/other.example'],
        ['/\t/other.example'],
        ['/\n/other.example'],
        ['/%0d%0a/x'],
    ])('%j 는 로비로', (next) => {
        expect(safeNextPath(next, ORIGIN)).toBe('/lobby');
    });
});
