import { NextRequest, NextResponse } from 'next/server';

// 서버 선택 쿠키 — 멀티서버 인게임에서 어느 게임 서버(world)를 보는지. 입장 URL `/game?server=smain`가
// 페이지 로드될 때 이 미들웨어가 쿠키로 고정 → 이후 모든 /api/game 프록시가 이 쿠키로 대상 game-api를
// 고른다(lib/serverRegistry). secret 아님(서버 선택자) — httpOnly 불필요, 클라가 읽어도 무관.
const SERVER_COOKIE = 'sam_server';

// nginx(prod)와 동일한 path serverId 규칙: s로 시작하는 영숫자/언더스코어/하이픈.
// 이 패턴이면 `/game/smain/join` → `/game/join?server=smain`으로 날개(rewrite) + 쿠키 고정.
const PATH_SERVER_ID = /^s[A-Za-z0-9_-]+$/;

function setServerCookie(res: NextResponse, server: string): void {
    res.cookies.set(SERVER_COOKIE, server, {
        path: '/',
        sameSite: 'lax',
        maxAge: 7 * 24 * 60 * 60,
    });
}

export function middleware(req: NextRequest) {
    const { pathname, searchParams } = req.nextUrl;

    // 1) 쿼리 기반 서버 선택 — 기존 동작 유지.
    const queryServer = searchParams.get('server');
    if (queryServer && /^[a-zA-Z0-9_-]+$/.test(queryServer)) {
        const res = NextResponse.next();
        setServerCookie(res, queryServer);
        return res;
    }

    // 2) path 기반 서버 선택 — prod nginx rewrite 없이도 로컬 dev / docker에서 동작.
    //    `/game/smain/join` → `/game/join?server=smain` (쿠키 동시 고정)
    const segments = pathname.split('/');
    if (segments.length >= 3 && segments[1] === 'game') {
        const serverId = segments[2];
        if (PATH_SERVER_ID.test(serverId)) {
            const rest = segments.slice(3).join('/');
            const targetUrl = req.nextUrl.clone();
            targetUrl.pathname = `/game${rest ? `/${rest}` : ''}`;
            targetUrl.searchParams.set('server', serverId);
            const res = NextResponse.rewrite(targetUrl);
            setServerCookie(res, serverId);
            return res;
        }
    }

    return NextResponse.next();
}

// /game 진입(및 하위)에서만 — 입장 시 ?server/path serverId를 쿠키로 심으면 SPA 이동 날개 유지된다.
export const config = {
    matcher: ['/game', '/game/:path*'],
};
