import { NextRequest, NextResponse } from 'next/server';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 맵 프리뷰 프록시 — 해당 서버 game-api의 /api/map/preview로 서버사이드 포워딩
// (브라우저는 게이트웨이 동일출처만 호출 → CORS 불필요). 맵 데이터는 공개(인증 불필요).
// game-api가 10분 캐싱하므로 여기선 단순 패스스루.
export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    // 서버별 내부 game-api origin은 레지스트리가 해석(멀티서버: id마다 다른 주소). 폴백 체인은
    // SERVER_REGISTRY_JSON(prod 멀티) → GAME_API_ORIGIN(단일서버 prod) → servers.json(dev localhost).
    // 단일 env로 모든 서버를 한 game-api에 강제하던 버그를 제거 — 빼섭 등 2번째 스택이 자기 game-api로 간다.
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    try {
        const upstream = await fetch(`${origin}/api/map/preview`, { cache: 'no-store' });
        const body = await upstream.text();
        return new NextResponse(body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
