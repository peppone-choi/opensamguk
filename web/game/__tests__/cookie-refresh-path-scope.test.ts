import { describe, expect, it } from 'vitest';
import { NextResponse } from 'next/server';
import { REFRESH_COOKIE, setAuthCookies } from '@/lib/cookies';

// RFC 6265 §5.1.4 path-match: cookie-path matches request-path iff cookie-path
// is a prefix of request-path AND (they're equal, or cookie-path ends with '/',
// or the char right after the prefix in request-path is '/').
function pathMatches(cookiePath: string, requestPath: string): boolean {
    if (requestPath === cookiePath) return true;
    if (!requestPath.startsWith(cookiePath)) return false;
    return cookiePath.endsWith('/') || requestPath[cookiePath.length] === '/';
}

// 구조적 회귀 계약: sam_refresh 는 path=/api/auth 로 좁혀 심어진다(장기 7일 토큰을 모든
// 동일출처 요청에 노출하지 않으려는 의도된 보안 결정, web/gateway/lib/cookies.ts:14-16 동일 패턴).
// RFC 6265 path-match 상 그 path 는 /api/game/** 의 접두어가 아니므로 브라우저는 그 라우트로
// 가는 요청에 이 쿠키를 절대 싣지 않는다 — 서버 프록시(web/game/app/api/game/[...path]/route.ts)가
// sam_refresh 에 의존하는 401 재시도를 넣으면 그 분기는 항상 죽은 코드다. 이 테스트는 그 계약을
// (a) setAuthCookies 가 실제로 심는 path 값, (b) path-match 규칙 자체 두 가지로 고정한다.
describe('sam_refresh cookie path scope (structural contract)', () => {
    it('setAuthCookies scopes sam_refresh to /api/auth, not /', () => {
        const res = NextResponse.json({});
        setAuthCookies(res, { accessToken: 'a', refreshToken: 'r' });
        expect(res.cookies.get(REFRESH_COOKIE)?.path).toBe('/api/auth');
    });

    it('a /api/auth-scoped cookie never reaches /api/game/** requests', () => {
        expect(pathMatches('/api/auth', '/api/game/select-pool/claim')).toBe(false);
        expect(pathMatches('/api/auth', '/api/game')).toBe(false);
    });

    it('sanity: the same cookie does reach its own /api/auth/** subtree', () => {
        expect(pathMatches('/api/auth', '/api/auth/me')).toBe(true);
        expect(pathMatches('/api/auth', '/api/auth')).toBe(true);
    });
});
