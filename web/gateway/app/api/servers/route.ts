import { NextResponse } from 'next/server';
import { readPublicServers } from '@/lib/serverPublication';

export const dynamic = 'force-dynamic';

// 공개 서버 목록(C8 producer) 그대로 — web 은 `{servers}` 로만 감싼다. 원천을 모르면(UNKNOWN) 503 · no-store,
// 빈 목록이나 env 표로 대신하지 않는다(계약판 「K5 → C8 소비 답」).
export async function GET() {
    const list = await readPublicServers();
    if (list.kind === 'unknown') {
        return NextResponse.json({ error: { code: 'SERVER_LIST_UNAVAILABLE' } }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
    return NextResponse.json({ servers: list.servers }, { headers: { 'Cache-Control': 'no-store' } });
}
