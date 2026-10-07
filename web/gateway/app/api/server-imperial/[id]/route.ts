import { publicServerAccess } from '@/lib/publicServerAccess';
import { NextResponse } from 'next/server';
import { publicImperialPresence, readPublicJson } from '@/lib/publicFeeds';
import { resolveGameApiOrigin } from '@/lib/serverRegistry';

// 서버별 황제 소재 공개 경로(계약판 K8-02 · K5-17) — 등록부로 푼 game-api /api/imperial/presence 를 익명으로 읽는다.
// 서버 배지 7필드를 검사하고 화면이 쓰는 3필드(계통 코드 · 계통 이름 · 황제가 있는 城)만 낸다. 쿼리는 넘기지 않는다.
// 200 READY · 200 NOT_SEEDED 만 공개 캐시, 409 STATE_UNAVAILABLE 은 상태 그대로 no-store, 그 밖(짝이 틀린 상태 포함)은 502.
const failure = () =>
    NextResponse.json({ error: '황실 소재를 불러오지 못했습니다.' }, { status: 502, headers: { 'Cache-Control': 'no-store' } });

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const denied = await publicServerAccess(id);
    if (denied) return denied;
    const origin = resolveGameApiOrigin(id);
    if (!origin) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404, headers: { 'Cache-Control': 'no-store' } });
    }
    const upstream = await readPublicJson(`${origin}/api/imperial/presence`, [200, 409]);
    if (upstream.kind !== 'ok') return failure();
    const body = publicImperialPresence(upstream.status, upstream.json);
    if (!body) return failure();
    const unavailable = body.status === 'STATE_UNAVAILABLE';
    return NextResponse.json(body, {
        status: unavailable ? 409 : 200,
        headers: { 'Cache-Control': 'no-store' },
    });
}
