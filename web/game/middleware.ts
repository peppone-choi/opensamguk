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
  // `PATH_SERVER_ID`가 하이픈을 막으므로 실동작상 도달 불가다. 목록의 일관성을 위해서만 넣는다
  // (이미 `battle-center` 등 하이픈 항목이 있다) — v2 실험 네임스페이스가 serverId로 오해되지 않게.
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

// v2 실험 네임스페이스(OPENSAM-35 / 0A-a)는 `V2_ENABLED=true`일 때만 존재한다.
// 렌더 레이어(`app/game/v2-lab/layout.tsx`의 notFound())는 status 200을 낸다 — `app/game/layout.tsx`가
// AuthGate(client component)를 렌더해 /game/** 서브트리가 클라이언트 경계 안에서 스트리밍되고,
// HTML 셸이 flush된 뒤에야 notFound()가 해소되기 때문이다. 진짜 404를 내려면 렌더 이전인 여기여야 한다.
// (layout의 게이트는 심층방어로 유지 — 미들웨어가 우회돼도 v2 콘텐츠는 렌더되지 않는다.)
function isV2LabPath(pathname: string): boolean {
  const segments = pathname.split('/');
  if (segments[1] !== 'game') return false;
  // `/game/<serverId>/v2-lab`은 아래 rewrite 분기에서 `/game/v2-lab`으로 접힌다. 원시 pathname이 아니라
  // 렌더에 쓰일 실효 경로로 판정해야 이 우회가 막힌다(쿼리 기반 `?server=`는 pathname이 이미 실효 경로다).
  const rest = segments[2] === configuredServerId() ? segments.slice(3) : segments.slice(2);
  return rest[0] === 'v2-lab';
}

export function middleware(req: NextRequest) {
  const { pathname, searchParams } = req.nextUrl;

  if (isV2LabPath(pathname) && process.env.V2_ENABLED !== 'true') {
    return new NextResponse(null, { status: 404 });
  }

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
