import { NextRequest, NextResponse } from 'next/server';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 전황(제 전황) 프록시 — 해당 서버 game-api의 /api/world-log로 서버사이드 포워딩
// (브라우저는 게이트웨이 동일출처만 호출 → CORS 불필요). 전황 데이터는 공개(인증 불필요).
// 내부 origin은 serverRegistry가 해석(맵 프리뷰와 동일 패턴; 멀티서버 id별 게임-api).
export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    try {
        const upstream = await fetch(`${origin}/api/world-log`, { cache: 'no-store' });
        const body = await upstream.text();
        return new NextResponse(body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
