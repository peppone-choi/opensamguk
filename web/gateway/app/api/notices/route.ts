import { NextResponse } from 'next/server';
import { GATEWAY_API_URL, GATEWAY_UPSTREAM_TIMEOUT_MS } from '@/lib/server-api';

// 공지 공개 읽기 — gateway-api `GET /notices`(permitAll) 서버사이드 프록시. 로그인 전 화면(01)과 로비(02)가 쓴다.
export async function GET(): Promise<NextResponse> {
    try {
        const upstream = await fetch(`${GATEWAY_API_URL}/notices`, {
            cache: 'no-store',
            signal: AbortSignal.timeout(GATEWAY_UPSTREAM_TIMEOUT_MS),
        });
        const body = await upstream.text();
        return new NextResponse(body === '' ? null : body, {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '공지 서버에 연결할 수 없습니다.' }, { status: 502 });
    }
}
