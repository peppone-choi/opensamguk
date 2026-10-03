import type { NextResponse } from 'next/server';
import type { AuthResponse } from './types';

// httpOnly 쿠키에 JWT를 보관한다 — JS에서 토큰을 읽을 수 없게 한다(XSS 토큰 탈취 방지).
export const ACCESS_COOKIE = 'sam_access';
export const REFRESH_COOKIE = 'sam_refresh';

// 쿠키 '수명'은 세션 길이(7일)와 같다. access JWT 자체는 15분 만료이며 만료 검증은
// gateway-api(JwtTokenProvider)가 서버사이드로 수행한다. 쿠키 수명을 토큰 수명과
// 분리해야 미들웨어가 access 쿠키 '존재'만으로 7일 동안 게이팅을 유지할 수 있고
// (JWT 만료 시 /api/auth/me가 refresh로 재발급), 15분마다 강제 로그아웃되지 않는다.
const SESSION_MAX_AGE = 7 * 24 * 60 * 60;

// refresh 쿠키는 /api/auth/{me,refresh}에서만 읽힌다 → 경로를 /api/auth로 좁혀
// 7일짜리 장기 토큰이 모든 동일출처 요청(/api/proxy, 정적 등)에 실려나가지 않게 한다.
const REFRESH_PATH = '/api/auth';

// `Secure` 쿠키는 HTTPS 연결에서만 브라우저에 저장/전송된다. 기본값은 운영 빌드(NODE_ENV=production)에서 켠다 — 운영은
// HTTPS 뒤에 있다. 로컬·사내 docker compose · 시험처럼 HTTP(nginx :80 / localhost:3000 / 컨테이너 이름 host)로 뜨는 운영 빌드는
// Secure 를 켜면 브라우저가 세션 쿠키를 버려 로그인 직후 /login 으로 되돌아가므로 COOKIE_SECURE=false 로 끈다
// (docker-compose.yml · CI 스모크 env 가 그렇게 둔다). COOKIE_SECURE 를 주면 그 값이 이긴다.
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

export function setAuthCookies(res: NextResponse, tokens: Pick<AuthResponse, 'accessToken' | 'refreshToken'>): void {
    res.cookies.set(ACCESS_COOKIE, tokens.accessToken, opts(SESSION_MAX_AGE, '/'));
    res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, opts(SESSION_MAX_AGE, REFRESH_PATH));
}

export function clearAuthCookies(res: NextResponse): void {
    res.cookies.set(ACCESS_COOKIE, '', opts(0, '/'));
    res.cookies.set(REFRESH_COOKIE, '', opts(0, REFRESH_PATH));
}
