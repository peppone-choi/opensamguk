import { NextRequest, NextResponse } from 'next/server';
import { publicWorldEvents } from '@/lib/publicFeeds';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 천하 정세 공개 경로 — 해당 서버 game-api 의 공개 사건 피드 /api/world-events(ADR-LITE-069 WORLD 칸)를 읽어
// 공개 필드 허용 목록(lib/publicFeeds)으로 다시 만들어 내보낸다. 옛 /api/server-log(삼모 world-log 문장)를 대신한다(계약판 K5-10).
// - 서버 id 는 등록부(serverRegistry)로만 origin 을 푼다 — 임의 주소를 만들지 않는다. 모르는 id 는 404.
// - game-api 는 자격증명 없이(익명으로) 부른다 — 쿠키 · Authorization · 서비스 토큰을 넘기지 않는다.
// - 문장은 화면이 만든다(@opensamguk/ui worldEventSentence). 성공만 30초 캐시한다.
const MAX_LIMIT = 30;
const PUBLIC_CACHE = 'public, max-age=30';

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    const requested = Number(req.nextUrl.searchParams.get('limit') ?? '5');
    const limit = Number.isFinite(requested) ? Math.min(Math.max(Math.trunc(requested), 1), MAX_LIMIT) : 5;
    let upstream: Response;
    try {
        upstream = await fetch(`${origin}/api/world-events?limit=${limit}`, { cache: 'no-store' });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502, headers: { 'Cache-Control': 'no-store' } });
    }
    if (!upstream.ok) {
        return NextResponse.json({ error: '천하 정세를 불러오지 못했습니다.' }, { status: upstream.status >= 500 ? 502 : upstream.status, headers: { 'Cache-Control': 'no-store' } });
    }
    const body = publicWorldEvents(await upstream.json().catch(() => null));
    if (!body) {
        return NextResponse.json({ error: '천하 정세 응답이 올바르지 않습니다.' }, { status: 502, headers: { 'Cache-Control': 'no-store' } });
    }
    return NextResponse.json(body, { headers: { 'Cache-Control': PUBLIC_CACHE } });
}
