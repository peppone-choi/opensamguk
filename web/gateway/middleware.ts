import { NextRequest, NextResponse } from 'next/server';
import { ACCESS_COOKIE, REFRESH_COOKIE } from '@/lib/cookies';

// 보호 경로: 세션 쿠키(access 또는 refresh)가 없으면 /login으로 보낸다.
// 로그인된 사용자가 /login으로 가면 /lobby로 되돌린다.
// 세밀한 사용자/역할 검증은 서버 컴포넌트(getSession/requireUser/requireAdmin)와
// /api/auth/me(토큰 갱신)가 담당한다 — 미들웨어는 쿠키 존재만 본다(엣지 런타임 가벼움).

// /join, /login은 공개(비로그인 신규 유저가 가입/로그인에 도달해야 함).
const PROTECTED = ['/lobby', '/entrance', '/admin'];

export function middleware(req: NextRequest) {
    const { pathname } = req.nextUrl;
    const hasSession = req.cookies.has(ACCESS_COOKIE) || req.cookies.has(REFRESH_COOKIE);

    if (PROTECTED.some((p) => pathname === p || pathname.startsWith(`${p}/`))) {
        if (!hasSession) {
            const url = req.nextUrl.clone();
            url.pathname = '/login';
            url.searchParams.set('next', pathname);
            return NextResponse.redirect(url);
        }
    }

    if (pathname === '/login' && hasSession) {
        const url = req.nextUrl.clone();
        url.pathname = '/lobby';
        return NextResponse.redirect(url);
    }

    return NextResponse.next();
}

export const config = {
    matcher: ['/lobby/:path*', '/entrance/:path*', '/admin/:path*', '/login'],
};
