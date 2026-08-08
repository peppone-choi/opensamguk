import { NextRequest, NextResponse } from 'next/server';

// Server-selection cookie: determines which game server (world) the player views in
// a multi-server game. On `/game?server=pep`, this middleware persists the choice;
// every subsequent /api/game proxy uses it to select the game API (`lib/serverRegistry`).
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

// The v2 experimental namespace (OPENSAM-35 / 0A-a) exists only when
// `V2_ENABLED=true`. The rendering-layer `notFound()` in
// `app/game/v2-lab/layout.tsx` can produce HTTP 200 because `app/game/layout.tsx`
// renders the `AuthGate` client component, streams the /game/** subtree within that
// client boundary, and resolves `notFound()` only after the HTML shell flushes.
// Reject it here, before rendering, to guarantee an HTTP 404. Retain the layout gate
// as defense in depth so v2 content cannot render if middleware is bypassed.
function isV2LabPath(pathname: string): boolean {
  const segments = pathname.split('/');
  if (segments[1] !== 'game') return false;
  // The rewrite below folds `/game/<serverId>/v2-lab` into `/game/v2-lab`. Evaluate
  // the effective render path rather than the raw pathname to prevent that bypass;
  // the query-based `?server=` form already has its effective pathname.
  const rest = segments[2] === configuredServerId() ? segments.slice(3) : segments.slice(2);
  return rest[0] === 'v2-lab';
}

export function middleware(req: NextRequest) {
  const { pathname, searchParams } = req.nextUrl;

  if (isV2LabPath(pathname) && process.env.V2_ENABLED !== 'true') {
    return new NextResponse(null, { status: 404 });
  }

  // 1) Query-based server selection: preserve existing behavior.
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

// Apply only to /game and its descendants: persisting the entry-time query or path
// server ID in a cookie keeps it across SPA navigation.
export const config = {
  matcher: ['/game', '/game/:path*'],
};
