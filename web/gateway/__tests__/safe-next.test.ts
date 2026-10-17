// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { safeNextGameUrl, safeNextPath } from '@/lib/safeNext';

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

// 게이트웨이 :3000 · 게임 :3001 을 따로 띄운 로컬 개발 — 게임 공개 주소의 출처 하나만 전체 주소 next 로 받는다.
describe('로그인 뒤 게임으로 — 설정된 게임 출처의 전체 주소만', () => {
    const GATEWAY = 'http://localhost:3000';
    const GAME = 'http://localhost:3001';

    it.each([
        ['http://localhost:3001/game/pep', 'http://localhost:3001/game/pep'],
        ['http://localhost:3001/game/pep/retinue?tab=yuedan', 'http://localhost:3001/game/pep/retinue?tab=yuedan'],
        ['http://localhost:3001/game/pep#top', 'http://localhost:3001/game/pep'],
        ['http://someone@localhost:3001/game/pep', 'http://localhost:3001/game/pep'],
    ])('%s → %s(양성 대조)', (next, want) => {
        expect(safeNextGameUrl(next, GATEWAY, GAME)).toBe(want);
    });

    it('게임 공개 주소에 경로가 있어도 출처로만 맞춘다', () => {
        expect(safeNextGameUrl('https://game.example/game/pep', 'https://gw.example', 'https://game.example/game')).toBe('https://game.example/game/pep');
    });

    it.each([
        [null],
        [''],
        ['/game/pep'],
        ['//localhost:3001/game/pep'],
        ['http://localhost:3002/game/pep'],
        ['https://localhost:3001/game/pep'],
        ['http://other.example/game/pep'],
        ['http://localhost:3001.other.example/game/pep'],
        ['http://localhost:3000/game/pep'],
        ['javascript:alert(1)'],
        ['http://localhost:3001/\\other.example'],
        ['http://localhost:3001/%5Cother.example'],
        ['http://localhost:3001/%0d%0a/x'],
        ['http://localhost:3001/\t/x'],
    ])('%j 는 받지 않는다', (next) => {
        expect(safeNextGameUrl(next, GATEWAY, GAME)).toBeNull();
    });

    it('한 출처(nginx — 게임 주소가 게이트웨이와 같은 출처)면 전체 주소 길은 닫힌다', () => {
        expect(safeNextGameUrl('https://sam.example/game/pep', 'https://sam.example', 'https://sam.example/game')).toBeNull();
        expect(safeNextPath('/game/pep', 'https://sam.example')).toBe('/game/pep');
    });

    it('게임 주소 설정이 깨졌으면 받지 않는다', () => {
        expect(safeNextGameUrl('http://localhost:3001/game/pep', GATEWAY, 'not a url')).toBeNull();
    });
});
