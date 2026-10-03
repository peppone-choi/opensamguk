import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { upstreamErrorResponse as surface } from '@/lib/upstreamError';

const ICON_URL = `${GATEWAY_API_URL}/auth/account/profile-icon`;

async function accessToken(): Promise<string | null> {
    return (await cookies()).get(ACCESS_COOKIE)?.value ?? null;
}

export async function POST(req: Request) {
    const access = await accessToken();
    if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });

    if ((req.headers.get('content-type') ?? '').includes('multipart/form-data')) {
        // 업로드: 브라우저 body에서 file·crops part만 추려 재구성한다. 임의 필드(userId/path/imgsvr/URL) 주입을
        // 원천 차단하고, 신원은 오직 httpOnly 쿠키의 Bearer에서만 파생된다.
        const form = await req.formData().catch(() => null);
        const file = form?.get('file');
        if (!(file instanceof File) || file.size === 0) {
            return NextResponse.json({ error: '업로드할 이미지를 선택해주세요.' }, { status: 400 });
        }
        const forward = new FormData();
        forward.append('file', file, file.name);
        const crops = form?.get('crops');
        if (crops !== null && crops !== undefined) {
            if (typeof crops !== 'string' || crops.length > 4096) return NextResponse.json({ error: '자르기 정보가 올바르지 않습니다.' }, { status: 400 });
            forward.append('crops', crops);
        }
        try {
            const upstream = await fetch(ICON_URL, {
                method: 'POST',
                headers: { Authorization: `Bearer ${access}` },
                body: forward,
            });
            const text = await upstream.text();
            if (!upstream.ok) return surface(upstream.status, text, '초상을 올리지 못했습니다.');
            return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    // 삼모 공유 초상 파일명 저장(JSON selectShared)은 계정 화면에서 뺐다(설계서 §2.5 A22–A25) — 이 경로는 원본 올리기만 받는다.
    return NextResponse.json({ error: '이미지 파일을 올려 주세요.' }, { status: 415 });
}

export async function DELETE() {
    const access = await accessToken();
    if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });
    try {
        const upstream = await fetch(ICON_URL, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${access}` },
        });
        if (upstream.ok) return NextResponse.json({ deleted: true });
        return surface(upstream.status, await upstream.text(), '초상을 지우지 못했습니다.');
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}
