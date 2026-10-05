import type { NextResponse } from 'next/server';

export const ACCESS_COOKIE = 'sam_access';
export const REFRESH_COOKIE = 'sam_refresh';

const SESSION_MAX_AGE = 7 * 24 * 60 * 60;
const REFRESH_PATH = '/api/auth';
/**
 * Secure 기본값 — 운영 빌드(NODE_ENV=production)는 켠다. COOKIE_SECURE 를 주면 그 값이 이긴다.
 * HTTP 로 뜨는 로컬 · 사내 compose · 시험(next start on http://127.0.0.1, 컨테이너 이름 host)은 COOKIE_SECURE=false 로 끈다
 * (docker-compose.yml · CI 스모크 env). 게이트웨이 lib/cookies.ts 와 같은 규칙이다.
 */
export function cookieSecureFrom(env: { readonly NODE_ENV?: string; readonly COOKIE_SECURE?: string }): boolean {
    if (env.COOKIE_SECURE === 'true') return true;
    if (env.COOKIE_SECURE === 'false') return false;
    return env.NODE_ENV === 'production';
}

const cookieSecure = cookieSecureFrom(process.env);

function opts(maxAge: number, path: string) {
    return {
        httpOnly: true,
        secure: cookieSecure,
        sameSite: 'lax' as const,
        path,
        maxAge,
    };
}

function hideCookieMirrorHeaders(res: NextResponse): void {
    res.headers.delete('x-middleware-set-cookie');
}

export function setAuthCookies(
    res: NextResponse,
    tokens: { accessToken: string; refreshToken: string },
): void {
    res.cookies.set(ACCESS_COOKIE, tokens.accessToken, opts(SESSION_MAX_AGE, '/'));
    res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, opts(SESSION_MAX_AGE, REFRESH_PATH));
    hideCookieMirrorHeaders(res);
}

export function clearAuthCookies(res: NextResponse): void {
    res.cookies.set(ACCESS_COOKIE, '', opts(0, '/'));
    res.cookies.set(REFRESH_COOKIE, '', opts(0, REFRESH_PATH));
    hideCookieMirrorHeaders(res);
}
