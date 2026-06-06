import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';
import { ACCESS_COOKIE } from '@/lib/cookies';

/**
 * K1 진입 — 서버별 fan-out으로 해당 game-api의 `GET /api/server-basic-info`를 호출한다.
 * `/api/server-map/[id]` 패턴과 동일하되, 로그인 사용자의 httpOnly 토큰(sam_access)을 Bearer로
 * 포워딩한다 — 각 서버 game-api가 자기 general_owner로 `me`를 따로 해석하도록(멀티서버 정본).
 * 토큰이 없거나 무효여도 game-api는 200(game{}는 공개, me=null)이므로 로비 행이 깨지지 않는다.
 */
export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }

    const access = (await cookies()).get(ACCESS_COOKIE)?.value;
    const headers: Record<string, string> = {};
    if (access) headers.Authorization = `Bearer ${access}`;

    try {
        const upstream = await fetch(`${origin}/api/server-basic-info`, { cache: 'no-store', headers });
        const body = await upstream.text();
        return new NextResponse(body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
