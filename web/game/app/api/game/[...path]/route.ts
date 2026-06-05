import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { resolveGameApiUrl } from '@/lib/serverRegistry';
import { ACCESS_COOKIE } from '@/lib/cookies';

// 멀티서버 선택 쿠키 — middleware가 입장 URL `?server=<id>`에서 심는다. 미설정/main → 기본 game-api.
const SERVER_COOKIE = 'sam_server';

/**
 * 동일출처 server-side 프록시 — web/game(:3001)의 모든 game-api 호출이 여기를 통과한다.
 *
 * sam_access 쿠키를 서버사이드로 읽어 Authorization: Bearer로 붙여 game-api(:8081)로 포워딩한다.
 * 쿠키가 없으면 Bearer 없이 그대로 전달(const/global-menu/map/preview 등 public read는 인증 불필요;
 * identity-required 엔드포인트는 game-api가 401을 돌려준다 → 클라이언트에서 처리). game-api는 Bearer로
 * 식별을 해결(W1 GeneralResolver)하므로 ?generalId= 를 주입하지 않는다. JWT는 절대 클라이언트 JS에
 * 노출되지 않는다(httpOnly 쿠키 → 서버 route handler에서만 읽힘).
 *
 * SSE(/api/game/sse/turn): text/event-stream 응답은 버퍼링하지 않고 upstream.body를 그대로 스트리밍한다.
 */

// SSE는 무한 스트림이므로 정적 최적화/캐시를 끈다.
export const dynamic = 'force-dynamic';

async function forward(req: NextRequest, path: string[]): Promise<NextResponse> {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;

    // 선택 서버(sam_server 쿠키) → 해당 game-api. 미선택/main → 기본. 멀티서버 인게임 라우팅.
    const base = resolveGameApiUrl(store.get(SERVER_COOKIE)?.value);
    const target = `${base}/${path.join('/')}${req.nextUrl.search}`;

    const headers: Record<string, string> = {};
    if (access) headers.Authorization = `Bearer ${access}`;

    const init: RequestInit = {
        method: req.method,
        headers,
        cache: 'no-store',
        // @ts-expect-error — Node fetch는 스트리밍 응답에 duplex가 필요(타입에 없음).
        duplex: 'half',
    };

    if (req.method !== 'GET' && req.method !== 'HEAD') {
        const contentType = req.headers.get('content-type');
        if (contentType) headers['Content-Type'] = contentType;
        init.body = await req.text();
    }

    const upstream = await fetch(target, init);
    const contentType = upstream.headers.get('content-type') ?? 'application/json';

    // SSE: 본문을 버퍼링하지 않고 그대로 흘려보낸다(turnCompleted 이벤트 실시간 전달).
    if (contentType.includes('text/event-stream')) {
        return new NextResponse(upstream.body, {
            status: upstream.status,
            headers: {
                'Content-Type': contentType,
                'Cache-Control': 'no-cache, no-transform',
                Connection: 'keep-alive',
            },
        });
    }

    const body = await upstream.text();
    return new NextResponse(body, {
        status: upstream.status,
        headers: { 'Content-Type': contentType },
    });
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
    const { path } = await ctx.params;
    return forward(req, path);
}

export async function POST(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
    const { path } = await ctx.params;
    return forward(req, path);
}
