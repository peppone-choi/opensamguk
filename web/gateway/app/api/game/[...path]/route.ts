import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { getServers, resolveGameApiOrigin } from '@/lib/serverRegistry';

const SERVER_COOKIE = 'sam_server';

export const dynamic = 'force-dynamic';

const encoder = new TextEncoder();
const SSE_HEARTBEAT_MS = 25_000;

function isEventStream(contentType: string): boolean {
    return contentType.includes('text/event-stream');
}

function isTurnSsePath(path: string[]): boolean {
    return path.join('/') === 'sse/turn';
}

function selectedServerId(raw: string | undefined | null): string | undefined {
    const value = raw?.trim();
    if (value) return value;
    return getServers()[0]?.id;
}

function resolveSelectedGameApiOrigin(serverId: string | undefined | null): string | undefined {
    const selected = selectedServerId(serverId);
    if (selected) return resolveGameApiOrigin(selected) ?? process.env.GAME_API_ORIGIN;
    return process.env.GAME_API_ORIGIN;
}

function sseHeaders(contentType = 'text/event-stream;charset=UTF-8'): HeadersInit {
    return {
        'Content-Type': contentType,
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
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
    const base = resolveSelectedGameApiOrigin(serverId)?.replace(/\/+$/, '');
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

    if (isEventStream(contentType)) {
        return new NextResponse(upstream.body, {
            status: upstream.status,
            headers: sseHeaders(contentType),
        });
    }

    const body = await upstream.text();
    return new NextResponse(body, {
        status: upstream.status,
        headers: {
            'Content-Type': contentType,
        },
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

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
    const { path } = await ctx.params;
    return forward(req, path);
}

export async function DELETE(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
    const { path } = await ctx.params;
    return forward(req, path);
}
