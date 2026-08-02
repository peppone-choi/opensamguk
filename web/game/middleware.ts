import { NextRequest, NextResponse } from 'next/server';

// 서버 선택 쿠키 — 멀티서버 인게임에서 어느 게임 서버(world)를 보는지. 입장 URL `/game?server=pep`가
// 페이지 로드될 때 이 미들웨어가 쿠키로 고정 → 이후 모든 /api/game 프록시가 이 쿠키로 대상 game-api를
// 고른다(lib/serverRegistry). secret 아님(서버 선택자) — httpOnly 불필요, 클라가 읽어도 무관.
const SERVER_COOKIE = 'sam_server';

const PATH_SERVER_ID = /^[a-z0-9]{1,48}$/;
const RESERVED_PATH_SERVER_IDS = new Set([
  'all',
  'main',
  'admin1',
  'admin2',
  'admin5',
  'admin7',
  'admin8',
  'auction',
  'battle-center',
  'betting',
  'board',
  'chief-center',
  'city',
  'coming-soon',
  'diplomacy',
  'generals',
  'global-diplomacy',
  'history',
  'inherit',
  'join',
  'mailbox',
  'map',
  'my',
  'my-boss',
  'my-cities',
  'my-generals',
  'my-nation',
  'nation',
  'nation-betting',
  'nation-finance',
  'npc-control',
  'rankings',
  'register',
  'select-pool',
  'simulator',
  'tournament',
  'tournament-admin',
  'troop',
  'vote',
  'world-log',
]);

function isPublicServerId(serverId: string): boolean {
  return PATH_SERVER_ID.test(serverId) && !RESERVED_PATH_SERVER_IDS.has(serverId);
}

function configuredServerId(): string | undefined {
  const serverId = process.env.SERVER_ID;
  return serverId && isPublicServerId(serverId) ? serverId : undefined;
}

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
  if (queryServer && queryServer === configuredServerId()) {
    const res = NextResponse.next();
    setServerCookie(res, queryServer);
    return res;
  }

  // Only rewrite this instance's SERVER_ID path, so `/game/join` remains an ordinary route.
  const segments = pathname.split('/');
  if (segments.length >= 3 && segments[1] === 'game') {
    const serverId = segments[2];
    if (serverId === configuredServerId()) {
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
