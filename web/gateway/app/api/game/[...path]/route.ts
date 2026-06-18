import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { getServers, resolveGameApiOrigin } from '@/lib/serverRegistry';

const SERVER_COOKIE = 'sam_server';

export const dynamic = 'force-dynamic';

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

    const upstream = await fetch(target, init);
    const body = await upstream.text();
    return new NextResponse(body, {
        status: upstream.status,
        headers: {
            'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
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
