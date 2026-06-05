import { NextRequest, NextResponse } from 'next/server';
import serversData from '@/config/servers.json';

interface ServerEntry {
    id: string;
    gameApiUrl?: string;
}
const SERVERS = serversData.servers as ServerEntry[];

// 서버별 맵 프리뷰 프록시 — 해당 서버 game-api의 /api/map/preview로 서버사이드 포워딩
// (브라우저는 게이트웨이 동일출처만 호출 → CORS 불필요). 맵 데이터는 공개(인증 불필요).
// game-api가 10분 캐싱하므로 여기선 단순 패스스루.
export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
    const { id } = await ctx.params;
    const server = SERVERS.find((s) => s.id === id);
    // game-api origin은 env(GAME_API_ORIGIN, compose가 컨테이너망 주소로 주입)를 먼저 본다 —
    // servers.json의 gameApiUrl은 dev 기본값(localhost:8081)이라 prod 컨테이너 안에서는 자기자신을 가리켜
    // connection refused→502→로비 맵 placeholder 고정이었다(로그인 500/server-api.ts와 동일 클래스 회귀).
    const origin = process.env.GAME_API_ORIGIN ?? server?.gameApiUrl;
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
