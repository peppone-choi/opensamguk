import { NextRequest, NextResponse } from 'next/server';

// Server-selection cookie: determines which game server (world) the player views in
// a multi-server game. On `/game?server=pep`, this middleware persists the choice;
// every subsequent /api/game request carries it to web/gateway's proxy, which is the
// sole place that resolves it to a game API origin (#516 §5 — web/game no longer has
// its own server registry or /api/game proxy).
// It is a non-secret server selector, so it neither needs httpOnly nor harms clients that read it.
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
  // `PATH_SERVER_ID` excludes hyphens, making this unreachable in practice. Keep it
  // for list consistency (for example, `battle-center`) and to prevent interpreting
  // the v2 experimental namespace as a server ID.
  'v2-lab',
  'vote',
  'court',
  'hand',
  'orders',
  'posts',
  'retinue',
  'siege',
  'supply',
  'war-room',
  'yuedan',
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

// Retired SAMMO routes must return a real HTTP 404 before AuthGate streams a shell.
// This also covers /game/<serverId>/... before the server-path rewrite.
const RETIRED_GAME_PATHS = new Set([
  'admin1', 'admin2', 'admin5', 'admin7', 'admin8',
  'auction', 'battle-plan', 'betting', 'chief-center', 'coming-soon',
  'diplomacy', 'inherit', 'my-boss', 'nation', 'nation-betting',
  'nation-finance', 'npc-control', 'select-pool', 'simulator', 'tournament',
  'tournament-admin', 'troop', 'v2-lab', 'vote',
]);
// 삼모 전용 랭킹 4종(ADR-LITE-049 2026-09-26: 대체 없이 삭제). 첫 조각만으로는 못 막아 rankings 아래를 따로 본다.
const RETIRED_RANKING_PATHS = new Set(['emperor', 'hall-of-fame', 'npcs', 'traffic']);

function isRetiredGamePath(pathname: string): boolean {
  const segments = pathname.split('/');
  if (segments[1] !== 'game') return false;
  const rest = segments[2] === configuredServerId() ? segments.slice(3) : segments.slice(2);
  return RETIRED_GAME_PATHS.has(rest[0]) || (rest[0] === 'rankings' && RETIRED_RANKING_PATHS.has(rest[1]));
}

export function middleware(req: NextRequest) {
  const { pathname, searchParams } = req.nextUrl;

  if (isRetiredGamePath(pathname)) {
    return new NextResponse(null, { status: 404 });
  }

  // Old campaign URLs redirect to the same screen at its domain route.
  const segments = pathname.split('/');
  const oldServerless = segments[1] === 'game' && segments[2] === 'hwiha';
  const oldServerPath = segments[1] === 'game' && isPublicServerId(segments[2] ?? '') && segments[3] === 'hwiha';
  if (oldServerless || oldServerPath) {
    const pathServerId = oldServerPath ? segments[2] : undefined;
    const serverId = pathServerId ?? configuredServerId();
    const slug = segments.slice(oldServerPath ? 4 : 3).filter(Boolean).join('/') || 'war-room';
    const targetUrl = req.nextUrl.clone();
    targetUrl.pathname = `/game/${serverId ? `${serverId}/` : ''}${slug}`;
    if (serverId) targetUrl.searchParams.delete('server');
    const res = NextResponse.redirect(targetUrl, 308);
    if (serverId && serverId === configuredServerId()) setServerCookie(res, serverId);
    return res;
  }

  // 1) Query-based server selection: preserve existing behavior.
  const queryServer = searchParams.get('server');
  if (queryServer && queryServer === configuredServerId()) {
    const res = NextResponse.next();
    setServerCookie(res, queryServer);
    return res;
  }

  // Only rewrite this instance's SERVER_ID path, so `/game/join` remains an ordinary route.
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

// Apply only to /game and its descendants: persisting the entry-time query or path
// server ID in a cookie keeps it across SPA navigation.
export const config = {
  matcher: ['/game', '/game/:path*'],
};
