import { NextRequest, NextResponse } from 'next/server';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 세력 현황 — 해당 서버 game-api 의 공개 `GET /api/rankings/kingdoms`(국명·색·성 수·장수 수)를 서버사이드 프록시.
// 로그인(01)·로비(02) 우측 「세력 현황」이 쓴다. 인증 불필요, 내부 origin 은 serverRegistry 가 해석(맵 프리뷰와 동일).
export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    try {
        const upstream = await fetch(`${origin}/api/rankings/kingdoms`, { cache: 'no-store' });
        const body = await upstream.text();
        return new NextResponse(body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
