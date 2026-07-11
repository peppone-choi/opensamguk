import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';

export async function POST(req: Request) {
    const access = (await cookies()).get(ACCESS_COOKIE)?.value;
    if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });
    const body = await req.json().catch(() => null);
    if (!body || !Object.prototype.hasOwnProperty.call(body, 'picture')) {
        return NextResponse.json({ error: '전콘 파일명을 입력해주세요.' }, { status: 400 });
    }
    try {
        const upstream = await fetch(`${GATEWAY_API_URL}/auth/account/profile-icon`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        return new NextResponse(await upstream.text(), {
            status: upstream.status,
            headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
        });
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}
