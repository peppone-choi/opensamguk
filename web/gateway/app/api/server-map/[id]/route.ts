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
    if (!server?.gameApiUrl) {
        return NextResponse.json({ error: '서버를 찾을 수 없습니다.' }, { status: 404 });
    }
    try {
        const upstream = await fetch(`${server.gameApiUrl}/api/map/preview`, { cache: 'no-store' });
        const body = await upstream.text();
        return new NextResponse(body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
