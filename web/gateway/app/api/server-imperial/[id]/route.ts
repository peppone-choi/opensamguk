import { NextResponse } from 'next/server';
import { publicImperialPresence } from '@/lib/publicFeeds';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 황제 소재 공개 경로 — game-api /api/imperial/presence(permitAll, 계약판 K8-02 · K5-17)를 읽어
// 화면이 쓰는 칸(상태 · 계통 코드 · 계통 이름 · 황제가 있는 城)만 다시 만들어 내보낸다. 황제 장수 id · 노드 id 는 내보내지 않는다.
// 등록부로만 origin 을 풀고(모르는 id 404), 자격증명 없이 부르며, 성공만 30초 캐시한다. 409 STATE_UNAVAILABLE 은 상태 그대로.
const PUBLIC_CACHE = 'public, max-age=30';

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    let upstream: Response;
    try {
        upstream = await fetch(`${origin}/api/imperial/presence`, { cache: 'no-store' });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502, headers: { 'Cache-Control': 'no-store' } });
    }
    const body = publicImperialPresence(await upstream.json().catch(() => null));
    if (!body || (!upstream.ok && upstream.status !== 409)) {
        return NextResponse.json({ error: '황실 소재를 불러오지 못했습니다.' }, { status: 502, headers: { 'Cache-Control': 'no-store' } });
    }
    const status = body.status === 'STATE_UNAVAILABLE' ? 409 : 200;
    return NextResponse.json(body, { status, headers: { 'Cache-Control': status === 200 ? PUBLIC_CACHE : 'no-store' } });
}
