import { NextRequest, NextResponse } from 'next/server';

// 서버 선택 쿠키 — 멀티서버 인게임에서 어느 게임 서버(world)를 보는지. 입장 URL `/game?server=bbae`가
// 페이지 로드될 때 이 미들웨어가 쿠키로 고정 → 이후 모든 /api/game 프록시가 이 쿠키로 대상 game-api를
// 고른다(lib/serverRegistry). secret 아님(서버 선택자) — httpOnly 불필요, 클라가 읽어도 무방.
const SERVER_COOKIE = 'sam_server';

export function middleware(req: NextRequest) {
    const server = req.nextUrl.searchParams.get('server');
    if (!server) return NextResponse.next();
    // 영숫자/언더스코어만 허용(주입 방지). 미지값은 route handler가 기본(main)으로 폴백.
    if (!/^[a-zA-Z0-9_-]+$/.test(server)) return NextResponse.next();
    const res = NextResponse.next();
    res.cookies.set(SERVER_COOKIE, server, {
        path: '/',
        sameSite: 'lax',
        maxAge: 7 * 24 * 60 * 60,
    });
    return res;
}

// /game 진입(및 하위)에서만 — 입장 시 ?server를 쿠키로 심으면 SPA 이동 내내 유지된다.
export const config = {
    matcher: ['/game', '/game/:path*'],
};
