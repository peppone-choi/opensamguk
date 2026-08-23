import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/api';

// sam_refresh는 path=/api/auth로 좁혀 심어져 /api/game/** 프록시엔 절대 안 실린다(구조적 계약:
// __tests__/cookie-refresh-path-scope.test.ts) — 그래서 401 복구는 여기, 클라이언트 fetchGame()에서
// sam_refresh가 실제로 도달하는 /api/auth/me를 거쳐 담당한다.
describe('game API client 401 → /api/auth/me refresh retry', () => {
    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('on 401, refreshes via /api/auth/me and retries the original request exactly once', async () => {
        let gameCalls = 0;
        const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
            const url = String(input);
            if (url === '/api/auth/me') {
                return new Response(JSON.stringify({ user: { id: 1 } }), { status: 200 });
            }
            if (url.includes('/api/game/')) {
                gameCalls += 1;
                if (gameCalls === 1) {
                    return new Response(JSON.stringify({ error: 'expired' }), { status: 401 });
                }
                return new Response(JSON.stringify({ ok: true }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            throw new Error(`unexpected fetch ${url}`);
        });

        const result = await api.post<{ ok: boolean }>('/api/select-pool/claim', { name: '장수' });

        expect(result).toEqual({ ok: true });
        expect(gameCalls).toBe(2); // 최초 401 + 재시도 1회
        expect(fetchMock).toHaveBeenCalledTimes(3); // game + /api/auth/me + game 재시도
    });

    it('does not retry a second time when /api/auth/me itself fails — surfaces the original 401', async () => {
        let gameCalls = 0;
        vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
            const url = String(input);
            if (url === '/api/auth/me') {
                return new Response(JSON.stringify({ user: null }), { status: 401 });
            }
            if (url.includes('/api/game/')) {
                gameCalls += 1;
                return new Response(JSON.stringify({ reason: 'expired' }), { status: 401 });
            }
            throw new Error(`unexpected fetch ${url}`);
        });

        await expect(api.post('/api/select-pool/claim', { name: '장수' })).rejects.toThrow('expired');
        expect(gameCalls).toBe(1); // 재시도 없음
    });
});
