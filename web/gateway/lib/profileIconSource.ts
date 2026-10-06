import { cookies } from 'next/headers';
import { ACCESS_COOKIE } from './cookies';
import { GATEWAY_API_URL } from './server-api';

// 내보내는 형식 허용 목록 — 원본은 올릴 수 있는 이미지 형식만, 자르기 정보는 JSON 만. 그 밖이면 내보내지 않는다(502).
const ALLOWED_TYPES: Record<'source' | 'crops', readonly string[]> = {
    source: ['image/jpeg', 'image/png', 'image/webp'],
    crops: ['application/json'],
};

function baseType(contentType: string | null): string {
    return (contentType ?? '').split(';')[0].trim().toLowerCase();
}

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
        const type = baseType(upstream.headers.get('Content-Type'));
        if (!ALLOWED_TYPES[part].includes(type)) {
            await upstream.body?.cancel().catch(() => undefined);
            return Response.json({ error: '보관된 원본의 형식을 확인할 수 없습니다.' }, { status: 502, headers });
        }
        // 화면은 fetch 로만 읽는다. 주소로 직접 열면 내려받기로, 문서로 열려도 스크립트 없이(sandbox).
        return new Response(upstream.body, { headers: { ...headers, 'X-Portrait-Id': upstream.headers.get('X-Portrait-Id') ?? '', 'Content-Type': type, 'Content-Disposition': 'attachment', 'Content-Security-Policy': 'sandbox' } });
    } catch { return Response.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502, headers }); }
}
