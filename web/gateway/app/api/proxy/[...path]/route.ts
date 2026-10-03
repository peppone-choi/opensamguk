import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL, GATEWAY_UPSTREAM_TIMEOUT_MS, isGatewayTimeout } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { adminProxyPath, sanitizeProxyBody } from '@/lib/adminProxy';
import { MAX_JSON_BODY_BYTES, bodyTooLarge, tooLargeResponse } from '@/lib/bodyLimit';

// 운영 콘솔 프록시 — access 쿠키를 Bearer 로 붙여 gateway-api `admin/**` 로 보낸다(경로 허용 목록: lib/adminProxy).
// 허용 밖 경로는 404, 미인증이면 401. 응답은 캐시하지 않고, JSON 의 토큰 필드는 지운다.

const NO_STORE = { 'Cache-Control': 'no-store' } as const;

async function forward(req: NextRequest, path: string[]): Promise<NextResponse> {
    const allowed = adminProxyPath(path);
    if (!allowed) return NextResponse.json({ error: '찾을 수 없습니다.' }, { status: 404, headers: NO_STORE });

    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;
    if (!access) return NextResponse.json({ error: '인증이 필요합니다.' }, { status: 401, headers: NO_STORE });

    const target = `${GATEWAY_API_URL}/${allowed}${req.nextUrl.search}`;
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
        if (bodyTooLarge(req.headers, MAX_JSON_BODY_BYTES)) return tooLargeResponse() as NextResponse;
        init.body = await req.text();
    }

    try {
        const upstream = await fetch(target, init);
        const contentType = upstream.headers.get('content-type');
        const body = sanitizeProxyBody(await upstream.text(), contentType);
        return new NextResponse(body === '' ? null : body, {
            status: upstream.status,
            headers: { 'Content-Type': contentType ?? 'application/json', ...NO_STORE },
        });
    } catch (error) {
        if (isGatewayTimeout(error)) {
            return NextResponse.json({ error: '게이트웨이 응답 시간이 초과되었습니다.' }, { status: 504, headers: NO_STORE });
        }
        if (error instanceof Error) {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502, headers: NO_STORE });
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

// 공지 수정(NoticeControl → PUT /admin/notices/{id}).
export async function PUT(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
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
