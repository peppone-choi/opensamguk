import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { resolveGameApiUrl } from '@/lib/serverRegistry';
import { ACCESS_COOKIE } from '@/lib/cookies';

// 멀티서버 선택 쿠키 — middleware가 입장 URL `?server=<id>`에서 심는다.
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
 * 이 프록시는 sam_refresh로 401 재시도를 하지 않는다 — 그 쿠키는 `web/game/lib/cookies.ts`의
 * `REFRESH_PATH='/api/auth'`로 좁혀 심어지므로(의도된 보안 결정, `web/gateway/lib/cookies.ts:14-16`
 * 동일 패턴), 브라우저가 `/api/game/**` 요청에는 애초에 그 쿠키를 실어 보내지 않는다(RFC 6265 §5.1.4
 * path-match). 서버 코드로는 절대 얻을 수 없는 값이라 여기서 재시도해도 항상 no-op이다 — access 만료로
 * 인한 401 복구는 클라이언트(`web/game/lib/api.ts`)가 `/api/auth/me`(sam_refresh가 실제로 도달하는
 * 유일한 경로)를 거쳐 담당한다.
 *
 * SSE(/api/game/sse/turn): 프록시가 즉시 event-stream을 열고 upstream.body를 그대로 스트리밍한다.
 */

// SSE는 무한 스트림이므로 정적 최적화/캐시를 끈다.
export const dynamic = 'force-dynamic';

const encoder = new TextEncoder();
const SSE_HEARTBEAT_MS = 25_000;

function isTurnSsePath(path: string[]): boolean {
    return path.join('/') === 'sse/turn';
}

function sseHeaders(contentType = 'text/event-stream;charset=UTF-8'): HeadersInit {
    return {
        'Content-Type': contentType,
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
    };
}

function streamEventSource(target: string, init: RequestInit): NextResponse {
    const upstreamAbort = new AbortController();
    let heartbeat: ReturnType<typeof setInterval> | null = null;
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    let closed = false;

    const stream = new ReadableStream<Uint8Array>({
        start(controller) {
            const send = (text: string) => {
                if (!closed) controller.enqueue(encoder.encode(text));
            };
            const close = () => {
                if (closed) return;
                closed = true;
                if (heartbeat) clearInterval(heartbeat);
                controller.close();
            };

            send(': proxy-connected\n\n');
            heartbeat = setInterval(() => send(': proxy-hb\n\n'), SSE_HEARTBEAT_MS);

            void (async () => {
                try {
                    const upstream = await fetch(target, { ...init, signal: upstreamAbort.signal });
                    if (!upstream.ok) {
                        send('event: error\ndata: {}\n\n');
                        return;
                    }
                    reader = upstream.body?.getReader() ?? null;
                    if (!reader) return;
                    while (!closed) {
                        const chunk = await reader.read();
                        if (chunk.done) break;
                        if (!closed && chunk.value) controller.enqueue(chunk.value);
                    }
                } catch {
                    send('event: error\ndata: {}\n\n');
                } finally {
                    close();
                }
            })();
        },
        cancel() {
            closed = true;
            if (heartbeat) clearInterval(heartbeat);
            void reader?.cancel();
            upstreamAbort.abort();
        },
    });

    return new NextResponse(stream, { status: 200, headers: sseHeaders() });
}

async function forward(req: NextRequest, path: string[]): Promise<NextResponse> {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;

    const serverId = req.nextUrl.searchParams.get('server') ?? store.get(SERVER_COOKIE)?.value;
    const base = resolveGameApiUrl(serverId)?.replace(/\/+$/, '');
    if (!base) return NextResponse.json({ error: '게임 서버를 찾을 수 없습니다.' }, { status: 503 });

    const searchParams = new URLSearchParams(req.nextUrl.searchParams);
    searchParams.delete('server');
    const search = searchParams.toString();
    const target = `${base}/${path.join('/')}${search ? `?${search}` : ''}`;

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

    if (req.method === 'GET' && isTurnSsePath(path)) {
        return streamEventSource(target, init);
    }

    const upstream = await fetch(target, init);
    const contentType = upstream.headers.get('content-type') ?? 'application/json';

    // SSE: 본문을 버퍼링하지 않고 그대로 흘려보낸다(turnCompleted 이벤트 실시간 전달).
    if (contentType.includes('text/event-stream')) {
        return new NextResponse(upstream.body, {
            status: upstream.status,
            headers: sseHeaders(contentType),
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
