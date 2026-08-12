import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL, GATEWAY_UPSTREAM_TIMEOUT_MS, isGatewayTimeout } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';

// 인증된 일반 프록시 — access 쿠키를 Bearer로 붙여 gateway-api로 포워딩.
// 향후 로비/어드민의 인증 read에 사용. 미인증이면 401.

async function forward(req: NextRequest, path: string[]): Promise<NextResponse> {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;
    if (!access) return NextResponse.json({ error: '인증이 필요합니다.' }, { status: 401 });

    const target = `${GATEWAY_API_URL}/${path.join('/')}${req.nextUrl.search}`;
    const init: RequestInit = {
        method: req.method,
        headers: {
            Authorization: `Bearer ${access}`,
            'Content-Type': req.headers.get('content-type') ?? 'application/json',
        },
        cache: 'no-store',
        signal: AbortSignal.timeout(GATEWAY_UPSTREAM_TIMEOUT_MS),
    };
    if (req.method !== 'GET' && req.method !== 'HEAD') {
        init.body = await req.text();
    }

    try {
        const upstream = await fetch(target, init);
        const body = await upstream.text();
        return new NextResponse(body === '' ? null : body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch (error) {
        if (isGatewayTimeout(error)) {
            return NextResponse.json({ error: '게이트웨이 응답 시간이 초과되었습니다.' }, { status: 504 });
        }
        if (error instanceof Error) {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
        throw error;
    }
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
