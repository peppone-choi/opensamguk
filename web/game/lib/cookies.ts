import type { NextResponse } from 'next/server';

export const ACCESS_COOKIE = 'sam_access';
export const REFRESH_COOKIE = 'sam_refresh';

const SESSION_MAX_AGE = 7 * 24 * 60 * 60;
const REFRESH_PATH = '/api/auth';
const cookieSecure = process.env.COOKIE_SECURE === 'true';

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
