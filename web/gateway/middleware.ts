import { NextRequest, NextResponse } from 'next/server';
import { ACCESS_COOKIE, REFRESH_COOKIE } from '@/lib/cookies';
import { checkApiRequest } from '@/lib/requestGuard';

// 보호 경로: 세션 쿠키(access 또는 refresh)가 없으면 /login으로 보낸다.
// 로그인된 사용자가 /login으로 가면 /lobby로 되돌린다.
// 세밀한 사용자/역할 검증은 서버 컴포넌트(getSession/requireUser/requireAdmin)와
// /api/auth/me(토큰 갱신)가 담당한다 — 미들웨어는 쿠키 존재만 본다(엣지 런타임 가벼움).

// /join, /login은 공개(비로그인 신규 유저가 가입/로그인에 도달해야 함). 이미 로그인했으면 둘 다 로비로 보낸다.
// 보호 경로(설계서 M1): 로비 · 계정 · 운영 콘솔. 옛 삼모 /entrance 는 뺐다.
const PROTECTED = ['/lobby', '/account', '/admin'];
const GUEST_ONLY = ['/login', '/join'];

export function middleware(req: NextRequest) {
    const { pathname } = req.nextUrl;

    // `/api/**` 비-GET 요청은 같은 출처 · JSON 본문만 받는다(lib/requestGuard).
    if (pathname.startsWith('/api/')) {
        const refused = checkApiRequest({ method: req.method, pathname, headers: req.headers, host: req.nextUrl.host });
        return refused ? NextResponse.json({ error: refused.error }, { status: refused.status }) : NextResponse.next();
    }
    const hasSession = req.cookies.has(ACCESS_COOKIE) || req.cookies.has(REFRESH_COOKIE);

    if (PROTECTED.some((p) => pathname === p || pathname.startsWith(`${p}/`))) {
        if (!hasSession) {
            const url = req.nextUrl.clone();
            url.pathname = '/login';
            url.searchParams.set('next', pathname);
            return NextResponse.redirect(url);
        }
    }

    if (GUEST_ONLY.includes(pathname) && hasSession) {
        const url = req.nextUrl.clone();
        url.pathname = '/lobby';
        return NextResponse.redirect(url);
    }

    return NextResponse.next();
}

export const config = {
    matcher: ['/lobby/:path*', '/account/:path*', '/admin/:path*', '/login', '/join', '/api/:path*'],
};
