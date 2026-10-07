import { publicServerAccess } from '@/lib/publicServerAccess';
import { NextRequest, NextResponse } from 'next/server';
import { publicWorldEvents, readPublicJson } from '@/lib/publicFeeds';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 천하 정세 공개 경로(계약판 K5-10) — 등록부로 푼 그 서버 game-api 의 공개 사건 피드 /api/world-events 를 익명으로 읽어
// 공개 필드 허용 목록으로 다시 만들어 낸다(lib/publicFeeds · C0 인계 · C8 fixture). 문장은 화면이 만든다(worldEventSentence).
// 넘기는 쿼리는 limit(1–30) 하나뿐이다 — 들어온 쿠키 · 인증 · 신원 쿼리는 upstream 으로 가지 않는다.
const MAX_LIMIT = 30;
const DEFAULT_LIMIT = 5;
/** 이 상태는 같은 상태로 알린다(본문은 고정 문구). 그 밖의 실패는 502. */
const PASS_STATUSES = new Set([400, 401, 403, 404, 429]);

const failure = (status: number) =>
    NextResponse.json({ error: '천하 정세를 불러오지 못했습니다.' }, { status, headers: { 'Cache-Control': 'no-store' } });

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const denied = await publicServerAccess(id);
    if (denied) return denied;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404, headers: { 'Cache-Control': 'no-store' } });
    }
    const requested = Number(req.nextUrl.searchParams.get('limit') ?? String(DEFAULT_LIMIT));
    const limit = Number.isFinite(requested) ? Math.min(Math.max(Math.trunc(requested), 1), MAX_LIMIT) : DEFAULT_LIMIT;
    const upstream = await readPublicJson(`${origin}/api/world-events?limit=${limit}`, [200]);
    if (upstream.kind === 'status') return failure(PASS_STATUSES.has(upstream.status) ? upstream.status : 502);
    if (upstream.kind !== 'ok') return failure(502);
    const body = publicWorldEvents(upstream.json, limit);
    if (!body) return failure(502);
    return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } });
}
