import { cookies } from 'next/headers';
import { ACCESS_COOKIE } from './cookies';
import { GATEWAY_API_URL } from './server-api';

/** Original media is private: never inherit upstream caching or trust client identity. */
export async function readOwnPortrait(part: 'source' | 'crops'): Promise<Response> {
    const access = (await cookies()).get(ACCESS_COOKIE)?.value;
    const headers = { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' };
    if (!access) return Response.json({ error: '로그인이 필요합니다.' }, { status: 401, headers });
    try {
        const upstream = await fetch(`${GATEWAY_API_URL}/auth/account/profile-icon/${part}`, { headers: { Authorization: `Bearer ${access}` }, cache: 'no-store', signal: AbortSignal.timeout(10_000) });
        if (!upstream.ok) {
            const body = await upstream.json().catch(() => null);
            return Response.json({ error: body?.message ?? '보관된 원본을 불러오지 못했습니다.' }, { status: upstream.status, headers });
        }
        return new Response(upstream.body, { headers: { ...headers, 'X-Portrait-Id': upstream.headers.get('X-Portrait-Id') ?? '', 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/octet-stream' } });
    } catch { return Response.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502, headers }); }
}
